package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.ColorUtils;

import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class TextureColorResolver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 最大纹理尺寸限制，防止 OOM */
    private static final int MAX_TEXTURE_DIMENSION = 4096;
    /** 最大纹理像素总数 (4096x4096 = 16,777,216) */
    private static final long MAX_TEXTURE_PIXELS = 16_777_216L;

    /** 纹理上传信号量，限制并发上传数量防止 GPU 内存溢出 */
    private static final Semaphore textureUploadSemaphore = new Semaphore(4, true);

    /**
     * 世代计数器，每次资源重载后递增    * 用于替代 boolean texturesNeedRefresh，避免竞态条件     * TextureEntry 记录自己最后注册的世代，ensureTextureRegistered 比较
     * 世代而不清除 flag，确保所有条目都能在下一帧正确重新注册     */
    private static volatile long textureGeneration = 0;

    /**
     * 递增全局纹理世代计数器     ModelLoadManager.markTexturesStale() 在资源重载时调用    */
    public static void incrementGeneration() {
        textureGeneration++;
    }

    /**
     * 返回当前纹理世代号     */
    public static long getTextureGeneration() {
        return textureGeneration;
    }

    private final Map<String, TextureEntry> entries = new ConcurrentHashMap<>();

    private final Map<String, ResourceLocation> textureRegistry = new ConcurrentHashMap<>();

    public static class TextureEntry {
        private volatile ColorUtils.TextureParseState state;
        private volatile ResourceLocation resourceLocation;
        private volatile BufferedImage image;
        private volatile NativeImage cachedNativeImage;
        private volatile int cachedColor;
        private volatile boolean translucent;
        private volatile boolean alphaTest;
        private volatile boolean noCull;
        /**
         * 此条目持有的、已注册TextureManager DynamicTexture 实例         * 整个生命周期内保持同一个实例，避免重新注册时关闭旧实例导致
         * RenderSystem 上传队列中残留对已关NativeImage null)纹理的引用，
         * 从而在 flipFrame replayQueue 中触NullPointerException         */
        private volatile DynamicTexture dynamicTexture;
        /** 此条目的纹理最后被注册时所在世代。与 TextureColorResolver.textureGeneration 比较*/
        private volatile long lastRegisteredGeneration = -1;

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

        public NativeImage getCachedNativeImage() { return cachedNativeImage; }
        public void setCachedNativeImage(NativeImage img) { this.cachedNativeImage = img; }

        public int getCachedColor() { return cachedColor; }
        public void setCachedColor(int color) { this.cachedColor = color; }

        public boolean isTranslucent() { return translucent; }
        public void setTranslucent(boolean v) { this.translucent = v; }

        public boolean isAlphaTest() { return alphaTest; }
        public void setAlphaTest(boolean v) { this.alphaTest = v; }

        public boolean isNoCull() { return noCull; }
        public void setNoCull(boolean v) { this.noCull = v; }

        public boolean isUsable() { return !state.isUsable(); }

        public long getLastRegisteredGeneration() { return lastRegisteredGeneration; }
        public void setLastRegisteredGeneration(long gen) { this.lastRegisteredGeneration = gen; }

        public DynamicTexture getDynamicTexture() { return dynamicTexture; }
        public void setDynamicTexture(DynamicTexture tex) { this.dynamicTexture = tex; }
    }

    public TextureEntry register(String texturePath) {
        return entries.computeIfAbsent(texturePath, TextureEntry::new);
    }

    public TextureEntry getEntry(String texturePath) {
        return entries.get(texturePath);
    }

    public ColorUtils.TextureParseState getState(String texturePath) {
        TextureEntry entry = entries.get(texturePath);
        return entry != null ? entry.getState() : ColorUtils.TextureParseState.UNPARSED;
    }

    public void markComplete(String texturePath, ResourceLocation loc, int color,
                              boolean translucent, boolean alphaTest, boolean noCull) {
        markComplete(texturePath, loc, color, translucent, alphaTest, noCull, null);
    }

    public void markComplete(String texturePath, ResourceLocation loc, int color,
                              boolean translucent, boolean alphaTest, boolean noCull,
                              NativeImage nativeImage) {
        TextureEntry entry = register(texturePath);
        entry.setState(ColorUtils.TextureParseState.COMPLETE);
        entry.setResourceLocation(loc);
        entry.setCachedColor(color);
        entry.setTranslucent(translucent);
        entry.setAlphaTest(alphaTest);
        entry.setNoCull(noCull);
        entry.setImage(null);
        if (nativeImage != null) {
            NativeImage old = entry.getCachedNativeImage();
            if (old != null && old != nativeImage) old.close();
            // Store a copy so the cache survives DynamicTexture.close()
            entry.setCachedNativeImage(safeCacheCopy(nativeImage));
        }
        if (loc != null) {
            textureRegistry.put(texturePath, loc);
        }
    }

    public void markComplete(String texturePath, ResourceLocation loc, int color) {
        markComplete(texturePath, loc, color, false, false, false);
    }

    public void markPartial(String texturePath, ResourceLocation loc, BufferedImage image) {
        TextureEntry entry = register(texturePath);
        int color = extractAverageColor(image);
        entry.setState(ColorUtils.TextureParseState.PARTIAL);
        entry.setResourceLocation(loc);
        entry.setCachedColor(color);
        entry.setTranslucent(false);
        entry.setAlphaTest(false);
        entry.setNoCull(false);
        entry.setImage(null);
        if (loc != null) {
            textureRegistry.put(texturePath, loc);
        }
    }

    public void markFailed(String texturePath, String reason) {
        TextureEntry entry = register(texturePath);
        entry.setState(ColorUtils.TextureParseState.FAILED);
        entry.setCachedColor(ColorUtils.FALLBACK_TEXTURE);
        entry.setImage(null);
        LOGGER.warn("[TextureColorResolver] Texture parse FAILED for '{}': {}", texturePath, reason);
    }

    public void markUnparsed(String texturePath) {
        TextureEntry entry = register(texturePath);
        entry.setState(ColorUtils.TextureParseState.UNPARSED);
    }

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

        try {
            ResourceLocation loc = registerToManager(texturePath, image);
            markComplete(texturePath, loc, color, translucent, alphaTest, noCull);
            if (parseTracker != null) parseTracker.incrementResolved();
            return Optional.of(register(texturePath));
        } catch (Exception e) {
            markFailed(texturePath, e.getMessage());
            if (parseTracker != null) parseTracker.incrementFailed();
            return Optional.empty();
        }
    }

    public Optional<ResourceLocation> safeGetTextureLocation(String texturePath) {
        TextureEntry entry = entries.get(texturePath);
        if (entry == null || entry.isUsable()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.getResourceLocation());
    }

    public int getColorOrDefault(String texturePath, int defaultColor) {
        TextureEntry entry = entries.get(texturePath);
        if (entry == null || entry.isUsable()) {
            return defaultColor;
        }
        int color = entry.getCachedColor();
        return ColorUtils.isValidColor(color) ? color : defaultColor;
    }

    public TextureRenderProps getRenderProps(String texturePath) {
        TextureEntry entry = entries.get(texturePath);
        if (entry == null || entry.isUsable()) {
            return TextureRenderProps.DEFAULT;
        }
        return new TextureRenderProps(
            entry.isTranslucent(),
            entry.isAlphaTest(),
            entry.isNoCull()
        );
    }

    public ResourceLocation registerToManager(String key, BufferedImage image) {
        String regKey = normalizeKey(key);
        ResourceLocation existing = textureRegistry.get(regKey);
        if (existing != null) return existing;

        ResourceLocation loc = ResourceLocation.parse(
            "transferstation_whimsicalideas:textures/generated/" + regKey);
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) {
                // On the render thread: build, cache, register atomically inline.
                buildAndRegister(regKey, loc, image);
            } else {
                // Off the render thread: defer the ENTIRE operation (build, cache,
                // register) to the render thread so the NativeImage is never closed
                // by another thread before the DynamicTexture is registered.
                mc.execute(() -> buildAndRegister(regKey, loc, image));
            }
        } catch (Exception e) {
            LOGGER.warn("[TextureColorResolver] Failed to register texture {}: {}", regKey, e.getMessage());
        }
        return loc;
    }

    /**
     * Build the NativeImage, DynamicTexture, cache copy, and register with the
     * TextureManager. MUST run on the render thread (or be wrapped in mc.execute)
     * so that the NativeImage is created, used, and registered atomically and is
     * never closed by another thread before registration completes.
     * <p>
     * 关键修复：每loc 在整个生命周期内只持有一DynamicTexture 实例     * 重新构建时复用已有实例（setImage + upload），而不是新建并重新注册     * 否则 TextureManager.register 会关闭旧实例，而旧实例upload 任务仍留     * RenderSystem 的上传队列中，在 flipFrame replayQueue 中因 NativeImage
     * null 而抛NullPointerException（crash-2026-07-19）     */
    private void buildAndRegister(String regKey, ResourceLocation loc, BufferedImage image) {
        try {
            NativeImage nativeImage = bufferedImageToNativeImage(image);
            if (nativeImage == null) {
                LOGGER.warn("[TextureColorResolver] Skipping texture {}: invalid source image", regKey);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            TextureEntry entry = entries.get(regKey);
            DynamicTexture dynamicTex = (entry != null) ? entry.getDynamicTexture() : null;
            // If the existing instance was closed by a resource reload (NativeImage
            // released), its getPixels() returns null create a fresh instance.
            if (dynamicTex == null || dynamicTex.getPixels() == null) {
                dynamicTex = new DynamicTexture(nativeImage);
                mc.getTextureManager().register(loc, dynamicTex);
                if (entry != null) entry.setDynamicTexture(dynamicTex);
            } else {
                // Reuse the existing instance: update pixels and re-upload in place.
                // This never closes the instance, so no stale upload lambda can hit a
                // null NativeImage.
                dynamicTex.setPixels(nativeImage);
                dynamicTex.upload();
            }
            textureRegistry.put(regKey, loc);
            if (entry != null) {
                // Store a copy so the cache survives DynamicTexture.close()
                NativeImage cacheCopy = safeCacheCopy(nativeImage);
                NativeImage old = entry.getCachedNativeImage();
                if (old != null && old != cacheCopy && cacheCopy != null) old.close();
                entry.setCachedNativeImage(cacheCopy);
                entry.setLastRegisteredGeneration(textureGeneration);
            }
            LOGGER.debug("[TextureColorResolver] Registered texture: {} ({}x{})",
                loc, image.getWidth(), image.getHeight());
        } catch (Exception e) {
            LOGGER.warn("[TextureColorResolver] Failed to register texture {}: {}", regKey, e.getMessage());
        }
    }

    /**
     * 检查是否已经有纹理注册如果有则直接复用而不是重复加载那样会造成内存浪费(¬_¬ )
     */
    public void applyNativeImage(ResourceLocation loc, NativeImage nativeImage) {
        if (loc == null || nativeImage == null) return;
        if (nativeImage.getWidth() <= 0 || nativeImage.getHeight() <= 0
                || (long) nativeImage.getWidth() * nativeImage.getHeight() > MAX_TEXTURE_PIXELS) {
            LOGGER.warn("[TextureColorResolver] Rejecting invalid/oversized NativeImage for {}", loc);
            return;
        }
        String path = loc.getPath();
        if (!path.startsWith("textures/generated/")) return;
        String regKey = path.substring("textures/generated/".length());
        Minecraft mc = Minecraft.getInstance();
        TextureEntry entry = entries.get(regKey);
        DynamicTexture dynamicTex = (entry != null) ? entry.getDynamicTexture() : null;
        if (dynamicTex == null || dynamicTex.getPixels() == null) {
            dynamicTex = new DynamicTexture(nativeImage);
            mc.getTextureManager().register(loc, dynamicTex);
            if (entry != null) entry.setDynamicTexture(dynamicTex);
        } else {
            dynamicTex.setPixels(nativeImage);
            dynamicTex.upload();
        }
        textureRegistry.put(regKey, loc);
    }

    /**
     * 确保纹理已注册到 TextureManager     * 使用世代计数器比较：如果该条目已在当前世代注册过，则跳过     * 这样每帧仅做一long 比较，极大减少无意义DynamicTexture 重建
     */
    public void ensureTextureRegistered(ResourceLocation loc) {
        if (loc == null) return;
        String path = loc.getPath();
        if (!path.startsWith("textures/generated/")) return;
        String regKey = path.substring("textures/generated/".length());
        TextureEntry entry = entries.get(regKey);
        if (entry == null) return;
        long currentGen = textureGeneration;
        if (entry.getLastRegisteredGeneration() == currentGen) return;
        // Only re-register entries that actually have a usable texture.
        if (entry.getState() != ColorUtils.TextureParseState.COMPLETE
                && entry.getState() != ColorUtils.TextureParseState.PARTIAL) {
            return;
        }
        // Do NOT read the cached NativeImage here and pass it across the thread
        // boundary (it could be closed before the deferred register runs). Let
        // reRegisterTexture re-read it inside the render-thread block and bail if null.
        // The generation stamp is set INSIDE the render-thread block (see
        // reRegisterOnRenderThread) so a render between this call and the actual
        // re-register cannot sample an unregistered/closed texture.
        reRegisterTexture(regKey, loc);
    }

    private void reRegisterTexture(String regKey, ResourceLocation loc) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            // On the render thread: re-read the cache and register atomically inline.
            reRegisterOnRenderThread(regKey, loc);
        } else {
            // Off the render thread: defer the ENTIRE operation to the render thread.
            // The cached NativeImage is re-read inside the block so it cannot be
            // closed by another thread between reading it and building the texture.
            mc.execute(() -> reRegisterOnRenderThread(regKey, loc));
        }
    }

    /**
     * Re-read the cached NativeImage and rebuild/register the DynamicTexture.
     * MUST run on the render thread. Bails early if the cached image became null
     * (e.g. cleared/trimmed before this deferred task ran).
     * 关键修复：复用已有的 DynamicTexture 实例（setImage + upload），
     * 而不是新建并重新注册。重新注册会关闭旧实例，而旧实例upload 任务
     * 仍留RenderSystem 上传队列中，flipFrame replayQueue 中因
     * NativeImage null 而抛NullPointerException     */
    private void reRegisterOnRenderThread(String regKey, ResourceLocation loc) {
        try {
            TextureEntry entry = entries.get(regKey);
            if (entry == null) return;
            NativeImage cached = entry.getCachedNativeImage();
            if (cached == null) return;
            NativeImage copy = safeNativeImageCopy(cached);
            if (copy == null) return;
            Minecraft mc = Minecraft.getInstance();
            DynamicTexture dynamicTex = entry.getDynamicTexture();
            // If the existing instance was closed by a resource reload, create a fresh one.
            if (dynamicTex == null || dynamicTex.getPixels() == null) {
                dynamicTex = new DynamicTexture(copy);
                mc.getTextureManager().register(loc, dynamicTex);
                entry.setDynamicTexture(dynamicTex);
            } else {
                dynamicTex.setPixels(copy);
                dynamicTex.upload();
            }
            textureRegistry.put(regKey, loc);
            // Stamp the generation only after the texture is truly registered on the
            // render thread. ensureTextureRegistered() compares against this stamp, so
            // stamping earlier (before the deferred register ran) let a render sample a
            // still-unregistered/closed texture and crash or show black.
            entry.setLastRegisteredGeneration(textureGeneration);
            LOGGER.debug("[TextureColorResolver] Re-registered texture: {}", loc);
        } catch (Exception e) {
            LOGGER.warn("[TextureColorResolver] Failed to re-register texture {}: {}", loc, e.getMessage());
        }
    }

    /**
     * 重新注册所有已生成的纹理到 TextureManager     * 先递增世代计数器（使所有现有条目标记为过期），
     * 再用缓存NativeImage 副本重建 DynamicTexture     * 每次成功注册后更新条目的世代，使 ensureTextureRegistered 跳过     */
    public void reRegisterAllTextures() {
        long newGen = ++textureGeneration;
        int reRegistered = 0;
        int skipped = 0;
        for (Map.Entry<String, TextureEntry> entry : entries.entrySet()) {
            TextureEntry texEntry = entry.getValue();
            if (texEntry.getState() != ColorUtils.TextureParseState.COMPLETE && texEntry.getState() != ColorUtils.TextureParseState.PARTIAL) {
                texEntry.setLastRegisteredGeneration(newGen); // skip unparseable this round
                skipped++;
                continue;
            }
            NativeImage cached = texEntry.getCachedNativeImage();
            if (cached == null) {
                skipped++;
                continue;
            }
            ResourceLocation loc = texEntry.getResourceLocation();
            if (loc == null) {
                skipped++;
                continue;
            }
            reRegisterTexture(entry.getKey(), loc);
            texEntry.setLastRegisteredGeneration(newGen);
            reRegistered++;
        }
        LOGGER.info("[TextureColorResolver] Re-registered {} textures after resource reload ({} skipped, generation={})", reRegistered, skipped, newGen);
    }

    public boolean isRegistered(String key) {
        return textureRegistry.containsKey(normalizeKey(key));
    }

    public ResourceLocation getRegistered(String key) {
        return textureRegistry.get(normalizeKey(key));
    }

    public void unregisterTexture(String texturePath) {
        String regKey = normalizeKey(texturePath);
        ResourceLocation loc = textureRegistry.remove(regKey);
        TextureEntry entry = entries.remove(texturePath);
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            // Release and close the cached image together on the render thread so a
            // pending deferred register cannot build a DynamicTexture from a closed image.
            if (loc != null) {
                mc.getTextureManager().release(loc);
            }
            if (entry != null) {
                entry.setImage(null);
                entry.setDynamicTexture(null);
                NativeImage cached = entry.getCachedNativeImage();
                if (cached != null) {
                    cached.close();
                    entry.setCachedNativeImage(null);
                }
            }
        } else {
            mc.execute(() -> {
                if (loc != null) {
                    mc.getTextureManager().release(loc);
                }
                if (entry != null) {
                    entry.setImage(null);
                    entry.setDynamicTexture(null);
                    NativeImage cached = entry.getCachedNativeImage();
                    if (cached != null) {
                        cached.close();
                        entry.setCachedNativeImage(null);
                    }
                }
            });
        }
    }

    private static String normalizeKey(String key) {
        return "gmod_" + key.replace('/', '_').replace('\\', '_')
            .replace('.', '_').toLowerCase(java.util.Locale.ROOT);
    }

    public void clearAll() {
        // Snapshot the data to release/close on the render thread atomically.
        java.util.List<TextureEntry> entrySnapshot = new java.util.ArrayList<>(entries.values());
        java.util.List<ResourceLocation> locSnapshot = new java.util.ArrayList<>(textureRegistry.values());
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            for (TextureEntry entry : entrySnapshot) {
                entry.setImage(null);
                entry.setDynamicTexture(null);
                NativeImage cached = entry.getCachedNativeImage();
                if (cached != null) {
                    cached.close();
                    entry.setCachedNativeImage(null);
                }
            }
            for (ResourceLocation loc : locSnapshot) {
                mc.getTextureManager().release(loc);
            }
        } else {
            mc.execute(() -> {
                for (TextureEntry entry : entrySnapshot) {
                    entry.setImage(null);
                    entry.setDynamicTexture(null);
                    NativeImage cached = entry.getCachedNativeImage();
                    if (cached != null) {
                        cached.close();
                        entry.setCachedNativeImage(null);
                    }
                }
                for (ResourceLocation loc : locSnapshot) {
                    mc.getTextureManager().release(loc);
                }
            });
        }
        entries.clear();
        textureRegistry.clear();
        LOGGER.info("[TextureColorResolver] Cleared all entries and texture registry");
    }

    public void trimStale() {
        int removed = 0;
        Iterator<Map.Entry<String, TextureEntry>> iter = entries.entrySet().iterator();
        Minecraft mc = Minecraft.getInstance();
        while (iter.hasNext()) {
            var e = iter.next();
            TextureEntry entry = e.getValue();
            ColorUtils.TextureParseState state = entry.getState();
            if (state == ColorUtils.TextureParseState.FAILED || state == ColorUtils.TextureParseState.UNPARSED) {
                String regKey = normalizeKey(e.getKey());
                ResourceLocation loc = textureRegistry.remove(regKey);
                // Release and close the cached image together on the render thread so a
                // pending deferred register cannot build a DynamicTexture from a closed image.
                if (mc.isSameThread()) {
                    if (loc != null) {
                        mc.getTextureManager().release(loc);
                    }
                    entry.setImage(null);
                    entry.setDynamicTexture(null);
                    NativeImage cached = entry.getCachedNativeImage();
                    if (cached != null) {
                        cached.close();
                        entry.setCachedNativeImage(null);
                    }
                } else {
                    mc.execute(() -> {
                        if (loc != null) {
                            mc.getTextureManager().release(loc);
                        }
                        entry.setImage(null);
                        entry.setDynamicTexture(null);
                        NativeImage cached = entry.getCachedNativeImage();
                        if (cached != null) {
                            cached.close();
                            entry.setCachedNativeImage(null);
                        }
                    });
                }
                iter.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("[TextureColorResolver] Trimmed {} stale entries", removed);
        }
    }

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

    public Map<String, TextureEntry> getAllEntries() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    private static int extractAverageColor(BufferedImage image) {
        if (image == null) return 0;
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return 0;

        long totalR = 0, totalG = 0, totalB = 0;
        long totalWeight = 0;
        int step = Math.max(1, Math.min(w, h) / 16);

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int argb = image.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a < 10) continue;

                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                totalR += r * (long) a;
                totalG += g * (long) a;
                totalB += b * (long) a;
                totalWeight += a;
            }
        }

        if (totalWeight == 0) return 0;
        int r = (int) (totalR / totalWeight);
        int g = (int) (totalG / totalWeight);
        int b = (int) (totalB / totalWeight);
        return ColorUtils.argb(255, r, g, b);
    }

    static com.mojang.blaze3d.platform.NativeImage bufferedImageToNativeImage(BufferedImage image) {
        BufferedImage limited = limitImage(image);
        if (limited == null) return null;
        int w = limited.getWidth();
        int h = limited.getHeight();
        if (w <= 0 || h <= 0) return null;
        com.mojang.blaze3d.platform.NativeImage nativeImage =
            new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, w, h, false);
        int[] pixels = new int[w * h];
        limited.getRGB(0, 0, w, h, pixels, 0, w);
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

    /** 超过上限的贴图降采样，防止 Java 堆 OOM 与 GPU 内存溢出 */
    private static BufferedImage limitImage(BufferedImage image) {
        if (image == null) return null;
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return image;
        if (w <= MAX_TEXTURE_DIMENSION && h <= MAX_TEXTURE_DIMENSION
                && (long) w * h <= MAX_TEXTURE_PIXELS) {
            return image;
        }
        double scale = Math.min((double) MAX_TEXTURE_DIMENSION / w, (double) MAX_TEXTURE_DIMENSION / h);
        int nw = Math.max(1, (int) (w * scale));
        int nh = Math.max(1, (int) (h * scale));
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        LOGGER.warn("[TextureColorResolver] Downscaled oversized texture {}x{} -> {}x{}", w, h, nw, nh);
        return scaled;
    }

    /** 安全复制 NativeImage，源无效或已关闭时返回 null，避免 "Image is not allocated" */
    private static NativeImage safeNativeImageCopy(NativeImage src) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0 || (long) w * h > MAX_TEXTURE_PIXELS) return null;
        NativeImage copy = new NativeImage(w, h, false);
        try {
            copy.copyFrom(src);
        } catch (Exception e) {
            copy.close();
            LOGGER.warn("[TextureColorResolver] NativeImage copy failed: {}", e.getMessage());
            return null;
        }
        return copy;
    }

    /** 受并发信号量保护的安全缓存复制，限制并发大块分配防止 OOM */
    private static NativeImage safeCacheCopy(NativeImage src) {
        try {
            textureUploadSemaphore.acquire();
            return safeNativeImageCopy(src);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            textureUploadSemaphore.release();
        }
    }

    public record TextureRenderProps(boolean translucent, boolean alphaTest, boolean noCull) {
            public static final TextureRenderProps DEFAULT = new TextureRenderProps(false, false, false);
    }

    public record ParseStatistics(int unparsed, int partial, int complete, int failed, int registeredTextures) {

        public int totalEntries() {
            return unparsed + partial + complete + failed;
        }

        public boolean hasFailures() {
            return failed > 0;
        }

        public float successRate() {
                int total = totalEntries();
                return total > 0 ? (float) complete / total : 0f;
            }

            @Override
            public @NotNull String toString() {
                return String.format("ParseStats{unparsed=%d, partial=%d, complete=%d, failed=%d, registered=%d, rate=%.1f%%}",
                        unparsed, partial, complete, failed, registeredTextures, successRate() * 100f);
            }
        }

    public static class TextureParseStateTracker {
        private final int totalToResolve;
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
