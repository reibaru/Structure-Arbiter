package com.reibaru.arbiter.mc;

import com.reibaru.arbiter.Box3;
import com.reibaru.arbiter.StructureIntent;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StructureStart の BoundingBox から StructureIntent を作る唯一の場所。
 * VanillaIntentSource（隣の先読み）と Mixin（実際の生成）が必ず同じ関数を通ることで、
 * 同じチャンクの同じ構造物は同じintentになる（＝調停結果が一致する）。
 *
 * priority は構造物IDだけで決まる（生成前に分かる）ことが重要。先読みは、順位で負ける構造物を
 * 生成せずに捨てるため、生成前に priorityOf(id) を使う。
 *
 * priority / margin / maxShift は構造物IDごとの上書きを受け付ける。
 * 既定の margin / maxShift は設定ファイル（ArbiterConfig）から読む。
 */
public final class IntentFactory {

    public static final int DEFAULT_PRIORITY = 0;

    /** id文字列 → {priority, margin, maxShift}。 */
    private static final Map<String, int[]> OVERRIDES = new ConcurrentHashMap<>();

    private IntentFactory() {}

    public static void override(String id, int priority, int margin, int maxShift) {
        OVERRIDES.put(id, new int[] {priority, margin, maxShift});
    }

    public static int priorityOf(String id) {
        int[] o = OVERRIDES.get(id);
        return o != null ? o[0] : DEFAULT_PRIORITY;
    }

    /** どの構造物のintentでも reach()（margin + maxShift）がこれを超えない。 */
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
