package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class NpcRagdoll extends Entity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final EntityDataAccessor<String> DATA_MODEL_NAME =
            SynchedEntityData.defineId(NpcRagdoll.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> DATA_Y_ROT =
            SynchedEntityData.defineId(NpcRagdoll.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DEATH_YAW =
            SynchedEntityData.defineId(NpcRagdoll.class, EntityDataSerializers.FLOAT);

    private int age = 0;
    private static final int MAX_AGE = 300;
    private static final int DESPAWN_START = 200;

    private long[] boneBodyIds = new long[0];
    private long[] jointIds = new long[0];
    private boolean physicsInitialized = false;

    private float deathVelocityX = 0;
    private float deathVelocityY = 0.2f;
    private float deathVelocityZ = 0;
    private final float deathAngularVelY = 0;

    public NpcRagdoll(EntityType<? extends NpcRagdoll> entityType, Level level) {
        super(entityType, level);
        this.blocksBuilding = true;
    }

    public NpcRagdoll(EntityType<? extends NpcRagdoll> entityType, Level level,
                       String modelName, float yRot, float deathYaw,
                       float dvx, float dvy, float dvz) {
        super(entityType, level);
        this.entityData.set(DATA_MODEL_NAME, modelName);
        this.entityData.set(DATA_Y_ROT, yRot);
        this.entityData.set(DATA_DEATH_YAW, deathYaw);
        this.deathVelocityX = dvx;
        this.deathVelocityY = dvy;
        this.deathVelocityZ = dvz;
        float deathAngularVelX = (float) (Math.random() * 2 - 1) * 0.3f;
        float deathAngularVelZ = (float) (Math.random() * 2 - 1) * 0.3f;
        this.blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_MODEL_NAME, "");
        this.entityData.define(DATA_Y_ROT, 0f);
        this.entityData.define(DATA_DEATH_YAW, 0f);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("RagdollModel")) {
            entityData.set(DATA_MODEL_NAME, tag.getString("RagdollModel"));
        }
        if (tag.contains("RagdollYRot")) {
            entityData.set(DATA_Y_ROT, tag.getFloat("RagdollYRot"));
        }
        if (tag.contains("RagdollDeathYaw")) {
            entityData.set(DATA_DEATH_YAW, tag.getFloat("RagdollDeathYaw"));
        }
        if (tag.contains("RagdollAge")) {
            age = tag.getInt("RagdollAge");
        }
        if (tag.contains("DeathVelX")) {
            deathVelocityX = tag.getFloat("DeathVelX");
            deathVelocityY = tag.getFloat("DeathVelY");
            deathVelocityZ = tag.getFloat("DeathVelZ");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("RagdollModel", entityData.get(DATA_MODEL_NAME));
        tag.putFloat("RagdollYRot", entityData.get(DATA_Y_ROT));
        tag.putFloat("RagdollDeathYaw", entityData.get(DATA_DEATH_YAW));
        tag.putInt("RagdollAge", age);
        tag.putFloat("DeathVelX", deathVelocityX);
        tag.putFloat("DeathVelY", deathVelocityY);
        tag.putFloat("DeathVelZ", deathVelocityZ);
    }

    @Override
    public void tick() {
        super.tick();
        age++;

        if (!level().isClientSide()) {
            if (!physicsInitialized) {
                initPhysics();
                physicsInitialized = true;
            }

            updatePhysics();

            if (age > DESPAWN_START && age % 10 == 0) {
                float fade = 1.0f - ((float)(age - DESPAWN_START) / (MAX_AGE - DESPAWN_START));
                if (level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.POOF,
                            getX(), getY() + 0.5, getZ(),
                            1, 0.1, 0.1, 0.1, 0.02);
                }
            }

            if (age >= MAX_AGE) {
                cleanupPhysics();
                this.discard();
            }
        }
    }

    private void initPhysics() {
        if (!PhysicsBridge.isAvailable()) {
            PhysicsBridge.tryInitialize();
        }

        List<MdlDataTypes.Bone> modelBones = loadModelBones();
        if (modelBones == null || modelBones.isEmpty()) {
            LOGGER.warn("[NpcRagdoll] No bone data available for ragdoll, using fallback");
            initPhysicsFallback();
            return;
        }

        float yaw = entityData.get(DATA_DEATH_YAW);
        float cosYaw = (float) Math.cos(Math.toRadians(yaw));
        float sinYaw = (float) Math.sin(Math.toRadians(yaw));

        float baseX = (float) getX();
        float baseY = (float) getY();
        float baseZ = (float) getZ();

        // Compute model scale from bone extent
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float maxExtent = 0f;
        for (MdlDataTypes.Bone bone : modelBones) {
            float my = bone.pos[2]; // Source Z -> Minecraft Y
            if (my < minY) minY = my;
            if (my > maxY) maxY = my;
            float mx = -bone.pos[1]; // Source Y -> Minecraft X
            float mz = bone.pos[0];  // Source X -> Minecraft Z
            float extent = (float) Math.sqrt(mx * mx + my * my + mz * mz);
            if (extent > maxExtent) maxExtent = extent;
        }
        float modelScale = 1.0f;
        float height = maxY - minY;
        if (height > 0.001f) {
            modelScale = 1.8f / height;
        }
        // Clamp scale to reasonable range
        if (modelScale > 5.0f) modelScale = 5.0f;
        if (modelScale < 0.1f) modelScale = 0.1f;

        int boneCount = modelBones.size();
        boneBodyIds = new long[boneCount];
        // Joints: one per bone that has a parent
        int jointCount = 0;
        for (MdlDataTypes.Bone bone : modelBones) {
            if (bone.parent >= 0 && bone.parent < boneCount) jointCount++;
        }
        jointIds = new long[jointCount];

        // Determine bone masses based on hierarchy depth
        int[] boneDepths = new int[boneCount];
        for (int i = 0; i < boneCount; i++) {
            int depth = 0;
            boolean[] visited = new boolean[boneCount];
            int p = modelBones.get(i).parent;
            int guard = 0;
            while (p >= 0 && p < boneCount && !visited[p] && guard++ < boneCount) {
                visited[p] = true;
                depth++;
                p = modelBones.get(p).parent;
            }
            boneDepths[i] = depth;
        }

        for (int i = 0; i < boneCount; i++) {
            MdlDataTypes.Bone bone = modelBones.get(i);

            // Convert Source Engine coords to Minecraft: (-sourceY, sourceZ, sourceX)
            float bx = -bone.pos[1];
            float by = bone.pos[2];
            float bz = bone.pos[0];

            // Apply scale
            bx *= modelScale;
            by *= modelScale;
            bz *= modelScale;

            // Apply yaw rotation around Y axis
            float worldX = baseX + bx * cosYaw - bz * sinYaw;
            float worldZ = baseZ + bx * sinYaw + bz * cosYaw;
            float worldY = baseY + by;

            // Mass: root bones (depth 0) are heaviest, leaves are lightest
            float mass = getMass(boneDepths, i, bone);

            boneBodyIds[i] = PhysicsBridge.createRigidBody(worldX, worldY, worldZ, mass);
            PhysicsBridge.setVelocity(boneBodyIds[i],
                    deathVelocityX, deathVelocityY + (float)(Math.random() * 0.5), deathVelocityZ);
        }

        // Create joints between parent and child bones
        int jointIdx = 0;
        for (int i = 0; i < boneCount; i++) {
            MdlDataTypes.Bone bone = modelBones.get(i);
            if (bone.parent >= 0 && bone.parent < boneCount) {
                // Position joint at midpoint between parent and child
                float[] parentPos = PhysicsBridge.getPosition(boneBodyIds[bone.parent]);
                float[] childPos = PhysicsBridge.getPosition(boneBodyIds[i]);
                float jx = (parentPos[0] + childPos[0]) / 2.0f - baseX;
                float jy = (parentPos[1] + childPos[1]) / 2.0f - baseY;
                float jz = (parentPos[2] + childPos[2]) / 2.0f - baseZ;
                jointIds[jointIdx++] = PhysicsBridge.createJoint(
                    boneBodyIds[bone.parent], boneBodyIds[i], jx, jy, jz, jx, jy, jz);
            }
        }
    }

    private static float getMass(int[] boneDepths, int i, MdlDataTypes.Bone bone) {
        float mass;
        if (boneDepths[i] <= 1) {
            mass = 4.0f;
        } else if (boneDepths[i] <= 3) {
            mass = 2.0f;
        } else {
            mass = 1.0f;
        }
        // Adjust mass based on bone name hints
        String name = bone.name != null ? bone.name.toLowerCase() : "";
        if (name.contains("head")) mass = 3.0f;
        else if (name.contains("pelvis") || name.contains("spine")) mass = 5.0f;
        else if (name.contains("upperarm") || name.contains("thigh") || name.contains("calf")) mass = 3.0f;
        else if (name.contains("foot") || name.contains("hand") || name.contains("forearm")) mass = 1.5f;
        return mass;
    }

    private List<MdlDataTypes.Bone> loadModelBones() {
        String modelName = entityData.get(DATA_MODEL_NAME);
        if (modelName.isEmpty()) return null;

        // Try cached model data first (client-side)
        String cacheKey;
        Path modelsDir = MdlModelRenderer.getModelsDir();
        if (modelsDir != null) {
            Path packageDir = modelsDir.resolve(modelName);
            cacheKey = packageDir.toAbsolutePath().toString();
            SourceModelData cached = ModelLoadManager.getCached(cacheKey);
            if (cached != null && !cached.bones.isEmpty()) {
                return convertBoneInfos(cached.bones);
            }
            // Try to load model data (may return from disk cache on server)
            try {
                SourceModelData modelData = ModelLoadManager.getCached(cacheKey);
                if (modelData == null) {
                    ModelLoadManager.loadModelAsync(packageDir);
                    return new ArrayList<>();
                }
                if (!modelData.bones.isEmpty()) {
                    return convertBoneInfos(modelData.bones);
                }
            } catch (Exception e) {
                LOGGER.debug("[NpcRagdoll] Could not load model via ModelLoadManager: {}", e.getMessage());
            }
            // Fall back to direct MDL parsing
            try {
                return parseBonesFromMdlFile(packageDir);
            } catch (Exception e) {
                LOGGER.debug("[NpcRagdoll] Could not parse MDL directly: {}", e.getMessage());
            }
        }
        return null;
    }

    private List<MdlDataTypes.Bone> convertBoneInfos(List<SourceModelData.BoneInfo> boneInfos) {
        List<MdlDataTypes.Bone> bones = new ArrayList<>();
        for (SourceModelData.BoneInfo info : boneInfos) {
            MdlDataTypes.Bone b = new MdlDataTypes.Bone();
            b.name = info.name();
            b.pos = info.pos();
            b.parent = info.parent();
            bones.add(b);
        }
        return bones;
    }

    private List<MdlDataTypes.Bone> parseBonesFromMdlFile(Path packageDir) throws IOException {
        Path mdlFile = null;
        Path smdFile = null;
        try (Stream<Path> files = Files.walk(packageDir, 4)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".mdl")) { mdlFile = f; break; }
                if (name.endsWith(".smd") && smdFile == null) smdFile = f;
            }
        }
        if (mdlFile != null) {
            ModelParserStrategy strategy = ModelParserProvider.getStrategy();
            MdlDataTypes.ParsedModel mdl = strategy.parseMdl(Files.readAllBytes(mdlFile));
            return mdl.bones;
        }
        if (smdFile != null) {
            SmdParser.ParsedSmd smd = SmdParser.parse(smdFile);
            if (!smd.bones.isEmpty()) {
                List<MdlDataTypes.Bone> bones = new ArrayList<>();
                for (SmdParser.SmdBone sb : smd.bones) {
                    MdlDataTypes.Bone b = new MdlDataTypes.Bone();
                    b.name = sb.name;
                    b.pos = new float[]{0, 0, 0};
                    b.parent = sb.parent;
                    bones.add(b);
                }
                return bones;
            }
        }
        return null;
    }

    private void initPhysicsFallback() {
        int boneCount = 8;
        boneBodyIds = new long[boneCount];
        jointIds = new long[boneCount - 1];

        float yaw = entityData.get(DATA_DEATH_YAW);
        float cosYaw = (float) Math.cos(Math.toRadians(yaw));
        float sinYaw = (float) Math.sin(Math.toRadians(yaw));

        float baseX = (float) getX();
        float baseY = (float) getY();
        float baseZ = (float) getZ();

        float[] boneMasses = {2.0f, 5.0f, 3.0f, 3.0f, 1.5f, 1.5f, 2.0f, 2.0f};
        float[] boneOffsetsY = {1.6f, 0.9f, 0.9f, 0.9f, 0.2f, 0.2f, 0.4f, 0.4f};
        float[] boneOffsetsX = {0.0f, 0.0f, 0.25f, -0.25f, 0.15f, -0.15f, 0.12f, -0.12f};

        for (int i = 0; i < boneCount; i++) {
            float worldX = baseX + boneOffsetsX[i] * cosYaw;
            float worldZ = baseZ - boneOffsetsX[i] * sinYaw;
            float worldY = baseY + boneOffsetsY[i];

            boneBodyIds[i] = PhysicsBridge.createRigidBody(worldX, worldY, worldZ, boneMasses[i]);
            PhysicsBridge.setVelocity(boneBodyIds[i],
                    deathVelocityX, deathVelocityY + (float)(Math.random() * 0.5), deathVelocityZ);
        }

        for (int i = 0; i < boneCount - 1; i++) {
            jointIds[i] = PhysicsBridge.createJoint(boneBodyIds[i], boneBodyIds[i + 1], 0, 0, 0, 0, 0, 0);
        }
    }

    private void updatePhysics() {
        if (!PhysicsBridge.isAvailable() || boneBodyIds.length == 0) return;

        PhysicsBridge.stepSimulation(1.0f / 20.0f);

        float avgX = 0, avgY = 0, avgZ = 0;
        for (long bodyId : boneBodyIds) {
            float[] pos = PhysicsBridge.getPosition(bodyId);
            avgX += pos[0];
            avgY += pos[1];
            avgZ += pos[2];
        }
        avgX /= boneBodyIds.length;
        avgY /= boneBodyIds.length;
        avgZ /= boneBodyIds.length;

        this.setPosRaw(avgX, avgY, avgZ);
    }

    private void cleanupPhysics() {
        if (!PhysicsBridge.isAvailable()) return;

        for (long jointId : jointIds) {
            if (jointId != 0) {
                PhysicsBridge.destroyJoint(jointId);
            }
        }
        for (long bodyId : boneBodyIds) {
            if (bodyId != 0) {
                PhysicsBridge.destroyRigidBody(bodyId);
            }
        }
        boneBodyIds = new long[0];
        jointIds = new long[0];
    }

    @Override
    public void remove(@NotNull RemovalReason reason) {
        cleanupPhysics();
        super.remove(reason);
    }

    public String getModelName() {
        return entityData.get(DATA_MODEL_NAME);
    }

    public float getYRotation() {
        return entityData.get(DATA_Y_ROT);
    }

    public float getDeathYaw() {
        return entityData.get(DATA_DEATH_YAW);
    }

    public int getAge() {
        return age;
    }

    public int getMaxAge() {
        return MAX_AGE;
    }

    public float getDecayProgress() {
        return (float) age / MAX_AGE;
    }

    public boolean isFading() {
        return age > DESPAWN_START;
    }

    public long[] getBoneBodyIds() {
        return boneBodyIds;
    }

    public float[] getBonePosition(int index) {
        if (!PhysicsBridge.isAvailable() || boneBodyIds.length == 0 || index < 0 || index >= boneBodyIds.length) {
            return new float[]{0, 0, 0};
        }
        return PhysicsBridge.getPosition(boneBodyIds[index]);
    }

    public float[] getBoneRotation(int index) {
        if (!PhysicsBridge.isAvailable() || boneBodyIds.length == 0 || index < 0 || index >= boneBodyIds.length) {
            return new float[]{0, 0, 0, 1};
        }
        return PhysicsBridge.getRotation(boneBodyIds[index]);
    }
}
