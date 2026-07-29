#include "phy_parser.h"
#include <cstring>
#include <stdexcept>
#include <algorithm>
#include <cstdint>

static const int MAX_SOLIDS = 256;
static const uint32_t MAX_FILE_SIZE = 32 * 1024 * 1024;

static inline int32_t readIntLE(const uint8_t* data, int off) {
    if (off < 0 || off + 3 < 0) return 0;
    return (int32_t)(data[off] | (data[off+1] << 8) | (data[off+2] << 16) | (data[off+3] << 24));
}

static inline int16_t readShortLE(const uint8_t* data, int off) {
    if (off < 0 || off + 1 < 0) return 0;
    return (int16_t)(data[off] | (data[off+1] << 8));
}

static inline float readFloatLE(const uint8_t* data, int off) {
    float v = 0.0f;
    if (off >= 0) std::memcpy(&v, data + off, sizeof(float));
    return v;
}

static inline uint8_t readByte(const uint8_t* data, int off) {
    if (off < 0) return 0;
    return data[off];
}

static std::string readFixedString(const uint8_t* data, int off, int length, size_t dataSize) {
    int maxLen = 0;
    while (maxLen < length && off + maxLen >= 0 && off + maxLen < static_cast<int>(dataSize) && data[off + maxLen] != 0) maxLen++;
    return std::string(reinterpret_cast<const char*>(data + off), maxLen);
}

struct ConvexHdr {
    int32_t vertexOffset;
    int32_t boneIndex;
    int32_t flags;
    int32_t triCount;
    int headerStartRel;
};

static void buildConvexHulls(
    const uint8_t* data, int dataSize,
    int absoluteStart, int size, int binaryEnd,
    PhyParser::PhySolid& solid,
    const std::vector<ConvexHdr>& headers)
{
    int end = std::min(absoluteStart + size, binaryEnd);

    for (size_t ci = 0; ci < headers.size(); ci++) {
        const auto& hdr = headers[ci];

        PhyParser::PhyConvexHull hull;
        hull.vertexOffset = hdr.vertexOffset;
        hull.boneIndex = hdr.boneIndex;
        hull.flags = hdr.flags;
        hull.triangleCount = hdr.triCount;

        int triStartRel = hdr.headerStartRel + 16;
        int triEndRel = hdr.vertexOffset;

        if (ci + 1 < headers.size()) {
            const auto& next = headers[ci + 1];
            int nextHdrStart = next.headerStartRel;
            if (nextHdrStart > hdr.headerStartRel && nextHdrStart < triEndRel)
                triEndRel = nextHdrStart;
            int nextVertStart = next.vertexOffset;
            if (nextVertStart > hdr.headerStartRel && nextVertStart < triEndRel)
                triEndRel = nextVertStart;
        }

        if (triEndRel > size) triEndRel = size;
        if (triStartRel < 0) triStartRel = 0;

        int maxTris = std::max(0, (triEndRel - triStartRel) / 16);
        int trisToRead = std::min(hdr.triCount, maxTris);

        for (int t = 0; t < trisToRead; t++) {
            int triOff = absoluteStart + triStartRel + t * 16;
            if (triOff + 16 > end) break;

            PhyParser::PhyTriangle tri;
            tri.vertexIndex = readByte(data, triOff);
            tri.v1 = readShortLE(data, triOff + 6);
            tri.v2 = readShortLE(data, triOff + 10);
            tri.v3 = readShortLE(data, triOff + 14);
            hull.triangles.push_back(tri);
        }

        int vertStartRel = hdr.vertexOffset;
        if (vertStartRel > 0 && vertStartRel < size) {
            int vertEndRel = size;
            if (ci + 1 < headers.size()) {
                int nextVS = headers[ci + 1].vertexOffset;
                if (nextVS > vertStartRel && nextVS < vertEndRel)
                    vertEndRel = nextVS;
            }
            int numVerts = std::max(0, (vertEndRel - vertStartRel) / 16);
            for (int v = 0; v < numVerts; v++) {
                int vo = absoluteStart + vertStartRel + v * 16;
                if (vo + 12 > end) break;
                PhyParser::PhyVertex vert;
                vert.x = readFloatLE(data, vo);
                vert.y = readFloatLE(data, vo + 4);
                vert.z = readFloatLE(data, vo + 8);
                hull.vertices.push_back(vert);
            }
        }

        solid.convexHulls.push_back(std::move(hull));
    }
}

