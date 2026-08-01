// VtfParser.java - jsonContract
package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.Inflater;

public class VtfParser {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VTF_SIGNATURE = 0x00465456;
    private static final int MAX_FILE_SIZE = 256 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;

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
    private static final int FORMAT_RG1616F = 26;
    private static final int FORMAT_RG32F = 27;
    private static final int FORMAT_RGB161616F = 28;

    // Extended format support
    private static final int FORMAT_ATI1N = 17;
    private static final int FORMAT_ATI2N = 18;
    private static final int FORMAT_BC6H = 29;
    private static final int FORMAT_BC7 = 30;

    public static BufferedImage parseToBufferedImage(byte[] data) throws IOException {
        VtfImageData imageData = parse(data);
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
        buf.getInt();
        int headerSize = buf.getInt();
        int width = buf.getShort() & 0xFFFF;
        int height = buf.getShort() & 0xFFFF;
        int flags = buf.getInt();
        int frames = buf.getShort() & 0xFFFF;
        buf.getShort();

        buf.position(buf.position() + 4);

        buf.getFloat();
        buf.getFloat();
        buf.getFloat();

        buf.position(buf.position() + 4);

        buf.getFloat();
        int imageFormat = buf.getInt();
        int mipmapCount = buf.get() & 0xFF;
        int lowResImageFormat = buf.getInt();
        int lowResImageWidth = buf.get() & 0xFF;
        int lowResImageHeight = buf.get() & 0xFF;
        buf.getShort();

        if (width == 0 || width > MAX_DIMENSION || height == 0 || height > MAX_DIMENSION) {
            throw new IOException("Invalid VTF dimensions: " + width + "x" + height);
        }

        buf.position(headerSize);

        // VTF 7.0+ stores a "resources" array between the header and the low-res
        // thumbnail. Each resource is an 8-byte descriptor (4-byte tag + 4-byte
        // data offset), and the data blocks they point to sit right after the
        // descriptor array. If we don't skip these, the low-res / mipmap skip
        // below starts at the wrong offset and the full-res pixels get read from
        // a shifted position -> diagonal gray stripes / garbage in the export.
        if (majorVersion >= 7) {
            int numResources = 0;
            if (headerSize >= 12) {
                // VTF 7.0+ stores the resource count as a 4-byte uint at
                // headerSize - 8 (the last field of the header). Reading it as
                // a short misreads the count and shifts all subsequent pixel
                // data, producing garbage textures.
                numResources = buf.getInt(headerSize - 8);
            }
            if (numResources <= 0 || numResources > 0x10000) {
                LOGGER.warn("[VtfParser] Invalid VTF7 resource count {} (headerSize={}); ignoring resources",
                    numResources, headerSize);
                numResources = 0;
            }
            if (numResources > 0) {
                int descArraySize = numResources * 8;
                int descArrayEnd = headerSize + descArraySize;
                // Each descriptor's last 4 bytes is the absolute offset of its
                // data block (relative to file start). Skip every data block so
                // we land exactly at the low-res thumbnail that follows.
                for (int r = 0; r < numResources; r++) {
                    int descOff = headerSize + r * 8;
                    if (descOff + 8 > buf.limit()) break;
                    int dataOffset = buf.getInt(descOff + 4);
                    if (dataOffset > descArrayEnd && dataOffset < buf.limit()) {
                        descArrayEnd = dataOffset;
                    }
                }
                if (descArrayEnd <= buf.limit()) {
                    buf.position(descArrayEnd);
                }
            }
        }

        // Skip low-resolution image data (thumbnail) if present
        if (lowResImageWidth > 0 && lowResImageHeight > 0 && lowResImageFormat >= 0) {
            int lowResDataSize = computeImageDataSize(lowResImageWidth, lowResImageHeight, lowResImageFormat);
            if (lowResDataSize > 0 && buf.position() + lowResDataSize <= buf.limit()) {
                buf.position(buf.position() + lowResDataSize);
            }
        }

        boolean isCubemap = (flags & 0x20000000) != 0;
        int faceCount = isCubemap ? 6 : 1;

        // VTF stores data mip-major: mipmaps from smallest (mipmapCount-1) to
        // largest (mipmap 0); within each mipmap level, all frames are stored
        // contiguously (first to last), and within each frame, all faces.
        // Skip all smaller mipmaps to reach the full-resolution image data.
        int skipSize = 0;
        for (int i = mipmapCount - 1; i > 0; i--) {
            int mipWidth = Math.max(1, width >> i);
            int mipHeight = Math.max(1, height >> i);
            int mipSize = computeImageDataSize(mipWidth, mipHeight, imageFormat);
            if (mipSize <= 0) break;
            skipSize += mipSize * frames * faceCount;
        }
        if (skipSize > 0 && buf.position() + skipSize <= buf.limit()) {
            buf.position(buf.position() + skipSize);
        }

        VtfImageData result = new VtfImageData();
        result.width = width;
        result.height = height;
        result.format = imageFormat;
        result.frameCount = frames;
        result.isCubemap = isCubemap;

        int dataSize = computeImageDataSize(width, height, imageFormat);

        // For P8 format, read palette first (1024 bytes: 256 * RGBA)
        byte[] p8Palette = null;
        if (imageFormat == FORMAT_P8) {
            p8Palette = new byte[1024];
            if (buf.remaining() >= 1024) {
                buf.get(p8Palette);
            }
        }

        byte[] rawData = new byte[dataSize];
        if (buf.remaining() < rawData.length) {
            rawData = new byte[buf.remaining()];
        }
        buf.get(rawData);

        // Check if data is Zlib compressed. A bare 0x78 first byte is NOT a
        // reliable signal (DXT/raw pixel data can legitimately start with 0x78),
        // so require a valid zlib header (0x78 0x01/0x9C/0xDA) AND a decompression
        // whose size is plausible for the image. Otherwise we'd corrupt real
        // texture data into gray garbage.
        if (rawData.length > 2 && isZlibHeader(rawData[0], rawData[1])) {
            byte[] decompressed = decompressZlib(rawData);
            if (decompressed != null && decompressed.length >= rawData.length) {
                rawData = decompressed;
            }
        }

        BufferedImage image = decodeToImage(rawData, width, height, imageFormat, p8Palette);
        java.util.List<BufferedImage> framesList = new java.util.ArrayList<>();
        framesList.add(image);

        // Read cubemap faces (5 more after the first one at mip 0)
        if (isCubemap) {
            result.cubemapFaces = new BufferedImage[6];
            result.cubemapFaces[0] = image;
            for (int face = 1; face < 6; face++) {
                if (buf.remaining() < dataSize) break;
                byte[] faceData = new byte[dataSize];
                buf.get(faceData);
                if (faceData.length > 2 && isZlibHeader(faceData[0], faceData[1])) {
                    byte[] decompressed = decompressZlib(faceData);
                    if (decompressed != null && decompressed.length >= faceData.length) {
                        faceData = decompressed;
                    }
                }
                result.cubemapFaces[face] = decodeToImage(faceData, width, height, imageFormat, p8Palette);
            }
        }

        // Read additional frames for multi-frame VTF
        // VTF stores data mip-major: for each mipmap level (smallest to
        // largest), all frames are stored contiguously (first to last), and
        // within each frame, all faces. After skipping the smaller mip levels
        // above, the full-res level contains all frames back-to-back, so
        // frame f's data directly follows frame f-1's.
        if (frames > 1) {
            for (int f = 1; f < frames; f++) {
                BufferedImage frame = null;
                for (int face = 0; face < faceCount; face++) {
                    if (buf.remaining() < dataSize) {
                        frame = null;
                        break;
                    }
                    byte[] frameData = new byte[dataSize];
                    buf.get(frameData);
                    if (frameData.length > 2 && isZlibHeader(frameData[0], frameData[1])) {
                        byte[] decompressed = decompressZlib(frameData);
                        if (decompressed != null && decompressed.length >= frameData.length) {
                            frameData = decompressed;
                        }
                    }
                    if (face == 0) {
                        frame = decodeToImage(frameData, width, height, imageFormat, p8Palette);
                    }
                }
                if (frame != null) framesList.add(frame);
            }
        }

        result.image = image;
        result.frames = framesList;
        return result;
    }

