package com.reibaru.arbiter.mc;

import com.reibaru.arbiter.IntentSource;
import com.reibaru.arbiter.RegionSolver;

public interface ArbiterAccess {

    RegionSolver arbiter$getSolver();

    IntentSource arbiter$getSource();

    long arbiter$getSeed();
}