static void parseConvexHullsDirect(
    const uint8_t* data, int dataSize,
    int absoluteStart, int size, int binaryEnd,
    PhyParser::PhySolid& solid)
{
    int end = std::min(absoluteStart + size, binaryEnd);
    std::vector<ConvexHdr> headers;
    int pos = absoluteStart;

    while (pos + 16 <= end) {
        int relVertOffset = readIntLE(data, pos);
        int boneIdx = readIntLE(data, pos + 4);
        int flags = readIntLE(data, pos + 8);
        int triCount = readIntLE(data, pos + 12);

        if (relVertOffset < 0 || relVertOffset > size) break;
        if (triCount < 0 || triCount > 65536) break;
        if (relVertOffset <= (pos - absoluteStart) + 16 && relVertOffset > 0) break;

        ConvexHdr hdr;
        hdr.vertexOffset = relVertOffset;
        hdr.boneIndex = boneIdx;
        hdr.flags = flags;
        hdr.triCount = triCount;
        hdr.headerStartRel = pos - absoluteStart;
        headers.push_back(hdr);
        pos += 16;
    }

    if (headers.empty()) return;
    buildConvexHulls(data, dataSize, absoluteStart, size, binaryEnd, solid, headers);
}

static void parseConvexHullsHeuristic(
    const uint8_t* data, int dataSize,
    int absoluteStart, int size, int binaryEnd,
    PhyParser::PhySolid& solid)
{
    int end = std::min(absoluteStart + size, binaryEnd);
    int startScan = absoluteStart + 0x130;
    if (startScan >= end - 16) return;

    std::vector<ConvexHdr> headers;
    int pos = startScan;

    while (pos + 16 <= end) {
        int relVertOffset = readIntLE(data, pos);
        int boneIdx = readIntLE(data, pos + 4);
        int flags = readIntLE(data, pos + 8);
        int triCount = readIntLE(data, pos + 12);

        if (relVertOffset <= 0 || relVertOffset >= size) { pos += 16; continue; }
        if (triCount <= 0 || triCount > 2048) { pos += 16; continue; }
        if (relVertOffset <= (pos - absoluteStart)) { pos += 16; continue; }

        int vertCheck = absoluteStart + relVertOffset;
        if (vertCheck + 12 > end) { pos += 16; continue; }

        float vx = readFloatLE(data, vertCheck);
        float vy = readFloatLE(data, vertCheck + 4);
        float vz = readFloatLE(data, vertCheck + 8);

        if (std::abs(vx) > 10000.0f || std::abs(vy) > 10000.0f || std::abs(vz) > 10000.0f) {
            pos += 16;
            continue;
        }

        ConvexHdr hdr;
        hdr.vertexOffset = relVertOffset;
        hdr.boneIndex = boneIdx;
        hdr.flags = flags;
        hdr.triCount = triCount;
        hdr.headerStartRel = pos - absoluteStart;
        headers.push_back(hdr);
        pos += 16;
    }

    if (!headers.empty()) {
        buildConvexHulls(data, dataSize, absoluteStart, size, binaryEnd, solid, headers);
    }
}

static void parseSurfaceData(
    const uint8_t* data, int dataSize,
    int absoluteStart, int size, int binaryEnd,
    PhyParser::PhySolid& solid, int solidIdx)
{
    (void)solidIdx;
    if (size < 16) return;

    bool hasIvps = false;
    if (size >= 0x34) {
        int magic = readIntLE(data, absoluteStart + 0x30);
        hasIvps = (magic == 0x53505649);
    }

    if (hasIvps) {
        parseConvexHullsHeuristic(data, dataSize, absoluteStart, size, binaryEnd, solid);
    } else {
        parseConvexHullsDirect(data, dataSize, absoluteStart, size, binaryEnd, solid);
    }
}

static int findKvStart(const uint8_t* data, int dataSize) {
    int searchStart = std::max(0, dataSize - static_cast<int>(dataSize * 0.35));
    for (int i = searchStart; i < dataSize - 5; i++) {
        if (data[i] == 's' && data[i+1] == 'o' && data[i+2] == 'l' &&
            data[i+3] == 'i' && data[i+4] == 'd' && data[i+5] == ' ') {
            return i;
        }
    }
    return 0;
}

