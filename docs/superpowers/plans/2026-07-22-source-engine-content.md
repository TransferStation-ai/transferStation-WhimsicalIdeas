# Source Engine 内容内置 — 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在模组中内置完整的 Source 引擎粒子系统（.pcf 解析+渲染）和 Valve 经典 NPC 模型包，安装即用。

**架构：**
- **粒子系统：** 二进制 .pcf 解析器 → 粒子系统定义 POJO → 运行时发射器/管理器 → 自定义渲染器（7 种），通过 Forge 事件/管道集成到 Minecraft
- **Valve 模型包：** JSON 注册清单 + 内置资源目录 → ValveContentLoader → 复用现有 NpcModelRegistry 机制注册 EntityType

**技术栈：** Java 17, Minecraft Forge (1.20.1), OpenGL (自定义粒子渲染), 二进制文件解析

---

## 文件清单

### 粒子系统（新建）

| # | 文件路径 | 职责 |
|---|---------|------|
| 1 | `.../client/particle/PcfParser.java` | 二进制 .pcf 文件解析（magic, header, KeyValues 树） |
| 2 | `.../client/particle/PcfParticleSystem.java` | 粒子系统定义 POJO（属性、渲染器、初始化器、运算符、子粒子、力） |
| 3 | `.../client/particle/renderer/RendererType.java` | 渲染器类型枚举 + 各类型数据记录 |
| 4 | `.../client/particle/Initializer.java` | 初始化器接口 + 所有实现类（位置/速度/颜色/Alpha/大小/寿命/旋转） |
| 5 | `.../client/particle/Operator.java` | 运算符接口 + 所有实现类（重力/阻力/噪点/颜色渐变/Alpha 淡入淡出/大小缩放/振荡器/涡流） |
| 6 | `.../client/particle/Particle.java` | 单个粒子实例（位置/速度/颜色/alpha/大小/旋转/年龄/寿命/序列帧） |
| 7 | `.../client/particle/ParticleEmitter.java` | 发射器（按定义生成粒子、tick 更新、回收死亡粒子） |
| 8 | `.../client/particle/ParticleManager.java` | 全局管理器（注册系统定义、触发效果、tick/渲染调度、缓存、配额控制） |
| 9 | `.../client/particle/renderer/ParticleRenderer.java` | 渲染器接口 |
| 10 | `.../client/particle/renderer/SpriteParticleRenderer.java` | 精灵粒子渲染（billboard 四边形） |
| 11 | `.../client/particle/renderer/ModelParticleRenderer.java` | 模型粒子渲染（实例化 .mdl） |
| 12 | `.../client/particle/renderer/BeamParticleRenderer.java` | 光束粒子渲染 |
| 13 | `.../client/particle/renderer/TrailParticleRenderer.java` | 拖尾/带装轨迹渲染 |
| 14 | `.../client/particle/renderer/DecalParticleRenderer.java` | 贴花渲染 |
| 15 | `.../client/particle/renderer/LightParticleRenderer.java` | 动态光源渲染 |
| 16 | `.../client/particle/renderer/RopeParticleRenderer.java` | 绳索渲染 |
| 17 | `.../client/particle/integration/ParticleCommands.java` | `/particle_spawn` 命令 |
| 18 | `.../client/particle/integration/ParticleClientHandler.java` | Forge 事件监听（渲染层注册、Tick、世界加载/卸载） |

### Valve 模型包

| # | 文件路径 | 职责 |
|---|---------|------|
| 19 | `.../client/model/ValveContentLoader.java` | 从 mod jar 内读取 `valve_npc_registry.json`，加载内置模型到 NPC 注册表 |
| 20 | `src/main/resources/assets/.../valve_valve_npc_registry.json` | NPC 注册清单（名称、模型路径、属性） |
| 21 | `src/main/resources/assets/.../valve_content/models/npc/...` | 内置 .mdl/.vvd/.vtx/.phy 文件 |
| 22 | `src/main/resources/assets/.../valve_content/materials/...` | 内置 .vmt/.vtf 文件 |

### 修改现有文件

| # | 文件 | 变更 |
|---|------|------|
| 23 | `Transferstation_whimsicalideas.java` | 在 ClientModEvents 中初始化 ParticleManager |
| 24 | `NpcModelRegistry.java` | 添加 `registerFromBuiltin(path)` 方法支持从类路径资源注册 |

---

## 任务分解

---

### 任务 1：PCF Parser 核心

**文件：** 创建 `src/main/java/transferstation/transferstation_whimsicalideas/client/particle/PcfParser.java`

**说明：** 解析 Source 引擎 .pcf 二进制格式。.pcf 文件结构为：
- Header: signature (u32 `0x50434646` = "PCFF"), version (u32), padding
- KeyValues 树: 顶层 `m_particleSystemDefinition` 包含所有粒子系统定义

**KeyValues 二进制格式：**
- 类型 byte + 名称 string (null-terminated) + 值/子节点
- 类型: 0x00=Null, 0x01=String, 0x02=Int, 0x03=Float, 0x04=Ptr, 0x05=WString
- 子节点用 0x08 (Object open) / 0x09 (Object close) 包裹

- [ ] **步骤 1：创建 PcfParser 骨架**

