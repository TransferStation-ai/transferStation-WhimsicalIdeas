#include "model_loader.h"
#include <fstream>
#include <iostream>
#include <filesystem>
#include <algorithm>
#include <sstream>

namespace fs = std::filesystem;

// Simple VMT parser to extract $basetexture and material flags
struct VmtInfo {
    std::string baseTexture;
    std::string bumpMap;
    std::string lightwarptexture;
    bool noCull = false;
    bool translucent = false;
    bool alphaTest = false;
    bool phong = false;
    bool halfLambert = false;
    float phongBoost = 0.0f;
    float colorTint[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    bool hasColorTint = false;
};

static VmtInfo parseVmtMaterial(const std::string& vmtPath) {
    VmtInfo info;
    std::ifstream file(vmtPath);
    if (!file) return info;

    auto extractValue = [](const std::string& line, const std::string& key) -> std::string {
        auto pos = line.find(key);
        if (pos == std::string::npos) return "";
        std::string rest = line.substr(pos + key.length());
        auto eq = rest.find('=');
        if (eq != std::string::npos) rest = rest.substr(eq + 1);
        size_t start = rest.find_first_not_of(" \t\"");
        if (start == std::string::npos) return "";
        rest = rest.substr(start);
        auto end = rest.find_first_of(" \t\"\n\r");
        if (end != std::string::npos) rest = rest.substr(0, end);
        return rest;
    };

    auto boolVal = [](const std::string& s) -> bool {
        return s == "1" || s == "true" || s == "True";
    };

    std::string line;
    while (std::getline(file, line)) {
        size_t start = line.find_first_not_of(" \t\r");
        if (start == std::string::npos) continue;
        line = line.substr(start);

        if (info.baseTexture.empty()) {
            info.baseTexture = extractValue(line, "$basetexture");
            if (info.baseTexture.empty()) info.baseTexture = extractValue(line, "$BaseTexture");
        }
        if (info.bumpMap.empty()) info.bumpMap = extractValue(line, "$bumpmap");
        if (info.lightwarptexture.empty()) info.lightwarptexture = extractValue(line, "$lightwarptexture");

        std::string nc = extractValue(line, "$nocull");
        if (!nc.empty()) info.noCull = boolVal(nc);
        std::string tr = extractValue(line, "$translucent");
        if (!tr.empty()) info.translucent = boolVal(tr);
        std::string at = extractValue(line, "$alphatest");
        if (!at.empty()) info.alphaTest = boolVal(at);
        std::string ph = extractValue(line, "$phong");
        if (!ph.empty()) info.phong = boolVal(ph);
        std::string hl = extractValue(line, "$halflambert");
        if (!hl.empty()) info.halfLambert = boolVal(hl);

        std::string pb = extractValue(line, "$phongboost");
        if (!pb.empty()) {
            try { info.phongBoost = std::stof(pb); } catch (...) {}
        }

        // Parse $color2 or $color (Source Engine format: "[R G B]" with 0-255 integers)
        if (!info.hasColorTint) {
            auto parseColor = [&info](const std::string& val) {
                if (val.empty()) return;
                std::string s = val;
                // Strip brackets
                if (!s.empty() && s.front() == '[') s = s.substr(1);
                if (!s.empty() && s.back() == ']') s.pop_back();
                // Parse space-separated values
                std::istringstream iss(s);
                float r = 1.0f, g = 1.0f, b = 1.0f, a = 1.0f;
                iss >> r >> g >> b;
                if (iss.good()) iss >> a;
                // Source Engine uses 0-255 range; normalize to 0-1
                if (r > 1.0f || g > 1.0f || b > 1.0f) {
                    r /= 255.0f; g /= 255.0f; b /= 255.0f; a /= 255.0f;
                }
                info.colorTint[0] = std::max(0.0f, std::min(1.0f, r));
                info.colorTint[1] = std::max(0.0f, std::min(1.0f, g));
                info.colorTint[2] = std::max(0.0f, std::min(1.0f, b));
                info.colorTint[3] = std::max(0.0f, std::min(1.0f, a));
                info.hasColorTint = true;
            };
            std::string c2 = extractValue(line, "$color2");
            if (!c2.empty()) { parseColor(c2); }
            else {
                std::string c = extractValue(line, "$color");
                if (!c.empty()) parseColor(c);
            }
        }
    }
    return info;
}

// Keep old function for backward compat
static std::string parseVmtBaseTexture(const std::string& vmtPath) {
    return parseVmtMaterial(vmtPath).baseTexture;
}

static std::string toLower(const std::string& s) {
    std::string r = s;
    std::transform(r.begin(), r.end(), r.begin(), ::tolower);
    return r;
}

struct CachedTexture {
    std::vector<uint8_t> rgbaData;
    int width;
    int height;
};

// Scan materials directory for VMT files and build texture name map
static void discoverTextures(
    const std::string& materialsDir,
    std::unordered_map<std::string, std::string>& materialToTexture,
    std::unordered_map<std::string, VmtInfo>& vmtInfoMap,
    std::string& defaultTexture,
    std::unordered_map<std::string, CachedTexture>& textureCache
) {
    if (!fs::exists(materialsDir)) return;

    for (auto& entry : fs::recursive_directory_iterator(materialsDir)) {
        if (!entry.is_regular_file()) continue;
        auto path = entry.path();
        auto ext = toLower(path.extension().string());
        std::string fileName = toLower(path.filename().string());

        if (ext == ".vmt") {
            // Parse VMT to get $basetexture reference and material flags
            VmtInfo vmtInfo = parseVmtMaterial(path.string());
            if (!vmtInfo.baseTexture.empty()) {
                // Get relative path without extension
                fs::path relPath = fs::relative(path, materialsDir);
                std::string matName = relPath.string();
                // Replace backslashes and remove .vmt extension
                std::replace(matName.begin(), matName.end(), '\\', '/');
                if (matName.size() >= 4) matName = matName.substr(0, matName.size() - 4);

                materialToTexture[matName] = vmtInfo.baseTexture;
                vmtInfoMap[matName] = vmtInfo;
                if (defaultTexture.empty()) defaultTexture = vmtInfo.baseTexture;

                std::cout << "[VMT] '" << matName << "' -> $basetexture '" << vmtInfo.baseTexture << "'"
                          << (vmtInfo.noCull ? " nocull" : "")
                          << (vmtInfo.translucent ? " translucent" : "")
                          << (vmtInfo.phong ? " phong" : "")
                          << std::endl;
            }
        } else if (ext == ".vtf") {
            // Read and decode VTF
            try {
                std::ifstream vtfFile(path, std::ios::binary | std::ios::ate);
                size_t vtfSize = static_cast<size_t>(vtfFile.tellg());
                vtfFile.seekg(0);
                if (vtfSize > 256 * 1024 * 1024) continue;
                std::vector<uint8_t> vtfData(vtfSize);
                vtfFile.read(reinterpret_cast<char*>(vtfData.data()), vtfSize);
                vtfFile.close();

                auto decoded = VtfDecoder::decode(vtfData);
                // Store under the path relative to materials dir
                fs::path relPath = fs::relative(path, materialsDir);
                std::string texPath = relPath.string();
                std::replace(texPath.begin(), texPath.end(), '\\', '/');
                if (texPath.size() >= 4) texPath = texPath.substr(0, texPath.size() - 4);

                CachedTexture ct;
                ct.rgbaData = std::move(decoded.rgbaData);
                ct.width = decoded.width;
                ct.height = decoded.height;
                textureCache[texPath] = std::move(ct);
                std::cout << "[VTF] Loaded '" << texPath << "' (" << decoded.width << "x" << decoded.height << ")" << std::endl;
            } catch (const std::exception& e) {
                std::cerr << "[VTF] Failed to load " << path << ": " << e.what() << std::endl;
            }
        }
    }
}

ModelLoader::LoadedModel::~LoadedModel() {
    // Cleanup will be called from Java via dispose()
}

std::unique_ptr<ModelLoader::LoadedModel> ModelLoader::loadFromDirectory(
    const std::string& baseDir,
    const std::string& modelName
) {
    auto model = std::make_unique<LoadedModel>();
    model->name = modelName;
    model->modelScale = 1.0f;
    model->hasSkinData = false;
    model->minZ = 0.0f;
    model->fallbackTexture = 0;
    model->numSkinRef = 0;
    model->numSkinFamilies = 0;

    std::string pkgDir = baseDir + "/" + modelName;

    if (!fs::exists(pkgDir)) {
        throw std::runtime_error("Model directory not found: " + pkgDir);
    }

    // Walk the directory to find .mdl, .vvd, .dx90.vtx
    std::string mdlPath, vvdPath, vtxPath;
    std::string materialsDir;

    for (auto& entry : fs::recursive_directory_iterator(pkgDir)) {
        if (!entry.is_regular_file()) continue;
        std::string fileName = toLower(entry.path().filename().string());

        if (fileName.ends_with(".mdl")) {
            if (mdlPath.empty()) mdlPath = entry.path().string();
        } else if (fileName.ends_with(".vvd")) {
            if (vvdPath.empty()) vvdPath = entry.path().string();
        } else if (fileName.ends_with(".dx90.vtx")) {
            if (vtxPath.empty()) vtxPath = entry.path().string();
        } else if (fileName.ends_with(".lua")) {
            // Parse Lua metadata for display name, author, scale and model references
            std::ifstream luaFile(entry.path());
            std::string line;
            while (std::getline(luaFile, line)) {
                // Trim
                size_t s = line.find_first_not_of(" \t\r");
                if (s == std::string::npos) continue;
                line = line.substr(s);
                if (line.rfind("--", 0) == 0) continue;

                // Comment-based metadata: --@FieldName: value
                auto commentPos = line.find("--@");
                if (commentPos != std::string::npos) {
                    std::string comment = line.substr(commentPos + 3);
                    auto colonPos = comment.find(':');
                    if (colonPos != std::string::npos) {
                        std::string field = comment.substr(0, colonPos);
                        std::string value = comment.substr(colonPos + 1);
                        value.erase(0, value.find_first_not_of(" \t"));
                        value.erase(value.find_last_not_of(" \t\r") + 1);

                        if (field == "DisplayName") model->displayName = value;
                        else if (field == "Author") model->author = value;
                        else if (field == "Scale") {
                            try { model->modelScale = std::stof(value); } catch (...) {}
                        }
                    }
                }

                // Extract model references for composite model support
                auto extractPath = [](const std::string& line, const std::string& func) -> std::string {
                    auto pos = line.find(func + "(");
                    if (pos == std::string::npos) {
                        pos = line.find(func + " (");
                        if (pos == std::string::npos) return "";
                    }
                    size_t start = pos + func.length() + 1;
                    // Skip first argument: name string
                    int parenD = 1;
                    for (size_t i = start; i < line.length() && parenD > 0; i++) {
                        if (line[i] == '(') parenD++;
                        else if (line[i] == ')') parenD--;
                        else if (line[i] == ',' && parenD == 1) {
                            // Found comma between name and model path
                            std::string rest = line.substr(i + 1);
                            rest.erase(0, rest.find_first_not_of(" \t"));
                            if (rest.empty()) return "";
                            if (rest[0] == '"') {
                                size_t eq = rest.find('"', 1);
                                if (eq != std::string::npos) {
                                    std::string p = rest.substr(1, eq - 1);
                                    if (!p.empty()) return p;
                                }
                            }
                            return "";
                        }
                    }
                    return "";
                };

                std::string modelRef = extractPath(line, "player_manager.AddValidModel");
                if (!modelRef.empty()) {
                    model->includeModelPaths.push_back(modelRef);
                }
                modelRef = extractPath(line, "list.Set");
                if (!modelRef.empty()) {
                    model->includeModelPaths.push_back(modelRef);
                }

                // Extract NPC Model = "..." field
                auto modelFieldPos = line.find("Model =");
                if (modelFieldPos == std::string::npos) modelFieldPos = line.find("Model=");
                if (modelFieldPos != std::string::npos) {
                    std::string rest = line.substr(modelFieldPos + 6);
                    rest.erase(0, rest.find_first_not_of(" \t"));
                    if (!rest.empty() && rest[0] == '"') {
                        size_t eq = rest.find('"', 1);
                        if (eq != std::string::npos) {
                            std::string p = rest.substr(1, eq - 1);
                            if (!p.empty()) model->includeModelPaths.push_back(p);
                        }
                    }
                }
            }
        }
    }

    // Find materials directory
    fs::path pkgDirPath(pkgDir);
    materialsDir = (pkgDirPath / "materials").string();
    if (!fs::exists(materialsDir)) {
        // Try parent directories
        fs::path ancestor = pkgDirPath.parent_path();
        while (!ancestor.empty()) {
            fs::path candidate = ancestor / "materials";
            if (fs::exists(candidate)) {
                materialsDir = candidate.string();
                break;
            }
            ancestor = ancestor.parent_path();
        }
    }

    if (model->displayName.empty()) model->displayName = modelName;

    // Parse files
    auto readFile = [](const std::string& path) -> std::vector<uint8_t> {
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) throw std::runtime_error("Cannot open file: " + path);
        size_t size = static_cast<size_t>(file.tellg());
        if (size > 512L * 1024 * 1024)
            throw std::runtime_error("File too large: " + path);
        file.seekg(0);
        std::vector<uint8_t> data(size);
        file.read(reinterpret_cast<char*>(data.data()), size);
        file.close();
        return data;
    };

