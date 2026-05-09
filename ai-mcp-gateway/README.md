# 3-5-message-handler-case

- 增加 `initialize`、`tools/list`、`tools/call`、`resources/list` 这几个 MCP method 的 handler 分发。
- `SessionMessageService` 从只处理 `JSONRPCRequest`，调整为统一接收 `JSONRPCMessage`，可以识别 `Request / Notification / Response`。
- `handleMessage` 从“HTTP 同步返回 JSON-RPC 响应”改成“HTTP 接收消息 + SSE 异步回推结果”。
- 当前通信链路是：
  - `GET /{gatewayId}/mcp/sse` 建立会话
  - `POST /{gatewayId}/mcp/sse?sessionId=...` 投递 JSON-RPC 消息
  - 服务端通过 session 对应的 SSE sink 回推响应
- `createMcpSession` 是 MCP 建连流程，节点链为 `RootNode -> VerifyNode -> SessionNode -> EndNode`。
- `createSession` 是底层会话创建，负责生成 `sessionId`、创建 `sink`、存储会话。
- 当前项目定位还是本地实现型 MCP Server 骨架，不是转发外部 MCP Server 的代理网关。

# 3-6-mcp-table-mapper

- 新增 `mcp_gateway`、`mcp_gateway_auth`、`mcp_protocol_mapping`、`mcp_protocol_registry` 这 4 张表对应的 PO、DAO、MyBatis mapper XML。
- 结构已按 `docs/dev-ops/mysql/sql/ai_mcp_gateway.sql` 对齐，不再使用占位字段。
- 启动类增加了 `@MapperScan("com.makiatox.ai.infrastructure.dao")`，并打开了 MyBatis 的 `mapper-locations` 和 `config-location` 配置。
- 新增 DAO 单测目录：`src/test/java/com/makiatox/ai/test/infrastructure/dao`。
- DAO 测试通过 `@SpringBootTest + @Transactional` 跑真实数据库访问，测试结束后默认回滚，所以插入成功不代表库里会保留数据。
- 本地联调方式改为通过 Docker 启动 MySQL，SQL 初始化后可用 IntelliJ IDEA 的 `Database` 工具窗口做可视化查看。

# 3-7-mcp-message-handler-initialize

- 参考 Java MCP SDK 的 `initialize` 处理链路梳理当前项目实现。
- 标准流程是：
  - 原始 JSON-RPC 消息先反序列化成请求对象
  - 再将其中 `params` 转成 `InitializeRequest`
  - 进入会话/消息处理层识别 `initialize`
  - 调用初始化逻辑生成 `InitializeResult`
  - 最后再统一包装成 `JSONRPCResponse` 返回
- 消息处理接口开始显式传递 `gatewayId`，为按网关查询配置做准备。
- 在领域层新增 `ISessionRepository`，由基础设施层实现网关配置聚合查询。
- `InitializeHandler` 从固定返回调整为基于 `gatewayId` 查询配置后动态组装 `InitializeResult`。
- `McpSchemaVO` 补充 initialize 相关协议对象，用结构化对象承接 `params` 和 `result`。
- 当前分支的重点是把 `InitializeHandler` 从硬编码返回，逐步往标准初始化结构靠拢。

# 3-8-mcp-message-handler-toollist

- 这一节最重要的结论不是 `tools/list` 本身，而是这条“配置转协议”的链路：

```text
mcp_protocol_mapping
-> 扁平字段配置
-> parentPath / mcpPath 还原树
-> buildProperty DFS 递归
-> JsonSchema
-> Tool
-> tools/list 响应
```

- `mcp_protocol_mapping` 不是普通字段清单，而是在关系表里表达一棵字段树，目标是把库里的配置动态还原成 MCP `inputSchema`。
- 这类设计不依赖 Java，也不是 Java MCP SDK 强制要求的存储方式；SDK 只关心最后返回的 Tool / Schema 结构，表怎么建是项目自己的平台化设计。

