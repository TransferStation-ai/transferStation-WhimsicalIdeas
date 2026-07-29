#ifndef MODEL_MANAGER_H
#define MODEL_MANAGER_H

#include "model_loader.h"
#include <string>
#include <unordered_map>
#include <memory>
#include <functional>
#include <mutex>
#include <vector>
#include <atomic>
#include <future>

// Forge专用 - 统一管理本地/远程模型，热加载、缓存、进度、卸载、异常上报
// 推荐由Java侧的ResourceManagerReloadListener/相关事件调用ModelManager::reloadAllModels
class ModelManager {
public:
    // 单例入口
    static ModelManager& instance();

    // 加载模型，支持本地/远端URI，异步/同步，支持进度回调
    // uri: "file://..."  "http(s)://..."  "zip://..."
    // progressCallback: 加载进度/阶段/异常回调，在主线程收发
    // isAsync: 若true则后台线程预加载，进度以回调异步汇报
    std::shared_ptr<ModelLoader::LoadedModel> loadModel(
        const std::string& uri,
        std::function<void(float, const std::string& stage)> progressCallback = nullptr,
        bool isAsync = false
    );

    // 查询已加载模型
    std::shared_ptr<ModelLoader::LoadedModel> getModel(const std::string& name);

    // 主动卸载模型，释放缓存
    void unloadModel(const std::string& name);

    // 所有模型批量热重载（配合资源刷新/目录监听），一般由Java事件驱动
    void reloadAllModels();

    // 缓存上限与策略
    void setCacheLimit(size_t maxModels);
    size_t getCacheLimit() const;

    // 清理过期/未被引用的模型，LRU淘汰
    void cleanup();

    // 获取所有已加载模型名列表
    std::vector<std::string> listLoadedModels() const;

    // 进度、异常统一事件（Java可注册回调通知UI）
    void setGlobalProgressCallback(std::function<void(const std::string& modelName, float progress, const std::string& info)> cb);

private:
    struct ModelEntry {
        std::shared_ptr<ModelLoader::LoadedModel> model;
        std::atomic<size_t> refCount;
        uint64_t lastAccessTick;
        std::string uri;
        bool isLoading = false;
        bool hasError = false;
        std::string errorMsg;
    };

    ModelManager();
    ~ModelManager();

    // Track in-flight async loads so their futures are not destroyed early
    // (destroying a std::future blocks on the async task, defeating async).
    std::vector<std::future<std::shared_ptr<ModelLoader::LoadedModel>>> _pendingLoads;

    // 不允许复制
    ModelManager(const ModelManager&) = delete;
    ModelManager& operator=(const ModelManager&) = delete;

    // 内部统一管理
    mutable std::recursive_mutex _mtx;
    std::unordered_map<std::string, ModelEntry> _models;
    size_t _cacheLimit = 32;
    std::function<void(const std::string&, float, const std::string&)> _globalProgressCb;

    // 实际加载实现
    std::shared_ptr<ModelLoader::LoadedModel> internalLoadModel(
        const std::string& uri, std::string resolvedPath,
        std::function<void(float, const std::string&)> progressCb, bool isAsync
    );
    // 本地/远程模型下载与判断
    std::string resolvePath(const std::string& uri);
};

#endif // MODEL_MANAGER_H
