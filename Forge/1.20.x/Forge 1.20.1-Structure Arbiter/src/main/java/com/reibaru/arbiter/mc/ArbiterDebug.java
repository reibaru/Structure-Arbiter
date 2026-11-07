package com.reibaru.arbiter.mc;

import com.reibaru.arbiter.ArbiterMod;
import com.reibaru.arbiter.Box3;
import com.reibaru.arbiter.Placement;
import com.reibaru.arbiter.RegionSolver;
import com.reibaru.arbiter.StructureIntent;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * デバッグの窓口。ログ・統計・直近イベントの履歴・チャット通知をここに集約する。
 *
 * 統計カウンタは常に動く（負荷は無視できる程度）。
 * ログ出力・履歴・チャット通知は enabled のときだけ動く。理由の取得（邪魔した構造物の特定）は
 * 再計算が入るので、棄却/移動のときかつ enabled のときだけ行う。
 *
 * 切り替え: /arbiter debug|verbose|chat on|off  （起動時は -Darbiter.debug=true でも有効化できる）
 */
public final class ArbiterDebug {

    public enum Kind { ACCEPT, MOVE, REJECT }

    public record Event(long timeMillis, Kind kind, String id, int chunkX, int chunkZ, Box3 box, String detail) {}

    // ---- 切り替え
    public static volatile boolean enabled = Boolean.getBoolean("arbiter.debug");
    /** 通過（問題なし）のログも出す。多いので普段は off。 */
    public static volatile boolean verbose = false;
    /** OP権限のプレイヤーにチャットでも通知する。 */
    public static volatile boolean chat = false;

    private static volatile MinecraftServer server;

    // ---- 統計
    private static final AtomicLong evaluated = new AtomicLong();
    private static final AtomicLong accepted = new AtomicLong();
    private static final AtomicLong moved = new AtomicLong();
    private static final AtomicLong rejected = new AtomicLong();
    private static final AtomicLong passthrough = new AtomicLong();
    private static final AtomicLong decideNanos = new AtomicLong();
    private static final AtomicLong decideMaxNanos = new AtomicLong();
    private static final AtomicLong lookaheadGenerates = new AtomicLong();
    private static final AtomicLong lookaheadNanos = new AtomicLong();
    private static final AtomicLong biomeSkips = new AtomicLong();
    private static final Map<String, LongAdder> resolveNanosById = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> resolveCountById = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> slowWarnedById = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> rejectedById = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> movedById = new ConcurrentHashMap<>();

    // ---- 直近イベント
    private static final int MAX_RECENT = 200;
    private static final ConcurrentLinkedDeque<Event> recent = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger recentSize = new AtomicInteger();

    private ArbiterDebug() {}

    public static void setServer(MinecraftServer s) {
        server = s;
    }

    // ---------------------------------------------------------------- 記録（Mixin/先読みから呼ぶ）

    /** 調停の結果を記録する。nanos は decide() にかかった時間。 */
    public static void onDecision(RegionSolver solver, StructureIntent intent, int chunkX, int chunkZ,
                                  Optional<Placement> result, long nanos) {
        evaluated.incrementAndGet();
        decideNanos.addAndGet(nanos);
        decideMaxNanos.accumulateAndGet(nanos, Math::max);

        long decideMs = nanos / 1_000_000;
        if (decideMs >= ArbiterConfig.slowDecideMs()) {
            // debugの設定に関係なく出す。先読みが重いときにすぐ気づけるように
            ArbiterMod.LOGGER.warn("[Arbiter] SLOW decision: {} chunk[{},{}] の判定に {} ms かかった"
                    + "（/arbiter stats で先読みが重い構造物を確認できる）", intent.id(), chunkX, chunkZ, decideMs);
        }

        Kind kind = result.isEmpty() ? Kind.REJECT : (result.get().moved() ? Kind.MOVE : Kind.ACCEPT);
        switch (kind) {
            case ACCEPT -> accepted.incrementAndGet();
            case MOVE -> {
                moved.incrementAndGet();
                movedById.computeIfAbsent(intent.id(), k -> new LongAdder()).increment();
            }
            case REJECT -> {
                rejected.incrementAndGet();
                rejectedById.computeIfAbsent(intent.id(), k -> new LongAdder()).increment();
            }
        }

        if (!enabled) return;
        if (kind == Kind.ACCEPT && !verbose) return;

        String detail = "";
        if (kind != Kind.ACCEPT) {
            detail = "blockedBy=" + describeBlockers(solver.conflictingAtOriginal(intent));
            if (kind == Kind.MOVE) {
                Placement p = result.get();
                detail += " shift=(" + p.dx() + "," + p.dz() + ")";
            }
        }
        detail += String.format(" prio=%d margin=%d shift<=%d %.2fms",
                intent.priority(), intent.margin(), intent.maxShift(), nanos / 1_000_000.0);

        emit(new Event(System.currentTimeMillis(), kind, intent.id(), chunkX, chunkZ, intent.box(), detail));
    }

    /** 調停できずバニラ挙動のまま通した（初期化前など）。 */
    public static void onPassthrough(String id) {
        passthrough.incrementAndGet();
        if (enabled && verbose) {
            ArbiterMod.LOGGER.info("[Arbiter] PASSTHROUGH {} (調停の準備前)", id);
        }
    }

