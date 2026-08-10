package transferstation.transferstation_whimsicalideas.client.particle;

import org.joml.Vector3f;
import org.joml.Vector4f;

public class Particle {
    public Vector3f position = new Vector3f();
    public Vector3f velocity = new Vector3f();
    public Vector4f color = new Vector4f(1, 1, 1, 1);
    public float size = 8f;
    public float rotation = 0f;
    public float angularVelocity = 0f;
    public float age = 0f;
    public float lifetime = 1f;
    public int sequence = 0;       // animation frame for sprite sheets
    public boolean alive = true;
    public Vector3f previousPosition = new Vector3f(); // for trail rendering
    public boolean hitThisTick = false;                 // 本 tick 内命中过碰撞

    public void tick(float dt) {
        previousPosition.set(position);
        position.add(velocity.x * dt, velocity.y * dt, velocity.z * dt);
        age += dt;
        rotation += angularVelocity * dt;
        if (age >= lifetime) {
            alive = false;
        }
    }

    public float getProgress() {
        return Math.min(1f, age / lifetime);
    }

    public float getAlpha() {
        return color.w;
    }

    public void setAlpha(float a) {
        color.w = a;
    }
}
