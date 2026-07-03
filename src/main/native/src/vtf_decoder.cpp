#include "vtf_decoder.h"
#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <fstream>
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
typedef LONG NTSTATUS;

constexpr int VTF_SIGNATURE = 0x00465456;
constexpr int FORMAT_RGBA8888 = 0;
constexpr int FORMAT_ABGR8888 = 1;
constexpr int FORMAT_RGB888 = 2;
constexpr int FORMAT_BGR888 = 3;
constexpr int FORMAT_BGRX8888 = 10;
constexpr int FORMAT_BGR565 = 11;
constexpr int FORMAT_BGRA4444 = 13;
constexpr int FORMAT_BGRA5551 = 20;
constexpr int FORMAT_DXT1 = 14;
constexpr int FORMAT_DXT3 = 15;
constexpr int FORMAT_DXT5 = 16;
constexpr int FORMAT_DXT1_ONEBITALPHA = 19;

// RGB565 to 8-bit per channel
static void rgb565To888(uint16_t c, uint8_t& r, uint8_t& g, uint8_t& b) {
    r = static_cast<uint8_t>(((c >> 11) & 0x1F) * 255 / 31);
    g = static_cast<uint8_t>(((c >> 5) & 0x3F) * 255 / 63);
    b = static_cast<uint8_t>((c & 0x1F) * 255 / 31);
}

static uint32_t lerpColor(uint32_t c0, uint32_t c1, int t) {
    uint8_t a0 = (c0 >> 24) & 0xFF, r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
    uint8_t a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
    return static_cast<uint32_t>(
        (((a0 * (4 - t) + a1 * t) / 4) << 24) |
        (((r0 * (4 - t) + r1 * t) / 4) << 16) |
        (((g0 * (4 - t) + g1 * t) / 4) << 8) |
        (((b0 * (4 - t) + b1 * t) / 4)));
}

static uint32_t lerpColorDXT(uint32_t c0, uint32_t c1, int t) {
    uint8_t r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
    uint8_t r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
    return static_cast<uint32_t>(
        (0xFFu << 24) |
        ((((r0 * (3 - t) + r1 * t) / 3) & 0xFF) << 16) |
        ((((g0 * (3 - t) + g1 * t) / 3) & 0xFF) << 8) |
        (((b0 * (3 - t) + b1 * t) / 3) & 0xFF));
}

static uint32_t lerpColorDXTHalf(uint32_t c0, uint32_t c1) {
    uint8_t r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
    uint8_t r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
    return static_cast<uint32_t>(
        (0xFFu << 24) |
        ((((r0 + r1) / 2) & 0xFF) << 16) |
        ((((g0 + g1) / 2) & 0xFF) << 8) |
        (((b0 + b1) / 2) & 0xFF));
}

