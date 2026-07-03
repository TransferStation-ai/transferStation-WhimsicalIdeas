#include "vtx_parser.h"
#include <cstring>
#include <stdexcept>
#include <algorithm>

constexpr int VTX_MESH_HEADER_SIZE = 9;
constexpr int VTX_STRIP_GROUP_HEADER_SIZE = 25;
constexpr int VTX_STRIP_HEADER_SIZE = 27;
constexpr int VTX_STRIP_FLAGS_OFFSET = 18;

// Try relative offset first (standard Source VTX convention), fall back to absolute
static int resolveOffset(int baseAddr, int relOffset, size_t dataSize, size_t bufferSize) {
    int relative = baseAddr + relOffset;
    if (relOffset >= 0 && relative >= 0 && static_cast<size_t>(relative) + dataSize <= bufferSize) {
        return relative;
    }
    // Fallback: try absolute file offset
    if (relOffset >= 0 && static_cast<size_t>(relOffset) + dataSize <= bufferSize) {
        return relOffset;
    }
    return -1;
}

VtxParser::ParsedVtx VtxParser::parse(const std::vector<uint8_t>& data) {
    ParsedVtx result;
    const uint8_t* buf = data.data();
    size_t size = data.size();

    if (size < 36) throw std::runtime_error("VTX file too small");

    int offset = 0;
    int32_t version = *reinterpret_cast<const int32_t*>(buf + offset); offset += 4;
    offset += 4;  // vertCacheSize
    offset += 2;  // maxBonesPerStrip
    offset += 2;  // maxBonesPerTri
    offset += 4;  // maxBonesPerVert
    int32_t checksum = *reinterpret_cast<const int32_t*>(buf + offset); offset += 4;
    int32_t numLODs = *reinterpret_cast<const int32_t*>(buf + offset); offset += 4;
    offset += 4;  // materialReplacementListOffset
    int32_t numBodyParts = *reinterpret_cast<const int32_t*>(buf + offset); offset += 4;
    int32_t bodyPartOffset = *reinterpret_cast<const int32_t*>(buf + offset);

    result.version = version;
    result.checksum = checksum;
    result.numLODs = numLODs;
    result.numBodyParts = numBodyParts;

    const int fileBase = 0;
    int bodyPartAddr = fileBase + bodyPartOffset;

    for (int bp = 0; bp < numBodyParts; bp++) {
        int bpAddr = bodyPartAddr + bp * 8;
        if (bpAddr + 8 > static_cast<int>(size)) break;

        int numModels = *reinterpret_cast<const int32_t*>(buf + bpAddr);
        int modelOffset = *reinterpret_cast<const int32_t*>(buf + bpAddr + 4);
        int modelAddr = bpAddr + modelOffset;

        for (int m = 0; m < numModels; m++) {
            int mAddr = modelAddr + m * 8;
            if (mAddr + 8 > static_cast<int>(size)) break;

            int numLOD = *reinterpret_cast<const int32_t*>(buf + mAddr);
            int lodOffset = *reinterpret_cast<const int32_t*>(buf + mAddr + 4);
            int lodAddr = mAddr + lodOffset;

            int numLODsToProcess = std::min(std::max(numLOD, 1), 4);
            for (int l = 0; l < numLODsToProcess; l++) {
                int lAddr = lodAddr + l * 8;
                if (lAddr + 8 > static_cast<int>(size)) break;

                int numMeshes = *reinterpret_cast<const int32_t*>(buf + lAddr);
                int meshOffset = *reinterpret_cast<const int32_t*>(buf + lAddr + 4);
                int meshAddr = lAddr + meshOffset;

                std::vector<StripGroupInfo> meshStripGroups;

                for (int meshIdx = 0; meshIdx < numMeshes; meshIdx++) {
                    int meshHdrAddr = meshAddr + meshIdx * VTX_MESH_HEADER_SIZE;
                    if (meshHdrAddr + VTX_MESH_HEADER_SIZE > static_cast<int>(size)) {
                        meshStripGroups.push_back({});
                        continue;
                    }

                    int numStripGroups = *reinterpret_cast<const int32_t*>(buf + meshHdrAddr);
                    int sgOffset = *reinterpret_cast<const int32_t*>(buf + meshHdrAddr + 4);

                    if (sgOffset == 0 || numStripGroups <= 0) {
                        meshStripGroups.push_back({});
                        continue;
                    }

                    int sgAddr = meshHdrAddr + sgOffset;
                    StripGroupInfo groupInfo;
                    int stripGroupLimit = std::min(numStripGroups, 256);

                    for (int sg = 0; sg < stripGroupLimit; sg++) {
                        int sgHdrAddr = sgAddr + sg * VTX_STRIP_GROUP_HEADER_SIZE;
                        if (sgHdrAddr + VTX_STRIP_GROUP_HEADER_SIZE > static_cast<int>(size)) break;

                        int numVerts = *reinterpret_cast<const int32_t*>(buf + sgHdrAddr);
                        int vertOff = *reinterpret_cast<const int32_t*>(buf + sgHdrAddr + 4);
                        int numIndices = *reinterpret_cast<const int32_t*>(buf + sgHdrAddr + 8);
                        int idxOff = *reinterpret_cast<const int32_t*>(buf + sgHdrAddr + 12);
                        int numStrips = *reinterpret_cast<const int32_t*>(buf + sgHdrAddr + 16);
                        int stripOff = *reinterpret_cast<const int32_t*>(buf + sgHdrAddr + 20);

                        if (numVerts <= 0 || numIndices < 3) continue;

                        // Resolve vertex data address
                        int vertDataAddr = resolveOffset(sgHdrAddr, vertOff,
                            static_cast<size_t>(numVerts) * VTX_VERTEX_SIZE, size);
                        if (vertDataAddr < 0) continue;

                        // Resolve index data address
                        int indexDataAddr = resolveOffset(sgHdrAddr, idxOff,
                            static_cast<size_t>(numIndices) * sizeof(uint16_t), size);
                        if (indexDataAddr < 0) continue;

                        // Read vertices
                        for (int vi = 0; vi < numVerts; vi++) {
                            VtxVertex vtxV;
                            memcpy(&vtxV, buf + vertDataAddr + vi * VTX_VERTEX_SIZE, VTX_VERTEX_SIZE);
                            groupInfo.vertices.push_back(vtxV);
                        }

                        // Read global indices (cache indices) - use uint32_t for >65535 vertex support
                        int maxIndices = std::min(numIndices, 262144);
                        std::vector<uint32_t> cacheIndices(maxIndices);
                        for (int ii = 0; ii < maxIndices; ii++) {
                            cacheIndices[ii] = *reinterpret_cast<const uint16_t*>(buf + indexDataAddr + ii * 2);
                        }

                        // Resolve strip headers
                        int stripHeadersAddr = resolveOffset(sgHdrAddr, stripOff,
                            static_cast<size_t>(numStrips) * VTX_STRIP_HEADER_SIZE, size);
                        if (stripHeadersAddr < 0) continue;

                        int stripLimit = std::min(numStrips, 256);
                        for (int s = 0; s < stripLimit; s++) {
                            int sAddr = stripHeadersAddr + s * VTX_STRIP_HEADER_SIZE;
                            if (sAddr + VTX_STRIP_HEADER_SIZE > static_cast<int>(size)) break;

                            int sNumIndices = *reinterpret_cast<const int32_t*>(buf + sAddr);
                            int sIndexOffset = *reinterpret_cast<const int32_t*>(buf + sAddr + 4);
                            int sFlags = buf[sAddr + VTX_STRIP_FLAGS_OFFSET];

                            if (sNumIndices < 3) continue;
                            if (sIndexOffset < 0 || sIndexOffset + sNumIndices > maxIndices) continue;

                            bool isTriList = (sFlags & 0x01) != 0;
                            StripGroupInfo::Strip strip;
                            strip.isTriList = isTriList;
                            int triEnd = isTriList ? sNumIndices : sNumIndices - 2;
                            int step = isTriList ? 3 : 1;

                            for (int i = 0; i + 2 < sNumIndices; i += step) {
                                uint32_t ci0, ci1, ci2;
                                if (isTriList || (i & 1) == 0) {
                                    ci0 = cacheIndices[sIndexOffset + i];
                                    ci1 = cacheIndices[sIndexOffset + i + 1];
                                    ci2 = cacheIndices[sIndexOffset + i + 2];
                                } else {
                                    ci0 = cacheIndices[sIndexOffset + i + 1];
                                    ci1 = cacheIndices[sIndexOffset + i];
                                    ci2 = cacheIndices[sIndexOffset + i + 2];
                                }
                                if (ci0 >= static_cast<uint32_t>(numVerts) || ci1 >= static_cast<uint32_t>(numVerts) || ci2 >= static_cast<uint32_t>(numVerts)) continue;
                                strip.indices.push_back(ci0);
                                strip.indices.push_back(ci1);
                                strip.indices.push_back(ci2);
                            }

                            if (!strip.indices.empty()) {
                                groupInfo.strips.push_back(std::move(strip));
                            }
                        }
                    }

                    meshStripGroups.push_back(std::move(groupInfo));
                }

                if (l == 0) {
                    result.meshStripGroups.push_back(std::move(meshStripGroups));
                } else {
                    auto target = l == 1 ? &result.lodMeshStripGroups1
                               : l == 2 ? &result.lodMeshStripGroups2
                               : l == 3 ? &result.lodMeshStripGroups3
                               : nullptr;
                    if (target) target->push_back(std::move(meshStripGroups));
                }
            }
        }
    }

    return result;
}

const std::vector<std::vector<VtxParser::StripGroupInfo>>& VtxParser::getStripGroupsForLod(
    const ParsedVtx& vtx, int lodLevel)
{
    if (lodLevel <= 0) return vtx.meshStripGroups;
    if (lodLevel == 1 && !vtx.lodMeshStripGroups1.empty()) return vtx.lodMeshStripGroups1;
    if (lodLevel == 2 && !vtx.lodMeshStripGroups2.empty()) return vtx.lodMeshStripGroups2;
    if (lodLevel == 3 && !vtx.lodMeshStripGroups3.empty()) return vtx.lodMeshStripGroups3;
    return vtx.meshStripGroups;
}
