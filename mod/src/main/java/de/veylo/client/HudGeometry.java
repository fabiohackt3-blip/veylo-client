package de.veylo.client;

final class HudGeometry {
    private static final int SNAP_DISTANCE = 6;

    record Anchor(int offset, boolean far) {}

    static int position(int offset, int size, int extent, boolean far) {
        int max = Math.max(0, extent - size);
        int bounded = Math.max(0, Math.min(max, offset));
        return far ? max - bounded : bounded;
    }

    static Anchor snapPosition(int position, int size, int extent, boolean snap) {
        int max = Math.max(0, extent - size);
        int bounded = Math.max(0, Math.min(max, position));
        if (snap && bounded <= SNAP_DISTANCE) return new Anchor(0, false);
        if (snap && max - bounded <= SNAP_DISTANCE) return new Anchor(0, true);
        return new Anchor(bounded, false);
    }
}
