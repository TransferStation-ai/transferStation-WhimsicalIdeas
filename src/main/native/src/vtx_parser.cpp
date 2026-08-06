#include "vtx_parser.h"
#include <cstring>
#include <stdexcept>
#include <algorithm>

constexpr int VTX_MESH_HEADER_SIZE_V2 = 8;
constexpr int VTX_MESH_HEADER_SIZE_V7 = 9;
constexpr int VTX_STRIP_GROUP_HEADER_SIZE = 25;
constexpr int VTX_STRIP_HEADER_SIZE = 27;
constexpr int VTX_STRIP_FLAGS_OFFSET = 18;

// Safe unaligned reads (avoids undefined behavior from reinterpret_cast on unaligned data)
static inline int32_t readInt32(const uint8_t* buf, int offset) {
    int32_t v;
    memcpy(&v, buf + offset, sizeof(int32_t));
    return v;
}

static inline uint16_t readUint16(const uint8_t* buf, int offset) {
    uint16_t v;
    memcpy(&v, buf + offset, sizeof(uint16_t));
    return v;
}

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
    int32_t version = readInt32(buf, offset); offset += 4;
    offset += 4;  // vertCacheSize
    offset += 2;  // maxBonesPerStrip
    offset += 2;  // maxBonesPerTri
    offset += 4;  // maxBonesPerVert
    int32_t checksum = readInt32(buf, offset); offset += 4;
    int32_t numLODs = readInt32(buf, offset); offset += 4;
    offset += 4;  // materialReplacementListOffset
    int32_t numBodyParts = readInt32(buf, offset); offset += 4;
    int32_t bodyPartOffset = readInt32(buf, offset);

    int meshHeaderSize = (version >= 7) ? VTX_MESH_HEADER_SIZE_V7 : VTX_MESH_HEADER_SIZE_V2;

    result.version = version;
    result.checksum = checksum;
    result.numLODs = numLODs;
    result.numBodyParts = numBodyParts;

    const int fileBase = 0;
    int bodyPartAddr = fileBase + bodyPartOffset;

    for (int bp = 0; bp < numBodyParts; bp++) {
        int bpAddr = bodyPartAddr + bp * 8;
        if (bpAddr + 8 > static_cast<int>(size)) break;

        int numModels = readInt32(buf, bpAddr);
        int modelOffset = readInt32(buf, bpAddr + 4);
        int modelAddr = bpAddr + modelOffset;

        if (numModels < 0 || numModels > 128) continue;

        for (int m = 0; m < numModels; m++) {
            int mAddr = modelAddr + m * 8;
            if (mAddr + 8 > static_cast<int>(size)) break;

            int numLOD = readInt32(buf, mAddr);
            int lodOffset = readInt32(buf, mAddr + 4);
            int lodAddr = mAddr + lodOffset;

            if (numLOD < 0 || numLOD > 8) continue;

            int numLODsToProcess = std::min(std::max(numLOD, 1), 4);
            for (int l = 0; l < numLODsToProcess; l++) {
                int lAddr = lodAddr + l * 8;
                if (lAddr + 8 > static_cast<int>(size)) break;

                int numMeshes = readInt32(buf, lAddr);
                int meshOffset = readInt32(buf, lAddr + 4);
                int meshAddr = lAddr + meshOffset;

                if (numMeshes < 0 || numMeshes > 4096) continue;

                std::vector<StripGroupInfo> meshStripGroups;

                for (int meshIdx = 0; meshIdx < numMeshes; meshIdx++) {
                    int meshHdrAddr = meshAddr + meshIdx * meshHeaderSize;
                    if (meshHdrAddr + meshHeaderSize > static_cast<int>(size)) {
                        meshStripGroups.push_back({});
                        continue;
                    }

                    int numStripGroups = readInt32(buf, meshHdrAddr);
                    int sgOffset = readInt32(buf, meshHdrAddr + 4);

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

                        int numVerts = readInt32(buf, sgHdrAddr);
                        int vertOff = readInt32(buf, sgHdrAddr + 4);
                        int numIndices = readInt32(buf, sgHdrAddr + 8);
                        int idxOff = readInt32(buf, sgHdrAddr + 12);
                        int numStrips = readInt32(buf, sgHdrAddr + 16);
                        int stripOff = readInt32(buf, sgHdrAddr + 20);

                        if (numVerts <= 0 || numIndices < 3) continue;
                        if (numStrips < 0 || numStrips > 256) continue;

                        // Cap numVerts to avoid a giant allocation (DoS) and out-of-bounds reads.
                        int maxVerts = static_cast<int>(4'000'000 / VTX_VERTEX_SIZE);
                        if (numVerts > maxVerts) numVerts = maxVerts;

                        // Resolve vertex data address
                        int vertDataAddr = resolveOffset(sgHdrAddr, vertOff,
                            static_cast<size_t>(numVerts) * VTX_VERTEX_SIZE, size);
                        if (vertDataAddr < 0) continue;

                        // Re-validate the full vertex block fits before reading.
                        if (static_cast<size_t>(vertDataAddr) + static_cast<size_t>(numVerts) * VTX_VERTEX_SIZE > size) continue;

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
                            cacheIndices[ii] = readUint16(buf, indexDataAddr + ii * 2);
                        }

                        // Resolve strip headers
                        int stripHeadersAddr = resolveOffset(sgHdrAddr, stripOff,
                            static_cast<size_t>(numStrips) * VTX_STRIP_HEADER_SIZE, size);
                        if (stripHeadersAddr < 0) continue;

                        int stripLimit = std::min(numStrips, 256);
                        for (int s = 0; s < stripLimit; s++) {
                            int sAddr = stripHeadersAddr + s * VTX_STRIP_HEADER_SIZE;
                            if (sAddr + VTX_STRIP_HEADER_SIZE > static_cast<int>(size)) break;

                            int sNumIndices = readInt32(buf, sAddr);
                            int sIndexOffset = readInt32(buf, sAddr + 4);
                            int sFlags = buf[sAddr + VTX_STRIP_FLAGS_OFFSET];

                            if (sNumIndices < 3) continue;
                            // The triangle assembly loop below accesses cacheIndices up to
                            // sIndexOffset + sNumIndices - 1 (last triangle window starts at
                            // i + 2 == sNumIndices - 1), so the buffer must hold up to
                            // sIndexOffset + sNumIndices entries. This mirrors the Java
                            // parser check (sIndexOffset + sNumIndices > maxIndices). Some
                            // files (e.g. pm) have strips that cover the entire index buffer
                            // (sIndexOffset == 0, sNumIndices == numIndices); the previous
                            // "+1" guard falsely rejected all of them.
                            if (sIndexOffset < 0 || sIndexOffset + sNumIndices > maxIndices) continue;

                            bool isTriList = (sFlags & 0x01) != 0;
                            StripGroupInfo::Strip strip;
                            strip.isTriList = isTriList;
                            // D3D strip restart marker: an index equal to this value ends the
                            // current strip segment and starts a new one. Mirror the Java
                            // VtxParser (STRIP_RESTART_INDEX = 0xFFFF): without detecting it,
                            // the sliding window crosses the restart boundary and emits huge
                            // diagonal triangles that connect far-apart vertices (visible as
                            // random lines spanning the model surface).
                            static constexpr uint16_t STRIP_RESTART_INDEX = 0xFFFF;

                            if (isTriList) {
                                for (int i = 0; i + 2 < sNumIndices; i += 3) {
                                    uint32_t ci0 = cacheIndices[sIndexOffset + i];
                                    uint32_t ci1 = cacheIndices[sIndexOffset + i + 1];
                                    uint32_t ci2 = cacheIndices[sIndexOffset + i + 2];
                                    // Skip restart markers and degenerate triangles
                                    if (ci0 == STRIP_RESTART_INDEX || ci1 == STRIP_RESTART_INDEX || ci2 == STRIP_RESTART_INDEX) continue;
                                    if (ci0 == ci1 || ci1 == ci2 || ci0 == ci2) continue;
                                    if (ci0 >= static_cast<uint32_t>(numVerts) || ci1 >= static_cast<uint32_t>(numVerts) || ci2 >= static_cast<uint32_t>(numVerts)) continue;
                                    strip.indices.push_back(ci0);
                                    strip.indices.push_back(ci1);
                                    strip.indices.push_back(ci2);
                                }
                            } else {
                                // Triangle strip: sliding window of 3 advancing by 1 each step,
                                // with alternating winding and D3D restart (0xFFFF) handling.
                                // Winding parity is keyed on a strip-relative counter (stripRel)
                                // that resets to 0 after a restart marker, so the new strip's
                                // first triangle always uses even winding regardless of where the
                                // restart occurred. stripRel increments every iteration (including
                                // degenerate skips, which are part of the strip's vertex stream)
                                // and resets to -1 on restart (loop increment makes it 0 = even).
                                for (int i = 0, stripRel = 0; i + 2 < sNumIndices; i++, stripRel++) {
                                    uint32_t ci0 = cacheIndices[sIndexOffset + i];
                                    uint32_t ci1 = cacheIndices[sIndexOffset + i + 1];
                                    uint32_t ci2 = cacheIndices[sIndexOffset + i + 2];

                                    // Handle strip restart marker: advance past it and reset the
                                    // strip-relative counter so the new strip starts with even winding.
                                    if (ci0 == STRIP_RESTART_INDEX || ci1 == STRIP_RESTART_INDEX || ci2 == STRIP_RESTART_INDEX) {
                                        int advance = (ci0 == STRIP_RESTART_INDEX) ? 1
                                                    : (ci1 == STRIP_RESTART_INDEX) ? 2 : 3;
                                        i += advance - 1; // -1 because loop increments
                                        stripRel = -1;    // loop increments to 0 -> even for new strip
                                        continue;
                                    }

                                    // Skip degenerate triangles (zero-area connectors). stripRel still
                                    // increments (they are part of the strip's vertex stream), preserving
                                    // the alternating parity for subsequent non-degenerate triangles.
                                    if (ci0 == ci1 || ci1 == ci2 || ci0 == ci2) continue;

                                    // Alternating winding keyed on the strip-relative counter:
                                    // even stripRel -> (ci0, ci1, ci2), odd stripRel -> (ci1, ci0, ci2).
                                    uint32_t tri0, tri1, tri2;
                                    if ((stripRel & 1) == 0) {
                                        tri0 = ci0; tri1 = ci1;
                                    } else {
                                        tri0 = ci1; tri1 = ci0;
                                    }
                                    tri2 = ci2;

                                    if (tri0 >= static_cast<uint32_t>(numVerts) || tri1 >= static_cast<uint32_t>(numVerts) || tri2 >= static_cast<uint32_t>(numVerts)) continue;
                                    strip.indices.push_back(tri0);
                                    strip.indices.push_back(tri1);
                                    strip.indices.push_back(tri2);
                                }
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
                    // Ensure lodMeshStripGroups has enough slots
                    while (static_cast<int>(result.lodMeshStripGroups.size()) < l) {
                        result.lodMeshStripGroups.emplace_back();
                    }
                    result.lodMeshStripGroups[l - 1].push_back(std::move(meshStripGroups));
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
    if (lodLevel - 1 < static_cast<int>(vtx.lodMeshStripGroups.size()) && 
        !vtx.lodMeshStripGroups[lodLevel - 1].empty()) {
        return vtx.lodMeshStripGroups[lodLevel - 1];
    }
    return vtx.meshStripGroups;
}
