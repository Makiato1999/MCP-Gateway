# main 分支笔记

## 3-3 责任链、抽象、接口、SSE、Flux、Sink 相关

这一部分对应的是 session 建链之前和建链过程中的那批概念。核心不是某个业务判断难，而是抽象层次比较多，所以第一次看会觉得绕。

### 3-3.1 先记住这条主线

这个 session 模块做的事是：

> 把一次 `GET /{gatewayId}/mcp/sse` 请求，变成一个可持续推送的 SSE 会话，并在内存里维护这个会话，直到断开或超时。

整体调用链可以先粗看成：

```text
客户端请求
  -> McpGatewayController.establishSSEConnection(gatewayId)
  -> McpSessionService.createMcpSession(gatewayId)
  -> DefaultMcpSessionFactory.strategyHandler()
  -> rootNode.apply(gatewayId, context)
  -> RootNode
  -> VerifyNode
  -> SessionNode
  -> EndNode
  -> 返回 Flux<ServerSentEvent<String>>
```

### 3-3.2 `interface`、`abstract class`、`abstract method`、业务类分别干什么

先记一句：

> `interface` 定义能力，`abstract class` 定义骨架，`abstract method` 留扩展点，业务类补具体逻辑。

放到这套代码里：

- `StrategyHandler`：定义统一调用方式 `apply(...)`
- `AbstractMultiThreadStrategyRouter`：定义公共执行模板和 `router(...)`
- `AbstractMcpSessionSupport`：收敛 session 场景共性
- `RootNode / VerifyNode / SessionNode / EndNode`：实现具体节点业务

以后看类似代码，先问自己三件事：

1. 这个 `interface` 规定了什么能力？
2. 这个 `abstract class` 已经帮我实现了什么公共流程？
3. 这个业务子类只需要补哪些具体步骤？

### 3-3.3 `StrategyHandler<T, D, R>` 是什么

它是统一处理器接口：

```java
R apply(T requestParameter, D dynamicContext) throws Exception;
```

意思就是：

- `T`：输入参数类型
- `D`：上下文类型
- `R`：返回结果类型

在当前 session 责任链里，它具体变成：

```java
StrategyHandler<String, DynamicContext, Flux<ServerSentEvent<String>>>
```

对应：

- `String`：`gatewayId`
- `DynamicContext`：链路共享上下文
- `Flux<ServerSentEvent<String>>`：最终返回的 SSE 数据流

### 3-3.4 `AbstractMultiThreadStrategyRouter` 是什么

这是责任链真正跑起来的框架底座。它最关键的两个方法是：

- `apply(...)`：执行当前节点
- `router(...)`：把请求交给下一个节点

逻辑可以压缩成：

```java
public R apply(T requestParameter, D dynamicContext) throws Exception {
    this.multiThread(requestParameter, dynamicContext);
    return this.doApply(requestParameter, dynamicContext);
}

public R router(T requestParameter, D dynamicContext) throws Exception {
    StrategyHandler<T, D, R> strategyHandler = this.get(requestParameter, dynamicContext);
    return null != strategyHandler
            ? strategyHandler.apply(requestParameter, dynamicContext)
            : this.defaultStrategyHandler.apply(requestParameter, dynamicContext);
}
```

一句话记忆：

- `apply = 进节点`
- `doApply = 节点干活`
- `get = 找下家`
- `router = 传给下家`

### 3-3.5 `AbstractMcpSessionSupport` 是什么

这是 session 场景下的业务基类。作用是：

- 统一泛型类型
- 注入公共依赖 `ISessionManagementService`
- 复用责任链框架父类的能力

继承关系可以看成：

```text
StrategyHandler
  ^
  |
AbstractMultiThreadStrategyRouter
  ^
  |
AbstractMcpSessionSupport
  ^
  |
RootNode / VerifyNode / SessionNode / EndNode
```

### 3-3.6 `DefaultMcpSessionFactory` 是什么

虽然名字叫 `Factory`，但它现在并不是经典抽象工厂。

它主要做两件事：

- 返回责任链入口 `rootNode`
- 定义 `DynamicContext`

所以更准确地说，它更像：

- 责任链入口提供者
- 链路上下文定义处

### 3-3.7 四个节点各自负责什么

