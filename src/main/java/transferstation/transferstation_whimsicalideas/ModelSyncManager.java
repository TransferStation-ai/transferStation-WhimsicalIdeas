package transferstation.transferstation_whimsicalideas;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.ModelSyncClientHelper;
import transferstation.transferstation_whimsicalideas.client.model.NpcModelRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages automatic model scanning, caching, and sync when players join the world.
 * Heavily inspired by YesSteveModel's ServerModelManager pattern.
 *
 * On player join, this triggers:
 * 1. Re-scan of model directories for new packages
 * 2. Pre-warming of model caches
 * 3. Notification to the connecting player
 */
public class ModelSyncManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Track when models were last scanned to avoid redundant scans
    private static long lastScanTime = 0L;
    // Default minimum scan interval (overridden by config if loaded)
    private static long minScanIntervalMs = 10_000L;
    private static int cachedModelCount = 0;
    private static int cachedNpcCount = 0;
    private static boolean initialized = false;

    // Resolved model directory path (stored to avoid referencing client-only MdlModelRenderer)
    private static Path resolvedModelsDir = null;

    // Cached list of discovered model names (relative paths from models dir)
    static final List<String> discoveredModels = Collections.synchronizedList(new ArrayList<>());

    /**
     * Initialize the sync manager. Called on server start.
     * Sets up cache directories and performs initial scan.
     */
    public static void initialize() {
        if (initialized) {
            LOGGER.debug("[ModelSyncManager] Already initialized, skipping");
            return;
        }

        Path configDir = FMLPaths.CONFIGDIR.get().resolve(Transferstation_whimsicalideas.MODID);
        resolvedModelsDir = configDir.resolve("models");
        Path cacheDir = configDir.resolve("cache");

        try {
            Files.createDirectories(resolvedModelsDir);
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOGGER.error("[ModelSyncManager] Failed to create model/cache directories", e);
        }

        // Notify client-only renderer/load manager about the cache directory.
        // On dedicated server these classes don't exist, so we use a helper that
        // is only loaded when on the client distribution.
        if (FMLEnvironment.dist.isClient()) {
            ModelSyncClientHelper.initializePaths(resolvedModelsDir, cacheDir);
        }

        // Use configured scan interval (convert seconds to ms)
        minScanIntervalMs = Config.getModelSyncScanInterval() * 1000L;

        initialized = true;
        LOGGER.info("[ModelSyncManager] Initialized with modelsDir={}, cacheDir={}, scanInterval={}ms",
                resolvedModelsDir, cacheDir, minScanIntervalMs);

        // Perform initial scan
        scanAndSync();
    }

    /**
     * Scan model directories and pre-load model info into caches.
     * Called when a player joins, respects MIN_SCAN_INTERVAL_MS.
     * Returns true if a new scan was performed.
     */
    public static boolean scanAndSync() {
        if (!initialized) {
            LOGGER.warn("[ModelSyncManager] Not initialized, call initialize() first");
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastScanTime < minScanIntervalMs) {
            LOGGER.debug("[ModelSyncManager] Skipping scan, only {}ms since last scan (minimum {}ms)",
                    now - lastScanTime, minScanIntervalMs);
            return false;
        }

        if (resolvedModelsDir == null || !Files.exists(resolvedModelsDir)) {
            LOGGER.warn("[ModelSyncManager] Models directory does not exist: {}", resolvedModelsDir);
            return false;
        }

        // Discover model packages (directories containing .mdl or .smd files)
        List<String> newlyDiscovered = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resolvedModelsDir)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(resolvedModelsDir))
                    .forEach(dir -> {
                        if (hasAnyModelFile(dir)) {
                            String relativePath = resolvedModelsDir.relativize(dir).toString().replace('\\', '/');
                            if (!newlyDiscovered.contains(relativePath)) {
                                newlyDiscovered.add(relativePath);
                            }
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("[ModelSyncManager] Failed to walk models directory", e);
            return false;
        }

        // Update the discovered model list
        discoveredModels.clear();
        discoveredModels.addAll(newlyDiscovered);
        cachedModelCount = discoveredModels.size();

        // Run NpcModelRegistry scan to discover and register any new NPC entity types
        Path configDir = FMLPaths.CONFIGDIR.get().resolve(Transferstation_whimsicalideas.MODID);
        NpcModelRegistry.scanAndRegister(configDir);
        cachedNpcCount = NpcModelRegistry.getNpcCount();

        lastScanTime = System.currentTimeMillis();

        LOGGER.info("[ModelSyncManager] Scan complete: {} model packages found, {} NPC entity types registered",
                cachedModelCount, cachedNpcCount);

        // Pre-warm model caches for discovered models
        prewarmModelCaches();

        return true;
    }

    /**
     * Force a full rescan regardless of time interval.
     */
    public static void forceRescan() {
        long prev = lastScanTime;
        lastScanTime = 0L;
        boolean performed = scanAndSync();
        if (!performed) {
            // If scanAndSync returned false for reasons other than time interval,
            // restore the previous timestamp
            lastScanTime = prev;
            LOGGER.warn("[ModelSyncManager] forceRescan did not complete successfully");
        }
    }

    /**
     * Get the count of available NPC entity types.
     */
    public static int getNpcCount() {
        return cachedNpcCount;
    }

    /**
     * Get the count of available model packages.
     */
    public static int getModelCount() {
        return cachedModelCount;
    }

    /**
     * Send sync info to a specific player (system message with model count).
     */
    public static void sendSyncInfoToPlayer(ServerPlayer player) {
        if (player == null) return;

        Component message = Component.literal(
                "§7[TransferStation] §fModel pack synced: §e" + cachedModelCount
                        + " §fmodels, §e" + cachedNpcCount + " §fNPC types available."
                        + " Use §b/npc §fto browse and summon."
        );
        player.sendSystemMessage(message);

        LOGGER.debug("[ModelSyncManager] Sent sync info to player {}", player.getScoreboardName());
    }

    /**
     * Pre-warm model caches by initiating async loading of all discovered models.
     * This improves first-render performance when players first see NPCs.
     * Delegates to a client-only helper to avoid classloading client classes on dedicated server.
     */
    public static void prewarmModelCaches() {
        if (!FMLEnvironment.dist.isClient()) {
            LOGGER.debug("[ModelSyncManager] Skipping model pre-warm on dedicated server (no client renderer)");
            return;
        }
        ModelSyncClientHelper.prewarmCaches(discoveredModels, resolvedModelsDir);
    }

    /**
     * Check if a directory contains any Source engine model files.
     */
    private static boolean hasAnyModelFile(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> {
                String name = f.getFileName().toString().toLowerCase();
                return name.endsWith(".mdl") || name.endsWith(".smd")
                        || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".bbmodel");
            });
        } catch (IOException e) {
            return false;
        }
    }
}