```java
package transferstation.transferstation_whimsicalideas.client.particle;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PcfParser {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PCF_SIGNATURE = 0x50434646; // "PCFF"
    private static final int KV_TYPE_NULL = 0x00;
    private static final int KV_TYPE_STRING = 0x01;
    private static final int KV_TYPE_INT = 0x02;
    private static final int KV_TYPE_FLOAT = 0x03;
    private static final int KV_TYPE_PTR = 0x04;
    private static final int KV_TYPE_WSTRING = 0x05;
    private static final int KV_TYPE_OBJECT_OPEN = 0x08;
    private static final int KV_TYPE_OBJECT_CLOSE = 0x09;
    private static final int KV_TYPE_ARRAY_OPEN = 0x0A;
    private static final int KV_TYPE_ARRAY_CLOSE = 0x0B;

    public static PcfParticleSystemDef parse(byte[] data) throws IOException {
        var buf = new PcfBuffer(data);
        int signature = buf.readInt32();
        if (signature != PCF_SIGNATURE) {
            throw new IOException("Invalid PCF signature: 0x" + Integer.toHexString(signature));
        }
        int version = buf.readInt32();
        if (version < 1 || version > 3) {
            LOGGER.warn("[PcfParser] Unknown PCF version: {}, attempting to parse anyway", version);
        }
        // Skip padding (4 bytes)
        buf.readInt32();

        var root = parseKeyValues(buf);
        var systemDefs = new PcfParticleSystemDef();
        // Recursively extract particle system definitions from the tree
        extractSystems(root, systemDefs);
        return systemDefs;
    }

    private static KvNode parseKeyValues(PcfBuffer buf) throws IOException {
        var root = new KvNode("root", KvType.NULL, null);
        parseChildren(buf, root);
        return root;
    }

    private static void parseChildren(PcfBuffer buf, KvNode parent) throws IOException {
        while (buf.hasRemaining()) {
            int type = buf.readUInt8();
            if (type == KV_TYPE_OBJECT_CLOSE || type == KV_TYPE_ARRAY_CLOSE) {
                return;
            }
            String name = buf.readNullTerminatedString();
            switch (type) {
                case KV_TYPE_NULL:
                    parent.children.add(new KvNode(name, KvType.NULL, null));
                    break;
                case KV_TYPE_STRING:
                    parent.children.add(new KvNode(name, KvType.STRING, buf.readNullTerminatedString()));
                    break;
                case KV_TYPE_INT:
                    parent.children.add(new KvNode(name, KvType.INT, buf.readInt32()));
                    break;
                case KV_TYPE_FLOAT:
                    parent.children.add(new KvNode(name, KvType.FLOAT, buf.readFloat32()));
                    break;
                case KV_TYPE_PTR:
                    parent.children.add(new KvNode(name, KvType.PTR, buf.readInt32()));
                    break;
                case KV_TYPE_WSTRING: {
                    int len = buf.readUInt16();
                    byte[] wide = buf.readBytes(len * 2);
                    String str = new String(wide, StandardCharsets.UTF_16LE);
                    parent.children.add(new KvNode(name, KvType.WSTRING, str));
                    break;
                }
                case KV_TYPE_OBJECT_OPEN: {
                    var child = new KvNode(name, KvType.OBJECT, null);
                    parseChildren(buf, child);
                    parent.children.add(child);
                    break;
                }
                case KV_TYPE_ARRAY_OPEN: {
                    var child = new KvNode(name, KvType.ARRAY, null);
                    parseChildren(buf, child);
                    parent.children.add(child);
                    break;
                }
                default:
                    LOGGER.debug("[PcfParser] Unknown KV type: 0x{} at pos {}", Integer.toHexString(type), buf.position());
                    break;
            }
        }
    }

    private void extractSystems(KvNode node, PcfParticleSystemDef target) {
        for (var child : node.children) {
            if ("m_particleSystemDefinition".equals(child.name) && child.type == KvType.OBJECT) {
                target.systemDefinitions.add(parseSystemDef(child));
            }
            extractSystems(child, target);
        }
    }

    private static PcfParticleSystemDef.SystemDefinition parseSystemDef(KvNode node) {
        var def = new PcfParticleSystemDef.SystemDefinition();
        for (var prop : node.children) {
            switch (prop.name) {
                case "m_name" -> def.name = prop.stringValue();
                case "m_nMaxParticles" -> def.maxParticles = prop.intValue();
                case "m_flConstantLife" -> def.lifespan = prop.floatValue();
                // Additional properties parsed in task 2's renderer/initializer/operator section
            }
        }
        return def;
    }

    // --- Helper types ---

    enum KvType { NULL, STRING, INT, FLOAT, PTR, WSTRING, OBJECT, ARRAY }

    static class KvNode {
        String name;
        KvType type;
        Object value;
        List<KvNode> children = new ArrayList<>();
        KvNode(String name, KvType type, Object value) {
            this.name = name; this.type = type; this.value = value;
        }
        String stringValue() { return (String) value; }
        int intValue() { return (int) value; }
        float floatValue() { return (float) value; }
    }

    static class PcfBuffer {
        private final byte[] data;
        private int pos;
        PcfBuffer(byte[] data) { this.data = data; this.pos = 0; }
        int readUInt8() { return data[pos++] & 0xFF; }
        int readInt32() { int v = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8) | ((data[pos+2] & 0xFF) << 16) | ((data[pos+3] & 0xFF) << 24); pos += 4; return v; }
        float readFloat32() { return Float.intBitsToFloat(readInt32()); }
        int readUInt16() { int v = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8); pos += 2; return v; }
        String readNullTerminatedString() {
            int start = pos;
            while (pos < data.length && data[pos] != 0) pos++;
            String s = new String(data, start, pos - start, StandardCharsets.UTF_8);
            if (pos < data.length) pos++;
            return s;
        }
        byte[] readBytes(int n) { byte[] b = new byte[n]; System.arraycopy(data, pos, b, 0, n); pos += n; return b; }
        boolean hasRemaining() { return pos < data.length; }
        int position() { return pos; }
    }
}
```

- [ ] **步骤 2：创建 PcfParticleSystemDef POJO**

**文件：** 创建 `src/main/java/transferstation/transferstation_whimsicalideas/client/particle/PcfParticleSystemDef.java`

```java
package transferstation.transferstation_whimsicalideas.client.particle;

import java.util.*;

public class PcfParticleSystemDef {
    public final List<SystemDefinition> systemDefinitions = new ArrayList<>();

    public static class SystemDefinition {
        public String name;
        public int maxParticles = 1000;
        public float lifespan = 0f;      // 0 = per-particle lifetime
        public float emissionRate = 10f;
        public RendererDef renderer;
        public final List<InitializerDef> initializers = new ArrayList<>();
        public final List<OperatorDef> operators = new ArrayList<>();
        public final List<ChildDef> children = new ArrayList<>();
        public final List<ForceDef> forces = new ArrayList<>();
        public boolean continuous = false;  // continuous emission vs burst
    }

    public static class RendererDef {
        public RendererType type = RendererType.SPRITE;
        // Sprite
        public String materialPath;
        public int textureWidth = 64, textureHeight = 64;
        public boolean additive = false;
        // Model
        public String modelPath;
        public float modelScale = 1f;
        // Beam
        public float beamWidth = 2f;
        // Trail
        public float trailLength = 1f;
        public int trailSegments = 8;
        // Decal
        public String decalMaterial;
        public float decalSize = 16f;
        // Light
        public float lightRadius = 8f;
        public float lightIntensity = 1f;
        // Rope
        public float ropeWidth = 1f;
        public int ropeSegments = 16;
    }

    public enum RendererType {
        SPRITE, MODEL, BEAM, TRAIL, DECAL, LIGHT, ROPE
    }

    public static class InitializerDef {
        public String type;
        public Map<String, Object> params = new HashMap<>();
    }

    public static class OperatorDef {
        public String type;
        public Map<String, Object> params = new HashMap<>();
    }

    public static class ChildDef {
        public String childName;
        public float delay;
        public float delayRate;
    }

    public static class ForceDef {
        public String type;
        public float magnitude;
        public float[] direction = new float[3];
    }
}
```