    private static boolean isBlockCompressed(int format) {
        return format == FORMAT_DXT1 || format == FORMAT_DXT3 || format == FORMAT_DXT5
            || format == FORMAT_DXT1_ONEBITALPHA
            || format == FORMAT_ATI1N || format == FORMAT_ATI2N
            || format == FORMAT_BC6H || format == FORMAT_BC7;
    }

    private static int getBlockSize(int format) {
        return switch (format) {
            case FORMAT_DXT1, FORMAT_DXT1_ONEBITALPHA, FORMAT_ATI1N -> 8;
            case FORMAT_DXT3, FORMAT_DXT5, FORMAT_ATI2N, FORMAT_BC6H, FORMAT_BC7 -> 16;
            default -> 4;
        };
    }

    private static int getBytesPerPixel(int format) {
        return switch (format) {
            case FORMAT_RGB888, FORMAT_BGR888 -> 3;
            case FORMAT_RG1616F, FORMAT_RG32F -> 8;
            case FORMAT_RGB161616F -> 6;
            case FORMAT_BGRA4444, FORMAT_RGB565, FORMAT_BGR565, FORMAT_BGRA5551, FORMAT_IA88 -> 2;
            case FORMAT_I8, FORMAT_A8, FORMAT_P8 -> 1;
            default -> 4;
        };
    }

