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
