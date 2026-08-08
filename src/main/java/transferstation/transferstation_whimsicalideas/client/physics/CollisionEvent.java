package transferstation.transferstation_whimsicalideas.client.physics;

import org.joml.Vector3f;

/**
 * Event data for a collision between two rigid bodies or a body and the environment.
 * Used by the collision response system to communicate collision details to listeners.
 */
public final class CollisionEvent {

    public enum Type {
        BODY_BODY,
        BODY_ENVIRONMENT,
        TRIGGER_ENTER,
        TRIGGER_EXIT
    }

    private final Type type;
    private final long bodyIdA;
    private final long bodyIdB;
    private final Vector3f contactPoint;
    private final Vector3f contactNormal;
    private final float impactVelocity;
    private final PhysicsMaterial materialA;
    private final PhysicsMaterial materialB;

    public CollisionEvent(Type type, long bodyIdA, long bodyIdB,
                          Vector3f contactPoint, Vector3f contactNormal,
                          float impactVelocity,
                          PhysicsMaterial materialA, PhysicsMaterial materialB) {
        this.type = type;
        this.bodyIdA = bodyIdA;
        this.bodyIdB = bodyIdB;
        this.contactPoint = contactPoint;
        this.contactNormal = contactNormal;
        this.impactVelocity = impactVelocity;
        this.materialA = materialA;
        this.materialB = materialB;
    }

    public Type getType() { return type; }
    public long getBodyIdA() { return bodyIdA; }
    public long getBodyIdB() { return bodyIdB; }
    public Vector3f getContactPoint() { return contactPoint; }
    public Vector3f getContactNormal() { return contactNormal; }
    public float getImpactVelocity() { return impactVelocity; }
    public PhysicsMaterial getMaterialA() { return materialA; }
    public PhysicsMaterial getMaterialB() { return materialB; }

    /**
     * Compute combined restitution from the two materials.
     */
    public float getCombinedRestitution() {
        return PhysicsMaterial.blend(materialA, materialB, 0.5f).getRestitution();
    }

    /**
     * Compute combined friction from the two materials.
     */
    public float getCombinedFriction() {
        return PhysicsMaterial.blend(materialA, materialB, 0.5f).getFriction();
    }

    @Override
    public String toString() {
        return "CollisionEvent{type=" + type + ", bodyA=" + bodyIdA + ", bodyB=" + bodyIdB
                + ", impact=" + String.format("%.2f", impactVelocity) + "}";
    }
}
