package transferstation.transferstation_whimsicalideas.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadProgress;
import transferstation.transferstation_whimsicalideas.client.model.MdlModelRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-only helper for ModelSyncManager.
 * Lives in the client package to avoid classloading client-only classes (MdlModelRenderer,
 * ModelLoadManager, ModelLoadProgress) on a dedicated server.
 */
public final class ModelSyncClientHelper {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PREWARM_CONCURRENT = 4;

    private ModelSyncClientHelper() {}

    /**
     * Initialize paths on client-only renderer/load manager classes.
     */
    public static void initializePaths(Path modelsDir, Path cacheDir) {
        MdlModelRenderer.setModelsDir(modelsDir);
        MdlModelRenderer.setCacheDir(cacheDir);
        ModelLoadManager.setCacheDir(cacheDir);
    }

    /**
     * Pre-warm model caches by initiating async loading of all discovered models.
     * Called only on the client distribution. All references herein are client-only classes.
     */
    public static void prewarmCaches(List<String> discoveredModels, Path modelsDir) {
        if (modelsDir == null || !Files.exists(modelsDir)) {
            LOGGER.debug("[ModelSyncClientHelper] Cannot pre-warm caches, models directory unavailable");
            return;
        }

        if (discoveredModels.isEmpty()) {
            LOGGER.debug("[ModelSyncClientHelper] No models to pre-warm");
            return;
        }

        // Sort: prioritize the player's current model to avoid first-render contention
        List<String> ordered = new ArrayList<>(discoveredModels);
        String currentModel = MdlModelRenderer.getCurrentModel();
        if (currentModel != null) {
            int idx = ordered.indexOf(currentModel);
            if (idx > 0) {
                ordered.remove(idx);
                ordered.add(0, currentModel);
            }
        }

        // Set up multi-model batch progress tracking
        ModelLoadProgress.beginBatch(ordered.size());

        int prewarmed = 0;
        int deferred = 0;
        for (int i = 0; i < ordered.size(); i++) {
            String modelName = ordered.get(i);
            Path packageDir = modelsDir.resolve(modelName);
            if (!Files.exists(packageDir)) continue;

            if (prewarmed >= MAX_PREWARM_CONCURRENT) {
                deferred++;
                ModelLoadProgress.advanceBatch();
                continue;
            }

            ModelLoadManager.loadModelAsync(packageDir)
                    .thenAccept(data -> {
                        if (data != null) {
                            LOGGER.debug("[ModelSyncClientHelper] Pre-warmed model '{}' ({} meshes, {} triangles)",
                                    modelName, data.meshes.size(), data.totalTriangles());
                        } else {
                            LOGGER.debug("[ModelSyncClientHelper] Pre-warm completed with no data for '{}'", modelName);
                        }
                        ModelLoadProgress.advanceBatch();
                    })
                    .exceptionally(throwable -> {
                        LOGGER.debug("[ModelSyncClientHelper] Pre-warm failed for '{}': {}", modelName, throwable.getMessage());
                        ModelLoadProgress.advanceBatch();
                        return null;
                    });
            prewarmed++;
        }

        LOGGER.info("[ModelSyncClientHelper] Initiated async pre-warm for {} models (deferred {} to on-demand loading, current='{}')",
                prewarmed, deferred, currentModel);
    }
}
