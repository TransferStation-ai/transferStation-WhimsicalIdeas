// VtfParser.java - jsonContract
package transferstation.transferstation_whimsicalideas.client.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.Inflater;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.ColorUtils;

public class VtfParser {

    private static final Logger LOGGER = LogUtils.getLogger();
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

        boolean isCubemap = (flags & 0x20000000) != 0;

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
            int faceDataSize = dataSize;
            for (int face = 1; face < 6; face++) {
                if (buf.remaining() < faceDataSize) break;
                byte[] faceData = new byte[faceDataSize];
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
        if (frames > 1) {
            int frameSize = dataSize;
            if (imageFormat == FORMAT_P8) frameSize = dataSize; // palette already read
            for (int f = 1; f < frames; f++) {
                // Each frame stores its own mip pyramid (mipmapCount-1 .. 1) before
                // the next frame's full-res block. Skip that frame's mip chain so
                // we land on the correct full-res data for frame f.
                int frameMipSkip = 0;
                for (int i = mipmapCount - 1; i > 0; i--) {
                    int mipWidth = Math.max(1, width >> i);
                    int mipHeight = Math.max(1, height >> i);
                    int mipSize = computeImageDataSize(mipWidth, mipHeight, imageFormat);
                    if (mipSize <= 0) break;
                    frameMipSkip += mipSize;
                }
                if (frameMipSkip > 0 && buf.position() + frameMipSkip <= buf.limit()) {
                    buf.position(buf.position() + frameMipSkip);
                }
                if (buf.remaining() < frameSize) break;
                byte[] frameData = new byte[dataSize];
                buf.get(frameData);
                if (frameData.length > 1 && frameData[0] == 0x78) {
                    byte[] decompressed = decompressZlib(frameData);
                    if (decompressed != null && decompressed.length > 0) {
                        frameData = decompressed;
                    }
                }
                BufferedImage frame = decodeToImage(frameData, width, height, imageFormat, p8Palette);
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
        switch (format) {
            case FORMAT_DXT1:
            case FORMAT_DXT1_ONEBITALPHA:
            case FORMAT_ATI1N:
                return 8;
            case FORMAT_DXT3:
            case FORMAT_DXT5:
            case FORMAT_ATI2N:
            case FORMAT_BC6H:
            case FORMAT_BC7:
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
            case FORMAT_RG1616F:
            case FORMAT_RG32F:
                return 8;
            case FORMAT_RGB161616F:
                return 6;
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

    private static BufferedImage decodeToImage(byte[] data, int width, int height, int format) {
        return decodeToImage(data, width, height, format, null);
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
            case FORMAT_RGBA16161616F:
                decodeRGBA16F(data, width, height, pixels);
                break;
            case FORMAT_RGBA16F:
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

    private static void decodeRGBA16161616F(byte[] data, int width, int height, int[] pixels) {
        int idx = 0;
        for (int i = 0; i < width * height; i++) {
            if (idx + 15 < data.length) {
                ByteBuffer bb = ByteBuffer.wrap(data, idx, 16).order(ByteOrder.LITTLE_ENDIAN);
                float r = bb.getFloat();
                float g = bb.getFloat();
                float b = bb.getFloat();
                float a = bb.getFloat();
                int ri = Math.min(255, Math.max(0, (int)(r * 255.0f)));
                int gi = Math.min(255, Math.max(0, (int)(g * 255.0f)));
                int bi = Math.min(255, Math.max(0, (int)(b * 255.0f)));
                int ai = Math.min(255, Math.max(0, (int)(a * 255.0f)));
                pixels[i] = (ai << 24) | (ri << 16) | (gi << 8) | bi;
                idx += 16;
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
                pixels[i] = 0xFF000000 | (ri << 16) | (gi << 8) | 0;
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
                pixels[i] = 0xFF000000 | (ri << 16) | (gi << 8) | 0;
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
     * Decode BC6H (BPTC float format) - handles modes 0-3 (covers ~95% of usage)
     */
    private static void decodeBC6H(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int blockOff = (by * blockW + bx) * 16;
                if (blockOff + 16 > data.length) continue;

                int modeInfo = data[blockOff] & 0x1F;
                int mode;
                if ((modeInfo & 1) == 1 && (modeInfo & 2) == 0) mode = 1;
                else if ((modeInfo & 1) == 0 && (modeInfo & 2) == 0) mode = 2;
                else if ((modeInfo & 1) == 1 && (modeInfo & 2) == 1 && (modeInfo & 16) != 0) mode = 0;
                else if ((modeInfo & 1) == 1 && (modeInfo & 2) == 1 && (modeInfo & 16) == 0) mode = 3;
                else if ((modeInfo & 0x1F) == 0x1E) mode = 4;
                else if ((modeInfo & 0x1F) == 0x1C) mode = 5;
                else if ((modeInfo & 0x1F) == 0x18) mode = 6;
                else if ((modeInfo & 0x1F) == 0x10) mode = 7;
                else if ((modeInfo & 0x1F) == 0x0E) mode = 8;
                else if ((modeInfo & 0x1F) == 0x0C) mode = 9;
                else if ((modeInfo & 0x1F) == 0x08) mode = 10;
                else if ((modeInfo & 0x1F) == 0x06) mode = 11;
                else if ((modeInfo & 0x1F) == 0x04) mode = 12;
                else if ((modeInfo & 0x1F) == 0x02) mode = 13;
                else mode = -1;

                if (mode < 0) {
                    for (int py = 0; py < 4; py++) {
                        for (int px = 0; px < 4; px++) {
                            int pxAbs = bx * 4 + px, pyAbs = by * 4 + py;
                            if (pxAbs < width && pyAbs < height)
                                pixels[pyAbs * width + pxAbs] = 0xFFFF00FF;
                        }
                    }
                    continue;
                }

                int r0 = 0, g0 = 0, b0 = 0, r1 = 0, g1 = 0, b1 = 0;
                int transformType = 0;
                int idxBits = 4;
                int fixupBit = 0;

                switch (mode) {
                    case 0:
                        r0 = (int)readBits(data, blockOff, 2, 10);
                        g0 = (int)readBits(data, blockOff, 12, 10);
                        b0 = (int)readBits(data, blockOff, 22, 10);
                        r1 = (int)readBits(data, blockOff, 34, 10);
                        g1 = (int)readBits(data, blockOff, 44, 10);
                        b1 = (int)readBits(data, blockOff, 54, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 4; fixupBit = 1;
                        break;
                    case 1:
                        r0 = (int)readBits(data, blockOff, 1, 10);
                        g0 = (int)readBits(data, blockOff, 11, 10);
                        b0 = (int)readBits(data, blockOff, 21, 10);
                        r1 = (int)readBits(data, blockOff, 33, 10);
                        g1 = (int)readBits(data, blockOff, 43, 10);
                        b1 = (int)readBits(data, blockOff, 53, 10);
                        transformType = 0; idxBits = 4; fixupBit = 0;
                        break;
                    case 2:
                        {
                            long p = readBits(data, blockOff, 2, 48);
                            int r0s = (int)(p & 0x7F);
                            int g0s = (int)((p >> 7) & 0x3F);
                            int b0s = (int)((p >> 13) & 0x7F);
                            int r1s = (int)((p >> 20) & 0x7F);
                            int g1s = (int)((p >> 27) & 0x3F);
                            int b1s = (int)((p >> 33) & 0x7F);
                            r0 = (r0s << 3) | (r0s >> 4);
                            g0 = (g0s << 4) | g0s;
                            b0 = (b0s << 3) | (b0s >> 4);
                            r1 = (r1s << 3) | (r1s >> 4);
                            g1 = (g1s << 4) | g1s;
                            b1 = (b1s << 3) | (b1s >> 4);
                            r1 = (r0 + signExtend(r1, 7) * 2) & 0x3FF;
                            g1 = (g0 + signExtend(g1, 6) * 2) & 0x3FF;
                            b1 = (b0 + signExtend(b1, 7) * 2) & 0x3FF;
                            transformType = 1; idxBits = 4; fixupBit = 1;
                        }
                        break;
                    case 3:
                        r0 = (int)readBits(data, blockOff, 2, 10);
                        g0 = (int)readBits(data, blockOff, 12, 10);
                        b0 = (int)readBits(data, blockOff, 22, 10);
                        r1 = (int)readBits(data, blockOff, 32, 10);
                        g1 = (int)readBits(data, blockOff, 42, 10);
                        b1 = (int)readBits(data, blockOff, 52, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 4; fixupBit = 0;
                        break;
                    case 4:
                        r0 = (int)readBits(data, blockOff, 5, 10);
                        g0 = (int)readBits(data, blockOff, 15, 10);
                        b0 = (int)readBits(data, blockOff, 25, 10);
                        r1 = (int)readBits(data, blockOff, 37, 10);
                        g1 = (int)readBits(data, blockOff, 47, 10);
                        b1 = (int)readBits(data, blockOff, 57, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 2; fixupBit = 0;
                        break;
                    case 5:
                        r0 = (int)readBits(data, blockOff, 5, 10);
                        g0 = (int)readBits(data, blockOff, 15, 10);
                        b0 = (int)readBits(data, blockOff, 25, 10);
                        r1 = (int)readBits(data, blockOff, 39, 10);
                        g1 = (int)readBits(data, blockOff, 49, 10);
                        b1 = (int)readBits(data, blockOff, 59, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 2; fixupBit = 0;
                        break;
                    case 6:
                        r0 = (int)readBits(data, blockOff, 7, 10);
                        g0 = (int)readBits(data, blockOff, 17, 10);
                        b0 = (int)readBits(data, blockOff, 27, 10);
                        r1 = (int)readBits(data, blockOff, 39, 10);
                        g1 = (int)readBits(data, blockOff, 49, 10);
                        b1 = (int)readBits(data, blockOff, 59, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 2; fixupBit = 0;
                        break;
                    case 7:
                        r0 = (int)readBits(data, blockOff, 7, 10);
                        g0 = (int)readBits(data, blockOff, 17, 10);
                        b0 = (int)readBits(data, blockOff, 27, 10);
                        r1 = (int)readBits(data, blockOff, 41, 10);
                        g1 = (int)readBits(data, blockOff, 51, 10);
                        b1 = (int)readBits(data, blockOff, 61, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 2; fixupBit = 0;
                        break;
                    case 8:
                        r0 = (int)readBits(data, blockOff, 4, 10);
                        g0 = (int)readBits(data, blockOff, 14, 10);
                        b0 = (int)readBits(data, blockOff, 24, 10);
                        r1 = (int)readBits(data, blockOff, 36, 10);
                        g1 = (int)readBits(data, blockOff, 46, 10);
                        b1 = (int)readBits(data, blockOff, 56, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 3; fixupBit = 0;
                        break;
                    case 9:
                        r0 = (int)readBits(data, blockOff, 4, 10);
                        g0 = (int)readBits(data, blockOff, 14, 10);
                        b0 = (int)readBits(data, blockOff, 24, 10);
                        r1 = (int)readBits(data, blockOff, 36, 10);
                        g1 = (int)readBits(data, blockOff, 46, 10);
                        b1 = (int)readBits(data, blockOff, 56, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 3; fixupBit = 0;
                        break;
                    case 10:
                        r0 = (int)readBits(data, blockOff, 4, 10);
                        g0 = (int)readBits(data, blockOff, 14, 10);
                        b0 = (int)readBits(data, blockOff, 24, 10);
                        r1 = (int)readBits(data, blockOff, 36, 10);
                        g1 = (int)readBits(data, blockOff, 46, 10);
                        b1 = (int)readBits(data, blockOff, 56, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 3; fixupBit = 0;
                        break;
                    case 11:
                        r0 = (int)readBits(data, blockOff, 4, 10);
                        g0 = (int)readBits(data, blockOff, 14, 10);
                        b0 = (int)readBits(data, blockOff, 24, 10);
                        r1 = (int)readBits(data, blockOff, 38, 10);
                        g1 = (int)readBits(data, blockOff, 48, 10);
                        b1 = (int)readBits(data, blockOff, 58, 10);
                        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
                        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
                        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
                        transformType = 1; idxBits = 3; fixupBit = 0;
                        break;
                    case 12:
                        {
                            long p = readBits(data, blockOff, 4, 48);
                            r0 = (int)(p & 0x7F);
                            g0 = (int)((p >> 7) & 0x3F);
                            b0 = (int)((p >> 13) & 0x7F);
                            r1 = (int)((p >> 20) & 0x7F);
                            g1 = (int)((p >> 27) & 0x3F);
                            b1 = (int)((p >> 33) & 0x7F);
                            r0 = (r0 << 3) | (r0 >> 4);
                            g0 = (g0 << 4) | g0;
                            b0 = (b0 << 3) | (b0 >> 4);
                            r1 = (r1 << 3) | (r1 >> 4);
                            g1 = (g1 << 4) | g1;
                            b1 = (b1 << 3) | (b1 >> 4);
                            r1 = (r0 + signExtend(r1, 7)) & 0x3FF;
                            g1 = (g0 + signExtend(g1, 6)) & 0x3FF;
                            b1 = (b0 + signExtend(b1, 7)) & 0x3FF;
                            transformType = 1; idxBits = 3; fixupBit = 0;
                        }
                        break;
                    case 13:
                        {
                            long p = readBits(data, blockOff, 4, 48);
                            r0 = (int)(p & 0x7F);
                            g0 = (int)((p >> 7) & 0x3F);
                            b0 = (int)((p >> 13) & 0x7F);
                            r1 = (int)((p >> 20) & 0x7F);
                            g1 = (int)((p >> 27) & 0x3F);
                            b1 = (int)((p >> 33) & 0x7F);
                            r0 = (r0 << 3) | (r0 >> 4);
                            g0 = (g0 << 4) | g0;
                            b0 = (b0 << 3) | (b0 >> 4);
                            r1 = (r1 << 3) | (r1 >> 4);
                            g1 = (g1 << 4) | g1;
                            b1 = (b1 << 3) | (b1 >> 4);
                            r1 = (r0 + signExtend(r1, 7)) & 0x3FF;
                            g1 = (g0 + signExtend(g1, 6)) & 0x3FF;
                            b1 = (b0 + signExtend(b1, 7)) & 0x3FF;
                            transformType = 1; idxBits = 3; fixupBit = 0;
                        }
                        break;
                }

                int numIdx = 16;
                int maxWeight = (1 << idxBits) - 1;
                long indexData = 0;
                int idxBitOff = mode <= 3 ? (transformType == 0 ? 65 : 66) : mode * 8 + 16;
                int idxNumBits = numIdx * idxBits;
                indexData = readBits(data, blockOff, idxBitOff, idxNumBits);

                for (int py = 0; py < 4; py++) {
                    for (int px = 0; px < 4; px++) {
                        int pixelIdx = py * 4 + px;
                        int idxShift = pixelIdx * idxBits;
                        int idx = (int)((indexData >> idxShift) & maxWeight);
                        int weight = idx;
                        int r = (r0 * (maxWeight - weight) + r1 * weight) / maxWeight;
                        int g = (g0 * (maxWeight - weight) + g1 * weight) / maxWeight;
                        int b = (b0 * (maxWeight - weight) + b1 * weight) / maxWeight;
                        int pxAbs = bx * 4 + px, pyAbs = by * 4 + py;
                        if (pxAbs < width && pyAbs < height) {
                            int ri = clampByte(r >> 2), gi = clampByte(g >> 2), bi = clampByte(b >> 2);
                            pixels[pyAbs * width + pxAbs] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
                        }
                    }
                }
            }
        }
    }

    /**
     * Decode BC7 (BPTC RGBA format) - fixed for all 8 modes
     */
    private static void decodeBC7(byte[] data, int width, int height, int[] pixels) {
        int blockW = (width + 3) / 4;
        int blockH = (height + 3) / 4;
        for (int by = 0; by < blockH; by++) {
            for (int bx = 0; bx < blockW; bx++) {
                int off = (by * blockW + bx) * 16;
                if (off + 16 > data.length) continue;

                int firstByte = data[off] & 0xFF;
                int mode;
                for (mode = 0; mode < 8; mode++) {
                    if ((firstByte & (1 << mode)) != 0) break;
                }
                if (mode >= 8) mode = 0;

                // Parse mode-specific parameters
                int numSubsets = 1, numPartitionBits = 0, numPBits = 0;
                int[] numIndexBits = {3, 3};
                int rotation = 0, idxMode = 0;

                switch (mode) {
                    case 0: numSubsets = 3; numPartitionBits = 4; numIndexBits[0] = 3; numIndexBits[1] = 3; numPBits = 1; break;
                    case 1: numSubsets = 2; numPartitionBits = 6; numIndexBits[0] = 3; numIndexBits[1] = 3; numPBits = 2; break;
                    case 2: numSubsets = 3; numPartitionBits = 6; numIndexBits[0] = 2; numIndexBits[1] = 2; numPBits = 1; break;
                    case 3: numSubsets = 2; numPartitionBits = 6; numIndexBits[0] = 2; numIndexBits[1] = 2; numPBits = 1; break;
                    case 4: numSubsets = 1; numPartitionBits = 0; numIndexBits[0] = 2; numIndexBits[1] = 3; rotation = (firstByte >> 4) & 3; idxMode = (firstByte >> 6) & 1; break;
                    case 5: numSubsets = 1; numPartitionBits = 0; numIndexBits[0] = 2; numIndexBits[1] = 3; rotation = (firstByte >> 5) & 3; idxMode = (firstByte >> 7) & 1; break;
                    case 6: numSubsets = 1; numPartitionBits = 0; numIndexBits[0] = 4; numIndexBits[1] = 4; break;
                    case 7: numSubsets = 2; numPartitionBits = 6; numIndexBits[0] = 2; numIndexBits[1] = 2; numPBits = 1; break;
                }

                int partition = 0;
                if (numPartitionBits > 0) {
                    partition = (firstByte >> (mode + 1)) & ((1 << numPartitionBits) - 1);
                }

                // Endpoints: [subset0_r0,g0,b0,a0, r1,g1,b1,a1, subset1_r0,g0,b0,a0, r1,g1,b1,a1, ...]
                int[] endpoints = new int[numSubsets * 8];
                computeBC7EndpointsFixed(data, off, mode, numSubsets, numPBits, endpoints);

                // Read entire block bits
                long blockBitsLow = readBits(data, off, 0, 64);
                long blockBitsHigh = readBits(data, off, 64, 64);

                // Calculate bit offset to index data (after all endpoint components + P-bits)
                int bitOff = mode + 1 + numPartitionBits;
                int compOff = bitOff;
                // For modes 4,5: skip rotation and idxMode bits (already part of mode bits area for other modes)
                if (mode == 4) bitOff += 2;
                if (mode == 5) bitOff += 2;
                compOff = bitOff;
                switch (mode) {
                    case 0: compOff = bitOff + 3*2*(4+4+4+0) + numSubsets*numPBits; break;
                    case 1: compOff = bitOff + 2*2*(6+6+6+0) + numSubsets*numPBits; break;
                    case 2: compOff = bitOff + 3*2*(5+5+5+0) + numSubsets*numPBits; break;
                    case 3: compOff = bitOff + 2*2*(7+7+7+0) + numSubsets*numPBits; break;
                    case 4: compOff = bitOff + 1*2*(5+5+5+6); break;
                    case 5: compOff = bitOff + 1*2*(7+7+7+8); break;
                    case 6: compOff = bitOff + 1*2*(7+7+7+7); break;
                    case 7: compOff = bitOff + 2*2*(5+5+5+0) + numSubsets*numPBits; break;
                }

                for (int py = 0; py < 4; py++) {
                    for (int px = 0; px < 4; px++) {
                        int subset = computeBC7Partition(partition, numSubsets, px, py);
                        int epBase = subset * 8;
                        int r0 = endpoints[epBase], g0 = endpoints[epBase + 1], b0 = endpoints[epBase + 2], a0 = endpoints[epBase + 3];
                        int r1 = endpoints[epBase + 4], g1 = endpoints[epBase + 5], b1 = endpoints[epBase + 6], a1 = endpoints[epBase + 7];

                        // For modes 4,5: pixel group determines index bit width
                        int bitsPerIdx = numIndexBits[subset];
                        if (mode == 4 || mode == 5) {
                            int pixelIdx = py * 4 + px;
                            boolean firstGroup = (idxMode == 0) ? (pixelIdx < 8) : (pixelIdx >= 8);
                            bitsPerIdx = firstGroup ? numIndexBits[0] : numIndexBits[1];
                        }

                        int idx = 0;
                        for (int b = 0; b < bitsPerIdx; b++) {
                            long bit = readBits(data, off, compOff + b, 1);
                            idx |= (int)bit << b;
                        }
                        compOff += bitsPerIdx;

                        int maxIdx = (1 << bitsPerIdx) - 1;
                        int weight = idx;

                        int r = (r0 * (maxIdx - weight) + r1 * weight) / maxIdx;
                        int g = (g0 * (maxIdx - weight) + g1 * weight) / maxIdx;
                        int b = (b0 * (maxIdx - weight) + b1 * weight) / maxIdx;
                        int a = (a0 * (maxIdx - weight) + a1 * weight) / maxIdx;

                        // Apply rotation for modes 4 and 5
                        if (mode == 4 || mode == 5) {
                            int tempA = a;
                            switch (rotation) {
                                case 1: a = r; r = tempA; break;
                                case 2: a = g; g = tempA; break;
                                case 3: a = b; b = tempA; break;
                            }
                        }

                        int pxAbs = bx * 4 + px, pyAbs = by * 4 + py;
                        if (pxAbs < width && pyAbs < height) {
                            pixels[pyAbs * width + pxAbs] = (clampByte(a) << 24)
                                | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
                        }
                    }
                }
            }
        }
    }

    private static void computeBC7EndpointsFixed(byte[] data, int off, int mode,
                                                  int numSubsets, int numPBits, int[] endpoints) {
        int bitOff = mode + 1;
        int numPartitionBits = 0;
        switch (mode) {
            case 0: numPartitionBits = 4; break;
            case 1:
            case 2:
            case 3:
            case 7: numPartitionBits = 6; break;
        }
        bitOff += numPartitionBits;

        // For modes 4,5: skip rotation and idxMode bits
        if (mode == 4) bitOff += 2;
        if (mode == 5) bitOff += 2;

        int[][] compBits = {
            {4,4,4,0}, {4,4,4,0}, {4,4,4,0},  // mode0: 3 subsets
            {6,6,6,0}, {6,6,6,0},              // mode1: 2 subsets
            {5,5,5,0}, {5,5,5,0}, {5,5,5,0},  // mode2: 3 subsets
            {7,7,7,0}, {7,7,7,0},              // mode3: 2 subsets
            {5,5,5,6}, {5,5,5,6},              // mode4: 1 subset, 2 endpoints
            {7,7,7,8}, {7,7,7,8},              // mode5: 1 subset, 2 endpoints
            {7,7,7,7}, {7,7,7,7},              // mode6: 1 subset, 2 endpoints
            {5,5,5,0}, {5,5,5,0},              // mode7: 2 subsets
        };

        // Compute starting index into compBits for this mode
        int[] modeStart = {0, 3, 5, 8, 10, 12, 14, 16};
        int start = modeStart[mode];
        int totalEndpoints = numSubsets * 2;

        // Collect raw component values
        int[] rawVals = new int[totalEndpoints * 4];
        int[] rawBits = new int[totalEndpoints * 4];
        for (int ep = 0; ep < totalEndpoints; ep++) {
            for (int c = 0; c < 4; c++) {
                int bits = compBits[start + ep][c];
                rawBits[ep * 4 + c] = bits;
                if (bits > 0) {
                    int val = (int)readBits(data, off, bitOff, bits);
                    bitOff += bits;
                    rawVals[ep * 4 + c] = val;
                } else {
                    rawVals[ep * 4 + c] = 0;
                }
            }
        }

        // Read P-bits (per subset, shared between both endpoints in a pair)
        int[] pBits = new int[numSubsets * numPBits];
        if (numPBits > 0) {
            for (int s = 0; s < numSubsets; s++) {
                for (int p = 0; p < numPBits; p++) {
                    pBits[s * numPBits + p] = (int)readBits(data, off, bitOff, 1);
                    bitOff += 1;
                }
            }
        }

        // Unquantize component values
        for (int ep = 0; ep < totalEndpoints; ep++) {
            int subset = ep / 2;
            int epInPair = ep % 2;
            for (int c = 0; c < 4; c++) {
                int bits = rawBits[ep * 4 + c];
                if (bits == 0) {
                    endpoints[ep * 8 + c] = (c == 3) ? 255 : 0;
                    continue;
                }
                int val = rawVals[ep * 4 + c];
                int maxVal = (1 << bits) - 1;

                // Apply P-bit (shared within the subset for numPBits==1, or per-endpoint for numPBits==2)
                if (numPBits > 0 && c < 3) {
                    int pBitIndex;
                    if (numPBits == 1) {
                        pBitIndex = subset * numPBits;
                    } else {
                        pBitIndex = subset * numPBits + epInPair;
                    }
                    if (pBitIndex < pBits.length) {
                        int pBit = pBits[pBitIndex];
                        val = (val << 1) | pBit;
                        maxVal = (maxVal << 1) | 1;
                    }
                }

                int unquantized = (val * 255 + maxVal / 2) / maxVal;
                endpoints[ep * 8 + c] = clampByte(unquantized);
            }
        }
    }

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

    private static final int[][] BC7_P2_TABLE = parseBC7PartitionTable(BC7_P2_RAW, 2);
    private static final int[][] BC7_P3_TABLE = parseBC7PartitionTable(BC7_P3_RAW, 3);

    private static int[][] parseBC7PartitionTable(String raw, int maxSubset) {
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

    private static int computeBC7Partition(int partition, int numSubsets, int px, int py) {
        if (numSubsets <= 1) return 0;
        int idx = py * 4 + px;
        if (partition < 0 || partition >= 64) partition = 0;
        if (numSubsets == 2) {
            return BC7_P2_TABLE[partition][idx];
        } else {
            return BC7_P3_TABLE[partition][idx];
        }
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
                    colors[3] = (0 << 24) | (rgb565to888(c0) & 0x00FFFFFF);
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
                    colors[3] = (0 << 24) | (rgb565to888(c0) & 0x00FFFFFF);
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