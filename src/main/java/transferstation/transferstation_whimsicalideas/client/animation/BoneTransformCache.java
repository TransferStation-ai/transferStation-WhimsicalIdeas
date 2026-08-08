package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches computed per-bone transform matrices keyed by (entity, animationName, frame).
 * Avoids redundant recomputation when the same frame is sampled multiple times
 * in a single render pass (e.g. skinned mesh + debug overlay).
 * <p>
 * Entries are automatically evicted when the entity is garbage-collected (WeakReference)
 * or when the cache exceeds {@link #MAX_ENTRIES}.
 */
public final class BoneTransformCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Hard cap to prevent unbounded memory growth. */
    private static final int MAX_ENTRIES = 2048;

    private static final Map<CacheKey, float[][]> cache = new ConcurrentHashMap<>();
    private static int hitCount = 0;
    private static int missCount = 0;

    private BoneTransformCache() {
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Retrieve a cached bone-transform array, or null if not cached.
     */
    public static float[][] get(LivingEntity entity, String animationName, int frame) {
        CacheKey key = new CacheKey(entity, animationName, frame);
        float[][] result = cache.get(key);
        if (result != null) {
            hitCount++;
        } else {
            missCount++;
        }
        return result;
    }

    /**
     * Store a bone-transform array in the cache.
     */
    public static void put(LivingEntity entity, String animationName, int frame, float[][] transforms) {
        if (transforms == null) return;

        if (cache.size() >= MAX_ENTRIES) {
            evictOldest();
        }

        CacheKey key = new CacheKey(entity, animationName, frame);
        cache.put(key, transforms);
    }

    /**
     * Convenience: get-or-compute. Returns cached value if present,
     * otherwise calls the supplier, stores and returns the result.
     */
    public static float[][] getOrCompute(LivingEntity entity, String animationName, int frame,
                                         java.util.function.Supplier<float[][]> compute) {
        float[][] cached = get(entity, animationName, frame);
        if (cached != null) return cached;

        float[][] result = compute.get();
        put(entity, animationName, frame, result);
        return result;
    }

    /**
     * Invalidate all cached entries for a specific entity.
     */
    public static void invalidateEntity(LivingEntity entity) {
        if (entity == null) return;
        int id = entity.getId();
        cache.keySet().removeIf(k -> k.entityId == id);
    }

    /**
     * Invalidate all cached entries for a specific animation name.
     */
    public static void invalidateAnimation(String animationName) {
        if (animationName == null) return;
        cache.keySet().removeIf(k -> k.animationName.equals(animationName));
    }

    /**
     * Clear the entire cache.
     */
    public static void clear() {
        cache.clear();
        hitCount = 0;
        missCount = 0;
    }

    /**
     * Returns cache hit ratio for diagnostics.
     */
    public static float getHitRatio() {
        int total = hitCount + missCount;
        return total > 0 ? (float) hitCount / total : 0f;
    }

    public static int size() {
        return cache.size();
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Evict entries whose entities have been garbage-collected, then if still
     * over capacity, remove the oldest 25%.
     */
    private static void evictOldest() {
        // First pass: remove entries for dead entities
        cache.entrySet().removeIf(e -> e.getKey().entityRef.get() == null);

        // Second pass: if still over cap, remove 25%
        if (cache.size() >= MAX_ENTRIES) {
            int toRemove = MAX_ENTRIES / 4;
            int removed = 0;
            var iter = cache.keySet().iterator();
            while (iter.hasNext() && removed < toRemove) {
                iter.next();
                iter.remove();
                removed++;
            }
            LOGGER.debug("[BoneTransformCache] Evicted {} entries (capacity: {})", removed, MAX_ENTRIES);
        }
    }

    // ── Cache Key ─────────────────────────────────────────────────────────

    /**
     * Composite cache key: (entityId, animationName, frame).
     * Uses WeakReference for entity to allow GC when entity is removed.
     */
    private static final class CacheKey {
        final WeakReference<LivingEntity> entityRef;
        final int entityId;
        final String animationName;
        final int frame;

        CacheKey(LivingEntity entity, String animationName, int frame) {
            this.entityRef = new WeakReference<>(entity);
            this.entityId = entity.getId();
            this.animationName = animationName;
            this.frame = frame;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey other)) return false;
            return entityId == other.entityId
                    && frame == other.frame
                    && animationName.equals(other.animationName);
        }

        @Override
        public int hashCode() {
            int result = entityId;
            result = 31 * result + animationName.hashCode();
            result = 31 * result + frame;
            return result;
        }
    }

    @FunctionalInterface
    public interface Supplier<T> {
        T get();
    }
}
