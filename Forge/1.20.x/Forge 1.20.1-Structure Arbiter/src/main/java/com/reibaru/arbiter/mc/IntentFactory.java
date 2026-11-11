package com.reibaru.arbiter.mc;

import com.reibaru.arbiter.Box3;
import com.reibaru.arbiter.StructureIntent;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class IntentFactory {

    public static final int DEFAULT_PRIORITY = 0;

    private static final Map<String, int[]> OVERRIDES = new ConcurrentHashMap<>();

    private IntentFactory() {}

    public static void override(String id, int priority, int margin, int maxShift) {
        OVERRIDES.put(id, new int[] {priority, margin, maxShift});
    }

    public static int priorityOf(String id) {
        int[] o = OVERRIDES.get(id);
        return o != null ? o[0] : DEFAULT_PRIORITY;
    }

    public static int maxNeighborReach() {
        int max = ArbiterConfig.defaultMargin() + ArbiterConfig.defaultMaxShift();
        for (int[] o : OVERRIDES.values()) max = Math.max(max, o[1] + o[2]);
        return max;
    }

    public static StructureIntent fromBox(ResourceLocation id, BoundingBox bb) {
        String key = id.toString();
        int[] o = OVERRIDES.get(key);
        int priority = o != null ? o[0] : DEFAULT_PRIORITY;
        int margin = o != null ? o[1] : ArbiterConfig.defaultMargin();
        int maxShift = o != null ? o[2] : ArbiterConfig.defaultMaxShift();
        Box3 box = new Box3(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
        return new StructureIntent(key, priority, box, margin, maxShift);
    }
}
