package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.NpcChatScreen;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationLayers;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor;
import transferstation.transferstation_whimsicalideas.common.BodyHitboxSystem;
import transferstation.transferstation_whimsicalideas.common.InjurySystem;
import transferstation.transferstation_whimsicalideas.npc.ai.AINpcAgent;

import java.util.List;
import java.util.Map;

public class NpcEntity extends PathfinderMob {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** AI 单次骨骼指令上限 */
    private static final int MAX_AI_POSES = 8;

    // AI代理成员，AI型NPC自动挂载
    //这样就能给各种奇奇怪怪的生物上ai比如猎 头 蟹
    public final AINpcAgent aiAgent = new AINpcAgent(this);

    private final String modelName;
    private NpcData npcData;
    private String currentAnimation = "idle";

    @SuppressWarnings("unchecked")
    public NpcEntity(EntityType<?> entityType, Level world, String modelName) {
        super((EntityType<? extends PathfinderMob>) entityType, world);
        // Guard against null modelName: getDisplayName()/die() dereference it, so default it.
        this.modelName = (modelName != null) ? modelName : "unknown";
        this.npcData = new NpcData();
    }

    @Override
    public void remove(@NotNull RemovalReason reason) {
        NpcBoneController.clearEntity(this.getStringUUID());
        AnimationLayers.clearEntity(this);
        super.remove(reason);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.25));
        // Attack goal: only runs when the NPC has a target (set on betrayal).
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, false));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 1.0));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Mob.class, 8.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Owner-target goal: when betrayed (hostile mood), attack the owner.
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true) {
            @Override
            public boolean canUse() {
                return "hostile".equals(npcData.getCurrentMood())
                        && npcData.getOwnerUUID() != null
                        && super.canUse();
            }
        });
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.ARMOR, 2.0);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("ModelName", modelName);
        tag.putString("CurrentAnimation", currentAnimation);
        if (npcData != null) {
            tag.put(NpcData.NBT_KEY, npcData.toTag());
        }
        tag.put("InjuryData", InjurySystem.saveNbt(this));
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("CurrentAnimation")) {
            currentAnimation = tag.getString("CurrentAnimation");
        }
        if (tag.contains(NpcData.NBT_KEY)) {
            npcData = NpcData.fromTag(tag.getCompound(NpcData.NBT_KEY));
        }
        if (tag.contains("InjuryData")) {
            InjurySystem.loadNbt(this, tag.getCompound("InjuryData"));
        }
    }

    @Override
    protected @NotNull InteractionResult mobInteract(@NotNull Player player, @NotNull InteractionHand hand) {
        if (npcData == null) npcData = new NpcData();

        ItemStack itemStack = player.getItemInHand(hand);

        if (itemStack.is(Items.BONE)) {
            npcData.addAffection(5.0f);
            npcData.addLoyalty(3.0f);
            npcData.onInteract();
            npcData.setCurrentMood("happy");
            setAnimation("happy");

            if (!level().isClientSide()) {
                ((ServerLevel) level()).sendParticles(ParticleTypes.HEART,
                        getX(), getY() + getBbHeight(), getZ(),
                        5, 0.3, 0.5, 0.3, 0.01);
                player.playSound(SoundEvents.GENERIC_EAT, 1.0f, 1.2f);
            }

            if (!player.getAbilities().instabuild) {
                itemStack.shrink(1);
            }
            return InteractionResult.sidedSuccess(level().isClientSide());
        }

        if (itemStack.is(Items.DIAMOND)) {
            npcData.addAffection(15.0f);
            npcData.addLoyalty(10.0f);
            npcData.onInteract();
            npcData.setCurrentMood("happy");
            setAnimation("excited");

            if (!level().isClientSide()) {
                ((ServerLevel) level()).sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        getX(), getY() + getBbHeight(), getZ(),
                        10, 0.5, 0.5, 0.5, 0.02);
                player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);
            }

            if (!player.getAbilities().instabuild) {
                itemStack.shrink(1);
            }
            return InteractionResult.sidedSuccess(level().isClientSide());
        }

        if (itemStack.is(Items.POISONOUS_POTATO)) {
            npcData.addAffection(-10.0f);
            npcData.addLoyalty(-5.0f);
            npcData.setCurrentMood("angry");
            setAnimation("disgusted");

            if (!level().isClientSide()) {
                ((ServerLevel) level()).sendParticles(ParticleTypes.ANGRY_VILLAGER,
                        getX(), getY() + getBbHeight(), getZ(),
                        3, 0.3, 0.5, 0.3, 0.01);
                player.playSound(SoundEvents.VILLAGER_NO, 1.0f, 0.8f);
            }

            if (!player.getAbilities().instabuild) {
                itemStack.shrink(1);
            }
            return InteractionResult.sidedSuccess(level().isClientSide());
        }

        if (itemStack.isEmpty()) {
            if (level().isClientSide()) {
                // Client: open chat screen
                Minecraft.getInstance().setScreen(new NpcChatScreen(this));
            }
            npcData.onInteract();
            return InteractionResult.sidedSuccess(level().isClientSide());
        }

        if (itemStack.is(Items.WRITABLE_BOOK)) {
            if (!level().isClientSide()) {
                String message = itemStack.hasTag() && itemStack.getTag().contains("pages") ?
                        itemStack.getTag().getList("pages", 8).getString(0) : "I have something to say...";

                NpcChatHandler.sendMessage(this, player, message).thenAccept(reply -> {
                    if (player instanceof ServerPlayer serverPlayer) {
                        ServerLevel serverLevel = (ServerLevel) level();
                        serverLevel.getServer().execute(() -> {
                            if (serverPlayer.isRemoved()) return;
                            serverPlayer.sendSystemMessage(
                                    Component.translatable("npc.transferstation_whimsicalideas.npc_chat_prefix",
                                            getDisplayName().getString(), reply));
                        });
                    }
                });
            }
            return InteractionResult.sidedSuccess(level().isClientSide());
        }

        return super.mobInteract(player, hand);
    }

    @Override
    public void die(@NotNull DamageSource source) {
        super.die(source);
        if (npcData != null) {
            npcData.addDeath();
        }

        InjurySystem.clearEntity(this);
        BodyHitboxSystem.clearEntity(this);

        if (!level().isClientSide() && level() instanceof ServerLevel) {
            float deathYaw = this.getYRot();
            float dvx = (float)(-Math.sin(Math.toRadians(deathYaw)) * 0.5);
            float dvy = 0.5f;
            float dvz = (float)(Math.cos(Math.toRadians(deathYaw)) * 0.5);

            if (source != null) {
                var entity = source.getEntity();
                if (entity != null) {
                    float angle = (float) Math.atan2(
                            entity.getX() - this.getX(),
                            entity.getZ() - this.getZ());
                    dvx = (float) (-Math.sin(angle) * 1.2);
                    dvz = (float) (Math.cos(angle) * 1.2);
                    dvy = 0.6f;
                }
            }

            EntityType<NpcRagdoll> ragdollType = NpcModelRegistry.getNpcRagdollType();
            if (ragdollType != null) {
                NpcRagdoll ragdoll = new NpcRagdoll(
                        ragdollType, level(), modelName,
                        this.getYRot(), deathYaw,
                        dvx, dvy, dvz);
                ragdoll.moveTo(getX(), getY(), getZ(), this.getYRot(), this.getXRot());
                level().addFreshEntity(ragdoll);
            }

            level().playSound(null, blockPosition(), SoundEvents.GENERIC_HURT, SoundSource.HOSTILE, 1.0f, 0.8f);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // 每tick驱动AI能力
        aiAgent.tick();

        if (!level().isClientSide()) InjurySystem.tick(this);

        if (!level().isClientSide() && npcData != null) {
            long tick = level().getGameTime();
            if (tick % 200 == 0) {
                // Don't clobber explicit moods (hostile from betrayal, scared from chat).
                String mood = npcData.getCurrentMood();
                if (!"hostile".equals(mood) && !"scared".equals(mood)) {
                    if (npcData.getAffection() > 70 && !"happy".equals(mood)) {
                        npcData.setCurrentMood("happy");
                    } else if (npcData.getAffection() < 30 && !"angry".equals(mood)) {
                        npcData.setCurrentMood("angry");
                    }
                }
            }

            if (tick % 100 == 0 && npcData.shouldBetray()) {
                if (npcData.getOwnerUUID() != null) {
                    // Betrayal: actually turn hostile and attack the owner instead of
                    // clearing the target (the old setTarget(null) made betrayal a no-op).
                    Player owner = level().getPlayerByUUID(npcData.getOwnerUUID());
                    if (owner != null) {
                        setTarget(owner);
                    }
                    npcData.setCurrentMood("hostile");
                }
            }

            // Random entity dialogue for NPCs
            if (level().getGameTime() % 400 == 0 && random.nextInt(3) == 0) {
                var nearbyPlayers = level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(16));
                if (!nearbyPlayers.isEmpty()) {
                    java.util.List<String> messages = transferstation.transferstation_whimsicalideas.Config.getEntityMessages();
                    if (!messages.isEmpty()) {
                        String msg = messages.get(random.nextInt(messages.size()));
                        msg = msg.replace("%entity%", getDisplayName().getString());
                        for (Player p : nearbyPlayers) {
                            p.sendSystemMessage(Component.literal(msg));
                        }
                    }
                }
            }
        }
    }

    public String getModelName() {
        return modelName;
    }

    public NpcData getNpcData() {
        return npcData;
    }

    public void setNpcData(NpcData data) {
        this.npcData = data;
    }

    public String getCurrentAnimation() {
        return currentAnimation;
    }

    public void setAnimation(String animation) {
        this.currentAnimation = animation;
    }

    /**
     * 播放手势到 overlay 层：优先 VMD 动画文件（CustomAnim 目录），
     * 缺失时 fallback 程序化手势。不打断 base 层动画。
     */
    public void playGesture(String gesture, float fadeTime) {
        if (gesture == null || gesture.isEmpty()) return;
        if ("idle".equals(gesture)) {
            AnimationLayers.stop(this, AnimationLayers.OVERLAY, fadeTime);
            return;
        }
        if (AnimationProcessor.getAnimation(gesture) != null) {
            AnimationLayers.play(this, AnimationLayers.OVERLAY, gesture, 1.0f,
                    AnimationLayers.BoneMaskType.UPPER_BODY, fadeTime);
        } else {
            playProceduralGesture(gesture);
        }
    }

    private void playProceduralGesture(String gesture) {
        String id = getStringUUID();
        SourceModelData modelData = JavaModelRenderer.getModelData(this);
        List<SourceModelData.BoneInfo> bones = modelData != null ? modelData.bones : null;
        switch (gesture) {
            case "wave" -> NpcBoneController.playWaveAnimation(id, bones);
            case "nod" -> NpcBoneController.playNodAnimation(id, bones);
            case "shake" -> NpcBoneController.playShakeAnimation(id, bones);
            case "dance" -> NpcBoneController.playDanceAnimation(id, bones);
            default -> NpcBoneController.resetAllBones(id);
        }
    }

    /**
     * AI 骨骼指令入口（服务端解析 / 客户端 packet 双端可调，幂等覆盖式）。
     * @return 实际接受的骨骼数
     */
    public int applyBonePose(Map<String, float[]> boneRotations, float duration) {
        if (boneRotations == null || boneRotations.isEmpty()) return 0;
        long tick = level().getGameTime();
        int accepted = 0;
        for (Map.Entry<String, float[]> entry : boneRotations.entrySet()) {
            if (accepted >= MAX_AI_POSES) {
                LOGGER.warn("[NpcEntity] AI pose exceeds {} bones, ignoring rest", MAX_AI_POSES);
                break;
            }
            float[] r = entry.getValue();
            if (r == null || r.length < 3) continue;
            accepted += NpcBoneController.applyAiPose(getStringUUID(), entry.getKey(),
                    r[0], r[1], r[2], duration, tick);
        }
        return accepted;
    }

    /**
     * Called server-side when a chat packet arrives for this NPC.
     * Processes the message through AI and sends reply via S2C packet.
     */
    public void handleChatMessage(Player player, String message) {
        if (npcData == null) npcData = new NpcData();
        npcData.onInteract();

        NpcChatHandler.sendMessage(this, player, message).thenAccept(reply -> {
            // Reply is handled inside processStructuredResponse (S2C packet sent there)
        });
    }

    /**
     * Called client-side (or server-side) when a gesture packet arrives or is processed.
     */
    public void handleGesture(String emotion, String gesture) {
        if (npcData != null) {
            switch (emotion) {
                case "happy" -> npcData.setCurrentMood("happy");
                case "angry" -> npcData.setCurrentMood("angry");
                case "scared" -> npcData.setCurrentMood("scared");
                case "sad" -> npcData.setCurrentMood("sad");
                default -> npcData.setCurrentMood("neutral");
            }
        }
        playGesture(gesture, 0.15f);
    }

    @Override
    public @NotNull Component getDisplayName() {
        return Component.literal(modelName.contains("/") ?
                modelName.substring(modelName.lastIndexOf('/') + 1) : modelName);
    }
}
