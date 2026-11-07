package com.reibaru.arbiter;

/**
 * ブロック座標の閉区間AABB [min, max]（MinecraftのBoundingBoxと同じ流儀）。
 * MC非依存なので単体テストできる。
 */
public record Box3(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box3 {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("invalid box: " + minX + "," + minY + "," + minZ
                    + " .. " + maxX + "," + maxY + "," + maxZ);
        }
    }

    public boolean intersects(Box3 o) {
        return minX <= o.maxX && maxX >= o.minX
                && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public boolean contains(Box3 o) {
        return minX <= o.minX && maxX >= o.maxX
                && minY <= o.minY && maxY >= o.maxY
                && minZ <= o.minZ && maxZ >= o.maxZ;
    }

    public Box3 translate(int dx, int dy, int dz) {
        return new Box3(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** 水平方向にn ブロック広げる（構造物同士の最小間隔の表現に使う）。 */
    public Box3 inflateXZ(int n) {
        return n == 0 ? this : new Box3(minX - n, minY, minZ - n, maxX + n, maxY, maxZ + n);
    }
}
