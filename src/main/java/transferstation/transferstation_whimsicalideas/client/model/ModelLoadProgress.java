package transferstation.transferstation_whimsicalideas.client.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型加载进度追踪器。
 * 轻量级静态状态机，ModelLoadManager 在加载的关键阶段更新状态，
 * ModelLoadProgressOverlay 读取状态并渲染 HUD 进度条。
 * 以防止出现玩家觉得模型正在加载但实际未被加载的情况
 * 新增多模型批处理支持：prewarmModelCaches 等批量加载场景可调用
 * beginBatch/advanceBatch 追踪"模型 X/Y"整体进度，与当前模型的
 * 子阶段（PARSING/TEXTURING/BUILDING）叠加显示。
 *
 * <h3>线程安全说明</h3>
 * 所有字段为 volatile 保证可见性；批处理计数使用 AtomicInteger
 * 防止异步回调中的 check-then-act 竞态。
 */
public class ModelLoadProgress {

    public enum Phase {
        IDLE(""),
        SCANNING("Scanning"),
        PARSING("Parsing"),
        TEXTURING("Textures"),
        BUILDING("Building");

        final String display;
        Phase(String display) { this.display = display; }
        public String getDisplay() { return display; }

        /** 获取该主阶段对应的颜色 (ARGB) */
        public int getColor() {
            return switch (this) {
                case SCANNING -> 0xFF4CAF50;    // 绿色
                case PARSING -> 0xFF2196F3;     // 蓝色
                case TEXTURING -> 0xFFFF9800;   // 橙色
                case BUILDING -> 0xFF9C27B0;    // 紫色
                default -> 0xFFFFFFFF;          // 白色
            };
        }
    }

    /**
     * 子阶段枚举，提供更细粒度的进度追踪
     */
    public enum SubPhase {
        // SCANNING 子阶段
        SCAN_DIR("Scanning directory"),
        SCAN_VPK("Scanning VPK"),
        SCAN_MATERIALS("Scanning materials"),

        // PARSING 子阶段
        PARSE_MDL("Parsing MDL"),
        PARSE_VVD("Parsing VVD"),
        PARSE_VTX("Parsing VTX"),
        PARSE_SMD("Parsing SMD"),
        PARSE_PHYSICS("Parsing physics"),
        PARSE_ANIMATIONS("Parsing animations"),

        // TEXTURING 子阶段
        TEX_SCAN_VTFS("Scanning VTFs"),
        TEX_PARSE_VMTS("Parsing VMTs"),
        TEX_RESOLVE_MATERIALS("Resolving materials"),
        TEX_REGISTER_TEXTURES("Registering textures"),
        TEX_LOADING("Loading textures"),

        // BUILDING 子阶段
        BUILD_MESHES("Building meshes"),
        BUILD_LODS("Building LODs"),
        BUILD_BONES("Building bones"),
        BUILD_PHYSICS("Building physics"),
        BUILD_ATTACHMENTS("Building attachments"),
        BUILD_HITBOXES("Building hitboxes"),
        BUILD_SEQUENCES("Building sequences"),
        BUILD_FLEXES("Building flexes"),
        BUILD_INCLUDES("Processing includes");

        final String display;
        SubPhase(String display) { this.display = display; }
        public String getDisplay() { return display; }

        /** 根据子阶段推断所属的主阶段 */
        public Phase getParentPhase() {
            return switch (this) {
                case SCAN_DIR, SCAN_VPK, SCAN_MATERIALS -> Phase.SCANNING;
                case PARSE_MDL, PARSE_VVD, PARSE_VTX, PARSE_SMD, PARSE_PHYSICS, PARSE_ANIMATIONS -> Phase.PARSING;
                case TEX_SCAN_VTFS, TEX_PARSE_VMTS, TEX_RESOLVE_MATERIALS, TEX_REGISTER_TEXTURES, TEX_LOADING -> Phase.TEXTURING;
                case BUILD_MESHES, BUILD_LODS, BUILD_BONES, BUILD_PHYSICS, BUILD_ATTACHMENTS,
                        BUILD_HITBOXES, BUILD_SEQUENCES, BUILD_FLEXES, BUILD_INCLUDES -> Phase.BUILDING;
            };
        }
    }

