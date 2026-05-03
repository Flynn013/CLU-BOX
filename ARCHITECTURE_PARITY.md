# Goose Desktop → CLU-BOX (Android Native) Parity Matrix

**Sources of truth:**
- `block/goose @ main` — `crates/goose/src/**` (Rust agent runtime) and `ui/desktop/src/**` (Electron + React desktop client). Used for *semantic parity* (typed messages, reasoning traces, recipes, sub-recipes, permission semantics, todo behaviour, scheduler shape).
- `MaxFlynn13/goose-android` — prior-art Android packaging that includes a 5,200-line pure-Kotlin agent stack (`engine/StreamingAgentLoop`, `engine/providers/{Anthropic,Google,OpenAI}Provider`, `engine/mcp/{Stdio,Http}Transport`, `engine/tools/ToolRouter`, `data/models/ChatModels`, Compose `MessageBubble`/`ToolCallCard`). Used for *Android implementation patterns* and as a starting point for the streaming provider abstraction. Both projects are Apache-2.0 — we may liberally borrow shape/structure with attribution.

**Target:** CLU-BOX is **Goose-inspired, not Goose-cloned**. The goal is an Android-native agent that *behaves* like Goose at the conversation layer (typed messages, streaming reasoning, sessions, recipes, tool permissions, todo planner) but keeps the **entire existing CLU-BOX cognitive substrate** as the implementation engine: SplinterAPI, Python skills via Chaquopy, BRAIN/SCDL/CLN/LNK/FILE_BOX, BusyBoxBridge, JGitManager, MstrCtrlScreen, the protected LiteRT runtime, model downloaders, and existing OS-module screens are all preserved and **repurposed** as the Goose-equivalent surfaces.

This document is the *executable* plan — every row maps to a concrete commit on `feature/goose-parity` and is updated as work lands.

---

## 1. Repurposing map — Goose surface → existing CLU-BOX module

Principle: every Goose-equivalent feature **must** be backed by a CLU-BOX module that already exists, unless one truly does not. New code is the *adapter layer* that gives existing primitives a Goose-shaped API; it does not replace them.

