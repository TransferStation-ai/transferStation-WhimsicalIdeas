package transferstation.transferstation_whimsicalideas.common;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import transferstation.transferstation_whimsicalideas.client.model.MdlDataTypes;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.util.*;

public class BodyHitboxSystem {

    public record HitboxEntry(InjurySystem.BodyPart bodyPart, float minX, float minY, float minZ, float maxX,
                              float maxY, float maxZ, int boneIndex, int groupIndex) {
    }

    private static final Map<UUID, List<HitboxEntry>> entityHitboxes = new HashMap<>();

    public static void registerHitboxes(LivingEntity entity, SourceModelData modelData) {
        UUID uuid = entity.getUUID();
        List<HitboxEntry> entries = new ArrayList<>();

        if (!modelData.hitboxSets.isEmpty()) {
            MdlDataTypes.HitboxSet hitboxSet = modelData.hitboxSets.get(0);
            for (MdlDataTypes.Bbox hb : hitboxSet.hitboxes) {
                InjurySystem.BodyPart bodyPart = mapToBodyPart(hb.bone, hb.group, modelData);
                entries.add(new HitboxEntry(bodyPart,
                    hb.bbmin[0], hb.bbmin[1], hb.bbmin[2],
                    hb.bbmax[0], hb.bbmax[1], hb.bbmax[2],
                    hb.bone, hb.group));
            }
        }

        // Fallback: simple humanoid hitboxes when no MDL hitbox data
        if (entries.isEmpty()) {
            entries.add(new HitboxEntry(InjurySystem.BodyPart.HEAD, -0.3f, 1.4f, -0.3f, 0.3f, 1.8f, 0.3f, -1, 0));
            entries.add(new HitboxEntry(InjurySystem.BodyPart.CHEST, -0.4f, 0.7f, -0.3f, 0.4f, 1.4f, 0.3f, -1, 1));
            entries.add(new HitboxEntry(InjurySystem.BodyPart.STOMACH, -0.35f, 0.0f, -0.25f, 0.35f, 0.7f, 0.25f, -1, 2));
            entries.add(new HitboxEntry(InjurySystem.BodyPart.LEFT_ARM, -0.5f, 0.7f, -0.2f, -0.3f, 1.3f, 0.2f, -1, 3));
            entries.add(new HitboxEntry(InjurySystem.BodyPart.RIGHT_ARM, 0.3f, 0.7f, -0.2f, 0.5f, 1.3f, 0.2f, -1, 4));
            entries.add(new HitboxEntry(InjurySystem.BodyPart.LEFT_LEG, -0.25f, -0.7f, -0.2f, -0.1f, 0.0f, 0.2f, -1, 5));
            entries.add(new HitboxEntry(InjurySystem.BodyPart.RIGHT_LEG, 0.1f, -0.7f, -0.2f, 0.25f, 0.0f, 0.2f, -1, 6));
        }

        entityHitboxes.put(uuid, entries);
    }

    private static InjurySystem.BodyPart mapToBodyPart(int boneIndex, int groupIndex, SourceModelData modelData) {
        // Standard Source Engine hitbox groups
        return switch (groupIndex) {
            case 1 -> InjurySystem.BodyPart.HEAD;
            case 2 -> InjurySystem.BodyPart.CHEST;
            case 3 -> InjurySystem.BodyPart.STOMACH;
            case 4 -> InjurySystem.BodyPart.LEFT_ARM;
            case 5 -> InjurySystem.BodyPart.RIGHT_ARM;
            case 6 -> InjurySystem.BodyPart.LEFT_LEG;
            case 7 -> InjurySystem.BodyPart.RIGHT_LEG;
            default -> {
                // Try bone name
                if (boneIndex >= 0 && boneIndex < modelData.bones.size()) {
                    String boneName = modelData.bones.get(boneIndex).name().toLowerCase();
                    if (boneName.contains("head") || boneName.contains("neck")) yield InjurySystem.BodyPart.HEAD;
                    if (boneName.contains("chest") || boneName.contains("spine") || boneName.contains("upper")) yield InjurySystem.BodyPart.CHEST;
                    if (boneName.contains("stomach") || boneName.contains("pelvis") || boneName.contains("hip")) yield InjurySystem.BodyPart.STOMACH;
                    if (boneName.contains("leftarm") || boneName.contains("arm_left") || boneName.contains("l_arm")) yield InjurySystem.BodyPart.LEFT_ARM;
                    if (boneName.contains("rightarm") || boneName.contains("arm_right") || boneName.contains("r_arm")) yield InjurySystem.BodyPart.RIGHT_ARM;
                    if (boneName.contains("leftleg") || boneName.contains("leg_left") || boneName.contains("l_leg")) yield InjurySystem.BodyPart.LEFT_LEG;
                    if (boneName.contains("rightleg") || boneName.contains("leg_right") || boneName.contains("r_leg")) yield InjurySystem.BodyPart.RIGHT_LEG;
                }
                yield InjurySystem.BodyPart.CHEST;
            }
        };
    }

    public static List<HitboxEntry> getHitboxes(LivingEntity entity) {
        return entityHitboxes.getOrDefault(entity.getUUID(), Collections.emptyList());
    }

    public static InjurySystem.BodyPart determineHitBodyPart(LivingEntity entity, Vec3 hitLocation) {
        List<HitboxEntry> hitboxes = getHitboxes(entity);
        if (hitboxes.isEmpty()) return InjurySystem.BodyPart.CHEST;

        Vec3 relative = hitLocation.subtract(entity.position());
        float rx = (float) relative.x;
        float ry = (float) relative.y;
        float rz = (float) relative.z;

        for (HitboxEntry hb : hitboxes) {
            if (rx >= hb.minX && rx <= hb.maxX &&
                ry >= hb.minY && ry <= hb.maxY &&
                rz >= hb.minZ && rz <= hb.maxZ) {
                return hb.bodyPart;
            }
        }
        return InjurySystem.BodyPart.CHEST;
    }

    public static void clearEntity(LivingEntity entity) {
        entityHitboxes.remove(entity.getUUID());
    }
}
