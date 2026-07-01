package transferstation.transferstation_whimsicalideas.client.model;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.Inflater;

public class VtfParser {

    private static final int VTF_SIGNATURE = 0x00465456;
    private static final int MAX_FILE_SIZE = 256 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;

    private static final int FORMAT_DXT1 = 0;
    private static final int FORMAT_DXT3 = 1;
    private static final int FORMAT_DXT5 = 2;
    private static final int FORMAT_RGBA8888 = 21;
    private static final int FORMAT_BGRA8888 = 12;
    private static final int FORMAT_BGR888 = 6;
    private static final int FORMAT_RGB888 = 5;

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

        buf.position(headerSize);

        // Skip low-resolution image data (thumbnail) if present
        if (lowResImageWidth > 0 && lowResImageHeight > 0) {
            int lowResBlockSize = getBlockSize(lowResImageFormat);
            int lowResRowBlocks = (lowResImageWidth + 3) / 4;
            int lowResColBlocks = (lowResImageHeight + 3) / 4;
            int lowResDataSize = lowResRowBlocks * lowResColBlocks * lowResBlockSize;
            if (buf.position() + lowResDataSize <= buf.limit()) {
                buf.position(buf.position() + lowResDataSize);
            }
        }

        VtfImageData result = new VtfImageData();
        result.width = width;
        result.height = height;
        result.format = imageFormat;

        int blockSize = getBlockSize(imageFormat);
        int rowBlocks = (width + 3) / 4;
        int colBlocks = (height + 3) / 4;
        int blockCount = rowBlocks * colBlocks;
        int dataSize = blockCount * blockSize;

        byte[] compressedData = new byte[dataSize];
        if (buf.remaining() < dataSize) {
            int remaining = buf.remaining();
            compressedData = new byte[remaining];
        }
        buf.get(compressedData);

        byte[] rawData;
        if (compressedData.length > 0 && compressedData[0] == (byte) 0x78) {
            rawData = decompressZlib(compressedData);
        } else {
            rawData = compressedData;
        }

        BufferedImage image = decodeToImage(rawData, width, height, imageFormat, blockSize);
        result.image = image;
        return result;
    }

    private static int getBlockSize(int format) {
        switch (format) {
            case FORMAT_DXT1:
                return 8;
            case FORMAT_DXT3:
            case FORMAT_DXT5:
                return 16;
            default:
                return 4;
        }
    }

    private static BufferedImage decodeToImage(byte[] data, int width, int height, int format, int blockSize) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];

        switch (format) {
            case FORMAT_DXT1:
                decodeDXT1(data, width, height, pixels);
                break;
            case FORMAT_DXT3:
                decodeDXT3(data, width, height, pixels);
                break;
            case FORMAT_DXT5:
                decodeDXT5(data, width, height, pixels);
                break;
            default:
                decodeRawRGBA(data, width, height, pixels, blockSize);
                break;
        }

        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static void decodeRawRGBA(byte[] data, int width, int height, int[] pixels, int bytesPerPixel) {
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (idx + bytesPerPixel <= data.length) {
                    int r = data[idx] & 0xFF;
                    int g = data[idx + 1] & 0xFF;
                    int b = data[idx + 2] & 0xFF;
                    int a = bytesPerPixel >= 4 ? (data[idx + 3] & 0xFF) : 255;
                    pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    idx += bytesPerPixel;
                } else {
                    pixels[y * width + x] = 0xFFFFFFFF;
                }
            }
        }
    }

    private static int rgb565to888(int c) {
        int r = (c >> 11) & 0x1F;
        int g = (c >> 5) & 0x3F;
        int b = c & 0x1F;
        return (r << 19) | (g << 10) | (b << 3) | 0xFF000000;
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
                    colors[2] = lerpColor(colors[0], colors[1], 1);
                    colors[3] = lerpColor(colors[0], colors[1], 2);
                } else {
                    colors[2] = lerpColor(colors[0], colors[1], 1);
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
                colors[2] = lerpColor(colors[0], colors[1], 1);
                colors[3] = lerpColor(colors[0], colors[1], 2);

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
                colors[2] = lerpColor(colors[0], colors[1], 1);
                colors[3] = lerpColor(colors[0], colors[1], 2);

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

    private static byte[] decompressZlib(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length * 4);
        byte[] buffer = new byte[4096];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Zlib decompression failed", e);
        } finally {
            inflater.end();
        }
        return outputStream.toByteArray();
    }

    public static class VtfImageData {
        public int width;
        public int height;
        public int format;
        public BufferedImage image;
    }
}