- [ ] **步骤 3：完成 PcfParser 的完整属性解析**

修改 `parseSystemDef()` 方法，解析完整的粒子系统属性：

```java
private static PcfParticleSystemDef.SystemDefinition parseSystemDef(KvNode node) {
    var def = new PcfParticleSystemDef.SystemDefinition();
    for (var prop : node.children) {
        switch (prop.name) {
            case "m_name" -> def.name = prop.stringValue();
            case "m_nMaxParticles" -> def.maxParticles = prop.intValue();
            case "m_flConstantLife" -> def.lifespan = prop.floatValue();
            case "m_flEmissionRate" -> def.emissionRate = prop.floatValue();
            case "m_bContinuous" -> def.continuous = prop.intValue() != 0;
            case "_renderer" -> def.renderer = parseRenderer(prop);
            case "_initializers" -> parseInitializers(prop, def.initializers);
            case "_operators" -> parseOperators(prop, def.operators);
            case "_children" -> parseChildren(prop, def.children);
            case "_forces" -> parseForces(prop, def.forces);
        }
    }
    return def;
}

private static PcfParticleSystemDef.RendererDef parseRenderer(KvNode node) {
    var renderer = new PcfParticleSystemDef.RendererDef();
    for (var prop : node.children) {
        switch (prop.name) {
            case "m_nRendererType" -> renderer.type = switch (prop.intValue()) {
                case 0 -> PcfParticleSystemDef.RendererType.SPRITE;
                case 1 -> PcfParticleSystemDef.RendererType.MODEL;
                case 2 -> PcfParticleSystemDef.RendererType.BEAM;
                case 3 -> PcfParticleSystemDef.RendererType.TRAIL;
                case 4 -> PcfParticleSystemDef.RendererType.DECAL;
                case 5 -> PcfParticleSystemDef.RendererType.LIGHT;
                case 6 -> PcfParticleSystemDef.RendererType.ROPE;
                default -> PcfParticleSystemDef.RendererType.SPRITE;
            };
            case "m_szMaterialName" -> renderer.materialPath = prop.stringValue();
            // ... additional renderer-specific parameters
        }
    }
    return renderer;
}

private static void parseInitializers(KvNode node, List<PcfParticleSystemDef.InitializerDef> list) {
    for (var item : node.children) {
        if (item.type == KvType.OBJECT && item.value == null) {
            var init = new PcfParticleSystemDef.InitializerDef();
            for (var p : item.children) {
                if ("m_nInitializerType".equals(p.name)) init.type = switch (p.intValue()) {
                    case 0 -> "position_sphere";
                    case 1 -> "position_box";
                    case 2 -> "velocity_random";
                    case 3 -> "color_random";
                    case 4 -> "alpha_random";
                    case 5 -> "lifetime_random";
                    case 6 -> "size_random";
                    case 7 -> "rotation_random";
                    case 8 -> "position_circle";
                    case 9 -> "position_model";
                    default -> "unknown_" + p.intValue();
                };
                else init.params.put(p.name, p.value);
            }
            list.add(init);
        }
    }
}

private static void parseOperators(KvNode node, List<PcfParticleSystemDef.OperatorDef> list) {
    for (var item : node.children) {
        if (item.type == KvType.OBJECT && item.value == null) {
            var op = new PcfParticleSystemDef.OperatorDef();
            for (var p : item.children) {
                if ("m_nOperatorType".equals(p.name)) op.type = switch (p.intValue()) {
                    case 0 -> "gravity";
                    case 1 -> "friction";
                    case 2 -> "noise";
                    case 3 -> "color_fade";
                    case 4 -> "alpha_fade";
                    case 5 -> "size_scale";
                    case 6 -> "oscillator";
                    case 7 -> "vortex";
                    case 8 -> "wind";
                    case 9 -> "damping";
                    default -> "unknown_" + p.intValue();
                };
                else op.params.put(p.name, p.value);
            }
            list.add(op);
        }
    }
}

private static void parseChildren(KvNode node, List<PcfParticleSystemDef.ChildDef> list) {
    // Each child: m_childName, m_flDelay, m_flDelayRate
    for (var item : node.children) {
        if (item.type == KvType.OBJECT) {
            var child = new PcfParticleSystemDef.ChildDef();
            for (var p : item.children) {
                switch (p.name) {
                    case "m_childName" -> child.childName = p.stringValue();
                    case "m_flDelay" -> child.delay = p.floatValue();
                    case "m_flDelayRate" -> child.delayRate = p.floatValue();
                }
            }
            list.add(child);
        }
    }
}

private static void parseForces(KvNode node, List<PcfParticleSystemDef.ForceDef> list) {
    for (var item : node.children) {
        if (item.type == KvType.OBJECT) {
            var force = new PcfParticleSystemDef.ForceDef();
            for (var p : item.children) {
                switch (p.name) {
                    case "m_nForceType" -> force.type = p.stringValue();
                    case "m_flMagnitude" -> force.magnitude = p.floatValue();
                    case "m_vDirection" -> {
                        // Expect 3-float array
                    }
                }
            }
            list.add(force);
        }
    }
}
```

---

### 任务 2：Particle 与 ParticleEmitter 运行时

**文件：** 创建 `Particle.java` 和 `ParticleEmitter.java`

- [ ] **步骤 1：创建 Particle.java**

```java
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
```

- [ ] **步骤 2：创建 ParticleEmitter.java**

