#ifndef VTX_PARSER_H
#define VTX_PARSER_H

#include "studio_types.h"
#include <vector>
#include <cstdint>

class VtxParser {
public:
    struct StripGroupInfo {
        std::vector<VtxVertex> vertices;
        std::vector<uint32_t> indices;
        struct Strip {
            std::vector<uint32_t> indices;
            bool isTriList;
        };
        std::vector<Strip> strips;
    };

    struct ParsedVtx {
        int32_t version;
        int32_t checksum;
        int32_t numLODs;
        int32_t numBodyParts;
        std::vector<std::vector<StripGroupInfo>> meshStripGroups;
        std::vector<std::vector<StripGroupInfo>> lodMeshStripGroups1;
        std::vector<std::vector<StripGroupInfo>> lodMeshStripGroups2;
        std::vector<std::vector<StripGroupInfo>> lodMeshStripGroups3;
    };

    static const std::vector<std::vector<StripGroupInfo>>& getStripGroupsForLod(
        const ParsedVtx& vtx, int lodLevel);

    static ParsedVtx parse(const std::vector<uint8_t>& data);
};

#endif // VTX_PARSER_H
