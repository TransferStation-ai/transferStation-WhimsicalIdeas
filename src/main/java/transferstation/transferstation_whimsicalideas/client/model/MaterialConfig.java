package transferstation.transferstation_whimsicalideas.client.model;

import java.util.Map;

/**
 * 编译后的材质配置。
 * 由 MaterialCompiler 序列化为 JSON 传给 C++ 侧进行 Shader 编译。
 */
public class MaterialConfig {
    public String shaderType;                // 着色器类型名
    public Map<String, String> parameters;   // VMT 参数表
    public int renderPassCount;              // Pass 数量
    public RenderPassConfig[] passes;        // 每个 Pass 的配置
    public boolean translucent, alphaTest, additive, noCull;

    public static class RenderPassConfig {
        public String shaderVariant;   // "base", "bump", "phong", "envmap", "detail"
        public String textureName;     // 此 Pass 绑定的纹理引用名
        public int blendMode;          // 0=NORMAL, 1=ADDITIVE, 2=ALPHA_TEST
        public int cullMode;           // 0=BACK, 1=FRONT, 2=NONE
        public boolean depthWrite;
        public String[] defines;       // 此 Pass 的 GLSL define
    }

    /** 根据 VMT 参数推断 Pass 数量 */
    public static int computePassCount(Map<String, String> params) {
        int passes = 1;
        if (parseBool(params.get("$bumpmap"))) passes++;
        if (parseBool(params.get("$phong"))) passes++;
        if (params.containsKey("$envmap")) passes++;
        if (params.containsKey("$detail")) passes++;
        return passes;
    }

    private static boolean parseBool(String val) {
        if (val == null) return false;
        val = val.trim().toLowerCase();
        return val.equals("1") || val.equals("true") || val.equals("yes") || val.equals("on");
    }

    /** 从 VmtMaterial 构建 MaterialConfig */
    public static MaterialConfig fromVmt(VmtParser.VmtMaterial vmt) {
        MaterialConfig cfg = new MaterialConfig();
        cfg.shaderType = vmt.shader;
        cfg.parameters = vmt.parameters;
        cfg.translucent = vmt.isTransparent();
        cfg.alphaTest = vmt.isAlphaTest();
        cfg.additive = parseBool(vmt.parameters.get("$additive"));
        cfg.noCull = vmt.isNoCull();
        cfg.renderPassCount = computePassCount(vmt.parameters);
        return cfg;
    }
}