VtfDecoder::DecodedTexture VtfDecoder::decode(const std::vector<uint8_t>& data) {
    DecodedTexture result;

    if (data.size() < 80) throw std::runtime_error("VTF file too small");

    int offset = 0;
    auto readInt = [&]() -> int32_t {
        int32_t v; memcpy(&v, data.data() + offset, 4); offset += 4; return v;
    };
    auto readShort = [&]() -> int16_t {
        int16_t v; memcpy(&v, data.data() + offset, 2); offset += 2; return v;
    };
    auto readByte = [&]() -> uint8_t {
        return data[offset++];
    };

    int32_t signature = readInt();
    if (signature != VTF_SIGNATURE)
        throw std::runtime_error("Invalid VTF signature");

    /*int32_t majorVersion =*/ readInt();
    /*int32_t minorVersion =*/ readInt();
    int32_t headerSize = readInt();
    int width = readShort() & 0xFFFF;
    int height = readShort() & 0xFFFF;
    /*int32_t flags =*/ readInt();
    /*int16_t frames =*/ readShort();
    /*int16_t firstFrame =*/ readShort();
    offset += 4; // padding
    /*float reflectivity[3];*/ readInt(); readInt(); readInt();
    offset += 4; // padding
    /*float bumpmapScale =*/ readInt();
    int imageFormat = readInt();
    int mipmapCount = readByte();
    int lowResImageFormat = readInt();
    int lowResImageWidth = readByte();
    int lowResImageHeight = readByte();
    /*int16_t depth =*/ readShort();

    if (width <= 0 || width > 8192 || height <= 0 || height > 8192)
        throw std::runtime_error("Invalid VTF dimensions");

    result.width = width;
    result.height = height;
    result.format = imageFormat;
    result.rgbaData.resize(static_cast<size_t>(width) * height * 4);

    // Read image data starting at headerSize
    offset = headerSize;

    auto getBlockSize = [](int fmt) -> int {
        switch (fmt) {
            case FORMAT_DXT1:
            case FORMAT_DXT1_ONEBITALPHA:
                return 8;
            case FORMAT_DXT3:
            case FORMAT_DXT5:
                return 16;
            default: return 4;
        }
    };

    auto computeImageDataSize = [&](int w, int h, int fmt) -> size_t {
        if (fmt == FORMAT_DXT1 || fmt == FORMAT_DXT1_ONEBITALPHA || fmt == FORMAT_DXT3 || fmt == FORMAT_DXT5) {
            int bw = (w + 3) / 4;
            int bh = (h + 3) / 4;
            return static_cast<size_t>(bw) * bh * getBlockSize(fmt);
        } else if (fmt == FORMAT_RGBA8888 || fmt == FORMAT_ABGR8888 || fmt == FORMAT_BGRX8888
                   || fmt == FORMAT_BGRA4444) {
            return static_cast<size_t>(w) * h * 4;
        } else if (fmt == FORMAT_BGRA5551 || fmt == FORMAT_BGR565) {
            return static_cast<size_t>(w) * h * 2;
        } else if (fmt == FORMAT_RGB888 || fmt == FORMAT_BGR888) {
            return static_cast<size_t>(w) * h * 3;
        } else if (fmt == FORMAT_BGR565) {
            return static_cast<size_t>(w) * h * 2;
        } else {
            return static_cast<size_t>(w) * h * 4;
        }
    };

    // Skip low-resolution thumbnail data if present
    if (lowResImageWidth > 0 && lowResImageHeight > 0) {
        offset += static_cast<int>(computeImageDataSize(lowResImageWidth, lowResImageHeight, lowResImageFormat));
    }

    // VTF stores mipmaps from smallest (mipmapCount-1) to largest (mipmap 0).
    // Skip all smaller mipmaps to reach the full-resolution image data.
    for (int i = mipmapCount - 1; i > 0; i--) {
        int mipW = std::max(1, width >> i);
        int mipH = std::max(1, height >> i);
        offset += static_cast<int>(computeImageDataSize(mipW, mipH, imageFormat));
    }

    size_t dataSize = computeImageDataSize(width, height, imageFormat);

    if (static_cast<size_t>(offset) + dataSize > data.size())
        dataSize = data.size() - offset;

    std::vector<uint8_t> compressedData(data.begin() + offset, data.begin() + offset + dataSize);

    std::vector<uint8_t> rawData;
    if (compressedData.size() > 1 && compressedData[0] == 0x78) {
        rawData = decompressZlib(compressedData.data(), compressedData.size());
    } else {
        rawData = std::move(compressedData);
    }

    switch (imageFormat) {
        case FORMAT_DXT1:
        case FORMAT_DXT1_ONEBITALPHA:
            decodeDXT1(rawData.data(), width, height, result.rgbaData);
            break;
        case FORMAT_DXT3:
            decodeDXT3(rawData.data(), width, height, result.rgbaData);
            break;
        case FORMAT_DXT5:
            decodeDXT5(rawData.data(), width, height, result.rgbaData);
            break;
        default:
            decodeRawRGBA(rawData.data(), width, height, result.rgbaData, imageFormat);
            break;
    }

    return result;
}

VtfDecoder::DecodedTexture VtfDecoder::decodeFile(const std::string& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) throw std::runtime_error("Cannot open VTF file: " + path);

    size_t size = static_cast<size_t>(file.tellg());
    file.seekg(0);
    std::vector<uint8_t> data(size);
    file.read(reinterpret_cast<char*>(data.data()), size);
    file.close();

    return decode(data);
}

