package transferstation.transferstation_whimsicalideas.client.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型加载进度追踪器。
 * 轻量级静态状态机，ModelLoadManager 在加载的关键阶段更新状态，
 * ModelLoadProgressOverlay 读取状态并渲染 HUD 进度条。
 *
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
    }

    private static volatile Phase currentPhase = Phase.IDLE;
    private static volatile int totalItems = 0;
    private static volatile int completedItems = 0;
    private static volatile int failedItems = 0;
    private static volatile String currentItem = "";
    private static volatile long startTime = 0;
    private static volatile String modelName = "";

    /** 当前阶段是否为不确定进度（无总量可预估）。 */
    private static volatile boolean indeterminate = false;

    // ---- 多模型批处理状态 ----
    private static volatile boolean batchMode = false;
    private static volatile int batchTotal = 0;
    /** 使用 AtomicInteger 防止异步回调中的并发推进。 */
    private static final AtomicInteger batchCompleted = new AtomicInteger(0);

    // ==================== 阶段控制 ====================

    /**
     * 开始一个确定进度的阶段（有总量）。
     * 若尚未计时则开始计时。
     */
    public static void begin(Phase phase, int total) {
        currentPhase = phase;
        totalItems = Math.max(total, 0);
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        indeterminate = (total <= 0);
        if (startTime == 0) startTime = System.currentTimeMillis();
    }

    /**
     * 开始一个不确定进度的阶段（无总量）。
     * 进度条显示为跑马灯动画。
     */
    public static void begin(Phase phase) {
        currentPhase = phase;
        totalItems = 0;
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        indeterminate = true;
        if (startTime == 0) startTime = System.currentTimeMillis();
    }

    /**
     * 切换阶段并设定总量，重置计数器。
     * 保留已有计时（不重置 startTime），使总计时跨阶段连续。
     */
    public static void setPhase(Phase phase, int total) {
        currentPhase = phase;
        totalItems = Math.max(total, 0);
        completedItems = 0;
        failedItems = 0;
        indeterminate = (total <= 0);
    }

    /**
     * 切换到不定量阶段。
     * 进度条将显示为跑马灯动画。
     */
    public static void setPhase(Phase phase) {
        currentPhase = phase;
        totalItems = 0;
        completedItems = 0;
        failedItems = 0;
        indeterminate = true;
    }

    /** 当前处理的模型名称。 */
    public static void setModelName(String name) {
        modelName = name != null ? name : "";
    }

    /** 推进一个进度项并更新当前项名称。 */
    public static void progress(String item) {
        completedItems++;
        if (item != null) currentItem = item;
    }

    /** 推进一个进度项（不更新名称）。 */
    public static void progress() {
        completedItems++;
    }

    /** 标记一项失败。 */
    public static void fail(String item) {
        failedItems++;
        if (item != null) currentItem = item;
    }

    /**
     * 重置当前模型的阶段进度为 IDLE。
     * <p>
     * <strong>不</strong>影响批处理状态（batchMode/batchTotal/batchCompleted）。
     * 批处理模式由 {@link #beginBatch(int)}/{@link #advanceBatch()}/{@link #endBatch()} 单独管理。
     * 批处理中的 startTime 会被保留，使总计时跨模型连续。
     */
    public static void reset() {
        currentPhase = Phase.IDLE;
        totalItems = 0;
        completedItems = 0;
        failedItems = 0;
        currentItem = "";
        modelName = "";
        indeterminate = false;
        if (!batchMode) {
            startTime = 0;
        }
        // 注意：batch 状态不由 reset 管理，避免 ModelLoadManager 在批量加载时
        // 因单个模型完成而错误地结束整个批次。
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
    public static int getTotalItems() { return totalItems; }
    public static int getCompletedItems() { return completedItems; }
    public static int getFailedItems() { return failedItems; }
    public static String getCurrentItem() { return currentItem; }
    public static long getStartTime() { return startTime; }
    public static String getModelName() { return modelName; }

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

    /** 已用时间格式 mm:ss。 */
    public static String getElapsed() {
        if (startTime == 0) return "";
        long elapsed = System.currentTimeMillis() - startTime;
        long secs = elapsed / 1000;
        long mins = secs / 60;
        secs %= 60;
        return String.format("%d:%02d", mins, secs);
    }
}
