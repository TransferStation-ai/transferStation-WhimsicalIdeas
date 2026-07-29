package transferstation.transferstation_whimsicalideas.client.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class NpcData {

    public static final String NBT_KEY = "transferstation_whimsicalideas:npc_data";

    private float affection = 50.0f;
    private float loyalty = 50.0f;
    private float betrayalProbability = 0.05f;
    private UUID ownerUUID = null;
    private String currentMood = "neutral";
    private long lastInteractionTick = 0;
    private int killCount = 0;
    private int deathCount = 0;
    private List<String> knownPlayers = new ArrayList<>();
    private String aiPersonality = "friendly";
    private float maxAffection = 100.0f;
    private float maxLoyalty = 100.0f;

    private static final Random RANDOM = new Random();

    public NpcData() {}

    public static NpcData fromTag(CompoundTag tag) {
        NpcData data = new NpcData();
        if (tag == null) return data;
        data.affection = tag.getFloat("Affection");
        data.loyalty = tag.getFloat("Loyalty");
        data.betrayalProbability = tag.getFloat("BetrayalProb");
        if (tag.hasUUID("OwnerUUID")) {
            data.ownerUUID = tag.getUUID("OwnerUUID");
        }
        data.currentMood = tag.getString("Mood");
        data.lastInteractionTick = tag.getLong("LastInteraction");
        data.killCount = tag.getInt("KillCount");
        data.deathCount = tag.getInt("DeathCount");
        data.aiPersonality = tag.getString("Personality");

        if (tag.contains("KnownPlayers")) {
            ListTag list = tag.getList("KnownPlayers", 8);
            for (int i = 0; i < list.size(); i++) {
                data.knownPlayers.add(list.getString(i));
            }
        }
        return data;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Affection", affection);
        tag.putFloat("Loyalty", loyalty);
        tag.putFloat("BetrayalProb", betrayalProbability);
        if (ownerUUID != null) {
            tag.putUUID("OwnerUUID", ownerUUID);
        }
        tag.putString("Mood", currentMood);
        tag.putLong("LastInteraction", lastInteractionTick);
        tag.putInt("KillCount", killCount);
        tag.putInt("DeathCount", deathCount);
        tag.putString("Personality", aiPersonality);

        ListTag list = new ListTag();
        for (String player : knownPlayers) {
            list.add(StringTag.valueOf(player));
        }
        tag.put("KnownPlayers", list);
        return tag;
    }

    public void saveToEntity(Entity entity) {
        CompoundTag customData = entity.getPersistentData();
        customData.put(NBT_KEY, toTag());
    }

    public static NpcData loadFromEntity(Entity entity) {
        CompoundTag customData = entity.getPersistentData();
        if (customData.contains(NBT_KEY)) {
            return fromTag(customData.getCompound(NBT_KEY));
        }
        return new NpcData();
    }

    public float getAffection() { return affection; }
    public void setAffection(float affection) { this.affection = clamp(affection, 0, maxAffection); }
    public void addAffection(float amount) { setAffection(this.affection + amount); }

    public float getLoyalty() { return loyalty; }
    public void setLoyalty(float loyalty) { this.loyalty = clamp(loyalty, 0, maxLoyalty); }
    public void addLoyalty(float amount) { setLoyalty(this.loyalty + amount); }

    public float getBetrayalProbability() { return betrayalProbability; }
    public void setBetrayalProbability(float prob) { this.betrayalProbability = clamp(prob, 0, 1); }

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID uuid) { this.ownerUUID = uuid; }

    public String getCurrentMood() { return currentMood; }
    public void setCurrentMood(String mood) { this.currentMood = mood; }

    public long getLastInteractionTick() { return lastInteractionTick; }
    public void setLastInteractionTick(long tick) { this.lastInteractionTick = tick; }

    public int getKillCount() { return killCount; }
    public void addKill() { killCount++; }

    public int getDeathCount() { return deathCount; }
    public void addDeath() { deathCount++; }

    public List<String> getKnownPlayers() { return knownPlayers; }
    public void addKnownPlayer(String playerName) {
        if (!knownPlayers.contains(playerName) && knownPlayers.size() < 50) {
            knownPlayers.add(playerName);
        }
    }

    public String getAiPersonality() { return aiPersonality; }
    public void setAiPersonality(String personality) { this.aiPersonality = personality; }

    public boolean shouldBetray() {
        float adjustedBetrayal = betrayalProbability;
        if (loyalty > 80) adjustedBetrayal *= 0.1f;
        else if (loyalty > 60) adjustedBetrayal *= 0.5f;
        else if (loyalty < 20) adjustedBetrayal *= 3.0f;

        if (affection < 20) adjustedBetrayal *= 2.0f;
        else if (affection > 80) adjustedBetrayal *= 0.2f;

        adjustedBetrayal = clamp(adjustedBetrayal, 0, 1);
        return RANDOM.nextFloat() < adjustedBetrayal;
    }

    public void onInteract() {
        affection = clamp(affection + 2.0f + RANDOM.nextFloat() * 3.0f, 0, maxAffection);
        loyalty = clamp(loyalty + 0.5f + RANDOM.nextFloat() * 1.0f, 0, maxLoyalty);
        betrayalProbability = clamp(betrayalProbability - 0.005f, 0, 1);
    }

    public void onHurtBy(Entity attacker) {
        affection = clamp(affection - 10.0f, 0, maxAffection);
        loyalty = clamp(loyalty - 5.0f, 0, maxLoyalty);
        betrayalProbability = clamp(betrayalProbability + 0.05f, 0, 1);
        if (attacker != null) {
            currentMood = "angry";
        }
    }

    public void onKill() {
        killCount++;
        loyalty = clamp(loyalty + 3.0f, 0, maxLoyalty);
        affection = clamp(affection + 1.0f, 0, maxAffection);
    }

    public Component getMoodDescription() {
        return switch (currentMood) {
            case "happy" -> Component.translatable("npc.transferstation_whimsicalideas.mood.happy");
            case "angry" -> Component.translatable("npc.transferstation_whimsicalideas.mood.angry");
            case "scared" -> Component.translatable("npc.transferstation_whimsicalideas.mood.scared");
            case "neutral" -> Component.translatable("npc.transferstation_whimsicalideas.mood.neutral");
            default -> Component.translatable("npc.transferstation_whimsicalideas.mood.unknown");
        };
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
