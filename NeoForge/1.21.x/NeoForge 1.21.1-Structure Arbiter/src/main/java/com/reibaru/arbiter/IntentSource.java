package com.reibaru.arbiter;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * 意図の供給元。
 *
 * 契約（これが決定性の土台）:
 *  - intentsNear は (seed, 矩形) だけで決まる純粋関数であること。
 *    生成済みかどうか・呼び出し順・スレッドに依存してはならない。
 *  - 返すリストは「box が水平矩形 [minX..maxX] x [minZ..maxZ] と交わるintentのうち、
 *    wanted が true のもの」を全て含むこと。余計なintentを含んでもよい（ソルバが厳密に絞り込む）。
 *  - wanted(id, priority) が false の構造物は、返さなくてよい。さらに重要なのは、
 *    <b>wanted が false の構造物は生成（高コストな先読み）をしてはならない</b>こと。
 *    id と priority は生成前に分かるので、順位で負ける構造物・同じ構造物の別インスタンスは、
 *    生成せずに捨てられる。これがコストの大半を決める。
 *  - どのintentも reach() が maxNeighborReach() を超えないこと。
 *
 * 近傍クエリにしたのは、バニラ構造物では1件のintentを得るのに Structure#generate
 * （ジグソーの試行錯誤）が要り、リージョン全体を先読みすると桁違いに重いため。
 */
public interface IntentSource {

    List<StructureIntent> intentsNear(long seed, int minX, int minZ, int maxX, int maxZ,
                                      BiPredicate<String, Integer> wanted);

    /** 全構造物が対象のクエリ（デバッグ・テスト用）。 */
    default List<StructureIntent> intentsNear(long seed, int minX, int minZ, int maxX, int maxZ) {
        return intentsNear(seed, minX, minZ, maxX, maxZ, (id, priority) -> true);
    }

    /** intent の reach()（margin + maxShift）の上界。構造物の大きさは含まない。 */
    int maxNeighborReach();
}
