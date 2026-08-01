package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.item.AttachmentItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class NpcModelRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "transferstation_whimsicalideas");
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, "transferstation_whimsicalideas");
    private static final ConcurrentHashMap<String, RegistryObject<EntityType<?>>> registeredNpcs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RegistryObject<Item>> spawnEggs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RegistryObject<Item>> attachmentItems = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();
    private static final List<String> availableModels = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static RegistryObject<EntityType<NpcRagdoll>> NPC_RAGDOLL;

    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "transferstation_whimsicalideas");

    public static final RegistryObject<CreativeModeTab> NPC_TAB = CREATIVE_MODE_TABS.register("npc_tab",
            () -> CreativeModeTab.builder()
                    .icon(() -> {
                        Item firstEgg = spawnEggs.values().stream()
                                .filter(RegistryObject::isPresent)
                                .findFirst()
                                .map(RegistryObject::get)
                                .orElse(net.minecraft.world.item.Items.SPAWNER);
                        return new ItemStack(firstEgg);
                    })
                    .title(Component.translatable("itemGroup.transferstation_whimsicalideas"))
                    .displayItems((params, output) -> {
                        for (RegistryObject<Item> egg : spawnEggs.values()) {
                            if (egg.isPresent()) {
                                output.accept(egg.get());
                            }
                        }
                        for (RegistryObject<Item> att : attachmentItems.values()) {
                            if (att.isPresent()) {
                                output.accept(att.get());
                            }
                        }
                    })
                    .build());

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
        ITEMS.register(bus);
        CREATIVE_MODE_TABS.register(bus);

        NPC_RAGDOLL = ENTITY_TYPES.register("npc_ragdoll", () ->
                EntityType.Builder.<NpcRagdoll>of(NpcRagdoll::new, MobCategory.MISC)
                        .sized(0.6f, 1.8f)
                        .clientTrackingRange(8)
                        .build("npc_ragdoll")
        );

        ITEMS.register("npc_ragdoll_spawn_egg", NpcModelRegistry::createRagdollSpawnEgg);
    }

    @SuppressWarnings("unchecked")
    private static ForgeSpawnEggItem createRagdollSpawnEgg() {
        Supplier<EntityType<? extends Mob>> supplier = () ->
                (EntityType<? extends Mob>) (EntityType<?>) NPC_RAGDOLL.get();
        return new ForgeSpawnEggItem(supplier, 0x808080, 0x404040, new Item.Properties());
    }

    private static int getEggColor(String modelName, int seed) {
        int hash = Math.abs((modelName.hashCode() * 31 + seed) & Integer.MAX_VALUE);
        int r = (hash >> 16) & 0xFF;
        int g = (hash >> 8) & 0xFF;
        int b = hash & 0xFF;
        r = Math.max(40, Math.min(215, r));
        g = Math.max(40, Math.min(215, g));
        b = Math.max(40, Math.min(215, b));
        return (r << 16) | (g << 8) | b;
    }

    public static void scanAndRegister() {
        scanAndRegisterNpcModels(MdlModelRenderer.getModelsDir());
    }

    public static void scanAndRegister(Path configDir) {
        Path modelsDir = configDir.resolve("models");
        scanAndRegisterNpcModels(modelsDir);
    }

    private static void scanAndRegisterNpcModels(Path modelsDir) {
        if (modelsDir == null || !Files.exists(modelsDir)) {
            return;
        }

        // Phase 1: scan directory-based models
        try {
            Files.list(modelsDir).filter(Files::isDirectory).forEach(modelDir -> {
                String modelName = modelsDir.relativize(modelDir).toString().replace('\\', '/');
                availableModels.add(modelName);

                Path npcDir = modelDir.resolve("npc");
                if (Files.exists(npcDir) && Files.isDirectory(npcDir)) {
                    registerNpcEntity(modelName);
                }
            });
        } catch (IOException e) {
            LOGGER.error("[NpcModelRegistry] Failed to scan NPC models", e);
        }

        // Phase 2: scan VPK archives and extract models into virtual directories
        List<String> vpkFiles = VpkParser.findVpkFiles(modelsDir);
        if (!vpkFiles.isEmpty()) {
            Path vpkCacheDir = modelsDir.resolve(".vpk_cache");
            try {
                Files.createDirectories(vpkCacheDir);
            } catch (IOException e) {
                LOGGER.error("[NpcModelRegistry] Failed to create VPK cache directory", e);
                return;
            }

            for (String vpkPath : vpkFiles) {
                try {
                    Path vpkFile = Path.of(vpkPath);
                    String vpkName = vpkFile.getFileName().toString();
                    if (vpkName.endsWith("_dir.vpk")) {
                        vpkName = vpkName.substring(0, vpkName.length() - 8);
                    } else if (vpkName.endsWith(".vpk")) {
                        vpkName = vpkName.substring(0, vpkName.length() - 4);
                    }

                    Path vpkExtractDir = vpkCacheDir.resolve(vpkName);
                    long vpkModTime = Files.getLastModifiedTime(vpkFile).toMillis();
                    Path stampFile = vpkExtractDir.resolve(".vpk_extracted");

                    boolean needsExtract = true;
                    if (Files.exists(stampFile)) {
                        try {
                            long extractedModTime = Long.parseLong(Files.readString(stampFile).trim());
                            needsExtract = extractedModTime != vpkModTime;
                        } catch (Exception ignored) {}
                    }

                    if (needsExtract) {
                        LOGGER.info("[NpcModelRegistry] Extracting VPK: {} to cache", vpkName);
                        extractVpkToCache(vpkFile, vpkExtractDir);
                        Files.writeString(stampFile, String.valueOf(vpkModTime));
                    }

                    if (Files.exists(vpkExtractDir)) {
                        try (var dirs = Files.list(vpkExtractDir)) {
                            dirs.filter(Files::isDirectory).forEach(modelDir -> {
                                String modelName = modelsDir.relativize(modelDir).toString().replace('\\', '/');
                                if (!availableModels.contains(modelName)) {
                                    availableModels.add(modelName);

                                    Path npcDir = modelDir.resolve("npc");
                                    if (Files.exists(npcDir) && Files.isDirectory(npcDir)) {
                                        registerNpcEntity(modelName);
                                    }
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("[NpcModelRegistry] Failed to process VPK: {}", vpkPath, e);
                }
            }
        }
    }

    private static void extractVpkToCache(Path vpkFile, Path outputDir) throws IOException {
        VpkParser.VpkArchive archive = VpkParser.open(vpkFile);
        try {
            Set<String> modelDirs = VpkParser.listModelPaths(archive);
            for (String modelDir : modelDirs) {
                if (modelDir.equals("/")) continue;
                VpkParser.extractModelFromVpk(archive, modelDir, outputDir, false);
            }
        } finally {
            archive.close();
        }
    }

    private static void registerNpcEntity(String modelName) {
        String entityId = "npc_" + modelName.replace('/', '_').replace('\\', '_');

        if (registeredNpcs.containsKey(entityId)) {
            return;
        }

        // DeferredRegister is frozen after mod construction, so re-registering at
        // runtime (e.g. via /npc reload) throws IllegalStateException. The entity
        // type was already registered during the initial scan, so at runtime we
        // only need to ensure the in-memory model list is up to date.
        RegistryObject<EntityType<?>> entityType;
        try {
            entityType = ENTITY_TYPES.register(entityId, () ->
                    EntityType.Builder.<Mob>of((type, world) -> new NpcEntity(type, world, modelName), MobCategory.CREATURE)
                            .sized(0.6f, 1.8f)
                            .build(entityId)
            );
        } catch (IllegalStateException e) {
            LOGGER.warn("[NpcModelRegistry] DeferredRegister already frozen; skipping re-registration for {}", entityId);
            entityType = registeredNpcs.get(entityId);
        }

        if (entityType != null) {
            registeredNpcs.put(entityId, entityType);
        }

        int primaryColor = getEggColor(modelName, 0);
        int secondaryColor = getEggColor(modelName, 1);

        String displayName = modelName.contains("/") ?
                modelName.substring(modelName.lastIndexOf('/') + 1) : modelName;

        RegistryObject<Item> egg;
        try {
            RegistryObject<EntityType<?>> finalEntityType = entityType;
            egg = ITEMS.register(entityId + "_spawn_egg", () ->
                    new NpcSpawnEggItem(() -> {
                        if (finalEntityType != null) {
                            return (EntityType<? extends Mob>) finalEntityType.get();
                        }
                        return null;
                    },
                            primaryColor, secondaryColor, new Item.Properties(), displayName));
        } catch (IllegalStateException e) {
            LOGGER.warn("[NpcModelRegistry] DeferredRegister already frozen; skipping spawn egg re-registration for {}", entityId);
            egg = spawnEggs.get(entityId);
        }
        if (egg != null) {
            spawnEggs.put(entityId, egg);
        }

        LOGGER.info("[NpcModelRegistry] Registered NPC entity: {} with model: {} and spawn egg", entityId, modelName);
    }

    /**
     * Registers a built-in NPC entity type via the DeferredRegister.
     * Designed for use during mod construction (before DeferredRegister is frozen).
     * Health, speed, and armor are accepted for future attribute configuration;
     * currently only scale is applied to the entity size.
     */
    @SuppressWarnings("unchecked")
    public static RegistryObject<EntityType<?>> registerBuiltinNpc(
            String entityId, String modelPath, float health, float speed, float armor, float scale) {
        var entityType = (RegistryObject<EntityType<?>>) (RegistryObject<?>) ENTITY_TYPES.register(entityId, () ->
            EntityType.Builder.<Mob>of((type, world) -> new NpcEntity(type, world, modelPath),
                MobCategory.CREATURE)
                .sized(0.6f * scale, 1.8f * scale)
                .build(entityId));
        registeredNpcs.put(entityId, entityType);
        return entityType;
    }

    public static EntityType<NpcRagdoll> getNpcRagdollType() {
        return NPC_RAGDOLL != null ? NPC_RAGDOLL.get() : null;
    }

    public static List<String> getAvailableModels() {
        return new ArrayList<>(availableModels);
    }

    /**
     * Returns only the model names that were actually registered as spawnable
     * NPC entity types (i.e. had an {@code npc/} subdir during scan). These are
     * the models the {@code /npc spawn} and {@code /npc debug exporttextures}
     * commands can actually use.
     */
    public static List<String> getAvailableNpcModels() {
        List<String> npcModels = new ArrayList<>();
        for (String entityId : registeredNpcs.keySet()) {
            npcModels.add(entityId.substring("npc_".length()).replace('_', '/'));
        }
        return npcModels;
    }

    public static String getRandomModel() {
        if (availableModels.isEmpty()) {
            return null;
        }
        return availableModels.get(RANDOM.nextInt(availableModels.size()));
    }

    public static String getRandomNpcModel() {
        List<String> npcModels = new ArrayList<>();
        for (String entityId : registeredNpcs.keySet()) {
            String modelName = entityId.substring("npc_".length()).replace('_', '/');
            npcModels.add(modelName);
        }
        if (npcModels.isEmpty()) {
            return getRandomModel();
        }
        return npcModels.get(RANDOM.nextInt(npcModels.size()));
    }

    public static boolean hasNpcModels() {
        return !registeredNpcs.isEmpty();
    }

    public static RegistryObject<EntityType<?>> getRegisteredNpc(String entityId) {
        return registeredNpcs.get(entityId);
    }

    public static int getNpcCount() {
        return registeredNpcs.size();
    }

    public static ConcurrentHashMap<String, RegistryObject<EntityType<?>>> getRegisteredNpcs() {
        return registeredNpcs;
    }

    public static ConcurrentHashMap<String, RegistryObject<Item>> getSpawnEggs() {
        return spawnEggs;
    }

    public static RegistryObject<Item> getSpawnEgg(String entityId) {
        return spawnEggs.get(entityId);
    }

    public static RegistryObject<Item> registerAttachmentItem(String itemId, String modelName, String attachmentName) {
        if (attachmentItems.containsKey(itemId)) return attachmentItems.get(itemId);

        RegistryObject<Item> item = ITEMS.register(itemId, () ->
            new AttachmentItem(new Item.Properties(), modelName, attachmentName));
        attachmentItems.put(itemId, item);
        return item;
    }

    public static ConcurrentHashMap<String, RegistryObject<Item>> getAttachmentItems() {
        return attachmentItems;
    }

    static class NpcSpawnEggItem extends ForgeSpawnEggItem {
        private final String displayName;

        NpcSpawnEggItem(Supplier<? extends EntityType<? extends Mob>> type,
                         int backgroundColor, int highlightColor,
                         Item.Properties properties, String displayName) {
            super(type, backgroundColor, highlightColor, properties);
            this.displayName = displayName;
        }

        @Override
        public @NotNull Component getName(@NotNull ItemStack stack) {
            return Component.translatable("item.transferstation_whimsicalideas.npc_spawn_egg", displayName);
        }
    }
}

