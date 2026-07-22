# Task 4 Report: 粒子渲染器体系

## 状态：DONE

## 创建文件

在 `src/main/java/.../client/particle/renderer/` 下创建了 8 个文件：

| 文件 | 类型 | 说明 |
|------|------|------|
| `ParticleRenderer.java` | 接口 | 包含 `render(PoseStack, MultiBufferSource, ParticleEmitter, List<Particle>, float, int)` 方法 |
| `SpriteParticleRenderer.java` | 实现 | 完整 billboard 精灵渲染器，使用 DefaultVertexFormat.PARTICLE |
| `ModelParticleRenderer.java` | 骨架 | 带有 TODO 注释的骨架，预留 ModelLoadManager 集成 |
| `BeamParticleRenderer.java` | 实现 | 连接粒子对的线段渲染器，使用 DEBUG_LINES |
| `TrailParticleRenderer.java` | 实现 | 带状条带渲染器，连接粒子位置形成四边形条带，面向相机 |
| `DecalParticleRenderer.java` | 实现 | Y-up 面片渲染器，在 XZ 平面上渲染贴花 |
| `LightParticleRenderer.java` | 实现 | 亮色 billboard 精灵渲染器，颜色增强 1.5x |
| `RopeParticleRenderer.java` | 实现 | 分段绳索渲染器，带正弦下垂模拟 |

## 编译验证

- `gradlew compileJava` — **通过**（BUILD SUCCESSFUL）
- 仅含 unchecked/unsafe operations 警告（来自泛型使用）

## 修复的编译问题

1. `RenderSystem.depthWrite()` → `RenderSystem.depthMask()`（1.20.1 中方法名不同）
2. `camera.getUp()/getLeft()` → `camera.getUpVector()/getLeftVector()`（1.20.1 API 差异）
3. `getUpVector()/getLeftVector()` 直接返回 `Vector3f`，无需 `.toVector3f()` 转换

## 疑虑

- **ModelParticleRenderer** 为骨架实现，仅包含 TODO 注释，待后续集成完整的 mdl 渲染管线
- **BeamParticleRenderer** 和 **RopeParticleRenderer** 使用 `DEBUG_LINES` 模式（线宽固定），如需可调节宽度的线条须换用其他顶点格式
- **DecalParticleRenderer** 仅作简化的 Y-up 面片渲染，未实现真正的表面投影
- **LightParticleRenderer** 仅作亮色精灵渲染（MVP），未实现真正的动态光源集成