- 这张表更准确地说，是“邻接表 + 路径枚举”的混合设计：
  - 邻接表（adjacency list）：一行表示一个节点，每个节点记录“我是谁”和“我父节点是谁”。
  - 路径枚举（materialized path）：把从根到当前节点的完整路径直接存下来。
  - 在本项目里：
    - `parent_path` 表示当前节点的直接父节点。
    - `mcp_path` 表示当前节点自己的完整路径。
    - `field_name` 表示当前节点在当前层的字段名。

- `xxxx.xxx` 这种值就是路径枚举，例如 `xxxRequest01.company.name` 表示：
  - 根节点是 `xxxRequest01`
  - 它下面有 `company`
  - `company` 下面有 `name`

- 以这组数据为例：
  - `parent_path = null, mcp_path = xxxRequest01`
  - `parent_path = xxxRequest01, mcp_path = xxxRequest01.company`
  - `parent_path = xxxRequest01.company, mcp_path = xxxRequest01.company.name`
- 还原出来的树就是：

```text
xxxRequest01
└── company
    └── name
```

- 为什么不是存“父 + 子”：
  - 树里每个节点只有一个父节点，但可能有多个子节点。
  - 让每一行只记录“我爸是谁”，比在一行里维护不定长子节点集合更自然，也更符合关系表一行一个实体的建模方式。

- 为什么不是直接存一整坨 JSON Schema：
  - 存 JSON 更适合整体读写。
  - 存树表更适合配置化管理，例如单独修改字段、控制排序、维护必填、描述、HTTP 映射关系。
  - 这个项目显然更偏“平台化配置”，所以选择节点级存储而不是整块 Schema 文本。

- `buildProperty` 的算法本质要记住：
  - `childrenMap` 是邻接表，key 形如 `parent_path -> children`。
  - `buildProperty(current, childrenMap)` 是多叉树 DFS。
  - 每次递归返回的是“当前节点对应的 JSON Schema property”。
  - 从根节点（`parent_path == null`）开始递归，最终拼出整个 `inputSchema`。

- 这一节还有一个容易混的点：要始终区分“外层协议壳子”和“内层 method 数据”。
  - 外层统一是 `JSONRPCRequest / JSONRPCResponse`。
  - `initialize` 的内层对象是 `InitializeRequest / InitializeResult`。
  - `tools/list` 的内层对象是 `ListToolsResult / Tool / JsonSchema`。
  - 一乱就先回到这句：先分外壳，再看里面装的是什么。

- `tool-list.json` 的定位也要记住：
  - 它是 `tools/list` 的目标样例 / 验收模板。
  - 它能帮助对照预期输出长什么样。
  - 它不是运行时数据源，不能替代 `mcp_protocol_mapping`。

- 这次分支里踩到的两个实际问题值得单独记住：
  - `Invalid bound statement`：通常先查 MyBatis mapper XML 是否缺少对应 `select`。
  - `{"tools":[]}`：不一定是构树逻辑错了，也可能是测试数据没有命中 `gatewayId`。

- 一个实用判断标准：
  - 如果业务目标是“整棵结构整体保存、整体读取”，存 JSON 往往更省事。
  - 如果业务目标是“节点可管理、可排序、可映射、可单独修改”，树表设计通常更合适。

- 复习时可以直接记住这 7 句话：
  - `tools/list` 的本质是“数据库配置动态生成 MCP Tool Schema”。
  - `mcp_protocol_mapping` 存的不是普通字段列表，而是一棵拆平后的字段树。
  - 这张表是“邻接表 + 路径枚举”的混合设计。
  - `childrenMap` 是邻接表，`buildProperty` 是多叉树 DFS。
  - JSON-RPC 是外层壳子，`initialize` / `tools/list` 的对象是内层内容。
  - `tool-list.json` 是目标样例，不是运行时数据源。
  - 代码跑不通时，除了逻辑，还要优先检查 mapper 绑定和测试数据是否对齐。

# 3-9-mcp-message-handler-toolcall