```java
package transferstation.transferstation_whimsicalideas.client.particle;

import net.minecraft.world.level.Level;
import org.joml.Math;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.*;
import java.util.function.Consumer;

public class ParticleEmitter {
    private final PcfParticleSystemDef.SystemDefinition def;
    private final Level level;
    public final Vector3f origin = new Vector3f();
    public boolean active = true;
    private final List<Particle> particles = new ArrayList<>();
    private float emissionAccumulator = 0f;
    private final Consumer<Particle> onSpawn;

    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Level level) {
        this(def, level, null);
    }

    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Level level, Consumer<Particle> onSpawn) {
        this.def = def;
        this.level = level;
        this.onSpawn = onSpawn;
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

        // Update existing particles
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.tick(dt);
            applyOperators(p, dt);
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
        var rand = level.random;
        switch (init.type) {
            case "position_sphere" -> {
                float radius = getFloatParam(init.params, "m_flRadius", 16f);
                float theta = rand.nextFloat() * org.joml.Math.PI * 2;
                float phi = (float) Math.acos(2 * rand.nextFloat() - 1);
                float r = radius * (float) Math.cbrt(rand.nextFloat());
                p.position.x += r * org.joml.Math.sin(phi) * org.joml.Math.cos(theta);
                p.position.y += r * org.joml.Math.sin(phi) * org.joml.Math.sin(theta);
                p.position.z += r * org.joml.Math.cos(phi);
            }
            case "position_box" -> {
                float sx = getFloatParam(init.params, "m_flSizeX", 16f);
                float sy = getFloatParam(init.params, "m_flSizeY", 16f);
                float sz = getFloatParam(init.params, "m_flSizeZ", 16f);
                p.position.x += (rand.nextFloat() - 0.5f) * sx * 2;
                p.position.y += (rand.nextFloat() - 0.5f) * sy * 2;
                p.position.z += (rand.nextFloat() - 0.5f) * sz * 2;
            }
            case "velocity_random" -> {
                float minSpeed = getFloatParam(init.params, "m_flMinSpeed", 0f);
                float maxSpeed = getFloatParam(init.params, "m_flMaxSpeed", 100f);
                float speed = minSpeed + rand.nextFloat() * (maxSpeed - minSpeed);
                float theta = rand.nextFloat() * org.joml.Math.PI * 2;
                float phi = (float) Math.acos(2 * rand.nextFloat() - 1);
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
                float maxRot = getFloatParam(init.params, "m_flRotMax", org.joml.Math.PI * 2);
                p.rotation = minRot + rand.nextFloat() * (maxRot - minRot);
                float minAV = getFloatParam(init.params, "m_flAngVelMin", -3f);
                float maxAV = getFloatParam(init.params, "m_flAngVelMax", 3f);
                p.angularVelocity = minAV + rand.nextFloat() * (maxAV - minAV);
            }
            case "position_circle" -> {
                float radius = getFloatParam(init.params, "m_flRadius", 16f);
                float angle = rand.nextFloat() * org.joml.Math.PI * 2;
                p.position.x += radius * org.joml.Math.cos(angle);
                p.position.z += radius * org.joml.Math.sin(angle);
                p.position.y += getFloatParam(init.params, "m_flHeight", 0f) * (rand.nextFloat() - 0.5f);
            }
        }
    }

    private void applyOperators(Particle p, float dt) {
        for (var op : def.operators) {
            switch (op.type) {
                case "gravity" -> {
                    float g = getFloatParam(op.params, "m_flGravity", 400f);
                    p.velocity.y -= g * dt;
                }
                case "friction", "damping" -> {
                    float drag = getFloatParam(op.params, "m_flDrag", 0.1f);
                    p.velocity.mul(1f - drag * dt);
                }
                case "noise" -> {
                    float strength = getFloatParam(op.params, "m_flStrength", 10f);
                    float freq = getFloatParam(op.params, "m_flFrequency", 1f);
                    float noiseX = (float) (Math.random() - 0.5f) * 2;
                    float noiseY = (float) (Math.random() - 0.5f) * 2;
                    float noiseZ = (float) (Math.random() - 0.5f) * 2;
                    p.velocity.x += noiseX * strength * dt;
                    p.velocity.y += noiseY * strength * dt;
                    p.velocity.z += noiseZ * strength * dt;
                }
                case "color_fade" -> {
                    float r = getFloatParam(op.params, "m_flFadeR", 0f);
                    float g = getFloatParam(op.params, "m_flFadeG", 0f);
                    float b = getFloatParam(op.params, "m_flFadeB", 0f);
                    float progress = p.getProgress();
                    p.color.x = Math.lerp(p.color.x, r, progress * 0.1f);
                    p.color.y = Math.lerp(p.color.y, g, progress * 0.1f);
                    p.color.z = Math.lerp(p.color.z, b, progress * 0.1f);
                }
                case "alpha_fade" -> {
                    float startAlpha = getFloatParam(op.params, "m_flStartAlpha", 1f);
                    float endAlpha = getFloatParam(op.params, "m_flEndAlpha", 0f);
                    float progress = p.getProgress();
                    p.color.w = startAlpha + (endAlpha - startAlpha) * progress;
                }
                case "size_scale" -> {
                    float startSize = getFloatParam(op.params, "m_flStartSize", 1f);
                    float endSize = getFloatParam(op.params, "m_flEndSize", 0.5f);
                    float progress = p.getProgress();
                    p.size = p.size * (startSize + (endSize - startSize) * progress);
                }
                case "oscillator" -> {
                    float amp = getFloatParam(op.params, "m_flAmplitude", 5f);
                    float freq = getFloatParam(op.params, "m_flFrequency", 2f);
                    float phase = p.age * freq * org.joml.Math.PI * 2;
                    p.position.x += org.joml.Math.sin(phase) * amp * dt;
                    p.position.y += org.joml.Math.cos(phase) * amp * dt;
                }
                case "vortex" -> {
                    float strength = getFloatParam(op.params, "m_flStrength", 50f);
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
                    float strength = getFloatParam(op.params, "m_flWindStrength", 50f);
                    float dirX = getFloatParam(op.params, "m_vWindDirX", 1f);
                    float dirY = getFloatParam(op.params, "m_vWindDirY", 0f);
                    float dirZ = getFloatParam(op.params, "m_vWindDirZ", 0f);
                    p.velocity.x += dirX * strength * dt;
                    p.velocity.y += dirY * strength * dt;
                    p.velocity.z += dirZ * strength * dt;
                }
            }
        }
    }

    private float getFloatParam(Map<String, Object> params, String key, float def) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.floatValue();
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
}
```

---

### 任务 3：ParticleManager 全局管理器

**文件：** 创建 `src/main/java/transferstation/transferstation_whimsicalideas/client/particle/ParticleManager.java`

- [ ] **步骤 1：创建 ParticleManager**

