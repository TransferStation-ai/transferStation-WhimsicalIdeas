// VtfParser.java - jsonContract
package transferstation.transferstation_whimsicalideas.client.model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VtfParser {

    private static final int VTF_SIGNATURE = 0x00465456;
    private static final int MAX_FILE_SIZE = 256 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;

    private static final int FORMAT_RGBA8888 = 0;
    private static final int FORMAT_ABGR8888 = 1;
    private static final int FORMAT_RGB888 = 2;
    private static final int FORMAT_BGR888 = 3;
    private static final int FORMAT_RGB565 = 4;
    private static final int FORMAT_I8 = 5;
    private static final int FORMAT_IA88 = 6;
    private static final int FORMAT_P8 = 7;
    private static final int FORMAT_A8 = 8;
    private static final int FORMAT_BGRX8888 = 10;
    private static final int FORMAT_BGR565 = 11;
    private static final int FORMAT_BGRA4444 = 13;
    private static final int FORMAT_DXT1 = 14;
    private static final int FORMAT_DXT3 = 15;
    private static final int FORMAT_DXT5 = 16;
    private static final int FORMAT_DXT1_ONEBITALPHA = 19;
    private static final int FORMAT_BGRA5551 = 20;
    private static final int FORMAT_RGBA16161616F = 25;
    private static final int FORMAT_RGBA16F = 23;
    private static final int FORMAT_RGB32F = 24;

    public static BufferedImage parseToBufferedImage(byte[] data) throws IOException {
        VtfImageData imageData = parse(data);
        if (imageData == null) return null;
        return imageData.image;
    }

    public static VtfImageData parse(byte[] data) throws IOException {
        if (data.length > MAX_FILE_SIZE) {
            throw new IOException("VTF file too large: " + data.length + " bytes");
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int signature = buf.getInt();
        if (signature != VTF_SIGNATURE) {
            throw new IOException("Not a valid VTF file (bad signature: 0x" + Integer.toHexString(signature) + ")");
        }

        int majorVersion = buf.getInt();
        int minorVersion = buf.getInt();
        int headerSize = buf.getInt();
        int width = buf.getShort() & 0xFFFF;
        int height = buf.getShort() & 0xFFFF;
        int flags = buf.getInt();
        int frames = buf.getShort() & 0xFFFF;
        int firstFrame = buf.getShort() & 0xFFFF;

        buf.position(buf.position() + 4);

        float reflectivity0 = buf.getFloat();
        float reflectivity1 = buf.getFloat();
        float reflectivity2 = buf.getFloat();

        buf.position(buf.position() + 4);

        float bumpmapScale = buf.getFloat();
        int imageFormat = buf.getInt();
        int mipmapCount = buf.get() & 0xFF;
        int lowResImageFormat = buf.getInt();
        int lowResImageWidth = buf.get() & 0xFF;
        int lowResImageHeight = buf.get() & 0xFF;
        int depth = buf.getShort() & 0xFFFF;

        if (width <= 0 || width > MAX_DIMENSION || height <= 0 || height > MAX_DIMENSION) {
            throw new IOException("Invalid VTF dimensions: " + width + "x" + height);
        }
        if (depth <= 0) depth = 1;

        buf.position(headerSize);

        // Skip low-resolution image data (thumbnail) if present
        if (lowResImageWidth > 0 && lowResImageHeight > 0 && lowResImageFormat >= 0) {
            int lowResDataSize = computeImageDataSize(lowResImageWidth, lowResImageHeight, lowResImageFormat);
            if (lowResDataSize > 0 && buf.position() + lowResDataSize <= buf.limit()) {
                buf.position(buf.position() + lowResDataSize);
            }
        }

        // VTF stores mipmaps from smallest (mipmapCount-1) to largest (mipmap 0).
        // For multi-frame VTFs, each mipmap level stores all frames.
        // Skip all smaller mipmaps to reach the full-resolution image data.
        int skipSize = 0;
        for (int i = mipmapCount - 1; i > 0; i--) {
            int mipWidth = Math.max(1, width >> i);
            int mipHeight = Math.max(1, height >> i);
            int mipSize = computeImageDataSize(mipWidth, mipHeight, imageFormat);
            if (mipSize <= 0) break;
            skipSize += mipSize * frames;
        }
        if (skipSize > 0 && buf.position() + skipSize <= buf.limit()) {
            buf.position(buf.position() + skipSize);
        }

        VtfImageData result = new VtfImageData();
        result.width = width;
        result.height = height;
        result.format = imageFormat;

        int dataSize = computeImageDataSize(width, height, imageFormat);

        byte[] rawData = new byte[dataSize];
        if (buf.remaining() < rawData.length) {
            rawData = new byte[buf.remaining()];
        }
        buf.get(rawData);

        BufferedImage image = decodeToImage(rawData, width, height, imageFormat);
        result.image = image;
        return result;
    }

    private static boolean isBlockCompressed(int format) {
        return format == FORMAT_DXT1 || format == FORMAT_DXT3 || format == FORMAT_DXT5 || format == FORMAT_DXT1_ONEBITALPHA;
    }

    private static int getBlockSize(int format) {
        switch (format) {
            case FORMAT_DXT1:
            case FORMAT_DXT1_ONEBITALPHA:
                return 8;
            case FORMAT_DXT3:
            case FORMAT_DXT5:
                return 16;
            default:
                return 4;
        }
    }

    private static int getBytesPerPixel(int format) {
        switch (format) {
            case FORMAT_RGB888:
            case FORMAT_BGR888:
                return 3;
            case FORMAT_RGBA8888:
            case FORMAT_ABGR8888:
            case FORMAT_BGRX8888:
            case FORMAT_RGBA16161616F:
            case FORMAT_RGBA16F:
            case FORMAT_RGB32F:
                return 4;
            case FORMAT_BGRA4444:
                return 2;
            case FORMAT_RGB565:
            case FORMAT_BGR565:
            case FORMAT_BGRA5551:
                return 2;
            case FORMAT_I8:
            case FORMAT_A8:
            case FORMAT_P8:
                return 1;
            case FORMAT_IA88:
                return 2;
            default:
                return 4;
        }
    }

    private static int computeImageDataSize(int width, int height, int format) {
        if (width <= 0 || height <= 0) return 0;
        if (isBlockCompressed(format)) {
            int blockSize = getBlockSize(format);
            int rowBlocks = (width + 3) / 4;
            int colBlocks = (height + 3) / 4;
            return rowBlocks * colBlocks * blockSize;
        } else if (format == FORMAT_RGBA16161616F) {
            return width * height * 8;
        } else if (format == FORMAT_RGBA16F) {
            return width * height * 8;
        } else if (format == FORMAT_RGB32F) {
            return width * height * 12;
        } else if (format >= 0) {
            int bytesPerPixel = getBytesPerPixel(format);
            return width * height * bytesPerPixel;
        }
        return 0;
    }

    private static BufferedImage decodeToImage(byte[] data, int width, int height, int format) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];

        switch (format) {
            case FORMAT_DXT1:
            case FORMAT_DXT1_ONEBITALPHA:
                decodeDXT1(data, width, height, pixels);
                break;
            case FORMAT_DXT3:
                decodeDXT3(data, width, height, pixels);
                break;
            case FORMAT_DXT5:
                decodeDXT5(data, width, height, pixels);
                break;
            case FORMAT_I8:
                decodeI8(data, width, height, pixels);
                break;
            case FORMAT_IA88:
                decodeIA88(data, width, height, pixels);
                break;
            case FORMAT_A8:
                decodeA8(data, width, height, pixels);
                break;
            case FORMAT_P8:
                decodeI8(data, width, height, pixels);
                break;
            case FORMAT_RGB565:
                decodeRGB565(data, width, height, pixels);
                break;
            case FORMAT_BGR565:
                decodeBGR565(data, width, height, pixels);
                break;
            case FORMAT_BGRA5551:
                decodeBGRA5551(data, width, height, pixels);
                break;
            case FORMAT_BGRA4444:
                decodeBGRA4444(data, width, height, pixels);
                break;
            case FORMAT_RGBA16161616F:
                decodeRGBA16F(data, width, height, pixels);
                break;
            case FORMAT_RGBA16F:
                decodeRGBA16F(data, width, height, pixels);
                break;
            case FORMAT_RGB32F:
                decodeRGB32F(data, width, height, pixels);
                break;
            default:
                boolean swapBR;
                boolean hasAlpha;
                switch (format) {
                    case FORMAT_BGR888:
                    case FORMAT_BGRX8888:
                    case FORMAT_BGRA4444:
                        swapBR = true;
                        hasAlpha = (format != FORMAT_BGRX8888);
                        break;
                    case FORMAT_ABGR8888:
                        swapBR = true;
                        hasAlpha = true;
                        break;
                    default:
                        swapBR = false;
                        hasAlpha = true;
                        break;
                }
                decodeRawRGBA(data, width, height, pixels, getBytesPerPixel(format), swapBR, hasAlpha);
                break;
        }

        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static void decodeRawRGBA(byte[] data, int width, int height, int[] pixels, int bytesPerPixel, boolean swapBR, boolean hasAlpha) {
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (idx + bytesPerPixel <= data.length) {
                    int ch0 = data[idx] & 0xFF;
                    int ch1 = data[idx + 1] & 0xFF;
                    int ch2 = data[idx + 2] & 0xFF;
                    int a = (hasAlpha && bytesPerPixel >= 4) ? (data[idx + 3] & 0xFF) : 255;
                    int r, g, b;
                    if (swapBR) {
                        b = ch0; g = ch1; r = ch2;
                    } else {
                        r = ch0; g = ch1; b = ch2;
                    }
                    pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    idx += bytesPerPixel;
                } else {
                    pixels[y * width + x] = 0xFFFFFFFF;
                }
            }
        }
    }

    private static void decodeI8(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height && idx < data.length; i++) {
            int val = data[idx] & 0xFF;
            pixels[i] = 0xFF000000 | (val << 16) | (val << 8) | val;
            idx++;
        }
    }

    private static void decodeIA88(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height && idx + 1 < data.length; i++) {
            int val = data[idx] & 0xFF;
            int a = data[idx + 1] & 0xFF;
            pixels[i] = (a << 24) | (val << 16) | (val << 8) | val;
            idx += 2;
        }
    }

    private static void decodeA8(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height && idx < data.length; i++) {
            int a = data[idx] & 0xFF;
            pixels[i] = (a << 24) | 0xFFFFFF;
            idx++;
        }
    }

    private static void decodeRGB565(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (idx + 1 < data.length) {
                    int c = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8);
                    pixels[y * width + x] = rgb565to888(c);
                    idx += 2;
                } else {
                    pixels[y * width + x] = 0xFFFFFFFF;
                }
            }
        }
    }

    private static void decodeBGR565(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (idx + 1 < data.length) {
                    int c = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8);
                    int b = ((c >> 11) & 0x1F) * 255 / 31;
                    int g = ((c >> 5) & 0x3F) * 255 / 63;
                    int r = (c & 0x1F) * 255 / 31;
                    pixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    idx += 2;
                } else {
                    pixels[y * width + x] = 0xFFFFFFFF;
                }
            }
        }
    }

    private static void decodeBGRA5551(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (idx + 1 < data.length) {
                    int c = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8);
                    int b = ((c >> 11) & 0x1F) * 255 / 31;
                    int g = ((c >> 6) & 0x1F) * 255 / 31;
                    int r = ((c >> 1) & 0x1F) * 255 / 31;
                    int a = (c & 1) * 255;
                    pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    idx += 2;
                } else {
                    pixels[y * width + x] = 0xFFFFFFFF;
                }
            }
        }
    }

    private static void decodeBGRA4444(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (idx + 1 < data.length) {
                    int c = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8);
                    int a = ((c >> 12) & 0xF) * 17;
                    int b = ((c >> 8) & 0xF) * 17;
                    int g = ((c >> 4) & 0xF) * 17;
                    int r = (c & 0xF) * 17;
                    pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    idx += 2;
                } else {
                    pixels[y * width + x] = 0xFFFFFFFF;
                }
            }
        }
    }

    private static void decodeRGBA16F(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height; i++) {
            if (idx + 7 < data.length) {
                float r = halfToFloat((data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8));
                float g = halfToFloat((data[idx + 2] & 0xFF) | ((data[idx + 3] & 0xFF) << 8));
                float b = halfToFloat((data[idx + 4] & 0xFF) | ((data[idx + 5] & 0xFF) << 8));
                float a = halfToFloat((data[idx + 6] & 0xFF) | ((data[idx + 7] & 0xFF) << 8));
                int ri = Math.min(255, Math.max(0, (int)(r * 255.0f)));
                int gi = Math.min(255, Math.max(0, (int)(g * 255.0f)));
                int bi = Math.min(255, Math.max(0, (int)(b * 255.0f)));
                int ai = Math.min(255, Math.max(0, (int)(a * 255.0f)));
                pixels[i] = (ai << 24) | (ri << 16) | (gi << 8) | bi;
                idx += 8;
            } else {
                pixels[i] = 0xFFFFFFFF;
            }
        }
    }

    private static void decodeRGB32F(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height; i++) {
            if (idx + 11 < data.length) {
                ByteBuffer bb = ByteBuffer.wrap(data, idx, 12).order(ByteOrder.LITTLE_ENDIAN);
                float r = bb.getFloat();
                float g = bb.getFloat();
                float b = bb.getFloat();
                int ri = Math.min(255, Math.max(0, (int)(r * 255.0f)));
                int gi = Math.min(255, Math.max(0, (int)(g * 255.0f)));
                int bi = Math.min(255, Math.max(0, (int)(b * 255.0f)));
                pixels[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
                idx += 12;
            } else {
                pixels[i] = 0xFFFFFFFF;
            }
        }
    }

    private static float halfToFloat(int half) {
        int sign = (half >> 15) & 1;
        int exponent = (half >> 10) & 0x1F;
        int mantissa = half & 0x3FF;

        if (exponent == 0) {
            if (mantissa == 0) {
                return sign == 0 ? 0.0f : -0.0f;
            }
            float value = (float)(mantissa / 1024.0) * (float)Math.pow(2, -14);
            return sign == 0 ? value : -value;
        } else if (exponent == 31) {
            if (mantissa == 0) {
                return sign == 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
            }
            return Float.NaN;
        }

        float value = (float)(1.0 + mantissa / 1024.0) * (float)Math.pow(2, exponent - 15);
        return sign == 0 ? value : -value;
    }

    private static void decodeDXT1(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int blockOff = (by * blockW + bx) * 8;
                if (blockOff + 8 > data.length) continue;
                int c0 = (data[blockOff + 1] & 0xFF) << 8 | (data[blockOff] & 0xFF);
                int c1 = (data[blockOff + 3] & 0xFF) << 8 | (data[blockOff + 2] & 0xFF);
                int bits = (data[blockOff + 7] & 0xFF) << 24 | (data[blockOff + 6] & 0xFF) << 16
                         | (data[blockOff + 5] & 0xFF) << 8 | (data[blockOff + 4] & 0xFF);
                int[] colors = new int[4];
                colors[0] = rgb565to888(c0);
                colors[1] = rgb565to888(c1);
                if (c0 > c1) {
                    colors[2] = lerpColorDXT(colors[0], colors[1], 1);
                    colors[3] = lerpColorDXT(colors[0], colors[1], 2);
                } else {
                    colors[2] = lerpColorDXTHalf(colors[0], colors[1]);
                    colors[3] = 0x00000000;
                }
                for (int py = 0; py < 4; py++) {
                    for (int px = 0; px < 4; px++) {
                        int idx = (bits >> (2 * (py * 4 + px))) & 3;
                        int pxAbs = bx * 4 + px;
                        int pyAbs = by * 4 + py;
                        if (pxAbs < width && pyAbs < height) {
                            pixels[pyAbs * width + pxAbs] = colors[idx];
                        }
                    }
                }
            }
        }
    }

    private static void decodeDXT3(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int blockOff = (by * blockW + bx) * 16;
                if (blockOff + 16 > data.length) continue;

                long alphaBits = 0;
                for (int i = 0; i < 8; i++) {
                    alphaBits |= (long)(data[blockOff + i] & 0xFF) << (i * 8);
                }

                int colorOff = blockOff + 8;
                int c0 = (data[colorOff + 1] & 0xFF) << 8 | (data[colorOff] & 0xFF);
                int c1 = (data[colorOff + 3] & 0xFF) << 8 | (data[colorOff + 2] & 0xFF);
                int bits = (data[colorOff + 7] & 0xFF) << 24 | (data[colorOff + 6] & 0xFF) << 16
                         | (data[colorOff + 5] & 0xFF) << 8 | (data[colorOff + 4] & 0xFF);

                int[] colors = new int[4];
                colors[0] = rgb565to888(c0);
                colors[1] = rgb565to888(c1);
                colors[2] = lerpColorDXT(colors[0], colors[1], 1);
                colors[3] = lerpColorDXT(colors[0], colors[1], 2);

                for (int py = 0; py < 4; py++) {
                    for (int px = 0; px < 4; px++) {
                        int idx = (bits >> (2 * (py * 4 + px))) & 3;
                        int alpha = (int)((alphaBits >> (4 * (py * 4 + px))) & 0xF) * 17;
                        int pxAbs = bx * 4 + px;
                        int pyAbs = by * 4 + py;
                        if (pxAbs < width && pyAbs < height) {
                            pixels[pyAbs * width + pxAbs] = (alpha << 24) | (colors[idx] & 0x00FFFFFF);
                        }
                    }
                }
            }
        }
    }

    private static void decodeDXT5(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int blockOff = (by * blockW + bx) * 16;
                if (blockOff + 16 > data.length) continue;

                int a0 = data[blockOff] & 0xFF;
                int a1 = data[blockOff + 1] & 0xFF;

                long alphaBits = 0;
                for (int i = 0; i < 6; i++) {
                    alphaBits |= (long)(data[blockOff + 2 + i] & 0xFF) << (i * 8);
                }

                int[] alphas = new int[8];
                alphas[0] = a0;
                alphas[1] = a1;
                if (a0 > a1) {
                    for (int i = 0; i < 6; i++) {
                        alphas[2 + i] = ((a0 * (6 - i) + a1 * (1 + i)) / 7) & 0xFF;
                    }
                } else {
                    for (int i = 0; i < 4; i++) {
                        alphas[2 + i] = ((a0 * (4 - i) + a1 * (1 + i)) / 5) & 0xFF;
                    }
                    alphas[6] = 0;
                    alphas[7] = 255;
                }

                int colorOff = blockOff + 8;
                int c0 = (data[colorOff + 1] & 0xFF) << 8 | (data[colorOff] & 0xFF);
                int c1 = (data[colorOff + 3] & 0xFF) << 8 | (data[colorOff + 2] & 0xFF);
                int bits = (data[colorOff + 7] & 0xFF) << 24 | (data[colorOff + 6] & 0xFF) << 16
                         | (data[colorOff + 5] & 0xFF) << 8 | (data[colorOff + 4] & 0xFF);

                int[] colors = new int[4];
                colors[0] = rgb565to888(c0);
                colors[1] = rgb565to888(c1);
                colors[2] = lerpColorDXT(colors[0], colors[1], 1);
                colors[3] = lerpColorDXT(colors[0], colors[1], 2);

                for (int py = 0; py < 4; py++) {
                    for (int px = 0; px < 4; px++) {
                        int colorIdx = (bits >> (2 * (py * 4 + px))) & 3;
                        int alphaIdx = (int)((alphaBits >> (3 * (py * 4 + px))) & 7);
                        int alpha = alphas[alphaIdx];
                        int pxAbs = bx * 4 + px;
                        int pyAbs = by * 4 + py;
                        if (pxAbs < width && pyAbs < height) {
                            pixels[pyAbs * width + pxAbs] = (alpha << 24) | (colors[colorIdx] & 0x00FFFFFF);
                        }
                    }
                }
            }
        }
    }

    private static int rgb565to888(int c) {
        int r = ((c >> 11) & 0x1F) * 255 / 31;
        int g = ((c >> 5) & 0x3F) * 255 / 63;
        int b = (c & 0x1F) * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerpColor(int c0, int c1, int t) {
        int a0 = (c0 >> 24) & 0xFF, r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a = ((a0 * (4 - t) + a1 * t) / 4) & 0xFF;
        int r = ((r0 * (4 - t) + r1 * t) / 4) & 0xFF;
        int g = ((g0 * (4 - t) + g1 * t) / 4) & 0xFF;
        int b = ((b0 * (4 - t) + b1 * t) / 4) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpColorDXT(int c0, int c1, int t) {
        int a0 = (c0 >> 24) & 0xFF, r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r = ((r0 * (3 - t) + r1 * t) / 3) & 0xFF;
        int g = ((g0 * (3 - t) + g1 * t) / 3) & 0xFF;
        int b = ((b0 * (3 - t) + b1 * t) / 3) & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerpColorDXTHalf(int c0, int c1) {
        int r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r = (r0 + r1) / 2;
        int g = (g0 + g1) / 2;
        int b = (b0 + b1) / 2;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static class VtfImageData {
        public int width;
        public int height;
        public int format;
        public BufferedImage image;
    }
}