- `tools/call` 的本质是把一次 MCP 工具调用，翻译成一次真实的 HTTP 请求发出去，再把 HTTP 返回包装回 MCP 响应。
- 业务上仍然只是这些信息：`method + url + headers + body/query`，只是项目把“HTTP 客户端初始化”和“业务请求发起”拆成了两层。
- `HTTPClientConfig` 放在 `config` 里，是因为它负责注册通用 HTTP Client，而不是处理具体业务。可以类比成提前准备好 `WebClient` / `RestTemplate`。
- `GenericHttpGateway` 更接近 Retrofit 的声明式 HTTP Client：接口方法 + 注解，就是一份“HTTP 请求说明书”。
- 这和 MyBatis 很像：
  - MyBatis：接口方法 + XML/注解 -> SQL
  - Retrofit：接口方法 + 注解 -> HTTP 请求
- 所以不是手写 `GenericHttpGateway` 的实现类，而是由 `retrofit.create(GenericHttpGateway.class)` 在运行时动态生成代理对象。
- 有了 Retrofit 之后，不需要手写底层 `Request.Builder` 这类样板代码，但业务层仍然要自己准备 url、headers、body、query 参数。
- `CallToolRequest` 属于 `McpSchemaVO` 协议层，它对应的是 `tools/call` 的 `params`，不是数据库配置 VO。
- `McpGatewayProtocolConfigVO` 属于配置层，它回答的是“这个 tool 最终该怎么调下游 HTTP 接口”。
- `deserializeJsonRpcMessage` 和 `unmarshalFrom` 要分开记：
  - 前者：原始 JSON 文本 -> 外层 `JSONRPCRequest / Response / Notification`
  - 后者：`params / result` -> method 专属强类型对象，例如 `CallToolRequest`
- `ToolsCallHandler` 是协议入口层，`SessionPort` 是执行层，`GenericHttpGateway` 是底层 HTTP Client 层。
- `SessionPort` 当前实现是同步阻塞调用，因为底层使用的是 `call.execute()`，不是异步 `enqueue(...)`。
- 联调时要区分三层角色，尤其要分清“当前项目”和“另一个 demo 项目”：
  - `ApiTest`：上游测试客户端。它不在当前项目里，位于另一个 demo 测试工程中，负责发起 MCP 请求。
  - `ai-mcp-gateway`：当前项目本身。它是中间 MCP Gateway，负责查库、识别 `tools/call`、按配置转成 HTTP 请求、再把结果按 MCP 协议包装返回。
  - `demo-server-test Application`：下游 HTTP Server。它也不在当前项目里，位于另一个 demo 工程中，负责提供真实业务接口给 gateway 调用。
- 所以联调不是“当前项目自己调自己”，而是：
  - 上游 demo 工程里的 `ApiTest` 调当前项目 `ai-mcp-gateway`
  - 当前项目 `ai-mcp-gateway` 再去调另一个 demo 工程里的 HTTP 接口
  - 下游结果返回到 `ai-mcp-gateway` 后，再包装回 MCP 响应给 `ApiTest`
- 整条链路可以记成：
  - `ApiTest(另一个 demo 工程) -> ai-mcp-gateway(当前项目) -> demo-server-test HTTP 接口(另一个 demo 工程) -> ai-mcp-gateway -> ApiTest`

# Project Summary

## 1. 项目定位

- `ai-mcp-gateway` 的核心定位不是“本地写死工具的 MCP Server”，而是“对上暴露 MCP、对下适配 HTTP 的配置化 MCP Gateway”。
- 从协议角度看，它对外提供的是 MCP / JSON-RPC 能力：
  - `initialize`
  - `tools/list`
  - `tools/call`
  - `resources/list`
- 从工程本质看，它更像传统 API Gateway / 协议适配层：
  - 按 `gatewayId` 查配置
  - 动态生成工具定义
  - 把 MCP 工具调用翻译成下游 HTTP 请求
  - 把下游结果重新包装成 MCP 响应