    // ==================== 核心状态 ====================
    private static volatile Phase currentPhase = Phase.IDLE;
    private static volatile SubPhase currentSubPhase = null;
    private static volatile int totalItems = 0;
    private static volatile int completedItems = 0;
    private static volatile int failedItems = 0;
    private static volatile String currentItem = "";
    private static volatile long startTime = 0;
    private static volatile String modelName = "";

    /** 当前阶段是否为不确定进度（无总量可预估）。 */
    private static volatile boolean indeterminate = false;

    // ==================== 多模型批处理状态 ====================
    private static volatile boolean batchMode = false;
    private static volatile int batchTotal = 0;
    /** 使用 AtomicInteger 防止异步回调中的并发推进。 */
    private static final AtomicInteger batchCompleted = new AtomicInteger(0);

    // ==================== 增强状态 ====================
    /** 解析器类型：native / java / fallback */
    private static volatile String parserType = "";
    /** 缓存命中信息 */
    private static volatile String cacheInfo = "";
    /** 当前处理的文件路径 */
    private static volatile String currentFile = "";
    /** 预估剩余时间（毫秒） */
    private static volatile long estimatedRemainingMs = 0;
    /** 已处理项目数 / 总项目数（用于显示 "X/Y"） */
    private static volatile int processedCount = 0;
    /** 总项目数（与 totalItems 类似，但用于显示） */
    private static volatile int displayTotal = 0;
    /** 材质加载进度：当前材质索引 */
    private static volatile int currentTextureIndex = 0;
    /** 材质加载进度：总材质数 */
    private static volatile int totalTextures = 0;
    /** 内存使用量（字节） */
    private static volatile long memoryUsageBytes = 0;
    /** 峰值内存使用量（字节） */
    private static volatile long peakMemoryUsageBytes = 0;
    /** 当前模型内存使用量（字节） */
    private static volatile long currentModelMemoryBytes = 0;
    /** 内存警告阈值（字节） */
    private static final long MEMORY_WARNING_THRESHOLD = 256L * 1024 * 1024; // 256 MB per model
    /** 阶段开始时间戳（用于阶段计时） */
    private static volatile long phaseStartTime = 0;
    /** 各阶段耗时记录（毫秒） */
    private static final AtomicLong[] phaseDurations = new AtomicLong[Phase.values().length];
    /** 子阶段开始时间戳 */
    private static volatile long subPhaseStartTime = 0;
    /** 当前阶段已完成项数（用于计算 ETA） */
    private static volatile int phaseCompletedAtLastUpdate = 0;
    /** 上次更新时间（用于计算速度） */
    private static volatile long lastUpdateTime = 0;

    // ==================== 模型复杂度指标 ====================
    /** 顶点数 */
    private static volatile int vertexCount = 0;
    /** 三角形数 */
    private static volatile int triangleCount = 0;
    /** 骨骼数 */
    private static volatile int boneCount = 0;

    // ==================== 内存历史（用于 sparkline） ====================
    private static final int MEMORY_HISTORY_SIZE = 60;
    private static final List<Long> memoryHistory = Collections.synchronizedList(new ArrayList<>(MEMORY_HISTORY_SIZE));
    private static volatile long lastMemorySampleTime = 0;
    private static final long MEMORY_SAMPLE_INTERVAL_MS = 200;

    static {
        for (int i = 0; i < phaseDurations.length; i++) {
            phaseDurations[i] = new AtomicLong(0);
        }
    }

    // ==================== 阶段控制 ====================

