package com.reibaru.arbiter.mc;

import com.reibaru.arbiter.IntentSource;
import com.reibaru.arbiter.RegionSolver;

/**
 * MixinChunkGenerator が ChunkGenerator に実装させる窓口。
 * コマンド側（/arbiter here）が、ディメンションごとのソルバを取り出すのに使う。
 * ソルバは最初の構造物生成が走るまで作られないので、getSolver() は null を返しうる。
 */
public interface ArbiterAccess {

    RegionSolver arbiter$getSolver();

    IntentSource arbiter$getSource();

    long arbiter$getSeed();
}
