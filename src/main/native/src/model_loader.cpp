#include "model_loader.h"
#include "skinning.h"
#include <fstream>
#include <iostream>
#include <filesystem>
#include <algorithm>
#include <sstream>
#include <cstring>
#include <atomic>
#include <future>
#include <mutex>
#include <thread>
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <propidl.h>
#include <gdiplus.h>

namespace fs = std::filesystem;

// Static members
std::unordered_map<std::string, ModelLoader::CachedTexture> ModelLoader::s_textureCache;
std::mutex ModelLoader::s_cacheMutex;

// Safe unaligned reads
static inline uint16_t readUint16(const uint8_t* buf, int offset) {
    uint16_t v; memcpy(&v, buf + offset, sizeof(uint16_t)); return v;
}

// VMT material info
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

        if (!info.hasColorTint) {
            auto parseColor = [&info](const std::string& val) {
                if (val.empty()) return;
                std::string s = val;
                if (!s.empty() && s.front() == '[') s = s.substr(1);
                if (!s.empty() && s.back() == ']') s.pop_back();
                std::istringstream iss(s);
                float r = 1.0f, g = 1.0f, b = 1.0f, a = 1.0f;
                iss >> r >> g >> b;
                if (iss.good()) iss >> a;
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

void ModelLoader::toLowerInPlace(std::string& s) {
    std::transform(s.begin(), s.end(), s.begin(), ::tolower);
}

std::string ModelLoader::toLower(std::string_view s) {
    std::string r(s);
    toLowerInPlace(r);
    return r;
}

// Memory-mapped file reading for large files
std::vector<uint8_t> ModelLoader::readFileMapped(const std::string& path) {
    HANDLE hFile = CreateFileA(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                                OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) {
        throw std::runtime_error("Cannot open file: " + path);
    }

    LARGE_INTEGER liSize;
    GetFileSizeEx(hFile, &liSize);
    size_t fileSize = static_cast<size_t>(liSize.QuadPart);

    if (fileSize > 512ULL * 1024 * 1024) {
        CloseHandle(hFile);
        throw std::runtime_error("File too large: " + path);
    }

    HANDLE hMapping = CreateFileMappingA(hFile, nullptr, PAGE_READONLY, 0, 0, nullptr);
    if (!hMapping) {
        CloseHandle(hFile);
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) throw std::runtime_error("Cannot open file: " + path);
        fileSize = static_cast<size_t>(file.tellg());
        file.seekg(0);
        std::vector<uint8_t> data(fileSize);
        file.read(reinterpret_cast<char*>(data.data()), fileSize);
        return data;
    }

    void* mapped = MapViewOfFile(hMapping, FILE_MAP_READ, 0, 0, fileSize);
    if (!mapped) {
        CloseHandle(hMapping);
        CloseHandle(hFile);
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) throw std::runtime_error("Cannot open file: " + path);
        fileSize = static_cast<size_t>(file.tellg());
        file.seekg(0);
        std::vector<uint8_t> data(fileSize);
        file.read(reinterpret_cast<char*>(data.data()), fileSize);
        return data;
    }

    std::vector<uint8_t> data(fileSize);
    memcpy(data.data(), mapped, fileSize);

    UnmapViewOfFile(mapped);
    CloseHandle(hMapping);
    CloseHandle(hFile);

    return data;
}

// Parallel file loading
std::vector<ModelLoader::FileData> ModelLoader::loadFilesParallel(const std::vector<std::string>& paths) {
    std::vector<std::future<FileData>> futures;
    futures.reserve(paths.size());

    for (const auto& path : paths) {
        futures.push_back(std::async(std::launch::async, [&path]() -> FileData {
            return FileData{path, readFileMapped(path)};
        }));
    }

    std::vector<FileData> results;
    results.reserve(paths.size());
    for (auto& f : futures) {
        results.push_back(f.get());
    }
    return results;
}

