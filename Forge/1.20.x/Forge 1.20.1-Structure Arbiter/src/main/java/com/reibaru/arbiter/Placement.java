package com.reibaru.arbiter;

public record Placement(StructureIntent intent, Box3 placed, int dx, int dz) {

    public boolean moved() {
        return dx != 0 || dz != 0;
    }
}
