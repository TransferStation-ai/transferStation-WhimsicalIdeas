#include <jni.h>
#include <cstdint>
#include <vector>
#include <string>
#include <algorithm>
#include <stdexcept>

#include "mdl_parser.h"
#include "vvd_parser.h"
#include "vtx_parser.h"
#include "phy_parser.h"

// Serialization helper — mirrors the one in native_bridge.cpp
struct ByteWriter {
    std::vector<uint8_t> data;
    void writeInt(int32_t v) {
        auto p = reinterpret_cast<const uint8_t*>(&v);
        data.insert(data.end(), p, p + 4);
    }
    void writeFloat(float v) {
        auto p = reinterpret_cast<const uint8_t*>(&v);
        data.insert(data.end(), p, p + 4);
    }
    void writeShort(uint16_t v) {
        auto p = reinterpret_cast<const uint8_t*>(&v);
        data.insert(data.end(), p, p + 2);
    }
    void writeByte(uint8_t v) {
        data.push_back(v);
    }
    void writeString(const std::string& s) {
        uint16_t len = static_cast<uint16_t>(std::min(s.size(), static_cast<size_t>(65535)));
        writeShort(len);
        data.insert(data.end(), s.begin(), s.begin() + len);
    }
    jbyteArray toJByteArray(JNIEnv* env) {
        jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
        if (result) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                reinterpret_cast<const jbyte*>(data.data()));
        }
        return result;
    }
};

