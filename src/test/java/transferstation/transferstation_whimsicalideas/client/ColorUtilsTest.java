package transferstation.transferstation_whimsicalideas.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ColorUtilsTest {

    @Test
    void extractAlpha() {
        assertEquals(0xFF, ColorUtils.extractAlpha(0xFFFF0000));
        assertEquals(0x80, ColorUtils.extractAlpha(0x80FF0000));
        assertEquals(0x00, ColorUtils.extractAlpha(0x00FFFFFF));
    }

    @Test
    void extractRed() {
        assertEquals(0xFF, ColorUtils.extractRed(0xFFFF0000));
        assertEquals(0x00, ColorUtils.extractRed(0xFF00FF00));
    }

    @Test
    void extractGreen() {
        assertEquals(0xFF, ColorUtils.extractGreen(0xFF00FF00));
        assertEquals(0x00, ColorUtils.extractGreen(0xFFFF0000));
    }

    @Test
    void extractBlue() {
        assertEquals(0xFF, ColorUtils.extractBlue(0xFF0000FF));
        assertEquals(0x00, ColorUtils.extractBlue(0xFFFF0000));
    }

    @Test
    void argb() {
        assertEquals(0xFFFF0000, ColorUtils.argb(255, 255, 0, 0));
        assertEquals(0x8000FF00, ColorUtils.argb(128, 0, 255, 0));
    }

    @Test
    void rgb() {
        assertEquals(0xFFFF0000, ColorUtils.rgb(255, 0, 0));
        assertEquals(0xFFAABBCC, ColorUtils.rgb(0xAA, 0xBB, 0xCC));
    }

    @Test
    void argbToRgb() {
        assertEquals(0x00FFFFFF, ColorUtils.argbToRgb(0xFFFFFFFF));
        assertEquals(0x00FF0000, ColorUtils.argbToRgb(0x80FF0000));
    }

    @Test
    void rgbToArgb() {
        assertEquals(0xFFFFFF00, ColorUtils.rgbToArgb(0x00FFFF00));
        assertEquals(0xFF000000, ColorUtils.rgbToArgb(0x00000000));
    }

    @Test
    void argbToRgba() {
        assertEquals(0xFF0000FF, ColorUtils.argbToRgba(0xFFFF0000));
    }

    @Test
    void rgbaToArgb() {
        assertEquals(0xFFFF0000, ColorUtils.rgbaToArgb(0xFF0000FF));
    }

    @Test
    void argbToBgr() {
        assertEquals(0x0000FF, ColorUtils.argbToBgr(0xFFFF0000));
        assertEquals(0x00FF00, ColorUtils.argbToBgr(0xFF00FF00));
    }

    @Test
    void bgrToArgb() {
        assertEquals(0xFFFF0000, ColorUtils.bgrToArgb(0x0000FF));
        assertEquals(0xFF00FF00, ColorUtils.bgrToArgb(0x00FF00));
    }

    @Test
    void argbToBgra() {
        assertEquals(0x0000FFFF, ColorUtils.argbToBgra(0xFFFF0000));
    }

    @Test
    void bgraToArgb() {
        assertEquals(0xFFFF0000, ColorUtils.bgraToArgb(0x0000FFFF));
    }

    @Test
    void rgb565ToArgb() {
        int rgb565 = 0b11111_000000_00000;
        int argb = ColorUtils.rgb565ToArgb(rgb565);
        assertEquals(255, ColorUtils.extractRed(argb));
        assertEquals(0, ColorUtils.extractGreen(argb));
        assertEquals(0, ColorUtils.extractBlue(argb));
    }

    @Test
    void argbToRgb565() {
        int rgb565 = ColorUtils.argbToRgb565(0xFFFF0000);
        int r5 = (rgb565 >> 11) & 0x1F;
        assertEquals(0x1F, r5);
    }

    @Test
    void argbToFloatRgb() {
        float[] rgb = ColorUtils.argbToFloatRgb(0xFFFF8080);
        assertEquals(1.0f, rgb[0], 0.01f);
        assertEquals(0.502f, rgb[1], 0.01f);
        assertEquals(0.502f, rgb[2], 0.01f);
    }

    @Test
    void argbToFloatRgba() {
        float[] rgba = ColorUtils.argbToFloatRgba(0x80FF0000);
        assertEquals(0.502f, rgba[3], 0.01f);
        assertEquals(1.0f, rgba[0], 0.01f);
    }

    @Test
    void floatRgbToArgb() {
        int argb = ColorUtils.floatRgbToArgb(1.0f, 0.5f, 0.0f);
        assertEquals(0xFFFF8000, argb);
    }

    @Test
    void floatRgbaToArgb() {
        int argb = ColorUtils.floatRgbaToArgb(1.0f, 0.0f, 0.0f, 0.5f);
        assertEquals(0x80FF0000, argb);
    }

    @ParameterizedTest
    @CsvSource({
        "#FF0000,   0xFFFF0000",
        "#00FF00,   0xFF00FF00",
        "0000FF,    0xFF0000FF",
        "#AABBCCDD, 0xAABBCCDD",
        "AABBCCDD,  0xAABBCCDD",
        "#FFF,      0xFFFFFFFF",
        "FFF,       0xFFFFFFFF",
        "#F80,      0xFFFF8800",
    })
    void parseHex(String input, String expectedHex) {
        int expected = (int) Long.parseLong(expectedHex.replace("0x", ""), 16);
        assertEquals(expected, ColorUtils.parseHex(input));
    }

    @Test
    void parseHex_invalid_throws() {
        assertThrows(IllegalArgumentException.class, () -> ColorUtils.parseHex(null));
        assertThrows(IllegalArgumentException.class, () -> ColorUtils.parseHex(""));
        assertThrows(IllegalArgumentException.class, () -> ColorUtils.parseHex("#GGG"));
    }

    @ParameterizedTest
    @CsvSource(value = {
        "rgb(255, 0, 0) | 0xFFFF0000",
        "rgba(255, 0, 0, 1) | 0xFFFF0000",
        "rgb(50%, 0%, 0%) | 0xFF800000"
    }, delimiter = '|')
    void parseCss(String input, String expectedHex) {
        int expected = (int) Long.parseLong(expectedHex.replace("0x", ""), 16);
        assertEquals(expected, ColorUtils.parseCss(input));
    }

    @Test
    void toHex() {
        assertEquals("#FFFF0000", ColorUtils.toHex(0xFFFF0000, true));
        assertEquals("#FF0000", ColorUtils.toHex(0xFFFF0000, false));
    }

    @Test
    void toCssRgb() {
        assertEquals("rgb(255, 0, 0)", ColorUtils.toCssRgb(0xFFFF0000));
    }

    @Test
    void toCssRgba() {
        assertEquals("rgba(255, 0, 0, 1.00)", ColorUtils.toCssRgba(0xFFFF0000));
    }

    @Test
    void hslToArgb() {
        int red = ColorUtils.hslToArgb(0.0f, 1.0f, 0.5f);
        assertEquals(0xFFFF0000, red);

        int green = ColorUtils.hslToArgb(1.0f / 3.0f, 1.0f, 0.5f);
        assertEquals(0xFF00FF00, green);

        int gray = ColorUtils.hslToArgb(0.0f, 0.0f, 0.5f);
        int expectedGray = ColorUtils.rgb(128, 128, 128);
        assertEquals(expectedGray, gray);
    }

    @Test
    void argbToHsl() {
        float[] hsl = ColorUtils.argbToHsl(0xFFFF0000);
        assertEquals(0.0f, hsl[0], 0.01f);
        assertEquals(1.0f, hsl[1], 0.01f);
        assertEquals(0.5f, hsl[2], 0.01f);

        float[] grayHsl = ColorUtils.argbToHsl(0xFF808080);
        assertEquals(0.0f, grayHsl[1], 0.01f);
    }

    @Test
    void hsvToArgb() {
        int red = ColorUtils.hsvToArgb(0.0f, 1.0f, 1.0f);
        assertEquals(0xFFFF0000, red);
    }

    @Test
    void argbToHsv() {
        float[] hsv = ColorUtils.argbToHsv(0xFFFF0000);
        assertEquals(0.0f, hsv[0], 0.01f);
        assertEquals(1.0f, hsv[1], 0.01f);
        assertEquals(1.0f, hsv[2], 0.01f);
    }

    @Test
    void luminance() {
        assertEquals(0.0f, ColorUtils.luminance(0xFF000000), 0.001f);
        assertEquals(1.0f, ColorUtils.luminance(0xFFFFFFFF), 0.001f);
    }

    @Test
    void perceivedBrightness() {
        assertEquals(0, ColorUtils.perceivedBrightness(0xFF000000));
        assertEquals(255, ColorUtils.perceivedBrightness(0xFFFFFFFF));
    }

    @Test
    void isDark() {
        assertTrue(ColorUtils.isDark(0xFF000000));
        assertFalse(ColorUtils.isDark(0xFFFFFFFF));
    }

    @Test
    void isLight() {
        assertTrue(ColorUtils.isLight(0xFFFFFFFF));
        assertFalse(ColorUtils.isLight(0xFF000000));
    }

    @Test
    void lerp() {
        int result = ColorUtils.lerp(0xFF000000, 0xFFFFFFFF, 0.5f);
        assertEquals(0xFF808080, result);
    }

    @Test
    void lerp_clamp() {
        int black = ColorUtils.lerp(0xFF000000, 0xFFFFFFFF, -0.5f);
        assertEquals(0xFF000000, black);
        int white = ColorUtils.lerp(0xFF000000, 0xFFFFFFFF, 1.5f);
        assertEquals(0xFFFFFFFF, white);
    }

    @Test
    void alphaBlend() {
        int opaqueRed = 0xFFFF0000;
        int semitransparentBlue = 0x800000FF;
        int blended = ColorUtils.alphaBlend(opaqueRed, semitransparentBlue);
        assertTrue(ColorUtils.extractAlpha(blended) > 0);
    }

    @Test
    void adjustBrightness() {
        int dimmed = ColorUtils.adjustBrightness(0xFFFF0000, 0.5f);
        assertEquals(0xFF800000, dimmed);
        int brightened = ColorUtils.adjustBrightness(0xFF800000, 2.0f);
        assertEquals(0xFFFF0000, brightened);
    }

    @Test
    void adjustSaturation() {
        int desaturated = ColorUtils.adjustSaturation(0xFFFF0000, 0.0f);
        assertTrue(ColorUtils.extractRed(desaturated) == ColorUtils.extractGreen(desaturated));
        assertTrue(ColorUtils.extractGreen(desaturated) == ColorUtils.extractBlue(desaturated));
    }

    @Test
    void adjustHue() {
        int result = ColorUtils.adjustHue(0xFFFF0000, 120.0f);
        assertEquals(0xFF00FF00, result);
    }

    @Test
    void invert() {
        assertEquals(0xFF0000FF, ColorUtils.invert(0xFFFFFF00));
        assertEquals(0xFFFFFFFF, ColorUtils.invert(0xFF000000));
    }

    @Test
    void grayscale() {
        int gray = ColorUtils.grayscale(0xFFFF0000);
        int value = ColorUtils.extractRed(gray);
        assertEquals(value, ColorUtils.extractGreen(gray));
        assertEquals(value, ColorUtils.extractBlue(gray));
    }

    @Test
    void setAlpha_int() {
        assertEquals(0x80FF0000, ColorUtils.setAlpha(0xFFFF0000, 128));
    }

    @Test
    void setAlpha_float() {
        assertEquals(0x80FF0000, ColorUtils.setAlpha(0xFFFF0000, 0.5f));
    }

    @Test
    void complementary() {
        int cyan = ColorUtils.complementary(0xFFFF0000);
        assertEquals(0xFF00FFFF, cyan);
    }

    @Test
    void contrastRatio() {
        float ratio = ColorUtils.contrastRatio(0xFFFFFFFF, 0xFF000000);
        assertEquals(21.0f, ratio, 0.01f);
        assertEquals(1.0f, ColorUtils.contrastRatio(0xFFFF0000, 0xFFFF0000), 0.01f);
    }

    @Test
    void meetsContrastRequirement() {
        assertTrue(ColorUtils.meetsContrastRequirement(0xFF000000, 0xFFFFFFFF, false));
        assertFalse(ColorUtils.meetsContrastRequirement(0xFF808080, 0xFFFFFFFF, false));
    }

    @Test
    void ensureContrast() {
        int adjusted = ColorUtils.ensureContrast(0xFF808080, 0xFFFFFFFF);
        float ratio = ColorUtils.contrastRatio(adjusted, 0xFFFFFFFF);
        assertTrue(ratio >= 4.5f);
    }

    @Test
    void toFloatRgbArray() {
        int[] colors = {0xFFFF0000, 0xFF00FF00};
        float[] result = ColorUtils.toFloatRgbArray(colors);
        assertEquals(6, result.length);
        assertEquals(1.0f, result[0], 0.01f);
        assertEquals(0.0f, result[1], 0.01f);
        assertEquals(0.0f, result[2], 0.01f);
        assertEquals(0.0f, result[3], 0.01f);
        assertEquals(1.0f, result[4], 0.01f);
        assertEquals(0.0f, result[5], 0.01f);
    }

    @Test
    void toFloatRgbaArray() {
        int[] colors = {0xFFFF0000};
        float[] result = ColorUtils.toFloatRgbaArray(colors);
        assertEquals(4, result.length);
        assertEquals(1.0f, result[3], 0.01f);
    }

    @Test
    void fromFloatRgbArray() {
        float[] rgb = {1.0f, 0.0f, 0.0f};
        int[] result = ColorUtils.fromFloatRgbArray(rgb);
        assertEquals(1, result.length);
        assertEquals(0xFFFF0000, result[0]);
    }

    @Test
    void fromFloatRgbaArray() {
        float[] rgba = {1.0f, 0.0f, 0.0f, 0.5f};
        int[] result = ColorUtils.fromFloatRgbaArray(rgba);
        assertEquals(1, result.length);
        assertEquals(0x80FF0000, result[0]);
    }

    @Test
    void textureParseState() {
        assertFalse(ColorUtils.TextureParseState.UNPARSED.isUsable());
        assertFalse(ColorUtils.TextureParseState.PARTIAL.isUsable());
        assertTrue(ColorUtils.TextureParseState.COMPLETE.isUsable());
        assertFalse(ColorUtils.TextureParseState.FAILED.isUsable());
        assertTrue(ColorUtils.TextureParseState.COMPLETE.isBetterThan(ColorUtils.TextureParseState.PARTIAL));
    }

    @Test
    void tryParseHex() {
        assertTrue(ColorUtils.tryParseHex("#FF0000").isPresent());
        assertEquals(0xFFFF0000, ColorUtils.tryParseHex("#FF0000").getAsInt());
        assertTrue(ColorUtils.tryParseHex(null).isEmpty());
        assertTrue(ColorUtils.tryParseHex("").isEmpty());
        assertTrue(ColorUtils.tryParseHex("#GGG").isEmpty());
    }

    @Test
    void tryParseCss() {
        assertTrue(ColorUtils.tryParseCss("rgb(255,0,0)").isPresent());
        assertEquals(0xFFFF0000, ColorUtils.tryParseCss("rgb(255,0,0)").getAsInt());
        assertTrue(ColorUtils.tryParseCss(null).isEmpty());
        assertTrue(ColorUtils.tryParseCss("invalid").isEmpty());
    }

    @Test
    void tryParseFloatRgb() {
        float[] result = ColorUtils.tryParseFloatRgb("1.0", "0.5", "0.0");
        assertNotNull(result);
        assertEquals(1.0f, result[0], 0.01f);
        assertNull(ColorUtils.tryParseFloatRgb("abc", "0.5", "0.0"));
    }

    @Test
    void clampComponent() {
        assertEquals(255, ColorUtils.clampComponent(300));
        assertEquals(0, ColorUtils.clampComponent(-1));
        assertEquals(128, ColorUtils.clampComponent(128));
    }

    @Test
    void clampFloat() {
        assertEquals(1.0f, ColorUtils.clampFloat(1.5f));
        assertEquals(0.0f, ColorUtils.clampFloat(-0.5f));
        assertEquals(0.5f, ColorUtils.clampFloat(0.5f));
    }

    @Test
    void safeFloatRgbToArgb() {
        assertEquals(0xFFFF0000, ColorUtils.safeFloatRgbToArgb(1.5f, 0.0f, 0.0f));
        assertEquals(0xFF000000, ColorUtils.safeFloatRgbToArgb(-0.5f, 0.0f, 0.0f));
    }

    @Test
    void safeFloatRgbaToArgb() {
        assertEquals(0x80FF0000, ColorUtils.safeFloatRgbaToArgb(1.5f, 0.0f, 0.0f, 0.5f));
    }

    @Test
    void isValidColor() {
        assertTrue(ColorUtils.isValidColor(0xFFFFFFFF));
        assertFalse(ColorUtils.isValidColor(0x00000000));
        assertTrue(ColorUtils.isValidColor(0xFF000000));
    }

    @Test
    void isValidFloatRgb() {
        assertTrue(ColorUtils.isValidFloatRgb(1.0f, 0.5f, 0.0f));
        assertFalse(ColorUtils.isValidFloatRgb(Float.NaN, 0.5f, 0.0f));
        assertFalse(ColorUtils.isValidFloatRgb(Float.POSITIVE_INFINITY, 0.5f, 0.0f));
    }

    @Test
    void isNearBlack() {
        assertTrue(ColorUtils.isNearBlack(0xFF000000, 10));
        assertFalse(ColorUtils.isNearBlack(0xFFFF0000, 10));
    }

    @Test
    void isNearWhite() {
        assertTrue(ColorUtils.isNearWhite(0xFFFFFFFF, 200));
        assertFalse(ColorUtils.isNearWhite(0xFF000000, 200));
    }

    @Test
    void allValid() {
        assertTrue(ColorUtils.allValid(new int[]{0xFFFF0000, 0xFF00FF00}));
        assertFalse(ColorUtils.allValid(new int[]{0xFFFF0000, 0x00000000}));
        assertFalse(ColorUtils.allValid(null));
    }

    @Test
    void orDefault() {
        assertEquals(0xFFFF0000, ColorUtils.orDefault(0xFFFF0000, 0xFF00FF00));
        assertEquals(0xFF00FF00, ColorUtils.orDefault(0x00000000, 0xFF00FF00));
    }

    @Test
    void resolveColor() {
        assertEquals(0xFFFF0000, ColorUtils.resolveColor(0xFFFF0000, 0xFF00FF00, 0xFF0000FF));
        assertEquals(0xFF00FF00, ColorUtils.resolveColor(0x00000000, 0xFF00FF00, 0xFF0000FF));
        assertEquals(0xFF0000FF, ColorUtils.resolveColor(0x00000000, 0x00000000, 0xFF0000FF));
    }

    @Test
    void mergeAlpha() {
        int result = ColorUtils.mergeAlpha(0x80FF0000, 0x40FFFFFF);
        assertEquals(0x80, ColorUtils.extractAlpha(result));
    }

    @Test
    void safeToFloatRgbArray() {
        float[] result = ColorUtils.safeToFloatRgbArray(new int[]{0xFFFF0000, 0x00000000});
        assertEquals(6, result.length);
        assertEquals(1.0f, result[0], 0.01f);
        assertTrue(Float.isNaN(result[3]));
        assertEquals(0, ColorUtils.safeToFloatRgbArray(null).length);
    }

    @Test
    void fromSafeFloatRgb() {
        assertEquals(0xFFFF0000, ColorUtils.fromSafeFloatRgb(1.0f, 0.0f, 0.0f, 0xFFFFFFFF));
        assertEquals(0xFFFFFFFF, ColorUtils.fromSafeFloatRgb(Float.NaN, 0.0f, 0.0f, 0xFFFFFFFF));
    }

    @Test
    void colorConstants() {
        assertEquals(0xFFFFFFFF, ColorUtils.WHITE);
        assertEquals(0xFF000000, ColorUtils.BLACK);
        assertEquals(0xFFFF0000, ColorUtils.RED);
        assertEquals(0xFF00FF00, ColorUtils.GREEN);
        assertEquals(0xFF0000FF, ColorUtils.BLUE);
        assertEquals(0x00000000, ColorUtils.TRANSPARENT);
    }

    @Test
    void roundTripArgbRgb() {
        int original = 0xFFAABBCC;
        int rgb = ColorUtils.argbToRgb(original);
        int roundTrip = ColorUtils.rgbToArgb(rgb);
        assertEquals(original, roundTrip);
    }

    @Test
    void roundTripHex() {
        int original = 0xFFAABBCC;
        String hex = ColorUtils.toHex(original, false);
        int parsed = ColorUtils.parseHex(hex);
        assertEquals(original, parsed);
    }

    @Test
    void roundTripHsl() {
        int original = 0xFFFF8800;
        float[] hsl = ColorUtils.argbToHsl(original);
        int reconstructed = ColorUtils.hslToArgb(hsl[0], hsl[1], hsl[2]);
        assertEquals(ColorUtils.extractRed(original), ColorUtils.extractRed(reconstructed), 1);
        assertEquals(ColorUtils.extractGreen(original), ColorUtils.extractGreen(reconstructed), 1);
        assertEquals(ColorUtils.extractBlue(original), ColorUtils.extractBlue(reconstructed), 1);
    }

    @Test
    void roundTripHsv() {
        int original = 0xFF4488CC;
        float[] hsv = ColorUtils.argbToHsv(original);
        int reconstructed = ColorUtils.hsvToArgb(hsv[0], hsv[1], hsv[2]);
        assertEquals(ColorUtils.extractRed(original), ColorUtils.extractRed(reconstructed), 1);
        assertEquals(ColorUtils.extractGreen(original), ColorUtils.extractGreen(reconstructed), 1);
        assertEquals(ColorUtils.extractBlue(original), ColorUtils.extractBlue(reconstructed), 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFAABBCC})
    void roundTripFormatConversions(int argb) {
        assertEquals(argb, ColorUtils.rgbaToArgb(ColorUtils.argbToRgba(argb)));
        assertEquals(argb, ColorUtils.bgraToArgb(ColorUtils.argbToBgra(argb)));
    }
}