std::vector<uint8_t> VtfDecoder::decompressZlib(const uint8_t* data, size_t size) {
    // Use Windows built-in RtlDecompressBuffer (ntdll.dll, no extra dependencies)
    using RtlDecompressBufferFn = NTSTATUS(NTAPI*)(USHORT, PUCHAR, ULONG, PUCHAR, ULONG, PULONG);
    static RtlDecompressBufferFn RtlDecompressBuffer = nullptr;
    if (!RtlDecompressBuffer) {
        HMODULE ntdll = GetModuleHandleA("ntdll.dll");
        if (ntdll)
            RtlDecompressBuffer = (RtlDecompressBufferFn)GetProcAddress(ntdll, "RtlDecompressBuffer");
    }

    if (!RtlDecompressBuffer) {
        // Fallback: return raw data (will look wrong but won't crash)
        return std::vector<uint8_t>(data, data + size);
    }

    // Try progressively larger buffers
    const USHORT COMPRESSION_FORMAT_ZLIB = 0x0002;
    std::vector<uint8_t> result(size * 4); // Start with 4x compressed size
    ULONG finalSize = 0;

    NTSTATUS status = RtlDecompressBuffer(COMPRESSION_FORMAT_ZLIB,
        result.data(), static_cast<ULONG>(result.size()),
        const_cast<uint8_t*>(data), static_cast<ULONG>(size), &finalSize);

    if (status >= 0 && finalSize > 0) {
        result.resize(finalSize);
        return result;
    }

    // If buffer too small, try larger
    result.resize(size * 8);
    status = RtlDecompressBuffer(COMPRESSION_FORMAT_ZLIB,
        result.data(), static_cast<ULONG>(result.size()),
        const_cast<uint8_t*>(data), static_cast<ULONG>(size), &finalSize);

    if (status >= 0 && finalSize > 0) {
        result.resize(finalSize);
        return result;
    }

    // Decompression failed, return raw
    return std::vector<uint8_t>(data, data + size);
}

void VtfDecoder::decodeDXT1(const uint8_t* data, int width, int height, std::vector<uint8_t>& rgba) {
    int blockW = (width + 3) / 4;
    int blockH = (height + 3) / 4;
    for (int by = 0; by < blockH; by++) {
        for (int bx = 0; bx < blockW; bx++) {
            int blockOff = (by * blockW + bx) * 8;
            uint16_t c0 = *reinterpret_cast<const uint16_t*>(data + blockOff);
            uint16_t c1 = *reinterpret_cast<const uint16_t*>(data + blockOff + 2);
            uint32_t bits = *reinterpret_cast<const uint32_t*>(data + blockOff + 4);

            uint8_t r0, g0, b0, r1, g1, b1;
            rgb565To888(c0, r0, g0, b0);
            rgb565To888(c1, r1, g1, b1);

            uint32_t colors[4];
            colors[0] = (0xFF << 24) | (r0 << 16) | (g0 << 8) | b0;
            colors[1] = (0xFF << 24) | (r1 << 16) | (g1 << 8) | b1;
            if (c0 > c1) {
                colors[2] = lerpColorDXT(colors[0], colors[1], 1);
                colors[3] = lerpColorDXT(colors[0], colors[1], 2);
            } else {
                colors[2] = lerpColorDXTHalf(colors[0], colors[1]);
                colors[3] = 0x00000000; // transparent
            }

            for (int py = 0; py < 4; py++) {
                for (int px = 0; px < 4; px++) {
                    int idx = (bits >> (2 * (py * 4 + px))) & 3;
                    int pxAbs = bx * 4 + px;
                    int pyAbs = by * 4 + py;
                    if (pxAbs < width && pyAbs < height) {
                        int dstOff = (pyAbs * width + pxAbs) * 4;
                        uint32_t col = colors[idx];
                        rgba[dstOff + 0] = (col >> 16) & 0xFF; // R
                        rgba[dstOff + 1] = (col >> 8) & 0xFF;  // G
                        rgba[dstOff + 2] = col & 0xFF;          // B
                        rgba[dstOff + 3] = (col >> 24) & 0xFF;  // A
                    }
                }
            }
        }
    }
}

