#ifndef VVD_PARSER_H
#define VVD_PARSER_H

#include "studio_types.h"
#include <vector>
#include <cstdint>

class VvdParser {
public:
    struct ParsedVvd {
        VvdHeader header;
        std::vector<VvdFixup> fixups;
        std::vector<StudioVertexExt> vertices;
        std::vector<std::vector<StudioVertexExt>> lodVertices;
    };

    static ParsedVvd parse(const std::vector<uint8_t>& data);
};

#endif // VVD_PARSER_H
