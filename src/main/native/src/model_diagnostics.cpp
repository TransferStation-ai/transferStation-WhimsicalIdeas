#include "model_diagnostics.h"
#include <iostream>
#include <iomanip>
#include <sstream>
#include <fstream>
#include <algorithm>
#include <set>

static std::string toLower(const std::string& s) {
    std::string r = s;
    std::transform(r.begin(), r.end(), r.begin(), ::tolower);
    return r;
}

std::vector<uint8_t> ModelDiagnostics::readFile(const fs::path& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) throw std::runtime_error("Cannot open file: " + path.string());
    size_t size = static_cast<size_t>(file.tellg());
    file.seekg(0);
    std::vector<uint8_t> data(size);
    file.read(reinterpret_cast<char*>(data.data()), size);
    return data;
}

std::vector<ModelDiagnostics::ModelGroup> ModelDiagnostics::findModelGroups(const fs::path& rootDir) {
    std::map<std::string, ModelGroup> groups;

    for (auto& entry : fs::recursive_directory_iterator(rootDir)) {
        if (!entry.is_regular_file()) continue;
        auto& path = entry.path();
        std::string fileName = toLower(path.filename().string());

        std::string base;
        if (fileName.size() > 4 && fileName.substr(fileName.size() - 4) == ".mdl") {
            base = fileName.substr(0, fileName.size() - 4);
        } else if (fileName.size() > 4 && fileName.substr(fileName.size() - 4) == ".vvd") {
            base = fileName.substr(0, fileName.size() - 4);
        } else if (fileName.size() > 9 && fileName.substr(fileName.size() - 9) == ".dx90.vtx") {
            base = fileName.substr(0, fileName.size() - 9);
        } else if (fileName.size() > 4 && fileName.substr(fileName.size() - 4) == ".phy") {
            base = fileName.substr(0, fileName.size() - 4);
        } else {
            continue;
        }

        fs::path relDir = fs::relative(path.parent_path(), rootDir);
        std::string key = relDir.string().empty() ? base : relDir.string() + "/" + base;
        std::replace(key.begin(), key.end(), '\\', '/');

        auto& g = groups[key];
        g.dir = path.parent_path();
        g.baseName = base;

        if (fileName.ends_with(".mdl"))      g.mdl = path;
        else if (fileName.ends_with(".vvd")) g.vvd = path;
        else if (fileName.ends_with(".dx90.vtx")) g.vtx = path;
        else if (fileName.ends_with(".phy")) g.phy = path;
    }

    std::vector<ModelGroup> result;
    result.reserve(groups.size());
    for (auto& [k, v] : groups) result.push_back(std::move(v));
    return result;
}

ModelDiagnostics::DiagnosticResult ModelDiagnostics::diagnoseFile(const fs::path& filePath) {
    DiagnosticResult result;
    result.modelName = filePath.filename().string();
    result.complete = false;
    result.hasPhy = false;

    try {
        auto data = readFile(filePath);
        std::string ext = toLower(filePath.extension().string());

        if (ext == ".mdl") {
            diagnoseMdl(data, result);
            result.complete = (result.warnings.empty() || result.warnings.size() == 1);
        } else if (ext == ".vvd") {
            diagnoseVvd(data, result);
            result.complete = (result.warnings.empty() || result.warnings.size() == 1);
        } else if (filePath.string().size() >= 9 &&
                   toLower(filePath.string().substr(filePath.string().size() - 9)) == ".dx90.vtx") {
            diagnoseVtx(data, result);
            result.complete = (result.warnings.empty() || result.warnings.size() == 1);
        } else if (ext == ".phy") {
            result.hasPhy = true;
            diagnosePhy(data, result);
            result.complete = true;
        }

    } catch (const std::exception& e) {
        result.warnings.push_back("Error reading/parsing: " + std::string(e.what()));
    }

    return result;
}