    /** 先読みで Structure#generate を1回実行した。重い構造物を特定するため、IDごとに時間を集計する。 */
    public static void onResolve(String id, long nanos) {
        lookaheadGenerates.incrementAndGet();
        lookaheadNanos.addAndGet(nanos);
        resolveNanosById.computeIfAbsent(id, k -> new LongAdder()).add(nanos);
        resolveCountById.computeIfAbsent(id, k -> new LongAdder()).increment();

        long ms = nanos / 1_000_000;
        if (ms >= ArbiterConfig.slowResolveMs()) {
            // 構造物ごとに最初の数回だけ警告（debugの設定に関係なく出す）
            int n = slowWarnedById.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            if (n <= 3) {
                ArbiterMod.LOGGER.warn("[Arbiter] SLOW lookahead: {} の生成に {} ms かかった（{} 回目）。"
                        + "重い場合は excludedNamespaces / excludedStructures で外せる", id, ms, n);
            }
        }
    }

    /** バイオームが合わないので、重い生成をせずに捨てた。 */
    public static void onPrefilterBiome() {
        biomeSkips.incrementAndGet();
    }

    // ---------------------------------------------------------------- 表示（コマンドから呼ぶ）

    public static String statsText() {
        long n = evaluated.get();
        double avgMs = n == 0 ? 0 : decideNanos.get() / (double) n / 1_000_000.0;
        double maxMs = decideMaxNanos.get() / 1_000_000.0;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[Arbiter] 判定 %d 件: 通過 %d / 移動 %d / 棄却 %d (素通し %d)%n",
                n, accepted.get(), moved.get(), rejected.get(), passthrough.get()));
        sb.append(String.format("  判定時間: 平均 %.2f ms / 最大 %.2f ms%n", avgMs, maxMs));
        long gen = lookaheadGenerates.get();
        sb.append(String.format("  先読み: generate %d 回 / 合計 %.0f ms (平均 %.2f ms) / バイオームで省略 %d 回%n",
                gen, lookaheadNanos.get() / 1_000_000.0,
                gen == 0 ? 0.0 : lookaheadNanos.get() / (double) gen / 1_000_000.0, biomeSkips.get()));
        sb.append("  先読みが重い構造物(累計): ").append(topResolve(5)).append('\n');
        sb.append("  棄却が多い構造物: ").append(top(rejectedById, 5)).append('\n');
        sb.append("  移動が多い構造物: ").append(top(movedById, 5)).append('\n');
        sb.append(String.format("  debug=%s verbose=%s chat=%s", onOff(enabled), onOff(verbose), onOff(chat)));
        return sb.toString();
    }

    public static List<String> recentLines(int n) {
        List<Event> all = new ArrayList<>(recent);
        int from = Math.max(0, all.size() - n);
        List<String> out = new ArrayList<>();
        for (Event e : all.subList(from, all.size())) out.add(format(e));
        return out;
    }

    public static void reset() {
        for (AtomicLong a : new AtomicLong[] {evaluated, accepted, moved, rejected, passthrough, decideNanos,
                decideMaxNanos, lookaheadGenerates, lookaheadNanos, biomeSkips}) {
            a.set(0);
        }
        rejectedById.clear();
        movedById.clear();
        resolveNanosById.clear();
        resolveCountById.clear();
        slowWarnedById.clear();
        recent.clear();
        recentSize.set(0);
    }

    public static String format(Event e) {
        Box3 b = e.box();
        return String.format("[Arbiter] %s %s chunk[%d,%d] box(%d,%d,%d)-(%d,%d,%d) %s",
                e.kind(), e.id(), e.chunkX(), e.chunkZ(),
                b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), e.detail());
    }

    public static String describeBlockers(List<Placement> blockers) {
        if (blockers.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Placement p : blockers) {
            if (shown++ == 3) {
                sb.append(" +").append(blockers.size() - 3).append(" more");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            Box3 b = p.placed();
            sb.append(p.intent().id()).append("(prio ").append(p.intent().priority()).append(")@(")
                    .append((b.minX() + b.maxX()) / 2).append(',').append((b.minZ() + b.maxZ()) / 2).append(')');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- internal

    private static void emit(Event e) {
        recent.addLast(e);
        if (recentSize.incrementAndGet() > MAX_RECENT) {
            recent.pollFirst();
            recentSize.decrementAndGet();
        }
        String line = format(e);
        ArbiterMod.LOGGER.info(line);
        broadcast(line);
    }

    private static void broadcast(String line) {
        MinecraftServer s = server;
        if (!chat || s == null) return;
        // ワールド生成スレッドからは直接送らず、サーバースレッドに渡す
        s.execute(() -> {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                if (p.hasPermissions(2)) p.sendSystemMessage(Component.literal(line));
            }
        });
    }

    private static String top(Map<String, LongAdder> m, int limit) {
        if (m.isEmpty()) return "なし";
        List<Map.Entry<String, LongAdder>> es = new ArrayList<>(m.entrySet());
        es.sort(Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum()).reversed());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, es.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(es.get(i).getKey()).append(" x").append(es.get(i).getValue().sum());
        }
        return sb.toString();
    }

    /** 先読みの累計時間が大きい順。"id 合計ms (回数, 平均ms)" */
    private static String topResolve(int limit) {
        if (resolveNanosById.isEmpty()) return "なし";
        List<Map.Entry<String, LongAdder>> es = new ArrayList<>(resolveNanosById.entrySet());
        es.sort(Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum()).reversed());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, es.size()); i++) {
            if (i > 0) sb.append(", ");
            String id = es.get(i).getKey();
            double totalMs = es.get(i).getValue().sum() / 1_000_000.0;
            LongAdder count = resolveCountById.get(id);
            long n = count == null ? 0 : count.sum();
            sb.append(String.format("%s %.0fms (%d回, 平均%.1fms)", id, totalMs, n, n == 0 ? 0.0 : totalMs / n));
        }
        return sb.toString();
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
