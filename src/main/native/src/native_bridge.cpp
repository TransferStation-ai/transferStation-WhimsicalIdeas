#include <jni.h>
#include <string>
#include <unordered_map>
#include <memory>
#include <iostream>
#include <vector>
#include <mutex>

#include "model_loader.h"
#include "gl_renderer.h"
#include "physics_simulation.h"
#include "gpu_skinning.h"

static std::unordered_map<int64_t, std::unique_ptr<ModelLoader::LoadedModel>> s_modelCache;
static int64_t s_nextHandle = 1;

// Path-based model dedup cache (maps cacheKey -> handle)
static std::unordered_map<std::string, int64_t> s_modelPathCache;

// GPU skinning mesh cache
static std::unordered_map<int64_t, GpuSkinning::SkinnedMesh> s_skinnedMeshes;
static int64_t s_skinNextHandle = 1;

// Protects all caches from concurrent access by multiple Java threads.
static std::mutex s_cacheMutex;

// Upload CPU-side mesh/texture data to the GPU. MUST run on the render thread,
// because the OpenGL context is only current there. Called lazily from the
// render entry points so model loading can happen on worker threads.
static void uploadModelToGpu(ModelLoader::LoadedModel* model) {
    if (model->gpuReady) return;

    // Create a white fallback texture for meshes without a real texture
    std::vector<uint8_t> whitePixel = {255, 255, 255, 255};
    model->fallbackTexture = GlRenderer::uploadTexture(whitePixel, 1, 1);

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
                meshes[i].textureId = model->fallbackTexture;
            }
        }
    };
    assignTextures(model->meshes);
    assignTextures(model->lodMeshes1);
    assignTextures(model->lodMeshes2);
    assignTextures(model->lodMeshes3);

    // Build OpenGL VBO/VAO for each mesh. Keep the CPU-side vertex/index
    // copies after upload: nativeCreateSkinnedMesh (a separate JNI call)
    // needs them to build GPU-skinned variants. Clearing here would make
    // skinning unreachable.
    auto uploadMeshes = [](std::vector<MeshData>& meshes) {
        for (auto& mesh : meshes) {
            if (mesh.vertices.empty() || mesh.indices.empty()) continue;
            if (mesh.renderMode == RenderMode::SKIP) continue;
            uint32_t vao = GlRenderer::buildMesh(mesh.vertices, mesh.indices);
            mesh.glVao = vao;
            mesh.indexCount = static_cast<int>(mesh.indices.size());
        }
    };
    uploadMeshes(model->meshes);
    uploadMeshes(model->lodMeshes1);
    uploadMeshes(model->lodMeshes2);
    uploadMeshes(model->lodMeshes3);

    model->gpuReady = true;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeInitialize(JNIEnv* env, jclass) {
    return GlRenderer::initialize() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeLoadModel(
    JNIEnv* env, jclass, jstring baseDir, jstring modelName)
{
    if (baseDir == nullptr || modelName == nullptr) return 0;
    const char* dirChars = env->GetStringUTFChars(baseDir, nullptr);
    const char* nameChars = env->GetStringUTFChars(modelName, nullptr);
    if (dirChars == nullptr || nameChars == nullptr) {
        if (dirChars) env->ReleaseStringUTFChars(baseDir, dirChars);
        if (nameChars) env->ReleaseStringUTFChars(modelName, nameChars);
        return 0;
    }
    std::string dirStr(dirChars);
    std::string nameStr(nameChars);
    env->ReleaseStringUTFChars(baseDir, dirChars);
    env->ReleaseStringUTFChars(modelName, nameChars);

    try {
        std::string cacheKey = dirStr + "|" + nameStr;
        {
            std::lock_guard<std::mutex> lock(s_cacheMutex);
            auto it = s_modelPathCache.find(cacheKey);
            if (it != s_modelPathCache.end()) {
                // Check if the cached handle is still alive
                auto modelIt = s_modelCache.find(it->second);
                if (modelIt != s_modelCache.end()) {
                    return static_cast<jlong>(it->second);
                }
                // Stale entry, remove it
                s_modelPathCache.erase(it);
            }
        }

        auto model = ModelLoader::loadFromDirectory(dirStr, nameStr);
        int64_t handle = 0;
        {
            std::lock_guard<std::mutex> lock(s_cacheMutex);
            handle = s_nextHandle++;
        }

        // NOTE: GPU upload (textures/VAOs) is deliberately deferred to the render
        // thread via uploadModelToGpu() in nativeRenderModel/nativeRenderModelLOD.
        // Creating GL objects here on the ModelLoader thread would fail because the
        // OpenGL context is only current on the render thread, leaving every mesh
        // with glVao == 0 and rendering nothing.

        {
            std::lock_guard<std::mutex> lock(s_cacheMutex);
            s_modelCache[handle] = std::move(model);
            s_modelPathCache[cacheKey] = handle;
        }
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

    {
        std::lock_guard<std::mutex> lock(s_cacheMutex);
        for (auto pit = s_modelPathCache.begin(); pit != s_modelPathCache.end(); ) {
            if (pit->second == handle) {
                pit = s_modelPathCache.erase(pit);
            } else {
                ++pit;
            }
        }
        s_modelCache.erase(it);
    }
}

JNIEXPORT jint JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetMeshCount(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return 0;
    return static_cast<jint>(it->second->meshes.size());
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeSetCameraPosition(
    JNIEnv* env, jclass, jfloat x, jfloat y, jfloat z)
{
    GlRenderer::setCameraPosition(static_cast<float>(x), static_cast<float>(y), static_cast<float>(z));
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeSetViewProjection(
    JNIEnv* env, jclass, jfloatArray viewProjection16)
{
    if (viewProjection16 == nullptr) return;
    if (env->GetArrayLength(viewProjection16) < 16) return;
    jfloat* vp = env->GetFloatArrayElements(viewProjection16, nullptr);
    if (vp == nullptr) return;
    GlRenderer::setViewProjection(vp);
    env->ReleaseFloatArrayElements(viewProjection16, vp, JNI_ABORT);
}

static void renderMeshList(
    const std::vector<MeshData>& meshes,
    const float* matrix,
    int packedLight)
{
    for (const auto& mesh : meshes) {
        if (mesh.renderMode == RenderMode::SKIP) continue;
        if (!mesh.glVao || mesh.indexCount <= 0) continue;
        GlRenderer::renderMesh(mesh.glVao, mesh.indexCount, mesh.textureId,
                                matrix, packedLight, mesh.colorTint, mesh.renderMode);
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
    if (modelMatrix16 == nullptr) return;
    if (env->GetArrayLength(modelMatrix16) < 16) return;
    jfloat* matrix = env->GetFloatArrayElements(modelMatrix16, nullptr);
    if (matrix == nullptr) return;

    try {
        // GL upload must happen on the render thread (this is called from there).
        uploadModelToGpu(model.get());
        renderMeshList(model->meshes, matrix, packedLight);
    } catch (...) {
        env->ReleaseFloatArrayElements(modelMatrix16, matrix, JNI_ABORT);
        return;
    }

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
    if (modelMatrix16 == nullptr) return;
    if (env->GetArrayLength(modelMatrix16) < 16) return;
    jfloat* matrix = env->GetFloatArrayElements(modelMatrix16, nullptr);
    if (matrix == nullptr) return;

    try {
        // GL upload must happen on the render thread (this is called from there).
        uploadModelToGpu(model.get());
        const auto& meshes = model->getMeshesForLod(lodLevel);
        renderMeshList(meshes, matrix, packedLight);
    } catch (...) {
        env->ReleaseFloatArrayElements(modelMatrix16, matrix, JNI_ABORT);
        return;
    }

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
    for (auto& pair : s_skinnedMeshes) {
        GpuSkinning::destroySkinnedMesh(pair.second);
    }
    {
        std::lock_guard<std::mutex> lock(s_cacheMutex);
        s_skinnedMeshes.clear();
        s_modelCache.clear();
    }
    ModelLoader::clearTextureCache();
}

// ===================== Physics Simulation JNI =====================

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativePhysicsInitialize(JNIEnv* env, jclass) {
    return PhysicsSimulation::initialize() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeCreateRigidBody(
    JNIEnv* env, jclass, jfloat x, jfloat y, jfloat z, jfloat mass)
{
    PhysicsSimulation::Vec3 pos(x, y, z);
    PhysicsSimulation::Quat rot(0, 0, 0, 1);
    return static_cast<jlong>(PhysicsSimulation::createRigidBody(pos, rot, mass));
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeDestroyRigidBody(
    JNIEnv* env, jclass, jlong id)
{
    PhysicsSimulation::destroyRigidBody(static_cast<uint64_t>(id));
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetVelocity(
    JNIEnv* env, jclass, jlong id, jfloat vx, jfloat vy, jfloat vz)
{
    PhysicsSimulation::Vec3 vel(vx, vy, vz);
    PhysicsSimulation::setRigidBodyVelocity(static_cast<uint64_t>(id), vel);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeApplyImpulse(
    JNIEnv* env, jclass, jlong id, jfloat ix, jfloat iy, jfloat iz)
{
    PhysicsSimulation::Vec3 impulse(ix, iy, iz);
    PhysicsSimulation::applyImpulse(static_cast<uint64_t>(id), impulse);
}

JNIEXPORT jfloatArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeGetPosition(
    JNIEnv* env, jclass, jlong id)
{
    auto body = PhysicsSimulation::getRigidBody(static_cast<uint64_t>(id));
    jfloatArray result = env->NewFloatArray(3);
    if (result) {
        jfloat pos[3] = { body.position.x, body.position.y, body.position.z };
        env->SetFloatArrayRegion(result, 0, 3, pos);
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeGetRotation(
    JNIEnv* env, jclass, jlong id)
{
    auto body = PhysicsSimulation::getRigidBody(static_cast<uint64_t>(id));
    jfloatArray result = env->NewFloatArray(4);
    if (result) {
        jfloat quat[4] = { body.rotation.x, body.rotation.y, body.rotation.z, body.rotation.w };
        env->SetFloatArrayRegion(result, 0, 4, quat);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeStepSimulation(
    JNIEnv* env, jclass, jfloat deltaTime)
{
    PhysicsSimulation::stepSimulation(deltaTime);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetGravity(
    JNIEnv* env, jclass, jfloat gx, jfloat gy, jfloat gz)
{
    PhysicsSimulation::Vec3 gravity(gx, gy, gz);
    PhysicsSimulation::setGravity(gravity);
}

JNIEXPORT jlong JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeCreateJoint(
    JNIEnv* env, jclass, jlong bodyA, jlong bodyB,
    jfloat pax, jfloat pay, jfloat paz, jfloat pbx, jfloat pby, jfloat pbz)
{
    PhysicsSimulation::Vec3 pivotA(pax, pay, paz);
    PhysicsSimulation::Vec3 pivotB(pbx, pby, pbz);
    return static_cast<jlong>(PhysicsSimulation::createJoint(
        static_cast<uint64_t>(bodyA), static_cast<uint64_t>(bodyB), pivotA, pivotB));
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeDestroyJoint(
    JNIEnv* env, jclass, jlong id)
{
    PhysicsSimulation::destroyJoint(static_cast<uint64_t>(id));
}

// ===================== Bullet3-Style Physics JNI =====================

JNIEXPORT jlong JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeCreateRigidBodyWithShape(
    JNIEnv* env, jclass, jfloat x, jfloat y, jfloat z, jfloat mass,
    jint shapeType, jfloatArray shapeParams)
{
    PhysicsSimulation::Vec3 pos(x, y, z);
    PhysicsSimulation::Quat rot(0, 0, 0, 1);
    PhysicsSimulation::Vec3 param1(0.3f, 0.3f, 0.3f);
    float param2 = 0.0f;

    if (shapeParams != nullptr) {
        jfloat* params = env->GetFloatArrayElements(shapeParams, nullptr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return 0;
        }
        jsize len = env->GetArrayLength(shapeParams);
        if (len >= 1) param1.x = params[0];
        if (len >= 2) param1.y = params[1];
        if (len >= 3) param1.z = params[2];
        if (len >= 4) param2 = params[3];
        env->ReleaseFloatArrayElements(shapeParams, params, JNI_ABORT);
    }

    try {
        return static_cast<jlong>(PhysicsSimulation::createRigidBodyWithShape(
            pos, rot, mass,
            static_cast<PhysicsSimulation::CollisionShape::Type>(shapeType),
            param1, param2));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetRestitution(
    JNIEnv* env, jclass, jlong id, jfloat restitution)
{
    PhysicsSimulation::setRestitution(static_cast<uint64_t>(id), restitution);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetFriction(
    JNIEnv* env, jclass, jlong id, jfloat friction)
{
    PhysicsSimulation::setFriction(static_cast<uint64_t>(id), friction);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetLinearFactor(
    JNIEnv* env, jclass, jlong id, jfloat fx, jfloat fy, jfloat fz)
{
    PhysicsSimulation::Vec3 factor(fx, fy, fz);
    PhysicsSimulation::setLinearFactor(static_cast<uint64_t>(id), factor);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetAngularFactor(
    JNIEnv* env, jclass, jlong id, jfloat fx, jfloat fy, jfloat fz)
{
    PhysicsSimulation::Vec3 factor(fx, fy, fz);
    PhysicsSimulation::setAngularFactor(static_cast<uint64_t>(id), factor);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetKinematicPose(
    JNIEnv* env, jclass, jlong id,
    jfloat px, jfloat py, jfloat pz,
    jfloat rx, jfloat ry, jfloat rz, jfloat rw)
{
    PhysicsSimulation::Vec3 pos(px, py, pz);
    PhysicsSimulation::Quat rot(rx, ry, rz, rw);
    PhysicsSimulation::setKinematicPose(static_cast<uint64_t>(id), pos, rot);
}

JNIEXPORT jlong JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeCreateConeTwistJoint(
    JNIEnv* env, jclass,
    jlong bodyA, jlong bodyB,
    jfloat pax, jfloat pay, jfloat paz,
    jfloat pbx, jfloat pby, jfloat pbz,
    jfloat swingSpan1, jfloat swingSpan2, jfloat twistSpan)
{
    PhysicsSimulation::Vec3 pivotA(pax, pay, paz);
    PhysicsSimulation::Vec3 pivotB(pbx, pby, pbz);
    return static_cast<jlong>(PhysicsSimulation::createConeTwistJoint(
        static_cast<uint64_t>(bodyA), static_cast<uint64_t>(bodyB),
        pivotA, pivotB, swingSpan1, swingSpan2, twistSpan));
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetJointLimit(
    JNIEnv* env, jclass, jlong jointId, jfloat linearLimit, jfloat angularLimit)
{
    PhysicsSimulation::setJointLimit(static_cast<uint64_t>(jointId), linearLimit, angularLimit);
}

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeRaycast(
    JNIEnv* env, jclass,
    jfloat fromX, jfloat fromY, jfloat fromZ,
    jfloat toX, jfloat toY, jfloat toZ,
    jfloatArray hitPointOut, jfloatArray hitNormalOut)
{
    PhysicsSimulation::Vec3 from(fromX, fromY, fromZ);
    PhysicsSimulation::Vec3 to(toX, toY, toZ);
    PhysicsSimulation::Vec3 hitPoint, hitNormal;

    bool result = PhysicsSimulation::raycast(from, to, hitPoint, hitNormal);

    if (result && hitPointOut != nullptr && hitNormalOut != nullptr) {
        jfloat point[3] = { hitPoint.x, hitPoint.y, hitPoint.z };
        jfloat normal[3] = { hitNormal.x, hitNormal.y, hitNormal.z };
        env->SetFloatArrayRegion(hitPointOut, 0, 3, point);
        env->SetFloatArrayRegion(hitNormalOut, 0, 3, normal);
    }

    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeSetEnvironmentMesh(
    JNIEnv* env, jclass, jfloatArray vertices, jintArray indices)
{
    if (vertices == nullptr || indices == nullptr) {
        PhysicsSimulation::clearEnvironmentMesh();
        return;
    }

    jsize vertCount = env->GetArrayLength(vertices) / 3;
    jsize idxCount = env->GetArrayLength(indices);
    if (vertCount <= 0 || idxCount < 3) {
        PhysicsSimulation::clearEnvironmentMesh();
        return;
    }

    jfloat* verts = env->GetFloatArrayElements(vertices, nullptr);
    jint* idxs = env->GetIntArrayElements(indices, nullptr);
    if (verts == nullptr || idxs == nullptr) {
        if (verts) env->ReleaseFloatArrayElements(vertices, verts, JNI_ABORT);
        if (idxs) env->ReleaseIntArrayElements(indices, idxs, JNI_ABORT);
        return;
    }

    std::vector<PhysicsSimulation::Vec3> v;
    v.reserve(static_cast<size_t>(vertCount));
    for (jsize i = 0; i < vertCount; i++) {
        v.emplace_back(verts[i * 3 + 0], verts[i * 3 + 1], verts[i * 3 + 2]);
    }

    std::vector<int> idx(static_cast<size_t>(idxCount));
    for (jsize i = 0; i < idxCount; i++) {
        idx[static_cast<size_t>(i)] = static_cast<int>(idxs[i]);
    }

    env->ReleaseFloatArrayElements(vertices, verts, JNI_ABORT);
    env->ReleaseIntArrayElements(indices, idxs, JNI_ABORT);

    PhysicsSimulation::setEnvironmentMesh(v.data(), static_cast<int>(v.size()),
                                          idx.data(), static_cast<int>(idx.size()));
}

// ===================== Bone Data Extraction JNI =====================
// Java CPU skinning must apply the same invBindPose multiply as the native
// renderer (source-engine-native bone semantics). Expose the authoritative
// C++-computed matrices here so Java replaces its missing-invBind path.

JNIEXPORT jint JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetBoneCount(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return 0;
    return static_cast<jint>(it->second->boneInvBindPose.size());
}

JNIEXPORT jfloatArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetBoneInvBindPose(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return nullptr;

    const auto& invBind = it->second->boneInvBindPose;
    size_t boneCount = invBind.size();
    if (boneCount == 0) return nullptr;

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(boneCount * 16));
    if (!result) return nullptr;

    std::vector<jfloat> flat(boneCount * 16);
    for (size_t i = 0; i < boneCount; i++) {
        for (size_t j = 0; j < 16; j++) {
            flat[i * 16 + j] = invBind[i].m[j];
        }
    }
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

JNIEXPORT jintArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetBoneParent(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return nullptr;

    const auto& parents = it->second->boneParent;
    if (parents.empty()) return nullptr;

    jintArray result = env->NewIntArray(static_cast<jsize>(parents.size()));
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(parents.size()),
        reinterpret_cast<const jint*>(parents.data()));
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetBoneNames(
    JNIEnv* env, jclass, jlong handle)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return nullptr;

    const auto& names = it->second->boneNames;
    if (names.empty()) return nullptr;

    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(names.size()), stringClass, nullptr);
    if (!result) return nullptr;

    for (size_t i = 0; i < names.size(); i++) {
        jstring s = env->NewStringUTF(names[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return result;
}

// ===================== Mesh Data Extraction JNI =====================

JNIEXPORT jfloatArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetMeshVertices(
    JNIEnv* env, jclass, jlong handle, jint meshIndex)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return nullptr;

    const auto& model = it->second;
    int idx = static_cast<int>(meshIndex);
    if (idx < 0 || idx >= static_cast<int>(model->meshes.size())) return nullptr;

    const auto& mesh = model->meshes[idx];
    size_t vertCount = mesh.vertices.size();
    if (vertCount == 0) return nullptr;

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(vertCount * 8));
    if (!result) return nullptr;

    std::vector<jfloat> flat(vertCount * 8);
    for (size_t i = 0; i < vertCount; i++) {
        flat[i * 8 + 0] = mesh.vertices[i].x;
        flat[i * 8 + 1] = mesh.vertices[i].y;
        flat[i * 8 + 2] = mesh.vertices[i].z;
        flat[i * 8 + 3] = mesh.vertices[i].nx;
        flat[i * 8 + 4] = mesh.vertices[i].ny;
        flat[i * 8 + 5] = mesh.vertices[i].nz;
        flat[i * 8 + 6] = mesh.vertices[i].u;
        flat[i * 8 + 7] = mesh.vertices[i].v;
    }
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

JNIEXPORT jintArray JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeGetMeshIndices(
    JNIEnv* env, jclass, jlong handle, jint meshIndex)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return nullptr;

    const auto& model = it->second;
    int idx = static_cast<int>(meshIndex);
    if (idx < 0 || idx >= static_cast<int>(model->meshes.size())) return nullptr;

    const auto& mesh = model->meshes[idx];
    size_t idxCount = mesh.indices.size();
    if (idxCount == 0) return nullptr;

    jintArray result = env->NewIntArray(static_cast<jsize>(idxCount));
    if (!result) return nullptr;

    // Convert uint32_t to jint
    std::vector<jint> jIndices(idxCount);
    for (size_t i = 0; i < idxCount; i++) {
        jIndices[i] = static_cast<jint>(mesh.indices[i]);
    }
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(idxCount), jIndices.data());
    return result;
}

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeIsMeshTranslucent(
    JNIEnv* env, jclass, jlong handle, jint meshIndex)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return JNI_FALSE;
    int idx = static_cast<int>(meshIndex);
    if (idx < 0 || idx >= static_cast<int>(it->second->meshes.size())) return JNI_FALSE;
    return it->second->meshes[idx].translucent ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeIsMeshAlphaTest(
    JNIEnv* env, jclass, jlong handle, jint meshIndex)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return JNI_FALSE;
    int idx = static_cast<int>(meshIndex);
    if (idx < 0 || idx >= static_cast<int>(it->second->meshes.size())) return JNI_FALSE;
    return it->second->meshes[idx].alphaTest ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeIsMeshNoCull(
    JNIEnv* env, jclass, jlong handle, jint meshIndex)
{
    auto it = s_modelCache.find(handle);
    if (it == s_modelCache.end()) return JNI_FALSE;
    int idx = static_cast<int>(meshIndex);
    if (idx < 0 || idx >= static_cast<int>(it->second->meshes.size())) return JNI_FALSE;
    return it->second->meshes[idx].noCull ? JNI_TRUE : JNI_FALSE;
}

// ===================== GPU Skinning JNI Bridge =====================

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeSkinningAvailable(
    JNIEnv* env, jclass)
{
    return GpuSkinning::isAvailable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeSkinningInitialize(
    JNIEnv* env, jclass)
{
    return GpuSkinning::initialize() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeCreateSkinnedMesh(
    JNIEnv* env, jclass, jlong modelHandle, jint meshIndex)
{
    auto it = s_modelCache.find(modelHandle);
    if (it == s_modelCache.end()) return 0;

    int idx = static_cast<int>(meshIndex);
    if (idx < 0 || idx >= static_cast<int>(it->second->meshes.size())) return 0;

    const auto& mesh = it->second->meshes[idx];
    size_t vertCount = mesh.vertices.size();
    if (vertCount == 0) return 0;

    // Flatten MeshVertex data to the format expected by GpuSkinning::createSkinnedMesh
    std::vector<float> verts(vertCount * 8);
    for (size_t i = 0; i < vertCount; i++) {
        verts[i * 8 + 0] = mesh.vertices[i].x;
        verts[i * 8 + 1] = mesh.vertices[i].y;
        verts[i * 8 + 2] = mesh.vertices[i].z;
        verts[i * 8 + 3] = mesh.vertices[i].nx;
        verts[i * 8 + 4] = mesh.vertices[i].ny;
        verts[i * 8 + 5] = mesh.vertices[i].nz;
        verts[i * 8 + 6] = mesh.vertices[i].u;
        verts[i * 8 + 7] = mesh.vertices[i].v;
    }

    size_t idxCount = mesh.indices.size();
    std::vector<uint32_t> indices(idxCount);
    for (size_t i = 0; i < idxCount; i++) {
        indices[i] = mesh.indices[i];
    }

    // Default bone data (no bone influences - model is static)
    std::vector<uint8_t> boneIndices(vertCount * 4, 0);
    std::vector<float> boneWeights(vertCount * 4, 0.0f);
    for (size_t i = 0; i < vertCount; i++) {
        boneWeights[i * 4] = 1.0f;
    }

    auto skinnedMesh = GpuSkinning::createSkinnedMesh(verts, indices, boneIndices, boneWeights);
    skinnedMesh.textureId = mesh.textureId;

    int64_t skinHandle = 0;
    {
        std::lock_guard<std::mutex> lock(s_cacheMutex);
        skinHandle = s_skinNextHandle++;
        s_skinnedMeshes[skinHandle] = skinnedMesh;
    }

    return static_cast<jlong>(skinHandle);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeDestroySkinnedMesh(
    JNIEnv* env, jclass, jlong skinHandle)
{
    auto it = s_skinnedMeshes.find(skinHandle);
    if (it == s_skinnedMeshes.end()) return;
    GpuSkinning::destroySkinnedMesh(it->second);
    s_skinnedMeshes.erase(it);
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeSkinAndRenderMesh(
    JNIEnv* env, jclass, jlong skinHandle, jfloatArray boneMatrices,
    jint boneCount, jfloatArray modelMatrix, jint packedLight, jfloatArray colorTint)
{
    auto it = s_skinnedMeshes.find(skinHandle);
    if (it == s_skinnedMeshes.end()) return;

    auto& mesh = it->second;
    if (!mesh.valid) return;

    // Validate bone matrix array length before reinterpretation.
    if (boneMatrices != nullptr && env->GetArrayLength(boneMatrices) < boneCount * 16) return;

    jfloat* bones = boneMatrices ? env->GetFloatArrayElements(boneMatrices, nullptr) : nullptr;
    jfloat* modelMat = modelMatrix ? env->GetFloatArrayElements(modelMatrix, nullptr) : nullptr;
    jfloat* tint = colorTint ? env->GetFloatArrayElements(colorTint, nullptr) : nullptr;

    try {
        GpuSkinning::skinMesh(mesh,
            reinterpret_cast<const GpuSkinning::BoneMatrix*>(bones),
            static_cast<int>(boneCount),
            modelMat, static_cast<int>(packedLight));

        if (modelMat != nullptr) {
            GpuSkinning::renderSkinnedMesh(mesh, mesh.textureId, modelMat, static_cast<int>(packedLight), tint);
        }
    } catch (...) {
        if (bones) env->ReleaseFloatArrayElements(boneMatrices, bones, JNI_ABORT);
        if (modelMat) env->ReleaseFloatArrayElements(modelMatrix, modelMat, JNI_ABORT);
        if (tint) env->ReleaseFloatArrayElements(colorTint, tint, JNI_ABORT);
        return;
    }

    if (bones) env->ReleaseFloatArrayElements(boneMatrices, bones, JNI_ABORT);

    if (modelMat) env->ReleaseFloatArrayElements(modelMatrix, modelMat, JNI_ABORT);

    if (tint) env->ReleaseFloatArrayElements(colorTint, tint, JNI_ABORT);
}

} // extern "C"
