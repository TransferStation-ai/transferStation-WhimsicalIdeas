package transferstation.transferstation_whimsicalideas.client.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型加载统计信息。
 * 跟踪平均加载时间、成功率、缓存命中率等指标。
 */
public class ModelLoadStatistics {

    private static final AtomicInteger totalLoads = new AtomicInteger(0);
    private static final AtomicInteger successfulLoads = new AtomicInteger(0);
    private static final AtomicInteger failedLoads = new AtomicInteger(0);
    private static final AtomicInteger retriedLoads = new AtomicInteger(0);
    private static final AtomicInteger cacheHits = new AtomicInteger(0);
    private static final AtomicInteger diskCacheHits = new AtomicInteger(0);
    private static final AtomicInteger memoryCacheHits = new AtomicInteger(0);
    private static final AtomicInteger nativeFallbacks = new AtomicInteger(0);
    private static final AtomicLong totalLoadTimeMs = new AtomicLong(0);
    private static final AtomicInteger peakConcurrentLoads = new AtomicInteger(0);

    private static final AtomicLong totalMemoryUsageBytes = new AtomicLong(0);
    private static final AtomicInteger trackedModelCount = new AtomicInteger(0);

    public static void recordLoadStart() {
        totalLoads.incrementAndGet();
    }

    public static void recordLoadSuccess(long elapsedMs) {
        successfulLoads.incrementAndGet();
        totalLoadTimeMs.addAndGet(elapsedMs);
    }

    public static void recordLoadFailure() {
        failedLoads.incrementAndGet();
    }

    public static void recordRetry() {
        retriedLoads.incrementAndGet();
    }

    public static void recordCacheHit(boolean disk) {
        cacheHits.incrementAndGet();
        if (disk) {
            diskCacheHits.incrementAndGet();
        } else {
            memoryCacheHits.incrementAndGet();
        }
    }

    public static void recordNativeFallback() {
        nativeFallbacks.incrementAndGet();
    }

    public static void recordConcurrentLoad(int count) {
        int current;
        do {
            current = peakConcurrentLoads.get();
            if (count <= current) break;
        } while (!peakConcurrentLoads.compareAndSet(current, count));
    }

    public static void recordModelMemory(long bytes) {
        totalMemoryUsageBytes.addAndGet(bytes);
        trackedModelCount.incrementAndGet();
    }

    public static void recordModelMemoryFreed(long bytes) {
        totalMemoryUsageBytes.addAndGet(-bytes);
        trackedModelCount.decrementAndGet();
    }

    public static int getTotalLoads() { return totalLoads.get(); }
    public static int getSuccessfulLoads() { return successfulLoads.get(); }
    public static int getFailedLoads() { return failedLoads.get(); }
    public static int getRetriedLoads() { return retriedLoads.get(); }
    public static int getCacheHits() { return cacheHits.get(); }
    public static int getDiskCacheHits() { return diskCacheHits.get(); }
    public static int getMemoryCacheHits() { return memoryCacheHits.get(); }
    public static int getNativeFallbacks() { return nativeFallbacks.get(); }
    public static int getPeakConcurrentLoads() { return peakConcurrentLoads.get(); }
    public static long getTotalMemoryUsageBytes() { return totalMemoryUsageBytes.get(); }
    public static int getTrackedModelCount() { return trackedModelCount.get(); }

    public static double getAverageLoadTimeMs() {
        int successes = successfulLoads.get();
        return successes > 0 ? (double) totalLoadTimeMs.get() / successes : 0;
    }

    public static double getSuccessRate() {
        int total = totalLoads.get();
        return total > 0 ? (double) successfulLoads.get() / total : 0;
    }

    public static double getCacheHitRate() {
        int total = totalLoads.get();
        return total > 0 ? (double) cacheHits.get() / total : 0;
    }

    public static void reset() {
        totalLoads.set(0);
        successfulLoads.set(0);
        failedLoads.set(0);
        retriedLoads.set(0);
        cacheHits.set(0);
        diskCacheHits.set(0);
        memoryCacheHits.set(0);
        nativeFallbacks.set(0);
        totalLoadTimeMs.set(0);
        peakConcurrentLoads.set(0);
        totalMemoryUsageBytes.set(0);
        trackedModelCount.set(0);
    }

    public static String toSummaryString() {
        int total = totalLoads.get();
        int successes = successfulLoads.get();
        int failures = failedLoads.get();
        return String.format(
            "[ModelLoadStats] loads=%d success=%d (%.1f%%) failed=%d retries=%d cache_hits=%d " +
            "(mem=%d disk=%d) native_fallbacks=%d avg_time=%.0fms peak_concurrent=%d " +
            "memory=%.1fMB tracked_models=%d",
            total, successes, getSuccessRate() * 100, failures, retriedLoads.get(),
            cacheHits.get(), memoryCacheHits.get(), diskCacheHits.get(),
            nativeFallbacks.get(), getAverageLoadTimeMs(),
            peakConcurrentLoads.get(),
            totalMemoryUsageBytes.get() / (1024.0 * 1024.0),
            trackedModelCount.get());
    }
}