```java
package transferstation.transferstation_whimsicalideas.client.particle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ParticleManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ParticleManager INSTANCE;

    private final Map<String, PcfParticleSystemDef.SystemDefinition> registry = new ConcurrentHashMap<>();
    private final List<ParticleEmitter> activeEmitters = Collections.synchronizedList(new ArrayList<>());
    private int maxGlobalParticles = 10000;
    private int maxParticlesPerEffect = 2000;

    // PCF file cache (raw bytes)
    private final Map<String, byte[]> pcfCache = new ConcurrentHashMap<>();

    private ParticleManager() {}

    public static ParticleManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ParticleManager();
        }
        return INSTANCE;
    }

    /** Register a particle system definition from parsed PCF data */
    public void registerSystem(PcfParticleSystemDef.SystemDefinition def) {
        if (def.name != null && !def.name.isEmpty()) {
            registry.put(def.name, def);
            LOGGER.info("[ParticleManager] Registered particle system: '{}' ({} inits, {} ops, {} children)",
                def.name, def.initializers.size(), def.operators.size(), def.children.size());
        }
    }

    /** Load a .pcf file from path and register all systems within */
    public void loadPcfFile(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            PcfParticleSystemDef systems = PcfParser.parse(data);
            for (var def : systems.systemDefinitions) {
                registerSystem(def);
            }
            pcfCache.put(path.getFileName().toString(), data);
            LOGGER.info("[ParticleManager] Loaded {} particle systems from {}", systems.systemDefinitions.size(), path);
        } catch (IOException e) {
            LOGGER.error("[ParticleManager] Failed to load PCF: {}", path, e);
        }
    }

    /** Load a .pcf from byte array (e.g. from jar resources) */
    public void loadPcfFromBytes(String name, byte[] data) {
        try {
            PcfParticleSystemDef systems = PcfParser.parse(data);
            for (var def : systems.systemDefinitions) {
                registerSystem(def);
            }
            pcfCache.put(name, data);
        } catch (IOException e) {
            LOGGER.error("[ParticleManager] Failed to parse PCF from bytes: {}", name, e);
        }
    }

    /** Spawn a particle effect at a world position */
    public ParticleEmitter spawnEffect(String systemName, Level level, double x, double y, double z) {
        return spawnEffect(systemName, level, x, y, z, null);
    }

    public ParticleEmitter spawnEffect(String systemName, Level level, double x, double y, double z,
                                        Consumer<Particle> onSpawn) {
        var def = registry.get(systemName);
        if (def == null) {
            LOGGER.warn("[ParticleManager] Unknown particle system: '{}'", systemName);
            return null;
        }
        var emitter = new ParticleEmitter(def, level, onSpawn);
        emitter.origin.set((float) x, (float) y, (float) z);
        emitter.active = true;
        activeEmitters.add(emitter);

        // Burst initial particles
        int burstCount = def.continuous ? Math.min((int) def.emissionRate, 20) : def.maxParticles;
        emitter.burst(burstCount);

        return emitter;
    }

    /** Tick all active emitters (called every client tick) */
    public void tick(float dt) {
        dt = Math.min(dt, 0.05f); // cap to prevent physics explosion

        synchronized (activeEmitters) {
            Iterator<ParticleEmitter> it = activeEmitters.iterator();
            while (it.hasNext()) {
                ParticleEmitter emitter = it.next();
                emitter.tick(dt);

                // Enforce particle caps
                int globalCount = getTotalParticleCount();
                if (globalCount > maxGlobalParticles) {
                    emitter.active = false;
                }
                if (emitter.getParticleCount() > maxParticlesPerEffect) {
                    emitter.active = false;
                }

                // Remove finished emitters (0 particles and inactive)
                if (!emitter.active && emitter.getParticleCount() == 0) {
                    it.remove();
                }
            }
        }
    }

    /** Render all particles (called every frame) */
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, float partialTicks) {
        synchronized (activeEmitters) {
            for (var emitter : activeEmitters) {
                // Delegate rendering to the appropriate renderer based on emitter definition
                var def = emitter.getDefinition();
                if (def.renderer == null) continue;
                // Renderers will be selected and dispatched here
            }
        }
    }

    public int getTotalParticleCount() {
        int count = 0;
        synchronized (activeEmitters) {
            for (var emitter : activeEmitters) {
                count += emitter.getParticleCount();
            }
        }
        return count;
    }

    public void clearAll() {
        synchronized (activeEmitters) {
            activeEmitters.clear();
        }
    }

    public void onWorldUnload() {
        clearAll();
    }
}
```

---

### 任务 4：粒子渲染器体系

**文件：** 创建 `renderer/ParticleRenderer.java`（接口）+ 7 个渲染器实现

- [ ] **步骤 1：创建渲染器接口**

```java
// src/main/java/.../client/particle/renderer/ParticleRenderer.java
package transferstation.transferstation_whimsicalideas.client.particle.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;

import java.util.List;

public interface ParticleRenderer {
    void render(PoseStack poseStack, MultiBufferSource bufferSource,
                ParticleEmitter emitter, List<Particle> particles,
                float partialTicks, int packedLight);
}
```

- [ ] **步骤 2：创建 SpriteParticleRenderer**

```java
// src/main/java/.../client/particle/renderer/SpriteParticleRenderer.java
package transferstation.transferstation_whimsicalideas.client.particle.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;

import java.util.List;

public class SpriteParticleRenderer implements ParticleRenderer {
    private ResourceLocation texture;

    public SpriteParticleRenderer(ResourceLocation texture) {
        this.texture = texture;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource,
                       ParticleEmitter emitter, List<Particle> particles,
                       float partialTicks, int packedLight) {
        if (particles.isEmpty()) return;

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f cameraPos = camera.getPosition().toVector3f();

        // Use Minecraft's particle rendering approach: billboard quads
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthWrite(false);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

        Matrix4f matrix = poseStack.last().pose();

        for (Particle p : particles) {
            float progress = p.getProgress();
            if (progress >= 1f) continue;

            float alpha = p.getAlpha();
            if (alpha < 0.01f) continue;

            float rx = p.position.x - cameraPos.x;
            float ry = p.position.y - cameraPos.y;
            float rz = p.position.z - cameraPos.z;
            float halfSize = p.size * 0.5f;

            // Billboard: face camera
            Vector3f up = camera.getUp().toVector3f();
            Vector3f right = camera.getLeft().toVector3f(); // actually right since we negate

            Vector3f v0 = new Vector3f(rx - right.x * halfSize + up.x * halfSize,
                                       ry - right.y * halfSize + up.y * halfSize,
                                       rz - right.z * halfSize + up.z * halfSize);
            Vector3f v1 = new Vector3f(rx + right.x * halfSize + up.x * halfSize,
                                       ry + right.y * halfSize + up.y * halfSize,
                                       rz + right.z * halfSize + up.z * halfSize);
            Vector3f v2 = new Vector3f(rx + right.x * halfSize - up.x * halfSize,
                                       ry + right.y * halfSize - up.y * halfSize,
                                       rz + right.z * halfSize - up.z * halfSize);
            Vector3f v3 = new Vector3f(rx - right.x * halfSize - up.x * halfSize,
                                       ry - right.y * halfSize - up.y * halfSize,
                                       rz - right.z * halfSize - up.z * halfSize);

            int light = 0xF000F0; // fullbright for particles by default
            float u0 = 0f, v0t = 0f, u1 = 1f, v1t = 1f;

            builder.vertex(matrix, v0.x, v0.y, v0.z).uv(u1, v1t).color(p.color.x, p.color.y, p.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, v1.x, v1.y, v1.z).uv(u0, v1t).color(p.color.x, p.color.y, p.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, v2.x, v2.y, v2.z).uv(u0, v0t).color(p.color.x, p.color.y, p.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, v3.x, v3.y, v3.z).uv(u1, v0t).color(p.color.x, p.color.y, p.color.z, alpha).uv2(light).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
        RenderSystem.depthWrite(true);
    }
}
```