| # | Goose subsystem (`crates/goose/src/...`) | Responsibility | CLU-BOX module already in repo | Adapter to add | Status |
|---|---|---|---|---|---|
| 1 | `conversation/message.rs` | Typed `MessageContent` enum: Text, Image, ToolRequest, ToolResponse, ToolConfirmationRequest, ActionRequired, FrontendToolRequest, **Thinking**, RedactedThinking, SystemNotification | `customtasks/agentchat/AgentChatScreen.kt` already maintains an in-memory message list of varying shapes | New `data/conversation/Message.kt` sealed-class layered on top — the chat screen's existing list becomes a `List<Message>` | **TODO** |
| 2 | `session/session_manager.rs` | `Session`, `SessionUpdateBuilder`, `SessionStorage`, multi-session, working dir, token accounting, recipe binding, `thread_id` | Existing chat history lives only in ViewModel state; the app already has a Room database (`AppDatabase`) and DAOs for BRAIN_BOX, SCDL_BOX | New `data/session/{SessionEntity, SessionDao, SessionStore, SessionManager}.kt`; reuse the same Room database via a new migration | **TODO** |
| 3 | `agents/agent.rs` + `execution/manager.rs` | The autonomous reply loop, max-turn enforcement, tool-call routing, retry, sub-agent spawn | Existing `ContinuousAgentDriver.kt` + `AgentGovernor.kt` already implement the loop and state machine; `AgentLoopManager.kt` owns retry/error-budget; `DelegationEngine.kt` does sub-agent spawn | Refactor `ContinuousAgentDriver` into **`AgentEngineV2`** that consumes `Provider.stream(): Flow<ProviderEvent>` instead of the current `runTurn` adapter — governor/loopmanager/delegation kept as-is | **PARTIAL** |
| 4 | `agents/extension.rs` + `extension_manager.rs` | `ExtensionConfig` (Stdio/Sse/Builtin/Platform/StreamableHttp), Envs, install/enable/disable, `available_tools` allow-list | **`LnkBoxBridge.kt` is already the extension/transport layer** (Ktor SSE for remote, Chaquopy stdio for local). `SkillRegistry` is the per-tool registry. | New `data/extensions/ExtensionConfig.kt` + `ExtensionManager.kt` that *describe* extensions in the Goose schema and delegate transport to LnkBoxBridge; built-ins enumerate `SkillRegistry` entries | **PARTIAL** (transports done) |
| 5 | `agents/platform_extensions/todo.rs` | Built-in `todo` MCP server; per-session todo state stored in `extension_data` | **BRAIN_BOX EPISODIC memories** can store todo deltas; **SCDL_BOX** can recur reminders. The persistent column wants a `Session.todoMarkdown` field once SessionStore exists. | New `data/extensions/builtin/TodoExtension.kt` exposing `todo_write` / `todo_read` skills; persists to `Session.todoMarkdown` and snapshots into BRAIN_BOX EPISODIC for history | **TODO** |
| 6 | `recipe/mod.rs` + `recipe/local_recipes.rs` | `Recipe` YAML/JSON schema (title, description, instructions, prompt, extensions, settings, parameters, response, sub_recipes), `Author`, `Settings`, `SubRecipe` | **CLN_BOX personas** already store `name + systemPrompt + metadata` — a Recipe is a superset of a persona (adds parameters, sub-recipes, extension allow-list, response schema). | New `data/recipe/{Recipe, RecipeStore, RecipeRenderer, RecipeDeeplink}.kt` that *extend* CLN_BOX rows with the extra fields, parse YAML/JSON via `com.charleskorn.kaml`, and run sub-recipes through the existing `DelegationEngine` | **TODO** |
| 7 | `recipe_deeplink.rs` | `goose://recipe?...` deep-link encoder/decoder for sharing recipes | New `data/recipe/RecipeDeeplink.kt` + Android intent filter on `clu://recipe` | **TODO** |
| 8 | `permission/permission_confirmation.rs` + `permission_store.rs` | `Permission` (AlwaysAllow/AllowOnce/Cancel/DenyOnce/AlwaysDeny), `PrincipalType` (Extension/Tool), per-principal persistent decisions | The `SkillRegistry.dispatch(name, args)` call site is the natural permission gate. No persistent store yet. | New `data/permission/{Permission, PermissionStore, PermissionInspector, ActionRequiredQueue}.kt`; `SkillRegistry.dispatch` consults the inspector before invoking | **TODO** |
| 9 | `scheduler.rs` + `scheduler_trait.rs` | Cron-based recipe scheduling, `ScheduledJob` records, persistent storage | **SCDL_BOX is already the scheduler** — `SplinterAPI.scdlBoxScheduleOnce/Periodic/Cancel` over WorkManager. | New `data/scheduler/RecipeScheduler.kt` that translates Goose cron expressions into SCDL_BOX periodic jobs whose payload is a recipe id | **PARTIAL** |
| 10 | `providers/{anthropic,gemini,openai,...}` + `providers/base.rs` | Provider trait, `ModelConfig`, streaming completions w/ tool calls + reasoning | The chat screen already calls Gemini and LiteRT through internal helpers; the protected `runtime/` package owns LiteRT. **Reference: `goose-android/engine/providers/{Anthropic,Google,OpenAI}Provider.kt`** show the exact streaming shape we need. | New `data/providers/{Provider, ProviderEvent, ProviderRegistry, GeminiProvider, LiteRTProvider}.kt` patterned after the reference repo's `LlmProvider`; `LiteRTProvider` *wraps* the protected runtime helper, never modifies it | **PARTIAL** |
| 11 | `context_mgmt/mod.rs` | Token accounting, automatic truncation policies (summarize, drop oldest, etc.) | New `data/context/ContextManager.kt` with summarise + drop-oldest strategies, gated by `Settings.max_turns` and provider context window | **TODO** |
| 12 | `prompt_template.rs` + `prompts/plan.md` | Jinja-style prompt templating, plan-mode preamble | New `data/prompts/PromptTemplate.kt` (mustache) + assets-bundled plan/reply preambles | **TODO** |
| 13 | `agents/subagent_handler.rs` + `subagent_execution_tool/` | Spawn sub-agent on a recipe, fan-out/parallel sub-tasks, return synthesis | `DelegationEngine.kt` already exists, currently keyed on CLN_BOX persona name. | Add `delegateRecipe(recipeId, params)` overload that loads a Recipe (which still resolves to a CLN_BOX persona under the hood) | **PARTIAL** |
| 14 | `agents/tool_confirmation_router.rs` + `action_required_manager.rs` | Pause loop on `ToolConfirmationRequest`/`ActionRequired`, await UI decision | New `data/permission/ActionRequiredQueue.kt` + Compose modal sheet | **TODO** |
| 15 | `slash_commands.rs` | `/recipe`, `/clear`, `/extension`, etc. typed slash-command parser | New `data/chat/SlashCommands.kt` | **TODO** |
| 16 | `dictation/` | Speech-to-text input | Out-of-scope v1 (Android STT is trivial to slot in later) | **DEFERRED** |
| 17 | `oauth/` | OAuth flows for cloud providers | `net.openid:appauth` is already in the project. | New `data/auth/OAuthManager.kt` that uses appauth to obtain Gemini/Anthropic/OpenAI tokens and feeds them to the new `Provider` instances | **TODO** |

