package transferstation.transferstation_whimsicalideas.client.particle;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PcfParser {
    private static final Logger LOGGER = LogUtils.getLogger();
    // NOTE: readInt32 is little-endian, so ASCII "PCFF" (50 43 46 46) reads back as 0x46464350
    private static final int PCF_SIGNATURE = 0x46464350; // "PCFF"
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

    private static KvNode parseKeyValues(PcfBuffer buf) {
        var root = new KvNode("root", KvType.NULL, null);
        parseChildren(buf, root);
        return root;
    }

    private static void parseChildren(PcfBuffer buf, KvNode parent) {
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

    private static void extractSystems(KvNode node, PcfParticleSystemDef target) {
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
                case "m_flEmissionRate" -> def.emissionRate = prop.floatValue();
                case "m_bContinuous" -> def.continuous = prop.intValue() != 0;
                case "_renderer" -> def.renderer = parseRenderer(prop);
                case "_initializers" -> parseInitializers(prop, def.initializers);
                case "_operators" -> parseOperators(prop, def.operators);
                case "_children" -> parseChildrenList(prop, def.children);
                case "_forces" -> parseForces(prop, def.forces);
                default -> {
                    // `_ramp` 结尾的曲线参数（如 m_flRadius_ramp）→ 曲线采样器
                    if (prop.name != null && prop.name.endsWith("_ramp")) {
                        def.ramps.put(prop.name, parseRamp(prop));
                    }
                }
            }
        }
        return def;
    }

    private static PcfParticleSystemDef.RendererDef parseRenderer(KvNode node) {
        var renderer = new PcfParticleSystemDef.RendererDef();
        for (var prop : node.children) {
            switch (prop.name) {
                case "m_nRendererType" -> renderer.type = switch (prop.intValue()) {
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

    private static void parseChildrenList(KvNode node, List<PcfParticleSystemDef.ChildDef> list) {
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
                            if (p.type == KvType.ARRAY && p.children != null) {
                                int n = Math.min(3, p.children.size());
                                for (int i = 0; i < n; i++) {
                                    if (p.children.get(i).value instanceof Number num) {
                                        force.direction[i] = num.floatValue();
                                    }
                                }
                            }
                        }
                        case "m_bControlPointBased" -> force.controlPointBased = p.intValue() != 0;
                        case "m_nControlPoint" -> force.controlPoint = p.intValue();
                    }
                }
                list.add(force);
            }
        }
    }

    /**
     * 解析 `_ramp` 结尾字段的曲线 knots。
     * 值为 ARRAY 时：遍历子节点，优先寻找成对 (time, value)；
     *   子节点为 OBJECT 且含 "time"/"value" 键则读之，否则按扁平成对读取。
     * 无法解析时退化为单点常量（[0, 当前值]）。
     */
    private static ParticleRamp parseRamp(KvNode node) {
        float fallbackValue = 1f;
        if (node.value instanceof Number n) {
            fallbackValue = n.floatValue();
        }
        List<Float> knots = new ArrayList<>();
        if (node.type == KvType.ARRAY && node.children != null) {
            boolean allObjects = !node.children.isEmpty();
            for (var c : node.children) {
                if (c.type != KvType.OBJECT) { allObjects = false; break; }
            }
            if (allObjects) {
                // 子节点为 OBJECT：读 "time"/"value" 键
                for (var c : node.children) {
                    Float t = null, v = null;
                    for (var p : c.children) {
                        if ("time".equals(p.name) && p.value instanceof Number num) t = num.floatValue();
                        if ("value".equals(p.name) && p.value instanceof Number num) v = num.floatValue();
                    }
                    if (t != null && v != null) { knots.add(t); knots.add(v); }
                }
            } else {
                // 扁平成对：{t0, v0, t1, v1, ...}
                for (var c : node.children) {
                    if (c.value instanceof Number num) knots.add(num.floatValue());
                }
            }
        }
        if (knots.size() < 2) {
            // 无法解析 → 单点常量
            knots.clear();
            knots.add(0f);
            knots.add(fallbackValue);
        } else if (knots.size() % 2 != 0) {
            knots.remove(knots.size() - 1); // 丢弃孤值
        }
        float[] arr = new float[knots.size()];
        for (int i = 0; i < knots.size(); i++) arr[i] = knots.get(i);
        return new ParticleRamp(arr);
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