可以把它压缩成一条链：

```text
MCP Client / LLM
-> ai-mcp-gateway
-> DB 配置驱动
-> 下游 HTTP 服务
-> ai-mcp-gateway
-> MCP 响应
```

## 2. 工程结构

- `ai-mcp-gateway-api`
  - 对外 API 契约，例如 `IMcpGatewayService`
- `ai-mcp-gateway-app`
  - Spring Boot 启动模块
  - 配置类、依赖装配、应用入口
- `ai-mcp-gateway-trigger`
  - HTTP Controller 入口
  - 把外部请求接入到内部会话和消息处理链
- `ai-mcp-gateway-case`
  - 会话建立编排
  - 基于节点链的 session 创建流程
- `ai-mcp-gateway-domain`
  - MCP 协议对象
  - 会话管理服务
  - 消息分发与 method handler
- `ai-mcp-gateway-infrastructure`
  - DAO / MyBatis Mapper
  - Repository 适配器
  - 下游 HTTP 调用适配器
- `ai-mcp-gateway-types`
  - 共享常量、枚举、异常

## 3. 系统主链路

### 3.1 建立会话

客户端先通过：

```text
GET /{gatewayId}/mcp/sse
```

建立 SSE 长连接。

会话创建链路大致是：

```text
McpGatewayController.establishSSEConnection
-> McpSessionService.createMcpSession
-> DefaultMcpSessionFactory.strategyHandler
-> RootNode
-> VerifyNode
-> SessionNode
-> EndNode
-> 返回 Flux<ServerSentEvent<String>>
```

其中：

- `SessionManagementService`
  - 负责生成 `sessionId`
  - 创建 `sink`
  - 用内存 `ConcurrentHashMap` 保存活跃 session
  - 处理心跳、超时与清理
- `EndNode`
  - 把 `sink.asFlux()` 暴露成最终 SSE 响应流
  - 合并心跳 `ping`
  - 在取消 / 终止时清理会话

### 3.2 发送消息

客户端再通过：

```text
POST /{gatewayId}/mcp/sse?sessionId=...
```

发送 JSON-RPC 消息。

这一步由 `McpGatewayController.handleMessage` 负责：

- 找到对应 session
- 反序列化 JSON-RPC 消息
- 调用 `SessionMessageService.processHandlerMessage`
- 把结果写回 session 对应的 SSE sink

## 4. JSON-RPC 与 MCP 协议模型

项目里协议模型主要集中在 `McpSchemaVO`。

### 4.1 两层结构

要始终区分两层：

- 外层：JSON-RPC 壳子
  - `JSONRPCRequest`
  - `JSONRPCResponse`
  - `JSONRPCNotification`
- 内层：method 专属对象
  - `InitializeRequest / InitializeResult`
  - `ListToolsResult / Tool / JsonSchema`
  - `CallToolRequest`

可以记成：

```text
原始 JSON
-> 外层 JSON-RPC 壳子
-> method 专属 params / result 对象
```

### 4.2 两段式转换

- `deserializeJsonRpcMessage`
  - 处理整段原始 JSON 文本
  - 先判断它是 Request / Notification / Response
- `unmarshalFrom`
  - 再把壳子里的 `params / result` 转成 method 专属强类型对象

也就是：

- 前者先拆信封
- 后者再读信内容

## 5. 数据库设计

当前项目核心依赖四张表：

- `mcp_gateway`
  - 网关基础信息
- `mcp_gateway_auth`
  - 网关鉴权信息
- `mcp_protocol_registry`
  - tool 注册信息
  - 下游 HTTP URL / method / headers / timeout 等协议配置
- `mcp_protocol_mapping`
  - tool 输入字段映射配置
  - 用于生成 `tools/list` 的 `inputSchema`

### 5.1 `mcp_protocol_mapping` 的意义

这张表不是普通字段清单，而是在关系表里表达一棵字段树。

关键字段：

