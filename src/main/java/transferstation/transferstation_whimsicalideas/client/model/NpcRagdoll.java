package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
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

import transferstation.transferstation_whimsicalideas.client.physics.EnvironmentMeshBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class NpcRagdoll extends Entity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Radius (in blocks) around the ragdoll used to build the collision mesh. */
    private static final int MESH_RADIUS = 4;
    /** Rebuild the collision mesh every N ticks (10 ticks = 0.5s). */
    private static final int MESH_REFRESH_TICKS = 10;

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

    private boolean grounded = false;

    private boolean meshOwned = false;

    private float deathVelocityX = 0;
    private float deathVelocityY = 0.2f;
    private float deathVelocityZ = 0;
    private final float deathAngularVelY = 0;
    private float deathAngularVelX = 0;
    private float deathAngularVelZ = 0;

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
        this.deathAngularVelX = (float) (Math.random() * 2 - 1) * 1.2f;
        this.deathAngularVelZ = (float) (Math.random() * 2 - 1) * 1.2f;
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

            if (age % MESH_REFRESH_TICKS == 0) {
                refreshEnvironmentMesh();
            }

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

        Map<String, PhyJointSpec> phySpecs = loadPhyConstraints();
        Map<String, PhyParser.PhySolid> solidsByName = new HashMap<>();
        loadPhySolidMap(solidsByName);

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

            // Mass: root bones (depth 0) are heaviest, leaves are lightest.
            // Override with the .phy solid mass when present.
            float mass = getMass(boneDepths, i, bone);
            PhyParser.PhySolid solid = solidsByName.get(bone.name);
            if (solid != null && solid.mass > 0) mass = solid.mass;

            // Capsule: radius scaled to the bone's length, axis along Y.
            float boneLen = boneLength(bone);
            float capRadius = Math.max(0.1f, boneLen * modelScale * 0.18f);
            float capHeight = Math.max(0.2f, boneLen * modelScale * 0.9f);
            boneBodyIds[i] = PhysicsBridge.createRigidBodyWithShape(
                worldX, worldY, worldZ, mass, PhysicsBridge.SHAPE_CAPSULE,
                new float[]{capRadius, capHeight});

            // Apply .phy damping if present (linear + angular).
            if (solid != null && (solid.damping != 0 || solid.rotdamping != 0)) {
                PhysicsBridge.setDamping(boneBodyIds[i],
                    solid.damping > 0 ? solid.damping : 0.1f,
                    solid.rotdamping > 0 ? solid.rotdamping : 0.1f);
            }

            PhysicsBridge.setVelocity(boneBodyIds[i],
                    deathVelocityX, deathVelocityY + (float)(Math.random() * 0.5), deathVelocityZ);
            // Give each bone a small random tumble so the ragdoll folds naturally.
            PhysicsBridge.setAngularVelocity(boneBodyIds[i],
                    deathAngularVelX * (float)(Math.random() * 0.5 + 0.5),
                    deathAngularVelY,
                    deathAngularVelZ * (float)(Math.random() * 0.5 + 0.5));
        }

        // Create joints between parent and child bones. Each joint is a real
        // cone-twist (ball-in-socket + swing/twist limits) with the anchor
        // expressed in each body's local frame and the joint axis aligned
        // with the bone direction.
        int jointIdx = 0;
        for (int i = 0; i < boneCount; i++) {
            MdlDataTypes.Bone bone = modelBones.get(i);
            if (bone.parent >= 0 && bone.parent < boneCount) {
                float[] parentPos = PhysicsBridge.getPosition(boneBodyIds[bone.parent]);
                float[] childPos = PhysicsBridge.getPosition(boneBodyIds[i]);

                // .phy-driven joint spec (child bone name lookup), if available.
                PhyJointSpec spec = (phySpecs != null) ? phySpecs.get(bone.name) : null;

                float jx, jy, jz;
                if (spec != null && spec.pivotMc != null) {
                    // Pivot is relative to the child bone's creation position in
                    // world space (Source space, convert via same transform).
                    float px = -spec.pivotMc[1];
                    float py = spec.pivotMc[2];
                    float pz = spec.pivotMc[0];
                    px *= modelScale; py *= modelScale; pz *= modelScale;
                    float wx = baseX + px * cosYaw - pz * sinYaw;
                    float wz = baseZ + px * sinYaw + pz * cosYaw;
                    float wy = baseY + py;
                    // Project onto the parent-child segment for stability.
                    jx = (parentPos[0] + wx) * 0.5f;
                    jy = (parentPos[1] + wy) * 0.5f;
                    jz = (parentPos[2] + wz) * 0.5f;
                } else {
                    jx = (parentPos[0] + childPos[0]) / 2.0f;
                    jy = (parentPos[1] + childPos[1]) / 2.0f;
                    jz = (parentPos[2] + childPos[2]) / 2.0f;
                }

                // Local-frame anchors (bodies have identity rotation at creation).
                float pax = jx - parentPos[0], pay = jy - parentPos[1], paz = jz - parentPos[2];
                float pbx = jx - childPos[0], pby = jy - childPos[1], pbz = jz - childPos[2];

                // Joint axis = bone direction (world == local at creation).
                float dx = childPos[0] - parentPos[0];
                float dy = childPos[1] - parentPos[1];
                float dz = childPos[2] - parentPos[2];
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-4f) { dx = 0; dy = 1; dz = 0; len = 1; }
                float ax = dx / len, ay = dy / len, az = dz / len;

                float swing1, swing2, twist;
                if (spec != null) {
                    swing1 = spec.swing1; swing2 = spec.swing2; twist = spec.twist;
                } else {
                    float[] spans = getJointSpans(bone.parent >= 0 ? modelBones.get(bone.parent).name : null, bone.name);
                    swing1 = spans[0]; swing2 = spans[1]; twist = spans[2];
                }

                long jointId = PhysicsBridge.createConeTwistJointEx(
                    boneBodyIds[bone.parent], boneBodyIds[i],
                    pax, pay, paz, pbx, pby, pbz,
                    ax, ay, az, ax, ay, az,
                    swing1, swing2, twist);
                jointIds[jointIdx++] = jointId;

                if (spec != null && spec.friction > 0) {
                    // Friction softens the swing/twist limits slightly.
                    float damped = 1.0f / (1.0f + spec.friction * 0.1f);
                    PhysicsBridge.setJointAngularLimits(jointId,
                        swing1 * damped, swing2 * damped, twist * damped);
                }
            }
        }

        meshOwned = true;
    }

    /**
     * Best-effort resolution of .phy-driven constraints for this ragdoll.
     * Returns a map from child bone name -> {parentName, pivot(MC space),
     * swing1(rad), swing2(rad), twist(rad), friction}. Falls back through:
     *   .phy ragdollconstraint blocks -> ragdoll->joints + heuristic spans
     *   -> empty (caller uses pure heuristic).
     */
    private Map<String, PhyJointSpec> loadPhyConstraints() {
        Map<String, PhyJointSpec> result = new HashMap<>();
        String modelName = entityData.get(DATA_MODEL_NAME);
        if (modelName.isEmpty()) return result;

        Path modelsDir = MdlModelRenderer.getModelsDir();
        if (modelsDir == null) return result;
        Path packageDir = modelsDir.resolve(modelName);
        Path phyFile = findPhyFile(packageDir);
        if (phyFile == null) return result;

        PhyParser.ParsedPhy phy;
        try {
            ModelParserStrategy strategy = ModelParserProvider.getStrategy();
            phy = strategy.parsePhy(Files.readAllBytes(phyFile));
        } catch (Exception e) {
            LOGGER.debug("[NpcRagdoll] Failed to parse PHY: {}", e.getMessage());
            return result;
        }
        if (phy == null || !phy.valid) return result;

        // Preferred: real ragdollconstraint blocks.
        for (PhyParser.PhyConstraint c : phy.ragdollConstraints) {
            if (c.childName == null || c.parentName == null) continue;
            float swing1 = rad(c.xmin, c.xmax);
            float swing2 = rad(c.ymin, c.ymax);
            float twist = rad(c.zmin, c.zmax);
            float friction = (c.xfriction + c.yfriction + c.zfriction) / 3f;
            PhyJointSpec spec = new PhyJointSpec(c.parentName, new float[]{c.pivotX, c.pivotY, c.pivotZ},
                    swing1, swing2, twist, friction);
            result.put(c.childName, spec);
        }
        if (!result.isEmpty()) return result;

        // Fallback: .phy ragdoll->joints are not exposed through the Java
        // parser, so leave the map empty — the caller uses pure heuristics.
        return result;
    }

    /** Convert a Source Euler range [min,max] (radians) into a cone half-angle. */
    private static float rad(float min, float max) {
        float span = Math.abs(max - min) * 0.5f;
        return Math.min(span, (float) Math.PI);
    }

    private static Path findPhyFile(Path packageDir) {
        if (packageDir == null) return null;
        try (Stream<Path> files = Files.walk(packageDir, 4)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (f.getFileName().toString().toLowerCase().endsWith(".phy")) return f;
            }
        } catch (IOException e) {
            LOGGER.debug("[NpcRagdoll] PHY search failed: {}", e.getMessage());
        }
        return null;
    }

    /** Resolved .phy joint spec (all values in Minecraft space / radians). */
    private static class PhyJointSpec {
        final String parentName;
        final float[] pivotMc;
        final float swing1, swing2, twist, friction;
        PhyJointSpec(String parentName, float[] pivotMc, float swing1, float swing2, float twist, float friction) {
            this.parentName = parentName;
            this.pivotMc = pivotMc;
            this.swing1 = swing1;
            this.swing2 = swing2;
            this.twist = twist;
            this.friction = friction;
        }
    }

    /**
     * Pick swing/twist spans (radians) for a bone joint based on the Source
     * bone-name conventions used by ValveBiped rigs. Hinge-like joints
     * (knee/elbow/foot/hand) get a narrow cone, ball joints (shoulder/hip)
     * get a wide cone, the spine/neck get moderate values.
     */
    private static float[] getJointSpans(String parentName, String childName) {
        String parent = parentName != null ? parentName.toLowerCase() : "";
        String child = childName != null ? childName.toLowerCase() : "";
        String combined = parent + " " + child;

        if (containsAny(combined, "calf", "forearm", "foot", "toe", "hand", "finger", "elbow", "knee", "ankle")) {
            // Hinge approximation: narrow swing cone + small twist.
            return new float[]{0.25f, 0.9f, 0.15f};
        }
        if (containsAny(combined, "upperarm", "thigh", "shoulder", "clavicle")) {
            // Ball joint: wide swing cone, moderate twist.
            return new float[]{1.2f, 1.2f, 0.8f};
        }
        if (containsAny(combined, "neck", "head", "skull")) {
            return new float[]{0.55f, 0.55f, 0.45f};
        }
        if (containsAny(combined, "spine", "pelvis", "chest", "abdomen")) {
            return new float[]{0.6f, 0.6f, 0.5f};
        }
        return new float[]{0.8f, 0.8f, 0.6f};
    }

    private static boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) return true;
        }
        return false;
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

    private void loadPhySolidMap(Map<String, PhyParser.PhySolid> out) {
        String modelName = entityData.get(DATA_MODEL_NAME);
        if (modelName.isEmpty()) return;
        Path modelsDir = MdlModelRenderer.getModelsDir();
        if (modelsDir == null) return;
        Path phyFile = findPhyFile(modelsDir.resolve(modelName));
        if (phyFile == null) return;
        try {
            PhyParser.ParsedPhy phy = ModelParserProvider.getStrategy().parsePhy(Files.readAllBytes(phyFile));
            if (phy != null && phy.valid) {
                for (PhyParser.PhySolid s : phy.solids) {
                    if (s.name != null) out.put(s.name, s);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[NpcRagdoll] Solid map failed: {}", e.getMessage());
        }
    }

    private static float boneLength(MdlDataTypes.Bone bone) {
        float[] pos = bone.pos;
        return (float) Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
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
            PhysicsBridge.setAngularVelocity(boneBodyIds[i],
                    deathAngularVelX, deathAngularVelY, deathAngularVelZ);
        }

        for (int i = 0; i < boneCount - 1; i++) {
            jointIds[i] = PhysicsBridge.createJoint(boneBodyIds[i], boneBodyIds[i + 1], 0, 0, 0, 0, 0, 0);
        }

        meshOwned = true;
    }

    /**
     * Rebuild the native collision mesh from the block surfaces around this ragdoll.
     * Only takes ownership of the (global) mesh if this ragdoll currently owns it,
     * so concurrent ragdolls don't steal each other's mesh.
     */
    private void refreshEnvironmentMesh() {
        if (!PhysicsBridge.isAvailable()) return;
        if (!meshOwned) return;

        Level level = level();
        BlockPos center = blockPosition();
        EnvironmentMeshBuilder.MeshData mesh = EnvironmentMeshBuilder.build(level, center, MESH_RADIUS);
        PhysicsBridge.setEnvironmentMesh(mesh.vertices(), mesh.indices());
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

        boolean anyGrounded = false;
        for (long bodyId : boneBodyIds) {
            if (PhysicsBridge.isBodyGrounded(bodyId)) { anyGrounded = true; break; }
        }
        this.grounded = anyGrounded;
    }

    private void cleanupPhysics() {
        if (!PhysicsBridge.isAvailable()) return;

        if (meshOwned) {
            PhysicsBridge.clearEnvironmentMesh();
            meshOwned = false;
        }

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

    /** True if at least one bone touched the ground in the last physics step. */
    public boolean isGrounded() {
        return grounded;
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