    // Parse MDL
    MdlParser::ParsedMdl parsedMdl;
    if (!mdlPath.empty()) {
        auto mdlData = readFile(mdlPath);
        parsedMdl = MdlParser::parse(mdlData);
    } else {
        throw std::runtime_error("No .mdl file found in " + pkgDir);
    }

    // Populate bodypart metadata
    for (size_t i = 0; i < parsedMdl.bodyParts.size(); i++) {
        BodyPartInfo bpInfo;
        bpInfo.name = (i < parsedMdl.bodyPartNames.size()) ? parsedMdl.bodyPartNames[i] : "";
        bpInfo.numModels = parsedMdl.bodyParts[i].nummodels;
        bpInfo.baseIndex = parsedMdl.bodyParts[i].baseIndex;
        for (size_t mi = 0; mi < parsedMdl.modelBodyPartIndices.size(); mi++) {
            if (parsedMdl.modelBodyPartIndices[mi] == static_cast<int>(i)) {
                std::string modelName(parsedMdl.models[mi].name);
                bpInfo.modelNames.push_back(modelName);
            }
        }
        model->bodyParts.push_back(bpInfo);
    }

    // Store skin table data
    model->numSkinRef = parsedMdl.header.numskinref;
    model->numSkinFamilies = parsedMdl.header.numskinfamilies;
    model->skinTable = parsedMdl.skinTable;