void VtfDecoder::decodeDXT3(const uint8_t* data, int width, int height, std::vector<uint8_t>& rgba) {
    int blockW = (width + 3) / 4;
    int blockH = (height + 3) / 4;
    for (int by = 0; by < blockH; by++) {
        for (int bx = 0; bx < blockW; bx++) {
            int blockOff = (by * blockW + bx) * 16;

            // Alpha data (first 8 bytes)
            uint64_t alphaBits = *reinterpret_cast<const uint64_t*>(data + blockOff);

            int colorOff = blockOff + 8;
            uint16_t c0 = *reinterpret_cast<const uint16_t*>(data + colorOff);
            uint16_t c1 = *reinterpret_cast<const uint16_t*>(data + colorOff + 2);
            uint32_t bits = *reinterpret_cast<const uint32_t*>(data + colorOff + 4);

            uint8_t r0, g0, b0, r1, g1, b1;
            rgb565To888(c0, r0, g0, b0);
            rgb565To888(c1, r1, g1, b1);

            uint32_t colors[4];
            colors[0] = (0xFF << 24) | (r0 << 16) | (g0 << 8) | b0;
            colors[1] = (0xFF << 24) | (r1 << 16) | (g1 << 8) | b1;
            colors[2] = lerpColorDXT(colors[0], colors[1], 1);
            colors[3] = lerpColorDXT(colors[0], colors[1], 2);

            for (int py = 0; py < 4; py++) {
                for (int px = 0; px < 4; px++) {
                    int idx = (bits >> (2 * (py * 4 + px))) & 3;
                    int alpha = static_cast<int>((alphaBits >> (4 * (py * 4 + px))) & 0xF) * 17;
                    int pxAbs = bx * 4 + px;
                    int pyAbs = by * 4 + py;
                    if (pxAbs < width && pyAbs < height) {
                        int dstOff = (pyAbs * width + pxAbs) * 4;
                        uint32_t col = colors[idx];
                        rgba[dstOff + 0] = (col >> 16) & 0xFF;
                        rgba[dstOff + 1] = (col >> 8) & 0xFF;
                        rgba[dstOff + 2] = col & 0xFF;
                        rgba[dstOff + 3] = static_cast<uint8_t>(alpha);
                    }
                }
            }
        }
    }
}

void VtfDecoder::decodeDXT5(const uint8_t* data, int width, int height, std::vector<uint8_t>& rgba) {
    int blockW = (width + 3) / 4;
    int blockH = (height + 3) / 4;
    for (int by = 0; by < blockH; by++) {
        for (int bx = 0; bx < blockW; bx++) {
            int blockOff = (by * blockW + bx) * 16;

            int a0 = data[blockOff];
            int a1 = data[blockOff + 1];
            uint64_t alphaBits = 0;
            for (int i = 0; i < 6; i++) {
                alphaBits |= static_cast<uint64_t>(data[blockOff + 2 + i]) << (i * 8);
            }

            uint8_t alphas[8];
            alphas[0] = static_cast<uint8_t>(a0);
            alphas[1] = static_cast<uint8_t>(a1);
            if (a0 > a1) {
                for (int i = 0; i < 6; i++)
                    alphas[2 + i] = static_cast<uint8_t>((a0 * (6 - i) + a1 * (1 + i)) / 7);
            } else {
                for (int i = 0; i < 4; i++)
                    alphas[2 + i] = static_cast<uint8_t>((a0 * (4 - i) + a1 * (1 + i)) / 5);
                alphas[6] = 0;
                alphas[7] = 255;
            }

            int colorOff = blockOff + 8;
            uint16_t c0 = *reinterpret_cast<const uint16_t*>(data + colorOff);
            uint16_t c1 = *reinterpret_cast<const uint16_t*>(data + colorOff + 2);
            uint32_t bits = *reinterpret_cast<const uint32_t*>(data + colorOff + 4);

            uint8_t r0, g0, b0, r1, g1, b1;
            rgb565To888(c0, r0, g0, b0);
            rgb565To888(c1, r1, g1, b1);

            uint32_t colors[4];
            colors[0] = (0xFF << 24) | (r0 << 16) | (g0 << 8) | b0;
            colors[1] = (0xFF << 24) | (r1 << 16) | (g1 << 8) | b1;
            colors[2] = lerpColorDXT(colors[0], colors[1], 1);
            colors[3] = lerpColorDXT(colors[0], colors[1], 2);

            for (int py = 0; py < 4; py++) {
                for (int px = 0; px < 4; px++) {
                    int colorIdx = (bits >> (2 * (py * 4 + px))) & 3;
                    int alphaIdx = static_cast<int>((alphaBits >> (3 * (py * 4 + px))) & 7);
                    uint8_t alpha = alphas[alphaIdx];
                    int pxAbs = bx * 4 + px;
                    int pyAbs = by * 4 + py;
                    if (pxAbs < width && pyAbs < height) {
                        int dstOff = (pyAbs * width + pxAbs) * 4;
                        uint32_t col = colors[colorIdx];
                        rgba[dstOff + 0] = (col >> 16) & 0xFF;
                        rgba[dstOff + 1] = (col >> 8) & 0xFF;
                        rgba[dstOff + 2] = col & 0xFF;
                        rgba[dstOff + 3] = alpha;
                    }
                }
            }
        }
    }
}

