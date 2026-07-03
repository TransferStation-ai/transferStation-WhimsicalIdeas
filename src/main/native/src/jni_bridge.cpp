#include <jni.h>
#include <string>
#include <unordered_map>
#include <memory>
#include <iostream>

#include "model_loader.h"
#include "gl_renderer.h"

static std::unordered_map<int64_t, std::unique_ptr<ModelLoader::LoadedModel>> s_modelCache;
static int64_t s_nextHandle = 1;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeInitialize(JNIEnv* env, jclass) {
    return GlRenderer::initialize() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeLoadModel(
    JNIEnv* env, jclass, jstring baseDir, jstring modelName)
{
    const char* dirChars = env->GetStringUTFChars(baseDir, nullptr);
    const char* nameChars = env->GetStringUTFChars(modelName, nullptr);
    std::string dirStr(dirChars);
    std::string nameStr(nameChars);
    env->ReleaseStringUTFChars(baseDir, dirChars);
    env->ReleaseStringUTFChars(modelName, nameChars);

    try {
        auto model = ModelLoader::loadFromDirectory(dirStr, nameStr);
        int64_t handle = s_nextHandle++;

        // Create a white fallback texture for meshes without a real texture
        uint32_t fallbackTexture = 0;
        {
            std::vector<uint8_t> whitePixel = {255, 255, 255, 255};
            fallbackTexture = GlRenderer::uploadTexture(whitePixel, 1, 1);
        }
        model->fallbackTexture = fallbackTexture;

        // Upload textures from stored data
        for (auto& td : model->textureData) {
            if (!td.rgbaData.empty() && td.width > 0 && td.height > 0) {
                uint32_t glTex = GlRenderer::uploadTexture(td.rgbaData, td.width, td.height);
                // Find matching TextureInfo entry
                for (auto& texInfo : model->textures) {
                    if (texInfo.glTextureId == 0) {
                        texInfo.glTextureId = glTex;
                        texInfo.width = td.width;
                        texInfo.height = td.height;
                        break;
                    }
                }
            }
        }

        // Free CPU-side texture RGBA data now that it's uploaded to GPU
        for (auto& td : model->textureData) {
            td.rgbaData.clear();
            td.rgbaData.shrink_to_fit();
        }
        model->textureData.clear();
        model->textureData.shrink_to_fit();

        // Assign texture IDs to meshes, using fallback white texture if none found
        auto assignTextures = [&](std::vector<MeshData>& meshes) {
            for (size_t i = 0; i < meshes.size(); i++) {
                auto it = model->meshTextureMap.find(static_cast<int>(i));
                if (it != model->meshTextureMap.end() &&
                    it->second >= 0 &&
                    it->second < static_cast<int>(model->textures.size()) &&
                    model->textures[it->second].glTextureId != 0) {
                    meshes[i].textureId = model->textures[it->second].glTextureId;
                } else {
                    meshes[i].textureId = fallbackTexture;
                }
            }
        };
        assignTextures(model->meshes);
        assignTextures(model->lodMeshes1);
        assignTextures(model->lodMeshes2);
        assignTextures(model->lodMeshes3);

        // Build OpenGL VBO/VAO for each mesh
        auto uploadMeshes = [](std::vector<MeshData>& meshes) {
            for (auto& mesh : meshes) {
                if (mesh.vertices.empty() || mesh.indices.empty()) continue;
                uint32_t vao = GlRenderer::buildMesh(mesh.vertices, mesh.indices);
                mesh.glVao = vao;
                mesh.indexCount = static_cast<int>(mesh.indices.size());
                mesh.vertices.clear();
                mesh.vertices.shrink_to_fit();
                mesh.indices.clear();
                mesh.indices.shrink_to_fit();
            }
        };
        uploadMeshes(model->meshes);
        uploadMeshes(model->lodMeshes1);
        uploadMeshes(model->lodMeshes2);
        uploadMeshes(model->lodMeshes3);

        s_modelCache[handle] = std::move(model);
        return static_cast<jlong>(handle);
    }
    catch (const std::exception& e) {
        jclass excCls = env->FindClass("java/io/IOException");
        if (excCls) {
            env->ThrowNew(excCls, e.what());
        }
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeFreeModel(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return;

    auto& model = it->second;

    // Only destroy VAOs from meshes (textures are freed below to avoid double-free)
    auto freeMeshes = [](std::vector<MeshData>& meshes) {
        for (auto& mesh : meshes) {
            if (mesh.glVao) GlRenderer::destroyMesh(mesh.glVao);
        }
    };
    freeMeshes(model->meshes);
    freeMeshes(model->lodMeshes1);
    freeMeshes(model->lodMeshes2);
    freeMeshes(model->lodMeshes3);

    // Destroy textures from the textures list (each unique GL texture once)
    for (auto& tex : model->textures) {
        if (tex.glTextureId) GlRenderer::destroyTexture(tex.glTextureId);
    }

    // Destroy fallback texture once
    if (model->fallbackTexture) {
        GlRenderer::destroyTexture(model->fallbackTexture);
    }

    s_modelCache.erase(it);
}

JNIEXPORT jint JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetMeshCount(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return 0;
    return static_cast<jint>(it->second->meshes.size());
}

static void renderMeshList(
    const std::vector<MeshData>& meshes,
    const float* matrix,
    int packedLight)
{
    for (const auto& mesh : meshes) {
        if (!mesh.glVao || mesh.indexCount <= 0) continue;
        GlRenderer::renderMesh(mesh.glVao, mesh.indexCount, mesh.textureId,
                                matrix, packedLight, mesh.colorTint);
    }
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeRenderModel(
    JNIEnv* env, jclass, jlong handle,
    jfloatArray modelMatrix16, jint packedLight, jfloat partialTicks)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return;

    const auto& model = it->second;
    jfloat* matrix = env->GetFloatArrayElements(modelMatrix16, nullptr);

    renderMeshList(model->meshes, matrix, packedLight);

    env->ReleaseFloatArrayElements(modelMatrix16, matrix, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeRenderModelLOD(
    JNIEnv* env, jclass, jlong handle,
    jfloatArray modelMatrix16, jint packedLight, jfloat partialTicks, jint lodLevel)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return;

    const auto& model = it->second;
    jfloat* matrix = env->GetFloatArrayElements(modelMatrix16, nullptr);

    const auto& meshes = model->getMeshesForLod(lodLevel);
    renderMeshList(meshes, matrix, packedLight);

    env->ReleaseFloatArrayElements(modelMatrix16, matrix, JNI_ABORT);
}

JNIEXPORT jfloat JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetMinZ(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return 0.0f;
    return static_cast<jfloat>(it->second->minZ);
}

JNIEXPORT jfloat JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetModelScale(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return 1.0f;
    return it->second->modelScale;
}

JNIEXPORT jstring JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetDisplayName(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return nullptr;
    return env->NewStringUTF(it->second->displayName.c_str());
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeClearAllCaches(
    JNIEnv* env, jclass)
{
    for (auto& pair : s_modelCache) {
        auto& model = pair.second;
        auto freeMeshes = [](std::vector<MeshData>& meshes) {
            for (auto& mesh : meshes) {
                if (mesh.glVao) GlRenderer::destroyMesh(mesh.glVao);
            }
        };
        freeMeshes(model->meshes);
        freeMeshes(model->lodMeshes1);
        freeMeshes(model->lodMeshes2);
        freeMeshes(model->lodMeshes3);
        for (auto& tex : model->textures) {
            if (tex.glTextureId) GlRenderer::destroyTexture(tex.glTextureId);
        }
        if (model->fallbackTexture) {
            GlRenderer::destroyTexture(model->fallbackTexture);
        }
    }
    s_modelCache.clear();
}

} // extern "C"