extern "C" {

// ===================== Native MDL Parsing JNI =====================

JNIEXPORT jbyteArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeCoreBridge_nativeParseMdlSerialized(
    JNIEnv* env, jclass, jbyteArray mdlData)
{
    if (mdlData == nullptr) return nullptr;
    jsize len = env->GetArrayLength(mdlData);
    if (len <= 0) return nullptr;

    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(mdlData, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    try {
        auto parsed = MdlParser::parse(buf);
        ByteWriter w;
        const int MAGIC = 0x574E5054;
        w.writeInt(MAGIC);
        w.writeInt(parsed.header.id);
        w.writeInt(parsed.header.version);
        w.writeInt(parsed.header.checksum);
        w.writeString(std::string(parsed.header.name));
        w.writeInt(parsed.header.numbones);
        w.writeInt(parsed.header.numbodyparts);
        w.writeInt(parsed.header.numtextures);
        w.writeInt(parsed.header.numskinref);
        w.writeInt(parsed.header.numskinfamilies);
        w.writeInt(parsed.header.flags);

        w.writeInt(static_cast<int>(parsed.bodyParts.size()));
        for (size_t i = 0; i < parsed.bodyParts.size(); i++) {
            std::string bpName = (i < parsed.bodyPartNames.size()) ? parsed.bodyPartNames[i] : "";
            w.writeString(bpName);
            w.writeInt(parsed.bodyParts[i].nummodels);
            w.writeInt(parsed.bodyParts[i].baseIndex);
        }

        w.writeInt(static_cast<int>(parsed.models.size()));
        for (size_t i = 0; i < parsed.models.size(); i++) {
            w.writeString(std::string(parsed.models[i].name));
            w.writeInt(parsed.models[i].nummeshes);
            w.writeInt(parsed.models[i].numvertices);
            int bpIdx = (i < parsed.modelBodyPartIndices.size()) ? parsed.modelBodyPartIndices[i] : -1;
            w.writeInt(bpIdx);
        }

        w.writeInt(static_cast<int>(parsed.meshes.size()));
        for (size_t i = 0; i < parsed.meshes.size(); i++) {
            w.writeInt(parsed.meshes[i].material);
            w.writeInt(parsed.meshes[i].numvertices);
            w.writeInt(parsed.meshes[i].vertexoffset);
            w.writeInt(parsed.meshes[i].meshid);
        }

        w.writeInt(static_cast<int>(parsed.bones.size()));
        for (size_t i = 0; i < parsed.bones.size(); i++) {
            std::string boneName = (i < parsed.boneNames.size()) ? parsed.boneNames[i] : "";
            w.writeString(boneName);
            w.writeInt(parsed.bones[i].parent);
            w.writeFloat(parsed.bones[i].pos[0]);
            w.writeFloat(parsed.bones[i].pos[1]);
            w.writeFloat(parsed.bones[i].pos[2]);
            w.writeFloat(parsed.bones[i].quat[0]);
            w.writeFloat(parsed.bones[i].quat[1]);
            w.writeFloat(parsed.bones[i].quat[2]);
            w.writeFloat(parsed.bones[i].quat[3]);
        }

        w.writeInt(static_cast<int>(parsed.textures.size()));
        for (size_t i = 0; i < parsed.textures.size(); i++) {
            std::string texName = (i < parsed.textureNames.size()) ? parsed.textureNames[i] : "";
            w.writeString(texName);
            w.writeInt(parsed.textures[i].flags);
        }

        w.writeInt(static_cast<int>(parsed.cdTextures.size()));
        for (const auto& ct : parsed.cdTextures) {
            w.writeString(ct);
        }

        w.writeInt(static_cast<int>(parsed.skinTable.size()));
        for (auto sk : parsed.skinTable) {
            w.writeInt(sk);
        }

        w.writeInt(static_cast<int>(parsed.includeModels.size()));
        for (const auto& inc : parsed.includeModels) {
            w.writeString(inc);
        }

        return w.toJByteArray(env);
    } catch (const std::exception& e) {
        return nullptr;
    }
}

// ===================== Native VVD Parsing JNI =====================

JNIEXPORT jbyteArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeCoreBridge_nativeParseVvdSerialized(
    JNIEnv* env, jclass, jbyteArray vvdData)
{
    if (vvdData == nullptr) return nullptr;
    jsize len = env->GetArrayLength(vvdData);
    if (len <= 0) return nullptr;

    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(vvdData, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    try {
        auto parsed = VvdParser::parse(buf);
        ByteWriter w;
        const int MAGIC = 0x574E5055;
        w.writeInt(MAGIC);
        w.writeInt(parsed.header.id);
        w.writeInt(parsed.header.version);
        w.writeInt(parsed.header.checksum);
        w.writeInt(parsed.header.numLODs);

        w.writeInt(static_cast<int>(parsed.vertices.size()));
        for (const auto& v : parsed.vertices) {
            w.writeFloat(v.x);
            w.writeFloat(v.y);
            w.writeFloat(v.z);
            w.writeFloat(v.nx);
            w.writeFloat(v.ny);
            w.writeFloat(v.nz);
            w.writeFloat(v.u);
            w.writeFloat(v.v);
            w.writeFloat(v.boneWeight[0]);
            w.writeFloat(v.boneWeight[1]);
            w.writeFloat(v.boneWeight[2]);
            w.writeByte(v.boneId[0]);
            w.writeByte(v.boneId[1]);
            w.writeByte(v.boneId[2]);
            w.writeByte(v.numBones);
        }

        return w.toJByteArray(env);
    } catch (const std::exception& e) {
        return nullptr;
    }
}

// ===================== Native VTX Parsing JNI =====================

JNIEXPORT jbyteArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeCoreBridge_nativeParseVtxSerialized(
    JNIEnv* env, jclass, jbyteArray vtxData)
{
    if (vtxData == nullptr) return nullptr;
    jsize len = env->GetArrayLength(vtxData);
    if (len <= 0) return nullptr;

    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(vtxData, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    try {
        auto parsed = VtxParser::parse(buf);
        ByteWriter w;
        const int MAGIC = 0x574E5056;
        w.writeInt(MAGIC);
        w.writeInt(parsed.version);
        w.writeInt(parsed.checksum);
        w.writeInt(parsed.numBodyParts);
        w.writeInt(parsed.numLODs);

        // Flatten per-model entries to per-mesh: the native VTX parser groups
        // strip groups per-model (60 entries for 60 models), but the Java
        // buildMeshesForLod walker expects one entry per-mesh (40 entries for
        // 40 meshes). Flatten so the Java side can pair VTX meshes 1:1 with
        // MDL meshes.
        std::vector<std::vector<VtxParser::StripGroupInfo>> flatMeshes;
        for (const auto& modelGroups : parsed.meshStripGroups) {
            for (const auto& sg : modelGroups) {
                flatMeshes.push_back({sg});
            }
        }
        int meshCount = static_cast<int>(flatMeshes.size());
        w.writeInt(meshCount);
        for (int m = 0; m < meshCount; m++) {
            std::vector<uint32_t> triangles;
            for (const auto& sg : flatMeshes[m]) {
                for (const auto& strip : sg.strips) {
                    triangles.insert(triangles.end(), strip.indices.begin(), strip.indices.end());
                }
            }
            // triangles holds a flat list of index triplets. Write the triangle
            // count (size()/3), NOT the raw index count, so the Java side which
            // reads one triangle (3 ints) per iteration stays in sync.
            int triCount = static_cast<int>(triangles.size() / 3);
            w.writeInt(triCount);
            for (size_t t = 0; t + 2 < triangles.size(); t += 3) {
                w.writeInt(static_cast<int32_t>(triangles[t]));
                w.writeInt(static_cast<int32_t>(triangles[t + 1]));
                w.writeInt(static_cast<int32_t>(triangles[t + 2]));
            }
        }

        return w.toJByteArray(env);
    } catch (const std::exception& e) {
        return nullptr;
    }
}

// ===================== Native PHY Parsing JNI =====================

JNIEXPORT jbyteArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeCoreBridge_nativeParsePhySerialized(
    JNIEnv* env, jclass, jbyteArray phyData)
{
    if (phyData == nullptr) return nullptr;
    jsize len = env->GetArrayLength(phyData);
    if (len <= 0) return nullptr;

    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(phyData, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    try {
        auto parsed = PhyParser::parse(buf);
        if (!parsed.valid) return nullptr;

        ByteWriter w;
        const int MAGIC = 0x574E5057;
        w.writeInt(MAGIC);
        w.writeInt(parsed.size);
        w.writeString(parsed.id);
        w.writeInt(parsed.solidCount);
        w.writeInt(parsed.checksum);

        w.writeInt(static_cast<int>(parsed.solids.size()));
        for (const auto& solid : parsed.solids) {
            w.writeInt(solid.index);
            w.writeString(solid.name);
            w.writeInt(static_cast<int>(solid.convexHulls.size()));
            for (const auto& hull : solid.convexHulls) {
                w.writeInt(hull.vertexOffset);
                w.writeInt(hull.boneIndex);
                w.writeInt(hull.flags);
                w.writeInt(hull.triangleCount);

                w.writeInt(static_cast<int>(hull.triangles.size()));
                for (const auto& tri : hull.triangles) {
                    w.writeByte(tri.vertexIndex);
                    w.writeShort(static_cast<uint16_t>(tri.v1));
                    w.writeShort(static_cast<uint16_t>(tri.v2));
                    w.writeShort(static_cast<uint16_t>(tri.v3));
                }

                w.writeInt(static_cast<int>(hull.vertices.size()));
                for (const auto& vert : hull.vertices) {
                    w.writeFloat(vert.x);
                    w.writeFloat(vert.y);
                    w.writeFloat(vert.z);
                }
            }
        }

        return w.toJByteArray(env);
    } catch (const std::exception& e) {
        return nullptr;
    }
}

} // extern "C"