    /**
     * 开始一个确定进度的阶段（有总量）。
     * 若尚未计时则开始计时。
     */
    public static void begin(Phase phase, int total) {
        currentPhase = phase;
        currentSubPhase = null;
        totalItems = Math.max(total, 0);
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        currentFile = "";
        processedCount = 0;
        displayTotal = Math.max(total, 0);
        indeterminate = (total <= 0);
        if (startTime == 0) startTime = System.currentTimeMillis();
        phaseStartTime = System.currentTimeMillis();
        lastUpdateTime = System.currentTimeMillis();
        phaseCompletedAtLastUpdate = 0;
        estimatedRemainingMs = 0;
    }

    /**
     * 开始一个不确定进度的阶段（无总量）。
     * 进度条显示为跑马灯动画。
     */
    public static void begin(Phase phase) {
        currentPhase = phase;
        currentSubPhase = null;
        totalItems = 0;
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        currentFile = "";
        processedCount = 0;
        displayTotal = 0;
        indeterminate = true;
        if (startTime == 0) startTime = System.currentTimeMillis();
        phaseStartTime = System.currentTimeMillis();
        lastUpdateTime = System.currentTimeMillis();
        phaseCompletedAtLastUpdate = 0;
        estimatedRemainingMs = 0;
    }

    /**
     * 切换阶段并设定总量，重置计数器。
     * 保留已有计时（不重置 startTime），使总计时跨阶段连续。
     * 记录上一阶段的耗时。
     */
    public static void setPhase(Phase phase, int total) {
        // 记录上一阶段耗时
        if (currentPhase != Phase.IDLE && phaseStartTime > 0) {
            long duration = System.currentTimeMillis() - phaseStartTime;
            phaseDurations[currentPhase.ordinal()].addAndGet(duration);
        }

        currentPhase = phase;
        currentSubPhase = null;
        totalItems = Math.max(total, 0);
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        currentFile = "";
        processedCount = 0;
        displayTotal = Math.max(total, 0);
        indeterminate = (total <= 0);
        phaseStartTime = System.currentTimeMillis();
        lastUpdateTime = System.currentTimeMillis();
        phaseCompletedAtLastUpdate = 0;
        estimatedRemainingMs = 0;
        // 重置材质进度
        currentTextureIndex = 0;
        totalTextures = 0;
    }

    /**
     * 切换到不定量阶段。
     * 进度条将显示为跑马灯动画。
     */
    public static void setPhase(Phase phase) {
        if (currentPhase != Phase.IDLE && phaseStartTime > 0) {
            long duration = System.currentTimeMillis() - phaseStartTime;
            phaseDurations[currentPhase.ordinal()].addAndGet(duration);
        }

        currentPhase = phase;
        currentSubPhase = null;
        totalItems = 0;
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        currentFile = "";
        processedCount = 0;
        displayTotal = 0;
        indeterminate = true;
        phaseStartTime = System.currentTimeMillis();
        lastUpdateTime = System.currentTimeMillis();
        phaseCompletedAtLastUpdate = 0;
        estimatedRemainingMs = 0;
        currentTextureIndex = 0;
        totalTextures = 0;
    }

    /**
     * 设置子阶段，提供更细粒度的进度追踪。
     * @param subPhase 子阶段
     * @param total 该子阶段的总项目数（<=0 表示不定量）
     */
    public static void setSubPhase(SubPhase subPhase, int total) {
        if (subPhase == null) return;

        // 验证子阶段属于当前主阶段
        if (currentPhase != Phase.IDLE && subPhase.getParentPhase() != currentPhase) {
            // 如果主阶段不匹配，自动切换主阶段
            setPhase(subPhase.getParentPhase(), total);
        } else if (total > 0) {
            totalItems = total;
            displayTotal = total;
            indeterminate = false;
        }

        currentSubPhase = subPhase;
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        currentFile = "";
        processedCount = 0;
        subPhaseStartTime = System.currentTimeMillis();
        lastUpdateTime = System.currentTimeMillis();
        phaseCompletedAtLastUpdate = 0;
        estimatedRemainingMs = 0;
    }