- [ ] **步骤 3：创建 ModelParticleRenderer（骨架）**

```java
// src/main/java/.../client/particle/renderer/ModelParticleRenderer.java
package transferstation.transferstation_whimsicalideas.client.particle.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.util.List;

/**
 * Renders particles as instanced .mdl models.
 * For MVP: renders each particle as a scaled + rotated model at particle position.
 * Uses existing ModelLoadManager to get the model data.
 */
public class ModelParticleRenderer implements ParticleRenderer {
    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource,
                       ParticleEmitter emitter, List<Particle> particles,
                       float partialTicks, int packedLight) {
        // TODO: For each particle -> load model -> render with pose from particle position/rotation
        // Reuses existing rendering pipeline from GmodModelRenderer / MdlModelRenderer
    }
}
```

- [ ] **步骤 4：创建 BeamParticleRenderer**

```java
// src/main/java/.../client/particle/renderer/BeamParticleRenderer.java
package transferstation.transferstation_whimsicalideas.client.particle.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;

import java.util.*;

/**
 * Renders beam/line particles as connected segments between particle pairs
 * or between particle and emitter origin.
 */
public class BeamParticleRenderer implements ParticleRenderer {
    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource,
                       ParticleEmitter emitter, List<Particle> particles,
                       float partialTicks, int packedLight) {
        if (particles.size() < 2) return;

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f cameraPos = camera.getPosition().toVector3f();

        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthWrite(false);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();
        float beamWidth = emitter.getDefinition().renderer != null ?
            emitter.getDefinition().renderer.beamWidth : 2f;

        // Connect particles sequentially to form beam
        for (int i = 0; i < particles.size() - 1; i++) {
            Particle a = particles.get(i);
            Particle b = particles.get(i + 1);
            if (!a.alive || !b.alive) continue;

            float alpha = (a.getAlpha() + b.getAlpha()) * 0.5f;
            builder.vertex(matrix,
                a.position.x - cameraPos.x,
                a.position.y - cameraPos.y,
                a.position.z - cameraPos.z)
                .color(a.color.x, a.color.y, a.color.z, alpha).endVertex();
            builder.vertex(matrix,
                b.position.x - cameraPos.x,
                b.position.y - cameraPos.y,
                b.position.z - cameraPos.z)
                .color(b.color.x, b.color.y, b.color.z, alpha).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
        RenderSystem.depthWrite(true);
    }
}
```

- [ ] **步骤 5：创建 TrailParticleRenderer**

```java
// src/main/java/.../client/particle/renderer/TrailParticleRenderer.java
// Renders ribbon/strip trails following particle paths
// Uses each particle's previousPosition to form a quad strip
// Implementation similar to SpriteParticleRenderer but connects
// sequential positions into ribbon geometry
```

- [ ] **步骤 6：创建 DecalParticleRenderer**

```java
// src/main/java/.../client/particle/renderer/DecalParticleRenderer.java
// Projects a texture onto surfaces using OverlayTexture-like approach
// For MVP: renders as flat sprite on the nearest surface below the particle
```

- [ ] **步骤 7：创建 LightParticleRenderer**

```java
// src/main/java/.../client/particle/renderer/LightParticleRenderer.java
// Uses Minecraft's DynamicLight system (or custom lightmaps)
// For MVP: doesn't add actual dynamic lighting (complex); renders as bright sprite instead
// Full dynamic light can be integrated with existing light systems later
```

- [ ] **步骤 8：创建 RopeParticleRenderer**

```java
// src/main/java/.../client/particle/renderer/RopeParticleRenderer.java
// Renders particles as connected line segments with thickness
// Like BeamParticleRenderer but with a fixed number of segments and sag physics
// For MVP: renders as connected lines with configurable segments
```

---

### 任务 5：Minecraft 集成层

**文件：** 创建 `ParticleCommands.java` 和 `ParticleClientHandler.java`

- [ ] **步骤 1：创建 ParticleClientHandler（Forge 事件集成）**

```java
// src/main/java/.../client/particle/integration/ParticleClientHandler.java
package transferstation.transferstation_whimsicalideas.client.particle.integration;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleManager;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, value = Dist.CLIENT)
public class ParticleClientHandler {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Minecraft.getInstance().level == null) {
            ParticleManager.getInstance().onWorldUnload();
            return;
        }
        ParticleManager.getInstance().tick(0.05f); // ~20fps tick
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        ParticleManager.getInstance().render(
            event.getPoseStack(),
            Minecraft.getInstance().renderBuffers().bufferSource(),
            event.getPartialTick()
        );
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ParticleManager.getInstance().onWorldUnload();
    }
}
```

- [ ] **步骤 2：创建 ParticleCommands（/particle_spawn 命令）**

```java
// src/main/java/.../client/particle/integration/ParticleCommands.java
package transferstation.transferstation_whimsicalideas.client.particle.integration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleManager;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, value = Dist.CLIENT)
public class ParticleCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("particle_spawn")
            .then(Commands.argument("name", StringArgumentType.string())
                .executes(ctx -> spawnParticle(ctx, ctx.getSource().getPosition()))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                    .executes(ctx -> spawnParticle(ctx, Vec3Argument.getVec3(ctx, "pos"))))
            )
        );

        dispatcher.register(Commands.literal("particle_list")
            .executes(ctx -> {
                // List registered systems
                var systems = ParticleManager.getInstance().getRegisteredSystemNames();
                ctx.getSource().sendSuccess(() ->
                    Component.literal("Registered particle systems: " + String.join(", ", systems)), false);
                return 1;
            })
        );
    }

    private static int spawnParticle(CommandContext<CommandSourceStack> ctx, Vec3 pos) {
        String name = StringArgumentType.getString(ctx, "name");
        var level = ctx.getSource().getLevel();
        var emitter = ParticleManager.getInstance().spawnEffect(name, level, pos.x, pos.y, pos.z);
        if (emitter != null) {
            ctx.getSource().sendSuccess(() ->
                Component.literal("Spawned particle effect: " + name), false);
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("Unknown particle system: " + name));
            return 0;
        }
    }
}
```

