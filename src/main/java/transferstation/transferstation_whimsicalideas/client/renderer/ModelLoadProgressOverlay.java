package transferstation.transferstation_whimsicalideas.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadProgress;

/**
 * 模型加载进度条 HUD 叠加层。
 * 屏幕底部居中，显示加载阶段、进度条、当前项等信息。
 * 支持不确定进度动画、批处理模式、失败计数、渐变消失等交互。
 *
 * 注册为 IGuiOverlay，仅当 ModelLoadProgress.isActive() 时显示，
 * 并在状态变为非活跃后以 1.5 秒渐变消失。
 */
public class ModelLoadProgressOverlay {

    // ---- 布局常量 ----
    private static final int BAR_WIDTH = 160;
    private static final int BAR_HEIGHT = 5;
    private static final int BG_PADDING = 8;
    private static final int LINE_SPACING = 3;
    private static final int BORDER_WIDTH = 2;
    private static final int BAR_BOTTOM_MARGIN = 3;
    private static final int TITLE_BAR_GAP = 2;

    // ---- 颜色常量 ----
    private static final int TEXT_COLOR = 0xFFF0F0F0;
    private static final int SUB_TEXT_COLOR = 0xFFB0B0B0;
    private static final int BAR_BG_COLOR = 0x44FFFFFF;
    private static final int BAR_OK_COLOR = 0xFF5CBF60;
    private static final int BAR_FAIL_COLOR = 0xFFE84C4C;
    private static final int BG_COLOR = 0xCC0A0A0A;
    private static final int BORDER_COLOR = 0x33FFFFFF;
    private static final int FAIL_TEXT_COLOR = 0xFFFF5A5A;
    private static final int BAR_PCT_COLOR = 0xFFFFFFFF;

    // ---- 不确定进度动画 ----
    private static final int INDETERMINATE_PERIOD_MS = 2000;
    private static final float INDETERMINATE_HIGHLIGHT_RATIO = 0.30f;
    private static final int INDETERMINATE_STRIPS = 7;

    // ---- 渐变消失 ----
    private static final long FADE_DURATION_MS = 1500;

    // 缓存的上一次活跃状态（用于渐变消失）
    private static String cachedTitle = "";
    private static String cachedItem = "";
    private static float cachedProgress = -1f;
    private static int cachedFailed = 0;
    private static boolean wasActive = false;
    private static long fadeStartTime = 0;