static void parseSolidNames(const uint8_t* data, int kvStart, int kvEnd,
                            std::vector<PhyParser::PhySolid>& solids) {
    std::string kvText(reinterpret_cast<const char*>(data) + kvStart, kvEnd - kvStart);
    size_t idx = 0;
    while (idx < kvText.size()) {
        size_t blockStart = kvText.find("solid", idx);
        if (blockStart == std::string::npos) break;
        size_t braceOpen = kvText.find('{', blockStart);
        if (braceOpen == std::string::npos) break;
        size_t braceClose = kvText.find('}', braceOpen);
        if (braceClose == std::string::npos) break;

        std::string block = kvText.substr(braceOpen + 1, braceClose - braceOpen - 1);
        int solidIndex = -1;
        std::string solidName;

        size_t propIdx = 0;
        while (true) {
            size_t q1 = block.find('"', propIdx); if (q1 == std::string::npos) break;
            size_t q2 = block.find('"', q1 + 1); if (q2 == std::string::npos) break;
            size_t q3 = block.find('"', q2 + 1); if (q3 == std::string::npos) break;
            size_t q4 = block.find('"', q3 + 1); if (q4 == std::string::npos) break;

            std::string key = block.substr(q1 + 1, q2 - q1 - 1);
            std::string value = block.substr(q3 + 1, q4 - q3 - 1);

            if (key == "index") {
                try { solidIndex = std::stoi(value); } catch (...) {}
            } else if (key == "name") {
                solidName = value;
            }
            propIdx = q4 + 1;
        }

        if (solidIndex >= 0 && !solidName.empty() && solidIndex < static_cast<int>(solids.size())) {
            solids[solidIndex].name = solidName;
        }
        idx = braceClose + 1;
    }
}

PhyParser::ParsedPhy PhyParser::parse(const std::vector<uint8_t>& data) {
    ParsedPhy result;
    result.valid = false;

    if (data.size() > MAX_FILE_SIZE) {
        return result;
    }
    if (data.size() < 16) {
        return result;
    }

    try {
        const uint8_t* raw = data.data();
        int fileLen = static_cast<int>(data.size());

        result.size = readIntLE(raw, 0);
        result.id = readFixedString(raw, 4, 4, data.size());
        result.solidCount = readIntLE(raw, 8);
        result.checksum = readIntLE(raw, 12);

        bool altOffset = false;
        if (result.id != "VPHY" && result.id != "PHYS") {
            if (fileLen >= 20) {
                int altMagic = readIntLE(raw, 16);
                if (altMagic == 0x59504856 || altMagic == 0x53594850) {
                    altOffset = true;
                    result.id = "VPHY";
                }
            }
        }

        bool tryParse = (result.id == "VPHY" || result.id == "PHYS") ||
            (result.solidCount > 0 && result.solidCount <= MAX_SOLIDS);
        if (!tryParse || result.solidCount < 0 || result.solidCount > MAX_SOLIDS) {
            result.valid = true;
            return result;
        }

        int kvStart = findKvStart(raw, fileLen);
        int binaryEnd = (kvStart > 0) ? kvStart : fileLen;
        int cursor = altOffset ? 16 : 16;

        result.solids.resize(result.solidCount);

        for (int i = 0; i < result.solidCount; i++) {
            if (cursor + 28 > binaryEnd) break;

            int sectionOrigin = cursor;
            int sectionSize = readIntLE(raw, cursor);
            if (sectionSize <= 0 || 4 + sectionSize > binaryEnd - cursor) break;

            std::string vphysicsId;
            if (cursor + 8 <= binaryEnd) {
                vphysicsId = readFixedString(raw, cursor + 4, 4, data.size());
            }

            int surfaceSize = 0;
            if (vphysicsId == "VPHY" || vphysicsId == "PHYS") {
                if (cursor + 16 <= binaryEnd) {
                    int ver = readShortLE(raw, cursor + 8) & 0xFFFF;
                    (void)ver;
                    int mType = readShortLE(raw, cursor + 10) & 0xFFFF;
                    (void)mType;
                    if (cursor + 16 <= binaryEnd) {
                        surfaceSize = readIntLE(raw, cursor + 12);
                    }
                }
            }

            PhySolid solid;
            solid.index = i;
            solid.name = "solid_" + std::to_string(i);

            if (surfaceSize > 0 && surfaceSize < binaryEnd - cursor) {
                int surfaceStart = cursor + 28;
                if (surfaceStart + surfaceSize <= binaryEnd) {
                    parseSurfaceData(raw, fileLen, surfaceStart, surfaceSize, binaryEnd, solid, i);
                }
            }

            result.solids[i] = std::move(solid);
            cursor = sectionOrigin + 4 + sectionSize;
        }

        if (kvStart > 0) {
            parseSolidNames(raw, kvStart, fileLen, result.solids);
        }

        result.valid = true;

    } catch (const std::exception&) {
        result.valid = false;
    }

    return result;
}
