# AI Logic Enhancement Design for TransferStation WhimsicalIdeas

## Overview

Enhance NPC AI logic with a zero-hardcode, extensible architecture inspired by
[AI-Player](https://github.com/shasankp000/ai-player) and
[TouhouLittleMaid](https://github.com/TartaricAcid/TouhouLittleMaid).

## Core Principles

1. **No hardcoded behaviors** — all behaviors registered via `TaskRegistry`
2. **No hardcoded weights** — emotion × personality → behavior weight mapping driven by JSON
3. **No hardcoded provider** — LLM providers registered via `AiProviderRegistry`
4. **Persona-driven** — each NPC carries an external-JSON-defined character profile

## Architecture

```
LLM Chat → Meta-Decision Layer → TaskQueue → TaskRegistry → INpcTask[]
                ↑                                    ↓
           WorldPerception ←─────────────────── TaskStatus
                ↑
           Mood + Persona Modifiers
```

### Components

#### 1. Meta-Decision Layer
- LLM parses chat into structured JSON
- `action.type == "task_chain"` → decompose into `Task[]` and enqueue
- `action.type == 单行为` → create single task, enqueue
- `action.type == 查询/纯聊天` → chat reply only, no task

#### 2. TaskQueue
- Priority-sorted queue per NPC
- Dependency graph support (task B depends on task A)
- Only one `activeTask` at a time
- Pending tasks sorted by: `baseWeight × personaBonus × moodModifier`
- Task states: PENDING → RUNNING → SUCCESS/FAILED/CANCELLED

#### 3. TaskRegistry + INpcTask
```java
TaskRegistry.register("mine", MineTask::new);
TaskRegistry.register("build", BuildTask::new);
// ...
```
Each task implements `INpcTask` with: `canStart()`, `tick()`, `canContinue()`, `stop()`.

Supported task types: chop_wood, mine, fight, collect_items, farm, fish, build, follow, patrol, rest, socialize, goto.

#### 4. Persona System
External JSON files in `config/transferstation_whimsicalideas/personas/`:
```json
{
  "id": "miner_steve",
  "name": "老矿工史蒂夫",
  "background": "你是一位在矿洞里生活了30年的老矿工...",
  "personality": "stoic",
  "traits": { "bravery": 0.9, "sociability": 0.3, "diligence": 0.8, "emotionality": 0.2, "aggression": 0.4 },
  "moodBias": "neutral",
  "speechPattern": "terse",
  "skillBonus": { "mining": 1.5, "combat": 0.6 },
  "behaviorPreferences": { "mine": 1.5, "chop_wood": 0.3, "socialize": 0.1 },
  "modelId": "steve"
}
```

Built-in presets: default, warrior, scholar, jester, guardian, miner, farmer, builder.

#### 5. Weight Profiles (JSON-driven)
`config/npc_behaviors/weight_profiles.json`:
- `moodModifiers`: emotion → per-behavior multiplier
- `personalityBaselines`: personality → per-behavior baseline

Runtime weight: `baseWeight × personaBaseline[type] × moodModifier[mood]`

#### 6. WorldPerception
- `ResourceScanner`: find nearest blocks/ores/trees
- `DangerDetector`: lava, cliffs, hostile entities
- `EntityTracker`: nearby players, mobs, items

#### 7. AiProviderRegistry
- Plugin-based LLM provider registration
- Providers: OpenAI, DeepSeek, Ollama, Custom
- Replace current NpcChatHandler switch-case

#### 8. Autonomous Behavior
When task queue is empty, NPC acts autonomously based on:
- Current mood + persona → select behavior from weight profile
- Danger detection → auto-fight or flee
- Resource detection → auto-mine/chop
- Schedule (day/night) → rest at night, work at day

### Persistence
- Persona ID, TaskQueue state, Perception cache saved in NBT via NpcData
- Restored on entity load: resume pending tasks

### Configuration UI
- `/npc persona set/get/list/reload` commands
- Persona selection in AiConfigScreen (new tab)
- Behavior weight visualization in `/npc info`

## Package Structure

```
npc/
├── ai/
│   ├── AINpcAgent.java
│   ├── INpcTask.java
│   ├── TaskStatus.java
│   ├── TaskQueue.java
│   ├── TaskRegistry.java
│   └── task/ (individual task implementations)
├── persona/
│   ├── NpcPersona.java
│   ├── PersonaRegistry.java
│   └── default_personas.json
├── perception/
│   ├── WorldPerception.java
│   ├── DangerDetector.java
│   └── ResourceScanner.java
├── provider/
│   ├── AiProviderRegistry.java
│   ├── IAiProvider.java
│   └── impl/
└── config/
    ├── BehaviorConfig.java
    └── weight_profiles.json
```

## Files to Modify

| File | Change |
|------|--------|
| `AINpcAgent.java` | Replace hardcoded goals with TaskQueue-driven system |
| `NpcChatHandler.java` | Replace switch-case provider with AiProviderRegistry; extend action parsing for task_chain |
| `NpcData.java` | Add personaId, task queue NBT serialization |
| `NpcEntity.java` | Route aiStep through new AINpcAgent.tick() |
| `AiConfigScreen.java` | Add persona/behavior config UI |
| `NpcCommand.java` | Add persona subcommands |

## Self-Check

- [x] No placeholder/TODO left
- [x] Architecture and components are consistent
- [x] Scope focused on AI enhancement, not tangential refactoring
- [x] Requirements unambiguous
- [x] Zero hardcode principle applied throughout
- [x] External JSON configuration for all variable data
- [x] Registry pattern for behaviors and providers
- [x] AI-Player meta-decision layer adapted
- [x] TouhouLittleMaid persona + brain patterns adapted