// Load common image with GDI+ fallback
static bool loadCommonImage(const std::string& imagePath, std::vector<uint8_t>& outRgba, int& outW, int& outH) {
    static bool gdiplusInitialized = false;
    static ULONG_PTR gdiplusToken = 0;

    if (!gdiplusInitialized) {
        Gdiplus::GdiplusStartupInput gdiplusStartupInput;
        Gdiplus::Status status = GdiplusStartup(&gdiplusToken, &gdiplusStartupInput, nullptr);
        gdiplusInitialized = (status == Gdiplus::Ok);
    }

    if (!gdiplusInitialized) return false;

    int wideLen = MultiByteToWideChar(CP_UTF8, 0, imagePath.c_str(), -1, nullptr, 0);
    if (wideLen <= 0) return false;
    std::wstring widePath(wideLen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, imagePath.c_str(), -1, &widePath[0], wideLen);

    Gdiplus::Bitmap bitmap(widePath.c_str());
    if (bitmap.GetLastStatus() != Gdiplus::Ok) return false;

    outW = bitmap.GetWidth();
    outH = bitmap.GetHeight();
    if (outW <= 0 || outH <= 0) return false;

    outRgba.resize(static_cast<size_t>(outW) * outH * 4);
    Gdiplus::Rect rect(0, 0, outW, outH);
    Gdiplus::BitmapData bitmapData;
    bitmap.LockBits(&rect, Gdiplus::ImageLockModeRead, PixelFormat32bppARGB, &bitmapData);

    if (bitmapData.Scan0) {
        uint8_t* src = static_cast<uint8_t*>(bitmapData.Scan0);
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int srcOff = y * static_cast<int>(bitmapData.Stride) + x * 4;
                int dstOff = (y * outW + x) * 4;
                outRgba[dstOff + 0] = src[srcOff + 2];
                outRgba[dstOff + 1] = src[srcOff + 1];
                outRgba[dstOff + 2] = src[srcOff + 0];
                outRgba[dstOff + 3] = src[srcOff + 3];
            }
        }
        bitmap.UnlockBits(&bitmapData);
        return true;
    }
    return false;
}

static bool isCommonImageExt(const std::string& ext) {
    return ext == ".png" || ext == ".jpg" || ext == ".jpeg" || ext == ".bmp" || ext == ".tga";
}

// Thread-safe texture cache
static void cacheTexture(const std::string& key, std::vector<uint8_t>&& rgba, int w, int h) {
    std::lock_guard<std::mutex> lock(ModelLoader::s_cacheMutex);
    ModelLoader::CachedTexture ct;
    ct.rgbaData = std::move(rgba);
    ct.width = w;
    ct.height = h;
    ModelLoader::s_textureCache[key] = std::move(ct);
}

static bool getCachedTexture(const std::string& key, ModelLoader::CachedTexture& out) {
    std::lock_guard<std::mutex> lock(ModelLoader::s_cacheMutex);
    auto it = ModelLoader::s_textureCache.find(key);
    if (it != ModelLoader::s_textureCache.end()) {
        out = it->second;
        return true;
    }
    return false;
}

void ModelLoader::clearTextureCache() {
    std::lock_guard<std::mutex> lock(s_cacheMutex);
    s_textureCache.clear();
}