    // Log include model references
    if (!parsedMdl.includeModels.empty()) {
        std::cout << "[ModelLoader] Model references " << parsedMdl.includeModels.size()
                  << " include model(s):";
        for (size_t i = 0; i < parsedMdl.includeModels.size(); i++) {
            std::cout << " " << parsedMdl.includeModels[i];
        }
        std::cout << std::endl;
    }

    // Parse VVD
    VvdParser::ParsedVvd parsedVvd;
    if (!vvdPath.empty()) {
        auto vvdData = readFile(vvdPath);
        parsedVvd = VvdParser::parse(vvdData);
    } else {
        throw std::runtime_error("No .vvd file found in " + pkgDir);
    }

    // Parse VTX
    VtxParser::ParsedVtx parsedVtx;
    if (!vtxPath.empty()) {
        auto vtxData = readFile(vtxPath);
        parsedVtx = VtxParser::parse(vtxData);
    } else {
        throw std::runtime_error("No .dx90.vtx file found in " + pkgDir);
    }

    // Build meshes from parsed data (LOD 0 = full detail)
    model->meshes = buildMeshes(parsedMdl, parsedVvd, parsedVtx, 0);
    model->minZ = computeMinZ(model->meshes);

    // Build lower LOD meshes if VTX has them
    auto buildLodIfNeeded = [&](int lod, std::vector<MeshData>& target) {
        const auto& lodGroups = VtxParser::getStripGroupsForLod(parsedVtx, lod);
        if (&lodGroups != &parsedVtx.meshStripGroups) {
            auto lodMeshes = buildMeshes(parsedMdl, parsedVvd, parsedVtx, lod);
            if (!lodMeshes.empty()) {
                target = std::move(lodMeshes);
                for (auto& m : target) {
                    m.textureName.clear();
                }
            }
        }
    };
    buildLodIfNeeded(1, model->lodMeshes1);
    buildLodIfNeeded(2, model->lodMeshes2);
    buildLodIfNeeded(3, model->lodMeshes3);