## 1a. Reference: `MaxFlynn13/goose-android` Kotlin patterns we can lift directly

The reference Android wrapper around Goose includes a clean pure-Kotlin agent stack. These files map straight to our parity layer (Apache-2.0 — attribute the source in headers):

| Reference file | LOC | What we lift |
|---|---|---|
| `engine/providers/LlmProvider.kt` | 81 | The provider interface shape (`stream(messages, tools): Flow<ProviderEvent>`) |
| `engine/providers/GoogleProvider.kt` | 397 | Gemini streaming SSE parser + tool-call decoding |
| `engine/providers/AnthropicProvider.kt` | 371 | Anthropic streaming JSON-delta parser (only if we add Anthropic later) |
| `engine/providers/OpenAIProvider.kt` | 306 | OpenAI streaming chat-completions parser |
| `engine/StreamingAgentLoop.kt` | 298 | Streaming loop driver semantics; we adapt it onto our existing `ContinuousAgentDriver` rather than replacing |
| `engine/mcp/StdioTransport.kt` | 163 | Stdio MCP transport — our `LnkBoxBridge.connectStdio` already does this via Chaquopy; cross-check correctness |
| `engine/mcp/HttpTransport.kt` | 264 | Streamable HTTP MCP transport; complements our existing SSE transport |
| `engine/tools/ToolDefinitions.kt` + `ToolRouter.kt` | 475+88 | Tool spec data classes & dispatch; we layer onto existing `SkillRegistry` |
| `data/models/ChatModels.kt` | 158 | Typed `Message`/`MessageContent`/`ToolCall`/`ToolResult` shape — directly informs our new `data/conversation/Message.kt` |
| `ui/chat/MessageBubble.kt` | 863 | Compose chat bubble pattern — we use as a structural reference, restyle for CLU-BOX neon-green theme |
| `ui/chat/ToolCallCard.kt` | 367 | Tool-call card patterns; complements our existing `ToolExecutionBox` |

**Important:** lifting means *learning structure*, not literal copy/paste — our headers, naming (`SkillRegistry`/Splinter not `ToolRouter`), and theme stay distinct. CLU-BOX remains its own product.

---

## 2. UI surface map (`ui/desktop/src/components/...`)

