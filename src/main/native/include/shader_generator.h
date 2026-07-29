#ifndef SHADER_GENERATOR_H
#define SHADER_GENERATOR_H

#include <string>
#include <vector>
#include <unordered_map>
#include <cstdint>

/**
 * ShaderGenerator — 从 VMT 材质属性生成 GLSL Shader。
 * 
 * 生成策略：
 * - 解析 MaterialConfig JSON（从 Java 端传入）
 * - 根据激活的 VMT 特性（bumpmap/phong/envmap/detail）组合 Shader 变体
 * - 生成对应的 GLSL 顶点着色器 + 片段着色器
 * - 编译为 OpenGL Shader Program，返回 program ID
 */
class ShaderGenerator {
public:
    struct ShaderSource {
        std::string vertexSource;
        std::string fragmentSource;
    };

    struct ShaderProgram {
        uint32_t programId = 0;
        std::string variantName;     // "base", "bump", "phong", "envmap", "detail", "full"
        std::vector<std::string> defines;
        
        // Uniform 位置缓存
        int uModelMatrix = -1;
        int uViewMatrix = -1;
        int uProjectionMatrix = -1;
        int uLightDir = -1;
        int uLightColor = -1;
        int uAmbientColor = -1;
        int uCameraPos = -1;
        
        // 纹理 uniform
        int uBaseTexture = -1;
        int uBumpTexture = -1;
        int uEnvTexture = -1;
        int uDetailTexture = -1;
        
        // 材质参数 uniform
        int uPhongBoost = -1;
        int uPhongExponent = -1;
        int uEnvMapTint = -1;
        int uDetailBlendFactor = -1;
    };

    /**
     * 生成 GLSL Shader 源码。
     * @param properties VMT 材质属性键值对（从 MaterialConfig.parameters 序列化而来）
     * @param variant Shader 变体名：base / bump / phong / envmap / detail / full
     * @return ShaderSource 包含顶点和片段着色器源码
     */
    static ShaderSource generateSource(
        const std::unordered_map<std::string, std::string>& properties,
        const std::string& variant);

    /**
     * 编译 Shader Program。
     * @param source 由 generateSource 生成的源码
     * @return 编译后的 ShaderProgram，programId=0 表示编译失败
     */
    static ShaderProgram compile(const ShaderSource& source);

    /**
     * 从属性集推断需要的 Shader 变体列表。
     * 例如：有 $bumpmap + $phong → {"base", "bump", "phong"}
     */
    static std::vector<std::string> determineVariants(
        const std::unordered_map<std::string, std::string>& properties);

    /**
     * 释放 Shader Program。
     */
    static void destroy(ShaderProgram& program);

private:
    static std::string generateVertexSource(const std::string& variant);
    static std::string generateFragmentSource(
        const std::unordered_map<std::string, std::string>& properties,
        const std::string& variant);
    static std::string getVersionDirective();
    static uint32_t compileShader(uint32_t type, const std::string& source);
};

#endif // SHADER_GENERATOR_H