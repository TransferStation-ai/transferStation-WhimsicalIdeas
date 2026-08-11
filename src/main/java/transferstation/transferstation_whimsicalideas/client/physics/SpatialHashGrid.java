package transferstation.transferstation_whimsicalideas.client.physics;

import java.util.*;

/**
 * Spatial hash grid for broad-phase collision detection and spatial queries.
 * Divides the world into uniform cells and tracks which objects occupy each cell.
 * Used to quickly find potential collision pairs without checking every pair.
 */
public final class SpatialHashGrid {

    private final float cellSize;
    private final float inverseCellSize;
    private final Map<Long, CellEntry> entries = new HashMap<>();
    private final Map<Long, List<long[]>> cellBuckets = new HashMap<>();

    public SpatialHashGrid(float cellSize) {
        this.cellSize = cellSize;
        this.inverseCellSize = 1f / cellSize;
    }

    public float getCellSize() { return cellSize; }

    public void insert(long bodyId, float minX, float minY, float minZ,
                       float maxX, float maxY, float maxZ) {
        CellEntry existing = entries.get(bodyId);
        if (existing != null) {
            if (existing.minX == minX && existing.minY == minY && existing.minZ == minZ
                    && existing.maxX == maxX && existing.maxY == maxY && existing.maxZ == maxZ) {
                return;
            }
            remove(bodyId);
        }

        CellEntry entry = new CellEntry(minX, minY, minZ, maxX, maxY, maxZ);
        entries.put(bodyId, entry);

        int cx0 = cellCoord(minX);
        int cy0 = cellCoord(minY);
        int cz0 = cellCoord(minZ);
        int cx1 = cellCoord(maxX);
        int cy1 = cellCoord(maxY);
        int cz1 = cellCoord(maxZ);

        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    long key = cellKey(cx, cy, cz);
                    cellBuckets.computeIfAbsent(key, k -> new ArrayList<>()).add(new long[]{bodyId});
                }
            }
        }
    }

    public void remove(long bodyId) {
        CellEntry entry = entries.remove(bodyId);
        if (entry == null) return;

        int cx0 = cellCoord(entry.minX);
        int cy0 = cellCoord(entry.minY);
        int cz0 = cellCoord(entry.minZ);
        int cx1 = cellCoord(entry.maxX);
        int cy1 = cellCoord(entry.maxY);
        int cz1 = cellCoord(entry.maxZ);

        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    long key = cellKey(cx, cy, cz);
                    List<long[]> bucket = cellBuckets.get(key);
                    if (bucket != null) {
                        bucket.removeIf(e -> e[0] == bodyId);
                        if (bucket.isEmpty()) {
                            cellBuckets.remove(key);
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns all body IDs that could potentially overlap with the given AABB.
     * The result may contain false positives but never false negatives.
     */
    public List<Long> queryAABB(float minX, float minY, float minZ,
                                 float maxX, float maxY, float maxZ) {
        List<Long> result = new ArrayList<>();
        int cx0 = cellCoord(minX);
        int cy0 = cellCoord(minY);
        int cz0 = cellCoord(minZ);
        int cx1 = cellCoord(maxX);
        int cy1 = cellCoord(maxY);
        int cz1 = cellCoord(maxZ);

        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    long key = cellKey(cx, cy, cz);
                    List<long[]> bucket = cellBuckets.get(key);
                    if (bucket != null) {
                        for (long[] entry : bucket) {
                            if (!result.contains(entry[0])) {
                                result.add(entry[0]);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns all body IDs within a sphere of given radius around center.
     */
    public List<Long> querySphere(float cx, float cy, float cz, float radius) {
        float minX = cx - radius;
        float minY = cy - radius;
        float minZ = cz - radius;
        float maxX = cx + radius;
        float maxY = cy + radius;
        float maxZ = cz + radius;

        List<Long> candidates = queryAABB(minX, minY, minZ, maxX, maxY, maxZ);
        List<Long> result = new ArrayList<>();
        float r2 = radius * radius;

        for (Long bodyId : candidates) {
            CellEntry entry = entries.get(bodyId);
            if (entry == null) continue;
            float bx = (entry.minX + entry.maxX) * 0.5f;
            float by = (entry.minY + entry.maxY) * 0.5f;
            float bz = (entry.minZ + entry.maxZ) * 0.5f;
            float dx = bx - cx;
            float dy = by - cy;
            float dz = bz - cz;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                result.add(bodyId);
            }
        }
        return result;
    }

    /**
     * Find all potentially overlapping pairs. Useful for broad-phase collision detection.
     */
    public List<long[]> findPotentialPairs() {
        List<long[]> pairs = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();

        for (Map.Entry<Long, List<long[]>> cellEntry : cellBuckets.entrySet()) {
            List<long[]> bucket = cellEntry.getValue();
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    long a = bucket.get(i)[0];
                    long b = bucket.get(j)[0];
                    if (a == b) continue;
                    long pairKey = a < b ? a * 1_000_000_007L + b : b * 1_000_000_007L + a;
                    if (seen.add(pairKey)) {
                        pairs.add(new long[]{a, b});
                    }
                }
            }
        }
        return pairs;
    }

    public void clear() {
        entries.clear();
        cellBuckets.clear();
    }

    public int getEntryCount() {
        return entries.size();
    }

    public int getCellCount() {
        return cellBuckets.size();
    }

    private int cellCoord(float worldCoord) {
        return (int) Math.floor(worldCoord * inverseCellSize);
    }

    private long cellKey(int cx, int cy, int cz) {
        long h = cx * 73856093L;
        h ^= cy * 19349663L;
        h ^= cz * 83492791L;
        return h;
    }

    private static class CellEntry {
        final float minX, minY, minZ;
        final float maxX, maxY, maxZ;

        CellEntry(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }
}
