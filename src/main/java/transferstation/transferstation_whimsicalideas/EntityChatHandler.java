package transferstation.transferstation_whimsicalideas;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.LevelTickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class EntityChatHandler {

    private static final Map<UUID, String> trackedEntities = new ConcurrentHashMap<>();
    private static final Random random = new Random();
    private static final AtomicInteger tickCounter = new AtomicInteger(0);

    private static final Map<String, List<String>> biomeMessages = new HashMap<>();

    // Message categories
    private static final List<String> HOSTILE_MESSAGES = java.util.Arrays.asList(
        "%entity%: Grrr...",
        "%entity%: You won't escape!",
        "%entity%: I smell you...",
        "%entity%: Time to hunt!"
    );

    private static final List<String> NEUTRAL_MESSAGES = java.util.Arrays.asList(
        "%entity%: ...",
        "%entity%: Who's there?",
        "%entity%: Hmm?",
        "%entity%: ...just passing through"
    );

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        if (entity instanceof ServerPlayer) return;

        trackedEntities.put(entity.getUUID(), entity.getName().getString());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        tickCounter.set(0);
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        trackedEntities.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent event) {
        // Only fire once per server tick, not once per dimension.
        // LevelTickEvent fires once for each loaded dimension (Overworld, Nether, End).
        // We process only the Overworld to avoid sending 3x more messages than configured.
        if (event.level.isClientSide()) return;
        if (event.phase != TickEvent.Phase.END) return;
        if (trackedEntities.isEmpty()) return;
        if (!event.level.dimension().equals(ServerLevel.OVERWORLD)) return;

        tickCounter.incrementAndGet();
        if (tickCounter.get() >= getTicksUntilNextMessage()) {
            tickCounter.set(0);
            sendRandomMessageToPlayers(event.level);
        }
    }

    private static int getTicksUntilNextMessage() {
        return Config.getEntityMessageInterval() * 20;
    }

    /**
     * Enhanced version that considers entity type for message selection.
     */
private static String selectMessageForEntity(LivingEntity entity, String name) {
    ResourceLocation regKey = 
        net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
    String entityType = regKey != null ? regKey.toString() : "";

    List<String> messages;
    if (entity instanceof net.minecraft.world.entity.monster.Monster || 
        entityType.contains("monster") || entityType.contains("zombie") ||
        entityType.contains("skeleton") || entityType.contains("creeper")) {
        messages = HOSTILE_MESSAGES;
    } else if (entity instanceof net.minecraft.world.entity.animal.Animal ||
               entityType.contains("villager") || entityType.contains("animal")) {
        messages = Config.getEntityMessages(); // friendly messages
    } else {
        messages = NEUTRAL_MESSAGES;
    }
    
    if (messages == null || messages.isEmpty()) return name + " says something...";
    String message = messages.get(random.nextInt(messages.size()));
    return message.replace("%entity%", name);
}

    private static void sendRandomMessageToPlayers(net.minecraft.world.level.Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        List<UUID> keys = new ArrayList<>(trackedEntities.keySet());
        if (keys.isEmpty()) return;
        UUID chosenUUID = keys.get(random.nextInt(keys.size()));
        String name = trackedEntities.get(chosenUUID);

        // Get the actual entity for type-aware message selection
        net.minecraft.world.entity.Entity entity = serverLevel.getEntity(chosenUUID);
        String message;
        if (entity instanceof LivingEntity le) {
            message = selectMessageForEntity(le, name);
        } else {
            List<String> messages = Config.getEntityMessages();
            if (messages.isEmpty()) return;
            message = messages.get(random.nextInt(messages.size()));
            message = message.replace("%entity%", name);
        }

        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }

    @Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
    public static class CleanupHandler {
        @SubscribeEvent
        public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
            trackedEntities.clear();
        }
    }
}