- `parent_path`
  - 当前节点的直接父节点
- `mcp_path`
  - 当前节点的完整路径
- `field_name`
  - 当前层字段名

它本质上是：

- 邻接表（adjacency list）
- 路径枚举（materialized path）

的混合设计。

目标不是存一整坨 JSON Schema，而是把字段节点拆开存储，便于：

- 配置化管理
- 排序
- 必填控制
- HTTP 映射扩展

## 6. 三类核心配置 VO

### 6.1 `McpGatewayConfigVO`

- 网关级摘要配置
- 主要给 `initialize` 和 `tools/list` 使用
- 回答“当前 gateway 是谁、挂了哪个 tool、tool 叫什么”

### 6.2 `McpGatewayToolConfigVO`

- 工具字段级配置
- 主要给 `tools/list` 使用
- 回答“tool 的字段树长什么样”

### 6.3 `McpGatewayProtocolConfigVO`

- 协议调用级配置
- 主要给 `tools/call` 使用
- 回答“这个 tool 最终怎么调下游 HTTP 接口”

一句话区分：

- `McpGatewayConfigVO`：是谁
- `McpGatewayToolConfigVO`：长什么样
- `McpGatewayProtocolConfigVO`：怎么调出去

## 7. `initialize`

`initialize` 是第一条核心消息链路。

它的流程是：

```text
原始 JSON-RPC
-> JSONRPCRequest
-> InitializeRequest
-> queryMcpGatewayConfigByGatewayId
-> InitializeResult
-> JSONRPCResponse
```

重点是：

- `gatewayId` 已经进入消息处理层
- 初始化响应不再写死
- 可以根据网关配置动态返回 `serverInfo`、`instructions`、`capabilities`

## 8. `tools/list`

`tools/list` 的重点不是“返回一个列表”，而是：

> 如何把数据库里的扁平字段配置，动态组装成 MCP Tool Schema。

主链路是：

```text
queryMcpGatewayConfigByGatewayId
-> queryMcpGatewayToolConfigListByGatewayId
-> 按 toolId 分组
-> parentPath / mcpPath 还原树
-> buildProperty DFS 递归
-> JsonSchema
-> Tool
-> JSONRPCResponse
```

### 8.1 `buildProperty`

`buildProperty(current, childrenMap)` 本质上就是多叉树 DFS：

- 先处理当前节点
- 再从 `childrenMap` 里拿所有子节点
- 递归生成子 property
- 最后挂回当前节点的 `properties`

这是 `tools/list` 最重要的算法点。

### 8.2 当前实现的关键经验

- `mcp_protocol_mapping` 的测试数据必须命中 `gatewayId`
- Mapper XML 里必须补齐对应 `select`
- `tool-list.json` 只是目标样例，不是运行时数据源

## 9. `tools/call`

`tools/call` 的重点不是“处理一个请求对象”，而是：

> 如何把一次 MCP 工具调用翻译成真实 HTTP 请求，并把结果按 MCP 协议返回。

主链路是：

```text
JSONRPCRequest(method=tools/call)
-> CallToolRequest
-> queryMcpGatewayProtocolConfig(gatewayId)
-> SessionPort.toolCall(...)
-> GenericHttpGateway
-> 下游 HTTP 响应
-> JSONRPCResponse.result
```

### 9.1 `CallToolRequest`

`CallToolRequest` 属于 `McpSchemaVO` 协议层。

它对应的是：

```json
{
  "method": "tools/call",
  "params": {
    "name": "...",
    "arguments": { ... }
  }
}
```

其中：

- `name`
  - 本次要调哪个 tool
- `arguments`
  - 传给 tool 的参数

它属于“method 专属请求对象”，而不是数据库配置对象。

### 9.2 `ToolsCallHandler`

`ToolsCallHandler` 是协议入口层。

它主要做 4 步：

1. 查 `McpGatewayProtocolConfigVO`
2. 把 `message.params()` 转成 `CallToolRequest`
3. 调 `SessionPort.toolCall(...)`
4. 把下游结果包装成 MCP `content + isError` 结构，再放进 `JSONRPCResponse`