ModelDiagnostics::DiagnosticResult ModelDiagnostics::diagnoseGroup(const ModelGroup& group) {
    DiagnosticResult result;
    result.modelName = group.baseName;
    result.complete = group.isComplete();
    result.hasPhy = !group.phy.empty();

    auto diagnose = [&](const fs::path& path, const std::string& type) {
        if (path.empty()) {
            result.warnings.push_back("[" + type + "] MISSING");
            return;
        }
        try {
            auto data = readFile(path);
            result.infoFields.push_back(type + " size: " + std::to_string(data.size()) + " bytes");

            if (type == "MDL")       diagnoseMdl(data, result);
            else if (type == "VVD")  diagnoseVvd(data, result);
            else if (type == "VTX")  diagnoseVtx(data, result);
            else if (type == "PHY")  diagnosePhy(data, result);
        } catch (const std::exception& e) {
            result.warnings.push_back("[" + type + "] Error: " + std::string(e.what()));
        }
    };

    diagnose(group.mdl, "MDL");
    diagnose(group.vvd, "VVD");
    diagnose(group.vtx, "VTX");
    diagnose(group.phy, "PHY");

    checkChecksumConsistency(group, result);

    return result;
}

std::vector<ModelDiagnostics::DiagnosticResult> ModelDiagnostics::diagnoseDirectory(const fs::path& rootDir) {
    auto groups = findModelGroups(rootDir);
    std::vector<DiagnosticResult> results;
    results.reserve(groups.size());
    for (auto& g : groups) {
        results.push_back(diagnoseGroup(g));
    }
    return results;
}

// ———————————————— Individual file diagnostics ————————————————

void ModelDiagnostics::diagnoseMdl(const std::vector<uint8_t>& data, DiagnosticResult& result) {
    try {
        MdlParser::ParsedMdl mdl = MdlParser::parse(data);
        auto& h = mdl.header;

        result.checksums["MDL"] = h.checksum;

        result.infoFields.push_back("MDL version: " + std::to_string(h.version));
        if (h.version < 44 || h.version > 53) {
            result.warnings.push_back("Unusual MDL version: " + std::to_string(h.version));
        }

        char safeName[65] = {};
        memcpy(safeName, h.name, 64);
        result.infoFields.push_back("MDL name: " + std::string(safeName));

        result.infoFields.push_back("Bones: " + std::to_string(h.numbones));
        result.infoFields.push_back("BodyParts: " + std::to_string(h.numbodyparts));
        result.infoFields.push_back("Textures: " + std::to_string(h.numtextures));
        result.infoFields.push_back("IncludeModels: " + std::to_string(h.numincludemodels));
        result.infoFields.push_back("LocalAnim: " + std::to_string(h.numlocalanim));
        result.infoFields.push_back("LocalSeq: " + std::to_string(h.numlocalseq));

        if (h.vertexbase != 0 || h.offsetbase != 0) {
            result.warnings.push_back("vertexbase=" + std::to_string(h.vertexbase) +
                " or offsetbase=" + std::to_string(h.offsetbase) + " non-zero (may indicate GPU skinning)");
        }
        if (h.studiohdr2index > 0) {
            std::stringstream ss;
            ss << "studiohdr2 present at 0x" << std::hex << h.studiohdr2index;
            result.infoFields.push_back(ss.str());
        }

        if (h.numbodyparts == 0) {
            result.warnings.push_back("No body parts");
        } else {
            int totalModels = static_cast<int>(mdl.models.size());
            int totalMeshes = static_cast<int>(mdl.meshes.size());
            int totalVerticesFromMdl = 0;
            for (auto& m : mdl.models) {
                totalVerticesFromMdl += m.numvertices;
            }
            result.infoFields.push_back("Total Models: " + std::to_string(totalModels));
            result.infoFields.push_back("Total Meshes: " + std::to_string(totalMeshes));
            result.infoFields.push_back("Total Vertices (MDL): " + std::to_string(totalVerticesFromMdl));

            for (size_t i = 0; i < std::min(mdl.bodyParts.size(), size_t(10)); i++) {
                result.infoFields.push_back("  BodyPart[" + std::to_string(i) + "]: " +
                    std::to_string(mdl.bodyParts[i].nummodels) + " models");
            }
        }

    } catch (const std::exception& e) {
        result.warnings.push_back("[MDL] Parse error: " + std::string(e.what()));
    }
}

