# 任务 3 报告：ParticleManager 全局管理器

## 状态：DONE

## 创建的文件

- `src/main/java/transferstation/transferstation_whimsicalideas/client/particle/ParticleManager.java`

## 实现细节

### ParticleManager 类（单例）

实现了粒子系统的中央调度器，功能包括：

1. **注册与加载**：
   - `registerSystem(SystemDefinition)` — 注册粒子系统定义
   - `loadPcfFile(Path)` — 从文件系统加载 .pcf 文件并注册其中所有系统
   - `loadPcfFromBytes(String, byte[])` — 从字节数组加载（如 jar 资源）

2. **粒子效果生成**：
   - `spawnEffect(String, Level, double, double, double)` — 在指定位置生成粒子效果
   - `spawnEffect(String, Level, double, double, double, Consumer<Particle>)` — 带 onSpawn 回调的重载
   - 首次生成时自动 burst 初始粒子（连续模式 burst min(emissionRate, 20)，burst 模式 burst maxParticles）

3. **每 tick 更新**：
   - `tick(float)` — 更新所有发射器，dt 上限 0.05s 防止物理爆炸
   - 强制粒子数量上限（全局 10,000，每个效果 2,000）
   - 自动清理已结束的发射器（非活跃且粒子数为 0）

4. **渲染与清理**：
   - `render(PoseStack, MultiBufferSource, float)` — 渲染框架（具体渲染器分派留空供后续任务实现）
   - `getTotalParticleCount()` — 获取全局粒子总数
   - `clearAll()` — 清除所有发射器
   - `onWorldUnload()` — 世界卸载时清理

### 与现有 API 的兼容性

- 使用 `PcfParticleSystemDef.SystemDefinition` 作为注册类型
- 使用 `ParticleEmitter(def, level, onSpawn)` 构造函数
- 使用 `emitter.origin`（Vector3f）、`emitter.active`（boolean）
- 使用 `PcfParser.parse(byte[])` 解析 PCF 数据
- 使用 `Minecraft Forge 1.20.1` 的 `PoseStack`、`MultiBufferSource`、`Level`

## 编译验证

- BUILD SUCCESSFUL (compileJava)，无 Java 编译错误

## 提交

- 提交：仅包含 `ParticleManager.java`
