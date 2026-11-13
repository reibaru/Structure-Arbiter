package com.reibaru.arbiter;


public record StructureIntent(String id, int priority, Box3 box, int margin, int maxShift) {

    public StructureIntent {
        if (margin < 0 || maxShift < 0) throw new IllegalArgumentException("margin/maxShift must be >= 0");
    }

    public int reach() {
        return margin + maxShift;
    }
}
