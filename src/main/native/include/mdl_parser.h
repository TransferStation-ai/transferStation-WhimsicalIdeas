#ifndef MDL_PARSER_H
#define MDL_PARSER_H

#include "studio_types.h"
#include <string>
#include <vector>
#include <cstdint>

class MdlParser {
public:
    struct ParsedMdl {
        StudioHeader header;
        std::vector<StudioBodyPart> bodyParts;
        std::vector<std::string> bodyPartNames;
        std::vector<StudioModel> models;
        std::vector<int32_t> modelBodyPartIndices;
        std::vector<StudioMesh> meshes;
        std::vector<StudioBone> bones;
        std::vector<StudioEyeball> eyeballs;
        std::vector<StudioTexture> textures;
        std::vector<int32_t> skinTable;
        std::vector<std::string> cdTextures;
        std::vector<std::string> includeModels;
    };

    static ParsedMdl parse(const std::vector<uint8_t>& data);
};

#endif // MDL_PARSER_H
