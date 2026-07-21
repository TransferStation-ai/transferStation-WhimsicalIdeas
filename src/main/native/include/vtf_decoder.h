#ifndef VTF_DECODER_H
#define VTF_DECODER_H

#include <vector>
#include <cstdint>
#include <string>

namespace vtf {

constexpr int VTF_SIGNATURE = 0x00465456;

class VtfDecoder {
public:
    struct DecodedTexture {
        int width = 0;
        int height = 0;
        int format = 0;
        std::vector<uint8_t> rgbaData;
        std::vector<uint8_t> paletteData; // For P8 format
    };

    static DecodedTexture decode(const std::vector<uint8_t>& data);
    static DecodedTexture decodeFile(const std::string& path);

    // Format utilities
    static int computeImageDataSize(int w, int h, int format);
    static bool isBlockCompressed(int format);
    static int getBlockSize(int format);
    static int getBytesPerPixel(int format);

private:
    // Zlib decompression
    static std::vector<uint8_t> decompressZlib(const uint8_t* data, size_t size);

    // DXT decoders
    static void decodeDXT1(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeDXT3(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeDXT5(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba);

    // BC6H and BC7 decoders
    static void decodeBC6H(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeBC7(const uint8_t* data, size_t rawSize, int width, int height, std::vector<uint8_t>& rgba);

    // Raw format decoders
    static void decodeRawRGBA(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba, int format);
    static void decodeI8(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeIA88(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeA8(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeRGB565(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeBGR565(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeBGRA5551(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeBGRA4444(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeP8(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba, std::vector<uint8_t>& palette);

    // Floating point decoders
    static void decodeRGBA16F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeRGB32F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeRGBA16161616F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeRG1616F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeRG32F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeRGB161616F(const uint8_t* data, size_t srcSize, int width, int height, std::vector<uint8_t>& rgba);

    // Half-float conversion
    static float halfToFloat(uint16_t half);
    static uint8_t floatToUint8(float f);

    // Color interpolation helpers
    static uint32_t lerpColorDXT(uint32_t c0, uint32_t c1, int t);
    static uint32_t lerpColorDXTHalf(uint32_t c0, uint32_t c1);
    static void rgb565To888(uint16_t c, uint8_t& r, uint8_t& g, uint8_t& b);
};

} // namespace vtf

#endif // VTF_DECODER_H
