#include "vvd_parser.h"
#include <cstring>
#include <stdexcept>
#include <algorithm>

VvdParser::ParsedVvd VvdParser::parse(const std::vector<uint8_t>& data) {
    ParsedVvd result;

    if (data.size() < VVD_HEADER_SIZE) {
        throw std::runtime_error("VVD file too small for header");
    }

    memcpy(&result.header, data.data(), VVD_HEADER_SIZE);

    if (result.header.id != VVD_MAGIC) {
        throw std::runtime_error("Not a valid VVD file (bad magic)");
    }

    auto readAt = [&](int offset, void* dest, size_t size) {
        if (offset < 0 || offset + size > data.size())
            throw std::runtime_error("VVD read out of bounds");
        memcpy(dest, data.data() + offset, size);
    };

    // Parse fixups
    if (result.header.numFixups > 0) {
        int numFixups = std::min(result.header.numFixups, 65536);
        result.fixups.resize(numFixups);
        for (int i = 0; i < numFixups; i++) {
            readAt(result.header.fixupTableStart + i * FIXUP_SIZE,
                   &result.fixups[i], FIXUP_SIZE);
        }
    }

    // Read raw LOD 0 vertices
    int numVerts = result.header.numLODVertices[0];
    if (numVerts <= 0 || numVerts > 1000000) {
        throw std::runtime_error("Invalid VVD vertex count");
    }

    std::vector<StudioVertexExt> rawVertices(numVerts);
    for (int i = 0; i < numVerts; i++) {
        readAt(result.header.vertexDataStart + i * VVD_VERTEX_SIZE,
               &rawVertices[i], VVD_VERTEX_SIZE);
    }

    // Store raw vertices (indexed by model.vertexindex + mesh.vertexoffset).
    result.vertices = rawVertices;

    // Also build fixup-processed vertex lists for each LOD (used for LOD culling).
    if (!result.fixups.empty()) {
        auto applyFixupsForLod = [&](int targetLod) -> std::vector<StudioVertexExt> {
            std::vector<StudioVertexExt> out;
            for (auto& fixup : result.fixups) {
                if (fixup.lodIndex != targetLod) continue;
                int end = std::min(fixup.sourceVertexID + fixup.numVertexes, numVerts);
                for (int i = fixup.sourceVertexID; i < end; i++) {
                    out.push_back(rawVertices[i]);
                }
            }
            return out;
        };

        for (int lod = 0; lod < 4; lod++) {
            auto lodVerts = applyFixupsForLod(lod);
            if (!lodVerts.empty()) {
                result.lodVertices.push_back(std::move(lodVerts));
            }
        }
    }

    return result;
}
