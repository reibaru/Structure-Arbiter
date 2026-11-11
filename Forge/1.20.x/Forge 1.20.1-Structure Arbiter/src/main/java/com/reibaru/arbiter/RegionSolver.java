package com.reibaru.arbiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * 決定的な調停ソルバ。
 *
 * 核になる性質:
 *   decide(intent) は「intent本体＋シード＋IntentSource」だけで決まる純粋関数。
 *   誰が・どの順で・どのリージョンから問い合わせても同じ答えを返す。
 *
 * 規則（priority降順→id順の順位を入れた貪欲法）:
 *   0. 同じ構造物（同じid）同士は調停しない。その配置規則（spacing/separation）はMOD側に任せる。
 *      密集して小さい構造物（モンスタールーム系など）が自己衝突で大量に棄却されるのを防ぐ
 *   1. intent X に対し、X と衝突しうる「より高順位」のintent Hを近傍から集める
 *   2. 各Hの確定配置を再帰的に decide(H) で求める（順位は単調に下がるので必ず停止する）
 *   3. X は 希望位置 → シード由来の決定的な移動候補列 の順に試し、
 *      確定済みHの配置と衝突しない最初の候補を採用。全滅なら棄却
 *
 * 既知の限界: 再帰は衝突グラフを順位の高い方向にたどる。構造物が疎なら連鎖は数個で終わるが、
 * 高密度だと深くなる。その場合はMOD側のspacing設定かregion/margin設計で密度を下げること。
 */
public final class RegionSolver {

    private static final int CANDIDATES = 8;
    private static final int CACHE_LIMIT = 200_000;

    /**
     * 順位: priority が大きい方が上、同じなら id の辞書順が小さい方が上。
     * id と priority だけで決まる（生成前に判定できる）のが肝。
     * 同じ id 同士は比較しない（下の higherRankedPlacements で除外する）ので、全順序として十分。
     */
    static boolean isHigher(int priorityA, String idA, int priorityB, String idB) {
        return priorityA != priorityB ? priorityA > priorityB : idA.compareTo(idB) < 0;
    }

    private final long seed;
    private final IntentSource source;
    private final Map<StructureIntent, Optional<Placement>> cache = new ConcurrentHashMap<>();

    public RegionSolver(long seed, IntentSource source) {
        this.seed = seed;
        this.source = source;
    }

    public Optional<Placement> decide(StructureIntent intent) {
        Optional<Placement> cached = cache.get(intent);
        if (cached != null) return cached;

        Map<StructureIntent, Optional<Placement>> memo = new HashMap<>();
        Optional<Placement> result = decide(intent, memo);

        if (cache.size() > CACHE_LIMIT) cache.clear();
        // memoの中身も純粋関数の結果なので共有キャッシュに入れてよい
        for (Map.Entry<StructureIntent, Optional<Placement>> e : memo.entrySet()) {
            cache.putIfAbsent(e.getKey(), e.getValue());
        }
        return result;
    }

    /**
     * デバッグ用: 希望位置のままだと衝突する「より高順位の確定配置」の一覧。
     * 無移動で通ったintentでは空、移動・棄却されたintentでは必ず1件以上になる。
     */
    public List<Placement> conflictingAtOriginal(StructureIntent intent) {
        List<Placement> out = new ArrayList<>();
        for (Placement p : higherRankedPlacements(intent, new HashMap<>())) {
            if (conflicts(intent.box(), intent.margin(), p.placed(), p.intent().margin())) out.add(p);
        }
        return out;
    }

    /** 2つの配置が衝突するか。ソルバとテストで同じ述語を使う。 */
    public static boolean conflicts(Box3 a, int marginA, Box3 b, int marginB) {
        return a.inflateXZ(Math.max(marginA, marginB)).intersects(b);
    }

    private Optional<Placement> decide(StructureIntent intent, Map<StructureIntent, Optional<Placement>> memo) {
        Optional<Placement> m = memo.get(intent);
        if (m != null) return m;
        Optional<Placement> g = cache.get(intent);
        if (g != null) return g;

        List<Placement> blockers = higherRankedPlacements(intent, memo);

        Optional<Placement> result = Optional.empty();
        for (int k = 0; k <= CANDIDATES; k++) {
            if (k > 0 && intent.maxShift() == 0) break;
            int dx = 0, dz = 0;
            if (k > 0) {
                int[] off = shiftOffset(intent, k);
                dx = off[0];
                dz = off[1];
            }
            Box3 cand = intent.box().translate(dx, 0, dz);
            if (!conflictsWithAny(intent, cand, blockers)) {
                result = Optional.of(new Placement(intent, cand, dx, dz));
                break;
            }
        }
        memo.put(intent, result);
        return result;
    }

    private List<Placement> higherRankedPlacements(StructureIntent intent, Map<StructureIntent, Optional<Placement>> memo) {
        Box3 b = intent.box();
        // n.box を n.reach() 広げたものが intent.box を reach() 広げたものと交わる  ⇒  n.box は下の矩形と交わる
        int r = intent.reach() + source.maxNeighborReach();
        Box3 mine = b.inflateXZ(intent.reach());
        String myId = intent.id();
        int myPriority = intent.priority();
        // 生成前に判定できる条件で絞る: 自分と同じ構造物は対象外、順位が下の構造物も対象外
        BiPredicate<String, Integer> wanted =
                (id, priority) -> !id.equals(myId) && isHigher(priority, id, myPriority, myId);

        List<Placement> out = new ArrayList<>();
        for (StructureIntent n : source.intentsNear(seed, b.minX() - r, b.minZ() - r, b.maxX() + r, b.maxZ() + r,
                wanted)) {
            // ソースが絞り込みを守らなくても結果が変わらないよう、ここでも同じ条件で判定する
            if (!wanted.test(n.id(), n.priority())) continue;
            if (!n.box().inflateXZ(n.reach()).intersects(mine)) continue; // 移動しても届かない
            decide(n, memo).ifPresent(out::add);
        }
        return out;
    }

    private static boolean conflictsWithAny(StructureIntent intent, Box3 cand, List<Placement> blockers) {
        for (Placement p : blockers) {
            if (conflicts(cand, intent.margin(), p.placed(), p.intent().margin())) return true;
        }
        return false;
    }

    /** k番目の移動候補。距離は maxShift*k/CANDIDATES、向きはシード由来。StrictMathでJVM間も同一結果。 */
    private int[] shiftOffset(StructureIntent intent, int k) {
        long h = mix(seed ^ 0x9E3779B97F4A7C15L);
        h = mix(h ^ intent.id().hashCode());
        h = mix(h ^ ((long) intent.box().minX() << 32 | (intent.box().minZ() & 0xFFFFFFFFL)));
        h = mix(h ^ k);
        SplittableRandom rng = new SplittableRandom(h);
        double angle = rng.nextDouble() * 2.0 * StrictMath.PI;
        int dist = Math.max(1, (int) StrictMath.round((double) intent.maxShift() * k / CANDIDATES));
        int dx = (int) StrictMath.round(StrictMath.cos(angle) * dist);
        int dz = (int) StrictMath.round(StrictMath.sin(angle) * dist);
        return new int[] {dx, dz};
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
