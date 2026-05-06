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