    /**
     * 设置子阶段（不定量版本）。
     */
    public static void setSubPhase(SubPhase subPhase) {
        setSubPhase(subPhase, 0);
    }

    /** 当前处理的模型名称。 */
    public static void setModelName(String name) {
        modelName = name != null ? name : "";
    }

    /** 推进一个进度项并更新当前项名称。 */
    public static void progress(String item) {
        completedItems++;
        processedCount++;
        if (item != null) {
            currentItem = item;
            currentFile = item;
        }
        updateEta();
    }

    /** 推进一个进度项（不更新名称）。 */
    public static void progress() {
        completedItems++;
        processedCount++;
        updateEta();
    }

    /** 推进进度并指定当前处理的文件路径。 */
    public static void progressFile(String filePath) {
        completedItems++;
        processedCount++;
        if (filePath != null) {
            currentFile = filePath;
            currentItem = filePath;
        }
        updateEta();
    }

    /** 标记一项失败。 */
    public static void fail(String item) {
        failedItems++;
        if (item != null) {
            currentItem = item;
            currentFile = item;
        }
        updateEta();
    }

    /**
     * 更新预估剩余时间（基于当前处理速度）。
     */
    private static void updateEta() {
        long now = System.currentTimeMillis();
        long elapsedSinceLastUpdate = now - lastUpdateTime;
        if (elapsedSinceLastUpdate > 100 && totalItems > 0) { // 至少间隔 100ms 更新一次
            int completedSinceLast = (completedItems + failedItems) - phaseCompletedAtLastUpdate;
            if (completedSinceLast > 0) {
                double rate = completedSinceLast / (elapsedSinceLastUpdate / 1000.0); // items/second
                int remaining = totalItems - completedItems - failedItems;
                if (rate > 0) {
                    estimatedRemainingMs = (long) (remaining / rate * 1000);
                }
            }
            lastUpdateTime = now;
            phaseCompletedAtLastUpdate = completedItems + failedItems;
        }
    }

    /**
     * 设置解析器类型（native / java / fallback）。
     */
    public static void setParserType(String type) {
        parserType = type != null ? type : "";
    }

    /**
     * 设置缓存信息（如 "Cache hit: disk" 或 "Cache miss"）。
     */
    public static void setCacheInfo(String info) {
        cacheInfo = info != null ? info : "";
    }

    /**
     * 设置材质加载进度。
     * @param current 当前材质索引（从 1 开始）
     * @param total 总材质数
     */
    public static void setTextureProgress(int current, int total) {
        currentTextureIndex = Math.max(0, current);
        totalTextures = Math.max(0, total);
    }

    /**
     * 更新内存使用量。
     * @param currentBytes 当前内存使用量（字节）
     */
    public static void updateMemoryUsage(long currentBytes) {
        memoryUsageBytes = Math.max(0, currentBytes);
        if (currentBytes > peakMemoryUsageBytes) {
            peakMemoryUsageBytes = currentBytes;
        }
        // 采样内存历史（限频）
        long now = System.currentTimeMillis();
        if (now - lastMemorySampleTime >= MEMORY_SAMPLE_INTERVAL_MS) {
            memoryHistory.add(currentBytes);
            if (memoryHistory.size() > MEMORY_HISTORY_SIZE) {
                memoryHistory.remove(0);
            }
            lastMemorySampleTime = now;
        }
    }

    /**
     * 设置模型复杂度指标。
     */
    public static void setComplexity(int vertices, int triangles, int bones) {
        vertexCount = Math.max(0, vertices);
        triangleCount = Math.max(0, triangles);
        boneCount = Math.max(0, bones);
    }

    /** 获取顶点数。 */
    public static int getVertexCount() { return vertexCount; }
    /** 获取三角形数。 */
    public static int getTriangleCount() { return triangleCount; }
    /** 获取骨骼数。 */
    public static int getBoneCount() { return boneCount; }
    /** 获取内存历史快照（副本）。 */
    public static List<Long> getMemoryHistory() {
        synchronized (memoryHistory) {
            return new ArrayList<>(memoryHistory);
        }
    }

