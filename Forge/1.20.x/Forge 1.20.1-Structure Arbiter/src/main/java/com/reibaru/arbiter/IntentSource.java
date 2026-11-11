package com.reibaru.arbiter;

import java.util.List;
import java.util.function.BiPredicate;


public interface IntentSource {

    List<StructureIntent> intentsNear(long seed, int minX, int minZ, int maxX, int maxZ,
                                      BiPredicate<String, Integer> wanted);

    /** 全構造物が対象のクエリ（デバッグ・テスト用）。 */
    default List<StructureIntent> intentsNear(long seed, int minX, int minZ, int maxX, int maxZ) {
        return intentsNear(seed, minX, minZ, maxX, maxZ, (id, priority) -> true);
    }

    int maxNeighborReach();
}
