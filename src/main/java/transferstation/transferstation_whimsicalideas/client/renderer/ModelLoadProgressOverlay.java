package transferstation.transferstation_whimsicalideas.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadProgress;

import java.util.List;

/**
 * 模型加载进度条 HUD 叠加层。
 * 屏幕底部居中，显示加载阶段、进度条、当前项等信息。
 * 支持不确定进度动画、批处理模式、失败计数、渐变消失等交互。
 *
 * 增强功能：
 * - 子阶段显示（如 PARSING: MDL/VVD/VTX, TEXTURING: VTF/VMT 等）
 * - 更详细的进度信息：处理项数/总数、当前文件、预估剩余时间
 * - 视觉指示器：缓存命中、原生 vs Java 解析器、材质加载进度
 * - 批处理进度显示
 * - 迷你旋转器 / 更详细的不确定进度动画
 * - 内存使用量显示
 * - 阶段耗时分解
 * - 更宽更明显的进度条
 * - 不同阶段的颜色编码
 *
 * 注册为 IGuiOverlay，仅当 ModelLoadProgress.isActive() 时显示，
 * 并在状态变为非活跃后以 1.5 秒渐变消失。
 */
public class ModelLoadProgressOverlay {

    // ---- 布局常量 ----
    private static final int BAR_WIDTH = 280;           // 加宽进度条
    private static final int BAR_HEIGHT = 8;            // 增高进度条
    private static final int BG_PADDING = 10;
    private static final int LINE_SPACING = 2;
    private static final int BORDER_WIDTH = 2;
    private static final int BAR_BOTTOM_MARGIN = 4;
    private static final int TITLE_BAR_GAP = 3;
    private static final int SECTION_GAP = 4;           // 章节间距

    // ---- 颜色常量 ----
    private static final int TEXT_COLOR = 0xFFF0F0F0;
    private static final int SUB_TEXT_COLOR = 0xFFB0B0B0;
    private static final int ACCENT_TEXT_COLOR = 0xFFE0E0E0;
    private static final int BAR_BG_COLOR = 0x44FFFFFF;
    private static final int BAR_OK_COLOR = 0xFF5CBF60;
    private static final int BAR_FAIL_COLOR = 0xFFE84C4C;
    private static final int BG_COLOR = 0xE60A0A0A;     // 稍微更不透明
    private static final int BORDER_COLOR = 0x44FFFFFF;
    private static final int FAIL_TEXT_COLOR = 0xFFFF5A5A;
    private static final int BAR_PCT_COLOR = 0xFFFFFFFF;
    private static final int PHASE_TIMING_COLOR = 0xFF88CC88;
    private static final int MEMORY_COLOR = 0xFF88AACC;
    private static final int CACHE_HIT_COLOR = 0xFF4CAF50;
    private static final int CACHE_MISS_COLOR = 0xFFFF9800;
    private static final int PARSER_NATIVE_COLOR = 0xFF2196F3;
    private static final int PARSER_JAVA_COLOR = 0xFF9C27B0;
    private static final int SUB_PHASE_COLOR = 0xFFCCCCCC;

    // ---- 不确定进度动画 ----
    private static final int INDETERMINATE_PERIOD_MS = 2000;
    private static final float INDETERMINATE_HIGHLIGHT_RATIO = 0.30f;
    private static final int INDETERMINATE_STRIPS = 9;   // 更多条纹，更平滑

    // 旋转器动画
    private static final char[] SPINNER_CHARS = {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};
    private static final int SPINNER_PERIOD_MS = 100;

    // ---- 渐变消失 ----
    private static final long FADE_DURATION_MS = 1500;

    // ---- 详细模式 ----
    private static boolean detailedMode = false;

    // ---- 脉冲动画 ----
    private static final float PULSE_MIN_ALPHA = 0.55f;
    private static final float PULSE_MAX_ALPHA = 1.0f;
    private static final long PULSE_PERIOD_MS = 1200;

    // ---- 阶段耗时条形图 ----
    private static final int BAR_CHART_WIDTH = 120;
    private static final int BAR_CHART_HEIGHT = 5;
    private static final int BAR_CHART_GAP = 2;