    // Load textures
    if (!materialsDir.empty()) {
        std::unordered_map<std::string, std::string> matToTex;
        std::unordered_map<std::string, VmtInfo> vmtInfoMap;
        std::string defaultTexName;
        std::unordered_map<std::string, CachedTexture> texCache;
        discoverTextures(materialsDir, matToTex, vmtInfoMap, defaultTexName, texCache);

        // Build cdtexture prefix for ordering textures
        std::string cdPrefix;
        if (!parsedMdl.cdTextures.empty()) {
            cdPrefix = parsedMdl.cdTextures[0];
            std::replace(cdPrefix.begin(), cdPrefix.end(), '\\', '/');
            if (!cdPrefix.empty() && cdPrefix.back() != '/') cdPrefix += '/';
        }

        // Build ordered list of VTF keys within cdtexture directory
        // We use the materialToTexture ($basetexture from VMT) as the canonical name
        std::vector<std::string> orderedBaseTexKeys;
        for (auto& [matPath, baseTex] : matToTex) {
            auto baseLower = toLower(baseTex);
            std::replace(baseLower.begin(), baseLower.end(), '\\', '/');
            if (baseLower.rfind(toLower(cdPrefix), 0) == 0) { // starts_with
                orderedBaseTexKeys.push_back(baseLower);
            }
        }
        std::sort(orderedBaseTexKeys.begin(), orderedBaseTexKeys.end());

        // Also build ordered list of VTF cache keys within cdPrefix
        std::vector<std::string> orderedVtfKeys;
        for (auto& [texPath, ct] : texCache) {
            auto lowerPath = toLower(texPath);
            if (lowerPath.rfind(toLower(cdPrefix), 0) == 0) {
                orderedVtfKeys.push_back(texPath);
            }
        }
        std::sort(orderedVtfKeys.begin(), orderedVtfKeys.end());

        // Map mesh textures using ordered VTF list by texture index
        for (int meshIdx = 0; meshIdx < static_cast<int>(model->meshes.size()); meshIdx++) {
            auto& mesh = model->meshes[meshIdx];

            // Get texture index from MDL mesh material + skin table
            int texIndex = 0;
            if (meshIdx < static_cast<int>(parsedMdl.meshes.size())) {
                int materialIdx = parsedMdl.meshes[meshIdx].material;
                texIndex = materialIdx;
                if (!parsedMdl.skinTable.empty() && parsedMdl.header.numskinref > 0) {
                    int wrapped = materialIdx % parsedMdl.header.numskinref;
                    if (wrapped >= 0 && wrapped < static_cast<int>(parsedMdl.skinTable.size())) {
                        texIndex = parsedMdl.skinTable[wrapped];
                    }
                }
            }

            // Strategy 1: Match by texture name from MDL + cdtexture against VMT $basetexture
            // (currently MDL names are mostly empty for this model, so this usually won't match)

            // Strategy 2: Use texture index to select Nth ordered $basetexture
            if (!orderedBaseTexKeys.empty()) {
                int idx = texIndex >= 0 ? texIndex % static_cast<int>(orderedBaseTexKeys.size()) : 0;
                const std::string& btPath = orderedBaseTexKeys[idx];

                // Look up the VTF by exact $basetexture path
                auto texIt = texCache.find(btPath);
                if (texIt == texCache.end()) {
                    // Try matching by stripping cdPrefix
                    std::string btRelative = btPath;
                    if (btRelative.rfind(toLower(cdPrefix), 0) == 0) {
                        btRelative = btRelative.substr(cdPrefix.size());
                    }
                    // Search VTF keys that end with this name
                    for (auto& [k, ct] : texCache) {
                        if (toLower(k).find(toLower(btRelative)) != std::string::npos ||
                            toLower(btRelative).find(toLower(k)) != std::string::npos) {
                            texIt = texCache.find(k);
                            break;
                        }
                    }
                }

                if (texIt != texCache.end() && !texIt->second.rgbaData.empty()) {
                    TextureData td;
                    td.name = texIt->first;
                    td.width = texIt->second.width;
                    td.height = texIt->second.height;
                    td.rgbaData = std::move(texIt->second.rgbaData);
                    int texDataIdx = static_cast<int>(model->textureData.size());
                    model->textureData.push_back(std::move(td));
                    model->textures.push_back({0, 0, 0});
                    model->meshTextureMap[meshIdx] = texDataIdx;
                    mesh.textureName = btPath;
                    // Apply color tint from VMT $color2/$color
                    for (auto& [matKey, vmtInfo] : vmtInfoMap) {
                        auto btLower = toLower(vmtInfo.baseTexture);
                        std::replace(btLower.begin(), btLower.end(), '\\', '/');
                        if (btLower == btPath || toLower(btPath).find(btLower) != std::string::npos) {
                            if (vmtInfo.hasColorTint) {
                                mesh.colorTint[0] = vmtInfo.colorTint[0];
                                mesh.colorTint[1] = vmtInfo.colorTint[1];
                                mesh.colorTint[2] = vmtInfo.colorTint[2];
                                mesh.colorTint[3] = vmtInfo.colorTint[3];
                            }
                            break;
                        }
                    }
                    continue;
                }
            }

            // Strategy 3: Use texture index to select Nth ordered VTF
            if (!orderedVtfKeys.empty()) {
                int idx = texIndex >= 0 ? texIndex % static_cast<int>(orderedVtfKeys.size()) : meshIdx % static_cast<int>(orderedVtfKeys.size());
                const std::string& vtfKey = orderedVtfKeys[idx];
                auto texIt = texCache.find(vtfKey);
                if (texIt != texCache.end() && !texIt->second.rgbaData.empty()) {
                    TextureData td;
                    td.name = vtfKey;
                    td.width = texIt->second.width;
                    td.height = texIt->second.height;
                    td.rgbaData = std::move(texIt->second.rgbaData);
                    int texDataIdx = static_cast<int>(model->textureData.size());
                    model->textureData.push_back(std::move(td));
                    model->textures.push_back({0, 0, 0});
                    model->meshTextureMap[meshIdx] = texDataIdx;
                    mesh.textureName = vtfKey;
                }
            }
        }
    }