- `RootNode`：责任链入口，打日志，路由到下一个节点
- `VerifyNode`：预留鉴权/校验插槽，当前基本直通
- `SessionNode`：真正创建 session，并把 `SessionConfigVO` 写进 `DynamicContext`
- `EndNode`：从 `DynamicContext` 取出 session，把 `sink` 变成最终 SSE Flux，并挂上清理和心跳逻辑

### 3-3.8 session 本体存在哪

session 真正的生命周期管理在 `SessionManagementService`，它内部用：

```java
private final Map<String, SessionConfigVO> activeSessions = new ConcurrentHashMap<>();
```

也就是：

- session 当前存放在 JVM 内存里
- key 是 `sessionId`
- value 是 `SessionConfigVO`

`SessionConfigVO` 本质上是 session 的状态载体，至少包含：

- `sessionId`
- `sink`
- `createTime`
- `lastAccessedTime`
- `active`

### 3-3.9 `sink`、`Flux`、`SSE` 分别是什么

三者关系可以这样看：

```text
业务代码
  -> sink.tryEmitNext(...)
  -> sink.asFlux()
  -> Flux<ServerSentEvent<String>>
  -> Spring WebFlux 响应
  -> SSE 长连接发给客户端
```

各自含义：

- `sink`：服务端往流里写消息的入口
- `Flux`：响应式数据流
- `SSE`：最终对外的 HTTP 推送协议

所以：

> `sink` 不是 SSE 本身，`sink` 是 SSE 数据流的生产入口。

### 3-3.10 心跳机制是什么

`EndNode` 里会定时发一个 `ping` 事件，并把它和真实业务流合并。

作用：

- 防止长时间无数据时，连接被浏览器、代理、LB、网关断开
- 让客户端知道连接还活着
- 在业务空闲时维持 SSE 通道

要注意区分：

- 心跳保的是“连接活着”
- session 超时判断保的是“这个会话最近有没有被访问”

这两个不是一回事。

### 3-3.11 `Mono`、`Flux`、`session`、`message` 的关系

- `Flux<T>`：0 到多个结果，适合流式 SSE
- `Mono<T>`：0 或 1 个结果，适合单次 HTTP 响应

所以：

- 建立 SSE 连接通常返回 `Flux<ServerSentEvent<String>>`
- 发送一条 message 通常返回 `Mono<ResponseEntity<Object>>`

`session` 和 `message` 的关系是：

- `session`：一条已经建立好的会话通道 / 上下文
- `message`：挂在某个 session 上的一次具体请求

可以把它理解成：

- `session` 像聊天室
- `message` 像聊天室里的一条消息

### 3-3.12 `SSE transport`、Spring AI MCP Client、`initialize()` 握手

`SSE transport` 的意思是：

- 用 SSE 这种传输方式来承载 MCP 客户端和服务端之间的通信

图里那套 `McpSyncClient`、`HttpClientSseClientTransport` 更像是 Spring AI 的 MCP Client SDK 用法，也就是：

- 你的应用直接创建一个 MCP client
- 通过 SSE transport 直连某个 MCP server
- 调用 `initialize()` 完成 MCP 协议层初始化

`initialize()` 不是 TCP 握手，它是应用协议层握手。顺序应理解为：

```text
TCP 连上
  -> HTTP 请求建立
  -> SSE 通道建立
  -> MCP initialize() 协议握手
```

所以：

- TCP 握手解决“链路通不通”
- MCP initialize 解决“协议怎么讲”

### 3-3.13 直连模式和 Gateway 模式

截图里的 `McpSyncClient` 直连百度 MCP，更像：

```text
你的应用 -> 直连 下游 MCP Server
```

而当前这个 gateway 要做的是：

```text
上游客户端 / Agent
  -> 连你的 Gateway
  -> Gateway 再去连下游 MCP Server
```

也就是：

- 直连：客户端自己管下游地址、session、协议差异
- Gateway：客户端先连你，你统一做接入、路由、鉴权、会话管理、治理

## 3-4 Message 分发、JSON 解析、截图里那些新写法

这一部分和前面的 session 建链不是同一层。

前面的 `RootNode -> VerifyNode -> SessionNode -> EndNode` 是：

- 一个请求顺序经过多个节点
- 属于责任链 / pipeline

这里的 `InitializeHandler / ToolsListHandler / ToolsCallHandler / ResourcesListHandler` 是：

- 收到一条 message
- 根据 `method` 选中一个 handler
- 只交给这一个 handler 处理

