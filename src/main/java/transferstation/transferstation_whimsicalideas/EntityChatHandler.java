package transferstation.transferstation_whimsicalideas;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.LevelTickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class EntityChatHandler {

    private static final Map<UUID, String> trackedEntities = new ConcurrentHashMap<>();
    private static final Random random = new Random();
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        if (entity instanceof ServerPlayer) return;

        trackedEntities.put(entity.getUUID(), entity.getName().getString());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent event) {
        if (event.level.isClientSide()) return;
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        int intervalTicks = Config.entityMessageInterval * 20;
        if (tickCounter < intervalTicks) return;
        tickCounter = 0;

        if (trackedEntities.isEmpty()) return;
        if (Config.entityMessages.isEmpty()) return;

        List<UUID> keys = new ArrayList<>(trackedEntities.keySet());
        UUID chosenUUID = keys.get(random.nextInt(keys.size()));
        String name = trackedEntities.get(chosenUUID);
        String message = Config.entityMessages.get(random.nextInt(Config.entityMessages.size()));
        message = message.replace("%entity%", name);

        ServerLevel level = (ServerLevel) event.level;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        tickCounter = 0;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        trackedEntities.clear();
    }
}