void ModelDiagnostics::diagnoseVvd(const std::vector<uint8_t>& data, DiagnosticResult& result) {
    try {
        VvdParser::ParsedVvd vvd = VvdParser::parse(data);
        auto& h = vvd.header;

        result.checksums["VVD"] = h.checksum;

        result.infoFields.push_back("VVD version: " + std::to_string(h.version));
        if (h.version != 4) {
            result.warnings.push_back("Unusual VVD version: " + std::to_string(h.version) + " (expected 4)");
        }
        result.infoFields.push_back("VVD LODs: " + std::to_string(h.numLODs));
        result.infoFields.push_back("VVD LOD0 vertices: " + std::to_string(h.numLODVertices[0]));
        result.infoFields.push_back("VVD fixups: " + std::to_string(h.numFixups));

        int totalVerts = h.numLODVertices[0];
        int expectedMin = h.vertexDataStart + totalVerts * 48;
        if (expectedMin > static_cast<int>(data.size())) {
            result.warnings.push_back("Vertex data exceeds file: " +
                std::to_string(expectedMin) + " > " + std::to_string(data.size()));
        }
        if (h.tangentDataStart > 0 &&
            h.tangentDataStart + totalVerts * 16 > static_cast<int>(data.size())) {
            result.warnings.push_back("Tangent data exceeds file");
        }

        if (!vvd.vertices.empty()) {
            float minX = 0, maxX = 0, minY = 0, maxY = 0, minZ = 0, maxZ = 0;
            bool first = true;
            for (auto& v : vvd.vertices) {
                if (first) {
                    minX = maxX = v.x; minY = maxY = v.y; minZ = maxZ = v.z;
                    first = false;
                } else {
                    if (v.x < minX) minX = v.x; if (v.x > maxX) maxX = v.x;
                    if (v.y < minY) minY = v.y; if (v.y > maxY) maxY = v.y;
                    if (v.z < minZ) minZ = v.z; if (v.z > maxZ) maxZ = v.z;
                }
            }
            char buf[128];
            snprintf(buf, sizeof(buf), "VVD bounds X: [%.1f, %.1f]", minX, maxX);
            result.infoFields.push_back(buf);
            snprintf(buf, sizeof(buf), "VVD bounds Y: [%.1f, %.1f]", minY, maxY);
            result.infoFields.push_back(buf);
            snprintf(buf, sizeof(buf), "VVD bounds Z: [%.1f, %.1f]", minZ, maxZ);
            result.infoFields.push_back(buf);
        }

    } catch (const std::exception& e) {
        result.warnings.push_back("[VVD] Parse error: " + std::string(e.what()));
    }
}

void ModelDiagnostics::diagnoseVtx(const std::vector<uint8_t>& data, DiagnosticResult& result) {
    try {
        VtxParser::ParsedVtx vtx = VtxParser::parse(data);

        result.checksums["VTX"] = vtx.checksum;

        result.infoFields.push_back("VTX version: " + std::to_string(vtx.version));
        if (vtx.version != 7) {
            result.warnings.push_back("Unusual VTX version: " + std::to_string(vtx.version) + " (expected 7)");
        }
        result.infoFields.push_back("VTX LODs: " + std::to_string(vtx.numLODs));
        result.infoFields.push_back("VTX BodyParts: " + std::to_string(vtx.numBodyParts));

        int totalMeshes = static_cast<int>(vtx.meshStripGroups.size());
        int totalTriangles = 0;
        for (auto& meshGroups : vtx.meshStripGroups) {
            for (auto& sg : meshGroups) {
                for (auto& strip : sg.strips) {
                    totalTriangles += static_cast<int>(strip.indices.size()) / 3;
                }
            }
        }
        result.infoFields.push_back("VTX Meshes (LOD0): " + std::to_string(totalMeshes));
        result.infoFields.push_back("VTX Triangles (LOD0): " + std::to_string(totalTriangles));

    } catch (const std::exception& e) {
        result.warnings.push_back("[VTX] Parse error: " + std::string(e.what()));
    }
}