| Upstream React component | CLU-BOX Compose target | Status |
|---|---|---|
| `BaseChat.tsx`, `Hub.tsx` | `customtasks/agentchat/AgentChatScreen.kt` (existing — refactor to render typed `MessageContent`) | **PARTIAL** |
| `GooseMessage.tsx`, `MarkdownContent.tsx` | New `ui/chat/AssistantMessage.kt` (Markdown via `org.commonmark` → `androidx.compose.material3.Text` rich span) | **TODO** |
| `ThinkingContent.tsx` | New `ui/chat/ReasoningTrace.kt` — collapsible "Thinking…" fold with neon-green accent | **TODO** |
| `ToolCallArguments.tsx`, `ToolCallStatusIndicator.tsx`, `ToolApprovalButtons.tsx`, `ToolCallConfirmation.tsx` | Existing `ui/ToolExecutionBox.kt` → split into `ToolCallCard.kt` + `ToolApprovalSheet.kt` | **PARTIAL** |
| `RecipeHeader.tsx`, `ParameterInputModal.tsx` | New `ui/recipe/RecipeHeader.kt` + `RecipeParameterSheet.kt` | **TODO** |
| `ExtensionInstallModal.tsx`, `McpApps/` | New `ui/extensions/ExtensionDrawer.kt` + `ExtensionInstallSheet.kt` | **TODO** |
| `GooseSidebar/` | New `ui/nav/GooseSidebar.kt` — sessions list, recipes, extensions, schedule | **TODO** |
| `LoadingGoose.tsx`, `FlyingBird.tsx`, `AnimatedIcons.tsx` | Existing `ToolExecutionBox` pulse + new `ui/loading/StreamingDots.kt` | **PARTIAL** |
| `MessageQueue.tsx`, `ProgressiveMessageList.tsx` | Existing `LazyColumn` in `AgentChatScreen` (already lazy) | **DONE** |
| `LauncherView.tsx`, `PopularChatTopics.tsx` | New `ui/launcher/LauncherScreen.kt` showing recipes + recent sessions | **TODO** |
| `SessionIndicators.tsx` | Token-counter badge in chat top bar | **TODO** |

---

## 3. Native Kotlin module layout

```
data/
  conversation/        Message, MessageContent, Role, ToolCall, ReasoningTrace
  session/             SessionEntity, SessionDao, SessionStore, SessionManager, ThreadManager
  recipe/              Recipe, SubRecipe, RecipeStore, RecipeDeeplink, RecipeRenderer
  extensions/          ExtensionConfig, ExtensionManager, ExtensionInstaller
    builtin/           TodoExtension, MemoryExtension, ComputerControllerExtension (Android-flavoured)
  permission/          Permission, PermissionStore, PermissionInspector, ActionRequiredQueue
  scheduler/           RecipeScheduler (wraps WorkManager + cron-utils)
  providers/           Provider, ProviderRegistry, GeminiProvider, LiteRTProvider, ProviderEvent
  context/             ContextManager, TokenCounter, TruncationStrategy
  prompts/             PromptTemplate, PromptLibrary
  chat/                SlashCommands
  auth/                OAuthManager (uses existing net.openid:appauth)

customtasks/agentchat/
  AgentEngineV2.kt     replaces ContinuousAgentDriver (event-stream based)
  ToolPolicyEngine.kt  bridges PermissionInspector with the loop
  PlannerAgent.kt      wraps todo extension into a planning helper

ui/
  chat/                AssistantMessage, ReasoningTrace, ToolCallCard, ToolApprovalSheet, AttachmentPreview
  recipe/              RecipeHeader, RecipeParameterSheet, RecipeLibraryScreen
  extensions/          ExtensionDrawer, ExtensionInstallSheet, ExtensionListScreen
  permission/          PermissionPromptSheet, PermissionAuditScreen
  scheduler/           SchedulerScreen
  nav/                 GooseSidebar, GooseNavGraph
  launcher/            LauncherScreen
```

---

## 4. New Gradle dependencies

