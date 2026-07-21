#ifndef MODEL_LOADER_H
#define MODEL_LOADER_H

#include "studio_types.h"
#include "mdl_parser.h"
#include "vvd_parser.h"
#include "vtx_parser.h"
#include "vtf_decoder.h"
#include <string>
#include <string_view>
#include <vector>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <future>

class ModelLoader {
public:
    struct TextureInfo {
        uint32_t glTextureId;
        int width;
        int height;
    };

    struct TextureData {
        std::string name;
        int width;
        int height;
        std::vector<uint8_t> rgbaData;
    };

    struct BodyPartInfo {
        std::string name;
        int numModels;
        int baseIndex;
        std::vector<std::string> modelNames;
    };

    struct LoadedModel {
        std::string name;
        std::string displayName;
        std::string author;
        float modelScale;
        
        std::vector<MeshData> meshes;
        std::vector<MeshData> lodMeshes1;
        std::vector<MeshData> lodMeshes2;
        std::vector<MeshData> lodMeshes3;
        std::vector<TextureInfo> textures;
        std::vector<TextureData> textureData;
        std::unordered_map<int, int> meshTextureMap;

        std::vector<std::string> includeModelPaths;
        std::vector<BodyPartInfo> bodyParts;
        int numSkinRef;
        int numSkinFamilies;
        std::vector<int32_t> skinTable;

        bool hasSkinData;
        float minZ;
        uint32_t fallbackTexture;
        
        const std::vector<MeshData>& getMeshesForLod(int lod) const {
            if (lod <= 0 || lod > 3) return meshes;
            if (lod == 1 && !lodMeshes1.empty()) return lodMeshes1;
            if (lod == 2 && !lodMeshes2.empty()) return lodMeshes2;
            if (lod == 3 && !lodMeshes3.empty()) return lodMeshes3;
            return meshes;
        }

        ~LoadedModel();
    };

    static std::unique_ptr<LoadedModel> loadFromDirectory(
        const std::string& baseDir, 
        const std::string& modelName
    );

    // SMD fallback loader
    static std::unique_ptr<LoadedModel> loadFromSmd(
        const std::string& pkgDir,
        const std::string& smdPath,
        const std::string& materialsDir
    );

    static std::vector<MeshData> buildMeshes(
        const MdlParser::ParsedMdl& mdl,
        const VvdParser::ParsedVvd& vvd,
        const VtxParser::ParsedVtx& vtx,
        int lodLevel = 0
    );

    static void clearTextureCache();

    // Parallel file loading
    struct FileData {
        std::string path;
        std::vector<uint8_t> data;
    };

private:
    static float computeMinZ(const std::vector<MeshData>& meshes);
    static std::vector<FileData> loadFilesParallel(const std::vector<std::string>& paths);

    // Optimized string utilities
    static std::string toLower(std::string_view s);
    static void toLowerInPlace(std::string& s);

public:
    // Memory-mapped file reading
    static std::vector<uint8_t> readFileMapped(const std::string& path);

    // Static texture cache shared across model loads
    struct CachedTexture {
        std::vector<uint8_t> rgbaData;
        int width;
        int height;
    };
    static std::unordered_map<std::string, CachedTexture> s_textureCache;
    static std::mutex s_cacheMutex;
};

// ----- ModelManager静态接口 -----
#include "model_manager.h"

#endif // MODEL_LOADER_H
