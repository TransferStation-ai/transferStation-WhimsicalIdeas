# 任务 6：Valve 模型包 — 注册框架

## 状态：DONE

## 变更摘要

### 创建的文件

1. **`src/main/resources/assets/transferstation_whimsicalideas/valve_npc_registry.json`**
   - 定义 10 个内置 Valve NPC（metrocop, combine_soldier, zombie_classic, headcrab, vortigaunt, antlion, fast_zombie, manhack, rollermine, stalker）
   - 每个 NPC 包含 id、displayName、modelPath、attributes（health/speed/armor/scale）、eggColor

2. **`src/main/java/.../client/model/ValveContentLoader.java`**
   - 只处理粒子加载（`loadValveParticles()`），不做 NPC 实体注册
   - 使用 `ResourceManager.listResources()` 扫描 `valve_content/particles/` 目录下所有 `.pcf` 文件
   - 在 `FMLClientSetupEvent` 中触发

### 修改的文件

3. **`NpcModelRegistry.java`**
   - 新增 `registerBuiltinNpc(String entityId, String modelPath, float health, float speed, float armor, float scale)` 方法
   - 使用 `DeferredRegister` 注册实体类型，应用 scale 到实体碰撞箱大小
   - 由 `registerBuiltinValveNpcs()` 在 mod 构造函数中调用

4. **`Transferstation_whimsicalideas.java`**
   - 构造函数中 `NpcModelRegistry.scanAndRegister(configDir)` 后添加 `registerBuiltinValveNpcs()` 调用
   - 新增 `registerBuiltinValveNpcs()` 方法，从 classpath 读取 `valve_npc_registry.json` 并遍历注册每个 NPC
   - 新增必要的 import（`JsonParser`, `InputStreamReader`, `StandardCharsets`）

### 已遵循的重要修正

- **NPC 实体注册在 mod 构造函数中完成**，不在 `FMLClientSetupEvent` 中注册实体类型
- `ValveContentLoader.onClientSetup()` 只做粒子加载

### 注意事项

- 当前 `registerBuiltinNpc()` 只注册实体类型，不创建刷怪蛋（spawn egg）。JSON 中定义的 `eggColor` 供后续扩展使用。
- 参数 `health`/`speed`/`armor` 被保留以备将来用于属性配置，当前仅 `scale` 生效。
- 现有的 `Transferstation_whimsicalideas.ClientModEvents.loadBuiltInParticles()`（加载 `builtin.pcf`）保持不动，与 ValveContentLoader 的批量加载互补。

## 编译验证

```log
BUILD SUCCESSFUL in 13s
4 actionable tasks: 2 executed, 2 up-to-date
```

唯一的警告是预存在的 `ResourceLocation(String,String)` 弃用警告（第 209 行，`loadBuiltInParticles` 中的旧代码）。

## 提交

```
7f9363e feat(valve): 添加 Valve NPC 注册框架 (task 6)
```