    public static final IGuiOverlay INSTANCE = ModelLoadProgressOverlay::render;

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
            renderContent(guiGraphics, font, screenWidth, screenHeight,
                cachedTitle, cachedItem, cachedProgress, cachedFailed, alpha);
        }
    }

    /**
     * 从 ModelLoadProgress 实时读取状态并渲染（活跃态）。
     * 同时更新缓存，供稍后渐变消失阶段使用。
     */
    private static void renderLive(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight) {
        ModelLoadProgress.Phase phase = ModelLoadProgress.getCurrentPhase();
        String modelName = ModelLoadProgress.getModelName();
        String item = ModelLoadProgress.getCurrentItem();
        float progress = ModelLoadProgress.getProgress();
        int failed = ModelLoadProgress.getFailedItems();
        String elapsed = ModelLoadProgress.getElapsed();

        String phaseLabel = phase.getDisplay();

        // 构建标题行
        StringBuilder sb = new StringBuilder();

        // 批处理模式前缀 "Model X/Y"（batchDone >= batchTotal 时不显示）
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

        // 阶段标签
        if (!phaseLabel.isEmpty()) {
            sb.append("[").append(phaseLabel).append("] ");
        }

        // 失败计数（用红色绘制，文字形式 "X failed"）
        if (failed > 0) {
            sb.append(failed).append(" failed ");
        }

        // 已用时间 "Time: mm:ss"
        if (!elapsed.isEmpty()) {
            sb.append("Time: ").append(elapsed);
        }

        String title = sb.toString().trim();

        // 缓存当前状态供渐变消失使用
        cachedTitle = title;
        cachedItem = item;
        cachedProgress = progress;
        cachedFailed = failed;

        renderContent(guiGraphics, font, screenWidth, screenHeight, title, item, progress, failed, 1.0f);
    }

    /**
     * 核心渲染方法，绘制背景框、标题、进度条和当前项。
     *
     * @param alpha 全局透明度（1.0 = 不透明，0.0 = 完全透明）
     */
    private static void renderContent(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight,
                                       String title, String item, float progress, int failed, float alpha) {
        int centerX = screenWidth / 2;
        int baseY = screenHeight - 45;

        // 计算百分比文字和额外占宽
        String pctText = "";
        int pctExtraWidth = 0;
        boolean isIndeterminate = progress < 0;
        if (!isIndeterminate) {
            int pct = Math.round(progress * 100f);
            pctText = pct + "%";
            pctExtraWidth = 4 + font.width(pctText); // 4px 间距 + 文字宽度
        }

        // 测量各元素宽度
        int titleWidth = font.width(title);
        int itemWidth = item.isEmpty() ? 0 : font.width(item);
        int barSectionTotal = BAR_WIDTH + pctExtraWidth;
        int contentWidth = Math.max(titleWidth, Math.max(itemWidth, barSectionTotal));
        int bgWidth = contentWidth + BG_PADDING * 2;
        int bgX = centerX - bgWidth / 2;
        int bgY = baseY;

        // 计算背景高度
        int bgHeight = BG_PADDING;
        if (!title.isEmpty()) bgHeight += font.lineHeight + LINE_SPACING;
        bgHeight += BAR_HEIGHT + BAR_BOTTOM_MARGIN;
        if (!item.isEmpty()) bgHeight += font.lineHeight + LINE_SPACING;
        bgHeight += BG_PADDING;

        // ---- 绘制背景（带边框） ----
        // 外层：边框（2px 半透明白色线框）
        guiGraphics.fill(bgX - BORDER_WIDTH, bgY - BORDER_WIDTH,
            bgX + bgWidth + BORDER_WIDTH, bgY + bgHeight + BORDER_WIDTH,
            applyAlpha(BORDER_COLOR, alpha));
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

        // -------- 进度条 --------
        int barX = centerX - BAR_WIDTH / 2;

        // 进度条背景
        guiGraphics.fill(barX, currentY, barX + BAR_WIDTH, currentY + BAR_HEIGHT,
            applyAlpha(BAR_BG_COLOR, alpha));

        if (isIndeterminate) {
            // ---- 不确定进度：跑马灯高亮动画 ----
            double t = (System.currentTimeMillis() % INDETERMINATE_PERIOD_MS)
                       / (double) INDETERMINATE_PERIOD_MS;
            // 三角波：0 → 1 → 0 往返
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
                int stripColor = (stripAlpha << 24) | (BAR_OK_COLOR & 0x00FFFFFF);
                int sx = hlStart + i * stripWidth;
                int ex = Math.min(sx + stripWidth, hlEnd);
                if (ex > sx) {
                    guiGraphics.fill(sx, currentY, ex, currentY + BAR_HEIGHT,
                        applyAlpha(stripColor, alpha));
                }
            }
        } else if (progress > 0) {
            // ---- 确定进度：填充条 ----
            int fillWidth = Math.round(BAR_WIDTH * progress);
            int barColor = failed > 0 ? BAR_FAIL_COLOR : BAR_OK_COLOR;
            guiGraphics.fill(barX, currentY, barX + fillWidth, currentY + BAR_HEIGHT,
                applyAlpha(barColor, alpha));
        }

        // ---- 百分比文字（进度条右侧） ----
        if (!isIndeterminate && !pctText.isEmpty()) {
            int pctColor = failed > 0 ? applyAlpha(BAR_FAIL_COLOR, alpha)
                                      : applyAlpha(BAR_PCT_COLOR, alpha);
            guiGraphics.drawString(font, pctText,
                barX + BAR_WIDTH + 4, currentY - 1,
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
        }
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
