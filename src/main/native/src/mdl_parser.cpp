#include "mdl_parser.h"
#include <cstring>
#include <stdexcept>
#include <iostream>
#include <unordered_map>

// Helper: read a ptr (relative offset) from the data buffer
// Uses forwarding reference: advances offset for lvalues, leaves rvalues unchanged
template<typename T>
static inline int32_t readInt(const uint8_t* data, T&& offset) {
    int32_t v; std::memcpy(&v, data + offset, 4);
    if constexpr (std::is_lvalue_reference_v<T>) offset += 4;
    return v;
}
template<typename T>
static inline int16_t readShort(const uint8_t* data, T&& offset) {
    int16_t v; std::memcpy(&v, data + offset, 2);
    if constexpr (std::is_lvalue_reference_v<T>) offset += 2;
    return v;
}
template<typename T>
static inline uint8_t readByte(const uint8_t* data, T&& offset) {
    uint8_t v = data[offset];
    if constexpr (std::is_lvalue_reference_v<T>) offset += 1;
    return v;
}
template<typename T>
static inline float readFloat(const uint8_t* data, T&& offset) {
    float v; std::memcpy(&v, data + offset, 4);
    if constexpr (std::is_lvalue_reference_v<T>) offset += 4;
    return v;
}

static std::string readStringAt(const uint8_t* data, int stringOffset, size_t size) {
    if (stringOffset <= 0) return "";
    const char* str = reinterpret_cast<const char*>(data + stringOffset);
    int maxLen = 4096;
    int len = 0;
    while (len < maxLen && stringOffset + len < static_cast<int>(size) && str[len] != '\0') len++;
    return std::string(str, len);
}

// Avoid name collision with static function
using ParseReadString = std::string(*)(const uint8_t*, int);