    /**
     * 更新当前模型的内存使用量。
     * @param modelBytes 当前模型内存使用量（字节）
     */
    public static void updateModelMemoryUsage(long modelBytes) {
        currentModelMemoryBytes = Math.max(0, modelBytes);
    }

    /**
     * 获取当前模型内存使用量。
     */
    public static long getCurrentModelMemoryBytes() {
        return currentModelMemoryBytes;
    }

    /**
     * 检查当前模型内存是否超过警告阈值。
     */
    public static boolean isModelMemoryWarning() {
        return currentModelMemoryBytes > MEMORY_WARNING_THRESHOLD;
    }

    /**
     * 获取当前模型内存警告阈值。
     */
    public static long getMemoryWarningThreshold() {
        return MEMORY_WARNING_THRESHOLD;
    }

    /**
     * 重置当前模型的阶段进度为 IDLE。
     * <p>
     * <strong>不</strong>影响批处理状态（batchMode/batchTotal/batchCompleted）。
     * 批处理模式由 {@link #beginBatch(int)}/{@link #advanceBatch()}/{@link #endBatch()} 单独管理。
     * 批处理中的 startTime 会被保留，使总计时跨模型连续。
     */
    public static void reset() {
        // 记录最后一个阶段的耗时
        if (currentPhase != Phase.IDLE && phaseStartTime > 0) {
            long duration = System.currentTimeMillis() - phaseStartTime;
            phaseDurations[currentPhase.ordinal()].addAndGet(duration);
        }

        currentPhase = Phase.IDLE;
        currentSubPhase = null;
        totalItems = 0;
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        currentFile = "";
        modelName = "";
        indeterminate = false;
        parserType = "";
        cacheInfo = "";
        processedCount = 0;
        displayTotal = 0;
        currentTextureIndex = 0;
        totalTextures = 0;
        estimatedRemainingMs = 0;
        phaseStartTime = 0;
        subPhaseStartTime = 0;
        lastUpdateTime = 0;
        phaseCompletedAtLastUpdate = 0;
        if (!batchMode) {
            startTime = 0;
            // 重置阶段耗时记录
            for (AtomicLong ad : phaseDurations) ad.set(0);
            memoryUsageBytes = 0;
            peakMemoryUsageBytes = 0;
            currentModelMemoryBytes = 0;
            vertexCount = 0;
            triangleCount = 0;
            boneCount = 0;
            memoryHistory.clear();
            lastMemorySampleTime = 0;
        }
    }

    // ==================== 多模型批处理 API ====================

    /**
     * 开始批处理模式，追踪多个模型的整体加载进度。
     * 批处理模式下每完成一个模型应调用 {@link #advanceBatch()}。
     * 当 batchCompleted == batchTotal 时自动结束批处理。
     * <p>
     * 线程安全：内部使用 AtomicInteger，可在异步回调中安全调用。
     */
    public static void beginBatch(int total) {
        batchMode = true;
        batchTotal = Math.max(total, 1);
        batchCompleted.set(0);
        if (startTime == 0) startTime = System.currentTimeMillis();
    }

    /**
     * 推进批处理计数器（完成一个模型后调用）。
     * 使用 AtomicInteger 防止并发竞态：多次调用不会超过 batchTotal。
     * 当内部计数达到 batchTotal 时自动结束批处理。
     */
    public static void advanceBatch() {
        int completed = batchCompleted.incrementAndGet();
        if (completed >= batchTotal) {
            // 双重检查锁定风格：只有第一个达到阈值的线程执行 endBatch
            synchronized (ModelLoadProgress.class) {
                if (batchMode && batchCompleted.get() >= batchTotal) {
                    endBatch();
                }
            }
        }
    }

    /** 结束批处理模式。 */
    public static void endBatch() {
        batchMode = false;
        batchTotal = 0;
        batchCompleted.set(0);
    }

