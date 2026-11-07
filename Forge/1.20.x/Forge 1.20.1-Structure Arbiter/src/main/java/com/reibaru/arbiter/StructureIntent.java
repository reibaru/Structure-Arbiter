package com.reibaru.arbiter;

/**
 * MODが提出する「意図」。ブロックは置かず、空間要件だけを宣言する（＝射の定義）。
 *
 * @param id       "modid:structure" 形式の識別子（順序決定に使うので安定させること）
 * @param priority 大きいほど優先。同値ならid→座標で決定的に順序づける
 * @param box      希望配置のバウンディングボックス
 * @param margin   他の構造物との最小水平間隔（ブロック）。衝突判定は両者のmarginの大きい方
 * @param maxShift 平行移動で許容する最大距離（0なら移動不可。移動できないものは衝突したら棄却）
 */
public record StructureIntent(String id, int priority, Box3 box, int margin, int maxShift) {

    public StructureIntent {
        if (margin < 0 || maxShift < 0) throw new IllegalArgumentException("margin/maxShift must be >= 0");
    }

    /** 他のintentが移動してもこのintentに届きうる範囲の広がり。 */
    public int reach() {
        return margin + maxShift;
    }
}