当前版本先按最小链路打通：

- tool name 暂未真正参与多 tool 路由
- `result` 先手工用 `Map` 组装
- 异常统一包装成 JSON-RPC error

### 9.3 `SessionPort`

`SessionPort` 是执行层。

它不负责协议解析，只负责：

- 读取 HTTP config
- 解析 headers
- 判断 GET / POST
- 调 `GenericHttpGateway`
- 返回下游 HTTP 结果

当前实现特点：

- 支持 GET / POST
- 同步阻塞调用
- 底层用的是 `call.execute()`，不是异步 `enqueue(...)`

### 9.4 `GenericHttpGateway` 与 Retrofit

`GenericHttpGateway` 不是手写实现类，而是 Retrofit 的声明式 HTTP Client 接口。

可以类比：

- MyBatis：接口方法 + XML/注解 -> SQL
- Retrofit：接口方法 + 注解 -> HTTP 请求

所以：

- 不是自己写 `GenericHttpGatewayImpl`
- 而是通过 `retrofit.create(GenericHttpGateway.class)` 动态生成代理对象

这让业务层不再需要自己手写底层 `Request.Builder` 样板代码，
但仍然需要自己准备：

- url
- headers
- body
- query 参数

### 9.5 `HTTPClientConfig`

`HTTPClientConfig` 放在 `app/config`，是因为它负责的是：

- 注册 `OkHttpClient`
- 注册 `GenericHttpGateway`

也就是“先把 HTTP Client 基础设施准备好”，而不是直接处理业务调用。

可以类比成提前准备一个可注入的 `WebClient` / `RestTemplate`。

## 10. 联调角色

联调时要分清三层角色：

- 上游测试客户端
  - demo 工程里的 `ApiTest`
- 中间 MCP Gateway
  - 当前项目 `ai-mcp-gateway`
- 下游 HTTP Server
  - demo 工程里的 `demo-server-test Application`

整条联调链路是：

```text
ApiTest(另一个 demo 工程)
-> ai-mcp-gateway(当前项目)
-> demo-server-test HTTP 接口(另一个 demo 工程)
-> ai-mcp-gateway
-> ApiTest
```

所以联调并不是“当前项目自己调自己”，而是：

- 上游 demo client 调当前 gateway
- 当前 gateway 再按 DB 配置去调下游 demo HTTP 接口
- 最终 gateway 把下游结果重新包装成 MCP 响应返回

## 11. 当前完成度

到当前阶段，项目已经形成了一个可讲清楚的闭环：

- SSE 会话创建与管理
- JSON-RPC 消息接收与分发
- `initialize` 动态返回
- `tools/list` 动态生成 tool schema
- `tools/call` 翻译成下游 HTTP 调用
- 端到端联调链路可解释

这意味着项目已经不再只是“骨架”，而是形成了一个明确的 MCP Gateway 最小闭环。

## 12. 当前项目最重要的 10 句话

- 这个项目对上说 MCP，对下接 HTTP，本质是配置化 MCP Gateway。
- 外层统一是 JSON-RPC，内层再按 method 拆具体对象。
- `initialize` 解决的是协议握手和服务端能力声明。
- `tools/list` 解决的是工具发现和动态 schema 生成。
- `tools/call` 解决的是工具调用和下游协议转发。
- `mcp_protocol_mapping` 存的不是普通字段列表，而是一棵拆平后的字段树。
- `buildProperty` 是多叉树 DFS，是 `tools/list` 的核心算法。
- `CallToolRequest` 属于协议层，`McpGatewayProtocolConfigVO` 属于配置层。
- `ToolsCallHandler` 是入口层，`SessionPort` 是执行层，`GenericHttpGateway` 是 HTTP Client 层。
- 当前项目最核心的价值，是把 MCP 工具协议和下游 HTTP 能力适配起来。