更像“面向对象版的 `switch-case`”。

### 3-4.1 为什么这里也叫 handler

因为这里的 `handler` 只是“某类消息的专用处理器”：

- `InitializeHandler`：处理 `initialize`
- `ToolsListHandler`：处理 `tools/list`
- `ToolsCallHandler`：处理 `tools/call`
- `ResourcesListHandler`：处理 `resources/list`

所以这里的 `handler` 和前面的 session 责任链节点不是一回事，只是名字复用了。

### 3-4.2 `McpSchemaVO` 本质在干什么

这块本质上是在做：

- 解析 JSON
- 判断这是 JSON-RPC request 还是 response
- 再映射成对应 Java 对象

可以压成三步：

```text
JSON 字符串
  -> Map
  -> 判断是 request 还是 response
  -> 转成 Java 对象
```

说白了，本质就是在做 JSON 解析，只是写法比较现代。

### 3-4.3 `sealed interface`、`record` 是什么

这里用了 Java 新写法：

- `sealed interface`：限制只有指定类型可以实现这个接口
- `record`：简化数据类，默认不可变，没有 setter

`record` 不是 public 字段，它更接近：

- 私有只读字段
- 自动生成访问器
- 自动生成构造器、`equals/hashCode/toString`

所以 `record` 默认只有读，没有写。

### 3-4.4 `POJO` 是什么

`POJO` 全称是：

**Plain Old Java Object**

意思是普通 Java 对象。传统 POJO 常见写法是：

- `private` 字段
- `getter/setter`
- 一个普通 class

而 `record` 更像是“适合数据载体的现代简化版写法”。

### 3-4.5 `Map.of(...)` 是什么

`Map.of(...)` 是快速创建不可变 `Map` 的写法。

例如：

```java
Map.of(
    "name", "xiaoran",
    "age", 18
)
```

意思是：

- `"name"` -> `"xiaoran"`
- `"age"` -> `18`

也就是按：

```text
key1, value1, key2, value2
```

成对写，用逗号分隔。

### 3-4.6 `SessionMessageHandlerMethodEnum` 是干什么的

这个枚举类本质上是一张静态路由表：

```text
initialize      => initializeHandler
tools/list      => toolsListHandler
tools/call      => toolsCallHandler
resources/list  => resourcesListHandler
```

它不处理业务，只负责把：

- `method`

映射成：

- `handlerName`

所以它的角色就是 message 分发阶段的“查表器”。

### 3-4.7 `SessionMessageService` 是怎么分发消息的

这段代码虽然看起来绕，但业务本质很简单：

1. 从 `message` 里取 `method`
2. 用枚举按 `method` 查到 `handlerName`
3. 从 Spring 注入的 `requestHandlerMap` 里取出对应 handler 实例
4. 调 `handler.handle(message)`

压成一句就是：

> `method -> 找 handler -> 执行 handler`

如果用最朴素的写法，它本质上接近：

```java
String method = message.method();

if ("initialize".equals(method)) {
    return initializeHandler.handle(message);
}
if ("tools/list".equals(method)) {
    return toolsListHandler.handle(message);
}
if ("tools/call".equals(method)) {
    return toolsCallHandler.handle(message);
}
if ("resources/list".equals(method)) {
    return resourcesListHandler.handle(message);
}

throw new AppException(...);
```

现在看到的复杂版本，只是把这段 `if-else` 拆成了：

- `message`
- `enum`
- `handlerName`
- Spring Bean Map
- `IRequestHandler`
- 具体 handler 实现

### 3-4.8 为什么会觉得这块特别绕

不是业务判断多，而是结构层次多。一个原本可以写成几段 `if-else` 的分发，被拆成了：

- `message`
- 路由枚举
- Bean 名称
- Spring 容器里的 handler 映射
- handler 接口
- 多个实现类

所以你完全可以先抓本质：

> “收到 `tools/call`，就把这条消息交给 `ToolsCallHandler`。”

先把这个本质抓住，再回头看这些抽象层就不容易晕。

## 3-5 `tools/list`、`tools/call`、SSE 回包、两层错误

这一部分其实是在把前面的 message 分发继续往下走，看到一个最小可运行的 MCP demo 是怎么闭环的。

主线可以先记成：