void ModelDiagnostics::diagnosePhy(const std::vector<uint8_t>& data, DiagnosticResult& result) {
    try {
        if (data.size() < 16) {
            result.warnings.push_back("[PHY] File too small for header");
            return;
        }

        const uint8_t* raw = data.data();

        auto readInt = [&](int off) -> uint32_t {
            return raw[off] | (raw[off+1] << 8) | (raw[off+2] << 16) | (raw[off+3] << 24);
        };

        uint32_t size = readInt(0);
        std::string id(reinterpret_cast<const char*>(raw + 4), 4);
        uint32_t solidCount = readInt(8);
        uint32_t checksum = readInt(12);

        result.checksums["PHY"] = checksum;

        result.infoFields.push_back("PHY size: " + std::to_string(size));
        result.infoFields.push_back("PHY ID: " + id);
        result.infoFields.push_back("PHY solids: " + std::to_string(solidCount));

        if (id != "VPHY" && id != "PHYS" && id != std::string(4, '\0')) {
            result.warnings.push_back("Unusual PHY ID: " + id);
        }
        if (solidCount > 100) {
            result.warnings.push_back("Large solid count: " + std::to_string(solidCount));
        }

    } catch (const std::exception& e) {
        result.warnings.push_back("[PHY] Parse error: " + std::string(e.what()));
    }
}

void ModelDiagnostics::checkChecksumConsistency(const ModelGroup& group, DiagnosticResult& result) {
    if (result.checksums.size() < 2) return;

    std::set<uint32_t> unique;
    for (auto& [k, v] : result.checksums) unique.insert(v);
    if (unique.size() <= 1) {
        std::stringstream ss;
        ss << "Checksums: ALL MATCH (0x" << std::hex << std::uppercase
           << std::setw(8) << std::setfill('0') << *unique.begin() << ")";
        result.infoFields.push_back(ss.str());
        return;
    }

    result.warnings.push_back("Checksums don't match!");
    auto it = result.checksums.find("MDL");
    if (it != result.checksums.end()) {
        uint32_t mdlCs = it->second;
        for (auto& [k, v] : result.checksums) {
            if (k != "MDL" && v != mdlCs) {
                std::stringstream ss;
                ss << "  " << k << " (0x" << std::hex << std::uppercase
                   << std::setw(8) << std::setfill('0') << v
                   << ") != MDL (0x" << std::setw(8) << std::setfill('0') << mdlCs << ")";
                result.warnings.push_back(ss.str());
            }
        }
    }
}

std::string ModelDiagnostics::formatResults(const std::vector<DiagnosticResult>& results) {
    std::stringstream ss;

    ss << "\n" << std::string(70, '=') << "\n";
    ss << "  MODEL DIAGNOSTICS\n";
    ss << std::string(70, '=') << "\n\n";

    ss << "  Found " << results.size() << " model groups:\n\n";
    for (auto& r : results) {
        std::string status = r.complete ? "COMPLETE" : "INCOMPLETE";
        std::string phyStatus = r.hasPhy ? "has PHY" : "no PHY";
        ss << "  [" << status << "] " << r.modelName << " (" << phyStatus << ")\n";
    }

    ss << "\n" << std::string(70, '=') << "\n";
    ss << "  DETAILED ANALYSIS\n";
    ss << std::string(70, '=') << "\n\n";

    for (auto& r : results) {
        ss << std::string(60, '_') << "\n";
        ss << "  GROUP: " << r.modelName << "\n";
        ss << std::string(60, '_') << "\n";

        for (auto& w : r.warnings) {
            ss << "  WARNING: " << w << "\n";
        }
        for (auto& i : r.infoFields) {
            ss << "  " << i << "\n";
        }
        ss << "\n";
    }

    return ss.str();
}