package com.reibaru.arbiter;

/** 調停後の確定配置。dx,dzは希望位置からの平行移動量（0,0なら無移動）。 */
public record Placement(StructureIntent intent, Box3 placed, int dx, int dz) {

    public boolean moved() {
        return dx != 0 || dz != 0;
    }
}
