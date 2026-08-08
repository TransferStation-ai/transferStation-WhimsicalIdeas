package transferstation.transferstation_whimsicalideas.client.physics;

import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A trigger volume that fires callbacks when physics bodies enter or exit its bounds.
 * Supports box and sphere shapes. Trigger volumes do not produce collision responses.
 */
public final class TriggerVolume {

    public enum Shape { BOX, SPHERE }

    public interface Listener {
        void onEnter(long bodyId, TriggerVolume volume);
        void onExit(long bodyId, TriggerVolume volume);
        default void onTick(long bodyId, TriggerVolume volume) {}
    }

    private final String id;
    private final Shape shape;
    private final Vector3f center;
    private Vector3f halfExtents;
    private float radius;
    private Listener listener;
    private boolean active = true;
    private final Map<Long, Boolean> containedBodies = new ConcurrentHashMap<>();

    public TriggerVolume(Shape shape, Vector3f center, Vector3f halfExtents) {
        this.id = UUID.randomUUID().toString();
        this.shape = shape;
        this.center = new Vector3f(center);
        this.halfExtents = new Vector3f(halfExtents);
        this.radius = 0f;
    }

    public TriggerVolume(Shape shape, Vector3f center, float radius) {
        this.id = UUID.randomUUID().toString();
        this.shape = shape;
        this.center = new Vector3f(center);
        this.halfExtents = new Vector3f(0);
        this.radius = radius;
    }

    public String getId() { return id; }
    public Shape getShape() { return shape; }
    public Vector3f getCenter() { return center; }
    public Vector3f getHalfExtents() { return halfExtents; }
    public float getRadius() { return radius; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Listener getListener() { return listener; }
    public void setListener(Listener listener) { this.listener = listener; }

    public void setCenter(Vector3f center) { this.center.set(center); }
    public void setHalfExtents(Vector3f halfExtents) { this.halfExtents.set(halfExtents); }
    public void setRadius(float radius) { this.radius = radius; }

    /**
     * Check if a point is inside this trigger volume.
     */
    public boolean containsPoint(Vector3f point) {
        if (!active) return false;
        switch (shape) {
            case BOX:
                float dx = Math.abs(point.x - center.x) - halfExtents.x;
                float dy = Math.abs(point.y - center.y) - halfExtents.y;
                float dz = Math.abs(point.z - center.z) - halfExtents.z;
                return dx <= 0 && dy <= 0 && dz <= 0;
            case SPHERE:
                return point.distanceSquared(center) <= radius * radius;
            default:
                return false;
        }
    }

    /**
     * Update containment status for a body. Returns true if the state changed.
     */
    public boolean updateBody(long bodyId, Vector3f bodyPosition) {
        if (!active) {
            if (containedBodies.remove(bodyId) != null && listener != null) {
                listener.onExit(bodyId, this);
                return true;
            }
            return false;
        }

        boolean wasInside = containedBodies.getOrDefault(bodyId, false);
        boolean isInside = containsPoint(bodyPosition);

        if (isInside && !wasInside) {
            containedBodies.put(bodyId, true);
            if (listener != null) listener.onEnter(bodyId, this);
            return true;
        } else if (!isInside && wasInside) {
            containedBodies.remove(bodyId);
            if (listener != null) listener.onExit(bodyId, this);
            return true;
        } else if (isInside) {
            if (listener != null) listener.onTick(bodyId, this);
        }
        return false;
    }

    /**
     * Remove a body from tracking (e.g. when it is destroyed).
     */
    public void removeBody(long bodyId) {
        if (containedBodies.remove(bodyId) != null && listener != null) {
            listener.onExit(bodyId, this);
        }
    }

    public boolean isBodyContained(long bodyId) {
        return containedBodies.getOrDefault(bodyId, false);
    }

    public int getContainedCount() {
        return containedBodies.size();
    }
}
