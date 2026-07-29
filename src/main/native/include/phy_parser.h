#ifndef PHY_PARSER_H
#define PHY_PARSER_H

#include <string>
#include <vector>
#include <cstdint>

class PhyParser {
public:
    struct PhyVertex {
        float x, y, z;
    };

    struct PhyTriangle {
        uint8_t vertexIndex;
        int16_t v1, v2, v3;
    };

    struct PhyConvexHull {
        int32_t vertexOffset;
        int32_t boneIndex;
        int32_t flags;
        int32_t triangleCount;
        std::vector<PhyTriangle> triangles;
        std::vector<PhyVertex> vertices;
    };

    struct PhySolid {
        int index;
        std::string name;
        std::vector<PhyConvexHull> convexHulls;
    };

    struct ParsedPhy {
        int32_t size;
        std::string id;
        int32_t solidCount;
        int32_t checksum;
        std::vector<PhySolid> solids;
        bool valid;
    };

    static ParsedPhy parse(const std::vector<uint8_t>& data);
};

#endif // PHY_PARSER_H