void VtfDecoder::decodeRawRGBA(const uint8_t* data, int width, int height,
                                std::vector<uint8_t>& rgba, int format) {
    int bytesPerPixel;
    bool swapBR = false;
    bool hasAlpha = true;

    switch (format) {
        case FORMAT_RGB888:
            bytesPerPixel = 3; swapBR = false; hasAlpha = false; break;
        case FORMAT_BGR888:
            bytesPerPixel = 3; swapBR = true; hasAlpha = false; break;
        case FORMAT_RGBA8888:
            bytesPerPixel = 4; swapBR = false; hasAlpha = true; break;
        case FORMAT_ABGR8888:
            bytesPerPixel = 4; swapBR = true; hasAlpha = true; break;
        case FORMAT_BGRX8888:
            bytesPerPixel = 4; swapBR = true; hasAlpha = false; break;
        case FORMAT_BGRA4444:
            bytesPerPixel = 4; swapBR = true; hasAlpha = true; break;
        case FORMAT_BGRA5551:
            bytesPerPixel = 2; swapBR = true; hasAlpha = true; break;
        case FORMAT_BGR565:
            bytesPerPixel = 2; hasAlpha = false; break;
        default:
            bytesPerPixel = 4; hasAlpha = true; break;
    }

    int srcOffset = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int dstOff = (y * width + x) * 4;
            if (srcOffset + bytesPerPixel <= static_cast<int>(rgba.size())) {
                uint8_t r, g, b, a;

                if (bytesPerPixel == 2) {
                    uint16_t c = static_cast<uint16_t>(data[srcOffset] | (data[srcOffset + 1] << 8));
                    srcOffset += 2;
                    if (format == FORMAT_BGR565) {
                        uint8_t r5 = (c >> 11) & 0x1F;
                        uint8_t g6 = (c >> 5) & 0x3F;
                        uint8_t b5 = c & 0x1F;
                        r = static_cast<uint8_t>((r5 * 255) / 31);
                        g = static_cast<uint8_t>((g6 * 255) / 63);
                        b = static_cast<uint8_t>((b5 * 255) / 31);
                        a = 255;
                    } else if (format == FORMAT_BGRA5551) {
                        uint8_t b5 = (c >> 11) & 0x1F;
                        uint8_t g5 = (c >> 6) & 0x1F;
                        uint8_t r5 = (c >> 1) & 0x1F;
                        r = static_cast<uint8_t>((r5 * 255) / 31);
                        g = static_cast<uint8_t>((g5 * 255) / 31);
                        b = static_cast<uint8_t>((b5 * 255) / 31);
                        a = (c & 1) ? 255 : 0;
                    } else {
                        r = data[srcOffset - 2]; g = data[srcOffset - 1]; b = 0; a = 255;
                    }
                } else {
                    uint8_t ch0 = data[srcOffset++];
                    uint8_t ch1 = data[srcOffset++];
                    uint8_t ch2 = data[srcOffset++];
                    a = (hasAlpha && bytesPerPixel >= 4) ? data[srcOffset++] : 255;

                    if (swapBR) {
                        b = ch0; g = ch1; r = ch2;
                    } else {
                        r = ch0; g = ch1; b = ch2;
                    }
                }

                rgba[dstOff + 0] = r;
                rgba[dstOff + 1] = g;
                rgba[dstOff + 2] = b;
                rgba[dstOff + 3] = a;
            } else {
                rgba[dstOff + 0] = 255;
                rgba[dstOff + 1] = 255;
                rgba[dstOff + 2] = 255;
                rgba[dstOff + 3] = 255;
            }
        }
    }
}