    // ---- 内存 Sparkline ----
    private static final int SPARKLINE_WIDTH = 100;
    private static final int SPARKLINE_HEIGHT = 16;

    // ---- 复杂度颜色 ----
    private static final int COMPLEXITY_LOW_COLOR = 0xFF4CAF50;
    private static final int COMPLEXITY_MED_COLOR = 0xFFFF9800;
    private static final int COMPLEXITY_HIGH_COLOR = 0xFFE84C4C;

    // 缓存的上一次活跃状态（用于渐变消失）
    private static String cachedTitle = "";
    private static String cachedSubPhase = "";
    private static String cachedItem = "";
    private static String cachedFile = "";
    private static float cachedProgress = -1f;
    private static float cachedSubProgress = -1f;
    private static int cachedFailed = 0;
    private static boolean wasActive = false;
    private static long fadeStartTime = 0;

    public static final IGuiOverlay INSTANCE = ModelLoadProgressOverlay::render;

    /** 切换详细模式（供键盘事件调用）。 */
    public static void toggleDetailedMode() {
        detailedMode = !detailedMode;
    }

    /** 当前是否处于详细模式。 */
    public static boolean isDetailedMode() {
        return detailedMode;
    }

    private static void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
                                int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (font == null) return;

        boolean isActive = ModelLoadProgress.isActive();

