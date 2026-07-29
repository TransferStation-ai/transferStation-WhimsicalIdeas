#include "model_manager.h"
#include <filesystem>
#include <thread>
#include <fstream>
#include <iostream>
#include <future>
#include <algorithm>
#include <cstring>
// 若有CURL/下载需要可加第三方库

namespace fs = std::filesystem;

ModelManager& ModelManager::instance() {
    static ModelManager inst;
    return inst;
}

ModelManager::ModelManager() {}
ModelManager::~ModelManager() {}

std::shared_ptr<ModelLoader::LoadedModel> ModelManager::loadModel(
    const std::string& uri,
    std::function<void(float, const std::string&)> progressCb,
    bool isAsync
) {
    std::lock_guard<std::recursive_mutex> lock(_mtx);
    auto it = _models.find(uri);
    if (it != _models.end()) {
        it->second.refCount++;
        it->second.lastAccessTick = std::time(nullptr);
        if (progressCb) progressCb(1.0f, "Already loaded");
        return it->second.model;
    }
    // 新建entry
    ModelEntry& entry = _models[uri];
    entry.refCount = 1;
    entry.isLoading = true;
    entry.uri = uri;
    entry.hasError = false;
    entry.model = nullptr;
    entry.lastAccessTick = std::time(nullptr);
    // 异步or同步加载
    // Capture the model key (uri) by value and look up the entry inside the task
    // to avoid holding a reference into the _models map (which may rehash).
    auto loader = [this, uri, progressCb]() -> std::shared_ptr<ModelLoader::LoadedModel> {
        try {
            if (progressCb) progressCb(0.03f, "Resolving path...");
            std::string path = resolvePath(uri);
            if (progressCb) progressCb(0.08f, "Loading Native Model...");
            std::string base, name;
            fs::path fpath(path);
            if (fs::is_directory(fpath)) {
                base = fpath.parent_path().string(); name = fpath.filename().string();
            } else {
                base = fpath.parent_path().parent_path().string();
                name = fpath.parent_path().filename().string();
            }
            auto model = ModelLoader::loadFromDirectory(base, name);
            if (progressCb) progressCb(1.0f, "Model loaded");
            {
                std::lock_guard<std::recursive_mutex> lock(_mtx);
                auto it = _models.find(uri);
                if (it != _models.end()) {
                    it->second.model = std::move(model);
                    it->second.isLoading = false;
                    it->second.hasError = false;
                    it->second.errorMsg.clear();
                    it->second.lastAccessTick = std::time(nullptr);
                }
            }
            if (_globalProgressCb) _globalProgressCb(uri, 1.0f, "Model loaded");
            return model;
        } catch (const std::exception& e) {
            {
                std::lock_guard<std::recursive_mutex> lock(_mtx);
                auto it = _models.find(uri);
                if (it != _models.end()) {
                    it->second.model = nullptr;
                    it->second.isLoading = false;
                    it->second.hasError = true;
                    it->second.errorMsg = e.what();
                }
            }
            if (progressCb) progressCb(1.0f, std::string("Error: ") + e.what());
            if (_globalProgressCb) _globalProgressCb(uri, 1.0f, std::string("Error: ") + e.what());
            return nullptr;
        }
    };
    if (isAsync) {
        // Keep the future alive so its destructor does not block on the async task.
        _pendingLoads.push_back(std::async(std::launch::async, loader));
        return nullptr;
    } else {
        return loader();
    }
}

std::shared_ptr<ModelLoader::LoadedModel> ModelManager::getModel(const std::string& name) {
    std::lock_guard<std::recursive_mutex> lock(_mtx);
    auto it = _models.find(name);
    if (it != _models.end() && !it->second.hasError && it->second.model) {
        it->second.lastAccessTick = std::time(nullptr);
        return it->second.model;
    }
    return nullptr;
}

void ModelManager::unloadModel(const std::string& name) {
    std::lock_guard<std::recursive_mutex> lock(_mtx);
    _models.erase(name);
}

void ModelManager::reloadAllModels() {
    std::lock_guard<std::recursive_mutex> lock(_mtx);
    for (auto& [k, entry] : _models) {
        if (!entry.model || entry.hasError) continue;
        entry.isLoading = true;
        // 在主线程同步调用重新加载
        try {
            // 保存引用，重新加载
            std::string lastUri = entry.uri;
            entry.model = nullptr;
            entry.model = loadModel(lastUri, [this, lastUri](float p, const std::string& s) { if (_globalProgressCb) _globalProgressCb(lastUri, p, s); }, false);
            entry.hasError = false;
            entry.errorMsg.clear();
        } catch (const std::exception& e) {
            entry.hasError = true;
            entry.errorMsg = e.what();
            if (_globalProgressCb) _globalProgressCb(k, 1.0f, std::string("ReloadError: ") + e.what());
        }
        entry.isLoading = false;
    }
}

void ModelManager::setCacheLimit(size_t maxModels) {
    _cacheLimit = maxModels;
    cleanup();
}
size_t ModelManager::getCacheLimit() const { return _cacheLimit; }

void ModelManager::cleanup() {
    std::lock_guard<std::recursive_mutex> lock(_mtx);
    if (_models.size() <= _cacheLimit) return;
    // 简单LRU淘汰
    using Pair = std::pair<std::string, ModelEntry*>;
    std::vector<Pair> refs;
    for (auto& [name, entry] : _models) {
        if (entry.refCount == 0) refs.emplace_back(name, &entry);
    }
    if (refs.size() <= 0) return;
    std::sort(refs.begin(), refs.end(), [](const Pair& a, const Pair& b) {
        return a.second->lastAccessTick < b.second->lastAccessTick;
    });
    for (size_t i = 0; i < refs.size() && _models.size() > _cacheLimit; ++i) {
        _models.erase(refs[i].first);
    }
}

std::vector<std::string> ModelManager::listLoadedModels() const {
    std::lock_guard<std::recursive_mutex> lock(_mtx);
    std::vector<std::string> out;
    for (const auto& [k, v] : _models) {
        if (v.model && !v.hasError) out.push_back(k);
    }
    return out;
}

void ModelManager::setGlobalProgressCallback(std::function<void(const std::string&, float, const std::string&)> cb) {
    std::lock_guard<std::recursive_mutex> lock(_mtx);
    _globalProgressCb = cb;
}

// 路径解析: 本地/远端，可按需扩展
std::string ModelManager::resolvePath(const std::string& uri) {
    if (uri.rfind("http://",0)==0 || uri.rfind("https://",0)==0) {
        // TODO：可实现远程 HTTP 下载，缓存到临时目录后返回本地路径
        throw std::runtime_error("HTTP/URL模型加载未实现（需要用curl/winhttp）");
    } else if (uri.rfind("file://",0)==0) {
        return uri.substr(std::strlen("file://"));
    } else {
        // 默认视为本地路径
        return uri;
    }
}