// Discover textures with caching (parallel)
static void discoverTextures(
    const std::string& materialsDir,
    std::unordered_map<std::string, std::string>& materialToTexture,
    std::unordered_map<std::string, VmtInfo>& vmtInfoMap,
    std::string& defaultTexture,
    std::vector<ModelLoader::TextureData>& outTextureData
) {
    if (!fs::exists(materialsDir)) return;

    std::vector<fs::path> vmtFiles, vtfFiles, commonImageFiles;

    for (auto& entry : fs::recursive_directory_iterator(materialsDir)) {
        if (!entry.is_regular_file()) continue;
        auto path = entry.path();
        auto ext = path.extension().string();
        std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);

        if (ext == ".vmt") vmtFiles.push_back(path);
        else if (ext == ".vtf") vtfFiles.push_back(path);
        else if (isCommonImageExt(ext)) commonImageFiles.push_back(path);
    }

    // Parse VMT files (fast, sequential)
    for (auto& path : vmtFiles) {
        VmtInfo vmtInfo = parseVmtMaterial(path.string());
        if (!vmtInfo.baseTexture.empty()) {
            fs::path relPath = fs::relative(path, materialsDir);
            std::string matName = relPath.string();
            std::replace(matName.begin(), matName.end(), '\\', '/');
            if (matName.size() >= 4) matName = matName.substr(0, matName.size() - 4);

            materialToTexture[matName] = vmtInfo.baseTexture;
            vmtInfoMap[matName] = vmtInfo;
            if (defaultTexture.empty()) defaultTexture = vmtInfo.baseTexture;
        }
    }

    // Decode VTF files in parallel using thread pool
    std::mutex texDataMutex;
    size_t vtfCount = vtfFiles.size();
    std::atomic<size_t> vtfIndex{0};

    auto decodeVtfTask = [&]() {
        while (true) {
            size_t idx = vtfIndex.fetch_add(1);
            if (idx >= vtfCount) break;
            auto& path = vtfFiles[idx];

            fs::path relPath = fs::relative(path, materialsDir);
            std::string texPath = relPath.string();
            std::replace(texPath.begin(), texPath.end(), '\\', '/');
            if (texPath.size() >= 4) texPath = texPath.substr(0, texPath.size() - 4);

            // Check cache first
            ModelLoader::CachedTexture cached;
            if (getCachedTexture(texPath, cached)) {
                std::lock_guard<std::mutex> lock(texDataMutex);
                ModelLoader::TextureData td;
                td.name = texPath;
                td.width = cached.width;
                td.height = cached.height;
                td.rgbaData = cached.rgbaData;
                outTextureData.push_back(std::move(td));
                return;
            }

            try {
                auto vtfData = ModelLoader::readFileMapped(path.string());
                auto decoded = vtf::VtfDecoder::decode(vtfData);

                cacheTexture(texPath,
                    std::vector<uint8_t>(decoded.rgbaData.begin(), decoded.rgbaData.end()),
                    decoded.width, decoded.height);

                std::lock_guard<std::mutex> lock(texDataMutex);
                ModelLoader::TextureData td;
                td.name = texPath;
                td.width = decoded.width;
                td.height = decoded.height;
                td.rgbaData = std::move(decoded.rgbaData);
                outTextureData.push_back(std::move(td));
            } catch (const std::exception& e) {
                std::cerr << "[VTF] Failed to load " << path << ": " << e.what() << std::endl;
                std::string baseName = path.stem().string();
                fs::path parent = path.parent_path();
                std::string fallbackExts[] = {".png", ".jpg", ".jpeg", ".bmp", ".tga"};
                for (auto& fbExt : fallbackExts) {
                    fs::path fbPath = parent / (baseName + fbExt);
                    if (fs::exists(fbPath)) {
                        std::vector<uint8_t> rgba;
                        int w = 0, h = 0;
                        if (loadCommonImage(fbPath.string(), rgba, w, h) && w > 0 && h > 0) {
                            cacheTexture(texPath, std::vector<uint8_t>(rgba), w, h);
                            std::lock_guard<std::mutex> lock(texDataMutex);
                            ModelLoader::TextureData td;
                            td.name = texPath;
                            td.width = w;
                            td.height = h;
                            td.rgbaData = std::move(rgba);
                            outTextureData.push_back(std::move(td));
                            return;
                        }
                    }
                }
            }
        }
    };

    // Launch parallel VTF decoding
    size_t numThreads = std::min(vtfCount, static_cast<size_t>(std::thread::hardware_concurrency()));
    std::vector<std::future<void>> vtfFutures;
    for (size_t t = 0; t < numThreads; t++) {
        vtfFutures.push_back(std::async(std::launch::async, decodeVtfTask));
    }
    for (auto& f : vtfFutures) f.get();

    // Common images (sequential, fast)
    for (auto& path : commonImageFiles) {
        fs::path relPath = fs::relative(path, materialsDir);
        std::string texPath = relPath.string();
        std::replace(texPath.begin(), texPath.end(), '\\', '/');
        size_t dotPos = texPath.rfind('.');
        if (dotPos != std::string::npos) texPath = texPath.substr(0, dotPos);

        ModelLoader::CachedTexture cached;
        if (getCachedTexture(texPath, cached)) continue;

        std::vector<uint8_t> rgba;
        int w = 0, h = 0;
        if (loadCommonImage(path.string(), rgba, w, h) && w > 0 && h > 0) {
            cacheTexture(texPath, std::vector<uint8_t>(rgba), w, h);
            ModelLoader::TextureData td;
            td.name = texPath;
            td.width = w;
            td.height = h;
            td.rgbaData = std::move(rgba);
            outTextureData.push_back(std::move(td));
        }
    }
}

