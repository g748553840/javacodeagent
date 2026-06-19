# Java Code Agent - AI Code Assistant

基于 **Java 21 + Spring Boot 3.2.5 + WebFlux** 实现的 AI 编程助手，参照 Claude Code 的架构设计思路构建。

---

## 目录

1. [特性概览](#特性概览)
2. [技术栈](#技术栈)
3. [项目结构](#项目结构)
4. [快速开始](#快速开始)
5. [API 参考](#api-参考)
6. [内置工具](#内置工具)
7. [扩展开发](#扩展开发)
8. [架构详解](#架构详解)
9. [关键设计决策](#关键设计决策)
10. [与 Claude Code 对比](#与-claude-code-对比)
11. [开发路线图](#开发路线图)
12. [测试](#测试)
13. [许可证](#许可证)

---

## 特性概览

| 能力 | 说明 |
|------|------|
| **多模型支持** | Anthropic Claude + 所有 OpenAI 兼容接口（DeepSeek / GLM / Qwen / OpenAI） |
| **Agentic Loop** | 自主工具调用循环，最多 10 轮，自动处理 `tool_use` / `tool_result` |
| **流式 SSE** | 结构化 SSE 事件（tool_start / tool_result / content / done） |
| **工具系统** | Read / Write / Edit / Glob / Grep / List / Bash / Git，可插件化扩展 |
| **权限模型** | READ_ONLY / SAFE / NORMAL / ALL 四级，工具自声明所需权限 |
| **Hook 机制** | PRE/POST_TOOL_CALL 等 7 种钩子，支持拦截与审计 |
| **上下文压缩** | 消息 > 40 条时调用 LLM 进行语义摘要，保留最近 10 条 |
| **记忆系统** | YAML frontmatter Markdown 文件 + MEMORY.md 索引，跨会话持久化，多用户目录分区 |
| **计划模式** | Explore → Draft → Review → Approve → Execute 五阶段安全执行 |
| **任务系统** | 带 blockedBy / blocks 依赖 DAG 的任务追踪，JPA 双层持久化 |
| **Sub-Agent** | 同步 / 异步 / 并行 / 隔离（READ_ONLY）四种派发模式 |
| **MCP 协议** | McpService 连接外部 MCP 服务器，自动发现并注册远程工具 |
| **对话持久化** | ConversationMessage JPA 持久化，重启后消息历史不丢失 |
| **Token 流式** | chatStreamFull() 逐 token 流式 + 工具调用协作，实时打字机效果 |
| **HTTP 认证** | ApiKeyAuthFilter，Bearer Token / X-API-Key 双格式鉴权 |
| **后台任务** | BackgroundTaskExecutor 结果保留 5 分钟，定时清理 |

---

## 技术栈

- **Java 21** — 虚拟线程、Switch 表达式、Records
- **Spring Boot 3.2.5** — WebFlux / JPA / Actuator / Validation
- **Reactor** — Mono / Flux 响应式编程
- **H2** — 嵌入式数据库（开发环境），可切换 PostgreSQL
- **Lombok** — 减少样板代码
- **Jackson** — JSON 序列化 / SSE 事件格式化
- **Anthropic API** — `/v1/messages`，支持 `tool_use`、`thinking`、SSE 流式
- **OpenAI 兼容 API** — `/v1/chat/completions`，支持 DeepSeek / GLM / Qwen / OpenAI

---

## 项目结构

```
javacodeagent/
├── src/main/java/com/javacodeagent/
│   ├── JavaCodeAgentApplication.java      # 应用入口，@EnableScheduling
│   ├── config/
│   │   ├── LLMClientConfig.java           # LLM 工厂（按 provider 选实现）
│   │   ├── LLMConfig.java                 # LLM 配置（model / apiKey / thinking）
│   │   ├── PermissionConfig.java          # 权限默认级别 & 自动审批
│   │   ├── ContextCompressionConfig.java  # 上下文压缩配置（threshold / keep-recent）
│   │   ├── MemoryConfig.java              # 记忆存储路径
│   │   ├── HookRegistrationConfig.java    # 内置 Hook 注册
│   │   ├── ApiKeyAuthFilter.java          # API Key 全局 WebFilter
│   │   └── WebConfig.java                 # CORS 配置
│   ├── controller/
│   │   ├── ConversationController.java    # /tasks /plan /memory /skills REST
│   │   └── ConversationWebSocketHandler.java  # /chat /chat/stream SSE 端点
│   ├── core/
│   │   ├── llm/
│   │   │   ├── LLMClient.java             # 接口：chat() / chatStream() / chatStreamFull()
│   │   │   ├── AnthropicLLMClient.java    # Anthropic 实现（content_block delta 解析）
│   │   │   └── OpenAILLMClient.java       # OpenAI 兼容实现（tool_calls delta 累积）
│   │   ├── conversation/
│   │   │   ├── ConversationManager.java   # Agentic Loop 核心，流式 SSE 事件编排
│   │   │   ├── ContextBuilder.java        # 构建 ConversationContext
│   │   │   ├── ContextCompressor.java     # LLM 语义压缩（Mono<ConversationContext>）
│   │   │   ├── ResponseParser.java        # 解析 LLMResponse → tool calls + text
│   │   │   └── MessagePersistenceService.java  # 消息 JPA 持久化（@Transactional）
│   │   ├── tool/
│   │   │   ├── Tool.java                  # 工具接口（含 isBlocking() 声明）
│   │   │   └── ToolManager.java           # 自动注册、权限校验、Hook 触发、阻塞调度
│   │   ├── hook/
│   │   │   ├── HookManager.java           # 注册 & 触发 Hook 链（CopyOnWriteArrayList）
│   │   │   ├── HookType.java              # 7 种 Hook 类型
│   │   │   └── LoggingHook.java           # 内置日志 Hook（工具结果截断 200 字符）
│   │   ├── permission/
│   │   │   └── PermissionService.java     # 按用户 & 级别检查权限
│   │   ├── memory/
│   │   │   └── MemoryService.java         # 读写 .md 记忆文件，维护 MEMORY.md 索引
│   │   ├── agent/
│   │   │   ├── AgentManager.java          # 同步/异步/并行/隔离 Agent 调度（虚拟线程）
│   │   │   └── ExploreAgent.java          # 只读探索 Agent（实际调用 Glob/Grep/Read/List）
│   │   ├── plan/
│   │   │   └── PlanService.java           # 五阶段计划模式状态机（JPA 双层持久化）
│   │   ├── task/
│   │   │   └── TaskManager.java           # 任务 CRUD & 依赖 DAG（JPA 双层持久化）
│   │   └── skill/
│   │       └── SkillManager.java          # 技能系统（@PostConstruct 自动发现 Bean）
│   ├── tools/                             # 内置工具实现
│   │   ├── ReadTool.java                  # 分页读取（offset/limit）
│   │   ├── WriteTool.java
│   │   ├── EditTool.java                  # 字面量字符串替换（Pattern.quote 防注入）
│   │   ├── GlobTool.java                  # 相对路径 glob 匹配
│   │   ├── GrepTool.java                  # 正则搜索（真实行号）
│   │   ├── ListTool.java
│   │   ├── BashTool.java                  # isBlocking=true，走 boundedElastic 调度
│   │   ├── GitTool.java                   # Git 操作（白名单命令，引号检测）
│   │   ├── BackgroundTaskExecutor.java    # 异步执行（虚拟线程，结果保留 5min）
│   │   └── FilePathResolver.java          # 路径遍历（Path Traversal）防护
│   ├── entity/                            # JPA 实体
│   │   ├── Conversation.java
│   │   ├── ConversationMessage.java       # 含复合索引（conversationId, createdAt）
│   │   ├── Task.java                      # @ManyToMany EAGER 自引用依赖
│   │   └── PlanEntity.java               # steps/allowedPrompts 序列化为 JSON CLOB
│   └── repository/                        # Spring Data JPA Repository
└── src/main/resources/
    ├── application.yml                    # 主配置
    └── application-dev.yml               # 开发配置（H2 Console，show-sql）
```

---

## 快速开始

### 前置条件

- JDK 21+
- Maven 3.8+
- LLM API Key（Anthropic / DeepSeek / OpenAI 等）

### 启动

```bash
cd D:/workspace/javacodeagent

# 配置 API Key（推荐环境变量）
export LLM_API_KEY=sk-ant-xxxxxxxxxxxxxxxx

# 编译并运行
mvn spring-boot:run

# 开发模式（含 H2 Console）
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

应用启动后监听 `http://localhost:8080`。

### 核心配置

```yaml
# application.yml
llm:
  provider: anthropic           # anthropic / openai / deepseek / glm / qwen
  api-key: ${LLM_API_KEY:}      # 空值时启动报错，明确要求配置
  model: claude-opus-4-8        # 推荐最新旗舰模型
  endpoint: https://api.anthropic.com
  max-tokens: 16384
  temperature: 0.7
  system-prompt: "You are..."
  thinking-enabled: false        # true 开启 adaptive thinking（需 beta header）

permissions:
  default-level: SAFE           # READ_ONLY / SAFE / NORMAL / ALL
  auto-approve:
    - file-read
    - glob
    - grep

context:
  compression:
    threshold: 40               # 消息数超过此值触发 LLM 语义压缩
    keep-recent: 10

memory:
  enabled: true
  location: ${user.home}/.java-code-agent/memory
  index-file: MEMORY.md

security:
  api-key: ""                   # 留空则关闭 HTTP API 认证

mcp:
  enabled: false
  servers: ""                   # 格式: "name1=http://url1,name2=http://url2"
```

```yaml
# application-dev.yml（-Dspring.profiles.active=dev 激活）
spring:
  h2:
    console:
      enabled: true
      path: /h2-console
  jpa:
    show-sql: true
```

---

## API 参考

### 对话

#### `POST /api/v1/chat/stream` — 流式 SSE 对话（推荐）

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-User-Id: alice" \
  -d '{"sessionId": "session-001", "content": "帮我找出所有 TODO 注释"}'
```

**SSE 事件流：**
```
data: {"type":"tool_start","tool":"Grep","id":"toolu_01"}

data: {"type":"tool_result","tool":"Grep","success":true,"preview":"src/Main.java:42: // TODO: ..."}

data: {"type":"content","text":"找到以下 TODO 注释："}

data: {"type":"done","conversationId":"conv-001"}
```

**事件类型：**

| type | 字段 | 说明 |
|------|------|------|
| `tool_start` | tool, id | 开始执行工具 |
| `tool_result` | tool, success, preview | 工具执行完成 |
| `content` | text | 文本分块（逐 token） |
| `done` | conversationId | 本轮结束 |
| `error` | message | 执行出错 |

#### `POST /api/v1/chat` — 普通对话（等待完整响应）

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "s1", "content": "读取 src/Main.java 并解释其功能"}'
```

#### `GET /api/v1/health` — 健康检查

### 会话管理

```bash
POST   /api/v1/sessions              # 创建 SSE 会话，返回 {"sessionId": "uuid"}
DELETE /api/v1/sessions/{sessionId}  # 删除会话
```

### 任务管理

```bash
POST   /api/v1/tasks                 # 创建任务 {"subject":"...","description":"...","activeForm":"...","blockedBy":["id1","id2"]}
GET    /api/v1/tasks                 # 列出所有任务
GET    /api/v1/tasks/{id}            # 获取任务详情
PUT    /api/v1/tasks/{id}/status     # 更新状态 {"status": "IN_PROGRESS"}
PUT    /api/v1/tasks/{id}            # 更新任务（owner / subject / status，均持久化到 DB）
DELETE /api/v1/tasks/{id}            # 删除任务
GET    /api/v1/tasks/{id}/blocking   # 获取阻塞此任务的列表
```

状态值：`PENDING` / `IN_PROGRESS` / `COMPLETED` / `FAILED`

### 计划模式

```bash
POST /api/v1/plan
{"conversationId": "c1", "description": "重构认证模块"}

POST /api/v1/plan/{planId}/submit          # 提交审阅
POST /api/v1/plan/{planId}/approve         # 批准 {"allowedPrompts": ["file-read", ...]}
POST /api/v1/plan/{planId}/reject          # 拒绝 {"reason": "..."}
POST /api/v1/plan/{planId}/next-step       # 推进下一步骤
POST /api/v1/plan/{planId}/complete-step   # 标记步骤完成 {"result": "..."}
POST /api/v1/plan/{planId}/fail-step       # 标记步骤失败 {"error": "..."}
POST /api/v1/plan/{planId}/execute         # 批量推进所有步骤状态
GET  /api/v1/plan/{planId}                 # 查看计划详情
```

### 记忆管理

```bash
POST   /api/v1/memory
{
  "userId": "alice",
  "name": "user-background",
  "description": "用户技术背景",
  "type": "USER",
  "content": "5 年 Java 经验，熟悉 Spring，初次接触 React"
}

GET    /api/v1/memory/{userId}                     # 获取用户所有记忆
GET    /api/v1/memory/{userId}/search?keyword=Spring  # 关键词搜索（content/name/description）
DELETE /api/v1/memory/{userId}/{memoryId}          # 删除记忆
```

记忆类型：`USER` / `FEEDBACK` / `PROJECT` / `REFERENCE`

### 技能

```bash
POST /api/v1/skills/{name}   # 执行技能，请求体为参数 Map
```

---

## 内置工具

| 工具 | 权限 | 关键参数 | 说明 |
|------|------|---------|------|
| `Read` | FILE_READ | file_path, offset, limit | 读取文件，支持分页（行号输出） |
| `Write` | FILE_WRITE | file_path, content | 写入 / 覆盖文件（自动创建目录） |
| `Edit` | FILE_WRITE | file_path, old_string, new_string, replaceAll | 精确字符串替换（字面量，防正则注入） |
| `Glob` | FILE_READ | pattern, path | 文件名 glob 匹配（相对路径，防绝对路径 Bug） |
| `Grep` | FILE_READ | pattern, path, type | 正则搜索文件内容（显示真实行号） |
| `List` | FILE_READ | path | 列出目录内容（含大小格式化） |
| `Bash` | SHELL_EXECUTE | command, timeout, run_in_background | Shell 命令执行（boundedElastic 线程） |
| `Git` | GIT_OPERATION | command, args | Git 操作（白名单命令，引号合法性检测） |

**Git 白名单命令：**
`status` / `diff` / `log` / `show` / `branch` / `remote` / `add` / `commit` / `checkout` / `switch` / `pull` / `push` / `fetch` / `merge` / `rebase` / `stash` / `tag` / `init` / `clone`

---

## 扩展开发

### 添加自定义工具

```java
@Component
public class WebFetchTool implements Tool {

    @Override public String getName() { return "WebFetch"; }

    @Override public String getDescription() {
        return "Fetch content from a URL and return as text";
    }

    @Override public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of("url", Map.of("type", "string")),
            "required", List.of("url")
        );
    }

    @Override public boolean requiresPermission() { return true; }
    @Override public PermissionType getRequiredPermission() { return PermissionType.NETWORK_REQUEST; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String url = (String) input.get("url");
        // ... 实现
        return ToolExecutionResult.success(responseBody);
    }
}
// @Component 声明后 Spring 自动注入到 ToolManager，无需手动注册
```

### 添加 Hook

```java
// HookRegistrationConfig.java
hookManager.registerHook(HookType.PRE_TOOL_CALL, context -> {
    String toolName = (String) context.getData().get("toolName");
    if ("Bash".equals(toolName)) {
        log.warn("Bash invoked by {}", context.getUserId());
        // return HookResult.reject("Bash 已被禁用") 可阻断执行
    }
    return HookResult.continueWith();
});
```

### 添加自定义 Agent

```java
@Component
public class ReviewAgent implements Agent {
    @Override public String getType() { return "review"; }
    @Override public List<String> getAvailableTools() { return List.of("Read", "Grep", "Glob"); }

    @Override
    public AgentResult process(AgentTask task, AgentContext context) {
        // 实现 code review 逻辑
        return AgentResult.builder().output("Review complete").success(true).build();
    }
}
// Spring 自动发现并注册到 AgentManager
```

### 替换 LLM 提供商

```java
@Primary
@Service
public class CustomLLMClient implements LLMClient {
    @Override public Mono<LLMResponse> chat(ConversationContext context) { ... }
    @Override public Flux<String> chatStream(ConversationContext context) { ... }
}
```

### 扩展点汇总

| 扩展点 | 方式 | 注册机制 |
|--------|------|---------|
| 新增工具 | 实现 `Tool` + `@Component` | ToolManager `@PostConstruct` 自动注入 `List<Tool>` |
| 新增 Agent | 实现 `Agent` + `@Component` | AgentManager 同上 |
| 新增技能 | 实现 `Skill` + `@Component` | SkillManager 同上 |
| 新增 Hook | `hookManager.registerHook()` | 注入任意执行点 |
| 替换 LLM | 实现 `LLMClient` + `@Primary` | `LLMClientConfig` 按 provider 构建 |
| 接入 MCP | 配置 `mcp.servers` | 启动时自动发现工具并注册到 ToolManager |

---

## 架构详解

### 1. 整体架构

**核心设计哲学**：安全第一（权限审查 + Hook 拦截）、可观测性（结构化日志）、可扩展性（接口注册，无需修改核心）。

**分层架构：**

```
┌──────────────────────────────────────────────────────────────────────┐
│                        接入层 (Interface Layer)                       │
│   REST API (HTTP/SSE)  │  WebSocket  │  IDE 插件（规划中）            │
└─────────────────┬────────────────────────────────────────────────────┘
                  │
┌─────────────────▼────────────────────────────────────────────────────┐
│                     应用服务层 (Application Layer)                    │
│  ConversationManager  │  AgentManager  │  PlanService  │  TaskManager │
└─────────────────┬────────────────────────────────────────────────────┘
                  │
┌─────────────────▼────────────────────────────────────────────────────┐
│                     核心引擎层 (Core Engine)                          │
│  LLMClient  │  ContextBuilder  │  ContextCompressor  │  HookManager   │
│  PermissionService  │  MemoryService  │  SkillManager  │  McpService   │
└─────────────────┬────────────────────────────────────────────────────┘
                  │
┌─────────────────▼────────────────────────────────────────────────────┐
│                     工具执行层 (Tool Execution Layer)                 │
│  Read / Write / Edit / Glob / Grep / List / Bash / Git / MCP 代理    │
└─────────────────┬────────────────────────────────────────────────────┘
                  │
┌─────────────────▼────────────────────────────────────────────────────┐
│                     持久化层 (Persistence Layer)                      │
│  JPA (H2/PostgreSQL)  │  文件系统 (memory/*.md)  │  内存缓存          │
└──────────────────────────────────────────────────────────────────────┘
```

**核心数据流：**

```
用户输入
   │
   ▼
ConversationWebSocketHandler / ConversationController
   │  构建 ConversationRequest（含 userId 从 X-User-Id 提取）
   ▼
ConversationManager.processMessage() / processMessageStream()
   │  从 MessagePersistenceService 加载历史消息
   │  构建 ConversationContext
   ▼
ContextCompressor.compress()          ← 超过阈值时 LLM 语义压缩
   │
   ▼
LLMClient.chat() / chatStreamFull()   ← POST /v1/messages
   │
   ▼
  LLM 响应
   ├── stop_reason = "end_turn"  → 持久化消息 → 返回文本
   └── stop_reason = "tool_use" → 工具执行循环
                                      │
                                      ▼
                              ToolManager.executeToolCall()
                                      │ Pre-Hook → 权限检查 → 执行 → Post-Hook
                                      ▼
                              tool_result 添加到消息历史
                              MessagePersistenceService 持久化
                                      │
                                      └──→ 回到 LLMClient（递归，最多 10 次）
```

---

### 2. Agentic Loop

```
┌──────────────────────────────────────┐
│            Agentic Loop              │
│  用户消息                             │
│      │                               │
│      ▼                               │
│  LLM 推理 (claude-opus-4-8 等)        │
│      ├── end_turn → 持久化 → 输出     │
│      └── tool_use ─────────────────┐ │
│  ┌─────────────────────────────┐   │ │
│  │  并行 / 串行工具执行          │◄──┘ │
│  │  - Pre Hook 检查             │     │
│  │  - 权限校验                  │     │
│  │  - tool.execute()           │     │
│  │  - Post Hook 记录            │     │
│  └────────────┬────────────────┘     │
│               │ tool_result          │
│               └──→ 添加消息历史 → 继续 │
└──────────────────────────────────────┘
```

**消息格式规范（Anthropic API）：**

```json
// 助手消息（工具调用）
{ "role": "assistant", "content": [
    { "type": "text", "text": "我来读取这个文件..." },
    { "type": "tool_use", "id": "toolu_01", "name": "Read", "input": { "file_path": "/src/Main.java" } }
]}

// 用户消息（工具结果，role 必须是 "user" 而非 "tool"）
{ "role": "user", "content": [
    { "type": "tool_result", "tool_use_id": "toolu_01", "content": "package com.example;..." }
]}
```

---

### 3. 工具系统

**Tool 接口：**

```java
public interface Tool {
    String getName();
    String getDescription();
    Map<String, Object> getParameterSchema();
    ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context);
    default boolean requiresPermission() { return false; }
    default PermissionType getRequiredPermission() { return null; }
    default boolean isBlocking() { return false; }  // 阻塞工具走 boundedElastic
}
```

**执行流程：**

```
ToolManager.executeToolCall(toolCall, context)
    ├── 1. 查找工具（tools Map）
    ├── 2. 权限检查（PermissionService）
    ├── 3. Pre-Hook（HookType.PRE_TOOL_CALL）
    ├── 4. 若 isBlocking()：Mono.fromCallable().subscribeOn(Schedulers.boundedElastic()).block()
    │      否则：tool.execute(input, context) 直接调用
    ├── 5. Post-Hook（HookType.POST_TOOL_CALL）
    └── 6. 返回 ToolExecutionResult
```

**路径安全（Path Traversal 防护）：**

```java
// FilePathResolver.java — 拒绝 ../../etc/passwd 类攻击
Path resolved = workingDir.resolve(inputPath).normalize();
if (!resolved.startsWith(workingDir)) {
    throw new SecurityException("Path traversal detected");
}
```

---

### 4. SSE 流式传输

**项目向客户端发出的 SSE 事件格式（`/api/v1/chat/stream`）：**

```
data: {"type":"tool_start","tool":"Read","id":"toolu_01"}
data: {"type":"tool_result","tool":"Read","success":true,"preview":"package com.example;..."}
data: {"type":"content","text":"根据文件内容，"}
data: {"type":"done","conversationId":"conv-uuid-xxxx"}
data: {"type":"error","message":"Maximum tool call depth exceeded"}
```

**流式架构：**

```
ConversationManager.processMessageStream()
    │
    └── executeStreamingLoop(context, depth=0)
         │
         ├── compressor.compress()                     [Mono → 异步]
         └── llmClient.chatStreamFull()                [Flux<LLMStreamChunk>]
              ├── TEXT chunk    → emit "content" SSE（逐 token）
              ├── TOOL_CALL chunk → 积累 toolChunks
              └── DONE chunk    →
                   ├── toolChunks 非空：
                   │    emit "tool_start" → 执行工具 → emit "tool_result"
                   │    → 持久化 → 递归 executeStreamingLoop(depth+1)
                   └── toolChunks 为空：emit "done"
```

**两个客户端的流式实现差异：**

| 实现 | 文本 chunk | 工具调用组装 | 结束判断 |
|------|-----------|------------|---------|
| `AnthropicLLMClient` | `text_delta` 直接 emit | `content_block_stop` 时组装完整调用 | `message_delta.stop_reason` |
| `OpenAILLMClient` | `delta.content` 直接 emit | 按 `index` 累积，`finish_reason` 出现时一次性 emit | `finish_reason` 非 null |

---

### 5. 上下文管理与压缩

```
消息数 ≤ threshold（默认 40） → 直接传入，不压缩
消息数 > threshold            → LLM 语义压缩：
                                  把最早的 (总数 - keepRecent) 条消息发给 LLM 生成摘要
                                  只保留摘要 + 最近 keepRecent（默认 10）条
                                  失败时降级为字符串拼接摘要
enabled: false                → 完全跳过压缩
```

压缩阈值和保留数量通过 `application.yml` 配置（由 `ContextCompressionConfig` 绑定，不再硬编码）：

```yaml
context:
  compression:
    enabled: true
    threshold: 40
    keep-recent: 10
```

`compress()` 返回 `Mono<ConversationContext>`（异步 HTTP 调用，不阻塞 WebFlux 链）。

**ConversationContext 数据结构：**

```java
public class ConversationContext {
    String conversationId;
    String userId;
    List<Message> messages;           // 消息历史（含 tool_use/tool_result）
    List<ToolDefinition> availableTools;
    PermissionLevel permissionLevel;
    Path workingDirectory;
    Map<String, Object> metadata;
}
```

---

### 6. 权限模型

| 级别 | 允许的操作 | 适用场景 |
|------|-----------|---------|
| `READ_ONLY` | 仅 FILE_READ | 隔离 Agent、只读探索 |
| `SAFE` | FILE_READ + FILE_WRITE | 默认级别 |
| `NORMAL` | 除 CONFIG_MODIFY 外全部 | 完整开发任务 |
| `ALL` | 所有操作 | 高度信任场景 |

**权限类型与工具对应：**

```
FILE_READ        → Read, Glob, Grep, List
FILE_WRITE       → Write, Edit
SHELL_EXECUTE    → Bash
GIT_OPERATION    → Git
NETWORK_REQUEST  → MCP 代理工具
CONFIG_MODIFY    → 修改配置文件
```

**检查流程：**

```
ToolManager.executeToolCall(toolCall, context)
    ├── context.permissionLevel != null?
    │       YES: PermissionService.checkPermissionLevel(level, type)   ← ExploreAgent / 隔离 Agent
    │       NO:  PermissionService.checkPermission(userId, type)       ← 用户级配置
    └── 不满足 → ToolExecutionResult.error("Permission denied")

PermissionService.checkPermission(userId, type)
    ├── 全局 auto-approve？ → 直接放行
    ├── 用户级 auto-approve？ → 直接放行
    └── checkPermissionLevel(userDefaultLevel, type)
         └── 不满足 → 拒绝
```

**权限级别传播链：**

```
application.yml permissions.default-level
    → ContextBuilder.resolveDefaultPermissionLevel()
    → ConversationContext.permissionLevel
    → ConversationManager.toExecutionContext()
    → ExecutionContext.permissionLevel
    → ToolManager（context.permissionLevel 不为 null 时优先使用）
```

---

### 7. Hook 系统

| HookType | 触发时机 | 可阻断？ |
|----------|---------|---------|
| `PRE_TOOL_CALL` | 工具执行前 | ✅ |
| `POST_TOOL_CALL` | 工具执行后 | ❌ |
| `PRE_COMMIT` | Git commit 前 | ✅ |
| `POST_COMMIT` | Git commit 后 | ❌ |
| `PRE_RESPONSE` | 返回给用户前 | ✅ |
| `POST_RESPONSE` | 返回给用户后 | ❌ |
| `PERMISSION_DENIED` | 权限拒绝时 | — |

**Hook 链执行（CopyOnWriteArrayList，短路于首个 REJECT）：**

```
hookManager.triggerHook(PRE_TOOL_CALL, context)
    ├── handler1 → CONTINUE
    ├── handler2 → CONTINUE
    └── handler3 → REJECT  ← 链中断，工具调用被阻断
```

**各 Hook 触发位置：**

| HookType | 触发位置 | 说明 |
|----------|---------|------|
| `PRE_TOOL_CALL` | `ToolManager.executeToolCall()` 权限检查后、工具执行前 | 可阻断 |
| `POST_TOOL_CALL` | `ToolManager.executeToolCall()` 工具执行后 | 通知型 |
| `PRE_COMMIT` | `GitTool`（调用方负责触发） | 可阻断 |
| `POST_COMMIT` | `GitTool`（调用方负责触发） | 通知型 |
| `PRE_RESPONSE` | `ConversationManager` 返回最终文本前（非流式 & 流式 DONE） | 可阻断 |
| `POST_RESPONSE` | `ConversationManager` 返回最终文本后 | 通知型 |
| `PERMISSION_DENIED` | `ToolManager.executeToolCall()` 权限拒绝时 | 通知型 |

---

### 8. 记忆系统

**记忆类型：**

| 类型 | 用途 |
|------|------|
| `USER` | 用户偏好、角色、知识背景 |
| `FEEDBACK` | 用户对 AI 行为的反馈与修正 |
| `PROJECT` | 项目背景、决策、里程碑 |
| `REFERENCE` | 外部资源指针（Linear / Grafana / Confluence 等） |

**存储格式（YAML frontmatter Markdown）：**

```markdown
---
name: user-role
description: 用户是 Java 后端工程师，熟悉 Spring Boot
metadata:
  type: user
---

用户有 10 年 Java 开发经验，初次接触 React。

关联记忆: [[feedback-explanation-style]]
```

**目录结构：**

```
{memory.location}/
├── MEMORY.md                # 全局索引（跨用户，每行 ≤ 150 字符）
├── alice/
│   ├── user-role.md
│   └── feedback-style.md
└── bob/
    └── project-context.md
```

`searchMemories(userId, keyword)` 同时匹配 `content` / `name` / `description` 三个字段（大小写不敏感）。

---

### 9. 计划模式

**五阶段状态机：**

```
enterPlanMode()
      │
      ▼  mode=EXPLORE, status=DRAFT
   EXPLORE（只读探索）— 只允许 Read/Glob/Grep/List
      │  addStep()
      ▼
   DRAFT（起草）— submitForReview() → status=IN_REVIEW
      │
      ▼
   IN_REVIEW（等待审批）
      ├── approvePlan()  → status=APPROVED
      │       │ executeNextStep() → IN_PROGRESS
      │       │ completeCurrentStep() / failCurrentStep()
      │       └── 全部完成 → COMPLETED
      └── rejectPlan() → status=DRAFT（允许修改重提）
```

**PlanService 是状态机，不是执行器**。步骤的实际执行体（读写文件、运行命令）由持有计划的 Agent 完成，通过 `completeCurrentStep()` / `failCurrentStep()` 回调更新结果。

**持久化**：`ConcurrentHashMap`（快速读）+ `PlanRepository` JPA（持久化），`persistToDatabase()` 在每次状态变更后 upsert；启动时 `loadFromDatabase()` 恢复内存。步骤列表序列化为 JSON CLOB 存储。

---

### 10. 任务系统

**任务 vs 计划：**

| 维度 | 任务（Task） | 计划（Plan） |
|------|------------|------------|
| 粒度 | 单个工作项 | 多步骤实施方案 |
| 依赖 | blockedBy/blocks DAG | 步骤有序，线性执行 |
| 持久化 | JPA 双层（含依赖关系） | JPA 双层（步骤 JSON CLOB） |
| 用途 | AI 追踪工作进度 | 用户审批后再执行 |

**依赖 DAG：**

```
Task A ──blocks──► Task B ──blocks──► Task D
Task A ──blocks──► Task C ──blocks──► Task D

canStart("D") → 检查 B 和 C 是否均为 COMPLETED
```

启动时 `loadFromDatabase()` 从 JPA 恢复 `blockedBy` ID 集合（`@ManyToMany EAGER`），随后重建反向 `blocks` 引用。

---

### 11. Sub-Agent 架构

**执行模式：**

```java
// 同步
AgentResult result = agentManager.executeAgent("explore", task, context);

// 异步（Java 21 虚拟线程）
CompletableFuture<AgentResult> future = agentManager.launchAgentAsync("explore", task, context);

// 并行多 Agent（等待全部完成）
List<AgentResult> results = agentManager.launchParallelAgents(tasks, context);

// 隔离（强制 READ_ONLY，独立上下文）
CompletableFuture<AgentResult> isolated = agentManager.launchIsolatedAgent(task, context);
```

**ExploreAgent**：强制 `READ_ONLY`，通过 `task.parameters.mode` 分派工具（`glob` / `grep` / `read` / `list`）。Spring 自动发现所有 `@Component Agent` 并注入到 `AgentManager`。

---

### 12. 持久化层

**JPA 实体：**

| 实体 | 表 | 说明 |
|------|-----|------|
| `Conversation` | `conversations` | 对话会话元数据 |
| `ConversationMessage` | `conversation_messages` | 消息历史（tool_calls JSON，含复合索引） |
| `Task` | `tasks` | 任务实体（@ManyToMany EAGER 自引用） |
| `PlanEntity` | `plans` | 计划（steps / allowedPrompts 序列化为 JSON CLOB） |

开发模式 H2 Console：`http://localhost:8080/h2-console`（需激活 `dev` profile）
- JDBC URL: `jdbc:h2:mem:testdb` / 用户名: `sa` / 密码: 空

**消息持久化原子性（`MessagePersistenceService.saveMessages`）：**

`@Transactional` 方法内，try-catch 包裹整个 for 循环而非单条消息，确保"一批消息全部保存或全部不保存"的原子语义：
- 若任意一条 `repository.save()` 抛出异常，循环立即中止，整个事务回滚
- 捕获异常后记录 `log.warn`，对话流程不中断（消息仅暂时不持久化）

---

### 13. LLM 多提供商支持

| Provider 配置值 | API 格式 | 实现类 | 默认端点 |
|---------------|---------|--------|---------|
| `anthropic` | Anthropic Messages API | `AnthropicLLMClient` | `https://api.anthropic.com` |
| `openai` | OpenAI Chat Completions | `OpenAILLMClient` | `https://api.openai.com/v1` |
| `deepseek` | OpenAI 兼容 | `OpenAILLMClient` | `https://api.deepseek.com/v1` |
| `glm` / `zhipu` | OpenAI 兼容 | `OpenAILLMClient` | `https://open.bigmodel.cn/api/paas/v4` |
| `qwen` / `dashscope` | OpenAI 兼容 | `OpenAILLMClient` | `https://dashscope.aliyuncs.com/compatible-mode/v1` |

`LLMClientConfig` 根据 `llm.provider` 配置选择实现；未知 provider 值会记录 `log.warn` 后降级为 OpenAI 兼容客户端。

**消息格式差异：**

| 特性 | Anthropic | OpenAI 兼容 |
|------|-----------|------------|
| 系统提示 | 请求体 `system` 字段 | 首条 `role: system` 消息 |
| 工具结果 | `role: user` + `tool_result` block | `role: tool` + `tool_call_id` |
| stop_reason | `end_turn` / `tool_use` | `stop` / `tool_calls` |

---

### 14. MCP 协议支持

```
mcp.servers: "fs=http://localhost:3001,gh=http://localhost:3002"
                  │
                  ▼
            McpService.init()
                  │
      ┌───────────┴───────────┐
      │                       │
HttpMcpClient("fs")   HttpMcpClient("gh")
      │ connect() + listTools()
      ▼
McpProxyTool("mcp__fs__read_file")
      │
      └──→ toolManager.registerTool()  ← 直接注册到 ToolManager
```

使用 Streamable HTTP 传输层（JSON-RPC 2.0）。工具命名规范：`mcp__{server-name}__{tool-name}`，避免与内置工具冲突。

---

### 15. API 安全认证

**认证方式：**

```
配置: security.api-key=your-secret-key（留空则关闭认证）
客户端（任选其一）：
  Authorization: Bearer your-secret-key
  X-API-Key: your-secret-key
```

公开路径：`/api/v1/health`、`/h2-console/**`、`/actuator/**`

**多用户 userId 传递链：**

```
HTTP 请求头 X-User-Id
    │
    ▼
ConversationWebSocketHandler / ConversationController
    │  extractUserId() → 填入 ConversationRequest.userId
    ▼
ContextBuilder → ConversationContext.userId
    ▼
ConversationManager → ExecutionContext.userId
    ▼
ToolManager → PermissionService.checkPermission(userId, ...)
              MemoryService（按 userId 目录隔离）
```

---

## 关键设计决策

**为何使用 WebFlux？**  
SSE 流式传输天然适合 `Flux<String>`。Agentic Loop 中每轮等待 LLM + 工具执行，同步阻塞模型会长时间占用线程；响应式模型下 IO 等待不占线程。

**为何 `compress()` 返回 `Mono<ConversationContext>`？**  
LLM 语义压缩是异步 HTTP 调用，必须返回 `Mono` 才能在 WebFlux 链中无阻塞地组合，不能用同步返回值。

**为何工具执行是同步的（BashTool 例外走 boundedElastic）？**  
Anthropic API 要求工具调用结果在同一 user 消息中批量返回，同步执行便于控制 `tool_result` 的生成顺序。BashTool 等阻塞工具通过 `isBlocking()` 接口声明，由 ToolManager 统一调度到弹性线程池，避免阻塞 Netty IO 线程。

**为何 `PlanService` 不直接执行步骤？**  
计划系统是**状态机**，不是执行器。职责单一使其可以被不同执行器（本地 Agent、远程 Worker）灵活接入，不形成耦合。

**Anthropic vs OpenAI 流式 tool_calls 差异的处理？**  
Anthropic 以 `content_block_start/delta/stop` 精确边界描述每个 block，`content_block_stop` 时立即组装完整调用。OpenAI 以 `index` 标识不同工具调用的 delta 增量，需等到 `finish_reason` 出现后才能确认所有调用均完整接收，两者实现路径不同但对外接口统一。

**为何 `userId` 依赖请求头而非 Spring Security？**  
项目使用 WebFlux，未引入 Spring Security；`X-User-Id` 请求头方案实现简单且与认证（API Key）解耦，方便未来替换为 JWT claim 提取，无需修改下游代码。

---

## 与 Claude Code 对比

| 能力 | Claude Code | Java Code Agent |
|------|------------|----------------|
| Agentic Loop（最多 10 轮） | ✅ | ✅ |
| SSE Token 级流式（Anthropic） | ✅ | ✅ |
| SSE Token 级流式（OpenAI 兼容） | ✅ | ✅ |
| Hook 系统 | ✅ | ✅ |
| 权限模型（四级） | ✅ | ✅ |
| 记忆系统（文件持久化 + 多用户分区） | ✅ | ✅ |
| 记忆多字段搜索 | ✅ | ✅ |
| 计划模式（五阶段 + JPA 持久化） | ✅ | ✅ |
| 任务系统（依赖 DAG + JPA） | ✅ | ✅ |
| Sub-Agent（并行 / 隔离） | ✅ | ✅ |
| 上下文压缩（LLM 语义） | ✅ | ✅ |
| Git 工具 | ✅ | ✅ |
| 路径遍历防护 | ✅ | ✅ |
| 消息历史持久化（JPA） | ✅ | ✅ |
| LLM 多提供商（工厂模式） | ✅ | ✅ |
| MCP 协议客户端 | ✅ | ✅ |
| API Key 认证（WebFilter） | ✅ | ✅ |
| Thinking Mode（Anthropic） | ✅ | ✅ |
| **向量记忆检索（embedding）** | ✅ | ❌ 待实现 |
| **容器化（Dockerfile）** | ✅ | ❌ 待实现 |
| **生产级 OAuth2 / JWT 认证** | ✅ | ❌ 待实现 |

---

## 开发路线图

### 已完成

- [x] 基础框架（Spring Boot 3.2.5 + WebFlux）
- [x] Anthropic / OpenAI 兼容 LLM 客户端（Token 级流式）
- [x] Agentic Loop（消息格式符合 API 规范，最多 10 轮）
- [x] 工具系统（Read / Write / Edit / Glob / Grep / List / Bash / Git）
- [x] 权限模型（四级 + 自动审批 + 路径遍历防护）
- [x] Hook 机制（7 种类型，可拦截）
- [x] 记忆系统（文件持久化 + MEMORY.md 索引 + 多用户目录分区）
- [x] 计划模式（五阶段状态机 + JPA 持久化）
- [x] 任务系统（依赖 DAG + JPA 双层存储）
- [x] Sub-Agent（同步 / 异步 / 并行 / 隔离，ExploreAgent 实际调用工具）
- [x] 上下文压缩（LLM 语义压缩，降级到字符串摘要）
- [x] MCP 协议客户端（Streamable HTTP，工具自动注册到 ToolManager）
- [x] API Key 认证（双格式 Bearer / X-API-Key，WebFilter 层统一处理）
- [x] 对话消息持久化（JPA + @Transactional）
- [x] 集成测试（工具执行、Agentic Loop、SSE 端对端、计划持久化，`@MockBean LLMClient`）
- [x] 多用户 userId 全链路透传（X-User-Id → PermissionService / MemoryService）
- [x] Thinking Mode（Anthropic interleaved-thinking）

### 待实现

- [ ] **向量记忆检索** — embedding 相似度替换关键词匹配（P1）
- [ ] **容器化** — Dockerfile + docker-compose（含 PostgreSQL 替换 H2）（P2）
- [ ] **生产级认证** — OAuth2 / JWT，userId 从 Token claims 提取（P2）

---

## 测试

```bash
# 运行所有测试
mvn test

# 运行特定测试类
mvn test -Dtest=ReadToolTest

# 查看测试报告
open target/surefire-reports/index.html
```

集成测试使用 `@SpringBootTest` + H2 内存库，LLM 调用通过 `@MockBean LLMClient` 拦截，无需真实 API Key。

---

## 许可证

本项目仅供学习和参考使用。
