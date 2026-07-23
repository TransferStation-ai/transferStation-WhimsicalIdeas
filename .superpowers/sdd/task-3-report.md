# 任务 3 报告：重构 NpcChatHandler — 结构化 JSON + 多 Provider + AI 动作

## 状态：DONE

## 修改的文件

- `src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandler.java`
- `src/main/java/transferstation/transferstation_whimsicalideas/npc/ai/AINpcAgent.java`

## 实现细节

### NpcChatHandler.java 重构

1. **AiProvider 枚举** — 支持 CUSTOM、OPENAI、DEEPSEEK、OLLAMA 四种 provider，每个有 id 和 defaultModel
2. **Provider/Model 配置字段** — `static AiProvider provider` 和 `static String modelName`，带 getter/setter
3. **sendMessage 重写** — 使用 `buildRequest` + `parseResponse` + `processStructuredResponse` 三阶段流程
4. **buildRequest** — 根据 provider 构建不同格式的 HTTP 请求体（OpenAI/Dify 格式、Ollama 格式、Custom 格式）
5. **parseResponse** — 根据 provider 从不同 JSON 路径提取回复文本
6. **processStructuredResponse** — 解析可选的结构化 JSON（emotion/gesture/action），更新 NPC 状态，发送 S2C 包
7. **executeAiAction** — 执行 AI 动作命令（chop_wood/follow/stop/guard/emote）
8. **extractPlainReply** — 从结构化 JSON 中提取纯文本回复
9. **buildSystemPrompt 更新** — 末尾添加结构化 JSON 输出格式指令
10. **删除 processActions** — 旧的关键词匹配方法被新的结构化 JSON 处理替代

### AINpcAgent.java 增强

1. **orderFollowPlayer** 签名从 `Mob` 改为 `LivingEntity`，允许传入 `Player`
2. **orderGuard(BlockPos)** — 新增方法，NPC 守卫指定位置
3. **GuardGoal** 内部类 — 守卫 AI 目标，超出半径后自动返回守卫点

## 编译结果

- `gradlew compileJava` — 仅剩 **3 个预期错误**（均将在任务 5 修复）：
  - `NpcChatHandler.java:327`: `npc.handleGesture(emotion, gesture)` 方法不存在
  - `ChatC2SPacket.java:35`: `npc.handleChatMessage(sender, packet.message)` 方法不存在
  - `ChatS2CPacket.java:58`: `npc.handleGesture(packet.emotion, packet.gesture)` 方法不存在
- 另有 2 个 pre-existing deprecation 警告（`ResourceLocation` 构造函数）

## 提交

- `6f2e1a9` feat(chat): refactor NpcChatHandler with structured JSON, multi-provider, and AI actions
- 2 files changed, 286 insertions(+), 53 deletions(-)