ModelLoader::LoadedModel::~LoadedModel() {}

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
    model->cacheKey = baseDir + "|" + modelName;

    std::string pkgDir = baseDir + "/" + modelName;

    if (!fs::exists(pkgDir)) {
        throw std::runtime_error("Model directory not found: " + pkgDir);
    }

    // Collect file paths
    std::string mdlPath, vvdPath, vtxPath, luaPath, smdPath;
    std::string materialsDir;
    std::vector<std::string> allFiles;

    for (auto& entry : fs::recursive_directory_iterator(pkgDir)) {
        if (!entry.is_regular_file()) continue;
        std::string fileName = entry.path().filename().string();
        std::string lowerName = fileName;
        std::transform(lowerName.begin(), lowerName.end(), lowerName.begin(), ::tolower);

        if (lowerName.ends_with(".mdl")) {
            if (mdlPath.empty()) mdlPath = entry.path().string();
        } else if (lowerName.ends_with(".vvd")) {
            if (vvdPath.empty()) vvdPath = entry.path().string();
        } else if (lowerName.ends_with(".dx90.vtx")) {
            if (vtxPath.empty()) vtxPath = entry.path().string();
        } else if (lowerName.ends_with(".lua")) {
            luaPath = entry.path().string();
        } else if (lowerName.ends_with(".smd")) {
            if (smdPath.empty()) smdPath = entry.path().string();
        }
    }

    // If no MDL trio, try SMD
    if (mdlPath.empty() && !smdPath.empty()) {
        return loadFromSmd(pkgDir, smdPath, materialsDir);
    }

    // Load model files in parallel
    std::vector<std::string> filesToLoad;
    if (!mdlPath.empty()) filesToLoad.push_back(mdlPath);
    if (!vvdPath.empty()) filesToLoad.push_back(vvdPath);
    if (!vtxPath.empty()) filesToLoad.push_back(vtxPath);

    auto loadedFiles = loadFilesParallel(filesToLoad);

    // Map loaded files back
    std::vector<uint8_t> mdlData, vvdData, vtxData;
    for (auto& f : loadedFiles) {
        if (f.path == mdlPath) mdlData = std::move(f.data);
        else if (f.path == vvdPath) vvdData = std::move(f.data);
        else if (f.path == vtxPath) vtxData = std::move(f.data);
    }

    // Parse Lua metadata
    if (!luaPath.empty()) {
        std::ifstream luaFile(luaPath);
        std::string line;
        while (std::getline(luaFile, line)) {
            size_t s = line.find_first_not_of(" \t\r");
            if (s == std::string::npos) continue;
            line = line.substr(s);
            if (line.rfind("--", 0) == 0) continue;

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

            auto extractPath = [](const std::string& line, const std::string& func) -> std::string {
                auto pos = line.find(func + "(");
                if (pos == std::string::npos) {
                    pos = line.find(func + " (");
                    if (pos == std::string::npos) return "";
                }
                size_t start = pos + func.length() + 1;
                int parenD = 1;
                for (size_t i = start; i < line.length() && parenD > 0; i++) {
                    if (line[i] == '(') parenD++;
                    else if (line[i] == ')') parenD--;
                    else if (line[i] == ',' && parenD == 1) {
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
            if (!modelRef.empty()) model->includeModelPaths.push_back(modelRef);
            modelRef = extractPath(line, "list.Set");
            if (!modelRef.empty()) model->includeModelPaths.push_back(modelRef);

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

    // Find materials directory (walk up to find shared materials folder)
    fs::path pkgDirPath(pkgDir);
    materialsDir = (pkgDirPath / "materials").string();
    if (!fs::exists(materialsDir)) {
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

    // Parse MDL
    MdlParser::ParsedMdl parsedMdl;
    if (!mdlData.empty()) {
        parsedMdl = MdlParser::parse(mdlData);
    } else {
        throw std::runtime_error("No .mdl file found in " + pkgDir);
    }

    // Populate bodypart metadata
    size_t numBodyParts = parsedMdl.bodyParts.size();
    model->bodyParts.reserve(numBodyParts);
    for (size_t i = 0; i < numBodyParts; i++) {
        BodyPartInfo bpInfo;
        bpInfo.name = (i < parsedMdl.bodyPartNames.size()) ? parsedMdl.bodyPartNames[i] : "";
        bpInfo.numModels = parsedMdl.bodyParts[i].nummodels;
        bpInfo.baseIndex = parsedMdl.bodyParts[i].baseIndex;
        for (size_t mi = 0; mi < parsedMdl.modelBodyPartIndices.size(); mi++) {
            if (parsedMdl.modelBodyPartIndices[mi] == static_cast<int>(i)) {
                bpInfo.modelNames.push_back(std::string(parsedMdl.models[mi].name));
            }
        }
        model->bodyParts.push_back(std::move(bpInfo));
    }

    model->numSkinRef = parsedMdl.header.numskinref;
    model->numSkinFamilies = parsedMdl.header.numskinfamilies;
    model->skinTable = parsedMdl.skinTable;

    // Parse VVD
    VvdParser::ParsedVvd parsedVvd;
    if (!vvdData.empty()) {
        parsedVvd = VvdParser::parse(vvdData);
    } else {
        throw std::runtime_error("No .vvd file found in " + pkgDir);
    }

    // Parse VTX
    VtxParser::ParsedVtx parsedVtx;
    if (!vtxData.empty()) {
        parsedVtx = VtxParser::parse(vtxData);
    } else {
        throw std::runtime_error("No .dx90.vtx file found in " + pkgDir);
    }

    // Pre-allocate mesh vectors
    size_t estimatedMeshes = parsedMdl.meshes.size();
    if (estimatedMeshes > 0) {
        model->meshes.reserve(estimatedMeshes);
    }

    // Build meshes from parsed data
    model->meshes = buildMeshes(parsedMdl, parsedVvd, parsedVtx, 0);
    model->minZ = computeMinZ(model->meshes);

    // Build lower LOD meshes
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
        std::vector<TextureData> texDataList;
        discoverTextures(materialsDir, matToTex, vmtInfoMap, defaultTexName, texDataList);

        std::string cdPrefix;
        if (!parsedMdl.cdTextures.empty()) {
            cdPrefix = parsedMdl.cdTextures[0];
            std::replace(cdPrefix.begin(), cdPrefix.end(), '\\', '/');
            if (!cdPrefix.empty() && cdPrefix.back() != '/') cdPrefix += '/';
        }
        std::string cdPrefixLower = toLower(cdPrefix);

        // Build ordered lists
        std::vector<std::string> orderedBaseTexKeys;
        for (auto& [matPath, baseTex] : matToTex) {
            std::string baseLower = toLower(baseTex);
            if (cdPrefixLower.empty() || baseLower.find(cdPrefixLower) == 0) {
                orderedBaseTexKeys.push_back(std::move(baseLower));
            }
        }
        std::sort(orderedBaseTexKeys.begin(), orderedBaseTexKeys.end());

        std::vector<std::string> orderedTexKeys;
        orderedTexKeys.reserve(texDataList.size());
        for (auto& td : texDataList) {
            std::string lowerPath = toLower(td.name);
            if (cdPrefixLower.empty() || lowerPath.find(cdPrefixLower) == 0) {
                orderedTexKeys.push_back(td.name);
            }
        }
        std::sort(orderedTexKeys.begin(), orderedTexKeys.end());

        // Build index-based texture lookup for fast access
        std::unordered_map<std::string, int> texNameToIdx;
        texNameToIdx.reserve(texDataList.size());
        for (int i = 0; i < static_cast<int>(texDataList.size()); i++) {
            texNameToIdx[toLower(texDataList[i].name)] = i;
        }

        // Map mesh textures
        int numMeshes = static_cast<int>(model->meshes.size());
        for (int meshIdx = 0; meshIdx < numMeshes; meshIdx++) {
            auto& mesh = model->meshes[meshIdx];

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

            bool textureFound = false;

            // Try matching by cdtexture-indexed material names from MDL texture list
            if (texIndex >= 0 && texIndex < static_cast<int>(parsedMdl.textureNames.size())) {
                std::string texName = toLower(parsedMdl.textureNames[texIndex]);
                std::replace(texName.begin(), texName.end(), '\\', '/');

                // Try exact match with cd prefix
                std::string fullTexName = cdPrefixLower + texName;
                auto texIt = texNameToIdx.find(fullTexName);
                if (texIt == texNameToIdx.end()) {
                    texIt = texNameToIdx.find(texName);
                }
                if (texIt == texNameToIdx.end()) {
                    // Partial match
                    for (auto& [k, vi] : texNameToIdx) {
                        if (k.find(texName) != std::string::npos || texName.find(k) != std::string::npos) {
                            texIt = texNameToIdx.find(k);
                            break;
                        }
                    }
                }

                if (texIt != texNameToIdx.end() && texIt->second >= 0 &&
                    texIt->second < static_cast<int>(texDataList.size()) &&
                    !texDataList[texIt->second].rgbaData.empty()) {
                    TextureData td = std::move(texDataList[texIt->second]);
                    int texDataIdx = static_cast<int>(model->textureData.size());
                    model->textureData.push_back(std::move(td));
                    model->textures.push_back({0, 0, 0});
                    model->meshTextureMap[meshIdx] = texDataIdx;
                    mesh.textureName = texName;

                    // Apply color tint and rendering flags from VMT
                    for (auto& [matKey, vmtInfo] : vmtInfoMap) {
                        std::string btLower = toLower(vmtInfo.baseTexture);
                        std::replace(btLower.begin(), btLower.end(), '\\', '/');
                        if (btLower == texName || toLower(texName).find(btLower) != std::string::npos) {
                            if (vmtInfo.hasColorTint) {
                                mesh.colorTint[0] = vmtInfo.colorTint[0];
                                mesh.colorTint[1] = vmtInfo.colorTint[1];
                                mesh.colorTint[2] = vmtInfo.colorTint[2];
                                mesh.colorTint[3] = vmtInfo.colorTint[3];
                            }
                            mesh.translucent = vmtInfo.translucent;
                            mesh.alphaTest = vmtInfo.alphaTest;
                            mesh.noCull = vmtInfo.noCull;
                            break;
                        }
                    }
                    textureFound = true;
                }
            }

            // Fallback: Use cd-prefixed base texture keys
            if (!textureFound && !orderedBaseTexKeys.empty()) {
                int idx = texIndex >= 0 ? texIndex % static_cast<int>(orderedBaseTexKeys.size()) : 0;
                const std::string& btPath = orderedBaseTexKeys[idx];

                auto texIt = texNameToIdx.find(btPath);
                if (texIt == texNameToIdx.end()) {
                    for (auto& [k, vi] : texNameToIdx) {
                        if (k.find(btPath) != std::string::npos || btPath.find(k) != std::string::npos) {
                            texIt = texNameToIdx.find(k);
                            break;
                        }
                    }
                }

                if (texIt != texNameToIdx.end() && !texDataList[texIt->second].rgbaData.empty()) {
                    TextureData td = std::move(texDataList[texIt->second]);
                    int texDataIdx = static_cast<int>(model->textureData.size());
                    model->textureData.push_back(std::move(td));
                    model->textures.push_back({0, 0, 0});
                    model->meshTextureMap[meshIdx] = texDataIdx;
                    mesh.textureName = btPath;
                    textureFound = true;
                }
            }

            // Fallback: Use texture index to select Nth ordered texture
            if (!textureFound && !orderedTexKeys.empty()) {
                int idx = texIndex >= 0 ? texIndex % static_cast<int>(orderedTexKeys.size())
                                        : meshIdx % static_cast<int>(orderedTexKeys.size());
                const std::string& texKey = orderedTexKeys[idx];
                auto texIt = texNameToIdx.find(toLower(texKey));
                if (texIt != texNameToIdx.end() && !texDataList[texIt->second].rgbaData.empty()) {
                    TextureData td = std::move(texDataList[texIt->second]);
                    int texDataIdx = static_cast<int>(model->textureData.size());
                    model->textureData.push_back(std::move(td));
                    model->textures.push_back({0, 0, 0});
                    model->meshTextureMap[meshIdx] = texDataIdx;
                    mesh.textureName = texKey;
                }
            }
        }
    }

    return model;
}

// SMD fallback loader
std::unique_ptr<ModelLoader::LoadedModel> ModelLoader::loadFromSmd(
    const std::string& pkgDir,
    const std::string& smdPath,
    const std::string& materialsDir)
{
    auto model = std::make_unique<LoadedModel>();
    model->name = fs::path(smdPath).stem().string();
    model->modelScale = 1.0f;
    model->hasSkinData = false;
    model->minZ = 0.0f;
    model->fallbackTexture = 0;

    // Parse SMD file
    struct SmdBone { std::string name; int parent; };
    struct SmdVertex { float x, y, z, nx, ny, nz, u, v; int bone; };
    struct SmdTriangle { SmdVertex verts[3]; std::string material; };

    std::vector<SmdBone> smdBones;
    std::vector<SmdTriangle> smdTriangles;

    std::ifstream file(smdPath);
    if (!file) throw std::runtime_error("Cannot open SMD file: " + smdPath);

    std::string line;
    int section = 0;
    int version = 0;
    while (std::getline(file, line)) {
        auto trim = [](std::string& s) {
            s.erase(0, s.find_first_not_of(" \t\r"));
            s.erase(s.find_last_not_of(" \t\r") + 1);
        };
        trim(line);

        if (line.empty() || line[0] == '#') continue;

        if (line == "version 1") { version = 1; continue; }
        if (line == "nodes") { section = 1; continue; }
        if (line == "skeleton") { section = 2; continue; }
        if (line == "triangles") { section = 3; continue; }
        if (line == "end") { section = 0; continue; }

        if (section == 1) {
            // nodes: id parent "name"
            std::istringstream iss(line);
            int id, parent;
            std::string name;
            char quote;
            iss >> id >> parent >> quote;
            std::getline(iss, name, '"');
            if (!name.empty()) {
                if (static_cast<int>(smdBones.size()) <= id) smdBones.resize(id + 1);
                smdBones[id] = {name, parent};
            }
        } else if (section == 3) {
            // triangles: material
            std::string material = line;
            SmdTriangle tri;
            tri.material = material;
            for (int i = 0; i < 3; i++) {
                if (!std::getline(file, line)) break;
                trim(line);
                std::istringstream viss(line);
                SmdVertex& v = tri.verts[i];
                int boneRef;
                viss >> boneRef >> v.x >> v.y >> v.z >> v.nx >> v.ny >> v.nz >> v.u >> v.v;
                v.bone = boneRef;
            }
            smdTriangles.push_back(std::move(tri));
        }
    }

    if (smdTriangles.empty())
        throw std::runtime_error("SMD file has no triangles: " + smdPath);

    // Build meshes from triangles
    std::unordered_map<std::string, std::vector<MeshVertex>> meshVerts;
    std::unordered_map<std::string, std::vector<uint32_t>> meshIndices;
    std::unordered_map<std::string, int> meshCounts;
    int globalIdx = 0;

    for (auto& tri : smdTriangles) {
        auto& verts = meshVerts[tri.material];
        auto& indices = meshIndices[tri.material];

        for (int i = 0; i < 3; i++) {
            SmdVertex& sv = tri.verts[i];
            MeshVertex mv;
            // Source -> Minecraft coordinate conversion
            mv.x = -sv.y; mv.y = sv.z; mv.z = sv.x;
            mv.nx = -sv.ny; mv.ny = sv.nz; mv.nz = sv.nx;
            mv.u = sv.u; mv.v = 1.0f - sv.v;
            verts.push_back(mv);
            indices.push_back(static_cast<uint32_t>(verts.size() - 1));
        }
    }

    // Load textures if materials directory exists
    std::string smdMaterialsDir_str = materialsDir;
    if (smdMaterialsDir_str.empty()) {
        fs::path pkgDirPath(pkgDir);
        smdMaterialsDir_str = (pkgDirPath / "materials").string();
        if (!fs::exists(smdMaterialsDir_str)) {
            fs::path ancestor = pkgDirPath.parent_path();
            while (!ancestor.empty()) {
                fs::path candidate = ancestor / "materials";
                if (fs::exists(candidate)) {
                    smdMaterialsDir_str = candidate.string();
                    break;
                }
                ancestor = ancestor.parent_path();
            }
        }
    }

    if (!smdMaterialsDir_str.empty() && fs::exists(smdMaterialsDir_str)) {
        std::unordered_map<std::string, std::string> matToTex;
        std::unordered_map<std::string, VmtInfo> vmtInfoMap;
        std::string defaultTexName;
        std::vector<TextureData> texDataList;
        discoverTextures(smdMaterialsDir_str, matToTex, vmtInfoMap, defaultTexName, texDataList);

        std::unordered_map<std::string, int> texNameToIdx;
        for (int i = 0; i < static_cast<int>(texDataList.size()); i++) {
            texNameToIdx[toLower(texDataList[i].name)] = i;
        }

        for (auto& [material, verts] : meshVerts) {
            if (verts.empty()) continue;
            MeshData mesh;
            mesh.vertices = std::move(verts);

            std::string matLower = toLower(material);
            // Try to find matching texture
            auto texIt = texNameToIdx.find(matLower);
            if (texIt == texNameToIdx.end()) {
                for (auto& [k, vi] : texNameToIdx) {
                    if (k.find(matLower) != std::string::npos || matLower.find(k) != std::string::npos) {
                        texIt = texNameToIdx.find(k);
                        break;
                    }
                }
            }
            if (texIt != texNameToIdx.end()) {
                TextureData td = texDataList[texIt->second];
                int texDataIdx = static_cast<int>(model->textureData.size());
                model->textureData.push_back(std::move(td));
                model->textures.push_back({0, 0, 0});
                mesh.textureName = matLower;
            }

            mesh.indices = std::move(meshIndices[material]);
            model->meshes.push_back(std::move(mesh));
        }
    } else {
        for (auto& [material, verts] : meshVerts) {
            if (verts.empty()) continue;
            MeshData mesh;
            mesh.vertices = std::move(verts);
            mesh.indices = std::move(meshIndices[material]);
            model->meshes.push_back(std::move(mesh));
        }
    }

    model->minZ = computeMinZ(model->meshes);
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

    // Build bind-pose bone transforms for skinning (Phase 1: no animation, use bind pose)
    int numBones = static_cast<int>(mdl.invBindPose.size());
    std::vector<Matrix4x4> boneTransforms(numBones);
    for (int i = 0; i < numBones; i++) {
        boneTransforms[i] = Matrix4x4::from3x4(mdl.bones[i].poseToBone);
    }

    if (vvdVerts.empty()) {
        std::cerr << "[MeshBuilder] No VVD vertices available for LOD " << lodLevel << std::endl;
        return result;
    }

    struct MeshInfo { int modelIdx; int vertexOffset; };
    size_t totalMeshes = 0;
    for (const auto& mdlModel : mdl.models) {
        totalMeshes += static_cast<size_t>(mdlModel.nummeshes);
    }
    std::vector<MeshInfo> meshInfos;
    meshInfos.reserve(totalMeshes);

    {
        int meshCounter = 0;
        int numModels = static_cast<int>(mdl.models.size());
        int numMeshes = static_cast<int>(mdl.meshes.size());
        for (int mi = 0; mi < numModels; mi++) {
            for (int j = 0; j < mdl.models[mi].nummeshes; j++) {
                MeshInfo info;
                info.modelIdx = mi;
                info.vertexOffset = (meshCounter < numMeshes) ? mdl.meshes[meshCounter].vertexoffset : 0;
                meshInfos.push_back(info);
                meshCounter++;
            }
        }
    }

    const auto& stripGroupsList = VtxParser::getStripGroupsForLod(vtx, lodLevel);

    int vtxMeshCount = static_cast<int>(stripGroupsList.size());
    if (vtxMeshCount == 0) {
        std::cerr << "[MeshBuilder] No VTX meshes for LOD " << lodLevel << std::endl;
        return result;
    }

    int numMeshInfos = static_cast<int>(meshInfos.size());
    int totalTris = 0;
    result.reserve(vtxMeshCount);

    for (int meshIdx = 0; meshIdx < vtxMeshCount; meshIdx++) {
        MeshData mesh;

        int vvdBase = 0;
        if (meshIdx < numMeshInfos) {
            const auto& info = meshInfos[meshIdx];
            if (info.modelIdx >= 0 && info.modelIdx < static_cast<int>(mdl.models.size())) {
                {
                    int rawBase = mdl.models[info.modelIdx].vertexindex - vvd.header.vertexDataStart;
                    if (rawBase < 0) rawBase = 0;
                    vvdBase = rawBase / VVD_VERTEX_SIZE + info.vertexOffset;
                }
            }
        }

        const auto& stripGroups = stripGroupsList[meshIdx];
        size_t estimatedVerts = 0;
        size_t estimatedIndices = 0;
        for (const auto& sg : stripGroups) {
            for (const auto& strip : sg.strips) {
                estimatedIndices += strip.indices.size();
            }
        }
        mesh.vertices.reserve(estimatedIndices);
        mesh.indices.reserve(estimatedIndices / 3);

        for (const auto& sg : stripGroups) {
            if (sg.vertices.empty() || sg.strips.empty()) continue;
            size_t numVerts = sg.vertices.size();

            for (const auto& strip : sg.strips) {
                size_t idxSize = strip.indices.size();
                for (size_t i = 0; i + 2 < idxSize; i += 3) {
                    uint32_t ci0 = strip.indices[i];
                    uint32_t ci1 = strip.indices[i + 1];
                    uint32_t ci2 = strip.indices[i + 2];

                    if (ci0 >= numVerts || ci1 >= numVerts || ci2 >= numVerts) continue;

                    int vvdIdx0 = vvdBase + sg.vertices[ci0].origMeshVertID;
                    int vvdIdx1 = vvdBase + sg.vertices[ci1].origMeshVertID;
                    int vvdIdx2 = vvdBase + sg.vertices[ci2].origMeshVertID;

                    int numVvdVerts = static_cast<int>(vvdVerts.size());
                    if (vvdIdx0 >= numVvdVerts || vvdIdx1 >= numVvdVerts || vvdIdx2 >= numVvdVerts) continue;

                    auto addVert = [&](const StudioVertexExt& sv) {
                        SkinnedVertex sk;
                        if (numBones > 0 && mdl.invBindPose.size() == static_cast<size_t>(numBones)) {
                            skinVertex(sv, boneTransforms.data(), mdl.invBindPose.data(), numBones, sk);
                        } else {
                            sk.x = sv.x; sk.y = sv.y; sk.z = sv.z;
                            sk.nx = sv.nx; sk.ny = sv.ny; sk.nz = sv.nz;
                            sk.u = sv.u; sk.v = sv.v;
                        }
                        MeshVertex mv;
                        mv.x = -sk.y; mv.y = sk.z; mv.z = sk.x;
                        mv.nx = -sk.ny; mv.ny = sk.nz; mv.nz = sk.nx;
                        mv.u = sk.u;
                        mv.v = 1.0f - sk.v;
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

    // Fallback for empty VTX
    if (result.empty() && vvdVerts.size() >= 3) {
        MeshData fallback;
        int vvdCount = static_cast<int>(vvdVerts.size());
        fallback.vertices.reserve(vvdCount);
        fallback.indices.reserve(vvdCount / 3);
        int count = 0;

        auto skinOrCopy = [&](const StudioVertexExt& sv, SkinnedVertex& sk) {
            if (numBones > 0 && mdl.invBindPose.size() == static_cast<size_t>(numBones)) {
                skinVertex(sv, boneTransforms.data(), mdl.invBindPose.data(), numBones, sk);
            } else {
                sk.x = sv.x; sk.y = sv.y; sk.z = sv.z;
                sk.nx = sv.nx; sk.ny = sv.ny; sk.nz = sv.nz;
                sk.u = sv.u; sk.v = sv.v;
            }
        };

        for (int i = 0; i + 2 < vvdCount; i++) {
            const auto& v0 = vvdVerts[i];
            const auto& v1 = vvdVerts[i + 1];
            const auto& v2 = vvdVerts[i + 2];

            SkinnedVertex sk0, sk1, sk2;
            skinOrCopy(v0, sk0);
            skinOrCopy(v1, sk1);
            skinOrCopy(v2, sk2);

            MeshVertex mv0, mv1, mv2;
            mv0.x = -sk0.y; mv0.y = sk0.z; mv0.z = sk0.x;
            mv0.nx = -sk0.ny; mv0.ny = sk0.nz; mv0.nz = sk0.nx;
            mv0.u = sk0.u; mv0.v = 1.0f - sk0.v;

            mv1.x = -sk1.y; mv1.y = sk1.z; mv1.z = sk1.x;
            mv1.nx = -sk1.ny; mv1.ny = sk1.nz; mv1.nz = sk1.nx;
            mv1.u = sk1.u; mv1.v = 1.0f - sk1.v;

            mv2.x = -sk2.y; mv2.y = sk2.z; mv2.z = sk2.x;
            mv2.nx = -sk2.ny; mv2.ny = sk2.nz; mv2.nz = sk2.nx;
            mv2.u = sk2.u; mv2.v = 1.0f - sk2.v;

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