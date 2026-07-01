#ifndef STUDIO_TYPES_H
#define STUDIO_TYPES_H

#include <cstdint>
#include <string>
#include <vector>

#pragma pack(push, 1)

// ===================== MDL (StudioMDL) Structures =====================

constexpr int STUDIO_HEADER_SIZE = 348;
constexpr int BODYPART_SIZE = 16;
constexpr int MODEL_SIZE = 148;
constexpr int MESH_SIZE = 116;
constexpr int BONE_SIZE = 216;
constexpr int EYEBALL_SIZE = 324;
constexpr int TEXTURE_ENTRY_SIZE = 24;
constexpr uint32_t MDL_MAGIC = 0x54534449; // "IDST" (little-endian)

struct StudioHeader {
    int32_t id;
    int32_t version;
    int32_t checksum;
    char    name[64];
    int32_t dataLength;
    float   eyeposition[3];
    float   illumposition[3];
    float   hull_min[3];
    float   hull_max[3];
    float   view_bbmin[3];
    float   view_bbmax[3];
    int32_t flags;
    int32_t numbones;
    int32_t boneindex;
    int32_t numbonecontrollers;
    int32_t bonecontrollerindex;
    int32_t numhitboxsets;
    int32_t hitboxsetindex;
    int32_t numlocalanim;
    int32_t localanimindex;
    int32_t numlocalseq;
    int32_t localseqindex;
    int32_t activitylistversion;
    int32_t eventsindexed;
    int32_t numtextures;
    int32_t textureindex;
    int32_t numcdtextures;
    int32_t cdtextureindex;
    int32_t numskinref;
    int32_t numskinfamilies;
    int32_t skinindex;
    int32_t numbodyparts;
    int32_t bodypartindex;
    int32_t numlocalattachments;
    int32_t localattachmentindex;
    int32_t numlocalnodes;
    int32_t localnodeindex;
    int32_t localnodenameindex;
    int32_t numflexdesc;
    int32_t flexdescindex;
    int32_t numflexcontrollers;
    int32_t flexcontrollerindex;
    int32_t numflexrules;
    int32_t flexruleindex;
    int32_t numikchains;
    int32_t ikchainindex;
    int32_t nummouths;
    int32_t mouthindex;
    int32_t numlocalposeparameters;
    int32_t localposeparamindex;
    int32_t surfacepropindex;
    int32_t keyvalueindex;
    int32_t keyvaluesize;
    int32_t numlocalikautoplaylocks;
    int32_t localikautoplaylockindex;
    float   mass;
    int32_t contents;
    int32_t numincludemodels;
    int32_t includemodelindex;
    int32_t virtualModel;
    int32_t szanimblocknameindex;
    int32_t numanimblocks;
    int32_t animblockindex;
    int32_t animblockModel;
    int32_t bonetablenameindex;
    int32_t vertexbase;
    int32_t offsetbase;
    int8_t  directionaldotproduct;
    int8_t  rootLod;
    int8_t  numAllowedRootLods;
    int8_t  unused;
    int32_t flexcontrolleruiindex;
    float   vertAnimFixedPointScale;
    int32_t unused3;
    int32_t studiohdr2index;
};

struct StudioBodyPart {
    int32_t sznameindex;
    int32_t nummodels;
    int32_t baseIndex;
    int32_t modelindex;
};

struct StudioModel {
    char    name[64];
    int32_t type;
    float   boundingradius;
    int32_t nummeshes;
    int32_t meshindex;
    int32_t numvertices;
    int32_t vertexindex;
    int32_t tangentsindex;
    int32_t numattachments;
    int32_t attachmentindex;
    int32_t numeyeballs;
    int32_t eyeballindex;
    int32_t unused[2];
};

struct StudioMesh {
    int32_t material;
    int32_t modelindex;
    int32_t numvertices;
    int32_t vertexoffset;
    int32_t numflexes;
    int32_t flexindex;
    int32_t materialtype;
    int32_t materialparam;
    int32_t meshid;
    float   center[3];
    int32_t unused[8];
    int32_t extra[9];
};

struct StudioBone {
    int32_t sznameindex;
    int32_t parent;
    int32_t bonecontroller[6];
    float   pos[3];
    float   quat[4];
    float   rot[3];
    float   posscale[3];
    float   rotscale[3];
    float   poseToBone[12];
    float   qAlignment[4];
    int32_t flags;
    int32_t proctype;
    int32_t procindex;
    int32_t physicsbone;
    int32_t surfacepropidx;
    int32_t contents;
    int32_t unused[8];
};

struct StudioEyeball {
    int32_t sznameindex;
    int32_t bone;
    float   org[3];
    float   zoffset;
    float   radius;
    float   up[3];
    float   forward[3];
    int32_t irisMaterial;
    int32_t upperFlexDesc;
    int32_t lowerFlexDesc;
    int32_t upperTarget;
    int32_t lowerTarget;
    int32_t upperLidFlexDesc;
    int32_t lowerLidFlexDesc;
    int32_t unused[4];
    int8_t  eyelidFlexDesc[4];
    int32_t unused2[28];
};

struct StudioTexture {
    int32_t nameOffset;
    int32_t flags;
    int32_t width;
    int32_t height;
    int32_t viewportX;
    int32_t viewportY;
};

// ===================== VVD Structures =====================

constexpr uint32_t VVD_MAGIC = 0x56534449; // "IDSV"
constexpr int VVD_HEADER_SIZE = 64;
constexpr int VVD_VERTEX_SIZE = 48;
constexpr int FIXUP_SIZE = 12;

struct VvdHeader {
    int32_t id;
    int32_t version;
    int32_t checksum;
    int32_t numLODs;
    int32_t numLODVertices[8];
    int32_t numFixups;
    int32_t fixupTableStart;
    int32_t vertexDataStart;
    int32_t tangentDataStart;
};

struct VvdFixup {
    int32_t lodIndex;
    int32_t sourceVertexID;
    int32_t numVertexes;
};

struct StudioVertexExt {
    float   boneWeight[3];
    uint8_t boneId[3];
    uint8_t numBones;
    float   x, y, z;
    float   nx, ny, nz;
    float   u, v;
};

// ===================== VTX Structures =====================

constexpr int VTX_VERTEX_SIZE = 9;

struct VtxVertex {
    uint8_t boneWeightIndex[3];
    uint8_t numBones;
    uint16_t origMeshVertID;
    uint8_t boneId[3];
};

// ===================== Mesh Data (built from MDL+VVD+VTX) =====================

struct MeshVertex {
    float x, y, z;
    float nx, ny, nz;
    float u, v;
};

struct MeshData {
    std::vector<MeshVertex> vertices;
    std::vector<uint32_t> indices;
    uint32_t glVao = 0;
    uint32_t glVbo = 0;
    uint32_t glEbo = 0;
    int indexCount = 0;
    uint32_t textureId = 0;
    std::string textureName;
};

#pragma pack(pop)

#endif // STUDIO_TYPES_H
