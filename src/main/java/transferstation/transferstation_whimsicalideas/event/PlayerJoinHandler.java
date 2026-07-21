package transferstation.transferstation_whimsicalideas.event;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.Config;
import transferstation.transferstation_whimsicalideas.ModelSyncManager;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;

/**
 * Handles player join events to trigger automatic model scanning and sync.
 * <p>
 * Inspired by YesSteveModel's {@code EnterServerEvent} which sends a model sync
 * message to every player that logs in. This handler:
 * <ol>
 *   <li>Re-scans model directories for newly added model packages</li>
 *   <li>Registers any new NPC entity types discovered</li>
 *   <li>Pre-warms the model cache for faster first-render</li>
 *   <li>Notifies the joining player of available models</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public final class PlayerJoinHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Fired when a player logs into the server.
     * Triggers model scan/sync so all models are available to the joining player.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return; // Only handle server-side login
        }

        if (!Config.isAutoSyncModels()) {
            LOGGER.debug("[PlayerJoinHandler] Auto model sync disabled by config, skipping for {}",
                    serverPlayer.getScoreboardName());
            return;
        }

        LOGGER.info("[PlayerJoinHandler] Player logged in: {} (triggering model sync)",
                serverPlayer.getScoreboardName());

        // Ensure the sync manager is initialized
        ModelSyncManager.initialize();

        // Trigger model rescan and cache pre-warming
        boolean scanned = ModelSyncManager.scanAndSync();

        if (scanned) {
            LOGGER.info("[PlayerJoinHandler] Model scan completed for player join: {} models, {} NPCs",
                    ModelSyncManager.getModelCount(), ModelSyncManager.getNpcCount());
        } else {
            LOGGER.debug("[PlayerJoinHandler] Model scan skipped (recently scanned or no changes)");
        }

        // Send sync info to the joining player
        ModelSyncManager.sendSyncInfoToPlayer(serverPlayer);
    }
}