```text
客户端先建立 SSE session
  -> 服务端返回 messageEndpoint + sessionId
  -> 客户端 POST 一条 JSON-RPC message
  -> 服务端解析 message
  -> 按 method 分发给某个 handler
  -> handler 产出 JSONRPCResponse
  -> 服务端把 response 写回 session 对应的 sink
  -> 客户端从之前的 SSE 长连接收到 message 事件
```

### 3-5.1 `tools/list` 是干什么的

`tools/list` 不是执行工具，而是告诉客户端：

- 我这里有哪些工具
- 每个工具叫什么
- 工具描述是什么
- 调用这个工具需要什么参数

所以 `ToolsListHandler` 更像“工具目录接口”。

截图里那种 `toUpperCase`，本质上是在返回一个固定工具声明，像：

- tool name：`toUpperCase`
- description：把小写单词转成大写
- inputSchema：参数里需要一个 `word`

这一步是在“报菜单”，不是“做菜”。

### 3-5.2 `tools/call` 是干什么的

`tools/call` 才是真正执行工具调用。

客户端会传一条类似这样的请求：

```json
{
  "name": "toUpperCase",
  "arguments": {
    "word": "hello"
  }
}
```

服务端在 `ToolsCallHandler` 里做的事可以压成：

```text
取出 name
  -> 取出 arguments
  -> 判断是不是 toUpperCase
  -> 取出 word
  -> 执行 word.toUpperCase()
  -> 按 MCP response 格式返回 HELLO
```

所以：

- `tools/list`：告诉你“我有哪些工具”
- `tools/call`：真正调用其中一个工具

### 3-5.3 为什么例子总是 `toUpperCase`

不是因为这个工具本身重要，而是因为它最适合做教学 demo：

- 输入简单
- 逻辑简单
- 输出直观
- 不依赖数据库、外部服务、文件系统

所以作者是借 `toUpperCase` 这个最小例子，演示 MCP 里的完整闭环：

```text
tools/list
  -> 告诉客户端有个工具叫 toUpperCase

tools/call
  -> 客户端调用 toUpperCase(word=hello)

response
  -> 服务端返回 HELLO
```

重点不是“大写转换”这个业务，而是：

- tool 怎么声明
- tool 怎么被调用
- 结果怎么按 MCP 格式返回

### 3-5.4 这几个 handler 是什么关系

这里的几个 handler：

- `InitializeHandler`
- `ToolsListHandler`
- `ToolsCallHandler`
- `ResourcesListHandler`

它们不是责任链关系，而是并列分支关系。

也就是：

- 一条 message 进来
- 只会根据 `method` 选中其中一个 handler
- 不会几个 handler 按顺序都执行

更像：

```java
switch (method) {
    case "initialize" -> InitializeHandler
    case "tools/list" -> ToolsListHandler
    case "tools/call" -> ToolsCallHandler
    case "resources/list" -> ResourcesListHandler
}
```

所以要和前面的 session 节点区分开：

- `RootNode -> VerifyNode -> SessionNode -> EndNode`：串行责任链
- `InitializeHandler / ToolsListHandler / ToolsCallHandler / ResourcesListHandler`：method 分发的并列处理器

### 3-5.5 `Map.of(...)` 和 JSON 解析到底是什么关系

这块最容易混。

先直接分开：

- `Map.of(...)`：构造 Java 里的数据
- `ObjectMapper`：Java 对象和 JSON 字符串互转

所以：

- `Map.of(...)` 不是在解析 JSON
- 它只是快速创建一个 Java `Map`

例如：

```java
Map.of("name", "toUpperCase", "description", "小写转大写")
```

这时得到的还是 Java 对象，不是 JSON 字符串。

只有再经过：

```java
objectMapper.writeValueAsString(...)
```

才会真的变成 JSON 文本。

反过来，请求进来时：

- `messageBody` 先是 JSON 字符串
- 再通过 `deserializeJsonRpcMessage(...)` / `ObjectMapper` 反序列化成 Java 对象

所以：

- `Map.of(...)` 是“造结果”
- `ObjectMapper` 是“做转换”

### 3-5.6 为什么说这些 handler 现在有点 hardcode

这里的 hardcode 主要有两层。

第一层：路由写死。

例如：

- `initialize -> InitializeHandler`
- `tools/list -> ToolsListHandler`
- `tools/call -> ToolsCallHandler`

这说明系统只认识代码里提前登记过的 method。

第二层：响应内容也写死。

