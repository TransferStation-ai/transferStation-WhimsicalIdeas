#ifndef VTF_DECODER_H
#define VTF_DECODER_H

#include <vector>
#include <cstdint>
#include <string>

class VtfDecoder {
public:
    struct DecodedTexture {
        int width;
        int height;
        int format;
        std::vector<uint8_t> rgbaData;
    };

    static DecodedTexture decode(const std::vector<uint8_t>& data);
    static DecodedTexture decodeFile(const std::string& path);

private:
    static std::vector<uint8_t> decompressZlib(const uint8_t* data, size_t size);
    static void decodeDXT1(const uint8_t* data, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeDXT3(const uint8_t* data, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeDXT5(const uint8_t* data, int width, int height, std::vector<uint8_t>& rgba);
    static void decodeRawRGBA(const uint8_t* data, int width, int height, std::vector<uint8_t>& rgba, int format);
};

#endif // VTF_DECODER_H