        if (isActive) {
            // ---- 正常渲染（活跃态） ----
            renderLive(guiGraphics, font, screenWidth, screenHeight);
            wasActive = true;
            fadeStartTime = 0;
        } else if (wasActive) {
            // ---- 渐变消失 ----
            if (fadeStartTime == 0) {
                fadeStartTime = System.currentTimeMillis();
            }
            long fadeElapsed = System.currentTimeMillis() - fadeStartTime;
            if (fadeElapsed >= FADE_DURATION_MS) {
                wasActive = false;
                return;
            }
            float alpha = 1.0f - (float) fadeElapsed / FADE_DURATION_MS;
            // Use simplified render for fade-out (no parser/cache/memory details)
            renderContent(guiGraphics, font, screenWidth, screenHeight,
                cachedTitle, cachedSubPhase, cachedItem, cachedFile,
                cachedProgress, cachedSubProgress, cachedFailed, alpha,
                0xFFFFFFFF, "", "", 0, 0, 0, 0, 0, 0, "", 1.0f);
        }
    }

    /**
     * 从 ModelLoadProgress 实时读取状态并渲染（活跃态）。
     * 同时更新缓存，供稍后渐变消失阶段使用。
     */
    private static void renderLive(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight) {
        ModelLoadProgress.Phase phase = ModelLoadProgress.getCurrentPhase();
        ModelLoadProgress.SubPhase subPhase = ModelLoadProgress.getCurrentSubPhase();
        String modelName = ModelLoadProgress.getModelName();
        String item = ModelLoadProgress.getCurrentItem();
        String file = ModelLoadProgress.getCurrentFile();
        float progress = ModelLoadProgress.getProgress();
        float subProgress = ModelLoadProgress.getSubPhaseProgress();
        int failed = ModelLoadProgress.getFailedItems();
        String elapsed = ModelLoadProgress.getElapsed();
        String eta = ModelLoadProgress.getEta();
        String parserType = ModelLoadProgress.getParserType();
        String cacheInfo = ModelLoadProgress.getCacheInfo();
        int processed = ModelLoadProgress.getProcessedCount();
        int total = ModelLoadProgress.getDisplayTotal();
        int texCurrent = ModelLoadProgress.getCurrentTextureIndex();
        int texTotal = ModelLoadProgress.getTotalTextures();
        long memUsage = ModelLoadProgress.getMemoryUsageBytes();
        long peakMem = ModelLoadProgress.getPeakMemoryUsageBytes();

        // 脉冲动画 alpha（当前活跃阶段高亮）
        float pulseAlpha = PULSE_MAX_ALPHA;
        if (phase != ModelLoadProgress.Phase.IDLE) {
            double t = (System.currentTimeMillis() % PULSE_PERIOD_MS) / (double) PULSE_PERIOD_MS;
            pulseAlpha = PULSE_MIN_ALPHA + (PULSE_MAX_ALPHA - PULSE_MIN_ALPHA)
                * (float) (0.5 + 0.5 * Math.sin(2.0 * Math.PI * t));
        }

        String phaseLabel = phase.getDisplay();
        int phaseColor = phase.getColor();
        String subPhaseLabel = subPhase != null ? subPhase.getDisplay() : "";

        // 构建标题行
        StringBuilder sb = new StringBuilder();

        // 批处理模式前缀 "Model X/Y"
        if (ModelLoadProgress.isBatch()) {
            int batchDone = ModelLoadProgress.getBatchCompleted();
            int batchTotal = ModelLoadProgress.getBatchTotal();
            if (batchTotal > 0 && batchDone < batchTotal) {
                sb.append("Model ").append(Math.min(batchDone + 1, batchTotal))
                  .append("/").append(batchTotal).append(" ");
            }
        }

        // 模型名称
        if (!modelName.isEmpty()) {
            sb.append(modelName).append(" ");
        }

        // 阶段标签（带颜色标记，实际渲染时分段绘制）
        if (!phaseLabel.isEmpty()) {
            sb.append("[").append(phaseLabel).append("] ");
        }

        // 失败计数
        if (failed > 0) {
            sb.append(failed).append(" failed ");
        }

        // 已用时间
        if (!elapsed.isEmpty()) {
            sb.append("Time: ").append(elapsed);
        }

        // ETA
        if (!eta.isEmpty()) {
            sb.append("  ETA: ").append(eta);
        }

        String title = sb.toString().trim();

        // 缓存当前状态供渐变消失使用
        cachedTitle = title;
        cachedSubPhase = subPhaseLabel;
        cachedItem = item;
        cachedFile = file;
        cachedProgress = progress;
        cachedSubProgress = subProgress;
        cachedFailed = failed;

        renderContent(guiGraphics, font, screenWidth, screenHeight,
            title, subPhaseLabel, item, file, progress, subProgress, failed, 1.0f,
            phaseColor, parserType, cacheInfo, processed, total, texCurrent, texTotal,
            memUsage, peakMem, elapsed, pulseAlpha);
    }

    /**
     * 核心渲染方法，绘制背景框、标题、进度条、子阶段、当前项、内存、阶段耗时等。
     *
     * @param alpha 全局透明度（1.0 = 不透明，0.0 = 完全透明）
     */
    private static void renderContent(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight,
                                       String title, String subPhaseLabel, String item, String file,
                                       float progress, float subProgress, int failed, float alpha,
                                       int phaseColor, String parserType, String cacheInfo,
                                       int processed, int total, int texCurrent, int texTotal,
                                       long memUsage, long peakMem, String elapsed, float pulseAlpha) {
        int centerX = screenWidth / 2;
        int baseY = screenHeight - 55;  // 稍微往上移一点，给更多内容留空间

        // 计算百分比文字和额外占宽
        String pctText = "";
        int pctExtraWidth = 0;
        boolean isIndeterminate = progress < 0;
        if (!isIndeterminate) {
            int pct = Math.round(progress * 100f);
            pctText = pct + "%";
            pctExtraWidth = 6 + font.width(pctText); // 间距 + 文字宽度
        }

        // 测量各元素宽度
        int titleWidth = font.width(title);
        int subPhaseWidth = subPhaseLabel.isEmpty() ? 0 : font.width(subPhaseLabel);
        int itemWidth = item.isEmpty() ? 0 : font.width(item);
        int fileWidth = file.isEmpty() ? 0 : font.width(file);
        int barSectionTotal = BAR_WIDTH + pctExtraWidth;
        int infoWidth = Math.max(processed > 0 ? font.width(processed + "/" + total) : 0,
                         texTotal > 0 ? font.width("Tex: " + texCurrent + "/" + texTotal) : 0);
        int memWidth = memUsage > 0 ? font.width("Mem: " + ModelLoadProgress.formatMemory(memUsage) +
            (peakMem > memUsage ? " / " + ModelLoadProgress.formatMemory(peakMem) : "")) : 0;
        int parserWidth = parserType.isEmpty() ? 0 : font.width(parserType);
        int cacheWidth = cacheInfo.isEmpty() ? 0 : font.width(cacheInfo);

        int contentWidth = Math.max(titleWidth, Math.max(subPhaseWidth,
            Math.max(itemWidth, Math.max(fileWidth, Math.max(barSectionTotal,
            Math.max(infoWidth, Math.max(memWidth, Math.max(parserWidth, cacheWidth))))))));
        int bgWidth = contentWidth + BG_PADDING * 2;
        int bgX = centerX - bgWidth / 2;
        int bgY = baseY;

        // 计算背景高度（动态）
        int bgHeight = BG_PADDING;
        if (!title.isEmpty()) bgHeight += font.lineHeight + LINE_SPACING;
        if (!subPhaseLabel.isEmpty()) bgHeight += font.lineHeight + LINE_SPACING;
        bgHeight += BAR_HEIGHT + BAR_BOTTOM_MARGIN;
        if (!item.isEmpty()) bgHeight += font.lineHeight + LINE_SPACING;
        if (!file.isEmpty() && !file.equals(item)) bgHeight += font.lineHeight + LINE_SPACING;
        if (processed > 0 || texTotal > 0) bgHeight += font.lineHeight + LINE_SPACING;
        if (memUsage > 0) bgHeight += font.lineHeight + LINE_SPACING;
        if (!parserType.isEmpty() || !cacheInfo.isEmpty()) bgHeight += font.lineHeight + LINE_SPACING;
        // 详细模式额外高度
        if (detailedMode && alpha >= 1.0f) {
            bgHeight += SECTION_GAP + font.lineHeight;  // 阶段耗时
            bgHeight += SPARKLINE_HEIGHT + LINE_SPACING + font.lineHeight;  // 内存 sparkline
            bgHeight += font.lineHeight + LINE_SPACING;  // 错误率
            bgHeight += font.lineHeight + LINE_SPACING;  // 复杂度
        }
        bgHeight += BG_PADDING;

        // ---- 绘制背景（带边框） ----
        // 外层：边框（使用阶段颜色）
        int borderColor = applyAlpha(phaseColor, alpha * 0.6f);
        guiGraphics.fill(bgX - BORDER_WIDTH, bgY - BORDER_WIDTH,
            bgX + bgWidth + BORDER_WIDTH, bgY + bgHeight + BORDER_WIDTH,
            borderColor);
        // 内层：半透明深色背景
        guiGraphics.fill(bgX, bgY, bgX + bgWidth, bgY + bgHeight,
            applyAlpha(BG_COLOR, alpha));

        int currentY = bgY + BG_PADDING;

        // -------- 标题行 --------
        if (!title.isEmpty()) {
            guiGraphics.drawString(font, title,
                centerX - titleWidth / 2, currentY,
                applyAlpha(TEXT_COLOR, alpha), false);
            currentY += font.lineHeight + LINE_SPACING;
        } else {
            currentY += LINE_SPACING;
        }

        // -------- 子阶段行 --------
        if (!subPhaseLabel.isEmpty()) {
            // 子阶段进度（如果有）
            String subPctText = "";
            if (subProgress >= 0 && total > 0) {
                int spct = Math.round(subProgress * 100f);
                subPctText = " (" + spct + "%)";
            }
            String fullSubPhase = subPhaseLabel + subPctText;
            int spWidth = font.width(fullSubPhase);
            guiGraphics.drawString(font, fullSubPhase,
                centerX - spWidth / 2, currentY,
                applyAlpha(SUB_PHASE_COLOR, alpha), false);
            currentY += font.lineHeight + LINE_SPACING;
        }

        // -------- 进度条 --------
        int barX = centerX - BAR_WIDTH / 2;

        // 进度条背景
        guiGraphics.fill(barX, currentY, barX + BAR_WIDTH, currentY + BAR_HEIGHT,
            applyAlpha(BAR_BG_COLOR, alpha));

        if (isIndeterminate) {
            // ---- 不确定进度：跑马灯高亮动画 + 旋转器 ----
            double t = (System.currentTimeMillis() % INDETERMINATE_PERIOD_MS)
                       / (double) INDETERMINATE_PERIOD_MS;
            double pos = t < 0.5 ? t * 2.0 : 2.0 - t * 2.0;

            int hlWidth = Math.round(BAR_WIDTH * INDETERMINATE_HIGHLIGHT_RATIO);
            int hlStart = barX + Math.round((BAR_WIDTH - hlWidth) * (float) pos);
            int hlEnd = hlStart + hlWidth;

            // 多层渐变条模拟透明度渐变（中心亮，边缘淡）
            int strips = INDETERMINATE_STRIPS;
            int stripWidth = Math.max(1, hlWidth / strips);
            for (int i = 0; i < strips; i++) {
                float centerDist = (strips > 1)
                    ? Math.abs(i - (strips - 1) / 2f) / ((strips - 1) / 2f)
                    : 1.0f;
                float stripFactor = 1.0f - centerDist * centerDist; // 二次衰减
                int stripAlpha = Math.round((0.25f + 0.75f * stripFactor) * 255);
                stripAlpha = Math.min(255, Math.max(0, stripAlpha));
                int stripColor = (stripAlpha << 24) | (phaseColor & 0x00FFFFFF);
                int sx = hlStart + i * stripWidth;
                int ex = Math.min(sx + stripWidth, hlEnd);
                if (ex > sx) {
                    guiGraphics.fill(sx, currentY, ex, currentY + BAR_HEIGHT,
                        applyAlpha(stripColor, alpha));
                }
            }

            // 旋转器（进度条右侧）
            int spinnerIdx = (int) ((System.currentTimeMillis() % (SPINNER_CHARS.length * SPINNER_PERIOD_MS)) / SPINNER_PERIOD_MS);
            char spinner = SPINNER_CHARS[spinnerIdx % SPINNER_CHARS.length];
            guiGraphics.drawString(font, String.valueOf(spinner),
                barX + BAR_WIDTH + 6, currentY - 1,
                applyAlpha(phaseColor, alpha), false);
        } else if (progress > 0) {
            // ---- 确定进度：填充条 ----
            int fillWidth = Math.round(BAR_WIDTH * progress);
            int barColor = failed > 0 ? BAR_FAIL_COLOR : phaseColor;
            guiGraphics.fill(barX, currentY, barX + fillWidth, currentY + BAR_HEIGHT,
                applyAlpha(barColor, alpha));

            // 进度条上的光泽效果（可选）
            if (fillWidth > 10) {
                int shineWidth = Math.min(fillWidth, 30);
                int shineAlpha = (int) (0.3f * 255 * alpha);
                int shineColor = (shineAlpha << 24) | 0x00FFFFFF;
                guiGraphics.fill(barX + fillWidth - shineWidth, currentY,
                    barX + fillWidth, currentY + BAR_HEIGHT, shineColor);
            }
        }

        // ---- 百分比文字（进度条右侧） ----
        if (!isIndeterminate && !pctText.isEmpty()) {
            int pctColor = failed > 0 ? applyAlpha(BAR_FAIL_COLOR, alpha)
                                      : applyAlpha(BAR_PCT_COLOR, alpha);
            guiGraphics.drawString(font, pctText,
                barX + BAR_WIDTH + 6, currentY - 1,
                pctColor, false);
        }

        currentY += BAR_HEIGHT + BAR_BOTTOM_MARGIN;

        // -------- 当前项名称（截断显示） --------
        if (!item.isEmpty()) {
            String displayItem = item;
            int maxItemWidth = bgWidth - BG_PADDING * 2;
            if (font.width(displayItem) > maxItemWidth) {
                int truncWidth = Math.max(0, maxItemWidth - font.width("..."));
                displayItem = font.plainSubstrByWidth(displayItem, truncWidth) + "...";
            }
            guiGraphics.drawString(font, displayItem,
                centerX - font.width(displayItem) / 2, currentY,
                applyAlpha(SUB_TEXT_COLOR, alpha), false);
            currentY += font.lineHeight + LINE_SPACING;
        }

        // -------- 当前文件路径（如果与项不同） --------
        if (!file.isEmpty() && !file.equals(item)) {
            String displayFile = file;
            int maxFileWidth = bgWidth - BG_PADDING * 2;
            if (font.width(displayFile) > maxFileWidth) {
                int truncWidth = Math.max(0, maxFileWidth - font.width("..."));
                displayFile = font.plainSubstrByWidth(displayFile, truncWidth) + "...";
            }
            guiGraphics.drawString(font, displayFile,
                centerX - font.width(displayFile) / 2, currentY,
                applyAlpha(0xFF888888, alpha), false);  // 更淡的颜色
            currentY += font.lineHeight + LINE_SPACING;
        }

        // -------- 进度详情：已处理/总数、材质进度 --------
        boolean showProgressDetail = processed > 0 || texTotal > 0;
        if (showProgressDetail) {
            StringBuilder detailSb = new StringBuilder();
            if (processed > 0 && total > 0) {
                detailSb.append(processed).append("/").append(total);
            }
            if (texTotal > 0) {
                if (detailSb.length() > 0) detailSb.append("  ");
                detailSb.append("Tex: ").append(texCurrent).append("/").append(texTotal);
            }
            String detail = detailSb.toString();
            int detailWidth = font.width(detail);
            guiGraphics.drawString(font, detail,
                centerX - detailWidth / 2, currentY,
                applyAlpha(ACCENT_TEXT_COLOR, alpha), false);
            currentY += font.lineHeight + LINE_SPACING;
        }

        // -------- 内存使用 --------
        if (memUsage > 0) {
            String memText = "Mem: " + ModelLoadProgress.formatMemory(memUsage);
            if (peakMem > memUsage) {
                memText += " / " + ModelLoadProgress.formatMemory(peakMem);
            }
            int memWidth2 = font.width(memText);
            guiGraphics.drawString(font, memText,
                centerX - memWidth2 / 2, currentY,
                applyAlpha(MEMORY_COLOR, alpha), false);
            currentY += font.lineHeight + LINE_SPACING;
        }

        // -------- 解析器类型 / 缓存信息 --------
        if (!parserType.isEmpty() || !cacheInfo.isEmpty()) {
            StringBuilder infoSb = new StringBuilder();
            if (!parserType.isEmpty()) {
                infoSb.append("Parser: ").append(parserType);
            }
            if (!cacheInfo.isEmpty()) {
                if (infoSb.length() > 0) infoSb.append("  |  ");
                infoSb.append(cacheInfo);
            }
            String infoText = infoSb.toString();
            int infoWidth2 = font.width(infoText);
            int infoColor = cacheInfo.toLowerCase().contains("hit") ? CACHE_HIT_COLOR :
                            cacheInfo.toLowerCase().contains("miss") ? CACHE_MISS_COLOR :
                            parserType.toLowerCase().contains("native") ? PARSER_NATIVE_COLOR :
                            parserType.toLowerCase().contains("java") ? PARSER_JAVA_COLOR :
                            ACCENT_TEXT_COLOR;
            guiGraphics.drawString(font, infoText,
                centerX - infoWidth2 / 2, currentY,
                applyAlpha(infoColor, alpha), false);
        }

        // ======== 详细模式专属区域 ========
        if (!detailedMode || alpha < 1.0f) return;

        currentY += font.lineHeight + SECTION_GAP;

        // ---- 1. 阶段耗时条形图 ----
        renderPhaseChart(guiGraphics, font, centerX, currentY, bgWidth, alpha);
        currentY += font.lineHeight + SECTION_GAP;

        // ---- 2. 内存使用 Sparkline ----
        renderMemorySparkline(guiGraphics, font, centerX, currentY, memUsage, peakMem, alpha);
        currentY += SPARKLINE_HEIGHT + LINE_SPACING;

        // ---- 3. 错误率指示器 ----
        renderErrorRate(guiGraphics, font, centerX, currentY, failed, processed, alpha);
        currentY += font.lineHeight + LINE_SPACING;

        // ---- 4. 模型复杂度指标 ----
        renderComplexity(guiGraphics, font, centerX, currentY, alpha);
    }

    /**
     * 绘制阶段耗时条形图。
     * 水平排列 SCANNING / PARSING / TEXTURING / BUILDING 四个阶段的耗时比例。
     */
    private static void renderPhaseChart(GuiGraphics guiGraphics, Font font,
                                          int centerX, int y, int bgWidth, float alpha) {
        long[] durations = new long[4];
        long totalDuration = 0;
        for (int i = 1; i <= 4; i++) {
            ModelLoadProgress.Phase p = ModelLoadProgress.Phase.values()[i];
            durations[i - 1] = ModelLoadProgress.getPhaseDuration(p);
            totalDuration += durations[i - 1];
        }

        if (totalDuration <= 0) return;

        String label = "Phase Times:";
        int labelWidth = font.width(label);
        int chartStartX = centerX - labelWidth / 2;
        guiGraphics.drawString(font, label, chartStartX, y,
            applyAlpha(SUB_TEXT_COLOR, alpha), false);

        int barY = y + font.lineHeight + 1;
        int totalBarWidth = Math.min(BAR_CHART_WIDTH, bgWidth - BG_PADDING * 2);
        int barX = centerX - totalBarWidth / 2;

        // 背景
        guiGraphics.fill(barX, barY, barX + totalBarWidth, barY + BAR_CHART_HEIGHT,
            applyAlpha(BAR_BG_COLOR, alpha));

        // 各阶段填充
        int[] phaseColors = {
            0xFF4CAF50, // SCANNING - 绿
            0xFF2196F3, // PARSING - 蓝
            0xFFFF9800, // TEXTURING - 橙
            0xFF9C27B0  // BUILDING - 紫
        };
        String[] phaseNames = {"SCAN", "PARSE", "TEX", "BUILD"};

        int filledX = barX;
        for (int i = 0; i < 4; i++) {
            if (durations[i] <= 0) continue;
            int w = Math.max(1, (int) ((double) durations[i] / totalDuration * totalBarWidth));
            if (filledX + w > barX + totalBarWidth) w = barX + totalBarWidth - filledX;
            guiGraphics.fill(filledX, barY, filledX + w, barY + BAR_CHART_HEIGHT,
                applyAlpha(phaseColors[i], alpha));
            filledX += w;
        }

        // 阶段名称标签（均匀分布）
        int labelSpacing = totalBarWidth / 4;
        for (int i = 0; i < 4; i++) {
            String name = phaseNames[i];
            int nameWidth = font.width(name);
            int nameX = barX + i * labelSpacing + labelSpacing / 2 - nameWidth / 2;
            guiGraphics.drawString(font, name, nameX, barY + BAR_CHART_HEIGHT + 2,
                applyAlpha(phaseColors[i], alpha * 0.7f), false);
        }
    }

    /**
     * 绘制内存使用 sparkline（迷你折线图）。
     */
    private static void renderMemorySparkline(GuiGraphics guiGraphics, Font font,
                                               int centerX, int y, long memUsage, long peakMem, float alpha) {
        List<Long> history = ModelLoadProgress.getMemoryHistory();
        if (history.isEmpty()) return;

        String memLabel = "Mem: " + ModelLoadProgress.formatMemory(memUsage);
        if (peakMem > memUsage) {
            memLabel += " / " + ModelLoadProgress.formatMemory(peakMem);
        }
        int labelWidth = font.width(memLabel);
        int labelX = centerX - (labelWidth + SPARKLINE_WIDTH + 8) / 2;
        guiGraphics.drawString(font, memLabel, labelX, y,
            applyAlpha(MEMORY_COLOR, alpha), false);

        int sparkX = labelX + labelWidth + 8;
        int sparkY = y + 1;

        // 背景
        guiGraphics.fill(sparkX, sparkY, sparkX + SPARKLINE_WIDTH, sparkY + SPARKLINE_HEIGHT,
            applyAlpha(BAR_BG_COLOR, alpha));

        // 计算范围
        long maxVal = 1;
        for (Long v : history) if (v > maxVal) maxVal = v;

        // 绘制折线
        int points = history.size();
        if (points < 2) return;
        float xStep = (float) (SPARKLINE_WIDTH - 1) / (points - 1);
        int prevX = sparkX;
        int prevY = sparkY + SPARKLINE_HEIGHT - 1;
        for (int i = 0; i < points; i++) {
            int cx = sparkX + Math.round(i * xStep);
            int normalized = (int) ((history.get(i) * (long) (SPARKLINE_HEIGHT - 2)) / maxVal);
            int cy = sparkY + SPARKLINE_HEIGHT - 2 - normalized;
            // 简单线段绘制（水平 + 垂直像素）
            guiGraphics.fill(prevX, Math.min(prevY, cy), Math.max(prevX, cx) + 1, Math.max(prevY, cy) + 1,
                applyAlpha(MEMORY_COLOR, alpha * 0.9f));
            prevX = cx;
            prevY = cy;
        }

        // 右侧当前值小标签
        String curMem = ModelLoadProgress.formatMemory(memUsage);
        guiGraphics.drawString(font, curMem, sparkX + SPARKLINE_WIDTH + 4, y,
            applyAlpha(MEMORY_COLOR, alpha * 0.6f), false);
    }

    /**
     * 绘制错误率指示器：成功/失败比率 + 颜色编码。
     */
    private static void renderErrorRate(GuiGraphics guiGraphics, Font font,
                                         int centerX, int y, int failed, int processed, float alpha) {
        int total = processed + failed;
        if (total <= 0) return;

        float failRate = (float) failed / total;
        int failPct = Math.round(failRate * 100);

        String errText;
        int errColor;
        if (failed == 0) {
            errText = "Errors: 0 (" + total + " ok)";
            errColor = COMPLEXITY_LOW_COLOR;
        } else if (failRate < 0.1f) {
            errText = "Errors: " + failed + "/" + total + " (" + failPct + "%)";
            errColor = COMPLEXITY_MED_COLOR;
        } else {
            errText = "Errors: " + failed + "/" + total + " (" + failPct + "%)";
            errColor = COMPLEXITY_HIGH_COLOR;
        }

        int errWidth = font.width(errText);
        guiGraphics.drawString(font, errText, centerX - errWidth / 2, y,
            applyAlpha(errColor, alpha), false);

        // 小型错误率条
        int miniBarWidth = 80;
        int miniBarHeight = 3;
        int mbX = centerX + errWidth / 2 + 6;
        int mbY = y + 2;
        guiGraphics.fill(mbX, mbY, mbX + miniBarWidth, mbY + miniBarHeight,
            applyAlpha(BAR_BG_COLOR, alpha));
        if (failed > 0) {
            int failW = Math.max(1, Math.round(miniBarWidth * failRate));
            guiGraphics.fill(mbX, mbY, mbX + failW, mbY + miniBarHeight,
                applyAlpha(BAR_FAIL_COLOR, alpha));
        }
        int okW = miniBarWidth - (failed > 0 ? Math.max(1, Math.round(miniBarWidth * failRate)) : 0);
        if (okW > 0) {
            guiGraphics.fill(mbX + miniBarWidth - okW, mbY, mbX + miniBarWidth, mbY + miniBarHeight,
                applyAlpha(BAR_OK_COLOR, alpha * 0.5f));
        }
    }

    /**
     * 绘制模型复杂度指标：顶点数、三角形数、骨骼数。
     */
    private static void renderComplexity(GuiGraphics guiGraphics, Font font,
                                          int centerX, int y, float alpha) {
        int verts = ModelLoadProgress.getVertexCount();
        int tris = ModelLoadProgress.getTriangleCount();
        int bones = ModelLoadProgress.getBoneCount();

        if (verts == 0 && tris == 0 && bones == 0) return;

        StringBuilder sb = new StringBuilder();
        sb.append("V:").append(formatCount(verts));
        sb.append("  T:").append(formatCount(tris));
        sb.append("  B:").append(bones);

        String text = sb.toString();
        int textWidth = font.width(text);

        // 根据复杂度选择颜色
        int complexityColor;
        if (verts < 10000 && tris < 10000) {
            complexityColor = COMPLEXITY_LOW_COLOR;
        } else if (verts < 50000 && tris < 50000) {
            complexityColor = COMPLEXITY_MED_COLOR;
        } else {
            complexityColor = COMPLEXITY_HIGH_COLOR;
        }

        guiGraphics.drawString(font, text, centerX - textWidth / 2, y,
            applyAlpha(complexityColor, alpha), false);
    }

    /** 格式化大数字（如 123456 → "123K"）。 */
    private static String formatCount(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    /**
     * 将 ARGB 颜色的 Alpha 通道乘以一个系数，实现全局淡入/淡出。
     */
    private static int applyAlpha(int color, float alpha) {
        if (alpha >= 1.0f) return color;
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        a = Math.round(a * alpha);
        a = Math.min(255, Math.max(0, a));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}