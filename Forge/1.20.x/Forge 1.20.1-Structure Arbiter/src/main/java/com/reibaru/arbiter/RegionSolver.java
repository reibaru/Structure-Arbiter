package com.reibaru.arbiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;


public final class RegionSolver {

    private static final int CANDIDATES = 8;
    private static final int CACHE_LIMIT = 200_000;

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
        for (Map.Entry<StructureIntent, Optional<Placement>> e : memo.entrySet()) {
            cache.putIfAbsent(e.getKey(), e.getValue());
        }
        return result;
    }

    public List<Placement> conflictingAtOriginal(StructureIntent intent) {
        List<Placement> out = new ArrayList<>();
        for (Placement p : higherRankedPlacements(intent, new HashMap<>())) {
            if (conflicts(intent.box(), intent.margin(), p.placed(), p.intent().margin())) out.add(p);
        }
        return out;
    }

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
        int r = intent.reach() + source.maxNeighborReach();
        Box3 mine = b.inflateXZ(intent.reach());
        String myId = intent.id();
        int myPriority = intent.priority();
        BiPredicate<String, Integer> wanted =
                (id, priority) -> !id.equals(myId) && isHigher(priority, id, myPriority, myId);

        List<Placement> out = new ArrayList<>();
        for (StructureIntent n : source.intentsNear(seed, b.minX() - r, b.minZ() - r, b.maxX() + r, b.maxZ() + r,
                wanted)) {
            if (!wanted.test(n.id(), n.priority())) continue;
            if (!n.box().inflateXZ(n.reach()).intersects(mine)) continue;
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
