package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.ColorUtils;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的纹理解析状态跟踪器和颜色解析器。
 * <p>
 * 用于确保在纹理尚未被完全解析时不会产生无效的颜色/纹理引用。
 * 每个纹理路径都关联一个 {@link ColorUtils.TextureParseState}，解析器会根据状态决定：
 * <ul>
 *   <li>COMPLETE - 可以安全使用该纹理的颜色</li>
 *   <li>PARTIAL - 可以使用但需要降级处理</li>
 *   <li>FAILED/UNPARSED - 必须使用回退颜色或跳过</li>
 * </ul>
 */
public class TextureColorResolver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 纹理路径 -> 解析状态 */
    private final Map<String, TextureEntry> entries = new ConcurrentHashMap<>();

    /** 线程安全的纹理注册表（替代 ModelLoadManager 的 HashMap） */
    private final Map<String, ResourceLocation> textureRegistry = new ConcurrentHashMap<>();

    /**
     * 单个纹理条目，记录解析状态和关联数据
     */
    public static class TextureEntry {
        private volatile ColorUtils.TextureParseState state;
        private volatile ResourceLocation resourceLocation;
        private volatile BufferedImage image;
        private volatile int cachedColor;
        private volatile boolean translucent;
        private volatile boolean alphaTest;
        private volatile boolean noCull;

        public TextureEntry(String path) {
            this.state = ColorUtils.TextureParseState.UNPARSED;
            this.resourceLocation = null;
            this.image = null;
            this.cachedColor = 0;
        }

        public ColorUtils.TextureParseState getState() { return state; }
        public void setState(ColorUtils.TextureParseState state) { this.state = state; }

        public ResourceLocation getResourceLocation() { return resourceLocation; }
        public void setResourceLocation(ResourceLocation loc) { this.resourceLocation = loc; }

        public BufferedImage getImage() { return image; }
        public void setImage(BufferedImage img) { this.image = img; }

        public int getCachedColor() { return cachedColor; }
        public void setCachedColor(int color) { this.cachedColor = color; }

        public boolean isTranslucent() { return translucent; }
        public void setTranslucent(boolean v) { this.translucent = v; }

        public boolean isAlphaTest() { return alphaTest; }
        public void setAlphaTest(boolean v) { this.alphaTest = v; }

        public boolean isNoCull() { return noCull; }
        public void setNoCull(boolean v) { this.noCull = v; }

        public boolean isUsable() { return state.isUsable(); }
    }

    // ==================== 状态管理 ====================

    /**
     * 注册一个新的纹理路径，初始状态为 UNPARSED
     */
    public TextureEntry register(String texturePath) {
        return entries.computeIfAbsent(texturePath, TextureEntry::new);
    }

    /**
     * 获取纹理条目，不存在则返回 null
     */
    public TextureEntry getEntry(String texturePath) {
        return entries.get(texturePath);
    }

    /**
     * 获取纹理的解析状态
     */
    public ColorUtils.TextureParseState getState(String texturePath) {
        TextureEntry entry = entries.get(texturePath);
        return entry != null ? entry.getState() : ColorUtils.TextureParseState.UNPARSED;
    }

    /**
     * 标记纹理解析完成
     */
    public void markComplete(String texturePath, ResourceLocation loc, int color,
                             boolean translucent, boolean alphaTest, boolean noCull) {
        TextureEntry entry = register(texturePath);
        entry.setState(ColorUtils.TextureParseState.COMPLETE);
        entry.setResourceLocation(loc);
        entry.setCachedColor(color);
        entry.setTranslucent(translucent);
        entry.setAlphaTest(alphaTest);
        entry.setNoCull(noCull);
        if (loc != null) {
            textureRegistry.put(texturePath, loc);
        }
    }

    /**
     * 标记纹理解析完成（仅位置和颜色，无材质属性）
     */
    public void markComplete(String texturePath, ResourceLocation loc, int color) {
        markComplete(texturePath, loc, color, false, false, false);
    }

    /**
     * 标记纹理部分解析成功（有图像但可能缺少材质属性）
     */
    public void markPartial(String texturePath, ResourceLocation loc, BufferedImage image) {
        TextureEntry entry = register(texturePath);
        int color = extractAverageColor(image);
        entry.setState(ColorUtils.TextureParseState.PARTIAL);
        entry.setResourceLocation(loc);
        entry.setCachedColor(color);
        entry.setTranslucent(false);
        entry.setAlphaTest(false);
        entry.setNoCull(false);
        if (loc != null) {
            textureRegistry.put(texturePath, loc);
        }
        entry.setImage(null);
    }

    /**
     * 标记纹理解析失败
     */
    public void markFailed(String texturePath, String reason) {
        TextureEntry entry = register(texturePath);
        entry.setState(ColorUtils.TextureParseState.FAILED);
        entry.setCachedColor(ColorUtils.FALLBACK_TEXTURE);
        LOGGER.warn("[TextureColorResolver] Texture parse FAILED for '{}': {}", texturePath, reason);
    }

    /**
     * 标记纹理为未解析
     */
    public void markUnparsed(String texturePath) {
        TextureEntry entry = register(texturePath);
        entry.setState(ColorUtils.TextureParseState.UNPARSED);
    }

    // ==================== 安全解析方法 ====================

    /**
     * 安全地从 VTF 图像数据创建纹理并记录状态。
     * 如果解析过程中的任何步骤失败，会记录失败状态并返回空结果。
     */
    public Optional<TextureEntry> safeResolveTexture(
            String texturePath,
            VtfParser.VtfImageData vtfData,
            VmtParser.VmtMaterial vmtMaterial,
            TextureParseStateTracker parseTracker) {

        if (vtfData == null || vtfData.image == null) {
            markFailed(texturePath, "VTF data or image is null");
            if (parseTracker != null) parseTracker.incrementFailed();
            return Optional.empty();
        }

        BufferedImage image = vtfData.image;
        if (image.getWidth() <= 0 || image.getHeight() <= 0) {
            markFailed(texturePath, "Invalid image dimensions: " + image.getWidth() + "x" + image.getHeight());
            if (parseTracker != null) parseTracker.incrementFailed();
            return Optional.empty();
        }

        boolean translucent = false;
        boolean alphaTest = false;
        boolean noCull = false;
        if (vmtMaterial != null) {
            translucent = vmtMaterial.isTransparent();
            alphaTest = vmtMaterial.isAlphaTest();
            noCull = vmtMaterial.isNoCull();
        }

        int color = extractAverageColor(image);
        if (!ColorUtils.isValidColor(color)) {
            color = ColorUtils.FALLBACK_TEXTURE;
        }

        TextureEntry entry = register(texturePath);
        markComplete(texturePath, null, color, translucent, alphaTest, noCull);
        entry.setImage(null);

        if (parseTracker != null) parseTracker.incrementResolved();
        return Optional.of(entry);
    }

    /**
     * 安全地获取纹理的 ResourceLocation。
     * 如果纹理未完全解析，返回 Optional.empty()。
     */
    public Optional<ResourceLocation> safeGetTextureLocation(String texturePath) {
        TextureEntry entry = entries.get(texturePath);
        if (entry == null || !entry.isUsable()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.getResourceLocation());
    }

    /**
     * 获取纹理颜色，如果未解析则返回默认颜色
     */
    public int getColorOrDefault(String texturePath, int defaultColor) {
        TextureEntry entry = entries.get(texturePath);
        if (entry == null || !entry.isUsable()) {
            return defaultColor;
        }
        int color = entry.getCachedColor();
        return ColorUtils.isValidColor(color) ? color : defaultColor;
    }

    /**
     * 获取纹理的渲染属性（translucent/alphaTest/noCull），带安全检查
     */
    public TextureRenderProps getRenderProps(String texturePath) {
        TextureEntry entry = entries.get(texturePath);
        if (entry == null || !entry.isUsable()) {
            return TextureRenderProps.DEFAULT;
        }
        return new TextureRenderProps(
            entry.isTranslucent(),
            entry.isAlphaTest(),
            entry.isNoCull()
        );
    }

    // ==================== 纹理注册表操作 ====================

    /**
     * 注册纹理到 Minecraft 纹理管理器（线程安全）
     */
    public ResourceLocation registerToManager(String key, BufferedImage image) {
        String regKey = "gmod_" + key.replace('/', '_').replace('\\', '_')
            .replace('.', '_').toLowerCase(java.util.Locale.ROOT);
        ResourceLocation existing = textureRegistry.get(regKey);
        if (existing != null) return existing;

        ResourceLocation loc = ResourceLocation.parse(
            "transferstation_whimsicalideas:textures/generated/" + regKey);
        try {
            com.mojang.blaze3d.platform.NativeImage nativeImage = bufferedImageToNativeImage(image);
            net.minecraft.client.renderer.texture.DynamicTexture dynamicTex =
                new net.minecraft.client.renderer.texture.DynamicTexture(nativeImage);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.isSameThread()) {
                mc.getTextureManager().register(loc, dynamicTex);
            } else {
                mc.execute(() -> mc.getTextureManager().register(loc, dynamicTex));
            }
            textureRegistry.put(regKey, loc);
            LOGGER.debug("[TextureColorResolver] Registered texture: {} ({}x{})",
                loc, image.getWidth(), image.getHeight());
        } catch (Exception e) {
            LOGGER.warn("[TextureColorResolver] Failed to register texture {}: {}", regKey, e.getMessage());
        }
        return loc;
    }

    /**
     * 检查纹理是否已注册
     */
    public boolean isRegistered(String key) {
        String regKey = "gmod_" + key.replace('/', '_').replace('\\', '_')
            .replace('.', '_').toLowerCase(java.util.Locale.ROOT);
        return textureRegistry.containsKey(regKey);
    }

    /**
     * 获取已注册的 ResourceLocation
     */
    public ResourceLocation getRegistered(String key) {
        String regKey = "gmod_" + key.replace('/', '_').replace('\\', '_')
            .replace('.', '_').toLowerCase(java.util.Locale.ROOT);
        return textureRegistry.get(regKey);
    }

    // ==================== 缓存管理 ====================

    /**
     * 清除所有缓存
     */
    public void clearAll() {
        for (TextureEntry entry : entries.values()) {
            entry.setImage(null);
        }
        entries.clear();
        textureRegistry.clear();
        LOGGER.info("[TextureColorResolver] Cleared all entries and texture registry");
    }

    /**
     * 清除无用的条目（FAILED 和 UNPARSED 状态），释放内存
     */
    public int trimStale() {
        int removed = 0;
        var iter = entries.entrySet().iterator();
        while (iter.hasNext()) {
            var e = iter.next();
            TextureEntry entry = e.getValue();
            ColorUtils.TextureParseState state = entry.getState();
            if (state == ColorUtils.TextureParseState.FAILED || state == ColorUtils.TextureParseState.UNPARSED) {
                entry.setImage(null);
                iter.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("[TextureColorResolver] Trimmed {} stale entries", removed);
        }
        return removed;
    }

    /**
     * 获取统计信息
     */
    public ParseStatistics getStatistics() {
        int unparsed = 0, partial = 0, complete = 0, failed = 0;
        for (TextureEntry entry : entries.values()) {
            switch (entry.getState()) {
                case UNPARSED -> unparsed++;
                case PARTIAL -> partial++;
                case COMPLETE -> complete++;
                case FAILED -> failed++;
            }
        }
        return new ParseStatistics(unparsed, partial, complete, failed, textureRegistry.size());
    }

    /**
     * 获取所有纹理条目的不可变视图
     */
    public Map<String, TextureEntry> getAllEntries() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 从 BufferedImage 提取平均颜色作为代表色
     */
    private static int extractAverageColor(BufferedImage image) {
        if (image == null) return 0;
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return 0;

        long totalR = 0, totalG = 0, totalB = 0, totalA = 0;
        int count = 0;
        int step = Math.max(1, Math.min(w, h) / 16);

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int argb = image.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                totalA += a;
                totalR += r;
                totalG += g;
                totalB += b;
                count++;
            }
        }

        if (count == 0) return 0;
        int a = (int) (totalA / count);
        int r = (int) (totalR / count);
        int g = (int) (totalG / count);
        int b = (int) (totalB / count);
        return ColorUtils.argb(a, r, g, b);
    }

    static com.mojang.blaze3d.platform.NativeImage bufferedImageToNativeImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        com.mojang.blaze3d.platform.NativeImage nativeImage =
            new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, w, h, false);
        int[] pixels = new int[w * h];
        image.getRGB(0, 0, w, h, pixels, 0, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = pixels[y * w + x];
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return nativeImage;
    }

    // ==================== 内部类型 ====================

    /**
     * 纹理渲染属性的不可变快照
     */
    public static class TextureRenderProps {
        public static final TextureRenderProps DEFAULT = new TextureRenderProps(false, false, false);
        public final boolean translucent;
        public final boolean alphaTest;
        public final boolean noCull;

        public TextureRenderProps(boolean translucent, boolean alphaTest, boolean noCull) {
            this.translucent = translucent;
            this.alphaTest = alphaTest;
            this.noCull = noCull;
        }
    }

    /**
     * 纹理解析统计信息
     */
    public static class ParseStatistics {
        public final int unparsed;
        public final int partial;
        public final int complete;
        public final int failed;
        public final int registeredTextures;

        public ParseStatistics(int unparsed, int partial, int complete, int failed, int registeredTextures) {
            this.unparsed = unparsed;
            this.partial = partial;
            this.complete = complete;
            this.failed = failed;
            this.registeredTextures = registeredTextures;
        }

        public int totalEntries() { return unparsed + partial + complete + failed; }
        public boolean hasFailures() { return failed > 0; }
        public float successRate() {
            int total = totalEntries();
            return total > 0 ? (float) complete / total : 0f;
        }

        @Override
        public String toString() {
            return String.format("ParseStats{unparsed=%d, partial=%d, complete=%d, failed=%d, registered=%d, rate=%.1f%%}",
                unparsed, partial, complete, failed, registeredTextures, successRate() * 100f);
        }
    }

    /**
     * 纹理解析过程中的计数器，用于跟踪批量解析的进度
     */
    public static class TextureParseStateTracker {
        private int totalToResolve;
        private int resolved;
        private int failed;
        private int skipped;

        public TextureParseStateTracker(int totalToResolve) {
            this.totalToResolve = totalToResolve;
        }

        public void incrementResolved() { resolved++; }
        public void incrementFailed() { failed++; }
        public void incrementSkipped() { skipped++; }

        public int getTotalToResolve() { return totalToResolve; }
        public int getResolved() { return resolved; }
        public int getFailed() { return failed; }
        public int getSkipped() { return skipped; }
        public boolean isFullyResolved() { return resolved + failed + skipped >= totalToResolve; }

        @Override
        public String toString() {
            return String.format("TextureParseTracker{total=%d, resolved=%d, failed=%d, skipped=%d}",
                totalToResolve, resolved, failed, skipped);
        }
    }
}
