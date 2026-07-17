package dev.chunkcopy.replication;

/** Minecraft's stable packed chunk-coordinate representation without registry initialization. */
public final class PackedChunk {
    private PackedChunk() {
    }

    public static long pack(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFF_FFFFL) | ((long) chunkZ << 32);
    }

    public static int x(long packed) {
        return (int) packed;
    }

    public static int z(long packed) {
        return (int) (packed >>> 32);
    }

    public static int startX(long packed) {
        return x(packed) << 4;
    }

    public static int startZ(long packed) {
        return z(packed) << 4;
    }
}
