package transferstation.transferstation_whimsicalideas.client.particle;

import net.minecraft.world.level.Level;
import org.joml.Math;
import org.joml.Vector3f;
import transferstation.transferstation_whimsicalideas.client.particle.collision.ParticleCollisionHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

public class ParticleEmitter {
    private final PcfParticleSystemDef.SystemDefinition def;
    private final Random random;
    public final Vector3f origin = new Vector3f();
    public boolean active = true;
    private final List<Particle> particles = new ArrayList<>();
    private float emissionAccumulator = 0f;
    private final Consumer<Particle> onSpawn;
    private final ParticleControlPoints controlPoints = new ParticleControlPoints();
    // 子发射器回调：由 ParticleManager 注入（Level 依赖隔离，JUnit 可测）
    private Consumer<String> onChildSpawn;
    private final ParticleCollisionHandler collisionHandler;
    // 子发射器计时：childName → 已累积时间
    private final Map<String, Float> childTimers = new HashMap<>();
    // 子发射器当前延迟阈值：childName → 下次触发阈值（delay 可递增，不改 ChildDef 纯数据）
    private final Map<String, Float> childThresholds = new HashMap<>();

    // 纯逻辑构造：不触碰 Level（JUnit 直接测）
    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Random random) {
        this(def, random, null, null, null);
    }

    // 完整构造（Random 版，JUnit 直接测 / MC 集成用）
    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Random random,
                           Consumer<Particle> onSpawn,
                           ParticleCollisionHandler collisionHandler,
                           Consumer<String> onChildSpawn) {
        this.def = def;
        this.random = random;
        this.onSpawn = onSpawn;
        this.collisionHandler = collisionHandler;
        this.onChildSpawn = onChildSpawn;
    }

    // 完整构造（Level 版，MC 集成用）：用 world RNG 种子派生独立 Random
    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Level level,
                           Consumer<Particle> onSpawn,
                           ParticleCollisionHandler collisionHandler,
                           Consumer<String> onChildSpawn) {
        this(def, new Random(level.random.nextInt()), onSpawn, collisionHandler, onChildSpawn);
    }

    // 兼容旧构造（保留 Level 版入口）
    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Level level) {
        this(def, level, null);
    }

    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Level level, Consumer<Particle> onSpawn) {
        this(def, level, onSpawn, null, null);
    }

    public void tick(float dt) {
        if (!active) return;

        // Emit new particles
        if (def.continuous) {
            emissionAccumulator += def.emissionRate * dt;
            while (emissionAccumulator >= 1f && particles.size() < def.maxParticles) {
                spawnParticle();
                emissionAccumulator -= 1f;
            }
        }

        // 子发射器触发（独立于 emission 循环）
        if (def.children != null && !def.children.isEmpty()) {
            for (var child : def.children) {
                float acc = childTimers.merge(child.childName, dt, Float::sum);
                float threshold = childThresholds.getOrDefault(child.childName, child.delay);
                if (acc >= threshold) {
                    if (onChildSpawn != null) onChildSpawn.accept(child.childName);
                    childTimers.put(child.childName, acc - threshold);
                    // 递增后续延迟（Source delay rate 语义）
                    childThresholds.put(child.childName, threshold + child.delayRate);
                }
            }
        }

        // Update existing particles
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.tick(dt);
            applyOperators(p, dt);

            // 世界碰撞
            if (collisionHandler != null) {
                ParticleCollisionHandler.Collision col = collisionHandler.collide(p, dt);
                if (col != null) {
                    p.hitThisTick = true;
                    if (!col.alive()) {
                        p.alive = false;
                        it.remove();
                        continue;
                    }
                    float dot = p.velocity.dot(col.normal());
                    if (dot < 0) {
                        // 标准反射：v' = v - (1+e)(v·n)n（e = restitution）
                        p.velocity.sub(col.normal().mul(dot * (1f + col.restitution())));
                    }
                }
            }

            if (!p.alive) {
                it.remove();
            }
        }
    }

    private void spawnParticle() {
        Particle p = new Particle();
        p.lifetime = def.lifespan > 0 ? def.lifespan : 1f;
        p.position.set(origin);

        // Apply initializers
        for (var init : def.initializers) {
            applyInitializer(p, init);
        }

        // Apply renderer defaults
        if (def.renderer != null) {
            p.size = switch (def.renderer.type) {
                case SPRITE, DECAL -> 16f;
                case MODEL -> 32f;
                default -> 8f;
            };
        }

        particles.add(p);
        if (onSpawn != null) onSpawn.accept(p);
    }

    private void applyInitializer(Particle p, PcfParticleSystemDef.InitializerDef init) {
        // CP 出生点：initializer 若 control point based，以 CP 位置为出生中心
        Vector3f spawnCenter = origin;
        if (getBooleanParam(init.params, "m_bControlPointBased", false)) {
            int idx = getIntParam(init.params, "m_nControlPoint", 0);
            spawnCenter = controlPoints.get(idx);
            p.position.set(spawnCenter);
        }
        var rand = random;
        switch (init.type) {
            case "position_sphere" -> {
                float radius = getFloatParam(init.params, "m_flRadius", 16f);
                float theta = rand.nextFloat() * (float) (org.joml.Math.PI * 2);
                float phi = Math.acos(2 * rand.nextFloat() - 1);
                float r = radius * (float) java.lang.Math.cbrt(rand.nextFloat());
                p.position.x = spawnCenter.x + r * org.joml.Math.sin(phi) * org.joml.Math.cos(theta);
                p.position.y = spawnCenter.y + r * org.joml.Math.sin(phi) * org.joml.Math.sin(theta);
                p.position.z = spawnCenter.z + r * org.joml.Math.cos(phi);
            }
            case "position_box" -> {
                float sx = getFloatParam(init.params, "m_flSizeX", 16f);
                float sy = getFloatParam(init.params, "m_flSizeY", 16f);
                float sz = getFloatParam(init.params, "m_flSizeZ", 16f);
                p.position.x = spawnCenter.x + (rand.nextFloat() - 0.5f) * sx * 2;
                p.position.y = spawnCenter.y + (rand.nextFloat() - 0.5f) * sy * 2;
                p.position.z = spawnCenter.z + (rand.nextFloat() - 0.5f) * sz * 2;
            }
            case "velocity_random" -> {
                float minSpeed = getFloatParam(init.params, "m_flMinSpeed", 0f);
                float maxSpeed = getFloatParam(init.params, "m_flMaxSpeed", 100f);
                float speed = minSpeed + rand.nextFloat() * (maxSpeed - minSpeed);
                float theta = rand.nextFloat() * (float) (org.joml.Math.PI * 2);
                float phi = Math.acos(2 * rand.nextFloat() - 1);
                p.velocity.x = speed * org.joml.Math.sin(phi) * org.joml.Math.cos(theta);
                p.velocity.y = speed * org.joml.Math.sin(phi) * org.joml.Math.sin(theta);
                p.velocity.z = speed * org.joml.Math.cos(phi);
            }
            case "color_random" -> {
                float rMin = getFloatParam(init.params, "m_flColorMinR", 0.5f);
                float rMax = getFloatParam(init.params, "m_flColorMaxR", 1f);
                float gMin = getFloatParam(init.params, "m_flColorMinG", 0.5f);
                float gMax = getFloatParam(init.params, "m_flColorMaxG", 1f);
                float bMin = getFloatParam(init.params, "m_flColorMinB", 0.5f);
                float bMax = getFloatParam(init.params, "m_flColorMaxB", 1f);
                p.color.x = rMin + rand.nextFloat() * (rMax - rMin);
                p.color.y = gMin + rand.nextFloat() * (gMax - gMin);
                p.color.z = bMin + rand.nextFloat() * (bMax - bMin);
            }
            case "alpha_random" -> {
                float aMin = getFloatParam(init.params, "m_flAlphaMin", 0.5f);
                float aMax = getFloatParam(init.params, "m_flAlphaMax", 1f);
                p.color.w = aMin + rand.nextFloat() * (aMax - aMin);
            }
            case "lifetime_random" -> {
                float minLife = getFloatParam(init.params, "m_flLifetimeMin", 0.5f);
                float maxLife = getFloatParam(init.params, "m_flLifetimeMax", 3f);
                p.lifetime = minLife + rand.nextFloat() * (maxLife - minLife);
            }
            case "size_random" -> {
                float minSize = getFloatParam(init.params, "m_flSizeMin", 4f);
                float maxSize = getFloatParam(init.params, "m_flSizeMax", 16f);
                p.size = minSize + rand.nextFloat() * (maxSize - minSize);
            }
            case "rotation_random" -> {
                float minRot = getFloatParam(init.params, "m_flRotMin", 0f);
                float maxRot = getFloatParam(init.params, "m_flRotMax", (float) (org.joml.Math.PI * 2));
                p.rotation = minRot + rand.nextFloat() * (maxRot - minRot);
                float minAV = getFloatParam(init.params, "m_flAngVelMin", -3f);
                float maxAV = getFloatParam(init.params, "m_flAngVelMax", 3f);
                p.angularVelocity = minAV + rand.nextFloat() * (maxAV - minAV);
            }
            case "position_circle" -> {
                float radius = getFloatParam(init.params, "m_flRadius", 16f);
                float angle = rand.nextFloat() * (float) (org.joml.Math.PI * 2);
                p.position.x = spawnCenter.x + radius * org.joml.Math.cos(angle);
                p.position.z = spawnCenter.z + radius * org.joml.Math.sin(angle);
                p.position.y = spawnCenter.y + getFloatParam(init.params, "m_flHeight", 0f) * (rand.nextFloat() - 0.5f);
            }
        }
    }

    private void applyOperators(Particle p, float dt) {
        for (var op : def.operators) {
            switch (op.type) {
                case "gravity" -> {
                    float g = getRampParam(op.params, "m_flGravity", 400f, p.getProgress());
                    p.velocity.y -= g * dt;
                }
                case "friction", "damping" -> {
                    float drag = getRampParam(op.params, "m_flDrag", 0.1f, p.getProgress());
                    p.velocity.mul(1f - drag * dt);
                }
                case "noise" -> {
                    float strength = getRampParam(op.params, "m_flStrength", 10f, p.getProgress());
                    float freq = getRampParam(op.params, "m_flFrequency", 1f, p.getProgress());
                    float phase = p.age * freq;
                    float noiseX = Math.sin(phase + p.position.x * 0.1f);
                    float noiseY = Math.cos(phase + p.position.y * 0.1f);
                    float noiseZ = Math.sin(phase + p.position.z * 0.1f);
                    p.velocity.x += noiseX * strength * dt;
                    p.velocity.y += noiseY * strength * dt;
                    p.velocity.z += noiseZ * strength * dt;
                }
                case "color_fade" -> {
                    float r = getRampParam(op.params, "m_flFadeR", 0f, p.getProgress());
                    float g = getRampParam(op.params, "m_flFadeG", 0f, p.getProgress());
                    float b = getRampParam(op.params, "m_flFadeB", 0f, p.getProgress());
                    float progress = p.getProgress();
                    p.color.x = Math.lerp(p.color.x, r, progress * 0.1f);
                    p.color.y = Math.lerp(p.color.y, g, progress * 0.1f);
                    p.color.z = Math.lerp(p.color.z, b, progress * 0.1f);
                }
                case "alpha_fade" -> {
                    float startAlpha = getRampParam(op.params, "m_flStartAlpha", 1f, p.getProgress());
                    float endAlpha = getRampParam(op.params, "m_flEndAlpha", 0f, p.getProgress());
                    float progress = p.getProgress();
                    p.color.w = startAlpha + (endAlpha - startAlpha) * progress;
                }
                case "size_scale" -> {
                    float startSize = getRampParam(op.params, "m_flStartSize", 1f, p.getProgress());
                    float endSize = getRampParam(op.params, "m_flEndSize", 0.5f, p.getProgress());
                    float progress = p.getProgress();
                    p.size = p.size * (startSize + (endSize - startSize) * progress);
                }
                case "oscillator" -> {
                    float amp = getRampParam(op.params, "m_flAmplitude", 5f, p.getProgress());
                    float freq = getRampParam(op.params, "m_flFrequency", 2f, p.getProgress());
                    float phase = p.age * freq * (float) (org.joml.Math.PI * 2);
                    p.position.x += org.joml.Math.sin(phase) * amp * dt;
                    p.position.y += org.joml.Math.cos(phase) * amp * dt;
                }
                case "vortex" -> {
                    float strength = getRampParam(op.params, "m_flStrength", 50f, p.getProgress());
                    float radius = new Vector3f(p.position).sub(origin).length();
                    if (radius > 0.01f) {
                        Vector3f tangential = new Vector3f(
                            -(p.position.z - origin.z),
                            0,
                            (p.position.x - origin.x)
                        ).normalize();
                        p.velocity.add(tangential.mul(strength * dt));
                        p.velocity.add(new Vector3f(origin).sub(p.position).normalize().mul(strength * 0.3f * dt));
                    }
                }
                case "wind" -> {
                    float strength = getRampParam(op.params, "m_flWindStrength", 50f, p.getProgress());
                    float dirX = getFloatParam(op.params, "m_vWindDirX", 1f);
                    float dirY = getFloatParam(op.params, "m_vWindDirY", 0f);
                    float dirZ = getFloatParam(op.params, "m_vWindDirZ", 0f);
                    p.velocity.x += dirX * strength * dt;
                    p.velocity.y += dirY * strength * dt;
                    p.velocity.z += dirZ * strength * dt;
                }
            }
        }

        // Forces（m_vDirection 类）：controlPointBased 时方向取 CP - p.position 归一化
        if (def.forces != null) {
            for (var force : def.forces) {
                float mag = force.magnitude;
                if (mag == 0f) continue;
                Vector3f dir;
                if (force.controlPointBased) {
                    Vector3f cp = controlPoints.get(force.controlPoint);
                    dir = new Vector3f(cp).sub(p.position);
                    float len = dir.length();
                    if (len < 0.001f) continue;
                    dir.div(len);
                } else {
                    dir = new Vector3f(force.direction[0], force.direction[1], force.direction[2]);
                    float len = dir.length();
                    if (len < 0.001f) continue;
                    dir.div(len);
                }
                p.velocity.add(dir.mul(mag * dt));
            }
        }
    }

    private float getFloatParam(Map<String, Object> params, String key, float def) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.floatValue();
        return def;
    }

    private float getRampParam(Map<String, Object> params, String key, float fallback, float progress) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.floatValue();
        // _ramp 曲线采样：字段名同名 + "_ramp"
        ParticleRamp ramp = def.ramps.get(key + "_ramp");
        if (ramp != null) return ramp.sample(progress);
        return fallback;
    }

    private boolean getBooleanParam(Map<String, Object> params, String key, boolean def) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue() != 0;
        if (val instanceof Boolean b) return b;
        return def;
    }

    private int getIntParam(Map<String, Object> params, String key, int def) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        return def;
    }

    public List<Particle> getParticles() {
        return particles;
    }

    public int getParticleCount() { return particles.size(); }

    public PcfParticleSystemDef.SystemDefinition getDefinition() { return def; }

    public void burst(int count) {
        for (int i = 0; i < count && particles.size() < def.maxParticles; i++) {
            spawnParticle();
        }
    }

    // --- Control Points ---

    public ParticleControlPoints getControlPoints() { return controlPoints; }

    public void copyControlPoints(ParticleControlPoints cps) { controlPoints.copyFrom(cps); }

    public void setControlPoint(int index, Vector3f v) { controlPoints.set(index, v); }

    public void setControlPoint(int index, double x, double y, double z) {
        controlPoints.set(index, new Vector3f((float) x, (float) y, (float) z));
    }

    // --- 子发射器注入 ---

    public void setOnChildSpawn(Consumer<String> onChildSpawn) {
        this.onChildSpawn = onChildSpawn;
    }
}