    private static boolean isZlibHeader(byte b0, byte b1) {
        // Valid zlib (RFC 1950) headers: 0x78 followed by 0x01, 0x9C, or 0xDA.
        // Other 0x78 values are not zlib and must not be inflated.
        if ((b0 & 0xFF) != 0x78) return false;
        int b = b1 & 0xFF;
        return b == 0x01 || b == 0x9C || b == 0xDA;
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
        } else if (format == FORMAT_RG1616F) {
            return width * height * 4;
        } else if (format == FORMAT_RG32F) {
            return width * height * 8;
        } else if (format == FORMAT_RGB161616F) {
            return width * height * 6;
        } else if (format >= 0) {
            int bytesPerPixel = getBytesPerPixel(format);
            return width * height * bytesPerPixel;
        }
        return 0;
    }

    private static BufferedImage decodeToImage(byte[] data, int width, int height, int format, byte[] palette) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];

        switch (format) {
            case FORMAT_DXT1:
                decodeDXT1(data, width, height, pixels);
                break;
            case FORMAT_DXT1_ONEBITALPHA:
                decodeDXT1OneBitAlpha(data, width, height, pixels);
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
                if (palette != null) {
                    decodeP8(data, width, height, pixels, palette);
                } else {
                    decodeI8(data, width, height, pixels);
                }
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
            case FORMAT_RGBA16161616F, FORMAT_RGBA16F:
                decodeRGBA16F(data, width, height, pixels);
                break;
            case FORMAT_RGB32F:
                decodeRGB32F(data, width, height, pixels);
                break;
            case FORMAT_RG1616F:
                decodeRG1616F(data, width, height, pixels);
                break;
            case FORMAT_RG32F:
                decodeRG32F(data, width, height, pixels);
                break;
            case FORMAT_RGB161616F:
                decodeRGB161616F(data, width, height, pixels);
                break;
            case FORMAT_ATI1N:
                decodeATI1N(data, width, height, pixels);
                break;
            case FORMAT_ATI2N:
                decodeATI2N(data, width, height, pixels);
                break;
            case FORMAT_BC6H:
                decodeBC6H(data, width, height, pixels);
                break;
            case FORMAT_BC7:
                decodeBC7(data, width, height, pixels);
                break;
            default:
                boolean swapBR;
                boolean hasAlpha = switch (format) {
                    case FORMAT_BGR888, FORMAT_BGRX8888 -> {
                        swapBR = true;
                        yield (format != FORMAT_BGRX8888);
                    }
                    case FORMAT_ABGR8888 -> {
                        swapBR = true;
                        yield true;
                    }
                    default -> {
                        swapBR = false;
                        yield true;
                    }
                };
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

    private static void decodeRG1616F(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height; i++) {
            if (idx + 3 < data.length) {
                float r = halfToFloat((data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8));
                float g = halfToFloat((data[idx + 2] & 0xFF) | ((data[idx + 3] & 0xFF) << 8));
                int ri = Math.min(255, Math.max(0, (int)(r * 255.0f)));
                int gi = Math.min(255, Math.max(0, (int)(g * 255.0f)));
                pixels[i] = 0xFF000000 | (ri << 16) | (gi << 8);
                idx += 4;
            } else {
                pixels[i] = 0xFFFFFFFF;
            }
        }
    }

    private static void decodeRG32F(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height; i++) {
            if (idx + 7 < data.length) {
                ByteBuffer bb = ByteBuffer.wrap(data, idx, 8).order(ByteOrder.LITTLE_ENDIAN);
                float r = bb.getFloat();
                float g = bb.getFloat();
                int ri = Math.min(255, Math.max(0, (int)(r * 255.0f)));
                int gi = Math.min(255, Math.max(0, (int)(g * 255.0f)));
                pixels[i] = 0xFF000000 | (ri << 16) | (gi << 8);
                idx += 8;
            } else {
                pixels[i] = 0xFFFFFFFF;
            }
        }
    }

    private static void decodeRGB161616F(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height; i++) {
            if (idx + 5 < data.length) {
                float r = halfToFloat((data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8));
                float g = halfToFloat((data[idx + 2] & 0xFF) | ((data[idx + 3] & 0xFF) << 8));
                float b = halfToFloat((data[idx + 4] & 0xFF) | ((data[idx + 5] & 0xFF) << 8));
                int ri = Math.min(255, Math.max(0, (int)(r * 255.0f)));
                int gi = Math.min(255, Math.max(0, (int)(g * 255.0f)));
                int bi = Math.min(255, Math.max(0, (int)(b * 255.0f)));
                pixels[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
                idx += 6;
            } else {
                pixels[i] = 0xFFFFFFFF;
            }
        }
    }

    private static void decodeP8(byte[] data, int width, int height, int[] pixels, byte[] palette) {
        if (palette == null || palette.length < 1024) {
            decodeI8(data, width, height, pixels);
            return;
        }
        int[] paletteColors = new int[256];
        for (int i = 0; i < 256; i++) {
            int off = i * 4;
            int r = palette[off] & 0xFF;
            int g = palette[off + 1] & 0xFF;
            int b = palette[off + 2] & 0xFF;
            int a = palette[off + 3] & 0xFF;
            paletteColors[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        int idx = 0;
        for (int i = 0; i < width * height && idx < data.length; i++) {
            int palIdx = data[idx] & 0xFF;
            pixels[i] = paletteColors[palIdx % 256];
            idx++;
        }
    }

    private static void decodeATI1N(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int blockOff = (by * blockW + bx) * 8;
                if (blockOff + 8 > data.length) continue;
                int r0 = data[blockOff] & 0xFF;
                int r1 = data[blockOff + 1] & 0xFF;
                long bits = 0;
                for (int b = 2; b < 8; b++) {
                    bits |= (long)(data[blockOff + b] & 0xFF) << ((b - 2) * 8);
                }
                int[] reds = new int[8];
                reds[0] = r0;
                reds[1] = r1;
                if (r0 > r1) {
                    for (int i = 2; i < 8; i++) {
                        reds[i] = ((8 - i) * r0 + (i - 1) * r1) / 7;
                    }
                } else {
                    for (int i = 2; i < 6; i++) {
                        reds[i] = ((6 - i) * r0 + (i - 1) * r1) / 5;
                    }
                    reds[6] = 0;
                    reds[7] = 255;
                }
                for (int py = 0; py < 4; py++) {
                    for (int px = 0; px < 4; px++) {
                        int idx = (int)((bits >> (3 * (py * 4 + px))) & 7);
                        int v = reds[idx];
                        int pxAbs = bx * 4 + px, pyAbs = by * 4 + py;
                        if (pxAbs < width && pyAbs < height) {
                            pixels[pyAbs * width + pxAbs] = 0xFF000000 | (v << 16) | (v << 8) | v;
                        }
                    }
                }
            }
        }
    }

    private static void decodeATI2N(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int blockOff = (by * blockW + bx) * 16;
                if (blockOff + 16 > data.length) continue;
                for (int ch = 0; ch < 2; ch++) {
                    int chOff = blockOff + ch * 8;
                    int v0 = data[chOff] & 0xFF;
                    int v1 = data[chOff + 1] & 0xFF;
                    long bits = 0;
                    for (int b = 2; b < 8; b++) {
                        bits |= (long)(data[chOff + b] & 0xFF) << ((b - 2) * 8);
                    }
                    int[] vals = new int[8];
                    vals[0] = v0;
                    vals[1] = v1;
                    if (v0 > v1) {
                        for (int i = 2; i < 8; i++) {
                            vals[i] = ((8 - i) * v0 + (i - 1) * v1) / 7;
                        }
                    } else {
                        for (int i = 2; i < 6; i++) {
                            vals[i] = ((6 - i) * v0 + (i - 1) * v1) / 5;
                        }
                        vals[6] = 0;
                        vals[7] = 255;
                    }
                    for (int py = 0; py < 4; py++) {
                        for (int px = 0; px < 4; px++) {
                            int idx = (int)((bits >> (3 * (py * 4 + px))) & 7);
                            int pxAbs = bx * 4 + px, pyAbs = by * 4 + py;
                            if (pxAbs < width && pyAbs < height) {
                                int pixelIdx = pyAbs * width + pxAbs;
                                if (ch == 0) {
                                    int r = vals[idx];
                                    pixels[pixelIdx] = 0xFF000000 | (r << 16);
                                } else {
                                    int g = vals[idx];
                                    int r = (pixels[pixelIdx] >> 16) & 0xFF;
                                    float rn = r / 255.0f * 2.0f - 1.0f;
                                    float gn = g / 255.0f * 2.0f - 1.0f;
                                    float zn = (float)Math.sqrt(Math.max(0, 1.0f - rn*rn - gn*gn));
                                    int b = (int)((zn * 0.5f + 0.5f) * 255.0f);
                                    pixels[pixelIdx] = 0xFF000000 | (r << 16) | (g << 8) | clampByte(b);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Decode BC6H (BPTC float format) - port of DirectXTex D3DX_BC6H::Decode
     */
    private static void decodeBC6H(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int blockOff = (by * blockW + bx) * 16;
                if (blockOff + 16 > data.length) continue;
                int[] block = decodeBC6HBlock(data, blockOff);
                for (int i = 0; i < 16; i++) {
                    int pxAbs = bx * 4 + (i & 3), pyAbs = by * 4 + (i >> 2);
                    if (pxAbs < width && pyAbs < height) {
                        pixels[pyAbs * width + pxAbs] = block[i];
                    }
                }
            }
        }
    }

    private static int[] decodeBC6HBlock(byte[] data, int blockOff) {
        int[] out = new int[16];
        int bitPos;
        int modeCode = (int) readBits(data, blockOff, 0, 2);
        if (modeCode != 0 && modeCode != 1) {
            modeCode = ((int) readBits(data, blockOff, 2, 3) << 2) | modeCode;
        }
        bitPos = (modeCode == 0 || modeCode == 1) ? 2 : 5;
        if (modeCode >= BC6H_MODE_TO_INFO.length || BC6H_MODE_TO_INFO[modeCode] < 0) {
            // Invalid/reserved mode: opaque black per BC6H spec
            for (int i = 0; i < 16; i++) out[i] = 0xFF000000;
            return out;
        }
        int mode = BC6H_MODE_TO_INFO[modeCode];
        int[] ep = new int[12];
        int shape = 0;
        int headerBits = BC6H_PARTITIONS[mode] > 0 ? 82 : 65;
        String[] desc = BC6H_DESC[mode];
        while (bitPos < headerBits) {
            if (readBits(data, blockOff, bitPos, 1) != 0) {
                String tok = desc[bitPos];
                switch (tok.charAt(0)) {
                    case 'D':
                        shape |= 1 << Integer.parseInt(tok.substring(1));
                        break;
                    case 'R':
                    case 'G':
                    case 'B': {
                        int epIdx = switch (tok.charAt(1)) {
                            case 'W' -> 0;
                            case 'X' -> 1;
                            case 'Y' -> 2;
                            default -> 3;
                        };
                        int ch = tok.charAt(0) == 'R' ? 0 : (tok.charAt(0) == 'G' ? 1 : 2);
                        ep[epIdx * 3 + ch] |= 1 << Integer.parseInt(tok.substring(2));
                        break;
                    }
                    case 'N':
                    default:
                        return bc6hErrorColors(out);
                }
            }
            bitPos++;
        }

        int[] prec00 = BC6H_PREC[mode][0];
        int[] prec01 = BC6H_PREC[mode][1];
        int[] prec10 = BC6H_PREC[mode][2];
        int[] prec11 = BC6H_PREC[mode][3];

        if (BC6H_TRANSFORMED[mode]) {
            for (int ch = 0; ch < 3; ch++) {
                ep[3 + ch] = signExtend(ep[3 + ch], prec01[ch]);
                if (BC6H_PARTITIONS[mode] > 0) {
                    ep[6 + ch] = signExtend(ep[6 + ch], prec10[ch]);
                    ep[9 + ch] = signExtend(ep[9 + ch], prec11[ch]);
                }
            }
            for (int ch = 0; ch < 3; ch++) {
                int wrap = (1 << prec00[ch]) - 1;
                ep[3 + ch] = (ep[3 + ch] + ep[ch]) & wrap;
                if (BC6H_PARTITIONS[mode] > 0) {
                    ep[6 + ch] = (ep[6 + ch] + ep[ch]) & wrap;
                    ep[9 + ch] = (ep[9 + ch] + ep[ch]) & wrap;
                }
            }
        }

        int[] weights = BC6H_PARTITIONS[mode] > 0 ? BC6H_WEIGHTS3 : BC6H_WEIGHTS4;
        int[] fixups = BC6H_BC7_FIXUP[BC6H_PARTITIONS[mode]][shape];
        for (int i = 0; i < 16; i++) {
            int numBits = BC6H_INDEX_PREC[mode];
            for (int p = 0; p <= BC6H_PARTITIONS[mode]; p++) {
                if (i == fixups[p]) {
                    numBits--;
                    break;
                }
            }
            int index = (int) readBits(data, blockOff, bitPos, numBits);
            bitPos += numBits;
            if (index >= (BC6H_PARTITIONS[mode] > 0 ? 8 : 16)) {
                return bc6hErrorColors(out);
            }
            int region = BC6H_PARTITIONS[mode] > 0 ? BC7_P2_TABLE[shape][i] : 0;
            int w = weights[index];
            int r1 = unquantizeBC6H(ep[region * 6], prec00[0]);
            int g1 = unquantizeBC6H(ep[region * 6 + 1], prec00[1]);
            int b1 = unquantizeBC6H(ep[region * 6 + 2], prec00[2]);
            int r2 = unquantizeBC6H(ep[region * 6 + 3], prec00[0]);
            int g2 = unquantizeBC6H(ep[region * 6 + 4], prec00[1]);
            int b2 = unquantizeBC6H(ep[region * 6 + 5], prec00[2]);
            int fr = finishUnquantizeBC6H((r1 * (64 - w) + r2 * w + 32) >> 6);
            int fg = finishUnquantizeBC6H((g1 * (64 - w) + g2 * w + 32) >> 6);
            int fb = finishUnquantizeBC6H((b1 * (64 - w) + b2 * w + 32) >> 6);
            int ri = clampByte((int) (halfToFloat(fr) * 255.0f));
            int gi = clampByte((int) (halfToFloat(fg) * 255.0f));
            int bi = clampByte((int) (halfToFloat(fb) * 255.0f));
            out[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
        }
        return out;
    }

    private static int[] bc6hErrorColors(int[] out) {
        for (int i = 0; i < 16; i++) out[i] = 0xFFFF00FF;
        return out;
    }

    private static int unquantizeBC6H(int comp, int bits) {
        if (bits >= 15) return comp;
        if (comp == 0) return 0;
        if (comp == (1 << bits) - 1) return 0xFFFF;
        return ((comp << 16) + 0x8000) >> bits;
    }

    private static int finishUnquantizeBC6H(int comp) {
        return (comp * 31) >> 6;
    }

    /**
     * Decode BC7 (BPTC RGBA format) - port of DirectXTex D3DX_BC7::Decode
     */
    private static void decodeBC7(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int blockOff = (by * blockW + bx) * 16;
                if (blockOff + 16 > data.length) continue;
                int[] block = decodeBC7Block(data, blockOff);
                for (int i = 0; i < 16; i++) {
                    int pxAbs = bx * 4 + (i & 3), pyAbs = by * 4 + (i >> 2);
                    if (pxAbs < width && pyAbs < height) {
                        pixels[pyAbs * width + pxAbs] = block[i];
                    }
                }
            }
        }
    }

    private static int[] decodeBC7Block(byte[] data, int blockOff) {
        int[] out = new int[16];
        int bitPos = 0;
        while (bitPos < 8 && readBits(data, blockOff, bitPos, 1) == 0) bitPos++;
        if (bitPos >= 8) {
            // Reserved mode 8: transparent black per BC7 spec
            return out;
        }
        int mode = bitPos;
        bitPos = mode + 1;
        int partitions = BC7_PARTITIONS[mode];
        int shape = 0;
        int rotation = 0;
        int indexMode = 0;
        if (BC7_PARTITION_BITS[mode] > 0) {
            shape = (int) readBits(data, blockOff, bitPos, BC7_PARTITION_BITS[mode]);
            bitPos += BC7_PARTITION_BITS[mode];
        }
        if (BC7_ROTATION_BITS[mode] > 0) {
            rotation = (int) readBits(data, blockOff, bitPos, BC7_ROTATION_BITS[mode]);
            bitPos += BC7_ROTATION_BITS[mode];
        }
        if (BC7_INDEX_MODE_BITS[mode] > 0) {
            indexMode = (int) readBits(data, blockOff, bitPos, BC7_INDEX_MODE_BITS[mode]);
            bitPos += BC7_INDEX_MODE_BITS[mode];
        }

        int numEndpoints = (partitions + 1) * 2;
        int[] prec = BC7_RGBA_PREC[mode];
        int[] endPts = new int[numEndpoints * 4];
        for (int ch = 0; ch < 4; ch++) {
            for (int e = 0; e < numEndpoints; e++) {
                if (prec[ch] > 0) {
                    endPts[e * 4 + ch] = (int) readBits(data, blockOff, bitPos, prec[ch]);
                    bitPos += prec[ch];
                } else {
                    endPts[e * 4 + ch] = (ch == 3) ? 255 : 0;
                }
            }
        }

        int pBits = BC7_PBITS[mode];
        int[] pv = new int[6];
        for (int i = 0; i < pBits; i++) {
            pv[i] = (int) readBits(data, blockOff, bitPos, 1);
            bitPos++;
        }
        int[] precWithP = BC7_RGBA_PREC_WITH_P[mode];
        if (pBits > 0) {
            for (int e = 0; e < numEndpoints; e++) {
                int pi = e * pBits / numEndpoints;
                for (int ch = 0; ch < 4; ch++) {
                    if (prec[ch] != precWithP[ch]) {
                        endPts[e * 4 + ch] = (endPts[e * 4 + ch] << 1) | pv[pi];
                    }
                }
            }
        }
        for (int e = 0; e < numEndpoints; e++) {
            for (int ch = 0; ch < 4; ch++) {
                if (precWithP[ch] > 0) {
                    endPts[e * 4 + ch] = unquantizeBC7(endPts[e * 4 + ch], precWithP[ch]);
                }
            }
        }

        int[] w1 = new int[16];
        int[] w2 = new int[16];
        int idxPrec = BC7_INDEX_PREC[mode];
        int idxPrec2 = BC7_INDEX_PREC2[mode];
        int[] fixups = BC6H_BC7_FIXUP[partitions][shape];
        for (int i = 0; i < 16; i++) {
            int nb = idxPrec;
            for (int p = 0; p <= partitions; p++) {
                if (i == fixups[p]) {
                    nb--;
                    break;
                }
            }
            w1[i] = (int) readBits(data, blockOff, bitPos, nb);
            bitPos += nb;
        }
        if (idxPrec2 > 0) {
            for (int i = 0; i < 16; i++) {
                int nb = (i == 0) ? idxPrec2 - 1 : idxPrec2;
                w2[i] = (int) readBits(data, blockOff, bitPos, nb);
                bitPos += nb;
            }
        }

        for (int i = 0; i < 16; i++) {
            int region = partitions == 0 ? 0 : (partitions == 1 ? BC7_P2_TABLE[shape][i] : BC7_P3_TABLE[shape][i]);
            int c0 = region * 2;
            int c1 = c0 + 1;
            int wc, wa, wcPrec, waPrec;
            if (idxPrec2 == 0) {
                wc = w1[i];
                wa = w1[i];
                wcPrec = idxPrec;
                waPrec = idxPrec;
            } else if (indexMode == 0) {
                wc = w1[i];
                wa = w2[i];
                wcPrec = idxPrec;
                waPrec = idxPrec2;
            } else {
                wc = w2[i];
                wa = w1[i];
                wcPrec = idxPrec2;
                waPrec = idxPrec;
            }
            int r = interpolateBC7(endPts[c0 * 4], endPts[c1 * 4], wc, wcPrec);
            int g = interpolateBC7(endPts[c0 * 4 + 1], endPts[c1 * 4 + 1], wc, wcPrec);
            int b = interpolateBC7(endPts[c0 * 4 + 2], endPts[c1 * 4 + 2], wc, wcPrec);
            int a = interpolateBC7(endPts[c0 * 4 + 3], endPts[c1 * 4 + 3], wa, waPrec);
            switch (rotation) {
                case 1:
                    { int t = a; a = r; r = t; break; }
                case 2:
                    { int t = a; a = g; g = t; break; }
                case 3:
                    { int t = a; a = b; b = t; break; }
                default:
                    break;
            }
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return out;
    }


    private static int interpolateBC7(int c0, int c1, int w, int wPrec) {
        int[] table = wPrec == 2 ? BC7_WEIGHTS2 : (wPrec == 3 ? BC7_WEIGHTS3 : BC7_WEIGHTS4);
        return (c0 * (64 - table[w]) + c1 * table[w] + 32) >> 6;
    }

    private static int unquantizeBC7(int comp, int prec) {
        comp = comp << (8 - prec);
        return comp | (comp >> prec);
    }

    private static final int[] BC6H_WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] BC6H_WEIGHTS4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};
    private static final int[] BC7_WEIGHTS2 = {0, 21, 43, 64};
    private static final int[] BC7_WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] BC7_WEIGHTS4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    // BC6H 5-bit mode code -> mode index (DirectXTex ms_aModeToInfo); -1 = invalid/reserved
    private static final int[] BC6H_MODE_TO_INFO = {
            0, 1, 2, 10, -1, -1, 3, 11, -1, -1, 4, 12, -1, -1, 5, 13,
            -1, -1, 6, -1, -1, -1, 7, -1, -1, -1, 8, -1, -1, -1, 9, -1
    };

    // BC6H per-mode: partitions (0 = 1 subset, 1 = 2 subsets), transformed flag, index precision
    private static final int[] BC6H_PARTITIONS = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0};
    private static final boolean[] BC6H_TRANSFORMED = {true, true, true, true, true, true, true, true, true, false, false, true, true, true};
    private static final int[] BC6H_INDEX_PREC = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4};

    // BC6H RGBAPrec[mode][region][endpoint in pair] -> [r, g, b]
    private static final int[][][] BC6H_PREC = {
            {{10, 10, 10}, {5, 5, 5}, {5, 5, 5}, {5, 5, 5}},
            {{7, 7, 7}, {6, 6, 6}, {6, 6, 6}, {6, 6, 6}},
            {{11, 11, 11}, {5, 4, 4}, {5, 4, 4}, {5, 4, 4}},
            {{11, 11, 11}, {4, 5, 4}, {4, 5, 4}, {4, 5, 4}},
            {{11, 11, 11}, {4, 4, 5}, {4, 4, 5}, {4, 4, 5}},
            {{9, 9, 9}, {5, 5, 5}, {5, 5, 5}, {5, 5, 5}},
            {{8, 8, 8}, {6, 5, 5}, {6, 5, 5}, {6, 5, 5}},
            {{8, 8, 8}, {5, 6, 5}, {5, 6, 5}, {5, 6, 5}},
            {{8, 8, 8}, {5, 5, 6}, {5, 5, 6}, {5, 5, 6}},
            {{6, 6, 6}, {6, 6, 6}, {6, 6, 6}, {6, 6, 6}},
            {{10, 10, 10}, {10, 10, 10}, {0, 0, 0}, {0, 0, 0}},
            {{11, 11, 11}, {9, 9, 9}, {0, 0, 0}, {0, 0, 0}},
            {{12, 12, 12}, {8, 8, 8}, {0, 0, 0}, {0, 0, 0}},
            {{16, 16, 16}, {4, 4, 4}, {0, 0, 0}, {0, 0, 0}}
    };

    // BC6H per-mode 82-bit header layouts (DirectXTex ms_aDesc): 82 tokens per mode.
    // Token = field name (M/D/RW/RX/RY/RZ/GW/GX/GY/GZ/BW/BX/BY/BZ/NA) + bit index.
    private static final String[] BC6H_DESC_RAW = {
        "M0,M1,GY4,BY4,BZ4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,RW9,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GW9,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BW9,RX0,RX1,RX2,RX3,RX4,GZ4,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GX4,BZ0,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BX4,BZ1,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,RY4,BZ2,RZ0,RZ1,RZ2,RZ3,RZ4,BZ3,D0,D1,D2,D3,D4",
        "M0,M1,GY5,GZ4,GZ5,RW0,RW1,RW2,RW3,RW4,RW5,RW6,BZ0,BZ1,BY4,GW0,GW1,GW2,GW3,GW4,GW5,GW6,BY5,BZ2,GY4,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BZ3,BZ5,BZ4,RX0,RX1,RX2,RX3,RX4,RX5,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GX4,GX5,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BX4,BX5,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,RY4,RY5,RZ0,RZ1,RZ2,RZ3,RZ4,RZ5,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,RW9,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GW9,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BW9,RX0,RX1,RX2,RX3,RX4,RW10,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GW10,BZ0,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BW10,BZ1,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,RY4,BZ2,RZ0,RZ1,RZ2,RZ3,RZ4,BZ3,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,RW9,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GW9,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BW9,RX0,RX1,RX2,RX3,RW10,GZ4,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GX4,GW10,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BW10,BZ1,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,BZ0,BZ2,RZ0,RZ1,RZ2,RZ3,GY4,BZ3,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,RW9,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GW9,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BW9,RX0,RX1,RX2,RX3,RW10,BY4,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GW10,BZ0,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BX4,BW10,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,BZ1,BZ2,RZ0,RZ1,RZ2,RZ3,BZ4,BZ3,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,BY4,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GY4,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BZ4,RX0,RX1,RX2,RX3,RX4,GZ4,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GX4,BZ0,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BX4,BZ1,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,RY4,BZ2,RZ0,RZ1,RZ2,RZ3,RZ4,BZ3,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,GZ4,BY4,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,BZ2,GY4,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BZ3,BZ4,RX0,RX1,RX2,RX3,RX4,RX5,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GX4,BZ0,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BX4,BZ1,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,RY4,RY5,RZ0,RZ1,RZ2,RZ3,RZ4,RZ5,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,BZ0,BY4,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GY5,GY4,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,GZ5,BZ4,RX0,RX1,RX2,RX3,RX4,GZ4,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GX4,GX5,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BX4,BZ1,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,RY4,BZ2,RZ0,RZ1,RZ2,RZ3,RZ4,BZ3,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,BZ1,BY4,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,BY5,GY4,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BZ5,BZ4,RX0,RX1,RX2,RX3,RX4,GZ4,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GX4,BZ0,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BX4,BX5,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,RY4,BZ2,RZ0,RZ1,RZ2,RZ3,RZ4,BZ3,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,GZ4,BZ0,BZ1,BY4,GW0,GW1,GW2,GW3,GW4,GW5,GY5,BY5,BZ2,GY4,BW0,BW1,BW2,BW3,BW4,BW5,GZ5,BZ3,BZ5,BZ4,RX0,RX1,RX2,RX3,RX4,RX5,GY0,GY1,GY2,GY3,GX0,GX1,GX2,GX3,GX4,GX5,GZ0,GZ1,GZ2,GZ3,BX0,BX1,BX2,BX3,BX4,BX5,BY0,BY1,BY2,BY3,RY0,RY1,RY2,RY3,RY4,RY5,RZ0,RZ1,RZ2,RZ3,RZ4,RZ5,D0,D1,D2,D3,D4",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,RW9,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GW9,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BW9,RX0,RX1,RX2,RX3,RX4,RX5,RX6,RX7,RX8,RX9,GX0,GX1,GX2,GX3,GX4,GX5,GX6,GX7,GX8,GX9,BX0,BX1,BX2,BX3,BX4,BX5,BX6,BX7,BX8,BX9,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,RW9,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GW9,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BW9,RX0,RX1,RX2,RX3,RX4,RX5,RX6,RX7,RX8,RW10,GX0,GX1,GX2,GX3,GX4,GX5,GX6,GX7,GX8,GW10,BX0,BX1,BX2,BX3,BX4,BX5,BX6,BX7,BX8,BW10,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,RW9,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GW9,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BW9,RX0,RX1,RX2,RX3,RX4,RX5,RX6,RX7,RW11,RW10,GX0,GX1,GX2,GX3,GX4,GX5,GX6,GX7,GW11,GW10,BX0,BX1,BX2,BX3,BX4,BX5,BX6,BX7,BW11,BW10,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0",
        "M0,M1,M2,M3,M4,RW0,RW1,RW2,RW3,RW4,RW5,RW6,RW7,RW8,RW9,GW0,GW1,GW2,GW3,GW4,GW5,GW6,GW7,GW8,GW9,BW0,BW1,BW2,BW3,BW4,BW5,BW6,BW7,BW8,BW9,RX0,RX1,RX2,RX3,RW15,RW14,RW13,RW12,RW11,RW10,GX0,GX1,GX2,GX3,GW15,GW14,GW13,GW12,GW11,GW10,BX0,BX1,BX2,BX3,BW15,BW14,BW13,BW12,BW11,BW10,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0,NA0",
    };

    // BC6H/BC7 partition fixup pixel offsets (DirectXTex g_aFixUp): [partitions][shape][subset]
    private static final String[] BC6H_BC7_FIXUP_RAW = {
        "0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0|0,0,0",
        "0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,2,0|0,8,0|0,2,0|0,2,0|0,8,0|0,8,0|0,15,0|0,2,0|0,8,0|0,2,0|0,2,0|0,8,0|0,8,0|0,2,0|0,2,0|0,15,0|0,15,0|0,6,0|0,8,0|0,2,0|0,8,0|0,15,0|0,15,0|0,2,0|0,8,0|0,2,0|0,2,0|0,2,0|0,15,0|0,15,0|0,6,0|0,6,0|0,2,0|0,6,0|0,8,0|0,15,0|0,15,0|0,2,0|0,2,0|0,15,0|0,15,0|0,15,0|0,15,0|0,15,0|0,2,0|0,2,0|0,15,0",
        "0,3,15|0,3,8|0,15,8|0,15,3|0,8,15|0,3,15|0,15,3|0,15,8|0,8,15|0,8,15|0,6,15|0,6,15|0,6,15|0,5,15|0,3,15|0,3,8|0,3,15|0,3,8|0,8,15|0,15,3|0,3,15|0,3,8|0,6,15|0,10,8|0,5,3|0,8,15|0,8,6|0,6,10|0,8,15|0,5,15|0,15,10|0,15,8|0,8,15|0,15,3|0,3,15|0,5,10|0,6,10|0,10,8|0,8,9|0,15,10|0,15,6|0,3,15|0,15,8|0,5,15|0,15,3|0,15,6|0,15,6|0,15,8|0,3,15|0,15,3|0,5,15|0,5,15|0,5,15|0,8,15|0,5,15|0,10,15|0,5,15|0,10,15|0,8,15|0,13,15|0,15,3|0,12,15|0,3,15|0,3,8",
    };

    private static final String[][] BC6H_DESC = parseBC6HDesc();
    private static final int[][][] BC6H_BC7_FIXUP = parseFixupTable();

    private static String[][] parseBC6HDesc() {
        String[] raw = BC6H_DESC_RAW;
        String[][] desc = new String[raw.length][82];
        for (int m = 0; m < raw.length; m++) {
            String[] toks = raw[m].split(",");
            for (int i = 0; i < 82 && i < toks.length; i++) {
                desc[m][i] = toks[i];
            }
        }
        return desc;
    }

    private static int[][][] parseFixupTable() {
        String[] raw = BC6H_BC7_FIXUP_RAW;
        int[][][] table = new int[raw.length][64][3];
        for (int p = 0; p < raw.length; p++) {
            String[] rows = raw[p].split("\\|");
            for (int s = 0; s < 64 && s < rows.length; s++) {
                String[] vals = rows[s].split(",");
                for (int k = 0; k < 3 && k < vals.length; k++) {
                    table[p][s][k] = Integer.parseInt(vals[k].trim());
                }
            }
        }
        return table;
    }

    // BC7 per-mode parameters (DirectXTex ms_aInfo)
    private static final int[] BC7_PARTITIONS = {2, 1, 2, 1, 0, 0, 0, 1};
    private static final int[] BC7_PARTITION_BITS = {4, 6, 6, 6, 0, 0, 0, 6};
    private static final int[] BC7_PBITS = {6, 2, 0, 4, 0, 0, 2, 4};
    private static final int[] BC7_ROTATION_BITS = {0, 0, 0, 0, 2, 2, 0, 0};
    private static final int[] BC7_INDEX_MODE_BITS = {0, 0, 0, 0, 1, 0, 0, 0};
    private static final int[] BC7_INDEX_PREC = {3, 3, 2, 2, 2, 2, 4, 2};
    private static final int[] BC7_INDEX_PREC2 = {0, 0, 0, 0, 3, 2, 0, 0};
    private static final int[][] BC7_RGBA_PREC = {
            {4, 4, 4, 0}, {6, 6, 6, 0}, {5, 5, 5, 0}, {7, 7, 7, 0},
            {5, 5, 5, 6}, {7, 7, 7, 8}, {7, 7, 7, 7}, {5, 5, 5, 5}
    };
    private static final int[][] BC7_RGBA_PREC_WITH_P = {
            {5, 5, 5, 0}, {7, 7, 7, 0}, {5, 5, 5, 0}, {8, 8, 8, 0},
            {5, 5, 5, 6}, {7, 7, 7, 8}, {8, 8, 8, 8}, {6, 6, 6, 6}
    };
    private static final String BC7_P2_RAW =
        "0,0,1,1,0,0,1,1,0,0,1,1,0,0,1,1|" +
        "0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1|" +
        "0,1,1,1,0,1,1,1,0,1,1,1,0,1,1,1|" +
        "0,0,0,1,0,0,1,1,0,0,1,1,0,1,1,1|" +
        "0,0,0,0,0,0,0,1,0,0,0,1,0,0,1,1|" +
        "0,0,1,1,0,1,1,1,0,1,1,1,1,1,1,1|" +
        "0,0,0,1,0,0,1,1,0,1,1,1,1,1,1,1|" +
        "0,0,0,0,0,0,0,1,0,0,1,1,0,1,1,1|" +
        "0,0,0,0,0,0,0,0,0,0,0,1,0,0,1,1|" +
        "0,0,1,1,0,1,1,1,1,1,1,1,1,1,1,1|" +
        "0,0,0,0,0,0,0,1,0,1,1,1,1,1,1,1|" +
        "0,0,0,0,0,0,0,0,0,0,0,1,0,1,1,1|" +
        "0,0,0,1,0,1,1,1,1,1,1,1,1,1,1,1|" +
        "0,0,0,0,0,0,0,0,1,1,1,1,1,1,1,1|" +
        "0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1|" +
        "0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,1|" +
        "0,0,0,0,1,0,0,0,1,1,1,0,1,1,1,1|" +
        "0,1,1,1,0,0,0,1,0,0,0,0,0,0,0,0|" +
        "0,0,0,0,0,0,0,0,1,0,0,0,1,1,1,0|" +
        "0,1,1,1,0,0,1,1,0,0,0,1,0,0,0,0|" +
        "0,0,1,1,0,0,0,1,0,0,0,0,0,0,0,0|" +
        "0,0,0,0,1,0,0,0,1,1,0,0,1,1,1,0|" +
        "0,0,0,0,0,0,0,0,1,0,0,0,1,1,0,0|" +
        "0,1,1,1,0,0,1,1,0,0,1,1,0,0,0,1|" +
        "0,0,1,1,0,0,0,1,0,0,0,1,0,0,0,0|" +
        "0,0,0,0,1,0,0,0,1,0,0,0,1,1,0,0|" +
        "0,1,1,0,0,1,1,0,0,1,1,0,0,1,1,0|" +
        "0,0,1,1,0,1,1,0,0,1,1,0,1,1,0,0|" +
        "0,0,0,1,0,1,1,1,1,1,1,0,1,0,0,0|" +
        "0,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0|" +
        "0,1,1,1,0,0,0,1,1,0,0,0,1,1,1,0|" +
        "0,0,1,1,1,0,0,1,1,0,0,1,1,1,0,0|" +
        "0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1|" +
        "0,0,0,0,1,1,1,1,0,0,0,0,1,1,1,1|" +
        "0,1,0,1,1,0,1,0,0,1,0,1,1,0,1,0|" +
        "0,0,1,1,0,0,1,1,1,1,0,0,1,1,0,0|" +
        "0,0,1,1,1,1,0,0,0,0,1,1,1,1,0,0|" +
        "0,1,0,1,0,1,0,1,1,0,1,0,1,0,1,0|" +
        "0,1,1,0,1,0,0,1,0,1,1,0,1,0,0,1|" +
        "0,1,0,1,1,0,1,0,1,0,1,0,0,1,0,1|" +
        "0,1,1,1,0,0,1,1,1,1,0,0,1,1,1,0|" +
        "0,0,0,1,0,0,1,1,1,1,0,0,1,0,0,0|" +
        "0,0,1,1,0,0,1,0,0,1,0,0,1,1,0,0|" +
        "0,0,1,1,1,0,1,1,1,1,0,1,1,1,0,0|" +
        "0,1,1,0,1,0,0,1,1,0,0,1,0,1,1,0|" +
        "0,0,1,1,1,1,0,0,1,1,0,0,0,0,1,1|" +
        "0,1,1,0,0,1,1,0,1,0,0,1,1,0,0,1|" +
        "0,0,0,0,0,1,1,0,0,1,1,0,0,0,0,0|" +
        "0,1,0,0,1,1,1,0,0,1,0,0,0,0,0,0|" +
        "0,0,1,0,0,1,1,1,0,0,1,0,0,0,0,0|" +
        "0,0,0,0,0,0,1,0,0,1,1,1,0,0,1,0|" +
        "0,0,0,0,0,1,0,0,1,1,1,0,0,1,0,0|" +
        "0,1,1,0,1,1,0,0,1,0,0,1,0,0,1,1|" +
        "0,0,1,1,0,1,1,0,1,1,0,0,1,0,0,1|" +
        "0,1,1,0,0,0,1,1,1,0,0,1,1,1,0,0|" +
        "0,0,1,1,1,0,0,1,1,1,0,0,0,1,1,0|" +
        "0,1,1,0,1,1,0,0,1,1,0,0,1,0,0,1|" +
        "0,1,1,0,0,0,1,1,0,0,1,1,1,0,0,1|" +
        "0,1,1,1,1,1,1,0,1,0,0,0,0,0,0,1|" +
        "0,0,0,1,1,0,0,0,1,1,1,0,0,1,1,1|" +
        "0,0,0,0,1,1,1,1,0,0,1,1,0,0,1,1|" +
        "0,0,1,1,0,0,1,1,1,1,1,1,0,0,0,0|" +
        "0,0,1,0,0,0,1,0,1,1,1,0,1,1,1,0|" +
        "0,1,0,0,0,1,0,0,0,1,1,1,0,1,1,1";

    private static final String BC7_P3_RAW =
        "0,0,1,1,0,0,1,1,0,2,2,1,2,2,2,2|" +
        "0,0,0,1,0,0,1,1,2,2,1,1,2,2,2,1|" +
        "0,0,0,0,2,0,0,1,2,2,1,1,2,2,1,1|" +
        "0,2,2,2,0,0,2,2,0,0,1,1,0,1,1,1|" +
        "0,0,0,0,0,0,0,0,1,1,2,2,1,1,2,2|" +
        "0,0,1,1,0,0,1,1,0,0,2,2,0,0,2,2|" +
        "0,0,2,2,0,0,2,2,1,1,1,1,1,1,1,1|" +
        "0,0,1,1,0,0,1,1,2,2,1,1,2,2,1,1|" +
        "0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2|" +
        "0,0,0,0,1,1,1,1,1,1,1,1,2,2,2,2|" +
        "0,0,0,0,1,1,1,1,2,2,2,2,2,2,2,2|" +
        "0,0,1,2,0,0,1,2,0,0,1,2,0,0,1,2|" +
        "0,1,1,2,0,1,1,2,0,1,1,2,0,1,1,2|" +
        "0,1,2,2,0,1,2,2,0,1,2,2,0,1,2,2|" +
        "0,0,1,1,0,1,1,2,1,1,2,2,1,2,2,2|" +
        "0,0,1,1,2,0,0,1,2,2,0,0,2,2,2,0|" +
        "0,0,0,1,0,0,1,1,0,1,1,2,1,1,2,2|" +
        "0,1,1,1,0,0,1,1,2,0,0,1,2,2,0,0|" +
        "0,0,0,0,1,1,2,2,1,1,2,2,1,1,2,2|" +
        "0,0,2,2,0,0,2,2,0,0,2,2,1,1,1,1|" +
        "0,1,1,1,0,1,1,1,0,2,2,2,0,2,2,2|" +
        "0,0,0,1,0,0,0,1,2,2,2,1,2,2,2,1|" +
        "0,0,0,0,0,0,1,1,0,1,2,2,0,1,2,2|" +
        "0,0,0,0,1,1,0,0,2,2,1,0,2,2,1,0|" +
        "0,1,2,2,0,1,2,2,0,0,1,1,0,0,0,0|" +
        "0,0,1,2,0,0,1,2,1,1,2,2,2,2,2,2|" +
        "0,1,1,0,1,2,2,1,1,2,2,1,0,1,1,0|" +
        "0,0,0,0,0,1,1,0,1,2,2,1,1,2,2,1|" +
        "0,0,2,2,1,1,0,2,1,1,0,2,0,0,2,2|" +
        "0,1,1,0,0,1,1,0,2,0,0,2,2,2,2,2|" +
        "0,0,1,1,0,1,2,2,0,1,2,2,0,0,1,1|" +
        "0,0,0,0,2,0,0,0,2,2,1,1,2,2,2,1|" +
        "0,0,0,0,0,0,0,2,1,1,2,2,1,2,2,2|" +
        "0,2,2,2,0,0,2,2,0,0,1,2,0,0,1,1|" +
        "0,0,1,1,0,0,1,2,0,0,2,2,0,2,2,2|" +
        "0,1,2,0,0,1,2,0,0,1,2,0,0,1,2,0|" +
        "0,0,0,0,1,1,1,1,2,2,2,2,0,0,0,0|" +
        "0,1,2,0,1,2,0,1,2,0,1,2,0,1,2,0|" +
        "0,1,2,0,2,0,1,2,1,2,0,1,0,1,2,0|" +
        "0,0,1,1,2,2,0,0,1,1,2,2,0,0,1,1|" +
        "0,0,1,1,1,1,2,2,2,2,0,0,0,0,1,1|" +
        "0,1,0,1,0,1,0,1,2,2,2,2,2,2,2,2|" +
        "0,0,0,0,0,0,0,0,2,1,2,1,2,1,2,1|" +
        "0,0,2,2,1,1,2,2,0,0,2,2,1,1,2,2|" +
        "0,0,2,2,0,0,1,1,0,0,2,2,0,0,1,1|" +
        "0,2,2,0,1,2,2,1,0,2,2,0,1,2,2,1|" +
        "0,1,0,1,2,2,2,2,2,2,2,2,0,1,0,1|" +
        "0,0,0,0,2,1,2,1,2,1,2,1,2,1,2,1|" +
        "0,1,0,1,0,1,0,1,0,1,0,1,2,2,2,2|" +
        "0,2,2,2,0,1,1,1,0,2,2,2,0,1,1,1|" +
        "0,0,0,2,1,1,1,2,0,0,0,2,1,1,1,2|" +
        "0,0,0,0,2,1,1,2,2,1,1,2,2,1,1,2|" +
        "0,2,2,2,0,1,1,1,0,1,1,1,0,2,2,2|" +
        "0,0,0,2,1,1,1,2,1,1,1,2,0,0,0,2|" +
        "0,1,1,0,0,1,1,0,0,1,1,0,2,2,2,2|" +
        "0,0,0,0,0,0,0,0,2,1,1,2,2,1,1,2|" +
        "0,1,1,0,0,1,1,0,2,2,2,2,2,2,2,2|" +
        "0,0,2,2,0,0,1,1,0,0,1,1,0,0,2,2|" +
        "0,0,2,2,1,1,2,2,1,1,2,2,0,0,2,2|" +
        "0,0,0,0,0,0,0,0,0,0,0,0,2,1,1,2|" +
        "0,0,0,2,0,0,0,1,0,0,0,2,0,0,0,1|" +
        "0,2,2,2,1,2,2,2,0,2,2,2,1,2,2,2|" +
        "0,1,0,1,2,2,2,2,2,2,2,2,2,2,2,2|" +
        "0,1,1,1,2,0,1,1,2,2,0,1,2,2,2,0";

    private static final int[][] BC7_P2_TABLE = parseBC7PartitionTable(BC7_P2_RAW);
    private static final int[][] BC7_P3_TABLE = parseBC7PartitionTable(BC7_P3_RAW);

    private static int[][] parseBC7PartitionTable(String raw) {
        int[][] table = new int[64][16];
        String[] rows = raw.split("\\|");
        for (int i = 0; i < 64 && i < rows.length; i++) {
            String[] vals = rows[i].split(",");
            for (int j = 0; j < 16 && j < vals.length; j++) {
                table[i][j] = Integer.parseInt(vals[j].trim());
            }
        }
        return table;
    }


    // Read a range of bits from a byte array at the given bit offset
    private static long readBits(byte[] data, int startByte, long bitOffset, int numBits) {
        long result = 0;
        long absBitOff = startByte * 8L + bitOffset;
        for (int b = 0; b < numBits; b++) {
            long byteOff = (absBitOff + b) >> 3;
            int bitInByte = (int)((absBitOff + b) & 7);
            if (byteOff >= data.length) break;
            int bit = (data[(int)byteOff] >> bitInByte) & 1;
            result |= (long)bit << b;
        }
        return result;
    }

    private static int signExtend(int val, int bits) {
        int shift = 32 - bits;
        return (val << shift) >> shift;
    }

    private static int clampByte(int val) {
        return Math.max(0, Math.min(255, val));
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
                    // Transparent (alpha 0) for the 1-bit-alpha case. rgb565to888 forces A=0xFF,
                    // so build the int explicitly with alpha 0.
                    colors[3] = (0) | (rgb565to888(c0) & 0x00FFFFFF);
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

    private static void decodeDXT1OneBitAlpha(byte[] data, int width, int height, int[] pixels) {
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
                    // 1-bit alpha: color index 3 is fully transparent (alpha 0).
                    colors[3] = (0) | (rgb565to888(c0) & 0x00FFFFFF);
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
                    // DXT5 spec: when a0 <= a1, 6 interpolated steps use divisor 5:
                    //   value = (a0*(5-i) + a1*(1+i)) / 5   for i in 0..5
                    // with explicit 0 and 255 endpoints at indices 6 and 7.
                    for (int i = 0; i < 6; i++) {
                        alphas[2 + i] = ((a0 * (5 - i) + a1 * (1 + i)) / 5) & 0xFF;
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

    private static int lerpColorDXT(int c0, int c1, int t) {
        int r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
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

    private static byte[] decompressZlib(byte[] compressedData) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressedData);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(compressedData.length * 2);
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) break;
                baos.write(buffer, 0, count);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    public static class VtfImageData {
        public int width;
        public int height;
        public int format;
        public BufferedImage image;
        public int frameCount = 1;
        public List<BufferedImage> frames;
        public boolean isCubemap;
        public BufferedImage[] cubemapFaces;

        public BufferedImage getFrame(int index) {
            if (frames != null && index >= 0 && index < frames.size()) {
                return frames.get(index);
            }
            return image;
        }

        public int getFrameCount() {
            return (frames != null) ? frames.size() : 1;
        }
    }
}
