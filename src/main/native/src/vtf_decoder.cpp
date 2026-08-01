#include "vtf_decoder.h"
#include <algorithm>
#include <cstring>
#include <cmath>
#include <cfloat>
#include <stdexcept>
#include <fstream>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
typedef LONG NTSTATUS;
#endif

namespace vtf {

constexpr int FORMAT_RGBA8888 = 0;
constexpr int FORMAT_ABGR8888 = 1;
constexpr int FORMAT_RGB888 = 2;
constexpr int FORMAT_BGR888 = 3;
constexpr int FORMAT_RGB565 = 4;
constexpr int FORMAT_I8 = 5;
constexpr int FORMAT_IA88 = 6;
constexpr int FORMAT_P8 = 7;
constexpr int FORMAT_A8 = 8;
constexpr int FORMAT_BGRX8888 = 10;
constexpr int FORMAT_BGR565 = 11;
constexpr int FORMAT_BGRA4444 = 13;
constexpr int FORMAT_DXT1 = 14;
constexpr int FORMAT_DXT3 = 15;
constexpr int FORMAT_DXT5 = 16;
constexpr int FORMAT_DXT1_ONEBITALPHA = 19;
constexpr int FORMAT_BGRA5551 = 20;
constexpr int FORMAT_RGBA16F = 23;
constexpr int FORMAT_RGB32F = 24;
constexpr int FORMAT_RGBA16161616F = 25;
constexpr int FORMAT_RG1616F = 26;
constexpr int FORMAT_RG32F = 27;
constexpr int FORMAT_RGB161616F = 28;
constexpr int FORMAT_BC6H = 29;
constexpr int FORMAT_BC7 = 30;

// RGB565 to 8-bit per channel
void VtfDecoder::rgb565To888(uint16_t c, uint8_t& r, uint8_t& g, uint8_t& b) {
    r = static_cast<uint8_t>(((c >> 11) & 0x1F) * 255 / 31);
    g = static_cast<uint8_t>(((c >> 5) & 0x3F) * 255 / 63);
    b = static_cast<uint8_t>((c & 0x1F) * 255 / 31);
}

// Safe unaligned reads
static inline uint16_t readU16(const uint8_t* data, int offset) {
    uint16_t v; memcpy(&v, data + offset, sizeof(uint16_t)); return v;
}
static inline uint32_t readU32(const uint8_t* data, int offset) {
    uint32_t v; memcpy(&v, data + offset, sizeof(uint32_t)); return v;
}
static inline uint64_t readU64(const uint8_t* data, int offset) {
    uint64_t v; memcpy(&v, data + offset, sizeof(uint64_t)); return v;
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

uint32_t VtfDecoder::lerpColorDXT(uint32_t c0, uint32_t c1, int t) {
    uint8_t r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
    uint8_t r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
    return static_cast<uint32_t>(
        (0xFFu << 24) |
        ((((r0 * (3 - t) + r1 * t) / 3) & 0xFF) << 16) |
        ((((g0 * (3 - t) + g1 * t) / 3) & 0xFF) << 8) |
        (((b0 * (3 - t) + b1 * t) / 3) & 0xFF));
}

uint32_t VtfDecoder::lerpColorDXTHalf(uint32_t c0, uint32_t c1) {
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
    int16_t frames = readShort();
    /*int16_t firstFrame =*/ readShort();
    offset += 4; // padding
    /*float reflectivity[3];*/ readInt(); readInt(); readInt();
    offset += 4; // padding
    /*float bumpmapScale =*/ readInt();
    int imageFormat = readInt();
    int mipmapCount = readByte();
    // VTF 7.x header field order: numMipLevels (byte), numX (byte, low-res
    // image width), numY (byte, low-res image height), then lowResImageFormat
    // (int). The previous code read lowResImageFormat (int) before the two
    // bytes, shifting every subsequent field by 2-4 bytes and producing wrong
    // format/dimension values -> garbage or OOB texture decode.
    int lowResImageWidth = readByte();
    int lowResImageHeight = readByte();
    int lowResImageFormat = readInt();
    /*int16_t depth =*/ readShort();

    if (width <= 0 || width > 8192 || height <= 0 || height > 8192)
        throw std::runtime_error("Invalid VTF dimensions");

    result.width = width;
    result.height = height;
    result.format = imageFormat;
    result.rgbaData.resize(static_cast<size_t>(width) * height * 4);

    // Read image data starting at headerSize
    offset = headerSize;

    // Skip low-resolution thumbnail data if present
    if (lowResImageWidth > 0 && lowResImageHeight > 0) {
        offset += computeImageDataSize(lowResImageWidth, lowResImageHeight, lowResImageFormat);
    }

    // VTF stores mipmaps from smallest (mipmapCount-1) to largest (mipmap 0).
    // For multi-frame VTFs, each mipmap level stores all frames.
    // Skip all smaller mipmaps to reach the full-resolution image data.
    for (int i = mipmapCount - 1; i > 0; i--) {
        int mipW = std::max(1, width >> i);
        int mipH = std::max(1, height >> i);
        offset += computeImageDataSize(mipW, mipH, imageFormat) * std::max(1, static_cast<int>(frames));
    }

    size_t dataSize = computeImageDataSize(width, height, imageFormat);

    if (offset > data.size()) offset = data.size();
    if (static_cast<size_t>(offset) + dataSize > data.size())
        dataSize = data.size() - offset;

    std::vector<uint8_t> compressedData(data.begin() + offset, data.begin() + offset + dataSize);

    std::vector<uint8_t> rawData;
    if (compressedData.size() > 1 && compressedData[0] == 0x78) {
        rawData = decompressZlib(compressedData.data(), compressedData.size());
    } else {
        rawData = std::move(compressedData);
    }

    size_t rawSize = rawData.size();
    switch (imageFormat) {
        case FORMAT_DXT1:
        case FORMAT_DXT1_ONEBITALPHA:
            decodeDXT1(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_DXT3:
            decodeDXT3(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_DXT5:
            decodeDXT5(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_I8:
        case FORMAT_P8:
            decodeI8(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_IA88:
            decodeIA88(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_A8:
            decodeA8(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_RGB565:
            decodeRGB565(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_BGR565:
            decodeBGR565(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_BGRA5551:
            decodeBGRA5551(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_BGRA4444:
            decodeBGRA4444(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_RGBA16F:
        case FORMAT_RGBA16161616F:
            decodeRGBA16F(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_RGB32F:
            decodeRGB32F(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_RG1616F:
            decodeRG1616F(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_RG32F:
            decodeRG32F(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_RGB161616F:
            decodeRGB161616F(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_BC6H:
            decodeBC6H(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        case FORMAT_BC7:
            decodeBC7(rawData.data(), rawSize, width, height, result.rgbaData);
            break;
        default:
            decodeRawRGBA(rawData.data(), rawSize, width, height, result.rgbaData, imageFormat);
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
#ifdef _WIN32
    using RtlDecompressBufferFn = NTSTATUS(NTAPI*)(USHORT, PUCHAR, ULONG, PUCHAR, ULONG, PULONG);
    static RtlDecompressBufferFn RtlDecompressBuffer = nullptr;
    if (!RtlDecompressBuffer) {
        HMODULE ntdll = GetModuleHandleA("ntdll.dll");
        if (ntdll)
            RtlDecompressBuffer = (RtlDecompressBufferFn)GetProcAddress(ntdll, "RtlDecompressBuffer");
    }

    if (!RtlDecompressBuffer) {
        return std::vector<uint8_t>(data, data + size);
    }

    const USHORT COMPRESSION_FORMAT_ZLIB = 0x0002;
    std::vector<uint8_t> result(size * 4);
    ULONG finalSize = 0;

    NTSTATUS status = RtlDecompressBuffer(COMPRESSION_FORMAT_ZLIB,
        result.data(), static_cast<ULONG>(result.size()),
        const_cast<uint8_t*>(data), static_cast<ULONG>(size), &finalSize);

    if (status >= 0 && finalSize > 0) {
        result.resize(finalSize);
        return result;
    }

    result.resize(size * 8);
    status = RtlDecompressBuffer(COMPRESSION_FORMAT_ZLIB,
        result.data(), static_cast<ULONG>(result.size()),
        const_cast<uint8_t*>(data), static_cast<ULONG>(size), &finalSize);

    if (status >= 0 && finalSize > 0) {
        result.resize(finalSize);
        return result;
    }

    return std::vector<uint8_t>(data, data + size);
#else
    (void)data; (void)size;
    return std::vector<uint8_t>();
#endif
}

// BC6H/BC7 format constants for block mode detection
static constexpr int BC6H_MODE_MASK = 0x1F;
static constexpr int BC7_MODE_MASK = 0xFF;

// Forward declarations for helpers
static uint64_t readBits64(const uint8_t* data, int startByte, int bitOffset, int numBits);
static int signExtend(int val, int bits);
static void decodeBC6HBlock(const uint8_t* block, int bx, int by, int width, int height, std::vector<uint8_t>& rgba);
static void decodeBC7Block(const uint8_t* block, int bx, int by, int width, int height, std::vector<uint8_t>& rgba);

void VtfDecoder::decodeRawRGBA(const uint8_t* data, size_t srcSize, int width, int height,
                                std::vector<uint8_t>& rgba, int format) {
    size_t pixels = static_cast<size_t>(width) * height;
    if (format == FORMAT_RGBA8888 || format == FORMAT_BGRX8888) {
        size_t copySize = std::min(pixels * 4, srcSize);
        for (size_t i = 0; i < copySize; i += 4) {
            rgba[i + 0] = data[i + 0];
            rgba[i + 1] = data[i + 1];
            rgba[i + 2] = data[i + 2];
            rgba[i + 3] = (format == FORMAT_BGRX8888) ? 255 : data[i + 3];
        }
    } else if (format == FORMAT_ABGR8888) {
        size_t copySize = std::min(pixels * 4, srcSize);
        for (size_t i = 0; i < copySize; i += 4) {
            rgba[i + 0] = data[i + 3];
            rgba[i + 1] = data[i + 2];
            rgba[i + 2] = data[i + 1];
            rgba[i + 3] = data[i + 0];
        }
    } else if (format == FORMAT_RGB888 || format == FORMAT_BGR888) {
        size_t copySize = std::min(pixels * 3, srcSize);
        for (size_t i = 0, j = 0; j + 2 < copySize; i += 4, j += 3) {
            if (format == FORMAT_RGB888) {
                rgba[i + 0] = data[j + 0];
                rgba[i + 1] = data[j + 1];
                rgba[i + 2] = data[j + 2];
            } else {
                rgba[i + 0] = data[j + 2];
                rgba[i + 1] = data[j + 1];
                rgba[i + 2] = data[j + 0];
            }
            rgba[i + 3] = 255;
        }
    }
}

void VtfDecoder::decodeDXT1(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba) {
    int blockW = (width + 3) / 4;
    int blockH = (height + 3) / 4;
    for (int by = 0; by < blockH; by++) {
        for (int bx = 0; bx < blockW; bx++) {
            int blockOff = (by * blockW + bx) * 8;
            if (static_cast<size_t>(blockOff) + 8 > rawSize) continue;
            uint16_t c0 = readU16(data, blockOff);
            uint16_t c1 = readU16(data, blockOff + 2);
            uint32_t bits = readU32(data, blockOff + 4);

            uint8_t r0, g0, b0, r1, g1, b1;
            rgb565To888(c0, r0, g0, b0);
            rgb565To888(c1, r1, g1, b1);

            uint32_t colors[4];
            colors[0] = (0xFFu << 24) | (r0 << 16) | (g0 << 8) | b0;
            colors[1] = (0xFFu << 24) | (r1 << 16) | (g1 << 8) | b1;
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

void VtfDecoder::decodeDXT3(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba) {
    int blockW = (width + 3) / 4;
    int blockH = (height + 3) / 4;
    for (int by = 0; by < blockH; by++) {
        for (int bx = 0; bx < blockW; bx++) {
            int blockOff = (by * blockW + bx) * 16;
            if (static_cast<size_t>(blockOff) + 16 > rawSize) continue;

            // Alpha data (first 8 bytes)
            uint64_t alphaBits = readU64(data, blockOff);

            int colorOff = blockOff + 8;
            uint16_t c0 = readU16(data, colorOff);
            uint16_t c1 = readU16(data, colorOff + 2);
            uint32_t bits = readU32(data, colorOff + 4);

            uint8_t r0, g0, b0, r1, g1, b1;
            rgb565To888(c0, r0, g0, b0);
            rgb565To888(c1, r1, g1, b1);

            uint32_t colors[4];
            colors[0] = (0xFFu << 24) | (r0 << 16) | (g0 << 8) | b0;
            colors[1] = (0xFFu << 24) | (r1 << 16) | (g1 << 8) | b1;
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

void VtfDecoder::decodeDXT5(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba) {
    int blockW = (width + 3) / 4;
    int blockH = (height + 3) / 4;
    for (int by = 0; by < blockH; by++) {
        for (int bx = 0; bx < blockW; bx++) {
            int blockOff = (by * blockW + bx) * 16;
            if (static_cast<size_t>(blockOff) + 16 > rawSize) continue;

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
            uint16_t c0 = readU16(data, colorOff);
            uint16_t c1 = readU16(data, colorOff + 2);
            uint32_t bits = readU32(data, colorOff + 4);

            uint8_t r0, g0, b0, r1, g1, b1;
            rgb565To888(c0, r0, g0, b0);
            rgb565To888(c1, r1, g1, b1);

            uint32_t colors[4];
            colors[0] = (0xFFu << 24) | (r0 << 16) | (g0 << 8) | b0;
            colors[1] = (0xFFu << 24) | (r1 << 16) | (g1 << 8) | b1;
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

int VtfDecoder::computeImageDataSize(int w, int h, int format) {
    if (format == FORMAT_DXT1 || format == FORMAT_DXT1_ONEBITALPHA || format == FORMAT_DXT3 || format == FORMAT_DXT5) {
        int bw = (w + 3) / 4;
        int bh = (h + 3) / 4;
        int blockSize = (format == FORMAT_DXT1 || format == FORMAT_DXT1_ONEBITALPHA) ? 8 : 16;
        return bw * bh * blockSize;
    } else if (format == FORMAT_RGBA8888 || format == FORMAT_ABGR8888 || format == FORMAT_BGRX8888
               || format == FORMAT_BGRA4444) {
        return w * h * 4;
    } else if (format == FORMAT_BGRA5551 || format == FORMAT_BGR565 || format == FORMAT_RGB565) {
        return w * h * 2;
    } else if (format == FORMAT_RGB888 || format == FORMAT_BGR888) {
        return w * h * 3;
    } else if (format == FORMAT_I8 || format == FORMAT_A8 || format == FORMAT_P8) {
        return w * h;
    } else if (format == FORMAT_IA88) {
        return w * h * 2;
    } else if (format == FORMAT_RGBA16F || format == FORMAT_RGBA16161616F) {
        return w * h * 8;
    } else if (format == FORMAT_RGB32F) {
        return w * h * 12;
    } else if (format == FORMAT_RG1616F) {
        return w * h * 4;
    } else if (format == FORMAT_RG32F) {
        return w * h * 8;
    } else if (format == FORMAT_RGB161616F) {
        return w * h * 6;
    } else {
        return w * h * 4;
    }
}

void VtfDecoder::decodeI8(const uint8_t* data, size_t srcSize, int width, int height,
                           std::vector<uint8_t>& rgba) {
    int pixels = width * height;
    for (int i = 0; i < pixels; i++) {
        int dstOff = i * 4;
        if (static_cast<size_t>(i) < srcSize) {
            uint8_t val = data[i];
            rgba[dstOff + 0] = val;
            rgba[dstOff + 1] = val;
            rgba[dstOff + 2] = val;
            rgba[dstOff + 3] = 255;
        } else {
            rgba[dstOff + 0] = 0;
            rgba[dstOff + 1] = 0;
            rgba[dstOff + 2] = 0;
            rgba[dstOff + 3] = 255;
        }
    }
}

void VtfDecoder::decodeIA88(const uint8_t* data, size_t srcSize, int width, int height,
                              std::vector<uint8_t>& rgba) {
    int pixels = width * height;
    int srcOffset = 0;
    for (int i = 0; i < pixels; i++) {
        int dstOff = i * 4;
        if (static_cast<size_t>(srcOffset + 1) < srcSize) {
            uint8_t val = data[srcOffset];
            uint8_t alpha = data[srcOffset + 1];
            rgba[dstOff + 0] = val;
            rgba[dstOff + 1] = val;
            rgba[dstOff + 2] = val;
            rgba[dstOff + 3] = alpha;
            srcOffset += 2;
        } else {
            rgba[dstOff + 0] = 0;
            rgba[dstOff + 1] = 0;
            rgba[dstOff + 2] = 0;
            rgba[dstOff + 3] = 255;
        }
    }
}

void VtfDecoder::decodeA8(const uint8_t* data, size_t srcSize, int width, int height,
                           std::vector<uint8_t>& rgba) {
    int pixels = width * height;
    for (int i = 0; i < pixels; i++) {
        int dstOff = i * 4;
        if (static_cast<size_t>(i) < srcSize) {
            uint8_t alpha = data[i];
            rgba[dstOff + 0] = 255;
            rgba[dstOff + 1] = 255;
            rgba[dstOff + 2] = 255;
            rgba[dstOff + 3] = alpha;
        } else {
            rgba[dstOff + 0] = 255;
            rgba[dstOff + 1] = 255;
            rgba[dstOff + 2] = 255;
            rgba[dstOff + 3] = 255;
        }
    }
}

void VtfDecoder::decodeRGB565(const uint8_t* data, size_t srcSize, int width, int height,
                               std::vector<uint8_t>& rgba) {
    int srcOffset = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int dstOff = (y * width + x) * 4;
            if (static_cast<size_t>(srcOffset + 1) < srcSize) {
                uint16_t c = readU16(data, srcOffset);
                uint8_t r5 = (c >> 11) & 0x1F;
                uint8_t g6 = (c >> 5) & 0x3F;
                uint8_t b5 = c & 0x1F;
                rgba[dstOff + 0] = static_cast<uint8_t>((r5 * 255) / 31);
                rgba[dstOff + 1] = static_cast<uint8_t>((g6 * 255) / 63);
                rgba[dstOff + 2] = static_cast<uint8_t>((b5 * 255) / 31);
                rgba[dstOff + 3] = 255;
                srcOffset += 2;
            } else {
                rgba[dstOff + 0] = 0;
                rgba[dstOff + 1] = 0;
                rgba[dstOff + 2] = 0;
                rgba[dstOff + 3] = 255;
            }
        }
    }
}

void VtfDecoder::decodeBGR565(const uint8_t* data, size_t srcSize, int width, int height,
                               std::vector<uint8_t>& rgba) {
    int srcOffset = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int dstOff = (y * width + x) * 4;
            if (static_cast<size_t>(srcOffset + 1) < srcSize) {
                uint16_t c = readU16(data, srcOffset);
                uint8_t b5 = (c >> 11) & 0x1F;
                uint8_t g6 = (c >> 5) & 0x3F;
                uint8_t r5 = c & 0x1F;
                rgba[dstOff + 0] = static_cast<uint8_t>((r5 * 255) / 31);
                rgba[dstOff + 1] = static_cast<uint8_t>((g6 * 255) / 63);
                rgba[dstOff + 2] = static_cast<uint8_t>((b5 * 255) / 31);
                rgba[dstOff + 3] = 255;
                srcOffset += 2;
            } else {
                rgba[dstOff + 0] = 0;
                rgba[dstOff + 1] = 0;
                rgba[dstOff + 2] = 0;
                rgba[dstOff + 3] = 255;
            }
        }
    }
}

void VtfDecoder::decodeBGRA5551(const uint8_t* data, size_t srcSize, int width, int height,
                                 std::vector<uint8_t>& rgba) {
    int srcOffset = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int dstOff = (y * width + x) * 4;
            if (static_cast<size_t>(srcOffset + 1) < srcSize) {
                uint16_t c = readU16(data, srcOffset);
                uint8_t b5 = (c >> 11) & 0x1F;
                uint8_t g5 = (c >> 6) & 0x1F;
                uint8_t r5 = (c >> 1) & 0x1F;
                uint8_t a1 = c & 0x01;
                rgba[dstOff + 0] = static_cast<uint8_t>((r5 * 255) / 31);
                rgba[dstOff + 1] = static_cast<uint8_t>((g5 * 255) / 31);
                rgba[dstOff + 2] = static_cast<uint8_t>((b5 * 255) / 31);
                rgba[dstOff + 3] = a1 ? 255 : 0;
                srcOffset += 2;
            } else {
                rgba[dstOff + 0] = 0;
                rgba[dstOff + 1] = 0;
                rgba[dstOff + 2] = 0;
                rgba[dstOff + 3] = 255;
            }
        }
    }
}

void VtfDecoder::decodeBGRA4444(const uint8_t* data, size_t srcSize, int width, int height,
                                 std::vector<uint8_t>& rgba) {
    int srcOffset = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int dstOff = (y * width + x) * 4;
            if (static_cast<size_t>(srcOffset + 1) < srcSize) {
                uint16_t c = readU16(data, srcOffset);
                uint8_t a4 = (c >> 12) & 0xF;
                uint8_t b4 = (c >> 8) & 0xF;
                uint8_t g4 = (c >> 4) & 0xF;
                uint8_t r4 = c & 0xF;
                rgba[dstOff + 0] = static_cast<uint8_t>(r4 * 17);
                rgba[dstOff + 1] = static_cast<uint8_t>(g4 * 17);
                rgba[dstOff + 2] = static_cast<uint8_t>(b4 * 17);
                rgba[dstOff + 3] = static_cast<uint8_t>(a4 * 17);
                srcOffset += 2;
            } else {
                rgba[dstOff + 0] = 0;
                rgba[dstOff + 1] = 0;
                rgba[dstOff + 2] = 0;
                rgba[dstOff + 3] = 255;
            }
        }
    }
}

void VtfDecoder::decodeP8(const uint8_t* data, size_t srcSize, int width, int height,
                         std::vector<uint8_t>& rgba, std::vector<uint8_t>& palette) {
    palette.clear();
    if (srcSize <= 0) return;

    size_t paletteSize = data[0];
    if (paletteSize == 0 || paletteSize > 256) paletteSize = 256;
    palette.resize(paletteSize * 4, 0);

    size_t paletteOffset = 1;
    size_t bytesPerEntry = (paletteSize <= 2) ? 1 : (paletteSize <= 16) ? 2 : 3;
    if (paletteSize == 1) {
        for (size_t i = 0; i < paletteSize; i++) {
            uint8_t v = data[paletteOffset++];
            palette[i * 4 + 0] = v; palette[i * 4 + 1] = v;
            palette[i * 4 + 2] = v; palette[i * 4 + 3] = 255;
        }
    } else if (paletteSize <= 16) {
        for (size_t i = 0; i < paletteSize; i++) {
            if (paletteOffset >= srcSize) break;
            uint8_t packed = data[paletteOffset++];
            if (paletteSize <= 4) {
                int bitsPerChannel = (paletteSize <= 2) ? 3 : 2;
                uint8_t r = (packed >> 6) & 3;
                uint8_t g = (packed >> (4 - bitsPerChannel)) & ((1 << (bitsPerChannel + 1)) - 1);
                uint8_t b = packed & 15;
                if (paletteSize <= 2) {
                    r = (packed >> 5) & 7; g = (packed >> 2) & 7; b = packed & 3;
                }
                palette[i * 4 + 0] = (r * 255) / ((1 << (8 - bitsPerChannel * 2)) - 1);
                palette[i * 4 + 1] = (g * 255) / ((1 << (bitsPerChannel + 1)) - 1);
                palette[i * 4 + 2] = (b * 255) / 15;
                palette[i * 4 + 3] = 255;
            } else {
                uint8_t r = (packed >> 4) & 0xF;
                uint8_t g = packed & 0xF;
                palette[i * 4 + 0] = (r * 255) / 15;
                palette[i * 4 + 1] = (g * 255) / 15;
                palette[i * 4 + 2] = 0;
                palette[i * 4 + 3] = 255;
            }
        }
    } else {
        for (size_t i = 0; i < paletteSize; i++) {
            if (paletteOffset + 2 >= srcSize) break;
            palette[i * 4 + 0] = data[paletteOffset++];
            palette[i * 4 + 1] = data[paletteOffset++];
            palette[i * 4 + 2] = data[paletteOffset++];
            palette[i * 4 + 3] = 255;
        }
    }

    if (paletteOffset > srcSize) return;

    size_t totalPixels = static_cast<size_t>(width) * height;
    if (totalPixels > srcSize - paletteOffset)
        totalPixels = srcSize - paletteOffset;

    for (size_t i = 0; i < totalPixels; i++) {
        size_t dstOff = i * 4;
        uint8_t index = data[paletteOffset + i];
        size_t srcOff = static_cast<size_t>(index) * 4;
        if (srcOff + 3 < palette.size()) {
            rgba[dstOff + 0] = palette[srcOff + 0];
            rgba[dstOff + 1] = palette[srcOff + 1];
            rgba[dstOff + 2] = palette[srcOff + 2];
            rgba[dstOff + 3] = palette[srcOff + 3];
        } else {
            rgba[dstOff + 0] = rgba[dstOff + 1] = rgba[dstOff + 2] = 0;
            rgba[dstOff + 3] = 255;
        }
    }
}

void VtfDecoder::decodeRGBA16F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba) {
    if (srcSize < static_cast<size_t>(width) * height * 8) return;

    for (int i = 0; i < width * height; i++) {
        int idx = i * 8;
        if (idx + 7 >= srcSize) {
            rgba[i * 4 + 0] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = 255;
            continue;
        }

        uint16_t rHalf = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8);
        uint16_t gHalf = (data[idx + 2] & 0xFF) | ((data[idx + 3] & 0xFF) << 8);
        uint16_t bHalf = (data[idx + 4] & 0xFF) | ((data[idx + 5] & 0xFF) << 8);
        uint16_t aHalf = (data[idx + 6] & 0xFF) | ((data[idx + 7] & 0xFF) << 8);

        float r = halfToFloat(rHalf);
        float g = halfToFloat(gHalf);
        float b = halfToFloat(bHalf);
        float a = halfToFloat(aHalf);

        rgba[i * 4 + 0] = floatToUint8(r);
        rgba[i * 4 + 1] = floatToUint8(g);
        rgba[i * 4 + 2] = floatToUint8(b);
        rgba[i * 4 + 3] = floatToUint8(a);
    }
}

void VtfDecoder::decodeRGB32F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba) {
    if (srcSize < static_cast<size_t>(width) * height * 12) return;

    for (int i = 0; i < width * height; i++) {
        int idx = i * 12;
        if (idx + 11 >= srcSize) {
            rgba[i * 4 + 0] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = 255;
            continue;
        }

        uint32_t rBits, gBits, bBits;
        memcpy(&rBits, data + idx, 4);
        memcpy(&gBits, data + idx + 4, 4);
        memcpy(&bBits, data + idx + 8, 4);
        float r, g, b;
        memcpy(&r, &rBits, sizeof(float));
        memcpy(&g, &gBits, sizeof(float));
        memcpy(&b, &bBits, sizeof(float));

        rgba[i * 4 + 0] = floatToUint8(r);
        rgba[i * 4 + 1] = floatToUint8(g);
        rgba[i * 4 + 2] = floatToUint8(b);
        rgba[i * 4 + 3] = 255;
    }
}

void VtfDecoder::decodeRGBA16161616F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba) {
    if (srcSize < static_cast<size_t>(width) * height * 16) return;

    for (int i = 0; i < width * height; i++) {
        int idx = i * 16;
        if (idx + 15 >= srcSize) {
            rgba[i * 4 + 0] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = 255;
            continue;
        }

        uint32_t rBits, gBits, bBits, aBits;
        memcpy(&rBits, data + idx, 4);
        memcpy(&gBits, data + idx + 4, 4);
        memcpy(&bBits, data + idx + 8, 4);
        memcpy(&aBits, data + idx + 12, 4);
        float r, g, b, a;
        memcpy(&r, &rBits, sizeof(float));
        memcpy(&g, &gBits, sizeof(float));
        memcpy(&b, &bBits, sizeof(float));
        memcpy(&a, &aBits, sizeof(float));

        rgba[i * 4 + 0] = floatToUint8(r);
        rgba[i * 4 + 1] = floatToUint8(g);
        rgba[i * 4 + 2] = floatToUint8(b);
        rgba[i * 4 + 3] = floatToUint8(a);
    }
}

void VtfDecoder::decodeRG1616F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba) {
    if (srcSize < static_cast<size_t>(width) * height * 4) return;

    for (int i = 0; i < width * height; i++) {
        int idx = i * 4;
        if (idx + 3 >= srcSize) {
            rgba[i * 4 + 0] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = 255;
            continue;
        }

        uint16_t rHalf = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8);
        uint16_t gHalf = (data[idx + 2] & 0xFF) | ((data[idx + 3] & 0xFF) << 8);

        float r = halfToFloat(rHalf);
        float g = halfToFloat(gHalf);

        rgba[i * 4 + 0] = floatToUint8(r);
        rgba[i * 4 + 1] = floatToUint8(g);
        rgba[i * 4 + 2] = floatToUint8(r);
        rgba[i * 4 + 3] = 255;
    }
}

void VtfDecoder::decodeRG32F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba) {
    if (srcSize < static_cast<size_t>(width) * height * 8) return;

    for (int i = 0; i < width * height; i++) {
        int idx = i * 8;
        if (idx + 7 >= srcSize) {
            rgba[i * 4 + 0] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = 255;
            continue;
        }

        uint32_t rBits, gBits;
        memcpy(&rBits, data + idx, 4);
        memcpy(&gBits, data + idx + 4, 4);
        float r, g;
        memcpy(&r, &rBits, sizeof(float));
        memcpy(&g, &gBits, sizeof(float));

        rgba[i * 4 + 0] = floatToUint8(r);
        rgba[i * 4 + 1] = floatToUint8(g);
        rgba[i * 4 + 2] = floatToUint8(r);
        rgba[i * 4 + 3] = 255;
    }
}

void VtfDecoder::decodeRGB161616F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba) {
    if (srcSize < static_cast<size_t>(width) * height * 6) return;

    for (int i = 0; i < width * height; i++) {
        int idx = i * 6;
        if (idx + 5 >= srcSize) {
            rgba[i * 4 + 0] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = 255;
            continue;
        }

        uint16_t rHalf = (data[idx] & 0xFF) | ((data[idx + 1] & 0xFF) << 8);
        uint16_t gHalf = (data[idx + 2] & 0xFF) | ((data[idx + 3] & 0xFF) << 8);
        uint16_t bHalf = (data[idx + 4] & 0xFF) | ((data[idx + 5] & 0xFF) << 8);

        float r = halfToFloat(rHalf);
        float g = halfToFloat(gHalf);
        float b = halfToFloat(bHalf);

        rgba[i * 4 + 0] = floatToUint8(r);
        rgba[i * 4 + 1] = floatToUint8(g);
        rgba[i * 4 + 2] = floatToUint8(b);
        rgba[i * 4 + 3] = 255;
    }
}

float VtfDecoder::halfToFloat(uint16_t half) {
    int sign = (half >> 15) & 1;
    int exponent = (half >> 10) & 0x1F;
    int mantissa = half & 0x3FF;

    if (exponent == 0) {
        if (mantissa == 0) {
            return sign == 0 ? 0.0f : -0.0f;
        }
        float value = (static_cast<float>(mantissa) / 1024.0f) * static_cast<float>(pow(2, -14));
        return sign == 0 ? value : -value;
    } else if (exponent == 31) {
        if (mantissa == 0) {
            return sign == 0 ? INFINITY : -INFINITY;
        }
        return NAN;
    }

    float value = (1.0f + static_cast<float>(mantissa) / 1024.0f) * static_cast<float>(pow(2, exponent - 15));
    return sign == 0 ? value : -value;
}

uint8_t VtfDecoder::floatToUint8(float f) {
    if (f <= 0.0f) return 0;
    if (f >= 1.0f) return 255;
    return static_cast<uint8_t>(f * 255.0f + 0.5f);
}



// ==================== BC6H Decoder ====================

static uint64_t readBits64(const uint8_t* data, int startByte, int bitOffset, int numBits) {
    uint64_t result = 0;
    uint64_t absBitOff = (uint64_t)startByte * 8 + bitOffset;
    for (int b = 0; b < numBits; b++) {
        uint64_t byteOff = (absBitOff + b) >> 3;
        int bitInByte = (int)((absBitOff + b) & 7);
        int bit = (data[byteOff] >> bitInByte) & 1;
        result |= (uint64_t)bit << b;
    }
    return result;
}

static int signExtend(int val, int bits) {
    int shift = 32 - bits;
    return (val << shift) >> shift;
}

static void decodeBC6HBlock(const uint8_t* block, int bx, int by, int width, int height, std::vector<uint8_t>& rgba) {
    int modeInfo = block[0] & 0x1F;
    bool mode1 = (modeInfo & 0x02) == 0;
    bool mode2 = !mode1 && (modeInfo & 0x01) == 0;
    bool mode0 = (modeInfo & 0x01) == 1 && (modeInfo & 0x10) != 0;
    bool mode3 = (modeInfo & 0x01) == 1 && (modeInfo & 0x10) == 0;

    int r0 = 0, g0 = 0, b0 = 0, r1 = 0, g1 = 0, b1 = 0;

    if (mode1) {
        uint64_t low = readBits64(block, 0, 1, 32);
        uint64_t high = readBits64(block, 0, 33, 32);
        r0 = (int)(low & 0x3FF);
        g0 = (int)((low >> 10) & 0x3FF);
        b0 = (int)((low >> 20) & 0x3FF);
        r1 = (int)(high & 0x3FF);
        g1 = (int)((high >> 10) & 0x3FF);
        b1 = (int)((high >> 20) & 0x3FF);
    } else if (mode0) {
        uint64_t low = readBits64(block, 0, 2, 32);
        uint64_t high = readBits64(block, 0, 34, 32);
        r0 = (int)(low & 0x3FF);
        g0 = (int)((low >> 10) & 0x3FF);
        b0 = (int)((low >> 20) & 0x3FF);
        r1 = (int)(high & 0x3FF);
        g1 = (int)((high >> 10) & 0x3FF);
        b1 = (int)((high >> 20) & 0x3FF);
        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
    } else if (mode2) {
        uint64_t packed = readBits64(block, 0, 2, 48);
        int r0_7 = (int)(packed & 0x7F);
        int g0_6 = (int)((packed >> 7) & 0x3F);
        int b0_7 = (int)((packed >> 13) & 0x7F);
        int r1_7 = (int)((packed >> 20) & 0x7F);
        int g1_6 = (int)((packed >> 27) & 0x3F);
        int b1_7 = (int)((packed >> 33) & 0x7F);
        r0 = (r0_7 << 3) | (r0_7 >> 4);
        g0 = (g0_6 << 4) | g0_6;
        b0 = (b0_7 << 3) | (b0_7 >> 4);
        r1 = (r1_7 << 3) | (r1_7 >> 4);
        g1 = (g1_6 << 4) | g1_6;
        b1 = (b1_7 << 3) | (b1_7 >> 4);
        r1 = (r0 + signExtend(r1, 7) * 2) & 0x3FF;
        g1 = (g0 + signExtend(g1, 6) * 2) & 0x3FF;
        b1 = (b0 + signExtend(b1, 7) * 2) & 0x3FF;
    } else if (mode3) {
        uint64_t packed = readBits64(block, 0, 2, 56);
        r0 = (int)(packed & 0x3FF);
        g0 = (int)((packed >> 10) & 0x3FF);
        b0 = (int)((packed >> 20) & 0x3FF);
        r1 = (int)((packed >> 30) & 0x3FF);
        g1 = (int)((packed >> 40) & 0x3FF);
        b1 = (int)((packed >> 50) & 0x3FF);
        r1 = (r0 + signExtend(r1, 10)) & 0x3FF;
        g1 = (g0 + signExtend(g1, 10)) & 0x3FF;
        b1 = (b0 + signExtend(b1, 10)) & 0x3FF;
    } else {
        for (int py = 0; py < 4; py++)
            for (int px = 0; px < 4; px++) {
                int pxAbs = bx * 4 + px, pyAbs = by * 4 + py;
                if (pxAbs < width && pyAbs < height) {
                    int off = (pyAbs * width + pxAbs) * 4;
                    rgba[off] = 255; rgba[off + 1] = 0; rgba[off + 2] = 255; rgba[off + 3] = 255;
                }
            }
        return;
    }

    int idxStartByte = (mode1 || mode0) ? 9 : 8;
    uint64_t indexData = readBits64(block, 0, idxStartByte * 8, (16 - idxStartByte) * 8);

    for (int py = 0; py < 4; py++) {
        for (int px = 0; px < 4; px++) {
            int idx = (int)((indexData >> (4 * (py * 4 + px))) & 15);
            int weight = idx;
            int r = (r0 * (16 - weight) + r1 * weight) / 16;
            int g = (g0 * (16 - weight) + g1 * weight) / 16;
            int b = (b0 * (16 - weight) + b1 * weight) / 16;
            int pxAbs = bx * 4 + px, pyAbs = by * 4 + py;
            if (pxAbs < width && pyAbs < height) {
                int off = (pyAbs * width + pxAbs) * 4;
                int ri = std::max(0, std::min(255, r >> 2));
                int gi = std::max(0, std::min(255, g >> 2));
                int bi = std::max(0, std::min(255, b >> 2));
                rgba[off] = (uint8_t)ri; rgba[off + 1] = (uint8_t)gi;
                rgba[off + 2] = (uint8_t)bi; rgba[off + 3] = 255;
            }
        }
    }
}

static void decodeBC7Block(const uint8_t* block, int bx, int by, int width, int height, std::vector<uint8_t>& rgba) {
    int firstByte = block[0] & 0xFF;
    int mode;
    for (mode = 0; mode < 8; mode++) {
        if ((firstByte & (1 << mode)) != 0) break;
    }
    if (mode >= 8) mode = 0;

    // Simplified BC7 decoding - fill with a pattern
    for (int py = 0; py < 4; py++) {
        for (int px = 0; px < 4; px++) {
            int pxAbs = bx * 4 + px, pyAbs = by * 4 + py;
            if (pxAbs < width && pyAbs < height) {
                int off = (pyAbs * width + pxAbs) * 4;
                rgba[off] = 128; rgba[off + 1] = 128;
                rgba[off + 2] = 128; rgba[off + 3] = 255;
            }
        }
    }
}

void VtfDecoder::decodeBC6H(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba) {
    rgba.resize(width * height * 4, 0);
    int blockW = (width + 3) / 4;
    int blockH = (height + 3) / 4;
    for (int by = 0; by < blockH; by++) {
        for (int bx = 0; bx < blockW; bx++) {
            int blockOff = (by * blockW + bx) * 16;
            if (static_cast<size_t>(blockOff) + 16 > rawSize) continue;
            decodeBC6HBlock(data + blockOff, bx, by, width, height, rgba);
        }
    }
}

void VtfDecoder::decodeBC7(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba) {
    rgba.resize(width * height * 4, 0);
    int blockW = (width + 3) / 4;
    int blockH = (height + 3) / 4;
    for (int by = 0; by < blockH; by++) {
        for (int bx = 0; bx < blockW; bx++) {
            int blockOff = (by * blockW + bx) * 16;
            if (static_cast<size_t>(blockOff) + 16 > rawSize) continue;
            decodeBC7Block(data + blockOff, bx, by, width, height, rgba);
        }
    }
}

bool VtfDecoder::isBlockCompressed(int format) {
    return format == FORMAT_DXT1 || format == FORMAT_DXT3 || format == FORMAT_DXT5
        || format == FORMAT_DXT1_ONEBITALPHA || format == FORMAT_BC6H || format == FORMAT_BC7;
}

int VtfDecoder::getBlockSize(int format) {
    switch (format) {
        case FORMAT_DXT1:
        case FORMAT_DXT1_ONEBITALPHA:
            return 8;
        case FORMAT_DXT3:
        case FORMAT_DXT5:
        case FORMAT_BC6H:
        case FORMAT_BC7:
            return 16;
        default:
            return 4;
    }
}

int VtfDecoder::getBytesPerPixel(int format) {
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
        case FORMAT_RG1616F:
            return 4;
        case FORMAT_RG32F:
            return 8;
        case FORMAT_RGB161616F:
            return 6;
        default:
            return 4;
    }
}

} // namespace vtf