MdlParser::ParsedMdl MdlParser::parse(const std::vector<uint8_t>& data) {
    ParsedMdl result;
    const uint8_t* raw = data.data();
    size_t size = data.size();

    if (size < STUDIO_HEADER_SIZE)
        throw std::runtime_error("MDL file too small for header");

    // Read header
    int off = 0;
    StudioHeader& h = result.header;
    h.id = readInt(raw, off);
    h.version = readInt(raw, off);
    h.checksum = readInt(raw, off);
    std::memcpy(h.name, raw + off, std::min<size_t>(64, size - off)); h.name[63] = '\0';
    off += 64;
    int32_t dataLength = readInt(raw, off);
    h.eyeposition[0] = readFloat(raw, off); h.eyeposition[1] = readFloat(raw, off); h.eyeposition[2] = readFloat(raw, off);
    h.illumposition[0] = readFloat(raw, off); h.illumposition[1] = readFloat(raw, off); h.illumposition[2] = readFloat(raw, off);
    h.hull_min[0] = readFloat(raw, off); h.hull_min[1] = readFloat(raw, off); h.hull_min[2] = readFloat(raw, off);
    h.hull_max[0] = readFloat(raw, off); h.hull_max[1] = readFloat(raw, off); h.hull_max[2] = readFloat(raw, off);
    h.view_bbmin[0] = readFloat(raw, off); h.view_bbmin[1] = readFloat(raw, off); h.view_bbmin[2] = readFloat(raw, off);
    h.view_bbmax[0] = readFloat(raw, off); h.view_bbmax[1] = readFloat(raw, off); h.view_bbmax[2] = readFloat(raw, off);

    h.flags = readInt(raw, off);
    h.numbones = readInt(raw, off);
    h.boneindex = readInt(raw, off);
    h.numbonecontrollers = readInt(raw, off);
    h.bonecontrollerindex = readInt(raw, off);
    h.numhitboxsets = readInt(raw, off);
    h.hitboxsetindex = readInt(raw, off);
    h.numlocalanim = readInt(raw, off);
    h.localanimindex = readInt(raw, off);
    h.numlocalseq = readInt(raw, off);
    h.localseqindex = readInt(raw, off);
    h.activitylistversion = readInt(raw, off);
    h.eventsindexed = readInt(raw, off);
    h.numtextures = readInt(raw, off);
    h.textureindex = readInt(raw, off);
    h.numcdtextures = readInt(raw, off);
    h.cdtextureindex = readInt(raw, off);
    h.numskinref = readInt(raw, off);
    h.numskinfamilies = readInt(raw, off);
    h.skinindex = readInt(raw, off);
    h.numbodyparts = readInt(raw, off);
    h.bodypartindex = readInt(raw, off);
    h.numlocalattachments = readInt(raw, off);
    h.localattachmentindex = readInt(raw, off);
    h.numlocalnodes = readInt(raw, off);
    h.localnodeindex = readInt(raw, off);
    h.localnodenameindex = readInt(raw, off);
    h.numflexdesc = readInt(raw, off);
    h.flexdescindex = readInt(raw, off);
    h.numflexcontrollers = readInt(raw, off);
    h.flexcontrollerindex = readInt(raw, off);
    h.numflexrules = readInt(raw, off);
    h.flexruleindex = readInt(raw, off);
    h.numikchains = readInt(raw, off);
    h.ikchainindex = readInt(raw, off);
    h.nummouths = readInt(raw, off);
    h.mouthindex = readInt(raw, off);
    h.numlocalposeparameters = readInt(raw, off);
    h.localposeparamindex = readInt(raw, off);
    h.surfacepropindex = readInt(raw, off);
    h.keyvalueindex = readInt(raw, off);
    h.keyvaluesize = readInt(raw, off);
    h.numlocalikautoplaylocks = readInt(raw, off);
    h.localikautoplaylockindex = readInt(raw, off);
    h.mass = readFloat(raw, off);
    h.contents = readInt(raw, off);
    h.numincludemodels = readInt(raw, off);
    h.includemodelindex = readInt(raw, off);
    h.virtualModel = readInt(raw, off);
    h.szanimblocknameindex = readInt(raw, off);
    h.numanimblocks = readInt(raw, off);
    h.animblockindex = readInt(raw, off);
    h.animblockModel = readInt(raw, off);
    h.bonetablenameindex = readInt(raw, off);
    h.vertexbase = readInt(raw, off);
    h.offsetbase = readInt(raw, off);
    h.directionaldotproduct = readByte(raw, off);
    h.rootLod = readByte(raw, off);
    h.numAllowedRootLods = readByte(raw, off);
    h.unused = readByte(raw, off);
    h.flexcontrolleruiindex = readInt(raw, off);
    h.vertAnimFixedPointScale = readFloat(raw, off);
    h.unused3 = readInt(raw, off);
    h.studiohdr2index = readInt(raw, off);

    if (h.id != MDL_MAGIC)
        throw std::runtime_error("Invalid MDL signature (expected IDST)");

    // Parse cdTextures
    if (h.numcdtextures > 0 && h.cdtextureindex > 0 && h.cdtextureindex < static_cast<int>(size)) {
        int texDirOff = h.cdtextureindex;
        for (int i = 0; i < h.numcdtextures; i++) {
            if (texDirOff + 4 > static_cast<int>(size)) break;
            int strOff = readInt(raw, texDirOff + 0);
            texDirOff += 4;
            if (strOff > 0 && strOff < static_cast<int>(size) - 1) {
                // cdtexture nameOff convention varies by compiler:
                // - studiomdl / standard SDK: absolute offset
                // - Crowbar / some decompilers: relative offset from cdtexture array base
                // - Some community tools: relative offset from entry itself
                // Heuristic: small nameOff (< 256 or < cdIndex) is likely relative;
                // large nameOff is likely absolute.
                bool likelyRelative = (strOff < 256) || (strOff < h.cdtextureindex);
                std::string path;
                if (likelyRelative) {
                    path = readStringAt(raw, h.cdtextureindex + strOff, size);
                    if (path.empty()) path = readStringAt(raw, strOff, size);
                } else {
                    path = readStringAt(raw, strOff, size);
                    if (path.empty() && strOff < static_cast<int>(size) - 1)
                        path = readStringAt(raw, h.cdtextureindex + strOff, size);
                }
                if (!path.empty()) {
                    result.cdTextures.push_back(path);
                }
            } else {
                result.cdTextures.emplace_back();
            }
        }
    }

    // Parse textures
    if (h.numtextures > 0 && h.textureindex > 0) {
        int texOff = h.textureindex;
        for (int i = 0; i < h.numtextures; i++) {
            if (texOff + TEXTURE_ENTRY_SIZE > static_cast<int>(size)) break;
            int texEntryBase = texOff;
            StudioTexture tex;
            tex.nameOffset = readInt(raw, texEntryBase + 0); // but we skip
            tex.flags = readInt(raw, texEntryBase + 4);
            tex.width = readInt(raw, texEntryBase + 8);
            tex.height = readInt(raw, texEntryBase + 12);
            tex.viewportX = readInt(raw, texEntryBase + 16);
            tex.viewportY = readInt(raw, texEntryBase + 20);
            texOff += TEXTURE_ENTRY_SIZE;

            // Read name from nameOffset (relative to the texture entry, like Java parser)
            std::string texName;
            if (tex.nameOffset > 0) {
                int absNameOff = texEntryBase + tex.nameOffset;
                if (absNameOff > 0 && absNameOff < static_cast<int>(size)) {
                    texName = readStringAt(raw, absNameOff, size);
                }
            }
            if (texName.empty()) {
                texName = "texture_" + std::to_string(i);
            }
            result.textureNames.push_back(texName);
            result.textures.push_back(tex);
        }
    }

    // Parse skin table
    if (h.skinindex > 0 && h.numskinref > 0 && h.numskinfamilies > 0) {
        int skinOff = h.skinindex;
        int totalSkinEntries = h.numskinref * h.numskinfamilies;
        result.skinTable.reserve(totalSkinEntries);
        for (int i = 0; i < totalSkinEntries; i++) {
            if (skinOff + 4 > static_cast<int>(size)) break;
            result.skinTable.push_back(readInt(raw, skinOff));
            skinOff += 4;
        }
    }

    // Parse body parts
    if (h.numbodyparts > 0 && h.bodypartindex > 0) {
        int bpOff = h.bodypartindex;
        for (int i = 0; i < h.numbodyparts; i++) {
            if (bpOff + BODYPART_SIZE > static_cast<int>(size)) break;
            int bpBase = bpOff;
            StudioBodyPart bp;
            int nameOff = readInt(raw, bpBase + 0);
            bp.nummodels = readInt(raw, bpBase + 4);
            bp.baseIndex = readInt(raw, bpBase + 8);
            bp.modelindex = readInt(raw, bpBase + 12);
            bpOff += BODYPART_SIZE;

            // sznameindex is relative to the bodypart entry itself
            result.bodyPartNames.push_back(
                (nameOff > 0) ? readStringAt(raw, bpBase + nameOff, size) : "bp_" + std::to_string(i));
            result.bodyParts.push_back(bp);

            // Parse models within this body part (modelindex is relative to the bodypart entry)
            if (bp.modelindex > 0 && bp.nummodels > 0) {
                int modelOff = bpBase + bp.modelindex;
                for (int m = 0; m < bp.nummodels; m++) {
                    if (modelOff + MODEL_SIZE > static_cast<int>(size)) break;
                    StudioModel mdl;
                    std::memcpy(mdl.name, raw + modelOff, std::min<size_t>(64, size - modelOff));
                    mdl.name[63] = '\0';
                    mdl.type = readInt(raw, modelOff + 64);
                    mdl.boundingradius = readFloat(raw, modelOff + 68);
                    mdl.nummeshes = readInt(raw, modelOff + 72);
                    mdl.meshindex = readInt(raw, modelOff + 76);
                    mdl.numvertices = readInt(raw, modelOff + 80);
                    mdl.vertexindex = readInt(raw, modelOff + 84);
                    mdl.tangentsindex = readInt(raw, modelOff + 88);
                    mdl.numattachments = readInt(raw, modelOff + 92);
                    mdl.attachmentindex = readInt(raw, modelOff + 96);
                    mdl.numeyeballs = readInt(raw, modelOff + 100);
                    mdl.eyeballindex = readInt(raw, modelOff + 104);
                    int modelBase = modelOff;
                    modelOff += MODEL_SIZE;

                    result.models.push_back(mdl);
                    result.modelBodyPartIndices.push_back(i);

                    // Parse meshes within this model (meshindex is relative to the model entry)
                    if (mdl.meshindex > 0 && mdl.nummeshes > 0) {
                        int meshOff = modelBase + mdl.meshindex;
                        for (int mi = 0; mi < mdl.nummeshes; mi++) {
                            if (meshOff + MESH_SIZE > static_cast<int>(size)) break;
                            StudioMesh mesh;
                            mesh.material = readInt(raw, meshOff + 0);
                            mesh.modelindex = readInt(raw, meshOff + 4);
                            mesh.numvertices = readInt(raw, meshOff + 8);
                            mesh.vertexoffset = readInt(raw, meshOff + 12);
                            mesh.numflexes = readInt(raw, meshOff + 16);
                            mesh.flexindex = readInt(raw, meshOff + 20);
                            mesh.materialtype = readInt(raw, meshOff + 24);
                            mesh.materialparam = readInt(raw, meshOff + 28);
                            mesh.meshid = readInt(raw, meshOff + 32);
                            mesh.center[0] = readFloat(raw, meshOff + 36);
                            mesh.center[1] = readFloat(raw, meshOff + 40);
                            mesh.center[2] = readFloat(raw, meshOff + 44);
                            meshOff += MESH_SIZE;
                            result.meshes.push_back(mesh);
                        }
                    }

                    // Parse eyeballs
                    if (mdl.eyeballindex > 0 && mdl.numeyeballs > 0) {
                        int eyeOff = mdl.eyeballindex;
                        for (int ei = 0; ei < mdl.numeyeballs; ei++) {
                            if (eyeOff + EYEBALL_SIZE > static_cast<int>(size)) break;
                            StudioEyeball eye;
                            eye.sznameindex = readInt(raw, eyeOff);
                            eye.bone = readInt(raw, eyeOff + 4);
                            eye.org[0] = readFloat(raw, eyeOff + 8);
                            eye.org[1] = readFloat(raw, eyeOff + 12);
                            eye.org[2] = readFloat(raw, eyeOff + 16);
                            eye.zoffset = readFloat(raw, eyeOff + 20);
                            eye.radius = readFloat(raw, eyeOff + 24);
                            eye.up[0] = readFloat(raw, eyeOff + 28);
                            eye.up[1] = readFloat(raw, eyeOff + 32);
                            eye.up[2] = readFloat(raw, eyeOff + 36);
                            eye.forward[0] = readFloat(raw, eyeOff + 40);
                            eye.forward[1] = readFloat(raw, eyeOff + 44);
                            eye.forward[2] = readFloat(raw, eyeOff + 48);
                            eye.irisMaterial = readInt(raw, eyeOff + 52);
                            eyeOff += EYEBALL_SIZE;
                            result.eyeballs.push_back(eye);
                        }
                    }
                }
            }
        }
    }

    // Parse bones
    if (h.numbones > 0 && h.boneindex > 0) {
        int boneOff = h.boneindex;
        for (int i = 0; i < h.numbones; i++) {
            if (boneOff + BONE_SIZE > static_cast<int>(size)) break;
            StudioBone bone;
            int nameOff = readInt(raw, boneOff + 0);
            bone.parent = readInt(raw, boneOff + 4);
            for (int j = 0; j < 6; j++) bone.bonecontroller[j] = readInt(raw, boneOff + 8 + j * 4);
            bone.pos[0] = readFloat(raw, boneOff + 32);
            bone.pos[1] = readFloat(raw, boneOff + 36);
            bone.pos[2] = readFloat(raw, boneOff + 40);
            bone.quat[0] = readFloat(raw, boneOff + 44);
            bone.quat[1] = readFloat(raw, boneOff + 48);
            bone.quat[2] = readFloat(raw, boneOff + 52);
            bone.quat[3] = readFloat(raw, boneOff + 56);
            bone.rot[0] = readFloat(raw, boneOff + 60);
            bone.rot[1] = readFloat(raw, boneOff + 64);
            bone.rot[2] = readFloat(raw, boneOff + 68);
            for (int j = 0; j < 3; j++) bone.posscale[j] = readFloat(raw, boneOff + 72 + j * 4);
            for (int j = 0; j < 3; j++) bone.rotscale[j] = readFloat(raw, boneOff + 84 + j * 4);
            for (int j = 0; j < 12; j++) bone.poseToBone[j] = readFloat(raw, boneOff + 96 + j * 4);
            for (int j = 0; j < 4; j++) bone.qAlignment[j] = readFloat(raw, boneOff + 144 + j * 4);
            bone.flags = readInt(raw, boneOff + 160);
            bone.proctype = readInt(raw, boneOff + 164);
            bone.procindex = readInt(raw, boneOff + 168);
            bone.physicsbone = readInt(raw, boneOff + 172);
            bone.surfacepropidx = readInt(raw, boneOff + 176);
            bone.contents = readInt(raw, boneOff + 180);
            int boneBase = boneOff;
            boneOff += BONE_SIZE;

            // sznameindex is relative to the bone entry itself
            result.boneNames.push_back(
                (nameOff > 0) ? readStringAt(raw, boneBase + nameOff, size) : "bone_" + std::to_string(i));
            result.bones.push_back(bone);
        }
    }

    // Parse include models
    if (h.numincludemodels > 0 && h.includemodelindex > 0) {
        int incOff = h.includemodelindex;
        for (int i = 0; i < h.numincludemodels; i++) {
            if (incOff + 4 > static_cast<int>(size)) break;
            int nameOff = readInt(raw, incOff);
            int incBase = incOff;
            incOff += 4;
            if (nameOff > 0 && nameOff < static_cast<int>(size) - 1) {
                // Try absolute offset first, then fall back to relative to the entry base
                std::string path = readStringAt(raw, nameOff, size);
                if (path.empty() && nameOff < static_cast<int>(size) - 1)
                    path = readStringAt(raw, incBase + nameOff, size);
                if (!path.empty())
                    result.includeModels.push_back(path);
            }
        }
    }

    // Build invBindPose and boneParent from parsed bones.
    // invBindPose[i] is the INVERSE of bone i's WORLD bind pose (the pose-to-bone
    // matrices concatenated up the parent chain). Multiplying the animated world
    // bone matrix by this inverse yields the Source-engine skin matrix
    // (worldBone[ i ] * invBindWorld[ i ]), so vertices in the rest pose stay put
    // (identity) and only animation deltas deform them. A local-only inverse
    // (from3x4(poseToBone[i]).inverse()) would move chained bones even in the
    // bind pose, matching neither GLM nor GMod.
    int numBones = static_cast<int>(result.bones.size());
    result.invBindPose.resize(numBones);
    result.boneParent.resize(numBones);
    std::vector<Matrix4x4> bindWorld(numBones);
    for (int i = 0; i < numBones; i++) {
        const StudioBone& bone = result.bones[i];
        Matrix4x4 local = Matrix4x4::from3x4(bone.poseToBone);
        int parent = bone.parent;
        if (parent >= 0 && parent < numBones) {
            bindWorld[i] = Matrix4x4::multiply(bindWorld[parent], local);
        } else {
            bindWorld[i] = local;
        }
        result.invBindPose[i] = bindWorld[i].inverse();
        result.boneParent[i] = parent;
    }

    // Parse StudioHDR2 (extended data)
    if (h.studiohdr2index > 0 && h.studiohdr2index < static_cast<int>(size) - 24) {
        int hdr2Off = h.studiohdr2index;
        int hdr2NumSrcBoneTransforms = readInt(raw, hdr2Off);
        int hdr2SrcBoneTransformIndex = readInt(raw, hdr2Off + 4);
        int hdr2NumIllumAttachments = readInt(raw, hdr2Off + 8);
        int hdr2IllumAttachmentIndex = readInt(raw, hdr2Off + 12);
        int hdr2NumFluid = readInt(raw, hdr2Off + 16);
        int hdr2FluidIndex = readInt(raw, hdr2Off + 20);
    }

    return result;
}