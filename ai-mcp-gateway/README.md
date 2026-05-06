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