    /** 是否处于批处理模式。 */
    public static boolean isBatch() { return batchMode; }
    /** 批处理模型总数。 */
    public static int getBatchTotal() { return batchTotal; }
    /** 批处理已完成模型数。 */
    public static int getBatchCompleted() { return batchCompleted.get(); }

    // ==================== Getters ====================

    public static Phase getCurrentPhase() { return currentPhase; }
    public static SubPhase getCurrentSubPhase() { return currentSubPhase; }
    public static int getTotalItems() { return totalItems; }
    public static int getCompletedItems() { return completedItems; }
    public static int getFailedItems() { return failedItems; }
    public static String getCurrentItem() { return currentItem; }
    public static String getCurrentFile() { return currentFile; }
    public static long getStartTime() { return startTime; }
    public static String getModelName() { return modelName; }
    public static String getParserType() { return parserType; }
    public static String getCacheInfo() { return cacheInfo; }
    public static long getEstimatedRemainingMs() { return estimatedRemainingMs; }
    public static int getProcessedCount() { return processedCount; }
    public static int getDisplayTotal() { return displayTotal; }
    public static int getCurrentTextureIndex() { return currentTextureIndex; }
    public static int getTotalTextures() { return totalTextures; }
    public static long getMemoryUsageBytes() { return memoryUsageBytes; }
    public static long getPeakMemoryUsageBytes() { return peakMemoryUsageBytes; }
    public static long getPhaseDuration(Phase phase) { return phaseDurations[phase.ordinal()].get(); }
    public static long getTotalPhaseDuration() {
        long sum = 0;
        for (AtomicLong ad : phaseDurations) sum += ad.get();
        // 加上当前进行中的阶段时间
        if (currentPhase != Phase.IDLE && phaseStartTime > 0) {
            sum += System.currentTimeMillis() - phaseStartTime;
        }
        return sum;
    }
    public static long getSubPhaseElapsed() {
        if (subPhaseStartTime == 0) return 0;
        return System.currentTimeMillis() - subPhaseStartTime;
    }

    /** 是否处于活跃（非 IDLE）状态。 */
    public static boolean isActive() {
        return currentPhase != Phase.IDLE;
    }

    /** 当前阶段是否为不确定进度（无总量，显示跑马灯动画）。 */
    public static boolean isIndeterminate() {
        return indeterminate;
    }

    /**
     * 当前阶段进度 [0..1]。
     * @return 确定进度时返回 [0, 1]；不确定进度或无总量时返回 -1。
     */
    public static float getProgress() {
        if (indeterminate || totalItems <= 0) return -1f;
        return Math.min(1f, (float) (completedItems + failedItems) / totalItems);
    }

    /**
     * 子阶段进度 [0..1]。
     * @return 确定进度时返回 [0, 1]；不确定进度或无总量时返回 -1。
     */
    public static float getSubPhaseProgress() {
        if (currentSubPhase == null) return -1f;
        if (indeterminate || totalItems <= 0) return -1f;
        return Math.min(1f, (float) (completedItems + failedItems) / totalItems);
    }

    /** 已用时间格式 mm:ss。 */
    public static String getElapsed() {
        if (startTime == 0) return "";
        long elapsed = System.currentTimeMillis() - startTime;
        long secs = elapsed / 1000;
        long mins = secs / 60;
        secs %= 60;
        return String.format("%d:%02d", mins, secs);
    }

    /** 预估剩余时间格式 mm:ss，若无法估计返回空字符串。 */
    public static String getEta() {
        if (estimatedRemainingMs <= 0 || indeterminate) return "";
        long secs = estimatedRemainingMs / 1000;
        long mins = secs / 60;
        secs %= 60;
        return String.format("%d:%02d", mins, secs);
    }

    /** 格式化内存大小（如 "12.3 MB"）。 */
    public static String formatMemory(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** 格式化阶段耗时（如 "1.23s" 或 "45ms"）。 */
    public static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.2fs", ms / 1000.0);
    }
}