例如：

- `InitializeHandler` 里手工写死 `protocolVersion`、`capabilities`、`serverInfo`
- `ToolsListHandler` 里手工写死 `toUpperCase` 这个工具定义
- `ToolsCallHandler` 里手工写死只处理 `toUpperCase`

所以当前更像：

- 先 mock 一个最小 MCP Server
- 先把链路跑通
- 后面再做动态化、配置化、真实业务接入

### 3-5.7 message 请求进来以后，结果为什么不是直接 HTTP 返回

因为这套设计是：

- 请求走 POST
- 结果走 SSE

代码主线是：

```text
POST /{gatewayId}/mcp/message?sessionId=xxx
  -> 先按 sessionId 找 session
  -> 解析 JSON-RPC message
  -> 分发给 handler
  -> 得到 JSONRPCResponse
  -> session.getSink().tryEmitNext(...)
  -> 把 response 推回已建立好的 SSE 通道
```

所以这里的 HTTP 接口本身经常只是：

- 收到请求
- 处理完成
- 返回 `202 Accepted`

真正的业务结果，是通过：

```text
event: message
data: {...}
```

推回客户端。

一句话记忆：

> HTTP 是投递入口，SSE 是回包通道。

### 3-5.8 错误为什么要分两层看

这里至少要区分两套错误。

第一套：HTTP / 应用层错误。

例如：

- `sessionId` 找不到，返回 `404`
- 服务端异常，返回 `500`
- 参数非法，抛 `AppException`

它们回答的是：

> 这次 HTTP 请求本身有没有成功进入处理流程。

第二套：MCP / JSON-RPC 协议层错误。

例如：

- `method` 不支持
- tool 不存在
- `params` 结构不对

这时 HTTP 请求本身可能是成功的，但协议消息失败了，就应该返回：

- `result = null`
- `error = {...}`

它回答的是：

> 请求已经送到了，但这条 MCP 消息本身是否合法、能否执行。

最简单的区分方式：

- HTTP 错误：信封没送到
- MCP 错误：信送到了，但信里写错了

### 3-5.9 这一部分最该抓住的本质

如果只记一句，就记这句：

> `tools/list` 负责“报菜单”，`tools/call` 负责“点菜执行”，结果不直接走 HTTP body 返回，而是通过 session 对应的 SSE 通道推回客户端。

如果再加一句：

> 这里的几个 handler 是 method 分发的并列处理器，不是责任链节点。

## 3-7 Gateway 配置、Repository 落地、对象转换为什么这么多

这一部分的核心变化是：

- 前面的 demo 版本很多内容是 hardcode
- 到这里开始把配置从代码里抽出去
- 同时也开始把协议对象、业务对象、数据库对象分开建模

所以这一段看起来会比 3-5 更乱，因为它不只是“处理 message”，而是在补整套真实实现需要的中间层。

### 3-7.1 这里的 `gateway` 不是“整个系统只有一个网关”

最容易误解的一点就是：

- `mcp-gateway` 是整个系统 / 平台
- `gatewayId` 是这个平台里的某个逻辑网关实例标识

也就是：

- 不是“一个系统只能有一个 gateway”
- 而是“一个系统里可以配置多个 gateway”

例如：

```text
text-gateway
  -> toUpperCase
  -> translateText

weather-gateway
  -> queryWeather

docs-gateway
  -> searchDocs
```

客户端建立连接时访问的是：

```text
/{gatewayId}/mcp/sse
```

这就说明客户端不是只连“整个系统”，而是要先指定“连系统里的哪个 gateway”。

一句话记忆：

> 这个项目是网关平台，`gatewayId` 是平台里的某个具体接入配置。

### 3-7.2 为什么会有多张表，不是一张表搞定

因为这里要存的不是一类信息，而是几类职责不同的信息。

大致可以这么理解：

- `mcp_gateway`：存 gateway 本身是谁
- `mcp_gateway_auth`：存这个 gateway 怎么鉴权
- `mcp_protocol_registry`：存 tool / protocol 怎么调用
- `mcp_protocol_mapping`：存 gateway 和 tool / protocol 的关联关系

之所以拆开，是因为系统默认：

- 可能有很多个 gateway
- 也可能有很多个 tool / protocol
- 一个 gateway 可能挂多个 tool
- 一个 tool 也可能被多个 gateway 复用