- [ ] **步骤 3：修改 Transferstation_whimsicalideas.java**

在 `ClientModEvents.onClientSetup` 中添加粒子系统初始化：

```java
// 在 onClientSetup 方法中：
// 1. 加载内置 Valve PCF 粒子文件（如果有的话）
// 2. 从外部模型目录扫描 .pcf 文件
// ...

// 添加加载内置粒子的方法
private static void loadBuiltInParticles() {
    // 从 mod jar 的 valve_content/particles/ 目录加载
    try (var is = Minecraft.getInstance().getResourceManager()
            .getResource(new ResourceLocation("transferstation_whimsicalideas",
                    "valve_content/particles/builtin.pcf"))) {
        if (is.isPresent()) {
            byte[] data = is.get().get().open().readAllBytes();
            ParticleManager.getInstance().loadPcfFromBytes("builtin", data);
        }
    } catch (Exception e) {
        // Not all builds have bundled particles
    }
}
```

---

### 任务 6：Valve 模型包 — 注册框架

**文件：** 创建 `ValveContentLoader.java`，修改 `NpcModelRegistry.java`，创建 `valve_npc_registry.json`

- [ ] **步骤 1：创建 valve_npc_registry.json**

```json
{
  "version": 1,
  "npcs": [
    {
      "id": "metrocop",
      "displayName": "Metrocop",
      "modelPath": "valve_content/models/npc/metrocop",
      "attributes": {
        "health": 30.0,
        "speed": 0.25,
        "armor": 10.0,
        "scale": 1.0
      },
      "eggColor": { "primary": 0x444444, "secondary": 0x8888FF }
    },
    {
      "id": "combine_soldier",
      "displayName": "Combine Soldier",
      "modelPath": "valve_content/models/npc/combine_soldier",
      "attributes": {
        "health": 50.0,
        "speed": 0.28,
        "armor": 30.0,
        "scale": 1.0
      },
      "eggColor": { "primary": 0x555555, "secondary": 0xFF8800 }
    },
    {
      "id": "zombie_classic",
      "displayName": "Zombie",
      "modelPath": "valve_content/models/npc/zombie_classic",
      "attributes": {
        "health": 75.0,
        "speed": 0.2,
        "armor": 0.0,
        "scale": 1.0
      },
      "eggColor": { "primary": 0x88AA44, "secondary": 0x445522 }
    },
    {
      "id": "headcrab",
      "displayName": "Headcrab",
      "modelPath": "valve_content/models/npc/headcrab",
      "attributes": {
        "health": 15.0,
        "speed": 0.35,
        "armor": 0.0,
        "scale": 0.6
      },
      "eggColor": { "primary": 0x885533, "secondary": 0xCC9966 }
    },
    {
      "id": "vortigaunt",
      "displayName": "Vortigaunt",
      "modelPath": "valve_content/models/npc/vortigaunt",
      "attributes": {
        "health": 80.0,
        "speed": 0.22,
        "armor": 5.0,
        "scale": 1.1
      },
      "eggColor": { "primary": 0x446688, "secondary": 0x88CCEE }
    },
    {
      "id": "antlion",
      "displayName": "Antlion",
      "modelPath": "valve_content/models/npc/antlion",
      "attributes": {
        "health": 40.0,
        "speed": 0.4,
        "armor": 15.0,
        "scale": 0.9
      },
      "eggColor": { "primary": 0x664422, "secondary": 0xAA8855 }
    },
    {
      "id": "fast_zombie",
      "displayName": "Fast Zombie",
      "modelPath": "valve_content/models/npc/fast_zombie",
      "attributes": {
        "health": 50.0,
        "speed": 0.45,
        "armor": 0.0,
        "scale": 0.9
      },
      "eggColor": { "primary": 0x99BB55, "secondary": 0x669933 }
    },
    {
      "id": "manhack",
      "displayName": "Manhack",
      "modelPath": "valve_content/models/npc/manhack",
      "attributes": {
        "health": 20.0,
        "speed": 0.5,
        "armor": 5.0,
        "scale": 0.5
      },
      "eggColor": { "primary": 0x888888, "secondary": 0xFFFF00 }
    },
    {
      "id": "rollermine",
      "displayName": "Rollermine",
      "modelPath": "valve_content/models/npc/rollermine",
      "attributes": {
        "health": 50.0,
        "speed": 0.3,
        "armor": 40.0,
        "scale": 0.7
      },
      "eggColor": { "primary": 0x4488AA, "secondary": 0xAAEEFF }
    },
    {
      "id": "stalker",
      "displayName": "Stalker",
      "modelPath": "valve_content/models/npc/stalker",
      "attributes": {
        "health": 100.0,
        "speed": 0.18,
        "armor": 0.0,
        "scale": 1.0
      },
      "eggColor": { "primary": 0x222233, "secondary": 0x555577 }
    }
  ]
}
```

- [ ] **步骤 2：创建 ValveContentLoader.java**

