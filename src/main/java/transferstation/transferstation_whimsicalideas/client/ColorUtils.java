package transferstation.transferstation_whimsicalideas.client;

/**
 * 颜色工具类 - 支持多种颜色格式的转换和操作
 * 支持格式: ARGB8888, RGB888, RGBA8888, BGR888, BGRA8888, RGB565,
 *          HSL, HSV, 浮点格式, 十六进制字符串, CSS格式
 */
public final class ColorUtils {

    private ColorUtils() {
        // 工具类，禁止实例化
    }

    // ==================== 整数格式常量 ====================
    public static final int FORMAT_ARGB8888 = 0;
    public static final int FORMAT_RGB888 = 1;
    public static final int FORMAT_RGBA8888 = 2;
    public static final int FORMAT_BGR888 = 3;
    public static final int FORMAT_BGRA8888 = 4;
    public static final int FORMAT_RGB565 = 5;

    // ==================== 整数格式转换 ====================

    /**
     * 从ARGB8888提取Alpha分量 (0-255)
     */
    public static int extractAlpha(int argb) {
        return (argb >> 24) & 0xFF;
    }

    /**
     * 从ARGB8888提取Red分量 (0-255)
     */
    public static int extractRed(int argb) {
        return (argb >> 16) & 0xFF;
    }

    /**
     * 从ARGB8888提取Green分量 (0-255)
     */
    public static int extractGreen(int argb) {
        return (argb >> 8) & 0xFF;
    }

    /**
     * 从ARGB8888提取Blue分量 (0-255)
     */
    public static int extractBlue(int argb) {
        return argb & 0xFF;
    }