例如：

```text
gateway A -> toUpperCase, translateText
gateway B -> queryWeather
gateway C -> toUpperCase, searchDocs
```

这时如果全塞一张表，会重复很多字段；拆表后就更像：

- gateway 名单
- tool 名单
- gateway 和 tool 的关联表
- 各 gateway 的鉴权表

所以这里不是“为了复杂而复杂”，而是为了支持：

- 多 gateway
- 多 tool
- 多对多关系

### 3-7.3 `McpGatewayConfigVO` 是什么

`McpGatewayConfigVO` 可以理解成：

> 给业务层用的一条“网关能力配置摘要”

它不是数据库表对象，也不是 session 运行时对象，而是把当前 gateway 初始化时真正关心的那部分信息收敛到一起。

例如它里边可能有：

- `gatewayId`
- `gatewayName`
- `toolId`
- `toolName`
- `toolDesc`
- `toolVersion`

也就是把：

- 某个 gateway 是谁
- 它挂的 tool 是谁
- 对外展示的描述和版本是什么

打包成一个业务可直接使用的对象。

所以后面 `InitializeHandler`、`ToolsListHandler` 这些类，就不需要自己去拼很多零碎字段，而是可以直接拿这个 `VO` 来组装响应。

### 3-7.4 为什么接口在 `domain`，实现放 `infrastructure`

这就是依赖倒置在这里的具体表现。

规则可以先记成：

- `domain` 定义“我要什么能力”
- `infrastructure` 负责“具体怎么实现”

例如：

- `domain` 里定义 `ISessionRepository`
- `infrastructure` 里写 `SessionRepository`

意思是：

- 业务层只说“我需要一个 repository 能给我 gateway 配置”
- 但不关心这个数据来自数据库、缓存还是远程服务

所以：

- 高层业务不直接依赖 MyBatis / SQL
- 高层业务只依赖抽象接口
- 底层实现去适配这个接口

一句话记忆：

> 不是业务层跟着数据库跑，而是数据库实现去适配业务层定义的接口。

### 3-7.5 `Repository`、`DAO`、`PO`、`VO` 各自干什么

这一段对象特别多，最好先把层级分开。

#### `PO`

`PO` 是持久化对象，基本对应数据库表的一行记录。

例如：

- `McpGatewayPO`
- `McpProtocolRegistryPO`

它们更贴近数据库字段。

#### `DAO`

`DAO` 是数据访问接口，定义怎么查库。

例如：

- `IMcpGatewayDao`
- `IMcpProtocolRegistryDao`

这些接口通常对应 MyBatis 的 mapper。

#### `mapper.xml`

这是 MyBatis 的 SQL 映射文件。

它的作用是：

- 给 DAO 方法绑定 SQL
- 查具体哪张表
- 结果映射成哪个 `PO`

例如：

```xml
<select id="queryMcpProtocolRegistryByGatewayId" ...>
    SELECT ...
    FROM mcp_protocol_registry
    WHERE gateway_id = #{gateway_id}
</select>
```

#### `Repository`

`Repository` 不是直接给 handler 原始数据库结果，而是：

- 调 DAO
- 拿到一个或多个 `PO`
- 组装成业务层真正想要的对象

例如这里的 `SessionRepository`：

- 先查 `McpGatewayPO`
- 再查 `McpProtocolRegistryPO`
- 然后把两者拼成 `McpGatewayConfigVO`

#### `VO`

`VO` 更偏业务视角，不一定一一对应表结构。

例如：

- `McpGatewayConfigVO`
- `SessionConfigVO`

它们是业务层更愿意直接拿来用的对象。

一句话压缩：

> DAO 面向数据库，Repository 面向业务。

### 3-7.6 为什么 `Repository` 里判空这么简单，不一定抛异常

比如这种写法：

```java
if (null == mcpGatewayPO) return null;
if (null == mcpProtocolRegistryPO) return null;
```

不一定说明作者偷懒，更可能是在表达：

> repository 只负责如实告诉上层“有没有数据”，不急着在这一层定性成异常。

因为“查不到”有两种可能：

- 正常业务分支：确实没有这条配置
- 业务异常：按规则本该有，但现在没查到

而这个判断通常上层更清楚。

所以这里常见的分工是：

- repository：查到了就返回，查不到就 `null`
- service / handler：决定查不到时是报错、兜底，还是走默认逻辑