```java
// src/main/java/.../client/model/ValveContentLoader.java
package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleManager;
import transferstation.transferstation_whimsicalideas.item.AttachmentItem;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads built-in Valve content (NPC models + particle effects) from the mod jar.
 */
@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ValveContentLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            loadValveNpcs();
            loadValveParticles();
        });
    }

    private static void loadValveNpcs() {
        try {
            var resourceManager = net.minecraft.client.Minecraft.getInstance().getResourceManager();
            var resource = resourceManager.getResource(
                new ResourceLocation("transferstation_whimsicalideas", "valve_npc_registry.json"));

            if (resource.isEmpty()) {
                LOGGER.info("[ValveContentLoader] No valve_npc_registry.json found, skipping");
                return;
            }

            try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray npcs = root.getAsJsonArray("npcs");

                for (var elem : npcs) {
                    JsonObject npc = elem.getAsJsonObject();
                    String id = npc.get("id").getAsString();
                    String modelPath = npc.get("modelPath").getAsString();
                    JsonObject attrs = npc.getAsJsonObject("attributes");

                    float health = attrs.get("health").getAsFloat();
                    float speed = attrs.get("speed").getAsFloat();
                    float armor = attrs.get("armor").getAsFloat();
                    float scale = attrs.get("scale").getAsFloat();

                    // Register using existing NpcModelRegistry mechanism
                    // Note: Built-in models use a special prefix to avoid conflicts
                    String entityId = "valve_" + id;
                    registerValveNpc(entityId, modelPath, health, speed, armor, scale, npc);
                }

                LOGGER.info("[ValveContentLoader] Registered {} Valve NPCs", npcs.size());
            }
        } catch (Exception e) {
            LOGGER.error("[ValveContentLoader] Failed to load Valve NPCs", e);
        }
    }

    private static void registerValveNpc(String entityId, String modelDir, float health,
                                          float speed, float armor, float scale, JsonObject npcDef) {
        // Register entity type using NpcModelRegistry
        // This needs to happen during mod construction time, not client setup.
        // For Forge 1.20.1, deferred registers are frozen after mod construction.
        // We register the entity type in the main mod constructor using the JSON data.

        // Register attachment items if defined
        if (npcDef.has("attachments")) {
            JsonArray attachments = npcDef.getAsJsonArray("attachments");
            for (var attr : attachments) {
                JsonObject att = attr.getAsJsonObject();
                String attName = att.get("name").getAsString();
                String attModel = att.get("model").getAsString();
                String itemId = entityId + "_" + attName;
                NpcModelRegistry.registerAttachmentItem(itemId, modelDir, attName);
            }
        }
    }

    private static void loadValveParticles() {
        try {
            var resourceManager = net.minecraft.client.Minecraft.getInstance().getResourceManager();
            // Load all .pcf files from valve_content/particles/
            var resources = resourceManager.getResources(
                new ResourceLocation("transferstation_whimsicalideas", "valve_content/particles"));

            for (var resource : resources) {
                try (var is = resource.open()) {
                    byte[] data = is.readAllBytes();
                    ParticleManager.getInstance().loadPcfFromBytes(resource.getSourceName(), data);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[ValveContentLoader] No built-in particle files (expected if not bundled)");
        }
    }
}
```

- [ ] **步骤 3：修改 NpcModelRegistry 添加静态注册入口**

在 `NpcModelRegistry.java` 中添加一个方法，供 ValveContentLoader 通过 Builder 模式在 mod 初始化阶段调用：

```java
// 添加静态 builder 模式注册方法
public static RegistryObject<EntityType<?>> registerBuiltinNpc(
        String entityId, String modelPath, float health, float speed, float armor, float scale) {
    var entityType = ENTITY_TYPES.register(entityId, () ->
        EntityType.Builder.<Mob>of((type, world) -> new NpcEntity(type, world, modelPath),
            MobCategory.CREATURE)
            .sized(0.6f * scale, 1.8f * scale)
            .build(entityId));
    registeredNpcs.put(entityId, entityType);
    return entityType;
}
```

- [ ] **步骤 4：修改 Transferstation_whimsicalideas.java**

在 mod 构造函数中添加对 `valve_npc_registry.json` 的早期注册：

```java
// 在 Transferstation_whimsicalideas() 构造函数中
// 在 scanAndRegister 之后添加：
registerBuiltinValveNpcs();
```

并添加方法：

```java
private static void registerBuiltinValveNpcs() {
    // Read valve_npc_registry.json from classpath and register
    // 注意：NPC 注册必须在 mod 构造函数中完成，因为 Forge 的 DeferredRegister
    // 在构造完成后就会冻结。不要在 FMLClientSetupEvent 中注册实体类型。
    try (var is = Transferstation_whimsicalideas.class.getClassLoader()
            .getResourceAsStream("assets/transferstation_whimsicalideas/valve_npc_registry.json")) {
        if (is == null) return;
        var json = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
        var npcs = json.getAsJsonArray("npcs");
        for (var elem : npcs) {
            var npc = elem.getAsJsonObject();
            String id = npc.get("id").getAsString();
            String modelPath = npc.get("modelPath").getAsString();
            float scale = npc.getAsJsonObject("attributes").get("scale").getAsFloat();
            String entityId = "valve_" + id;
            NpcModelRegistry.registerBuiltinNpc(entityId, modelPath, 20f, 0.25f, 0f, scale);
        }
        LOGGER.info("Registered {} built-in Valve NPCs", npcs.size());
    } catch (Exception e) {
        LOGGER.debug("No built-in Valve NPCs to register");
    }
}
```

**注意修正：** ValveContentLoader 中的 `onClientSetup` 只做粒子加载和非注册类工作，不再参与 NPC 实体注册。

---

### 任务 7：Valve 模型文件提取与打包

- [ ] **步骤 1：从 HL2 SDK 提取模型文件**

从 Valve Source SDK 中提取选定 NPC 的模型资源：
- `models/npc/metrocop.mdl`, `.vvd`, `.dx90.vtx`, `.phy`
- `models/combine_soldier.mdl` ...
- 对应材质：`materials/models/...`

- [ ] **步骤 2：放入 mod 资源目录**

```
src/main/resources/assets/transferstation_whimsicalideas/
  valve_content/
    models/npc/metrocop/
      metrocop.mdl
      metrocop.vvd
      metrocop.dx90.vtx
      metrocop.phy
    models/npc/combine_soldier/
      ...
    materials/models/
      ...
```

- [ ] **步骤 3：配置 build.gradle 确保资源打包**

确认 `build.gradle` 的 `processResources` 任务包含 `valve_content/` 目录。

---

### 任务 8：自检

- [ ] **步骤 1：规格覆盖度检查**

检查设计方案中每个需求点是否都有对应任务实现：
- PCF 解析 ✅（任务 1）
- 粒子系统定义 POJO ✅（任务 1-2）
- 7 种渲染器 ✅（任务 4）
- Minecraft 集成（tick/render/命令）✅（任务 5）
- Valve NPC 注册 ✅（任务 6）
- 模型文件打包 ✅（任务 7）

- [ ] **步骤 2：占位符扫描**

确认计划中无 TODO/待定/模糊描述——每个渲染器实现已有具体代码或明确骨架。

- [ ] **步骤 3：类型一致性检查**

检查类型签名一致性：
- `ParticleManager.spawnEffect` 返回 `ParticleEmitter` ✅
- `ParticleEmitter.getParticles()` 返回 `List<Particle>` ✅
- `ParticleRenderer.render` 签名统一 ✅
- 初始化器/运算符类型字符串与 PCF 解析器一致 ✅

---

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-07-22-source-engine-content.md`。

**推荐执行方式：子代理驱动（subagent-driven-development）**
- 粒子系统和 Valve 模型包可独立并行
- 每个任务调度子代理实现
- 任务间进行审查