    /**
     * 从RGB888提取Red分量 (0-255)
     */
    public static int extractRedFromRGB(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    /**
     * 从RGB888提取Green分量 (0-255)
     */
    public static int extractGreenFromRGB(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    /**
     * 从RGB888提取Blue分量 (0-255)
     */
    public static int extractBlueFromRGB(int rgb) {
        return rgb & 0xFF;
    }

    /**
     * 从RGBA8888提取Red分量 (0-255)
     */
    public static int extractRedFromRGBA(int rgba) {
        return (rgba >> 24) & 0xFF;
    }

    /**
     * 从RGBA8888提取Green分量 (0-255)
     */
    public static int extractGreenFromRGBA(int rgba) {
        return (rgba >> 16) & 0xFF;
    }

    /**
     * 从RGBA8888提取Blue分量 (0-255)
     */
    public static int extractBlueFromRGBA(int rgba) {
        return (rgba >> 8) & 0xFF;
    }

    /**
     * 从RGBA8888提取Alpha分量 (0-255)
     */
    public static int extractAlphaFromRGBA(int rgba) {
        return rgba & 0xFF;
    }

    /**
     * 创建ARGB8888颜色
     */
    public static int argb(int alpha, int red, int green, int blue) {
        return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    /**
     * 创建ARGB8888颜色 (alpha=255)
     */
    public static int rgb(int red, int green, int blue) {
        return argb(255, red, green, blue);
    }

    /**
     * 创建RGB888颜色
     */
    public static int rgb888(int red, int green, int blue) {
        return ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    /**
     * 创建RGBA8888颜色
     */
    public static int rgba8888(int red, int green, int blue, int alpha) {
        return ((red & 0xFF) << 24) | ((green & 0xFF) << 16) | ((blue & 0xFF) << 8) | (alpha & 0xFF);
    }

    /**
     * 创建BGR888颜色
     */
    public static int bgr888(int blue, int green, int red) {
        return ((blue & 0xFF) << 16) | ((green & 0xFF) << 8) | (red & 0xFF);
    }

    /**
     * 创建BGRA8888颜色
     */
    public static int bgra8888(int blue, int green, int red, int alpha) {
        return ((blue & 0xFF) << 24) | ((green & 0xFF) << 16) | ((red & 0xFF) << 8) | (alpha & 0xFF);
    }

    // ==================== 格式转换 ====================

    /**
     * ARGB8888 转 RGB888
     */
    public static int argbToRgb(int argb) {
        return argb & 0x00FFFFFF;
    }

    /**
     * RGB888 转 ARGB8888 (alpha=255)
     */
    public static int rgbToArgb(int rgb) {
        return rgb | 0xFF000000;
    }

    /**
     * ARGB8888 转 RGBA8888
     */
    public static int argbToRgba(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return rgba8888(r, g, b, a);
    }

    /**
     * RGBA8888 转 ARGB8888
     */
    public static int rgbaToArgb(int rgba) {
        int r = (rgba >> 24) & 0xFF;
        int g = (rgba >> 16) & 0xFF;
        int b = (rgba >> 8) & 0xFF;
        int a = rgba & 0xFF;
        return argb(a, r, g, b);
    }

    /**
     * ARGB8888 转 BGR888
     */
    public static int argbToBgr(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return bgr888(b, g, r);
    }

    /**
     * BGR888 转 ARGB8888 (alpha=255)
     */
    public static int bgrToArgb(int bgr) {
        int b = (bgr >> 16) & 0xFF;
        int g = (bgr >> 8) & 0xFF;
        int r = bgr & 0xFF;
        return rgb(r, g, b);
    }

    /**
     * ARGB8888 转 BGRA8888
     */
    public static int argbToBgra(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return bgra8888(b, g, r, a);
    }

    /**
     * BGRA8888 转 ARGB8888
     */
    public static int bgraToArgb(int bgra) {
        int b = (bgra >> 24) & 0xFF;
        int g = (bgra >> 16) & 0xFF;
        int r = (bgra >> 8) & 0xFF;
        int a = bgra & 0xFF;
        return argb(a, r, g, b);
    }

    /**
     * RGB565 转 ARGB8888
     */
    public static int rgb565ToArgb(int rgb565) {
        int r5 = (rgb565 >> 11) & 0x1F;
        int g6 = (rgb565 >> 5) & 0x3F;
        int b5 = rgb565 & 0x1F;
        int r = (r5 << 3) | (r5 >> 2);
        int g = (g6 << 2) | (g6 >> 4);
        int b = (b5 << 3) | (b5 >> 2);
        return rgb(r, g, b);
    }

    /**
     * ARGB8888 转 RGB565
     */
    public static int argbToRgb565(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int r5 = (r >> 3) & 0x1F;
        int g6 = (g >> 2) & 0x3F;
        int b5 = (b >> 3) & 0x1F;
        return (r5 << 11) | (g6 << 5) | b5;
    }

    // ==================== 浮点格式转换 (0.0-1.0) ====================

    /**
     * ARGB8888 转浮点RGB (r, g, b)
     * @return float[3]: {r, g, b} 范围 0.0-1.0
     */
    public static float[] argbToFloatRgb(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        return new float[]{r, g, b};
    }

    /**
     * ARGB8888 转浮点RGBA (r, g, b, a)
     * @return float[4]: {r, g, b, a} 范围 0.0-1.0
     */
    public static float[] argbToFloatRgba(int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        return new float[]{r, g, b, a};
    }

    /**
     * 浮点RGB (0.0-1.0) 转 ARGB8888 (alpha=255)
     */
    public static int floatRgbToArgb(float r, float g, float b) {
        int ri = Math.round(Math.max(0.0f, Math.min(1.0f, r)) * 255.0f);
        int gi = Math.round(Math.max(0.0f, Math.min(1.0f, g)) * 255.0f);
        int bi = Math.round(Math.max(0.0f, Math.min(1.0f, b)) * 255.0f);
        return rgb(ri, gi, bi);
    }

    /**
     * 浮点RGBA (0.0-1.0) 转 ARGB8888
     */
    public static int floatRgbaToArgb(float r, float g, float b, float a) {
        int ri = Math.round(Math.max(0.0f, Math.min(1.0f, r)) * 255.0f);
        int gi = Math.round(Math.max(0.0f, Math.min(1.0f, g)) * 255.0f);
        int bi = Math.round(Math.max(0.0f, Math.min(1.0f, b)) * 255.0f);
        int ai = Math.round(Math.max(0.0f, Math.min(1.0f, a)) * 255.0f);
        return argb(ai, ri, gi, bi);
    }

    /**
     * ARGB8888 转浮点分量数组 (用于OpenGL渲染)
     * @return float[4]: {r, g, b, a} 范围 0.0-1.0
     */
    public static float[] toFloatComponents(int argb) {
        return argbToFloatRgba(argb);
    }

    /**
     * 从浮点分量创建ARGB8888
     */
    public static int fromFloatComponents(float r, float g, float b, float a) {
        return floatRgbaToArgb(r, g, b, a);
    }

    // ==================== 字符串格式解析 ====================

    /**
     * 解析十六进制颜色字符串
     * 支持格式: #RRGGBB, #AARRGGBB, RRGGBB, AARRGGBB
     */
    public static int parseHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            throw new IllegalArgumentException("Hex color string is null or empty");
        }

        // 移除可能的 # 前缀
        String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;

        // 移除可能的 0x 前缀
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
            cleaned = cleaned.substring(2);
        }

        long value;
        try {
            value = Long.parseLong(cleaned, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid hex color: " + hex, e);
        }

        switch (cleaned.length()) {
            case 6:
                // RRGGBB -> ARGB (alpha=255)
                return (int) (value | 0xFF000000);
            case 8:
                // AARRGGBB -> ARGB
                return (int) value;
            default:
                throw new IllegalArgumentException("Invalid hex color length: " + hex);
        }
    }

    /**
     * 解析CSS格式颜色字符串
     * 支持格式: rgb(r, g, b), rgba(r, g, b, a)
     */
    public static int parseCss(String css) {
        if (css == null || css.isEmpty()) {
            throw new IllegalArgumentException("CSS color string is null or empty");
        }

        String trimmed = css.trim().toLowerCase();

        if (trimmed.startsWith("rgb(") && trimmed.endsWith(")")) {
            // rgb(r, g, b)
            String inner = trimmed.substring(4, trimmed.length() - 1);
            String[] parts = inner.split(",");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid rgb() format: " + css);
            }
            int r = parseCssComponent(parts[0].trim());
            int g = parseCssComponent(parts[1].trim());
            int b = parseCssComponent(parts[2].trim());
            return rgb(r, g, b);
        } else if (trimmed.startsWith("rgba(") && trimmed.endsWith(")")) {
            // rgba(r, g, b, a)
            String inner = trimmed.substring(5, trimmed.length() - 1);
            String[] parts = inner.split(",");
            if (parts.length != 4) {
                throw new IllegalArgumentException("Invalid rgba() format: " + css);
            }
            int r = parseCssComponent(parts[0].trim());
            int g = parseCssComponent(parts[1].trim());
            int b = parseCssComponent(parts[2].trim());
            float a = Float.parseFloat(parts[3].trim());
            int ai = Math.round(a * 255.0f);
            return argb(ai, r, g, b);
        } else {
            // 尝试作为十六进制解析
            return parseHex(css);
        }
    }

    private static int parseCssComponent(String s) {
        if (s.endsWith("%")) {
            float percent = Float.parseFloat(s.substring(0, s.length() - 1));
            return Math.round(percent * 255.0f / 100.0f);
        } else {
            return Integer.parseInt(s);
        }
    }

    /**
     * 将ARGB8888转换为十六进制字符串
     * @param withAlpha 是否包含Alpha通道
     */
    public static String toHex(int argb, boolean withAlpha) {
        if (withAlpha) {
            return String.format("#%08X", argb);
        } else {
            return String.format("#%06X", argb & 0x00FFFFFF);
        }
    }

    /**
     * 将ARGB8888转换为CSS rgb()格式字符串
     */
    public static String toCssRgb(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return String.format("rgb(%d, %d, %d)", r, g, b);
    }

    /**
     * 将ARGB8888转换为CSS rgba()格式字符串
     */
    public static String toCssRgba(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        float af = a / 255.0f;
        return String.format("rgba(%d, %d, %d, %.2f)", r, g, b, af);
    }

    // ==================== HSL 颜色空间 ====================

    /**
     * HSL 转 ARGB8888
     * @param h 色相 (0.0-1.0)
     * @param s 饱和度 (0.0-1.0)
     * @param l 亮度 (0.0-1.0)
     */
    public static int hslToArgb(float h, float s, float l) {
        float r, g, b;

        if (s == 0.0f) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hueToRgb(p, q, h + 1.0f / 3.0f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1.0f / 3.0f);
        }

        return floatRgbToArgb(r, g, b);
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0.0f) t += 1.0f;
        if (t > 1.0f) t -= 1.0f;
        if (t < 1.0f / 6.0f) return p + (q - p) * 6.0f * t;
        if (t < 1.0f / 2.0f) return q;
        if (t < 2.0f / 3.0f) return p + (q - p) * (2.0f / 3.0f - t) * 6.0f;
        return p;
    }

    /**
     * ARGB8888 转 HSL
     * @return float[3]: {h, s, l} 范围 0.0-1.0
     */
    public static float[] argbToHsl(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2.0f;

        if (max == min) {
            return new float[]{0.0f, 0.0f, l};
        }

        float d = max - min;
        float s = l > 0.5f ? d / (2.0f - max - min) : d / (max + min);

        float h;
        if (max == r) {
            h = (g - b) / d + (g < b ? 6.0f : 0.0f);
        } else if (max == g) {
            h = (b - r) / d + 2.0f;
        } else {
            h = (r - g) / d + 4.0f;
        }
        h /= 6.0f;

        return new float[]{h, s, l};
    }

    // ==================== HSV/HSB 颜色空间 ====================

    /**
     * HSV 转 ARGB8888
     * @param h 色相 (0.0-1.0)
     * @param s 饱和度 (0.0-1.0)
     * @param v 明度 (0.0-1.0)
     */
    public static int hsvToArgb(float h, float s, float v) {
        float r, g, b;

        int i = (int) (h * 6.0f);
        float f = h * 6.0f - i;
        float p = v * (1.0f - s);
        float q = v * (1.0f - f * s);
        float t = v * (1.0f - (1.0f - f) * s);

        switch (i % 6) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }

        return floatRgbToArgb(r, g, b);
    }

    /**
     * ARGB8888 转 HSV
     * @return float[3]: {h, s, v} 范围 0.0-1.0
     */
    public static float[] argbToHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float v = max;
        float d = max - min;
        float s = max == 0.0f ? 0.0f : d / max;

        if (max == min) {
            return new float[]{0.0f, s, v};
        }

        float h;
        if (max == r) {
            h = (g - b) / d + (g < b ? 6.0f : 0.0f);
        } else if (max == g) {
            h = (b - r) / d + 2.0f;
        } else {
            h = (r - g) / d + 4.0f;
        }
        h /= 6.0f;

        return new float[]{h, s, v};
    }

    // ==================== 颜色工具方法 ====================

    /**
     * 计算颜色的亮度 (相对亮度)
     * @return 亮度值 (0.0-1.0)
     */
    public static float luminance(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /**
     * 计算颜色的感知亮度 (用于对比度计算)
     * @return 亮度值 (0-255)
     */
    public static int perceivedBrightness(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /**
     * 判断颜色是否为深色
     */
    public static boolean isDark(int argb) {
        return perceivedBrightness(argb) < 128;
    }

    /**
     * 判断颜色是否为浅色
     */
    public static boolean isLight(int argb) {
        return perceivedBrightness(argb) >= 128;
    }

    /**
     * 线性插值两个颜色
     * @param c1 起始颜色
     * @param c2 结束颜色
     * @param t 插值因子 (0.0-1.0)
     */
    public static int lerp(int c1, int c2, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        float[] rgba1 = argbToFloatRgba(c1);
        float[] rgba2 = argbToFloatRgba(c2);
        float r = rgba1[0] + (rgba2[0] - rgba1[0]) * t;
        float g = rgba1[1] + (rgba2[1] - rgba1[1]) * t;
        float b = rgba1[2] + (rgba2[2] - rgba1[2]) * t;
        float a = rgba1[3] + (rgba2[3] - rgba1[3]) * t;
        return floatRgbaToArgb(r, g, b, a);
    }

    /**
     * Alpha混合两个颜色
     * @param bottom 底层颜色
     * @param top 顶层颜色
     */
    public static int alphaBlend(int bottom, int top) {
        float[] rgbaBottom = argbToFloatRgba(bottom);
        float[] rgbaTop = argbToFloatRgba(top);

        float aTop = rgbaTop[3];
        float aBottom = rgbaBottom[3];
        float aOut = aTop + aBottom * (1.0f - aTop);

        if (aOut == 0.0f) {
            return 0;
        }

        float r = (rgbaTop[0] * aTop + rgbaBottom[0] * aBottom * (1.0f - aTop)) / aOut;
        float g = (rgbaTop[1] * aTop + rgbaBottom[1] * aBottom * (1.0f - aTop)) / aOut;
        float b = (rgbaTop[2] * aTop + rgbaBottom[2] * aBottom * (1.0f - aTop)) / aOut;

        return floatRgbaToArgb(r, g, b, aOut);
    }

    /**
     * 调整颜色亮度
     * @param factor 亮度因子 (>1.0变亮, <1.0变暗)
     */
    public static int adjustBrightness(int argb, float factor) {
        float r = ((argb >> 16) & 0xFF) / 255.0f * factor;
        float g = ((argb >> 8) & 0xFF) / 255.0f * factor;
        float b = (argb & 0xFF) / 255.0f * factor;
        int a = (argb >> 24) & 0xFF;
        return argb(a,
                Math.round(Math.min(1.0f, r) * 255.0f),
                Math.round(Math.min(1.0f, g) * 255.0f),
                Math.round(Math.min(1.0f, b) * 255.0f));
    }

    /**
     * 调整颜色饱和度
     * @param factor 饱和度因子 (>1.0增加, <1.0减少)
     */
    public static int adjustSaturation(int argb, float factor) {
        float[] hsv = argbToHsv(argb);
        hsv[1] = Math.max(0.0f, Math.min(1.0f, hsv[1] * factor));
        int rgb = hsvToArgb(hsv[0], hsv[1], hsv[2]);
        int a = (argb >> 24) & 0xFF;
        return (rgb & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /**
     * 调整颜色色相
     * @param degrees 色相偏移度数 (0-360)
     */
    public static int adjustHue(int argb, float degrees) {
        float[] hsv = argbToHsv(argb);
        hsv[0] = (hsv[0] + degrees / 360.0f) % 1.0f;
        if (hsv[0] < 0.0f) hsv[0] += 1.0f;
        int rgb = hsvToArgb(hsv[0], hsv[1], hsv[2]);
        int a = (argb >> 24) & 0xFF;
        return (rgb & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /**
     * 反转颜色
     */
    public static int invert(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = 255 - ((argb >> 16) & 0xFF);
        int g = 255 - ((argb >> 8) & 0xFF);
        int b = 255 - (argb & 0xFF);
        return argb(a, r, g, b);
    }

    /**
     * 将颜色转换为灰度
     */
    public static int grayscale(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int gray = Math.round(0.299f * r + 0.587f * g + 0.114f * b);
        int a = (argb >> 24) & 0xFF;
        return argb(a, gray, gray, gray);
    }

    /**
     * 设置颜色的Alpha值
     */
    public static int setAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /**
     * 设置颜色的Alpha值 (浮点版本)
     * @param alpha Alpha值 (0.0-1.0)
     */
    public static int setAlpha(int argb, float alpha) {
        return setAlpha(argb, Math.round(alpha * 255.0f));
    }

    /**
     * 颜色取反 (用于不同上下文)
     */
    public static int complementary(int argb) {
        float[] hsv = argbToHsv(argb);
        hsv[0] = (hsv[0] + 0.5f) % 1.0f;
        int rgb = hsvToArgb(hsv[0], hsv[1], hsv[2]);
        int a = (argb >> 24) & 0xFF;
        return (rgb & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /**
     * 计算两个颜色之间的对比度
     * @return 对比度值 (1-21)
     */
    public static float contrastRatio(int c1, int c2) {
        float l1 = luminance(c1);
        float l2 = luminance(c2);
        float lighter = Math.max(l1, l2);
        float darker = Math.min(l1, l2);
        return (lighter + 0.05f) / (darker + 0.05f);
    }

    /**
     * 验证颜色是否符合WCAG AA对比度标准
     * @param textColor 前景色
     * @param backgroundColor 背景色
     * @param isLargeText 是否为大文本
     */
    public static boolean meetsContrastRequirement(int textColor, int backgroundColor, boolean isLargeText) {
        float ratio = contrastRatio(textColor, backgroundColor);
        return isLargeText ? ratio >= 3.0f : ratio >= 4.5f;
    }

    /**
     * 确保文本在背景上具有足够的对比度
     * @param textColor 前景色
     * @param backgroundColor 背景色
     * @return 调整后的前景色
     */
    public static int ensureContrast(int textColor, int backgroundColor) {
        float ratio = contrastRatio(textColor, backgroundColor);
        if (ratio >= 4.5f) {
            return textColor;
        }

        // 尝试变亮或变暗文本以达到对比度要求
        boolean bgIsDark = isDark(backgroundColor);
        float factor = 1.0f;
        int adjusted = textColor;

        for (int i = 0; i < 50; i++) {
            factor += 0.1f;
            if (bgIsDark) {
                adjusted = adjustBrightness(textColor, factor);
            } else {
                adjusted = adjustBrightness(textColor, 1.0f / factor);
            }
            if (contrastRatio(adjusted, backgroundColor) >= 4.5f) {
                return adjusted;
            }
        }

        return adjusted;
    }

    // ==================== 批量转换方法 ====================

    /**
     * 将整数数组转换为浮点RGB数组
     * @param argbColors ARGB8888颜色数组
     * @return float数组，每3个元素为一组 {r, g, b}
     */
    public static float[] toFloatRgbArray(int[] argbColors) {
        float[] result = new float[argbColors.length * 3];
        for (int i = 0; i < argbColors.length; i++) {
            float[] rgb = argbToFloatRgb(argbColors[i]);
            result[i * 3] = rgb[0];
            result[i * 3 + 1] = rgb[1];
            result[i * 3 + 2] = rgb[2];
        }
        return result;
    }

    /**
     * 将整数数组转换为浮点RGBA数组
     * @param argbColors ARGB8888颜色数组
     * @return float数组，每4个元素为一组 {r, g, b, a}
     */
    public static float[] toFloatRgbaArray(int[] argbColors) {
        float[] result = new float[argbColors.length * 4];
        for (int i = 0; i < argbColors.length; i++) {
            float[] rgba = argbToFloatRgba(argbColors[i]);
            result[i * 4] = rgba[0];
            result[i * 4 + 1] = rgba[1];
            result[i * 4 + 2] = rgba[2];
            result[i * 4 + 3] = rgba[3];
        }
        return result;
    }

    /**
     * 将浮点RGB数组转换为整数ARGB数组
     * @param floatRgbArray float数组，每3个元素为一组 {r, g, b}
     * @return ARGB8888颜色数组
     */
    public static int[] fromFloatRgbArray(float[] floatRgbArray) {
        int count = floatRgbArray.length / 3;
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = floatRgbToArgb(
                    floatRgbArray[i * 3],
                    floatRgbArray[i * 3 + 1],
                    floatRgbArray[i * 3 + 2]
            );
        }
        return result;
    }

    /**
     * 将浮点RGBA数组转换为整数ARGB数组
     * @param floatRgbaArray float数组，每4个元素为一组 {r, g, b, a}
     * @return ARGB8888颜色数组
     */
    public static int[] fromFloatRgbaArray(float[] floatRgbaArray) {
        int count = floatRgbaArray.length / 4;
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = floatRgbaToArgb(
                    floatRgbaArray[i * 4],
                    floatRgbaArray[i * 4 + 1],
                    floatRgbaArray[i * 4 + 2],
                    floatRgbaArray[i * 4 + 3]
            );
        }
        return result;
    }

    // ==================== 纹理解析状态 ====================

    /**
     * 纹理解析状态枚举，跟踪纹理/颜色的解析完整度
     */
    public enum TextureParseState {
        UNPARSED(0),
        PARTIAL(1),
        COMPLETE(2),
        FAILED(3);

        private final int severity;
        TextureParseState(int severity) { this.severity = severity; }
        public int severity() { return severity; }
        public boolean isUsable() { return severity >= COMPLETE.severity; }
        public boolean isBetterThan(TextureParseState other) {
            return this.severity > other.severity;
        }
    }

    // ==================== 安全解析方法（不抛异常） ====================

    /**
     * 安全解析十六进制颜色字符串，解析失败返回 Optional.empty()
     */
    public static java.util.OptionalInt tryParseHex(String hex) {
        if (hex == null || hex.isEmpty()) return java.util.OptionalInt.empty();
        String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
            cleaned = cleaned.substring(2);
        }
        try {
            long value = Long.parseLong(cleaned, 16);
            switch (cleaned.length()) {
                case 6: return java.util.OptionalInt.of((int) (value | 0xFF000000));
                case 8: return java.util.OptionalInt.of((int) value);
                default: return java.util.OptionalInt.empty();
            }
        } catch (NumberFormatException e) {
            return java.util.OptionalInt.empty();
        }
    }

    /**
     * 安全解析CSS格式颜色字符串，解析失败返回 Optional.empty()
     */
    public static java.util.OptionalInt tryParseCss(String css) {
        if (css == null || css.isEmpty()) return java.util.OptionalInt.empty();
        try {
            return java.util.OptionalInt.of(parseCss(css.trim()));
        } catch (Exception e) {
            return java.util.OptionalInt.empty();
        }
    }

    /**
     * 安全解析浮点RGB字符串，解析失败返回 null
     */
    public static float[] tryParseFloatRgb(String r, String g, String b) {
        try {
            float rf = Float.parseFloat(r.trim());
            float gf = Float.parseFloat(g.trim());
            float bf = Float.parseFloat(b.trim());
            return new float[]{rf, gf, bf};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全的 int 分量提取，防止越界/非法值
     */
    public static int clampComponent(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static float clampFloat(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    /**
     * 从浮点 RGB 安全创建颜色，自动 clamp 所有分量到 [0,1]
     */
    public static int safeFloatRgbToArgb(float r, float g, float b) {
        return floatRgbToArgb(clampFloat(r), clampFloat(g), clampFloat(b));
    }

    /**
     * 从浮点 RGBA 安全创建颜色，自动 clamp 所有分量到 [0,1]
     */
    public static int safeFloatRgbaToArgb(float r, float g, float b, float a) {
        return floatRgbaToArgb(clampFloat(r), clampFloat(g), clampFloat(b), clampFloat(a));
    }

    // ==================== 颜色验证方法 ====================

    /**
     * 验证 ARGB 颜色值是否有效（alpha 通道非零，表示该像素不完全透明）
     * 注意：纯黑色 (0xFF000000) 是有效颜色，不应被拒绝。
     */
    public static boolean isValidColor(int argb) {
        return extractAlpha(argb) > 0;
    }

    /**
     * 验证浮点 RGB 分量是否在合法范围内
     */
    public static boolean isValidFloatRgb(float r, float g, float b) {
        return !Float.isNaN(r) && !Float.isNaN(g) && !Float.isNaN(b)
            && !Float.isInfinite(r) && !Float.isInfinite(g) && !Float.isInfinite(b);
    }

    /**
     * 判断颜色是否接近黑色（所有通道都很低）
     */
    public static boolean isNearBlack(int argb, int threshold) {
        int r = extractRed(argb);
        int g = extractGreen(argb);
        int b = extractBlue(argb);
        return r <= threshold && g <= threshold && b <= threshold;
    }

    /**
     * 判断颜色是否接近白色（所有通道都很高）
     */
    public static boolean isNearWhite(int argb, int threshold) {
        int r = extractRed(argb);
        int g = extractGreen(argb);
        int b = extractBlue(argb);
        return r >= threshold && g >= threshold && b >= threshold;
    }

    /**
     * 验证颜色数组是否全部有效
     */
    public static boolean allValid(int[] colors) {
        if (colors == null) return false;
        for (int c : colors) {
            if (!isValidColor(c)) return false;
        }
        return true;
    }

    // ==================== 颜色替换/合并 ====================

    /**
     * 当源颜色无效时返回默认颜色
     */
    public static int orDefault(int source, int defaultColor) {
        return isValidColor(source) ? source : defaultColor;
    }

    /**
     * 安全地混合源颜色和目标颜色（用于纹理回退）
     * 如果 source 无效则返回 target，如果 target 也无效则返回 fallback
     */
    public static int resolveColor(int source, int target, int fallback) {
        if (isValidColor(source)) return source;
        if (isValidColor(target)) return target;
        return fallback;
    }

    /**
     * 从两个颜色通道分别取最佳值（用于 VMT 属性覆盖）
     */
    public static int mergeAlpha(int base, int overlay) {
        int ba = extractAlpha(base);
        int oa = extractAlpha(overlay);
        int a = Math.max(ba, oa);
        int r = extractRed(base);
        int g = extractGreen(base);
        int b = extractBlue(base);
        return argb(a, r, g, b);
    }

    // ==================== 颜色空间批量转换 ====================

    /**
     * 安全地将 int 颜色数组转换为 float RGB，跳过无效值
     * @return float[] 数组，无效位置填充 NaN 标记
     */
    public static float[] safeToFloatRgbArray(int[] argbColors) {
        if (argbColors == null) return new float[0];
        float[] result = new float[argbColors.length * 3];
        for (int i = 0; i < argbColors.length; i++) {
            if (isValidColor(argbColors[i])) {
                float[] rgb = argbToFloatRgb(argbColors[i]);
                result[i * 3] = rgb[0];
                result[i * 3 + 1] = rgb[1];
                result[i * 3 + 2] = rgb[2];
            } else {
                result[i * 3] = Float.NaN;
                result[i * 3 + 1] = Float.NaN;
                result[i * 3 + 2] = Float.NaN;
            }
        }
        return result;
    }

    /**
     * 从 float 数组提取颜色，NaN 值自动替换为默认值
     */
    public static int fromSafeFloatRgb(float r, float g, float b, int defaultColor) {
        if (Float.isNaN(r) || Float.isNaN(g) || Float.isNaN(b)) return defaultColor;
        return safeFloatRgbToArgb(r, g, b);
    }

    // ==================== 预定义颜色常量 ====================

    // 常用颜色
    public static final int WHITE = 0xFFFFFFFF;
    public static final int BLACK = 0xFF000000;
    public static final int RED = 0xFFFF0000;
    public static final int GREEN = 0xFF00FF00;
    public static final int BLUE = 0xFF0000FF;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int CYAN = 0xFF00FFFF;
    public static final int MAGENTA = 0xFFFF00FF;
    public static final int ORANGE = 0xFFFFA500;
    public static final int PURPLE = 0xFF800080;
    public static final int PINK = 0xFFFFC0CB;
    public static final int BROWN = 0xFFA52A2A;
    public static final int GRAY = 0xFF808080;
    public static final int GREY = 0xFF808080;
    public static final int DARK_GRAY = 0xFF404040;
    public static final int LIGHT_GRAY = 0xFFC0C0C0;
    public static final int TRANSPARENT = 0x00000000;

    // Minecraft 风格颜色
    public static final int MC_SKIN = 0xFFD4A574;
    public static final int MC_SHIRT = 0xFF4A90D9;
    public static final int MC_PANTS = 0xFF4A4A4A;
    public static final int MC_SHOES = 0xFF2A2A2A;
    public static final int MC_EYE_WHITE = 0xFFFFFFFF;
    public static final int MC_PUPIL = 0xFF222222;
    public static final int MC_MOUTH = 0xFF8B5E5E;
    public static final int MC_EYEBROW = 0xFF5C3D2E;

    // 纹理解析失败回退颜色
    public static final int FALLBACK_TEXTURE = 0xFFFF00FF;  // 亮粉色（明显可见的回退色）
    public static final int FALLBACK_SKIN = MC_SKIN;
    public static final int FALLBACK_TRANSLUCENT = 0x80FFFFFF;
}
