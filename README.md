# Java Code Agent - AI 编程与数据分析助手

基于 **Java 21 + Spring Boot 3.2.5 + WebFlux** 构建的 **AI 编程和数据分析助手**，集成完整的 NL2SQL 数据分析流水线、多 Agent 协作分析与 Excel/CSV 文件分析能力，架构设计参考自 **DB-GPT**（Python/AWEL 流水线）和 **spring-ai-alibaba**（Java/Spring AI StateGraph）两个开源项目。

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
9. [Data Agent 深度解析](#data-agent-深度解析)
10. [关键设计决策](#关键设计决策)
11. [与 Claude Code 对比](#与-claude-code-对比)
12. [开发路线图](#开发路线图)
13. [测试](#测试)
14. [许可证](#许可证)

---

## 特性概览

| 能力 | 说明 |
|------|------|
| **多模型支持** | Anthropic Claude + 所有 OpenAI 兼容接口（DeepSeek / GLM / Qwen / OpenAI） |
| **Agentic Loop** | 自主工具调用循环，可配置上限（默认 50 轮），自动处理 `tool_use` / `tool_result` |
| **流式 SSE** | 结构化 SSE 事件（tool_start / tool_result / content / done） |
| **工具系统** | Read / Write / Edit / Glob / Grep / List / Bash / Git / SqlQuery，可插件化扩展 |
| **权限模型** | READ_ONLY / SAFE / NORMAL / ALL 四级，工具自声明所需权限 |
| **Hook 机制** | PRE/POST_TOOL_CALL 等 7 种钩子，支持拦截与审计 |
| **上下文压缩** | 消息 > 40 条时调用 LLM 进行语义摘要，保留最近 10 条 |
| **记忆系统** | YAML frontmatter Markdown 文件 + MEMORY.md 索引，跨会话持久化，多用户目录分区 |
| **向量记忆检索** | LangChain4j `OpenAiEmbeddingModel`（兼容 Ollama / Qwen / DeepSeek 等本地/云模型）+ `EmbeddingStore<TextSegment>`（开发用 InMemory，PostgreSQL profile 用 PgVector 持久化），`mode=semantic` 参数切换 |
| **计划模式** | Explore → Draft → Review → Approve → Execute 五阶段安全执行 |
| **任务系统** | 带 blockedBy / blocks 依赖 DAG 的任务追踪，JPA 双层持久化 |
| **Sub-Agent** | 同步 / 异步 / 并行 / 隔离（READ_ONLY）四种派发模式 |
| **MCP 协议** | `McpService` 基于 LangChain4j `DefaultMcpClient` + `StreamableHttpMcpTransport` 连接外部 MCP 服务器，自动发现并注册远程工具 |
| **对话持久化** | ConversationMessage JPA 持久化，重启后消息历史不丢失 |
| **Token 流式** | chatStreamFull() 逐 token 流式 + 工具调用协作，实时打字机效果 |
| **HTTP 认证** | ApiKeyAuthFilter，Bearer Token / X-API-Key 双格式鉴权；JWT 模式（JwtAuthFilter）自动从 sub claim 提取 userId |
| **后台任务** | BackgroundTaskExecutor 结果保留 5 分钟，定时清理 |
| **数据分析智能体** | NL2SQL 全流水线（Schema 检索→SQL 生成→执行→洞察），GPT-Vis 8 种图表协议 |
| **Excel/CSV 分析** | 上传文件→H2 内存表→NL 查询，Apache POI + Commons CSV；CJK 列名 LLM 自动翻译 |
| **Dashboard 多图** | LLM 规划 2-4 张互补图表，并行执行，错误隔离 |
| **多 Agent 协作分析** | DataAnalysisAgent + AnomalyDetectorAgent + VolatilityAnalysisAgent + ReportGenerationAgent 并行分析 |
| **历史 SQL 缓存** | SqlCacheService LRU + TTL 缓存，相似问题复用 SQL，减少 LLM 调用 |
| **多数据源管理** | `DataSourceManager` 注册/切换多个 DB 连接，HikariCP 连接池，REST API 动态注册，`dataSourceId` 路由 |
| **查询超时告警 + 慢查询日志** | Micrometer `Timer` 记录查询耗时，超阈值 WARN 日志，`/actuator/metrics` 可读 |
| **指标体系集成** | `MetricInfoRetriever` 注册指标定义，`MetricAnalysisPipeline` 完整归因链路（当前值+历史趋势→异常+波动→综合报告） |
| **容器化** | Dockerfile（multi-stage, JDK 21）+ docker-compose.yml（H2）+ docker-compose-pg.yml（PostgreSQL）|

---

## 技术栈

- **Java 21** — 虚拟线程、Switch 表达式、Records
- **Spring Boot 3.2.5** — WebFlux / JPA / Actuator / Validation / JDBC
- **Reactor** — Mono / Flux 响应式编程
- **H2** — 嵌入式数据库（开发环境），可切换 PostgreSQL；Data Agent 使用 H2 内存表存储 Excel 导入数据
- **Apache POI 5.2.5** — Excel (.xlsx/.xls) 读取与解析
- **Apache Commons CSV 1.10.0** — CSV 文件解析
- **Lombok** — 减少样板代码
- **Jackson** — JSON 序列化 / SSE 事件格式化
- **Anthropic API** — `/v1/messages`，支持 `tool_use`、`thinking`、SSE 流式
- **OpenAI 兼容 API** — `/v1/chat/completions`，支持 DeepSeek / GLM / Qwen / OpenAI
- **LangChain4j**（`memory.embedding.enabled=true` / `mcp.enabled=true` 时启用）：
  - `langchain4j-core` + `langchain4j-open-ai` — `OpenAiEmbeddingModel` 替代手写 `/v1/embeddings` 调用，`EmbeddingStore<TextSegment>` 统一向量存储抽象
  - `langchain4j-pgvector`（**beta**）— PostgreSQL profile 下的持久化向量存储，替代纯内存 `ConcurrentHashMap` 方案
  - `langchain4j-mcp`（**beta**）— 标准 MCP 客户端（`DefaultMcpClient` + `StreamableHttpMcpTransport`），替代手写 JSON-RPC over WebClient

---

## 项目结构

```
javacodeagent/
├── Dockerfile                         # Multi-stage build (maven:3.9 + JRE 21, non-root)
├── docker-compose.yml                 # H2 内嵌，开箱即用
├── docker-compose-pg.yml              # PostgreSQL 生产模式
├── src/main/java/com/javacodeagent/
│   ├── Application.java                   # 应用入口，@EnableScheduling
│   ├── config/
│   │   ├── AgentConfig.java               # Agent 配置（max-tool-call-depth 可配置深度）
│   │   ├── LLMClientConfig.java           # LLM 工厂（按 provider 选实现）
│   │   ├── LLMConfig.java                 # LLM 配置（model / apiKey / thinking）
│   │   ├── PermissionConfig.java          # 权限默认级别 & 自动审批
│   │   ├── ContextCompressionConfig.java  # 上下文压缩配置（threshold / keep-recent）
│   │   ├── MemoryConfig.java              # 记忆存储路径
│   │   ├── HookRegistrationConfig.java    # 内置 Hook 注册
│   │   ├── DataAgentConfig.java           # DataSourceConnector Bean（可选外部数据源）
│   │   ├── SkillConfig.java               # Skill 扩展配置（enabled / location 目录）
│   │   ├── ApiKeyAuthFilter.java          # API Key 全局 WebFilter（@Order(2)）
│   │   ├── JwtAuthFilter.java             # JWT 认证 WebFilter（@Order(1)，优先于 ApiKey）
│   │   └── WebConfig.java                 # CORS 配置
│   ├── controller/
│   │   ├── ConversationController.java    # /tasks /plan /memory REST
│   │   ├── SkillController.java           # /skills REST（list/register/reload/unregister/execute）
│   │   ├── ConversationWebSocketHandler.java  # /chat /chat/stream SSE 端点
│   │   ├── DataAgentController.java       # /api/v1/data-agent/* REST + SSE + /multi-analysis
│   │   └── ExcelAnalysisController.java   # /api/v1/data-agent/excel/* 上传 & 查询
│   ├── core/
│   │   ├── auth/
│   │   │   ├── JwtService.java            # JWT 生成/验证（HMAC-SHA256, sub=userId）
│   │   │   └── AuthController.java        # POST /api/v1/auth/token（公开路径）
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
│   │   ├── data/                          # ── Data Agent 数据分析模块 ──
│   │   │   ├── DataAgentPipeline.java     # 主编排：Schema→NL2SQL→执行→洞察
│   │   │   ├── DataSourceConnector.java   # 数据源抽象接口
│   │   │   ├── JdbcDataSourceConnector.java  # JDBC 实现（H2/MySQL/PostgreSQL 方言）
│   │   │   ├── ExcelDataSourceConnector.java # Excel/CSV → H2；中文列名 LLM 翻译
│   │   │   ├── SchemaRetriever.java       # Schema 检索（CJK codePointCount 修复）
│   │   │   ├── Nl2SqlService.java         # NL→SQL（LLMClient + SqlCacheService）
│   │   │   ├── SqlCacheService.java       # LRU+TTL SQL 缓存（500 条，1h TTL）
│   │   │   ├── SqlValidator.java          # DML/DDL 白名单拦截（12 个受阻关键词）
│   │   │   ├── SqlExecutor.java           # SQL 执行（boundedElastic，防阻塞 Netty）
│   │   │   ├── InsightGenerator.java      # 结果解读（LLM → Markdown 洞察）
│   │   │   ├── DashboardGenerator.java    # 多图 Dashboard（Flux.flatMap 并行 SQL）
│   │   │   ├── DataAgentConstants.java    # 所有共享常量（魔法值中心化）
│   │   │   ├── agent/
│   │   │   │   ├── DataAnalysisAgent.java      # 编排器（虚拟线程并行派发）
│   │   │   │   ├── AnomalyDetectorAgent.java   # 异常检测（LLM + JSON 数组输出）
│   │   │   │   ├── VolatilityAnalysisAgent.java # 波动分析（CV/趋势/极值）
│   │   │   │   └── ReportGenerationAgent.java  # Markdown 综合报告生成
│   │   │   └── model/
│   │   │       ├── DataQueryRequest.java
│   │   │       ├── DataQueryResult.java
│   │   │       ├── ChartSpec.java
│   │   │       ├── DashboardSpec.java
│   │   │       ├── Nl2SqlResult.java
│   │   │       ├── InsightResult.java
│   │   │       ├── DataAnalysisReport.java
│   │   │       ├── MultiAnalysisReport.java    # 多 Agent 协作分析聚合报告
│   │   │       └── SqlValidationResult.java
│   │   ├── core/
│   │   │   ├── skill/
│   │   │   │   ├── Skill.java                 # 技能接口（getName / getParameterSchema / execute）
│   │   │   │   ├── SkillManager.java          # 注册、查找、执行技能；list/unregister 管理方法
│   │   │   │   ├── SkillInput.java / SkillResult.java
│   │   │   │   ├── ExternalSkillDescriptor.java  # 外部 Skill YAML 描述符模型（name/execution/schema）
│   │   │   │   ├── HttpDelegatedSkill.java    # HTTP 委托实现：将执行转发到外部 HTTP 端点
│   │   │   │   └── ExternalSkillLoader.java   # 启动时扫描 skills.location 目录，支持运行时热加载
│   │   │   ├── tool/ hook/ permission/ memory/ agent/ plan/ task/  # 同前
│   ├── tools/                             # 内置工具实现（同前）
│   ├── entity/                            # JPA 实体（同前）
│   └── repository/                        # Spring Data JPA Repository
└── src/main/resources/
    ├── application.yml                    # 主配置（含 security.jwt.* 配置节）
    ├── application-dev.yml               # 开发配置（H2 Console，show-sql）
    └── application-postgres.yml          # PostgreSQL profile（data-agent 外部数据源）
```

---

## 快速开始

### 前置条件

- JDK 21+
- Maven 3.8+
- LLM API Key（Anthropic / DeepSeek / OpenAI 等）

### 启动（本地 JVM）

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

### 启动（Docker — H2 内嵌，开箱即用）

```bash
# 1. 复制环境变量模板
cp .env.example .env
# 2. 至少填写 LLM_API_KEY
vim .env

# 3. 启动（首次构建约 2-3 分钟）
docker compose up --build
```

### 启动（Docker — PostgreSQL 生产模式）

```bash
cp .env.example .env
# 填写 LLM_API_KEY、POSTGRES_PASSWORD 等
vim .env

docker compose -f docker-compose-pg.yml up --build
```

PostgreSQL 模式下，Data Agent 的 NL2SQL 分析查询将直接指向外部 PostgreSQL（`data-agent.datasource.*`），Spring JPA 实体（对话/任务/计划）仍使用 H2 内嵌存储。

### 核心配置

```yaml
# application.yml
spring:
  main:
    web-application-type: reactive  # 必须显式设置：防止 JPA 引入 Tomcat 后被误识别为 Servlet 模式

llm:
  provider: anthropic           # anthropic / openai / deepseek / glm / qwen
  api-key: ${LLM_API_KEY:}      # 空值时启动报错，明确要求配置
  model: claude-opus-4-8        # 推荐最新旗舰模型
  endpoint: https://api.anthropic.com
  max-tokens: 16384
  temperature: 0.7
  system-prompt: "You are..."
  thinking-enabled: false        # true 开启 adaptive thinking（需 beta header）

agent:
  max-tool-call-depth: 50        # Agentic Loop 最大工具调用深度，超出返回错误（可配置）

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
  api-key: ""                   # 留空则关闭 HTTP API Key 认证
  jwt:
    secret: ""                  # 非空时启用 JWT 认证（优先于 api-key）
    ttl-hours: 24               # JWT 令牌有效期（小时）

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

**会话 REST（对话元数据）：**

```bash
POST   /api/v1/conversations         # 创建对话记录
GET    /api/v1/conversations         # 列出所有对话（可选 ?userId=xxx 过滤）
GET    /api/v1/conversations/{id}    # 查询单条对话
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
POST /api/v1/plan/{planId}/execute         # ⚠️ 仅批量推进步骤状态为 COMPLETED，不执行任何真实文件/Shell操作，
                                            #    需要真实执行效果请改用 next-step + complete-step/fail-step 组合
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

### Data Agent（数据分析智能体）

#### 配置

```yaml
# application.yml
data-agent:
  dialect: h2          # h2 | mysql | postgresql
  db-name: PUBLIC      # H2 默认 Schema；MySQL 填数据库名；PostgreSQL 填 public
```

#### `POST /api/v1/data-agent/query` — 完整 NL→SQL→执行→洞察

```bash
curl -X POST http://localhost:8080/api/v1/data-agent/query \
  -H "Content-Type: application/json" \
  -d '{"question": "统计各地区月销售额趋势", "maxRows": 200}'
```

**响应：**
```json
{
  "question": "统计各地区月销售额趋势",
  "success": true,
  "chartSpec": {
    "sql": "SELECT region, month, SUM(amount) FROM sales GROUP BY region, month",
    "displayType": "response_line_chart",
    "thought": "按地区和月份聚合，折线图对比各地区趋势",
    "data": [{"region":"华东","month":"2024-01","total":1200000}]
  },
  "insightMarkdown": "## 销售趋势分析\n华东地区 1 月增长最显著..."
}
```

#### `POST /api/v1/data-agent/query/stream` — SSE 流式分析

```bash
curl -N -X POST http://localhost:8080/api/v1/data-agent/query/stream \
  -H "Content-Type: application/json" \
  -d '{"question": "各产品销量排名前10"}'
```

**SSE 事件流：**
```
data: {"type":"started","question":"各产品销量排名前10"}
data: {"type":"schema_retrieved","length":2048}
data: {"type":"sql_generated","sql":"SELECT ...","displayType":"response_bar_chart"}
data: {"type":"sql_executed","rowCount":10}
data: {"type":"insight_ready","markdown":"## 分析结论\n..."}
data: {"type":"done"}
```

| 事件类型 | 关键字段 | 说明 |
|----------|---------|------|
| `started` | question | 开始处理 |
| `schema_retrieved` | length | Schema 提取完成（字符数） |
| `sql_generated` | sql, displayType | LLM 生成 SQL + 图表类型 |
| `sql_executed` | rowCount | SQL 执行完成，返回行数 |
| `insight_ready` | markdown | 洞察报告 Markdown |
| `done` | — | 流结束 |
| `error` | message | 任意阶段错误 |

#### `POST /api/v1/data-agent/nl2sql` — 仅生成 SQL（不执行，供用户审核）

```bash
curl -X POST http://localhost:8080/api/v1/data-agent/nl2sql \
  -H "Content-Type: application/json" \
  -d '{"question": "月活跃用户数量"}'
# 响应：{"thought":"...","sql":"SELECT ...","displayType":"response_line_chart"}
```

#### `POST /api/v1/data-agent/execute` — 执行用户确认的 SQL

```bash
curl -X POST http://localhost:8080/api/v1/data-agent/execute \
  -H "Content-Type: application/json" \
  -d '{"sql": "SELECT COUNT(*) FROM users"}'
# 响应：{"columns":["COUNT(*)"],"rows":[[42]],"totalRows":1}
```

#### `GET /api/v1/data-agent/schema` — 数据源 Schema 概览

```bash
curl http://localhost:8080/api/v1/data-agent/schema
# 响应：{"database":"PUBLIC","dialect":"h2","tables":["ORDERS","USERS"],"tableCount":2}
```

#### `POST /api/v1/data-agent/dashboard` — 多图 Dashboard

LLM 一次性规划 2-4 张互补图表，每张独立执行 SQL，单图失败不阻断整体渲染（`errMsg` 字段隔离）。

```bash
curl -X POST http://localhost:8080/api/v1/data-agent/dashboard \
  -H "Content-Type: application/json" \
  -d '{"question": "生成销售综合看板"}'
# 响应：{"title":"...","charts":[...],"displayStrategy":"default","chartCount":2}
```

#### `POST /api/v1/data-agent/multi-analysis` — 多 Agent 协作分析

```bash
curl -X POST http://localhost:8080/api/v1/data-agent/multi-analysis \
  -H "Content-Type: application/json" \
  -d '{"question": "分析近3个月销售数据中的异常和波动情况"}'
```

**响应：**
```json
{
  "question": "分析近3个月销售数据中的异常和波动情况",
  "sql": "SELECT month, SUM(amount) FROM sales WHERE ...",
  "rowCount": 36,
  "anomalies": ["column 'amount' value 999999 — 5× above mean in 2024-11"],
  "volatilityMetrics": {"cv": 0.38, "trend": "increasing", "peakValue": 999999},
  "reportMarkdown": "## 执行摘要\n近3个月销售数据整体呈上升趋势...",
  "success": true
}
```

**执行顺序（全程非阻塞 + 虚拟线程）：**
1. `NL2SQL 管道` → 获取查询数据
2. `AnomalyDetectorAgent` + `VolatilityAnalysisAgent` → **并行** LLM 分析
3. `ReportGenerationAgent` → 聚合 Markdown 综合报告

#### `POST /api/v1/auth/token` — 生成 JWT 令牌（需配置 `security.jwt.secret`）

```bash
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId": "alice"}'
# 响应：{"token": "eyJhbGciOiJIUzI1NiJ9...", "userId": "alice"}
```

后续携带令牌访问：
```bash
curl -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  -H "Content-Type: application/json" \
  -d '{"content": "帮我分析 src/Main.java"}'
```

#### `POST /api/v1/data-agent/excel/upload` — 上传 Excel/CSV 文件

```bash
curl -X POST http://localhost:8080/api/v1/data-agent/excel/upload \
  -F "file=@sales.xlsx"
# 响应：{"tableName":"tbl_a1b2c3d4","filename":"sales.xlsx","schema":"...","status":"imported"}
```

支持 `.xlsx`、`.csv` 格式。文件数据导入 H2 内存表，列名自动安全化（空格→下划线，去除特殊字符）。

#### `POST /api/v1/data-agent/excel/query` — 针对已上传文件的 NL 查询

```bash
curl -X POST http://localhost:8080/api/v1/data-agent/excel/query \
  -H "Content-Type: application/json" \
  -d '{"tableName": "tbl_a1b2c3d4", "question": "各产品销量排名"}'
# 响应：DataAnalysisReport（同 /query）
```

**支持的图表类型（GPT-Vis 协议）：**

| displayType | 适用场景 |
|-------------|---------|
| `response_line_chart` | 趋势比较分析 |
| `response_bar_chart` | 分类对比 |
| `response_pie_chart` | 比例/分布统计 |
| `response_table` | 多列或非数值型数据 |
| `response_scatter_chart` | 变量关系、异常检测 |
| `response_area_chart` | 时序数据、多组对比 |
| `response_heatmap` | 大规模时序热力图 |
| `response_donut_chart` | 层级结构、分类比例 |

**SQL 安全双重防护：**

```
Prompt 层（NL2SQL system prompt 约束 LLM 只输出 SELECT）
    +
SqlValidator 工具层（不可绕过，执行前强制拦截）
    拦截 12 个危险关键词：
      INSERT / UPDATE / DELETE / DROP / TRUNCATE / ALTER
      CREATE / GRANT / REVOKE / EXEC / EXECUTE / MERGE
    仅放行 SELECT / WITH 前缀语句
    字符串字面量豁免：WHERE status = 'DELETE' 等合法列值不会误报
```

### 技能管理（Skill）

```bash
GET    /api/v1/skills                 # 列出所有已注册 Skill（含类型：builtin / external-http）
POST   /api/v1/skills/register        # 动态注册外部 HTTP Skill（Body = ExternalSkillDescriptor JSON）
POST   /api/v1/skills/reload          # 从 skills.location 目录热加载 .yml 描述符
DELETE /api/v1/skills/{name}          # 注销指定 Skill
POST   /api/v1/skills/{name}/execute  # 执行指定 Skill，请求体为参数 Map
```

**外部 Skill 描述符格式**（放在 `skills.location` 目录，如 `~/.java-code-agent/skills/my-skill.yml`）：

```yaml
name: code-formatter
description: "格式化代码并返回结果"
parameterSchema:
  type: object
  properties:
    code:
      type: string
  required: [code]
execution:
  type: http
  url: http://localhost:9000/format
  method: POST
  timeoutSeconds: 30
  headers:
    Authorization: "Bearer your-token"
```

启动时自动加载，也可调用 `POST /api/v1/skills/reload` 运行时热加载。

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
| `SqlQuery` | DATABASE_READ | sql, max_rows | 只读 SQL 查询（SELECT/WITH），LLM 可在 Agentic Loop 中调用 |

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
| 新增技能（编译期） | 实现 `Skill` + `@Component` | SkillManager 同上 |
| 新增技能（运行时-文件） | 在 `skills.location` 目录放 `.yml` 描述符 | `ExternalSkillLoader` 扫描并注册 `HttpDelegatedSkill` |
| 新增技能（运行时-API） | `POST /api/v1/skills/register` JSON body | `SkillController` 即时注册，立即生效 |
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
                                      └──→ 回到 LLMClient（递归，最多可配置次数，默认 50 次）
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
| `READ_ONLY` | FILE_READ + DATABASE_READ | 隔离 Agent、只读探索 |
| `SAFE` | FILE_READ + FILE_WRITE + DATABASE_READ + GIT_OPERATION | 默认级别 |
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
| `PRE_COMMIT` | `GitTool.execute()` commit 命令执行前 | 可阻断 |
| `POST_COMMIT` | `GitTool.execute()` commit 命令成功后 | 通知型 |
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

### 9. 向量记忆检索

#### 架构（LangChain4j 集成）

```
POST /api/v1/memory（保存记忆）
    │
    └──► MemoryService.saveMemory()
              │ fire-and-forget（不阻塞响应）
              ▼ Schedulers.boundedElastic()
         EmbeddingModel.embed(name + description + content)   ◄── langchain4j-open-ai
              │ OpenAiEmbeddingModel（兼容 OpenAI/Ollama/Qwen/DeepSeek 等端点）
              ▼
         EmbeddingStore<TextSegment>.add(embedding, segment)   ◄── langchain4j-core / pgvector
              ├── dev profile: InMemoryEmbeddingStore（进程内，重启丢失，零依赖）
              └── postgres profile: PgVectorEmbeddingStore（持久化，重启不丢失，可水平扩展）
              Metadata 携带 userId/memoryId，检索时用 Filter 按用户隔离

GET /api/v1/memory/{userId}/search?keyword=...&mode=semantic
    │
    ▼ EmbeddingModel.embed(keyword)
    │
    ▼ EmbeddingStore.search(EmbeddingSearchRequest{queryEmbedding, maxResults, minScore, filter})
    │  pgvector 走 SQL 端 ANN 索引；InMemory 走库内实现的暴力扫描
    ▼
List<MemoryEntry>（按语义相似度排列）
```

**关键设计：**
- **EmbeddingModel**（langchain4j-open-ai 提供的 `OpenAiEmbeddingModel`）替代手写 `OpenAICompatibleEmbeddingClient`，`baseUrl`/`apiKey`/`modelName` 映射自现有 `memory.embedding.*` 配置，字段不变，兼容端点不变（OpenAI/阿里云通义/DeepSeek/Ollama/LM Studio）
- **EmbeddingStore\<TextSegment\>** 替代手写的 `MemoryEmbeddingStore`（双层 `ConcurrentHashMap` + 手写余弦相似度）：开发环境用 `InMemoryEmbeddingStore`（行为与旧实现等价，零依赖），生产 PostgreSQL profile 下用 `PgVectorEmbeddingStore`（真正的向量索引 + 持久化，解决"记忆量上去后线性扫描是瓶颈、重启后向量全部丢失需要重新计算"的问题）
- 两个 Bean 均通过 `@ConditionalOnProperty(memory.embedding.enabled=true)` 条件注册，`MemoryService` 用 `@Autowired(required=false)` 注入——未启用时保持 null，自动降级为关键词搜索，行为与现状完全一致
- **启动时后台批量补齐**：逻辑不变，仍通过 `Flux.flatMap(concurrency=4)` 异步为所有已加载记忆生成 embedding，不阻塞 Spring 初始化
- ⚠️ **beta 依赖风险**：`langchain4j-pgvector` 目前是 beta 版本，API 可能变动；`docker-compose-pg.yml` 中的 Postgres 镜像需换成 `pgvector/pgvector:pg16`（或手动 `CREATE EXTENSION vector`）才能使用

#### 配置

```yaml
memory:
  embedding:
    enabled: true                          # 开启向量检索
    provider: openai                       # 仅用于日志
    api-key: ${LLM_API_KEY:}             # 与 LLM 共用同一个 key
    model: text-embedding-3-small         # OpenAI: 1536 维
    endpoint: https://api.openai.com/v1  # 替换为实际基础 URL
    dimensions: 1536                       # 与 model 输出维度一致
    top-k: 5                               # 语义搜索默认返回条数
    similarity-threshold: 0.5            # 余弦相似度下限
```

**兼容的 Embedding 服务：**

| 服务 | 模型示例 | endpoint |
|------|---------|---------|
| OpenAI | `text-embedding-3-small`（1536d） | `https://api.openai.com/v1` |
| 阿里云通义 | `text-embedding-v3`（1024d） | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| DeepSeek | `deepseek-embedding`（1536d） | `https://api.deepseek.com/v1` |
| Ollama（≥0.4） | `nomic-embed-text`（768d） | `http://localhost:11434/v1` |
| LM Studio | 任意本地模型 | `http://localhost:1234/v1` |

> ⚠️ **注意**：Anthropic API 不提供 Embedding 端点，使用 Anthropic 作为 LLM 时请配置独立的 `memory.embedding.endpoint` 指向 OpenAI 或其他兼容服务。

#### API

```bash
# 关键词搜索（默认，memory.embedding.enabled 无影响）
GET /api/v1/memory/{userId}/search?keyword=Spring

# 语义向量搜索（需 memory.embedding.enabled=true）
GET /api/v1/memory/{userId}/search?keyword=如何优化性能&mode=semantic&topK=5

# 查询 Embedding 服务状态
GET /api/v1/memory/embedding-status
# 响应：{"enabled": true, "note": "Semantic search available: ..."}
```

`mode=semantic` 在 Embedding 服务未启用或查询向量生成失败时，自动静默降级为关键词搜索，不影响现有功能。

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

### 14. MCP 协议支持（LangChain4j 集成）

```
mcp.servers: "fs=http://localhost:3001,gh=http://localhost:3002"
                  │
                  ▼
            McpService.init()
                  │
      ┌───────────┴───────────┐
      │                       │
DefaultMcpClient("fs")   DefaultMcpClient("gh")   ◄── langchain4j-mcp
      │ StreamableHttpMcpTransport + listTools()
      │ 返回 List<ToolSpecification>
      ▼
McpProxyTool("mcp__fs__read_file")
      │ execute() → mcpClient.executeTool(ToolExecutionRequest)
      └──→ toolManager.registerTool()  ← 直接注册到 ToolManager
```

使用 LangChain4j `langchain4j-mcp`（**beta**）的 `DefaultMcpClient` + `StreamableHttpMcpTransport` 替代手写 JSON-RPC over `WebClient`，获得标准的通知/重连/工具列表缓存处理。工具命名规范不变：`mcp__{server-name}__{tool-name}`，避免与内置工具冲突；`Tool` 接口与 `ToolManager` 注册逻辑均未改动。

### 15. API 安全认证

**认证层级（双模式，可共存）：**

```
JwtAuthFilter (@Order 1)
    │  security.jwt.secret 已配置？
    │  YES → 验证 Bearer JWT → 提取 sub claim 为 userId → 写入 exchange attribute
    │  NO  → 直接放行（JWT 未启用）
    │
ApiKeyAuthFilter (@Order 2)
    │  security.api-key 已配置？
    │  YES → 验证 Bearer <key> 或 X-API-Key: <key>
    │  NO  → 直接放行（认证关闭）
```

**JWT 模式（推荐生产使用）：**

```yaml
security:
  jwt:
    secret: ${JWT_SECRET}     # 至少 32 字符
    ttl-hours: 24
```

```bash
# 1. 获取令牌
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId": "alice"}'
# → {"token": "eyJhbGc...", "userId": "alice"}

# 2. 携带令牌调用 API
curl -H "Authorization: Bearer eyJhbGc..." \
  http://localhost:8080/api/v1/chat/stream ...
```

userId 传递链（JWT 模式）：
```
JWT sub claim
    │ JwtAuthFilter.filter()
    ▼
exchange.getAttribute("authenticated-user-id")
    │ ConversationWebSocketHandler.extractUserId()
    ▼
ConversationRequest.userId → ContextBuilder → MemoryService / PermissionService
```

**API Key 模式（简单模式，向后兼容）：**

```
配置: security.api-key=your-secret-key（留空则关闭认证）
客户端（任选其一）：
  Authorization: Bearer your-secret-key
  X-API-Key: your-secret-key
```

公开路径（无需认证）：`/api/v1/health`、`/api/v1/auth/**`、`/h2-console/**`、`/actuator/**`

> **JWT + API Key 共存逻辑：** 当 JWT 认证成功后，`ApiKeyAuthFilter` 检测到 `authenticated-user-id` attribute 已存在时**直接跳过**，不会再验证 Bearer Token，避免 JWT 令牌被 API Key 过滤器误拒绝。

---

### 16. 多 Agent 协作分析架构

```
POST /api/v1/data-agent/multi-analysis
          │
          ▼ (boundedElastic)
    DataAgentPipeline.analyze()        ← NL2SQL 获取原始数据
          │
          ▼
    DataAnalysisAgent.process()        ← 编排器（虚拟线程池）
          │
    ┌─────┴─────┐ 并行 (Java 21 virtual threads)
    │           │
    ▼           ▼
AnomalyDetector  VolatilityAnalysis    ← 各自独立 LLM 调用
    │           │
    └─────┬─────┘
          ▼
    ReportGenerationAgent              ← 聚合两路结果生成 Markdown
          │
          ▼
    MultiAnalysisReport (JSON)
```

**各 Agent 职责：**

| Agent | 类型标识 | 核心输出 |
|-------|---------|---------|
| `DataAnalysisAgent` | `data-analysis` | 编排器，返回 `MultiAnalysisReport` JSON |
| `AnomalyDetectorAgent` | `anomaly-detector` | 异常列表 JSON 数组（≥3σ 离群值、空值、分布异常） |
| `VolatilityAnalysisAgent` | `volatility-analysis` | `{cv, trend, peakValue, troughValue, observations}` |
| `ReportGenerationAgent` | `report-generation` | 5 节 Markdown 综合报告（摘要/发现/异常/趋势/建议） |

---

### 17. 历史 SQL 缓存

```
Nl2SqlService.generateSql(question, schema)
    │
    ▼
SqlCacheService.get(normalizedKey)  ← LRU 500 条 + TTL 1h
    │
    ├── 命中 → 直接返回 Nl2SqlResult（跳过 LLM 调用）
    │
    └── 未命中 → LLMClient.chat() → parseNl2SqlResult()
                    │
                    ▼
              SqlCacheService.put(key, result)
```

Key 标准化：`trim → lowercase → 合并空白 → 移除中英文标点`，使语义相同但标点/大小写不同的问题命中同一缓存。

**线程安全实现：** 使用 `ConcurrentHashMap` 作为主存储 + `LinkedHashMap`（同步块）维护 LRU 顺序，通过 `computeIfPresent` 原子操作实现 get-check-remove，消除 `Collections.synchronizedMap` 的 TOCTOU 竞争。

---

## Data Agent 深度解析

> 数据分析智能体（Data Agent）的架构设计参考自两个开源项目：**DB-GPT**（Python + AWEL DAG 流水线）和 **spring-ai-alibaba**（Java + Spring AI StateGraph），结合 javacodeagent 技术栈（Java 21 + WebFlux + JDBC）实现了一套完整的 NL2SQL 数据分析流水线。

### 设计参考对比

| 维度 | DB-GPT | spring-ai-alibaba | javacodeagent |
|------|--------|-------------------|---------------|
| 语言 | Python 3.10+ | Java 21 / Spring Boot | Java 21 / Spring Boot |
| 流水线 | AWEL（Agentic Workflow Expression Language）DAG | StateGraph（有向图，类 LangGraph） | Reactor `Mono` 链式编排 |
| Agent 模型 | ConversableAgent + Action 插件机制 | ReactAgent + ToolNode + NodeAction | DataAnalysisAgent（Java 21 虚拟线程） |
| 数据源抽象 | `BaseConnector` → `RDBMSConnector`（SQLAlchemy） | `JdbcTemplate` + `DataSource` | `DataSourceConnector` 接口 + `JdbcDataSourceConnector` |
| SQL 生成 | LLM + SchemaLinkingOperator（RAG 增强） | LLM + 强制工具调用（`sql_db_schema`） | `Nl2SqlService`（关键词匹配，向量检索待实现） |
| 可视化 | GPT-Vis 协议（`VisChart` / `VisDashboard`） | 无内置，输出文本/JSON | GPT-Vis 8 种图表类型 + `DashboardSpec` |
| 多 Agent | ConversableAgent 链式（`receive → act → send`） | StateGraph 节点编排 + ReactAgent 循环 | Java 21 虚拟线程并行（`CompletableFuture.allOf`） |
| Excel 分析 | `Excel2TableAgent` + DuckDB 内存表 | — | `ExcelDataSourceConnector` → H2 内存表 |
| SQL 缓存 | — | — | `SqlCacheService`（LRU 500 + TTL 1h） |

---

### 核心数据流

```
用户自然语言问题
        │
        ▼
  ┌─────────────────────────────────────────────────────────┐
  │               DataAgentPipeline（Mono 链式编排）          │
  │  Schema 检索 → NL2SQL → SQL 校验 → 执行 → 洞察生成        │
  └─────────────────────────────────────────────────────────┘
        │
        ├──► SchemaRetriever（关键词匹配；表数 ≥50 时可切换 Embedding 向量检索）
        │       → 相关表 DDL + 样例行（CREATE TABLE + 3行数据，仿 Rajkumar et al. 2022）
        │
        ├──► Nl2SqlService（LLMClient）→ Nl2SqlResult { sql, display_type, thought }
        │       └── SqlCacheService（LRU 500 + TTL 1h）命中则跳过 LLM 调用
        │
        ├──► SqlValidator（执行前强制拦截，不可绕过）
        │       → 拦截 12 个危险关键词；单引号字面量豁免；行/块注释剥离防注入
        │
        ├──► SqlExecutor（Schedulers.boundedElastic()）→ DataQueryResult { columns, rows }
        │
        └──► InsightGenerator（LLMClient）→ InsightResult { chartSpec, markdown }
                → switchIfEmpty 降级：LLM 空内容时回退到 thought 字段文本
```

**分层架构（Data Agent 专用）：**

```
┌──────────────────────────────────────────────────────┐
│               接口层（REST / SSE）                     │
│  DataAgentController  /api/v1/data-agent/*            │
│  ExcelAnalysisController  /api/v1/data-agent/excel/*  │
└──────────────────────────┬───────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────┐
│               编排层（Pipeline）                       │
│  DataAgentPipeline（单图）                             │
│  DashboardGenerator（多图，Flux.flatMap 并行）          │
│  DataAnalysisAgent（多 Agent 协作，虚拟线程）           │
└──────────────────────────┬───────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────┐
│               核心能力层                               │
│  SchemaRetriever │ Nl2SqlService │ SqlCacheService    │
│  SqlValidator    │ SqlExecutor   │ InsightGenerator   │
└──────────────────────────┬───────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────┐
│               数据源层                                 │
│  JdbcDataSourceConnector（MySQL / PostgreSQL / H2）   │
│  ExcelDataSourceConnector（.xlsx / .csv → H2 内存表） │
└──────────────────────────────────────────────────────┘
```

---

### NL2SQL Prompt 工程

`Nl2SqlService` 使用以下结构化系统提示（参考 DB-GPT `HODatasourceRetrieverOperator` 模板）：

```
你是数据库专家。
数据库名：{db_name}
表结构定义：{table_info}
约束：
  1. 根据用户意图生成语法正确的 {dialect} SQL
  2. 除非用户指定行数，限制结果最多 {max_num_results} 条
  3. 只用提供的表，不能捏造列名
  4. 注意表与列的关系，检查 SQL 正确性，优化查询性能
  5. 从以下展示方式中选最优一种：
     response_line_chart / response_bar_chart / response_pie_chart /
     response_table / response_scatter_chart / response_area_chart /
     response_heatmap / response_donut_chart
用户问题：{user_input}
按以下 JSON 格式回答：
  {"thoughts": "...", "sql": "...", "display_type": "..."}
```

**Schema 表示格式（仿 Rajkumar et al. 2022）：**
```sql
CREATE TABLE "sales" (
  "region" VARCHAR,
  "month"  VARCHAR,
  "amount" BIGINT
)
/*
3 rows from sales:
region  month    amount
华东    2024-01  1200000
华南    2024-01   980000
华北    2024-01   750000
*/
```

**Schema 检索策略：**

| 场景 | 策略 | 实现 |
|------|------|------|
| 表数 ≤ 10 | 全量 Schema 直接传入 | `connector.getTableInfo(allTables)` |
| 表数 11-50 | 关键词匹配（问题词 ∩ 表名/列名） | `codePointCount ≥ 2` 过滤短词（修复 CJK 单字误过滤） |
| 表数 > 50 | 向量检索（待实现） | Embedding + ChromaDB/Milvus |

**DB-GPT StateGraph 的 Schema 获取流程（spring-ai-alibaba 参考实现）：**
```
list_tables（ListTablesNode）
  → call_get_schema（CallGetSchemaNode，internalToolExecutionEnabled=false）
  → get_schema（ToolNode，LLM 决定查哪些表）
  → generate_query（ReactAgent，携带完整 Schema，自动修正失败 SQL）
```

---

### GPT-Vis 可视化协议

参考 DB-GPT `VisChart` / `VisDashboard` 协议，javacodeagent 定义了前端可渲染的图表规格：

#### 单图（ChartSpec）

SSE 事件格式：
```json
{"type":"chart_data","spec":{"displayType":"response_line_chart","title":"月销售趋势","data":[{"month":"2024-01","amount":1200000}]}}
```

#### 多图 Dashboard（DashboardSpec）

```json
{
  "title": "销售综合看板",
  "displayStrategy": "default",
  "chartCount": 2,
  "charts": [
    {
      "sql": "SELECT region, SUM(amount) FROM sales GROUP BY region",
      "displayType": "response_bar_chart",
      "title": "地区销售额",
      "thought": "按地区汇总本月销售",
      "data": [{"region": "华东", "amount": 1200000}],
      "errMsg": null
    },
    {
      "sql": "SELECT month, COUNT(*) FROM orders GROUP BY month",
      "displayType": "response_line_chart",
      "title": "月订单量趋势",
      "thought": "过去12个月订单量变化",
      "data": [{"month": "2024-01", "count": 3240}],
      "errMsg": null
    }
  ]
}
```

> 参考 DB-GPT `VisDashboard`：单图 SQL 执行失败时，`errMsg` 字段填写错误原因，其余图表正常渲染，不阻断整个 Dashboard。`DashboardGenerator` 以 `Flux.flatMap(concurrency=4)` 并行执行各图表 SQL，与 Prompt 中约定的 2-4 图表并发上限一致。

---

### Excel/CSV 分析路径

参考 DB-GPT `Excel2TableAgent`（DuckDB 路径），javacodeagent 使用 H2 内存表实现等价方案：

```
Excel/CSV 上传
    │
    ▼ ExcelDataSourceConnector.importFile()（@Transactional，中途失败整体回滚）
    ├── 读取列名 + 样例行（3行）
    ├── 检测 CJK 字符 → translateChineseHeaders()
    │       批量 LLM 翻译为英文 snake_case；失败降级为 col_0, col_1, ...
    ├── H2 建表："tbl_<uuid12>"（带双引号，防大小写冲突）
    └── 批量写入所有行数据
            │
            ▼ ExcelAnalysisController.queryExcel()
    标准 NL2SQL 管道（SqlValidator + SqlExecutor + InsightGenerator 完全复用）
```

**DB-GPT 对比设计启示：**
- DB-GPT 路径 A（`Excel2TableAgent`）：Excel → DuckDB → 标准 NL2SQL 分析
- DB-GPT 路径 B（`chat_excel`）：`excel_reader.read_from_df()` + DuckDB 内存注册 + 直接 SQL 查询
- javacodeagent：Apache POI / Commons CSV + H2 内存表，复用现有 JDBC 管道，无需引入 DuckDB 依赖

---

### 多 Agent 协作模式

参考 DB-GPT `ConversableAgent` 链式协作（MetricInfoRetriever → AnomalyDetector → VolatilityAnalysis → Report），javacodeagent 使用 Java 21 虚拟线程并行实现：

```
DataAgentPipeline.analyze()    ← NL2SQL 管道获取原始指标数据
        │
        ▼ DataAnalysisAgent（Executors.newVirtualThreadPerTaskExecutor()）
        │
    ┌───┴──── 并行（Virtual Thread）────┐
    │                                  │
    ▼                                  ▼
AnomalyDetectorAgent           VolatilityAnalysisAgent
  目标：检测统计异常              目标：计算 CV/趋势/极值
  约束：>3σ 离群值/空值/          约束：从候选维度中选最相关
        分布异常                        维度做贡献率分析
  输出：JSON 数组                  输出：JSON 对象
    │                                  │
    └──────────── 合并 ────────────────┘
                    │
                    ▼ ReportGenerationAgent（串行，等待两路完成）
              整合两路结论 → 5 节 Markdown 综合报告
              （执行摘要/关键发现/异常详情/波动趋势/行动建议）
```

**各 Agent Prompt 要点（参考 DB-GPT `ProfileConfig` 设计模式）：**

| Agent | 核心目标 | 输出格式 | 重试机制 |
|-------|---------|---------|---------|
| `AnomalyDetectorAgent` | 检测 >3σ 离群值、空值列、值域异常 | JSON 数组（贪婪 `\\[[\\s\\S]*]` 正则提取） | 无（单次调用） |
| `VolatilityAnalysisAgent` | 计算 CV、趋势方向、极值、波动区间 | JSON 对象（`indexOf/lastIndexOf` 贪婪提取） | 无（单次调用） |
| `ReportGenerationAgent` | 整合两路结论，面向业务用户 | Markdown（5 节结构化报告） | 无（单次调用） |

> **线程安全注意**：Agent 返回值通过 `CompletableFuture.get()` 收集后传入 `ReportGenerationAgent`，`DataAnalysisAgent` 的入参 `metrics` Map 使用 `new HashMap<>`（而非 `Map.of()`），以支持 LLM 返回 null 值时安全 `put()`。

---

### AWEL 流水线（设计参考）

DB-GPT 的 AWEL DAG 是 `DataAgentPipeline` 的设计原型，以下对照关系展示了 Python 算子到 Java Mono 链的映射：

| DB-GPT AWEL 算子 | javacodeagent 等价实现 |
|-----------------|----------------------|
| `SchemaLinkingOperator`（RAG 向量检索） | `SchemaRetriever.retrieve()`（关键词匹配，RAG 待实现） |
| `SqlGenOperator`（LLM → SQL 字符串） | `Nl2SqlService.generateSql()` |
| `SqlExecOperator`（→ DataFrame） | `SqlExecutor.execute()` + `DataQueryResult` |
| `ChartDrawOperator`（GPT-Vis 渲染） | `ChartSpec.from()` + SSE 事件推送 |
| `HODatasourceDashboardOperator`（多图） | `DashboardGenerator.generate()` |
| `JoinOperator`（合并上下文） | Reactor `flatMap` 链中隐式传递 schema 字符串 |

**完整 AWEL DAG 示意（参考原始流水线）：**
```
HttpTrigger（POST /query）
    │
    ▼
SchemaLinkingOperator（RAG → 相关 Schema）
    │
    └──► JoinOperator（question + schema → prompt）
                │
                ▼
         NL2SQL Operator（LLM → SQL + display_type）
                │
                ▼
         SqlExecOperator（执行 → DataFrame/DataQueryResult）
                │
                ▼
         ChartDrawOperator / InsightGenerator（可视化 + 洞察）
```

---

### Data Agent 关键设计决策

**为何先检索 Schema 再生成 SQL？**  
表数量超过 30 张后，全量 Schema 超出 LLM 上下文窗口（8K-32K token）。`SchemaRetriever` 仅取相关 3-5 张表的 DDL + 样例行，token 消耗减少约 80%，SQL 准确率反而更高（上下文更聚焦）。关键词匹配适用于表数 <50 的场景；更多时切换 Embedding 向量检索（LangChain4j `EmbeddingStore<TextSegment>`，开发环境 InMemory / 生产 PostgreSQL 环境 PgVector）。

**为何 `display_type` 由 LLM 选择而非规则？**  
同一份数据既可用折线图（看趋势）也可用饼图（看占比），规则无法覆盖所有语义意图。Prompt 给出 8 种图表的语义说明（line=趋势、pie=比例、scatter=异常检测…），引导 LLM 做有根据的选择。

**为何 SQL 安全用 Prompt + 工具层双重防护？**  
Prompt 约束（"只生成 SELECT"）不可靠——LLM 在边缘情况下可能生成 DML。`SqlValidator` 在执行前强制拦截，是不可绕过的安全边界；同时对字符串字面量豁免（`WHERE status = 'DELETE'` 不误报），并通过行/块注释剥离防止注释绕过堆叠查询。

**为何流水线返回 `Mono<>` 而非同步？**  
Schema 检索可能涉及向量库 HTTP 调用，SQL 执行是阻塞 JDBC（走 `boundedElastic`），LLM 调用是远程 HTTP。全链路 `Mono` 组合确保不阻塞 Netty IO 线程，支持并发多用户同时分析。

**为何 Excel 用 H2 内存表而非直接对 DataFrame 提问？**  
直接对 DataFrame 提问（PandasAI 模式）限制 LLM 只能生成 Python 代码，难以控制安全边界。将 Excel 导入 H2 后，可复用同一套 `SqlValidator + SqlExecutor` 管道，安全拦截和图表输出格式与标准 NL2SQL 完全一致，不引入额外代码路径。

**为何 Dashboard 由 LLM 一次性规划多条 SQL？**  
Dashboard 需从不同维度呈现数据（趋势 + 分布 + 排名），每个维度的聚合方式和时间粒度不同。LLM 一次性规划比规则拼接更灵活，且能理解图表间的语义关联（"总量折线 + 构成饼图"）。`Flux.flatMap(concurrency=4)` 并行执行，单图失败以 `errMsg` 隔离，不阻断其余图表渲染。

**为何多 Agent 协作用虚拟线程而非 WebFlux？**  
`AnomalyDetectorAgent` 和 `VolatilityAnalysisAgent` 各自调用 LLM（阻塞 HTTP），需要等待两路结果才能交给 `ReportGenerationAgent`。Java 21 虚拟线程 `CompletableFuture.allOf` 表达 fan-out/gather 语义比 `Mono.zip` 更简洁，且在 `Schedulers.boundedElastic()` 线程中调用 `.block()` 是安全的。

**为何 `Nl2SqlResult.sql` 要做非空检查？**  
LLM 可以成功响应（HTTP 200）但在 JSON 中返回 `"sql":""` 空字符串。若直接传给 `SqlExecutor` 则产生模糊的 JDBC 错误（`StatementCallback; bad SQL grammar`），对排查毫无帮助。`Nl2SqlService.generateSqlFromLlm()` 在 `map` 阶段检测空 SQL 并抛出 `IllegalStateException("LLM generated empty SQL")`，让错误在来源处就可被定位。

---

### 多数据源管理

`DataSourceManager` 维护运行时数据源注册表，支持同时连接多个异构数据库：

```bash
# 列出所有数据源
GET /api/v1/data-agent/datasources

# 动态注册外部数据源
POST /api/v1/data-agent/datasources
{
  "id": "prod-mysql",
  "url": "jdbc:mysql://host:3306/sales",
  "username": "ro_user",
  "password": "secret",
  "dialect": "mysql",
  "dbName": "sales",
  "description": "生产 MySQL 销售数据库"
}

# 针对指定数据源查询
POST /api/v1/data-agent/query
{"question": "各地区月销售额", "dataSourceId": "prod-mysql"}

# 获取指定数据源的 Schema
GET /api/v1/data-agent/schema?dataSourceId=prod-mysql

# 注销数据源（default 不能注销）
DELETE /api/v1/data-agent/datasources/prod-mysql
```

Schema 向量索引（`memory.embedding.enabled=true` 时）也按 `dataSourceId` 分开维护，不同数据源的表向量互不干扰。表结构变更后调用 `POST /datasources/{id}/invalidate-schema-index` 触发重建。

---

### 指标体系集成

仿 DB-GPT 归因分析链路（`MetricInfoRetriever → AnomalyDetector → VolatilityAnalysis → Report`）：

```
POST /api/v1/data-agent/metrics  # 注册指标定义
{
  "name":        "daily_order_count",
  "displayName": "日订单量",
  "table":       "orders",
  "valueColumn": "order_id",
  "timeColumn":  "created_at",
  "aggregation": "COUNT",
  "dimensions":  ["region", "product_type"],
  "description": "每日新增订单数量"
}

POST /api/v1/data-agent/metric-analysis  # 完整归因分析
{"metricName": "daily_order_count", "lookbackDays": 30}
```

**响应（MetricAnalysisReport）：**
```json
{
  "metricName":    "daily_order_count",
  "displayName":   "日订单量",
  "lookbackDays":  30,
  "currentValue":  1842,
  "dimensions":    ["region", "product_type"],
  "historicalData": [{"dt":"2024-12-01","value":1230}, ...],
  "anomalies":     ["2024-12-25 value=3840 — 3.1× above 30d mean"],
  "volatilityMetrics": {"cv": 0.28, "trend": "increasing", "peakValue": 3840},
  "reportMarkdown": "## 执行摘要\n...",
  "success": true
}
```

**归因分析流水线：**
```
MetricInfoRetriever.getMetricInfo("daily_order_count", 30)
    │  ① 查询当前聚合值（SELECT COUNT(order_id) FROM orders）
    │  ② 查询 30 天历史趋势（GROUP BY DATE(created_at)）
    ▼
MetricAnalysisContext（当前值 + 历史趋势 + 候选维度）
    │
    ├── AnomalyDetectorAgent    ─┐  并行（Java 21 虚拟线程）
    ├── VolatilityAnalysisAgent ─┘
    │
    └── ReportGenerationAgent → MetricAnalysisReport（Markdown 综合报告）
```

**与 `POST /multi-analysis` 的区别：**

| 端点 | 输入 | 特点 |
|------|------|------|
| `/multi-analysis` | 自然语言问题 | 先 NL2SQL 再分析，灵活但无法预定义指标语义 |
| `/metric-analysis` | 预定义指标名 | 指标定义完整（聚合函数/时间列/维度），SQL 精确，适合持续监控场景 |

---

### 慢查询日志 + Micrometer 指标

```yaml
data-agent:
  slow-query-threshold-ms: 2000  # 超过 2s 记录 WARN 日志

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

```bash
# 查询 SQL 执行耗时 P99/P95/AVG
GET /actuator/metrics/data_agent_query_duration_seconds

# 按状态（success/error）分别查看
GET /actuator/metrics/data_agent_query_duration_seconds?tag=status:success
```

慢查询 WARN 日志格式：
```
[SLOW QUERY] 3421ms (threshold=2000ms): SELECT region, month, SUM(amount) FROM sales GROUP BY ...
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

**为何需要 `spring.main.web-application-type: reactive`？**  
项目同时使用 `spring-boot-starter-webflux`（WebFlux / Netty）和 `spring-boot-starter-data-jpa`（JPA / Hibernate）。JPA starter 会传递引入 `spring-boot-starter-tomcat`（Servlet 容器），导致 Spring Boot 自动探测到 Servlet 栈后选择 Tomcat 启动，Netty 不会启动，所有 `WebFilter` Bean（`JwtAuthFilter`、`ApiKeyAuthFilter`）和响应式端点将全部失效。显式配置此属性可强制 Spring Boot 使用 Reactive 模式。

**为何 `userId` 依赖请求头而非 Spring Security？**  
项目使用 WebFlux，未引入 Spring Security；`X-User-Id` 请求头方案实现简单且与认证（API Key）解耦，方便未来替换为 JWT claim 提取，无需修改下游代码。

**为何向量存储改用 LangChain4j `EmbeddingStore`（InMemory + PgVector）而非继续手写？**  
手写的 `MemoryEmbeddingStore`/`SchemaRetriever` 向量索引本质是同一套逻辑重复了两遍（双层 `ConcurrentHashMap` + 手写余弦相似度），且是纯内存实现：记忆/Schema 索引量上去后是 O(n) 线性扫描，重启后向量全部丢失、需要后台重新计算。LangChain4j 的 `EmbeddingStore<TextSegment>` 提供统一抽象，开发环境用 `InMemoryEmbeddingStore` 保持零依赖（行为等价于旧实现），生产 PostgreSQL profile 下换成 `PgVectorEmbeddingStore` 即获得真正的持久化和向量索引，不用自己再实现一遍。代价是 `langchain4j-pgvector` 目前是 beta 版本，API 可能变动。

**为何 MCP 客户端改用 `langchain4j-mcp`？**  
手写的 `HttpMcpClient` 只实现了最基本的 JSON-RPC over HTTP（`.block()` 同步调用），不支持工具列表变更通知、连接断开重连、stdio transport（本地进程型 MCP Server）。这些协议边界情况 `langchain4j-mcp` 的 `DefaultMcpClient` 已经处理好，没必要自己重新踩一遍坑。代价同样是该模块目前仍是 beta 版本。

---

## 与 Claude Code 对比

| 能力 | Claude Code | Java Code Agent |
|------|------------|----------------|
| Agentic Loop（可配置深度，默认 50 轮） | ✅ | ✅ |
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
| **向量记忆检索（embedding）** | ✅ | ✅ |
| **容器化（Dockerfile）** | ✅ | ✅ |
| **生产级 JWT 认证** | ✅ | ✅ |
| **数据分析智能体（NL2SQL 全流水线）** | ✅ | ✅ |
| **Excel/CSV 文件分析** | ✅ | ✅ |
| **Excel 中文列名 LLM 翻译** | ✅ | ✅ |
| **GPT-Vis 8 种图表类型** | ✅ | ✅ |
| **多图 Dashboard** | ✅ | ✅ |
| **历史 SQL 缓存（LRU+TTL）** | ✅ | ✅ |
| **多 Agent 协作分析（归因/异常检测）** | ✅ | ✅ |
| **多数据源管理（动态注册/切换）** | ✅ | ✅ |
| **查询超时告警 + 慢查询日志（Micrometer）** | ✅ | ✅ |
| **指标体系归因分析（MetricInfoRetriever）** | ✅ | ✅ |
| **Schema Linking 向量检索** | ✅ | ✅ |

---

## 开发路线图

### 已完成

- [x] 基础框架（Spring Boot 3.2.5 + WebFlux）
- [x] Anthropic / OpenAI 兼容 LLM 客户端（Token 级流式）
- [x] Agentic Loop（消息格式符合 API 规范，最多 10 轮）
- [x] 工具系统（Read / Write / Edit / Glob / Grep / List / Bash / Git / SqlQuery）
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
- [x] **数据分析智能体（Data Agent）** — NL2SQL 全流水线（Schema 检索 → SQL 生成 → 执行 → 洞察）
- [x] **Excel/CSV 文件分析** — Apache POI + Commons CSV 导入 H2 内存表，复用 NL2SQL 管道
- [x] **GPT-Vis 可视化协议** — 8 种图表类型（line / bar / pie / table / scatter / area / heatmap / donut）
- [x] **Dashboard 多图** — LLM 规划 2-4 张互补图表，错误隔离（err_msg 字段）
- [x] **SqlQueryTool** — LLM 在 Agentic Loop 中可调用只读 SQL 查询工具
- [x] **Data Agent 测试** — SqlValidator、ExcelDataSourceConnector、DataAgentPipeline 集成测试
- [x] **代码质量修复（第三轮检视）** — PostgreSQL 标识符双引号修正；`extractJson()` 支持 markdown 包裹；`ExcelAnalysisController` 使用 InsightGenerator 生成真正洞察；`SqlValidator` 字符串字面量豁免；`DashboardGenerator` Builder 模式
- [x] **代码质量修复（第四轮检视）** — `SqlValidator` 12 个 Pattern 预编译（消除每次 validate 的 12 次 compile 开销）；`JdbcDataSourceConnector.executeQuery()` 接入 `setQueryTimeout()` + 子查询 LIMIT 括号深度检测；`ExcelAnalysisController.upload()` DataBuffer 内存泄漏修复；`DataAgentPipeline.jsonStr()` 补全 `\t\b\f` 转义；`DashboardGenerator.extractJsonArray()` 非贪婪正则；`ExcelDataSourceConnector` 表名改用 UUID 防碰撞；`PermissionType` 新增 `DATABASE_READ`；`SqlQueryTool` 权限语义修正；`Nl2SqlService.extractJson()` 成对 markdown 代码块正则；新增 `JdbcDataSourceConnectorTest`
- [x] **代码质量修复（第五轮检视）** — 新增 `DataAgentConstants` 常量类，消除 10 个文件中的硬编码魔法值（200 / 30s / 3 / 10 / `"system"` / conversationId 前缀等）；`DashboardGenerator` 串行 SQL 改为 `Flux.flatMap` 并行执行；`InsightGenerator` 空数据消息语言自适应；`DataAnalysisReport.error()` 新增携带 question 的重载；`ChartSpec.from()` 增加 null guard；`SchemaRetriever` 关键词过滤改用 `codePointCount` 修复中文单字被误过滤 bug
- [x] **容器化** — `Dockerfile`（maven:3.9 多阶段构建 + JRE 21 非 root 运行）；`docker-compose.yml`（H2 内嵌，开箱即用）；`docker-compose-pg.yml`（PostgreSQL 外部数据源，健康检查，持久化 volume）；`application-postgres.yml`；`DataAgentConfig` 支持 `data-agent.datasource.*` 可选外部数据源
- [x] **生产级 JWT 认证** — `JwtService`（HMAC-SHA256，sub claim 存 userId，TTL 可配）；`JwtAuthFilter`（`@Order(1)` WebFilter，认证后写 exchange attribute）；`AuthController`（`POST /api/v1/auth/token`，公开路径）；`application.yml` 新增 `security.jwt.*` 配置节；`ConversationWebSocketHandler.extractUserId()` 优先读 JWT attribute
- [x] **多 Agent 协作分析** — `AnomalyDetectorAgent`（检测统计异常/离群值）；`VolatilityAnalysisAgent`（计算 CV / 趋势 / 极值）；`ReportGenerationAgent`（汇编 Markdown 综合报告）；`DataAnalysisAgent`（编排器，虚拟线程并行）；`MultiAnalysisReport` 模型；`POST /api/v1/data-agent/multi-analysis` 端点
- [x] **Excel 中文列名翻译** — `ExcelDataSourceConnector` 检测 CJK 字符，调用 LLM 批量翻译为 snake_case 英文（`translateChineseHeaders()`），翻译失败自动降级；适用 .xlsx 和 .csv
- [x] **历史 SQL 缓存** — `SqlCacheService`（LRU 500 条 + TTL 1h，`ConcurrentHashMap` + `LinkedHashMap` 双结构 LRU；`computeIfPresent` 原子 TTL 检查）；`Nl2SqlService.generateSql()` 先查缓存再调 LLM，命中时跳过 LLM 调用
- [x] **自迭代检视修复（第六轮）** — `ApiKeyAuthFilter` 补充 `/api/v1/auth` 公开路径（修复登录端点被锁定 bug）；JWT 已认证请求跳过 ApiKey 二次校验（修复双重认证冲突）；`ExcelDataSourceConnector.getColumns()` 去掉 `.toUpperCase()`（修复 H2 引号表名大小写不匹配导致 Schema 查空）；`DataAgentConfig` 改用 `DataSourceBuilder`（修复 `DriverManagerDataSource` 无连接池的性能问题）；`importExcel/importCsv` 加 `@Transactional`（修复批量导入部分失败留脏数据）；`SqlCacheService` 改 `ConcurrentHashMap` 原子操作（修复 TOCTOU 线程安全）；`DataAnalysisAgent` `Map.of()` 改 `HashMap`（防 null 值 NPE）；`DataAgentConstants` 新增 `CONV_PREFIX_ANOMALY/VOLATILITY/REPORT`（规范 Agent 会话 ID 前缀）；三 Agent 类调用处的 LLM block 改为 null-safe；`.env.example` 新增 Docker 环境变量模板
- [x] **自迭代检视修复（第七轮）** — `application.yml` 补充 `spring.main.web-application-type: reactive`（修复 JPA 引入 Tomcat 后 Spring Boot 误以 Servlet 模式启动，WebFilter/WebFlux 失效的根本问题）；`JdbcDataSourceConnector` 补全 `import java.time.Duration`（编译错误修复）；`ConversationController.extractUserId/resolveUserId` 优先读 JWT exchange attribute（修复 JWT 模式下 userId 仍从 X-User-Id 头取值导致身份绕过）；`ExcelDataSourceConnector.importFile()` 改为 `@Transactional` 公共方法（Spring AOP 代理只拦截 public 方法，原 private 方法上的 @Transactional 被静默忽略无法回滚）；`JwtService` 短密钥补充 WARN 日志（密钥 <32 字节时告警运维）；`DataAgentController.multiAnalysis` 增加 `result.getOutput()` 空值检查；`JdbcDataSourceConnector.hasOuterLimit()` 改用 `Character.isWhitespace` + `regionMatches`（修复换行/制表符前导 LIMIT 未被识别导致 SQL 末尾重复追加 LIMIT）；`ConversationController` 对话 CRUD 接口用 `Mono.fromCallable().subscribeOn(boundedElastic)` 封装 JPA 阻塞调用（防止在 Netty IO 线程直接执行阻塞 SQL）；`DashboardGenerator.extractJsonArray()` 修正误导性"非贪婪"注释（贪婪匹配才是正确行为：从首 `[` 到末 `]` 捕获完整外层数组）
- [x] **自迭代检视修复（第八轮）** — `ConversationManager.processWithToolCalls()` 编译错误：lambda 参数 `response`（`LLMResponse`）与函数体内局部变量 `ConversationResponse response` 同名，Java 禁止 lambda 体内重新声明与参数同名的变量；重命名局部变量为 `conversationResponse` 修复；`ConversationManager.processMessage()` 同步调用 `messagePersistence.loadHistory()` 阻塞 Netty IO 线程，改为 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()).flatMap(...)`；`ConversationManager.processMessageStream()` 同样修复，改为 `.subscribeOn(boundedElastic()).flatMapMany(...)`；`ConversationManager.processWithToolCalls()` 在 `llmClient.chat()` 后增加 `.publishOn(Schedulers.boundedElastic())`（WebClient 在 Netty IO 线程回调，`flatMap` 中的 `persistNewMessages()`/`hookManager.triggerHook()`/`handleToolCalls()` 等阻塞操作必须在弹性池执行）；`ConversationManager.processStreamChunks()` 将 `subscribeOn` 改为 `publishOn`（`subscribeOn` 仅影响冷源订阅线程，不改变 WebClient 推送 chunk 的执行线程；`concatMap` 中阻塞工具调用仍在 Netty IO 线程）；`ExcelAnalysisController.queryExcel()` 将同步 JDBC 调用 `excelConnector.getTableInfo(tableName)` 改为 `Mono.fromCallable(...).subscribeOn(boundedElastic()).flatMap(...)`；`ExcelDataSourceConnector.query()` LIMIT 检测改用深度感知 `hasOuterLimit()` 方法（原 `contains(" LIMIT ")` 漏判 `\nLIMIT`/`\tLIMIT` 等空白前导形式）；`AnomalyDetectorAgent.JSON_ARRAY` Pattern 由非贪婪 `\\[.*?\\]`（DOTALL）改为贪婪 `\\[[\\s\\S]*]`（修复数组元素字符串含 `]` 字符时提前截断的 bug）；`DataAgentPipeline.jsonStr()` 补全 RFC 8259 要求的 0x00–0x1F 控制字符全量转义（NUL/BEL/VT 等用 `\\uXXXX`；缺失转义会导致 SSE 客户端 JSON 解析失败）；`Nl2SqlService.extractJson()` 兜底改为 `indexOf('{')`/`lastIndexOf('}')` 贪婪提取（原回退正则最多支持 2 层嵌套，`thoughts` 字段含 SQL 模板 `{}` 或 LLM 输出含深层对象时解析失败）；`VolatilityAnalysisAgent` 删除 `JSON_OBJ` Pattern 字段，`parseJsonObject()` 改用 `indexOf('{')`/`lastIndexOf('}')` 贪婪提取（原非贪婪 `\\{.*?\\}` 含 `metadata` 子键等嵌套对象时提前截断）；同步清理已无用的 `import java.util.regex.Matcher` 和 `import java.util.regex.Pattern`

- [x] **自迭代 Bug 修复（第九轮）** — `AgentConfig` 新增 `max-tool-call-depth`，`ConversationManager` 由硬编码 `10` 改为读 `AgentConfig.getMaxToolCallDepth()`（默认 50，`application.yml` 可覆盖）；`SkillConfig` 绑定 `skills.*` 配置；`ExternalSkillDescriptor`（YAML 描述符模型）+ `HttpDelegatedSkill`（HTTP 委托）+ `ExternalSkillLoader`（目录扫描，`@PostConstruct` + 热加载 `reload()`）+ `SkillController`（`/api/v1/skills` GET/POST/DELETE/execute 管理 API）——完整实现 Skill 三种扩展方式（@Component / YAML文件 / REST API）；`ConversationController` 移除 Skill 职责（迁到 SkillController），保持单一职责；`AgenticLoopIntegrationTest` 添加 `agent.max-tool-call-depth=3` 测试属性（防止默认 50 导致测试慢 5× 以上）；`SkillManager` 增加 `unregisterSkill/listSkills/containsSkill` 管理方法；`jackson-dataformat-yaml` 加入 pom.xml（外部 Skill YAML 解析依赖）；`DataAgentPipeline.analyzeStream()` 事件补充 `data: ...\n\n` SSE 前缀（修复 `/query/stream` 输出裸 JSON 的 SSE 格式不合规 bug）；`SkillManager` 消除方法体内 `java.util.Collection/Collections` 内联全限定名（改为正式 import）

- [x] **自迭代检视修复（第十轮）** — 安全层：`MemoryService.persistMemoryToFile()` 新增 `sanitizeFileName()` 防止 entry.name 含路径分隔符造成目录逃逸；`FilePathResolver.resolve()` 当 workingDirectory 为 null 时拒绝绝对路径（原来直接放行）；`SqlValidator` 增加行注释/块注释剥离 + 分号检测（防 PostgreSQL/H2 堆叠查询绕过）；`JwtAuthFilter` 修复：无 Bearer 头时放行给 ApiKeyFilter（原来直接返回 401，导致 JWT+ApiKey 双模式互斥）；`ApiKeyAuthFilter` 补充 `@Order(2)` 显式排序（修复 Bean 注册顺序不确定性）。运行时 Bug：`ContextCompressor.compress()` 新增 `Math.min(keepRecent, size-1)` 防止 keepRecent ≥ 消息数时 `subList` 越界；`summarizationContext` 补充 `.availableTools(List.of()).permissionLevel(...)` 防 NPE；`PlanService.rejectPlan()` 使用 `PlanStatus.REJECTED` 而非 DRAFT（修复 REJECTED 枚举值从未被设置的逻辑 Bug）；`executePlan()` 守卫改为只允许 APPROVED/IN_PROGRESS（原来 FAILED/REJECTED 状态下也能执行）；`BashTool.execute()` 增加 command 空值检查；`BashTool/GitTool` 输出流改为显式 UTF-8 charset（修复中文命令输出乱码）；`GrepTool.execute()` 增加 pattern 空值检查。性能/资源：`ReadTool` 由 `Files.readAllLines()` 改为 `Files.lines()` 懒读（防大文件 OOM）；`GrepTool/GlobTool` `Files.walk` 加最大深度 12（防文件系统根目录全量遍历 DoS）；`AnthropicLLMClient` thinking 配置修正为 `{"type":"enabled","budget_tokens":N}`（原 `"adaptive"` 不是合法 API 值）；`LLMConfig` 新增 `thinkingBudgetTokens` 可配字段（默认 8000）。空值安全：`Nl2SqlService` LLM 响应加 filter+switchIfEmpty 防 null content NPE；`InsightGenerator` 同步修复 + fallback null guard；`DataAgentPipeline.analyze()` 入口增加 question 非空校验 + error message null-safe；`DashboardGenerator.flatMap` 并发上限改为 4（与 prompt 约定的 2-4 图表一致）。测试修复：`ExcelDataSourceConnectorTest` 改为 `@MockBean LLMClient` + 注入 `ObjectMapper`，使用正确的三参数构造器（原单参数构造器不存在，测试无法编译）；所有测试方法注册 `createdTables` 并在 `@AfterEach` DROP，修复 H2 跨测试状态污染；表名长度断言改为 `4 + DataAgentConstants.TABLE_NAME_UUID_LENGTH`（与常量解耦）

- [x] **自迭代检视修复（第十轮）** — 安全层：`MemoryService.persistMemoryToFile()` 新增 `sanitizeFileName()` 防止 entry.name 含路径分隔符造成目录逃逸；`FilePathResolver.resolve()` 当 workingDirectory 为 null 时拒绝绝对路径（原来直接放行）；`SqlValidator` 增加行注释/块注释剥离 + 分号检测（防 PostgreSQL/H2 堆叠查询绕过）；`JwtAuthFilter` 修复：无 Bearer 头时放行给 ApiKeyFilter（原来直接返回 401，导致 JWT+ApiKey 双模式互斥）；`ApiKeyAuthFilter` 补充 `@Order(2)` 显式排序（修复 Bean 注册顺序不确定性）。运行时 Bug：`ContextCompressor.compress()` 新增 `Math.min(keepRecent, size-1)` 防止 keepRecent ≥ 消息数时 `subList` 越界；`summarizationContext` 补充 `.availableTools(List.of()).permissionLevel(...)` 防 NPE；`PlanService.rejectPlan()` 使用 `PlanStatus.REJECTED` 而非 DRAFT（修复 REJECTED 枚举值从未被设置的逻辑 Bug）；`executePlan()` 守卫改为只允许 APPROVED/IN_PROGRESS（原来 FAILED/REJECTED 状态下也能执行）；`BashTool.execute()` 增加 command 空值检查；`BashTool/GitTool` 输出流改为显式 UTF-8 charset（修复中文命令输出乱码）；`GrepTool.execute()` 增加 pattern 空值检查。性能/资源：`ReadTool` 由 `Files.readAllLines()` 改为 `Files.lines()` 懒读（防大文件 OOM）；`GrepTool/GlobTool` `Files.walk` 加最大深度 12（防文件系统根目录全量遍历 DoS）；`AnthropicLLMClient` thinking 配置修正为 `{"type":"enabled","budget_tokens":N}`（原 `"adaptive"` 不是合法 API 值）；`LLMConfig` 新增 `thinkingBudgetTokens` 可配字段（默认 8000）。空值安全：`Nl2SqlService` LLM 响应加 filter+switchIfEmpty 防 null content NPE；`InsightGenerator` 同步修复 + fallback null guard；`DataAgentPipeline.analyze()` 入口增加 question 非空校验 + error message null-safe；`DashboardGenerator.flatMap` 并发上限改为 4（与 prompt 约定的 2-4 图表一致）。测试修复：`ExcelDataSourceConnectorTest` 改为 `@MockBean LLMClient` + 注入 `ObjectMapper`，使用正确的三参数构造器（原单参数构造器不存在，测试无法编译）；所有测试方法注册 `createdTables` 并在 `@AfterEach` DROP，修复 H2 跨测试状态污染；表名长度断言改为 `4 + DataAgentConstants.TABLE_NAME_UUID_LENGTH`（与常量解耦）

- [x] **自迭代检视修复（第十一轮）** — 权限层：`PermissionService.checkPermissionLevel()` 扩充 `READ_ONLY` 涵盖 `DATABASE_READ`，`SAFE` 涵盖 `DATABASE_READ` + `GIT_OPERATION`（原 `SAFE` 级别只允许文件读写，导致 `SqlQueryTool`/`GitTool` 在默认 SAFE 权限下被拒绝，需手动配置 `ALL` 才能使用）；`application.yml auto-approve` 移除无效的 `glob`/`grep` 枚举值（这两个字符串在 `PermissionType` 中不存在，`valueOf()` 抛 `IllegalArgumentException` 被 catch 静默忽略，配置行实际无效，改为 `database-read`，与 `SqlQueryTool` 权限对齐）；`ToolManager.executeToolCall()` pre-hook 创建时 `toolCall.getInput()` 可能为 null 导致 `Map.of()` NPE，改为 null-safe 取值（`inputForHook = input != null ? input : Map.of()`）；`Nl2SqlService.generateSqlFromLlm()` 在 LLM 响应 map 阶段增加空 SQL 检测（`sql == null || isBlank()`），使上层能以 `IllegalStateException` 形式捕获并返回可读错误，而非在 `SqlExecutor` 中产生模糊的 JDBC 错误；`InsightGenerator.generate()` 补充 `switchIfEmpty` 分支（filter 过滤空 LLM 内容后 Mono 为空时降级到 `thought` 字段），防止调用方因 empty Mono 引发 NPE；`DataAgentPipeline.analyze()` 错误报告补充 `question` 字段（原 `error(message)` 不含 question，前端无法关联到原始请求）；`DataAgentPipeline.analyzeStream()` 补充入口非空校验（question 为 null 或空时立即返回 error/done SSE，防止后续 NullPointerException 传播到 SSE 流中导致无 done 事件）；`VolatilityAnalysisAgent.parseJsonObject()` 失败兜底由不可变 `Map.of("trend","insufficient_data")` 改为 `new HashMap<>()`，防止调用方 `metrics.put(...)` 抛出 `UnsupportedOperationException`

- [x] **自迭代检视修复（第十二轮）** — **编译错误修复**：`MetricInfoRetriever.getMetricInfo()` 中 `ctx.currentResult()` 改为 `ctx.getCurrentResult()`（`MetricAnalysisContext` 是 `@Data` 类，Lombok 生成的是 `getXxx()` 方法而非记录组件访问器）；`MetricAnalysisContext.toPromptText()` 内全部 `currentResult.getRows()`/`getColumns()` 改为 `currentResult.rows()`/`columns()`（`DataQueryResult` 是 record，访问器不带 `get` 前缀）；`MetricAnalysisPipeline.toMapList()` / `extractCurrentValue()` 同步修正 `getRows()`→`rows()` / `getColumns()`→`columns()`。**安全修复**：`MetricInfoRetriever.register()` 新增注册时校验——`validateIdentifier()` 用正则 `^[A-Za-z_][A-Za-z0-9_]*$` 防止 table/column 字段含 SQL 注入字符，`validateAggregation()` 白名单（SUM/COUNT/AVG/MIN/MAX）防止任意函数注入；新增 `q(identifier)` 方法按方言正确引用标识符（MySQL 用反引号，PostgreSQL/H2 用双引号），`buildDefaultCurrentSql()`/`buildHistorySql()` 全部改用 `q()` 替换原来 MySQL 分支误用双引号的 Bug。**并发修复**：`SchemaRetriever` 将 `indexedDataSources: Set<String>` 替换为 `indexBuilt: ConcurrentHashMap<String, Object>`，`selectByEmbedding()` 中原先 `if (!contains) { build() }` 的 TOCTOU 竞争改为 `computeIfAbsent(id, k -> { buildIndexBlocking(); return BUILT; })`，保证同一数据源仅有一个线程执行索引构建。**运行时 Bug 修复**：`MetricAnalysisPipeline.parseVolatility()` 失败兜底由 `Map.of("trend","unknown")`（不可变）改为 `new HashMap<>(Map.of(...))`，防止下游 `put()` 抛 `UnsupportedOperationException`；`toMapList()` 删除多余的 `(Map<String,Object>) m` 强转（`m` 本身即 `Map<String,Object>` 不需要转型）。**功能 Bug 修复**：`DataAgentController.invalidateSchemaIndex()` 原来只检查数据源存在性但从不调用 `schemaRetriever.invalidateIndex()`，注入 `SchemaRetriever` 后实际执行索引清除；`unregisterDataSource()` 追加 `schemaRetriever.invalidateIndex(id)` 防止注销数据源后仍有陈旧向量残留内存；`DataAgentPipeline.generateSqlOnly()` 补充 question 非空校验（缺失时 NPE 到 schemaRetriever）；`DataAgentPipeline.generateDashboard()` 原始实现忽略 `request.dataSourceId` 始终使用默认连接器，改为 `resolveConnector(dsId)` + 传入 `DashboardGenerator.generate(question, connector, dsId)`；`DashboardGenerator.generate()` 新增接受 `DataSourceConnector`/`dataSourceId` 的重载，`executeDashboardSqls()` 改为接收 `targetConnector` 参数，执行 SQL 时使用目标数据源连接器而非注入的默认连接器。**参数校验**：`DataSourceManager.registerJdbc()` 增加 url 非空判断（防止 HikariCP 构建时产生模糊的 NPE）；`DataAgentController.registerDataSource()` 提前校验 url 字段。**代码清理**：移除 `DataSourceManager` 的无用 import `DataQueryResult`；移除 `MemoryService` 的无用 import `Collections`；移除 `DataAgentPipeline` / `DashboardGenerator` 中对同包类 `DataAgentConstants` 的跨包式 import。

- [x] **自迭代检视修复（第十三轮）** — 本轮采用 README 设计声明 vs 源码实现的交叉核查方法（4 个并行核查维度：架构/端点/配置默认值、Hook/权限级联、上下文压缩/SQL安全/消息持久化、Plan状态机/认证过滤器/Dashboard并发/SQL缓存），共验证 63 个 API 端点与十余项行为声明。**安全修复（高优先级）**：`SqlValidator.validate()` 原实现先剥离注释（`BLOCK_COMMENT`/`LINE_COMMENT`）再剥离字符串字面量（`STRING_LITERAL`），导致字符串字面量内部的 `--`（如合法值 `'x--'`）被 `LINE_COMMENT` 正则误判为行注释起点，从该处开始把其后真实的 `; DROP TABLE ...` 一并当作注释吞掉，从而绕过分号堆叠查询检测与关键字黑名单——修复为先剥离字符串字面量、再剥离注释，彻底堵住此绕过路径；新增 `stringLiteralContainingDashDash_doesNotMaskStackedQuery` / `..._withoutStackedQuery_isAllowed` 两个回归测试覆盖该场景。**正确性修复**：`MessagePersistenceService.saveMessages()` 原来在 `@Transactional` 方法内部 catch 住批量保存过程中的异常并仅打印 `log.warn`，方法正常返回导致 Spring 事务代理认为方法成功从而提交事务——批次中已保存的部分消息被错误地持久化，与 README 宣称的"批次内要么全存要么全不存"原子性矛盾；修复为不再吞掉异常，让其向外传播以触发事务回滚，调用方可感知持久化失败并自行决定后续处理。**文档缺陷修复**：README 权限模型表格（`### 6. 权限模型`）多轮变更后未同步——`READ_ONLY`/`SAFE` 两行仍停留在第十一轮扩权前的旧描述（`READ_ONLY` 遗漏 `DATABASE_READ`，`SAFE` 遗漏 `DATABASE_READ`+`GIT_OPERATION`），现已按 `PermissionService.checkPermissionLevel()` 实际实现同步更新。**可用性修复**：`PlanService.executePlan()`（对应 `POST /plan/{id}/execute`）设计上是"仅推进步骤状态"的状态机批处理方法，不执行任何真实文件/Shell操作，但原先的成功响应文案（`"Marked COMPLETED by executePlan()"`）容易让 API 调用方误以为步骤真的被执行了；现已在 `PlanResult.output` 与每个 step 的 `result` 字段中显式标注"STATUS-ONLY COMPLETION / 未执行任何真实操作"，并在 README 端点列表中为该端点补充警示注释，引导需要真实执行效果的调用方改用 `next-step` + `complete-step`/`fail-step` 组合。**核查通过（无需改动）**：63 个文档化 REST 端点全部有对应 Controller 实现；`max-tool-call-depth` 默认值 50 与文档一致；7 种 Hook 类型均已定义且均被实际触发，PRE_* 阻塞/POST_*非阻塞语义正确；`ContextCompressor` 默认阈值/keepRecent/fallback 行为均与文档一致；JWT(@Order 1)/ApiKey(@Order 2) 过滤器协作逻辑正确；`DashboardGenerator` 并发度 4 + 单图表故障隔离正确；`SqlCacheService` LRU 500 + 1h TTL + key 归一化正确；Plan 5 阶段状态机（Explore→Draft→Review→Approve→Execute）与 README 架构图一致。
- [x] **自迭代检视修复（第十四轮，本轮）** — 本轮针对 tools/、config/、core/data（Excel/数据源）、core/memory、core/skill 五大模块做安全与正确性专项审查。**安全修复（Critical）**：① `FilePathResolver.resolve()` 原实现仅用 `normalize()` 做词法层面的路径遍历防护，未解析符号链接——若沙箱目录内存在指向外部的符号链接（如 `workdir/link -> /etc/passwd`），`Read`/`Write`/`Edit`/`Grep`/`Glob`/`List` 等全部工具均可被诱导读写沙箱外文件；修复为额外调用 `toRealPath()` 解析真实路径再校验边界（文件不存在时退化为最近的已存在祖先目录判断），新增 `FilePathResolverTest` 覆盖符号链接逃逸/正常场景。② `MemoryService.sanitizeDirName()` 原正则保留点号，`userId=".."` 未被替换，`Paths.resolve("..")` 导致 `persistMemoryToFile`/`deleteMemoryFile` 逃逸到 memory 根目录之外造成任意路径写入/删除；修复为不再保留 `.`，整体替换为 `_`。③ `ExcelDataSourceConnector.getSampleRows()` 直接将客户端传入、未经校验的 `tableName` 拼接进 SQL（`getColumns()` 已用 `?` 参数化但 `getSampleRows()` 遗漏），构成标识符注入（可构造 `{"tableName":"x\" UNION SELECT ... --"}`）；修复为新增 `validateTableName()` 按内部生成规则（`^tbl_[a-f0-9]{12}$`）强校验，`getTableInfo`/`getSampleRows` 双重把关，新增 `getTableInfo_maliciousTableName_isRejectedNotExecuted` 回归测试。④ `SkillController.registerSkill()`/`ExternalSkillLoader.reload()` 允许任意 `execution.url` 且未做 SSRF 校验，`HttpDelegatedSkill.execute()` 会让服务端对该 URL 发起请求并回显响应，可被诱导访问云平台元数据端点（169.254.169.254）或内网服务窃取凭证；新增 `SkillUrlValidator.validateNotInternal()`（拒绝回环/内网/链路本地/多播地址及云元数据主机名），在 REST 注册入口与 yml 文件加载入口均接入校验。⑤ `config/WebConfig` 原 CORS 配置 `allowedOrigins("*")` 全放开，任意网站可跨域读取 JWT/API Key 保护的响应；新增 `CorsConfig`（`security.cors.allowed-origins`，默认空即不注册跨域映射，无通配符选项）。⑥ `application-postgres.yml` 密码默认回落到硬编码明文 `agentsecret`；改为 `${POSTGRES_PASSWORD}` 强制显式配置，与 `docker-compose-pg.yml` 的 `:?` 语义一致。**资源泄漏修复（High）**：⑦ `DataSourceManager.registerJdbc()`/`DataAgentConfig` 动态创建的 HikariCP 连接池在 `unregister()`/应用关闭时从未真正关闭（`JdbcDataSourceConnector.close()` 原来是空实现），反复注册/注销会持续泄漏数据库连接与维护线程；`JdbcDataSourceConnector` 新增 `ownedDataSource` 字段，仅当连接器持有自建连接池时 `close()` 才显式关闭，Spring 托管的默认数据源不受影响。⑧ `BashTool`/`GitTool` 原来只在超时分支销毁子进程，读取输出流异常或线程被中断等其他异常路径会让子进程成为孤儿进程持续运行；修复为 catch 块统一检查 `process.isAlive()` 并销毁；顺带移除 `BashTool` 中声明但从未被填充/读取的死字段 `runningProcesses`。**其他修复（Medium）**：⑨ `GrepTool.searchInFile()` 使用平台默认字符集读取文件（与包内其他工具的显式 UTF-8 不一致），非 UTF-8 默认 locale 环境下会抛 `MalformedInputException` 或产生乱码；改为显式 `StandardCharsets.UTF_8`。⑩ `EditTool`/`WriteTool` 未校验 `old_string`/`new_string`/`content` 是否为 null，缺失时抛出令人困惑的 `"...null"` 错误信息；改为提前返回明确的字段必填错误。⑪ `POST /api/v1/memory` 请求体缺失 `type` 字段时，`MemoryService.persistMemoryToFile()` 对 `entry.getType().name()` 直接 NPE 且未被捕获，冒泡为 500；在 Controller 入口补充非空校验，返回可读的 400。⑫ Excel 上传接口无文件大小上限，且原实现用 `System.arraycopy` 手工拼接每个分片（O(n²) 拷贝），大文件上传会导致内存/CPU 被拖垮；改为 `DataBufferUtils.join` + 累计字节数提前中断，`application.yml` 新增 `spring.webflux.multipart.max-in-memory-size: 10MB`，`DataAgentConstants.EXCEL_UPLOAD_MAX_BYTES` 显式限制单文件 20MB。⑬ `SkillController.executeSkill()` 原来返回同步 `ResponseEntity`，但内部 `HttpDelegatedSkill.execute()` 对 WebClient 的 `Mono` 调用了 `.block()`——在 WebFlux 的 Netty event-loop 线程上直接阻塞，轻则抛出被吞掉的模糊错误，重则并发请求耗尽 event-loop 线程池拖慢全站响应；改为返回 `Mono<ResponseEntity<SkillResult>>` 并显式 `subscribeOn(Schedulers.boundedElastic())`。**并发/配置审查通过（无需改动）**：`AgentManager` 并行虚拟线程编排（Anomaly+Volatility→Report）无共享可变状态，`agentExecutor` 正确 `@PreDestroy` 关闭；`LLMConfig`/`AgentConfig`/`PermissionConfig` 等配置类无泄漏密钥、无遗留调试开关；JPA 实体/仓储无明显 N+1 模式。
- [x] **向量记忆检索** — `EmbeddingClient` 接口 + `OpenAICompatibleEmbeddingClient`（调 `/v1/embeddings`，兼容 OpenAI / Ollama / Qwen / DeepSeek 等）；`MemoryEmbeddingStore` 双层 `ConcurrentHashMap` 内存存储 + 余弦相似度排序（无需外部向量库）；`@ConditionalOnProperty` 条件注册，关闭时零开销；`GET /memory/{userId}/search?mode=semantic` 新参数；失败/未启用自动降级关键词搜索；启动时后台批量补齐 embedding；`MemoryEmbeddingStoreTest` 单元测试覆盖（余弦计算/阈值过滤/多用户隔离/零向量防护）
- [x] **Schema Linking 向量检索** — `SchemaRetriever` 复用 `EmbeddingClient`，为表名+列名建立向量索引（`ConcurrentHashMap<dsId::tableName, float[]>`），按余弦相似度选 top-K 表，降级为关键词匹配；首次 `retrieve()` 同步建索引，后续走缓存；`invalidateIndex(dsId)` 支持热刷新；相似度阈值 0.25（短表名语义更离散）；多数据源各自独立索引。
- [x] **查询超时告警 + 慢查询日志** — `SqlExecutor` 通过构造器注入 `MeterRegistry`（`@Autowired(required=false)`，无 Actuator 时零开销）；`Timer.builder("data_agent_query_duration_seconds").tag("status",...)` 记录全量查询耗时；执行时间超过 `data-agent.slow-query-threshold-ms`（默认 2000ms）时打 WARN 日志（含 SQL 摘要前 120 字符）；通过 `GET /actuator/metrics/data_agent_query_duration_seconds` 可读；`application.yml` 开放 `metrics,prometheus` Actuator 端点。
- [x] **多数据源管理** — `DataSourceManager` 维护 `ConcurrentHashMap<id, DataSourceConnector>`；`registerJdbc(id, url, ...)` 内部创建 HikariCP 连接池；`DataAgentPipeline.resolveConnector(dsId)` 按请求路由连接器；`SchemaRetriever.retrieve(q, connector, dsId)` 接受显式连接器参数；`DataAgentConfig` 注册默认连接器；REST API：`GET /datasources` 列出 / `POST /datasources` 注册 / `DELETE /datasources/{id}` 注销；`GET /schema?dataSourceId=xxx` 查指定数据源 Schema。
- [x] **指标体系集成** — `MetricInfoRetriever` 维护指标注册表（`MetricDefinition`: name/table/valueColumn/timeColumn/dimensions/currentValueSql/historySql）；按方言生成历史趋势 SQL（MySQL/PostgreSQL/H2）；`MetricAnalysisPipeline` 串联完整归因链路：`getMetricInfo()` → 并行（`AnomalyDetectorAgent` + `VolatilityAnalysisAgent`）→ `ReportGenerationAgent` → `MetricAnalysisReport`；REST API：`GET /metrics` 列出 / `POST /metrics` 注册 / `DELETE /metrics/{name}` 注销 / `POST /metric-analysis` 完整归因分析。

### 待实现

- [ ] **LangChain4j 集成（向量存储 + MCP 客户端）** — 引入 `langchain4j-bom` + `langchain4j-core`/`langchain4j-open-ai`/`langchain4j-pgvector`（beta）/`langchain4j-mcp`（beta）。用 `OpenAiEmbeddingModel` 替代手写 `OpenAICompatibleEmbeddingClient`；用 `EmbeddingStore<TextSegment>`（开发环境 `InMemoryEmbeddingStore`，PostgreSQL profile 下 `PgVectorEmbeddingStore`）替代手写 `MemoryEmbeddingStore`（双层 `ConcurrentHashMap` + 手写余弦相似度），同时替换 `SchemaRetriever` 内重复的向量索引逻辑；用 `DefaultMcpClient` + `StreamableHttpMcpTransport` 替代手写 `HttpMcpClient`（JSON-RPC over 阻塞 `WebClient.block()`）。`Tool`/`ToolManager`/`EmbeddingConfig` 对外配置字段保持不变，`memory.embedding.enabled=false` / `mcp.enabled=false` 时行为不变。

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
