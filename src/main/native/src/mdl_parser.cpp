#include "mdl_parser.h"
#include <cstring>
#include <stdexcept>
#include <algorithm>

static std::string readFixedString(const uint8_t* data, int offset, int length) {
    std::string result;
    result.reserve(length);
    for (int i = 0; i < length; i++) {
        char c = static_cast<char>(data[offset + i]);
        if (c == '\0') break;
        result += c;
    }
    return result;
}

MdlParser::ParsedMdl MdlParser::parse(const std::vector<uint8_t>& data) {
    ParsedMdl result;
    const uint8_t* raw = data.data();
    size_t size = data.size();

    if (size < STUDIO_HEADER_SIZE) {
        throw std::runtime_error("MDL file too small for header");
    }

    memcpy(&result.header, raw, STUDIO_HEADER_SIZE);

    if (result.header.id != MDL_MAGIC) {
        throw std::runtime_error("Not a valid MDL file (bad magic)");
    }

    auto sanitize = [](int count, int max, const char* name) {
        if (count < 0 || count > max)
            throw std::runtime_error(std::string(name) + " count exceeds maximum");
        return count;
    };

    auto readAt = [&](int fileOffset, void* dest, size_t sz) {
        if (fileOffset < 0 || static_cast<size_t>(fileOffset) + sz > size)
            throw std::runtime_error("MDL read out of bounds");
        memcpy(dest, raw + fileOffset, sz);
    };

    // Parse body parts
    int numBodyParts = sanitize(result.header.numbodyparts, 256, "numbodyparts");
    for (int i = 0; i < numBodyParts; i++) {
        StudioBodyPart bp;
        int bpAddr = result.header.bodypartindex + i * BODYPART_SIZE;
        readAt(bpAddr, &bp, BODYPART_SIZE);
        result.bodyParts.push_back(bp);

        std::string bpName;
        if (bp.sznameindex > 0) {
            int nameAddr = bpAddr + bp.sznameindex;
            bpName = readFixedString(raw, nameAddr, 128);
        }
        result.bodyPartNames.push_back(bpName);
    }

    // Parse models within body parts (store file offset alongside each model)
    std::vector<int> modelFileOffsets; // parallel array to result.models
    for (int bi = 0; bi < static_cast<int>(result.bodyParts.size()); bi++) {
        auto& bp = result.bodyParts[bi];
        int bpFileAddr = result.header.bodypartindex + bi * BODYPART_SIZE;
        int numModels = sanitize(bp.nummodels, 128, "nummodels");
        int modelArrayAddr = bpFileAddr + bp.modelindex;

        for (int mi = 0; mi < numModels; mi++) {
            int modelAddr = modelArrayAddr + mi * MODEL_SIZE;
            StudioModel mdl;
            readAt(modelAddr, &mdl, MODEL_SIZE);
            modelFileOffsets.push_back(modelAddr);
            result.models.push_back(mdl);
            result.modelBodyPartIndices.push_back(bi);
        }
    }

    // Parse meshes within models using stored file offsets
    for (size_t idx = 0; idx < result.models.size(); idx++) {
        auto& mdl = result.models[idx];
        int modelFileAddr = modelFileOffsets[idx];
        
        int meshAddr = modelFileAddr + mdl.meshindex;
        int numMeshes = sanitize(mdl.nummeshes, 4096, "nummeshes");

        for (int i = 0; i < numMeshes; i++) {
            StudioMesh mesh;
            readAt(meshAddr + i * MESH_SIZE, &mesh, MESH_SIZE);
            result.meshes.push_back(mesh);
        }

        // Parse eyeballs
        if (mdl.numeyeballs > 0 && mdl.eyeballindex > 0) {
            int eyeballAddr = modelFileAddr + mdl.eyeballindex;
            int numEyeballs = sanitize(mdl.numeyeballs, 32, "eyeballs");
            for (int i = 0; i < numEyeballs; i++) {
                StudioEyeball eye;
                readAt(eyeballAddr + i * EYEBALL_SIZE, &eye, EYEBALL_SIZE);
                result.eyeballs.push_back(eye);
            }
        }
    }

    // Parse bones
    if (result.header.numbones > 0) {
        int numBones = sanitize(result.header.numbones, 512, "numbones");
        result.bones.resize(numBones);
        for (int i = 0; i < numBones; i++) {
            readAt(result.header.boneindex + i * BONE_SIZE, &result.bones[i], BONE_SIZE);
        }
    }

    // Parse textures
    if (result.header.numtextures > 0 && result.header.textureindex > 0) {
        int numTextures = sanitize(result.header.numtextures, 256, "numtextures");
        int textureEntrySize = (result.header.version >= 48) ? TEXTURE_ENTRY_SIZE_V48 : TEXTURE_ENTRY_SIZE_V44;
        for (int i = 0; i < numTextures; i++) {
            int entryOff = result.header.textureindex + i * textureEntrySize;
            int32_t texFields[6];
            readAt(entryOff, texFields, sizeof(texFields));

            StudioTexture tex;
            tex.nameOffset = texFields[0];
            tex.flags = texFields[1];
            tex.width = texFields[2];
            tex.height = texFields[3];
            tex.viewportX = texFields[4];
            tex.viewportY = texFields[5];

            std::string texName;
            if (tex.nameOffset > 0) {
                int nameOff = entryOff + tex.nameOffset;
                texName = readFixedString(raw, nameOff, 64);
            }
            result.textures.push_back(tex);
        }
    }

    // Parse cdTextures (separate from texture names!)
    if (result.header.numcdtextures > 0 && result.header.cdtextureindex > 0) {
        int numCd = sanitize(result.header.numcdtextures, 64, "numcdtextures");
        for (int i = 0; i < numCd; i++) {
            int entryOff = result.header.cdtextureindex + i * 4;
            int32_t nameOffset;
            readAt(entryOff, &nameOffset, 4);
            if (nameOffset > 0) {
                int absOff = result.header.cdtextureindex + nameOffset;
                std::string path = readFixedString(raw, absOff, 128);
                if (!path.empty()) result.cdTextures.push_back(path);
            }
        }
    }

    // Parse skin table
    if (result.header.numskinref > 0 && result.header.numskinfamilies > 0 && result.header.skinindex > 0) {
        int totalEntries = result.header.numskinref * result.header.numskinfamilies;
        for (int i = 0; i < totalEntries; i++) {
            int16_t val;
            readAt(result.header.skinindex + i * 2, &val, 2);
            result.skinTable.push_back(val & 0xFFFF);
        }
    }

    // Parse include models
    if (result.header.numincludemodels > 0 && result.header.includemodelindex > 0) {
        int numIncludes = sanitize(result.header.numincludemodels, 64, "numincludemodels");
        for (int i = 0; i < numIncludes; i++) {
            int entryOff = result.header.includemodelindex + i * 4;
            int32_t nameOffset;
            readAt(entryOff, &nameOffset, 4);
            if (nameOffset > 0) {
                int absOff = result.header.includemodelindex + nameOffset;
                std::string path = readFixedString(raw, absOff, 128);
                if (!path.empty()) result.includeModels.push_back(path);
            }
        }
    }

    return result;
}