### 3-7.7 为什么这里开始新增很多 schema 类型

到这里，作者已经不满足于一直用：

- `Map<String, Object>`
- `Map.of(...)`

去硬拼协议结构了。

于是开始把 MCP 协议里的结构正式建模，例如：

- `InitializeRequest`
- `InitializeResult`
- `Request`
- 以及对应的 capability / implementation 类型

这类对象不是数据库 schema，而是：

> MCP / JSON-RPC 消息结构的 Java 模型

也就是把原来那种：

```java
Map.of("protocolVersion", ..., "capabilities", ...)
```

升级成：

```java
new InitializeResult(...)
```

这样做的好处是：

- 结构更清楚
- 字段名更稳定
- 更适合 IDE 提示
- 少很多手工 `Map` 强转

### 3-7.8 `unmarshalFrom(...)` 到底在做什么

这也是很容易晕的一点。

```java
public static <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
    return objectMapper.convertValue(data, typeRef);
}
```

这里不是直接“解析原始 JSON 字符串”，而是在做：

> 通用 Java 对象 -> 更具体的强类型对象

例如：

- `message.params()` 先可能只是一个 `Map`
- 再通过 `unmarshalFrom(...)`
- 转成 `InitializeRequest`

所以它更像：

```text
Object / Map
  -> InitializeRequest
```

不是：

```text
JSON 字符串
  -> InitializeRequest
```

真正原始 JSON 到 Java 对象的第一步，通常更早就已经做完了。

### 3-7.9 为什么会觉得“这些写法不统一”

因为这里实际上混了三种不同的转换，但表面上都像“在搞 JSON”。

第一种：原始 JSON 文本反序列化

```text
String messageBody
  -> JSONRPCMessage
```

第二种：通用对象再转具体 schema

```text
message.params()
  -> InitializeRequest
```

第三种：Java 对象再序列化回 JSON 文本

```text
JSONRPCResponse
  -> JSON 字符串
```

所以你会觉得乱，是因为这里不是一种转换，而是三种转换混在一起出现。

### 3-7.10 `JSONRPCResponse` 是什么

`JSONRPCResponse` 不是 JSON 字符串，而是：

> 项目里自己定义的 JSON-RPC 响应对象模型

它表示一条响应消息至少有：

- `jsonrpc`
- `id`
- `result`
- `error`

例如：

```java
new JSONRPCResponse("2.0", message.id(), initializeResult, null)
```

这时它还是 Java 对象，不是发出去的 JSON 文本。

只有后面再经过 `ObjectMapper.writeValueAsString(...)`，它才真正变成 JSON 字符串。

### 3-7.11 `InitializeHandler` 在这一版里发生了什么变化

这是 3-7 最值得抓的一条主线。

旧版本更像：

- 直接写死 `Map.of(...)`
- 写死 server name / version / capabilities

新版本开始变成：

1. 先把 `message.params()` 转成 `InitializeRequest`
2. 根据 `gatewayId` 查 `McpGatewayConfigVO`
3. 组装 `InitializeResult`
4. 再包装成 `JSONRPCResponse`

所以这条链可以写成：

```text
message.params()
  -> InitializeRequest
  -> repository 查配置
  -> McpGatewayConfigVO
  -> InitializeResult
  -> JSONRPCResponse
```

这说明初始化处理已经从：

- demo 硬编码版

走向：

- schema 类型化 + repository 配置驱动版

### 3-7.12 这一部分最该抓住的本质

如果只记一句，就记这句：

> 3-7 的核心不是某个单独类，而是系统开始把“硬编码 demo”升级成“数据库配置驱动 + 协议对象类型化”的真实实现。

如果再补一句：

> 这一段最容易乱，是因为一直在做协议对象、业务对象、数据库对象之间的来回翻译。

可以把对象先粗分成 4 类：

- 协议外壳对象：`JSONRPCRequest`、`JSONRPCResponse`
- 协议内容对象：`InitializeRequest`、`InitializeResult`
- 业务配置对象：`McpGatewayConfigVO`
- 持久化对象：`McpGatewayPO`、`McpProtocolRegistryPO`

以后再看这段代码，先问自己：

1. 这是协议对象？
2. 这是业务对象？
3. 这是数据库对象？
4. 这是在反序列化，还是在组装响应？

只要这四个问题先分清，这一段就不会那么绕。
