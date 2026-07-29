package transferstation.transferstation_whimsicalideas.common;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.*;

public class InjurySystem {

    public enum BodyPart {
        HEAD("head", 2.5f),
        CHEST("chest", 1.5f),
        STOMACH("stomach", 1.0f),
        LEFT_ARM("left_arm", 0.7f),
        RIGHT_ARM("right_arm", 0.7f),
        LEFT_LEG("left_leg", 0.8f),
        RIGHT_LEG("right_leg", 0.8f);

        public final String name;
        public final float damageMultiplier;
        BodyPart(String name, float mult) { this.name = name; this.damageMultiplier = mult; }

        public static BodyPart fromName(String name) {
            for (BodyPart bp : values()) {
                if (bp.name.equals(name)) return bp;
            }
            return CHEST;
        }
    }

    public enum InjuryType {
        FRACTURE, BLEEDING, BRUISE, LACERATION, BURN
    }

    public static class Injury {
        public final InjuryType type;
        public final BodyPart bodyPart;
        public int severity;          // 1-5
        public int remainingTicks;
        public boolean isBleeding;
        public boolean isStopped;
        public boolean hasArrow;

        public Injury(InjuryType type, BodyPart bodyPart, int severity, int durationTicks) {
            this.type = type;
            this.bodyPart = bodyPart;
            this.severity = severity;
            this.remainingTicks = durationTicks;
            this.isBleeding = (type == InjuryType.LACERATION || type == InjuryType.FRACTURE) && bodyPart != BodyPart.HEAD;
            this.hasArrow = (type == InjuryType.LACERATION);
        }
    }

    private static final Map<UUID, List<Injury>> entityInjuries = new HashMap<>();
    private static final Map<UUID, Float> trackedFallDistances = new HashMap<>();
    private static volatile int customBloodColor = 0xDC143C;

    public static void setBloodColor(int color) { customBloodColor = color; }
    public static int getBloodColor() { return customBloodColor; }

    public static void addInjury(LivingEntity entity, InjuryType type, BodyPart bodyPart, int severity, int durationTicks) {
        UUID uuid = entity.getUUID();
        entityInjuries.computeIfAbsent(uuid, k -> new ArrayList<>()).add(new Injury(type, bodyPart, severity, durationTicks));
    }

    /**
     * Adds an injury with explicit control over whether it represents an embedded
     * arrow. Used for fall-induced lacerations, which are wounds but not arrows
     * that can be pulled out with shears.
     */
    public static void addInjury(LivingEntity entity, InjuryType type, BodyPart bodyPart, int severity, int durationTicks, boolean hasArrow) {
        UUID uuid = entity.getUUID();
        Injury injury = new Injury(type, bodyPart, severity, durationTicks);
        injury.hasArrow = hasArrow;
        entityInjuries.computeIfAbsent(uuid, k -> new ArrayList<>()).add(injury);
    }

    public static boolean hasFracture(LivingEntity entity) {
        List<Injury> injuries = entityInjuries.get(entity.getUUID());
        if (injuries == null) return false;
        return injuries.stream().anyMatch(i -> i.type == InjuryType.FRACTURE);
    }

    public static boolean isBleeding(LivingEntity entity) {
        List<Injury> injuries = entityInjuries.get(entity.getUUID());
        if (injuries == null) return false;
        return injuries.stream().anyMatch(i -> i.isBleeding && !i.isStopped);
    }

    public static boolean hasEmbeddedArrow(LivingEntity entity) {
        List<Injury> injuries = entityInjuries.get(entity.getUUID());
        if (injuries == null) return false;
        return injuries.stream().anyMatch(i -> i.hasArrow);
    }

    public static boolean removeArrow(LivingEntity entity) {
        List<Injury> injuries = entityInjuries.get(entity.getUUID());
        if (injuries == null) return false;
        for (Injury injury : injuries) {
            if (injury.hasArrow) {
                injury.hasArrow = false;
                injury.isStopped = true;
                return true;
            }
        }
        return false;
    }

    public static List<Injury> getInjuries(LivingEntity entity) {
        return entityInjuries.getOrDefault(entity.getUUID(), Collections.emptyList());
    }

    public static void trackFallDistance(LivingEntity entity, float distance) {
        trackedFallDistances.put(entity.getUUID(), distance);
    }

    public static void tick(LivingEntity entity) {
        UUID uuid = entity.getUUID();
        List<Injury> injuries = entityInjuries.get(uuid);
        if (injuries == null) return;

        Iterator<Injury> it = injuries.iterator();
        while (it.hasNext()) {
            Injury injury = it.next();

            // Bleeding causes health loss once per second
            if (injury.isBleeding && !injury.isStopped && entity.tickCount % 20 == 0) {
                float dmg = injury.severity * 0.5f;
                entity.hurt(entity.damageSources().generic(), dmg);
            }

            injury.remainingTicks--;
            if (injury.remainingTicks <= 0) {
                it.remove();
            }
        }

        if (injuries.isEmpty()) {
            entityInjuries.remove(uuid);
        }

        // Fracture prevents sprinting
        if (hasFracture(entity) && entity instanceof Player player) {
            if (player.isSprinting()) {
                player.setSprinting(false);
            }
        }
    }

    public static void clearEntity(LivingEntity entity) {
        UUID uuid = entity.getUUID();
        entityInjuries.remove(uuid);
        trackedFallDistances.remove(uuid);
    }

    public static CompoundTag saveNbt(LivingEntity entity) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("BloodColor", customBloodColor);
        List<Injury> injuries = entityInjuries.get(entity.getUUID());
        if (injuries != null && !injuries.isEmpty()) {
            var list = new net.minecraft.nbt.ListTag();
            for (Injury inj : injuries) {
                CompoundTag t = new CompoundTag();
                t.putString("Type", inj.type.name());
                t.putString("BodyPart", inj.bodyPart.name());
                t.putInt("Severity", inj.severity);
                t.putInt("Remaining", inj.remainingTicks);
                t.putBoolean("Bleeding", inj.isBleeding);
                t.putBoolean("Stopped", inj.isStopped);
                t.putBoolean("Arrow", inj.hasArrow);
                list.add(t);
            }
            tag.put("Injuries", list);
        }
        return tag;
    }

    public static void loadNbt(LivingEntity entity, CompoundTag tag) {
        if (tag.contains("BloodColor")) {
            customBloodColor = tag.getInt("BloodColor");
        }
        if (tag.contains("Injuries")) {
            List<Injury> injuries = new ArrayList<>();
            var list = (net.minecraft.nbt.ListTag) tag.get("Injuries");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                Injury inj = new Injury(
                    InjuryType.valueOf(t.getString("Type")),
                    BodyPart.valueOf(t.getString("BodyPart")),
                    t.getInt("Severity"),
                    t.getInt("Remaining")
                );
                inj.isBleeding = t.getBoolean("Bleeding");
                inj.isStopped = t.getBoolean("Stopped");
                inj.hasArrow = t.getBoolean("Arrow");
                injuries.add(inj);
            }
            entityInjuries.put(entity.getUUID(), injuries);
        }
    }
}
