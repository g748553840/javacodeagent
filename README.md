# Java Code Agent - AI Code Assistant

一个基于Java (JDK21 + SpringBoot3) 实现的AI代码助手agent，参考Claude Code的架构设计。

## 项目特性

- LLM集成（支持Anthropic API，可扩展其他LLM提供商）
- 工具调用系统（Tool Calling）
- 文件系统操作（Read、Write、Edit、Glob、Grep、List）
- Shell命令执行
- 权限管理系统
- Hook机制
- Agent与子任务调度
- 技能系统
- 计划模式
- 记忆系统（持久化上下文）
- WebSocket流式响应
- 响应式Web接口

## 技术栈

- Java 21
- Spring Boot 3.2.5
- Spring WebFlux
- Lombok
- H2 Database / PostgreSQL
- JUnit 5 + Mockito

## 项目结构

```
java-code-agent/
├── src/
│   ├── main/
│   │   ├── java/com/javacodeagent/
│   │   │   ├── JavaCodeAgentApplication.java        # 应用入口
│   │   │   ├── config/                               # 配置类
│   │   │   ├── controller/                           # REST控制器
│   │   │   ├── core/
│   │   │   │   ├── agent/                            # Agent系统
│   │   │   │   ├── conversation/                     # 对话管理
│   │   │   │   ├── enums/                            # 枚举定义
│   │   │   │   ├── hook/                             # Hook机制
│   │   │   │   ├── llm/                              # LLM客户端
│   │   │   │   ├── memory/                           # 记忆系统
│   │   │   │   ├── model/                            # 数据模型
│   │   │   │   ├── permission/                       # 权限系统
│   │   │   │   ├── plan/                             # 计划模式
│   │   │   │   ├── skill/                            # 技能系统
│   │   │   │   └── tool/                             # 工具接口
│   │   │   └── tools/                                # 内置工具实现
│   │   └── resources/
│   │       └── application.yml                       # 应用配置
│   └── test/
│       └── java/com/javacodeagent/
│           ├── JavaCodeAgentApplicationTests.java
│           └── tools/                                # 工具测试
├── pom.xml                                            # Maven配置
└── README.md
```

## 快速开始

### 前置要求

- JDK 21 或更高版本
- Maven 3.8+

### 安装步骤

1. 克隆项目到本地
```bash
cd D:/workspace/claude-code-java
```

2. 编译项目
```bash
mvn clean compile
```

3. 运行项目
```bash
mvn spring-boot:run
```

### 配置

在 `application.yml` 中配置LLM API密钥：

```yaml
llm:
  provider: anthropic
  api-key: your-api-key-here
  model: claude-opus-4-7
```

或者通过环境变量设置：

```bash
export LLM_API_KEY=your-api-key-here
```

### 使用示例

#### 1. 启动应用
```bash
mvn spring-boot:run
```

应用将在 `http://localhost:8080` 启动。

#### 2. 发送聊天请求

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Hello, can you help me read a file?"
  }'
```

#### 3. 健康检查

```bash
curl http://localhost:8080/api/v1/health
```

#### 4. H2控制台

访问 `http://localhost:8080/h2-console` 查看数据库（如果使用H2）。

## 内置工具

| 工具 | 描述 | 需要权限 |
|------|------|----------|
| Read | 读取文件内容 | 否 |
| Write | 写入文件内容 | 是 (FILE_WRITE) |
| Edit | 精确字符串替换 | 是 (FILE_WRITE) |
| Glob | 文件模式匹配 | 否 |
| Grep | 文件内容搜索 | 否 |
| List | 列出目录内容 | 否 |
| Bash | 执行Shell命令 | 是 (SHELL_EXECUTE) |

## API文档

### POST /api/v1/chat

发送聊天消息并获取AI响应。

**请求体：**
```json
{
  "conversationId": "optional-conversation-id",
  "content": "Your message here",
  "workingDirectory": "/path/to/working/dir"
}
```

**响应：**
```json
{
  "content": "AI response",
  "conversationId": "conversation-id"
}
```

## 开发路线图

- [x] 基础框架
- [x] LLM客户端集成
- [x] 核心工具实现（Read、Write、Edit、Glob、Grep、Bash）
- [x] 权限系统
- [x] 记忆系统
- [x] Hook机制
- [x] Agent系统
- [x] 技能系统
- [x] 计划模式
- [ ] Git工具
- [ ] MCP协议支持
- [ ] 向量存储集成
- [ ] WebSocket流式响应
- [ ] 容器化部署
- [ ] 集成测试

## 测试

运行所有测试：
```bash
mvn test
```

运行特定测试：
```bash
mvn test -Dtest=ReadToolTest
```

## 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户界面层                                │
│  (CLI / Web / IDE插件)                                          │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│                      应用服务层 (Spring Boot)                    │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  对话管理器  │  │  Agent调度器 │  │  技能管理器 │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  工具管理器  │  │  权限控制器 │  │  记忆管理器 │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│                      核心引擎层                                  │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │ LLM客户端   │  │ 上下文构建器 │  │ Hook管理器  │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│                      工具执行层                                  │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  文件工具   │  │ Git工具     │  │  Shell工具  │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  Web工具    │  │  MCP客户端   │  │  自定义工具 │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│                      持久化层                                    │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  对话历史   │  │  记忆存储   │  │  配置存储   │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└─────────────────────────────────────────────────────────────────┘
```

## 许可证

本项目仅供学习和参考使用。