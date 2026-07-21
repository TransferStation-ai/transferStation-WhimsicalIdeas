package transferstation.transferstation_whimsicalideas.client.model;

/**
 * 模型加载完成后的回调接口。
 * 用于在模型加载时获取诊断信息、触发后处理或日志记录。
 */
@FunctionalInterface
public interface ModelLoadCallback {
    /**
     * 模型加载完成后调用（无论成功或失败）。
     * @param modelPath 模型目录的绝对路径字符串
     * @param diagnostics 诊断信息，包含模型元数据和加载状态
     */
    void onModelLoaded(String modelPath, ModelLoadDiagnostics diagnostics);
}