    // Validate checksums
    /*int mdlChecksum = parsedMdl.header.checksum;
    int vvdChecksum = parsedVvd.header.checksum;
    int vtxChecksum = parsedVtx.checksum;
    if (mdlChecksum != vvdChecksum) {
        std::cerr << "MDL/VVD checksum mismatch" << std::endl;
    }
    if (mdlChecksum != vtxChecksum) {
        std::cerr << "MDL/VTX checksum mismatch" << std::endl;
    }*/

    return model;
}

std::vector<MeshData> ModelLoader::buildMeshes(
    const MdlParser::ParsedMdl& mdl,
    const VvdParser::ParsedVvd& vvd,
    const VtxParser::ParsedVtx& vtx,
    int lodLevel
) {
    std::vector<MeshData> result;

    const auto& vvdVerts = vvd.vertices;

    if (vvdVerts.empty()) {
        std::cerr << "[MeshBuilder] No VVD vertices available for LOD " << lodLevel << std::endl;
        return result;
    }

    // Build mesh-to-VVD-base mapping from MDL structure.
    // Source Engine vertex index mapping:
    //   vvdVertexIndex = mdlModel.vertexindex + mdlMesh.vertexoffset + vtx.origMeshVertID
    // The VTX origMeshVertID is relative to the mesh's vertex range (0..mesh.numvertices-1),
    // and mesh.vertexoffset gives the starting offset within the model's VVD block.
    struct MeshInfo { int modelIdx; int vertexOffset; };
    std::vector<MeshInfo> meshInfos;
    {
        int meshCounter = 0;
        for (int mi = 0; mi < static_cast<int>(mdl.models.size()); mi++) {
            for (int j = 0; j < mdl.models[mi].nummeshes; j++) {
                MeshInfo info;
                info.modelIdx = mi;
                if (meshCounter < static_cast<int>(mdl.meshes.size())) {
                    info.vertexOffset = mdl.meshes[meshCounter].vertexoffset;
                } else {
                    info.vertexOffset = 0;
                }
                meshInfos.push_back(info);
                meshCounter++;
            }
        }
    }

    // Select VTX strip groups based on LOD level
    const auto& stripGroupsList = VtxParser::getStripGroupsForLod(vtx, lodLevel);

    int vtxMeshCount = static_cast<int>(stripGroupsList.size());
    if (vtxMeshCount == 0) {
        std::cerr << "[MeshBuilder] No VTX meshes for LOD " << lodLevel << std::endl;
        return result;
    }

    int totalTris = 0;
    for (int meshIdx = 0; meshIdx < vtxMeshCount; meshIdx++) {
        MeshData mesh;

        // Compute VVD vertex base for this mesh using MDL model.vertexindex + mesh.vertexoffset
        int vvdBase = 0;
        if (meshIdx < static_cast<int>(meshInfos.size())) {
            const auto& info = meshInfos[meshIdx];
            if (info.modelIdx >= 0 && info.modelIdx < static_cast<int>(mdl.models.size())) {
                vvdBase = mdl.models[info.modelIdx].vertexindex / VVD_VERTEX_SIZE + info.vertexOffset;
            }
        }

        const auto& stripGroups = stripGroupsList[meshIdx];
        for (const auto& sg : stripGroups) {
            if (sg.vertices.empty() || sg.strips.empty()) continue;

            for (const auto& strip : sg.strips) {
                size_t idxSize = strip.indices.size();
                for (size_t i = 0; i + 2 < idxSize; i += 3) {
                    uint32_t ci0 = strip.indices[i];
                    uint32_t ci1 = strip.indices[i + 1];
                    uint32_t ci2 = strip.indices[i + 2];

                    if (ci0 >= sg.vertices.size() || ci1 >= sg.vertices.size() || ci2 >= sg.vertices.size())
                        continue;

                    int vvdIdx0 = vvdBase + sg.vertices[ci0].origMeshVertID;
                    int vvdIdx1 = vvdBase + sg.vertices[ci1].origMeshVertID;
                    int vvdIdx2 = vvdBase + sg.vertices[ci2].origMeshVertID;

                    if (vvdIdx0 >= static_cast<int>(vvdVerts.size()) ||
                        vvdIdx1 >= static_cast<int>(vvdVerts.size()) ||
                        vvdIdx2 >= static_cast<int>(vvdVerts.size()))
                        continue;

                    auto addVert = [&](const StudioVertexExt& sv) {
                        MeshVertex mv;
                        mv.x = -sv.y; mv.y = sv.z; mv.z = sv.x;
                        mv.nx = -sv.ny; mv.ny = sv.nz; mv.nz = sv.nx;
                        mv.u = sv.u;
                        mv.v = 1.0f - sv.v;
                        mesh.vertices.push_back(mv);
                    };

                    addVert(vvdVerts[vvdIdx0]);
                    addVert(vvdVerts[vvdIdx1]);
                    addVert(vvdVerts[vvdIdx2]);

                    uint32_t baseIdx = static_cast<uint32_t>(mesh.vertices.size()) - 3;
                    mesh.indices.push_back(baseIdx);
                    mesh.indices.push_back(baseIdx + 1);
                    mesh.indices.push_back(baseIdx + 2);
                    totalTris++;
                }
            }
        }

        if (!mesh.indices.empty()) {
            result.push_back(std::move(mesh));
        }
    }

    std::cout << "[MeshBuilder] LOD " << lodLevel << ": Built " << result.size() << " meshes, "
              << totalTris << " triangles from " << vvdVerts.size() << " VVD vertices" << std::endl;

    // Fallback for empty VTX
    if (result.empty() && vvdVerts.size() >= 3) {
        MeshData fallback;
        int count = 0;
        for (size_t i = 0; i + 2 < vvdVerts.size(); i++) {
            const auto& v0 = vvdVerts[i];
            const auto& v1 = vvdVerts[i + 1];
            const auto& v2 = vvdVerts[i + 2];

            MeshVertex mv0, mv1, mv2;
            mv0.x = -v0.y; mv0.y = v0.z; mv0.z = v0.x;
            mv0.nx = -v0.ny; mv0.ny = v0.nz; mv0.nz = v0.nx;
            mv0.u = v0.u; mv0.v = 1.0f - v0.v;

            mv1.x = -v1.y; mv1.y = v1.z; mv1.z = v1.x;
            mv1.nx = -v1.ny; mv1.ny = v1.nz; mv1.nz = v1.nx;
            mv1.u = v1.u; mv1.v = 1.0f - v1.v;

            mv2.x = -v2.y; mv2.y = v2.z; mv2.z = v2.x;
            mv2.nx = -v2.ny; mv2.ny = v2.nz; mv2.nz = v2.nx;
            mv2.u = v2.u; mv2.v = 1.0f - v2.v;

            if (i % 2 == 0) {
                fallback.vertices.push_back(mv0);
                fallback.vertices.push_back(mv1);
                fallback.vertices.push_back(mv2);
            } else {
                fallback.vertices.push_back(mv1);
                fallback.vertices.push_back(mv0);
                fallback.vertices.push_back(mv2);
            }
            uint32_t base = static_cast<uint32_t>(fallback.vertices.size()) - 3;
            fallback.indices.push_back(base);
            fallback.indices.push_back(base + 1);
            fallback.indices.push_back(base + 2);
            count++;
        }
        if (!fallback.indices.empty()) {
            result.push_back(std::move(fallback));
            std::cout << "[MeshBuilder] LOD " << lodLevel << " VVD fallback: " << count << " triangles" << std::endl;
        }
    }

    return result;
}

float ModelLoader::computeMinZ(const std::vector<MeshData>& meshes) {
    float minZ = 0.0f;
    for (const auto& mesh : meshes) {
        for (const auto& v : mesh.vertices) {
            if (v.y < minZ) minZ = v.y;
        }
    }
    return minZ;
}
