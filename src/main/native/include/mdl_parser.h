#ifndef MDL_PARSER_H
#define MDL_PARSER_H

#include "studio_types.h"
#include "matrix_math.h"
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
        std::vector<std::string> boneNames;
        std::vector<Matrix4x4> invBindPose;
        std::vector<int32_t> boneParent;
        std::vector<StudioEyeball> eyeballs;
        std::vector<StudioTexture> textures;
        std::vector<std::string> textureNames;  // 存储纹理名称
        std::vector<int32_t> skinTable;
        std::vector<std::string> cdTextures;
        std::vector<std::string> includeModels;
    };

    static ParsedMdl parse(const std::vector<uint8_t>& data);
};

#endif // MDL_PARSER_H