| Dependency | Purpose |
|---|---|
| `com.charleskorn.kaml:kaml:0.61.0` | Recipe YAML parsing (Kotlinx-serialization-compatible) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3` | Recipe / extension JSON, MCP envelopes |
| `org.commonmark:commonmark:0.22.0` | Markdown rendering for AssistantMessage |
| `it.burning:cron-parser:1.0.7` *(or Quartz `cron-utils:9.2.1`)* | Cron→next-fire-time translation for RecipeScheduler |
| `androidx.work:work-runtime-ktx:2.9.1` | (already present) |
| `io.ktor:ktor-client-{core,cio,sse}:2.3.12` | (already present) |
| `androidx.room:room-{runtime,ktx,compiler}:2.6.1` | (already present) |
| `androidx.datastore:datastore-preferences:1.1.1` | Persistent provider/extension config |

---

## 5. Staged commit plan (`feature/goose-parity` branch)

Each **stage** is one self-contained commit; review/build between stages.

| Stage | Commit subject | Adds | Touches existing | LOC est. |
|---|---|---|---|---|
| **0** | `docs(parity): repurposing-first parity matrix + reference notes` | `ARCHITECTURE_PARITY.md` | — | ~250 |
| **1** | `feat(conversation): typed Message/MessageContent sealed model` | `data/conversation/Message.kt`, `data/conversation/Role.kt` | (none yet) | ~300 |
| **2** | `feat(session): SessionEntity + SessionDao + SessionManager` | `data/session/{SessionEntity,SessionDao,SessionStore,SessionManager}.kt`, Room migration | `AppDatabase.kt` adds entity+dao | ~500 |
| **3** | `feat(provider): streaming Provider abstraction` | `data/providers/{Provider,ProviderEvent,ProviderRegistry,GeminiProvider,LiteRTProvider}.kt` | none (wraps protected runtime) | ~900 |
| **4** | `feat(perm): Permission + Inspector + ActionRequiredQueue` | `data/permission/*.kt` (4 files) | `SkillRegistry.dispatch` consults inspector | ~450 |
| **5** | `feat(recipe): Recipe schema + Store + Renderer + Deeplink` | `data/recipe/*.kt` (4 files) | `DelegationEngine.delegateRecipe` overload, `AndroidManifest.xml` intent filter | ~700 |
| **6** | `feat(planner): TodoExtension + auto-todo + recurring via SCDL_BOX` | `data/extensions/builtin/TodoExtension.kt`, planner skill | `SessionEntity.todoMarkdown` column | ~350 |
| **7** | `feat(agent): AgentEngineV2 — ProviderEvent-driven loop with perms+session+todo` | `customtasks/agentchat/AgentEngineV2.kt`, deprecate `ContinuousAgentDriver` | `AgentChatScreen` switches to v2 | ~600 |
| **8** | `feat(ui): AssistantMessage + ReasoningTrace + ToolCallCard refresh + ToolApprovalSheet` | `ui/chat/{AssistantMessage,ReasoningTrace,ToolCallCard,ToolApprovalSheet}.kt`, attribution to goose-android `MessageBubble`/`ToolCallCard` | restyle of `ui/ToolExecutionBox.kt` | ~900 |
| **9** | `feat(ui): RecipeLibrary + ExtensionDrawer + SessionList + SchedulerScreen` | `ui/{recipe,extensions,sessions,scheduler}/*.kt` | nav graph in `MainActivity.kt` | ~1100 |
| **10** | `chore(wire): register subsystems; harvest tools into SkillRegistry; bump deps` | `GalleryApp.kt` registers managers, `build.gradle.kts` adds kaml + commonmark + cron-utils | small wiring edits | ~200 |

Total estimated parity layer: **~6,000 LOC** across **~30 new files**. Existing CLU-BOX surfaces remain untouched in spirit — new modules adapt them to the Goose-shaped API.

This plan is intentionally **strictly additive** — every existing CLU-BOX surface (LiteRT runtime, BRAIN_BOX, MstrCtrlScreen, BusyBoxBridge, JGitManager, SplinterAPI, SkillRegistry, SCDL/CLN/LNK/FILE_BOX, Chaquopy Python skills) keeps its current contract; the new modules either compose with them or expose them through the new typed APIs.

## 6. Execution policy

- One stage = one commit on `feature/goose-parity`.
- Each commit message includes the stage number, the commit table row, and a 5-line rationale.
- We pause after stages 3, 6, and 10 for explicit user review (natural integration boundaries).
- The `runtime/` and `data/protectedllm/` packages are **never** edited — only wrapped.
- Reference borrowings carry an attribution comment: `// Pattern adapted from MaxFlynn13/goose-android (Apache-2.0)`.
