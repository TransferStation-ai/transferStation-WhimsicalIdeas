package transferstation.transferstation_whimsicalideas.client.physics;

import com.mojang.logging.LogUtils;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class SoftBodySimulation {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<PointMass> pointMasses = new ArrayList<>();
    private final List<SpringConstraint> springs = new ArrayList<>();
    private final List<BoneAttachment> attachments = new ArrayList<>();

    private Vector3f gravity = new Vector3f(0, -9.81f, 0);
    private float damping = 0.995f;
    private float windStrength = 0;
    private Vector3f windDirection = new Vector3f(1, 0, 0);

    private boolean enabled = true;

    private double simTime = 0;

    public static class PointMass {
        public Vector3f position;
        public Vector3f prevPosition;
        public Vector3f velocity;
        public float mass;
        public boolean pinned;
        public float radius;

        public PointMass(float x, float y, float z, float mass) {
            this.position = new Vector3f(x, y, z);
            this.prevPosition = new Vector3f(x, y, z);
            this.velocity = new Vector3f(0, 0, 0);
            this.mass = mass;
            this.pinned = false;
            this.radius = 0.02f;
        }
    }

    public static class SpringConstraint {
        public int pointA;
        public int pointB;
        public float restLength;
        public float stiffness;
        public float damping;

        public SpringConstraint(int pointA, int pointB, float restLength, float stiffness, float damping) {
            this.pointA = pointA;
            this.pointB = pointB;
            this.restLength = restLength;
            this.stiffness = stiffness;
            this.damping = damping;
        }
    }

    public static class BoneAttachment {
        public int pointMassIndex;
        public String boneName;
        public Vector3f localOffset;

        public BoneAttachment(int pointMassIndex, String boneName, Vector3f localOffset) {
            this.pointMassIndex = pointMassIndex;
            this.boneName = boneName;
            this.localOffset = localOffset;
        }
    }

    public int addPointMass(float x, float y, float z, float mass) {
        PointMass pm = new PointMass(x, y, z, mass);
        pointMasses.add(pm);
        return pointMasses.size() - 1;
    }

    public void addSpring(int pointA, int pointB, float stiffness, float damping) {
        float restLength = pointMasses.get(pointA).position.distance(pointMasses.get(pointB).position);
        springs.add(new SpringConstraint(pointA, pointB, restLength, stiffness, damping));
    }

    public void attachPointToBone(int pointMassIndex, String boneName, Vector3f localOffset) {
        attachments.add(new BoneAttachment(pointMassIndex, boneName, localOffset));
    }

    public void setGravity(Vector3f gravity) {
        this.gravity = gravity;
    }

    public void setWind(float strength, Vector3f direction) {
        this.windStrength = strength;
        this.windDirection = direction;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void stepSimulation(float deltaTime) {
        if (!enabled || pointMasses.isEmpty()) return;

        simTime += deltaTime;

        int subSteps = 8;
        float subDt = deltaTime / subSteps;

        for (int step = 0; step < subSteps; step++) {
            for (int i = 0; i < pointMasses.size(); i++) {
                PointMass pm = pointMasses.get(i);
                if (pm.pinned) continue;

                Vector3f acceleration = new Vector3f(gravity);

                float distFromOrigin = pm.position.length();
                if (distFromOrigin > 5.0f) {
                    acceleration.add(new Vector3f(pm.position).negate().normalize().mul(9.81f));
                }

                if (windStrength > 0) {
                    float noise = (float) (Math.sin(pm.position.x * 2.3 + pm.position.z * 1.7 + simTime * 0.001) * 0.5 + 0.5);
                    acceleration.add(new Vector3f(windDirection).mul(windStrength * noise));
                }

                if (pm.position.y < -0.5f) {
                    pm.position.y = -0.5f;
                    pm.velocity.y = Math.abs(pm.velocity.y) * 0.3f;
                    // Keep prevPosition in sync so the Verlet integrator does not
                    // infer a large implicit velocity from the clamped position.
                    pm.prevPosition.y = pm.position.y;
                }

                Vector3f newVelocity = new Vector3f(pm.velocity).add(new Vector3f(acceleration).mul(subDt));
                newVelocity.mul(damping);

                Vector3f newPosition = new Vector3f(pm.position).add(new Vector3f(newVelocity).mul(subDt));

                pm.velocity.set(newVelocity);
                pm.prevPosition.set(pm.position);
                pm.position.set(newPosition);

                // Sanitize: a spring can explode and produce NaN/Inf or a runaway
                // magnitude. Reset to the previous position and zero velocity so a
                // single bad point mass cannot corrupt the whole simulation.
                if (!java.lang.Float.isFinite(pm.position.x) || !java.lang.Float.isFinite(pm.position.y)
                        || !java.lang.Float.isFinite(pm.position.z)
                        || pm.position.lengthSquared() > 1e12f) {
                    pm.position.set(pm.prevPosition);
                    pm.velocity.set(0f, 0f, 0f);
                }
            }

            for (int iter = 0; iter < 3; iter++) {
                for (SpringConstraint spring : springs) {
                    if (spring.pointA >= pointMasses.size() || spring.pointB >= pointMasses.size()) continue;

                    PointMass pA = pointMasses.get(spring.pointA);
                    PointMass pB = pointMasses.get(spring.pointB);

                    Vector3f diff = new Vector3f(pB.position).sub(pA.position);
                    float currentLength = diff.length();
                    if (currentLength < 0.0001f) continue;

                    Vector3f direction = diff.normalize();
                    float displacement = currentLength - spring.restLength;

                    Vector3f correction = new Vector3f(direction).mul(displacement * spring.stiffness * 0.5f);

                    float invMassA = pA.pinned ? 0 : (1.0f / pA.mass);
                    float invMassB = pB.pinned ? 0 : (1.0f / pB.mass);
                    float totalInvMass = invMassA + invMassB;
                    if (totalInvMass < 0.0001f) continue;

                    if (!pA.pinned) {
                        pA.position.add(new Vector3f(correction).mul(invMassA / totalInvMass));
                    }
                    if (!pB.pinned) {
                        pB.position.sub(new Vector3f(correction).mul(invMassB / totalInvMass));
                    }

                    Vector3f relVel = new Vector3f(pB.velocity).sub(pA.velocity);
                    float dampingForce = relVel.dot(direction) * spring.damping;
                    Vector3f dampCorrection = new Vector3f(direction).mul(dampingForce * 0.5f);

                    if (!pA.pinned) {
                        pA.velocity.add(new Vector3f(dampCorrection).mul(invMassA / totalInvMass));
                    }
                    if (!pB.pinned) {
                        pB.velocity.sub(new Vector3f(dampCorrection).mul(invMassB / totalInvMass));
                    }
                }
            }
        }
    }

    public void updateBoneAttachments(List<BoneAttachment> activeAttachments, Vector3f[] boneWorldPositions) {
        // boneWorldPositions is keyed by the same order as activeAttachments, so
        // index by position in that list rather than the internal attachments list.
        for (int i = 0; i < activeAttachments.size(); i++) {
            BoneAttachment attachment = activeAttachments.get(i);
            if (attachment.pointMassIndex >= pointMasses.size()) continue;
            if (i >= boneWorldPositions.length || boneWorldPositions[i] == null) continue;
            PointMass pm = pointMasses.get(attachment.pointMassIndex);
            if (pm.pinned) {
                pm.position.set(boneWorldPositions[i]);
            }
        }
    }

    public Vector3f getPointMassPosition(int index) {
        if (index < 0 || index >= pointMasses.size()) return new Vector3f(0, 0, 0);
        return pointMasses.get(index).position;
    }

    public Vector3f getPointMassVelocity(int index) {
        if (index < 0 || index >= pointMasses.size()) return new Vector3f(0, 0, 0);
        return pointMasses.get(index).velocity;
    }

    public int getPointMassCount() {
        return pointMasses.size();
    }

    public List<SpringConstraint> getSprings() {
        return springs;
    }

    public List<PointMass> getPointMasses() {
        return pointMasses;
    }

    public List<BoneAttachment> getAttachments() {
        return attachments;
    }

    public void cleanup() {
        pointMasses.clear();
        springs.clear();
        attachments.clear();
    }

    public static SoftBodySimulation createHairSimulation(Vector3f basePosition, int strandCount, int segmentsPerStrand) {
        SoftBodySimulation sim = new SoftBodySimulation();
        float spacing = 0.06f;

        for (int s = 0; s < strandCount; s++) {
            float strandOffsetX = (s - strandCount / 2f) * spacing * 1.5f;
            float strandOffsetZ = (float) Math.sin(s * 1.3) * spacing;

            int[] indices = new int[segmentsPerStrand + 1];
            for (int j = 0; j <= segmentsPerStrand; j++) {
                float t = j / (float) segmentsPerStrand;
                float x = basePosition.x + strandOffsetX * (1 - t * 0.3f);
                float y = basePosition.y - t * 0.3f;
                float z = basePosition.z + strandOffsetZ * (1 - t * 0.3f);
                float mass = (j == 0) ? 0 : 0.02f;
                int idx = sim.addPointMass(x, y, z, mass);
                indices[j] = idx;

                if (j == 0) {
                    sim.pointMasses.get(idx).pinned = true;
                }
            }

            for (int j = 0; j < segmentsPerStrand; j++) {
                sim.addSpring(indices[j], indices[j + 1], 0.8f, 0.3f);
            }
        }

        return sim;
    }

    public static SoftBodySimulation createClothSimulation(Vector3f origin, int width, int height, float spacing) {
        SoftBodySimulation sim = new SoftBodySimulation();
        int[][] indices = new int[width][height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float px = origin.x + x * spacing;
                float py = origin.y - y * spacing * 0.5f;
                float pz = origin.z;
                float mass = (y == 0) ? 0 : 0.01f;
                int idx = sim.addPointMass(px, py, pz, mass);
                indices[x][y] = idx;

                if (y == 0) {
                    sim.pointMasses.get(idx).pinned = true;
                }

                if (x > 0) {
                    sim.addSpring(indices[x - 1][y], indices[x][y], 0.6f, 0.2f);
                }
                if (y > 0) {
                    sim.addSpring(indices[x][y - 1], indices[x][y], 0.6f, 0.2f);
                }
            }
        }

        return sim;
    }

    public static SoftBodySimulation createAccessorySimulation(Vector3f attachPosition, float mass, float stiffness) {
        SoftBodySimulation sim = new SoftBodySimulation();

        int root = sim.addPointMass(attachPosition.x, attachPosition.y, attachPosition.z, 0);
        sim.pointMasses.get(root).pinned = true;

        int body = sim.addPointMass(
            attachPosition.x + 0.05f,
            attachPosition.y - 0.05f,
            attachPosition.z,
            mass
        );
        sim.addSpring(root, body, stiffness, 0.5f);

        return sim;
    }
}
