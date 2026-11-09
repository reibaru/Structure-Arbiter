package com.reibaru.arbiter.mc;

import com.reibaru.arbiter.Box3;
import com.reibaru.arbiter.IntentSource;
import com.reibaru.arbiter.Placement;
import com.reibaru.arbiter.RegionSolver;
import com.reibaru.arbiter.StructureIntent;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * /arbiter コマンド（OP権限レベル2）。
 *
 *   /arbiter debug on|off      ログ出力（棄却・移動）のオン/オフ
 *   /arbiter verbose on|off    通過（問題なし）や先読みのログも出す
 *   /arbiter chat on|off       OPのチャットにも通知する
 *   /arbiter stats             判定件数・時間・先読みコスト・棄却が多い構造物
 *   /arbiter recent [n]        直近のイベント（最大50件）
 *   /arbiter here              今いるリージョンの構造物の判定結果一覧（クリックでTP）
 *   /arbiter reset             統計と履歴をリセット
 */
public final class ArbiterCommands {

    /** /arbiter here で調べる範囲（ブロック）。初回は先読みの生成が走るので少し待つことがある。 */
    private static final int HERE_RADIUS = 192;

    private ArbiterCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("arbiter")
                .requires(s -> s.hasPermission(2))
                .then(toggle("debug", v -> ArbiterDebug.enabled = v, () -> ArbiterDebug.enabled))
                .then(toggle("verbose", v -> ArbiterDebug.verbose = v, () -> ArbiterDebug.verbose))
                .then(toggle("chat", v -> ArbiterDebug.chat = v, () -> ArbiterDebug.chat))
                .then(Commands.literal("stats").executes(c -> {
                    say(c, ArbiterDebug.statsText());
                    return 1;
                }))
                .then(Commands.literal("recent")
                        .executes(c -> recent(c, 10))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 50))
                                .executes(c -> recent(c, IntegerArgumentType.getInteger(c, "n")))))
                .then(Commands.literal("here").executes(ArbiterCommands::here))
                .then(Commands.literal("reset").executes(c -> {
                    ArbiterDebug.reset();
                    say(c, "[Arbiter] 統計と履歴をリセットしました");
                    return 1;
                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Consumer<Boolean> set,
                                                                     Supplier<Boolean> get) {
        return Commands.literal(name)
                .executes(c -> {
                    say(c, "[Arbiter] " + name + " = " + (get.get() ? "on" : "off"));
                    return 1;
                })
                .then(Commands.literal("on").executes(c -> {
                    set.accept(true);
                    say(c, "[Arbiter] " + name + " = on");
                    return 1;
                }))
                .then(Commands.literal("off").executes(c -> {
                    set.accept(false);
                    say(c, "[Arbiter] " + name + " = off");
                    return 1;
                }));
    }

    private static int recent(CommandContext<CommandSourceStack> c, int n) {
        List<String> lines = ArbiterDebug.recentLines(n);
        if (lines.isEmpty()) {
            say(c, "[Arbiter] 履歴なし（/arbiter debug on にしてから生成すると記録されます）");
        } else {
            say(c, String.join("\n", lines));
        }
        return lines.size();
    }

    /** 今いるリージョンの全intentについて、判定結果を距離順に表示する。 */
    private static int here(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        if (!(gen instanceof ArbiterAccess acc) || acc.arbiter$getSolver() == null) {
            say(c, "[Arbiter] このディメンションではまだ構造物生成が走っていません（調停の準備前）");
            return 0;
        }
        RegionSolver solver = acc.arbiter$getSolver();
        IntentSource source = acc.arbiter$getSource();

        Vec3 pos = src.getPosition();
        int px = (int) Math.floor(pos.x);
        int pz = (int) Math.floor(pos.z);

        record Row(StructureIntent intent, Optional<Placement> result, double dist) {}
        List<Row> rows = new ArrayList<>();
        for (StructureIntent in : source.intentsNear(acc.arbiter$getSeed(),
                px - HERE_RADIUS, pz - HERE_RADIUS, px + HERE_RADIUS, pz + HERE_RADIUS)) {
            Box3 b = in.box();
            double dx = (b.minX() + b.maxX()) / 2.0 - pos.x;
            double dz = (b.minZ() + b.maxZ()) / 2.0 - pos.z;
            rows.add(new Row(in, solver.decide(in), Math.sqrt(dx * dx + dz * dz)));
        }
        rows.sort(Comparator.comparingDouble(Row::dist));

        long ok = rows.stream().filter(r -> r.result().isPresent() && !r.result().get().moved()).count();
        long mv = rows.stream().filter(r -> r.result().isPresent() && r.result().get().moved()).count();
        long rej = rows.stream().filter(r -> r.result().isEmpty()).count();
        say(c, String.format("[Arbiter] 周囲 ±%d ブロック: intent %d 件（通過 %d / 移動 %d / 棄却 %d）。近い順に最大15件",
                HERE_RADIUS, rows.size(), ok, mv, rej));

        for (Row r : rows.subList(0, Math.min(15, rows.size()))) {
            Box3 b = r.intent().box();
            int cx = (b.minX() + b.maxX()) / 2;
            int cz = (b.minZ() + b.maxZ()) / 2;
            String tag;
            ChatFormatting color;
            String hover;
            if (r.result().isEmpty()) {
                tag = "REJECT";
                color = ChatFormatting.RED;
                hover = "邪魔したもの: " + ArbiterDebug.describeBlockers(solver.conflictingAtOriginal(r.intent()));
            } else if (r.result().get().moved()) {
                tag = "MOVED";
                color = ChatFormatting.YELLOW;
                hover = "移動量 (" + r.result().get().dx() + "," + r.result().get().dz() + ") 邪魔したもの: "
                        + ArbiterDebug.describeBlockers(solver.conflictingAtOriginal(r.intent()));
            } else {
                tag = "OK";
                color = ChatFormatting.GREEN;
                hover = "無移動で通過";
            }
            String line = String.format("[%s] %s (%d, %d) %dm", tag, r.intent().id(), cx, cz, (int) r.dist());
            MutableComponent msg = Component.literal(line).withStyle(s -> s
                    .withColor(color)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/tp @s " + cx + " " + (b.maxY() + 2) + " " + cz))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
            src.sendSuccess(() -> msg, false);
        }
        return rows.size();
    }

    private static void say(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendSuccess(() -> Component.literal(text), false);
    }
}
