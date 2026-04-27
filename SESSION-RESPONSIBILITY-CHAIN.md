# Session 责任链笔记

这部分代码的复杂度，确实高于日常直接写 Controller -> Service -> Repository 的直线式代码。

原因不是业务本身特别复杂，而是它叠了几层抽象：

- 响应式流：`Flux`、`Sinks`
- SSE 长连接：`ServerSentEvent`
- 责任链框架：`StrategyHandler`、`router()`、`get()`
- 业务节点：`RootNode`、`VerifyNode`、`SessionNode`、`EndNode`

所以第一次看会有一种“每个类都不长，但连起来很绕”的感觉。这是正常的。

## 1. 先记住一句话

这个 session 模块做的事是：

> 把一次 `GET /{gatewayId}/mcp/sse` 请求，变成一个可持续推送的 SSE 会话，并在内存里维护这个会话，直到断开或超时。

## 2. 整体调用链

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
  -> 浏览器/客户端建立 SSE 长连接
```

## 3. 责任链里每层分别干什么

### 3.1 Controller 层

入口在：

- `ai-mcp-gateway-trigger/.../McpGatewayController.java`

它负责：

- 接收 HTTP 请求
- 做基础参数校验
- 调用 `mcpSessionService.createMcpSession(gatewayId)`

这里它不负责创建 session，也不负责组装 SSE 细节。

### 3.2 McpSessionService

入口在：

- `ai-mcp-gateway-case/.../McpSessionService.java`

它负责：

- 从 `DefaultMcpSessionFactory` 拿到责任链入口
- 创建一个 `DynamicContext`
- 调用 `strategyHandler.apply(gatewayId, context)`

它本身像一个“流程启动器”。

### 3.3 DefaultMcpSessionFactory

位置：

- `ai-mcp-gateway-case/.../factory/DefaultMcpSessionFactory.java`

虽然类名叫 `Factory`，但它现在做的事情其实很轻：

- 返回责任链入口 `rootNode`
- 定义链路上下文 `DynamicContext`

所以它不是经典“抽象工厂模式”里那种创建一组对象的工厂，更像：

- 责任链入口提供者
- 上下文定义处

## 4. 核心抽象怎么理解

### 4.1 `StrategyHandler<T, D, R>` 是什么

它是统一处理器接口：

```java
R apply(T requestParameter, D dynamicContext) throws Exception;
```

可以翻译成：

> 任何节点，只要能接收“请求参数 + 上下文”，并返回一个结果，它就可以是一个 handler。

在这个项目里，具体类型是：

```java
StrategyHandler<String, DynamicContext, Flux<ServerSentEvent<String>>>
```

对应意思：

- `String`：`gatewayId`
- `DynamicContext`：链路共享上下文
- `Flux<ServerSentEvent<String>>`：最终返回的 SSE 数据流

### 4.2 `AbstractMultiThreadStrategyRouter` 是什么

这个类是责任链真正运转的框架底座。

它做两件核心事：

- `apply(...)`：执行当前节点
- `router(...)`：跳转到下一个节点

关键代码逻辑可以压缩成：

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

怎么理解：

- `apply()`：进入当前节点
- `doApply()`：当前节点真正做事
- `get()`：决定下一个节点是谁
- `router()`：把请求交给下一个节点

一句话记忆：

> `apply = 进节点`，`doApply = 节点干活`，`get = 找下家`，`router = 传给下家`

### 4.3 `AbstractMcpSessionSupport` 是什么

位置：

- `ai-mcp-gateway-case/.../AbstractMcpSessionSupport.java`

它是这个项目在 session 场景下定义的业务基类。

它的作用是：

- 统一泛型类型
- 给所有 session 节点注入公共依赖 `ISessionManagementService`
- 复用上层责任链路由能力

继承关系可以这样看：

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

## 5. 四个节点各自负责什么

### 5.1 RootNode

职责：

- 作为责任链入口节点
- 打日志
- 调用 `router()` 把请求传给 `VerifyNode`

它基本不做业务。

### 5.2 VerifyNode

职责：

- 预留鉴权/参数校验位置
- 当前实现里还没真正落业务
- 直接路由给 `SessionNode`

这个节点是以后扩展认证、租户校验、权限校验的插槽。

### 5.3 SessionNode

职责：

- 真正创建 session
- 调用 `sessionManagementService.createSession(gatewayId)`
- 把结果写入 `DynamicContext`
- 再把链路交给 `EndNode`

这是“创建会话”的核心节点。

### 5.4 EndNode

职责：

- 从 `DynamicContext` 里拿出 `SessionConfigVO`
- 取出其中的 `sink`
- `sink.asFlux()` 转成 SSE 流
- 合并心跳流
- 注册连接取消/终止时的清理逻辑

这是“把内存中的 session，转换成最终 SSE 响应”的节点。

## 6. session 本体到底存在哪

session 真正的生命周期管理不在 node 里，而在：

- `ai-mcp-gateway-domain/.../SessionManagementService.java`

它内部用：

```java
private final Map<String, SessionConfigVO> activeSessions = new ConcurrentHashMap<>();
```

也就是说，当前 session 存在 JVM 内存里。

### 6.1 createSession 做了什么

- 生成 `sessionId`
- 创建 `Sinks.Many<ServerSentEvent<String>>`
- 先推送一条 `endpoint` 事件给客户端
- 组装成 `SessionConfigVO`
- 放入 `activeSessions`

### 6.2 getSession 做了什么

- 按 `sessionId` 获取会话
- 如果会话还活着，刷新 `lastAccessedTime`

### 6.3 removeSession 做了什么

- 从 `activeSessions` 移除
- 标记 inactive
- 调用 `sink.tryEmitComplete()` 结束流

### 6.4 cleanupExpiredSessions 做了什么

- 定时扫描会话表
- 清理失活或超时 session

## 7. `sink`、`Flux`、`SSE` 是什么关系

可以用这张图记：

```text
业务代码
  -> sink.tryEmitNext(...)
  -> sink.asFlux()
  -> Flux<ServerSentEvent<String>>
  -> Spring WebFlux 响应
  -> SSE 长连接发给客户端
```

含义分别是：

- `sink`：服务端往流里写消息的入口
- `Flux`：响应式数据流
- `SSE`：最终对外传输协议

所以：

> `sink` 不是 SSE 本身，`sink` 是 SSE 数据流的生产入口。

## 8. 心跳机制是什么

心跳代码在 `EndNode`：

```java
Flux.interval(Duration.ofSeconds(60))
    .map(i -> ServerSentEvent.<String>builder()
        .event("ping")
        .data("ping")
        .build())
```

然后它会和真实业务流合并：

```java
sink.asFlux().mergeWith(heartbeatFlux)
```

作用：

- 防止长时间无数据导致连接被浏览器、代理、LB、网关断开
- 告诉客户端连接还活着
- 在业务空闲时维持 SSE 通道

注意：

- 心跳保的是“连接活着”
- `session` 超时保的是“这个会话最近有没有被访问”

这两个不是一回事。

## 9. 为什么会感觉复杂

如果你平常写的是这种代码：

```text
Controller -> Service -> DAO -> return
```

那这个项目比它复杂，主要是因为这里不是一次性请求返回，而是：

- 有长连接
- 有流式输出
- 有 session 生命周期
- 有责任链调度
- 有节点扩展点

所以它不是“业务判断多”，而是“框架抽象层次多”。

## 10. 最简单的阅读方式

以后再看这块代码，建议只按这个顺序读：

1. `McpGatewayController.establishSSEConnection`
2. `McpSessionService.createMcpSession`
3. `DefaultMcpSessionFactory.strategyHandler`
4. `RootNode.doApply`
5. `VerifyNode.doApply`
6. `SessionNode.doApply`
7. `SessionManagementService.createSession`
8. `EndNode.doApply`

不要一开始就试图把所有抽象类、接口、泛型一次吃透。

先盯住主流程：

> 请求进来 -> 启动责任链 -> 创建 session -> 返回 SSE 流

再回头理解：

- `StrategyHandler`
- `router`
- `apply`
- `sink`

会顺很多。

## 11. `interface`、`abstract class`、`abstract method`、业务实现类分别是什么

这一块是最容易混淆的地方，因为它们都在参与同一条责任链，但职责完全不同。

先记一句话：

> `interface` 定义能力，`abstract class` 定义骨架，`abstract method` 留出扩展点，业务类负责把具体逻辑补上。

### 11.1 `interface` 是能力约定

典型例子：

- `StrategyHandler<T, D, R>`

它的核心方法只有一个：

```java
R apply(T requestParameter, D dynamicContext) throws Exception;
```

这不是在实现业务，而是在规定：

- 任何 handler 都必须能被 `apply(...)`
- 上层只要拿到一个 `StrategyHandler`，就知道怎么调用它

所以 `interface` 的价值是：

- 统一调用协议
- 屏蔽具体实现
- 让上层只关心“能不能这样调用”，不关心内部怎么做

这也是为什么 `rootNode` 可以作为 `StrategyHandler` 返回出去。

### 11.2 `abstract class` 是流程骨架

典型例子：

- `AbstractMultiThreadStrategyRouter<T, D, R>`

它已经把节点执行流程固定下来了：

```java
public R apply(T requestParameter, D dynamicContext) throws Exception {
    this.multiThread(requestParameter, dynamicContext);
    return this.doApply(requestParameter, dynamicContext);
}
```

也就是说，父类已经规定好了：

1. 先进入 `apply()`
2. 再执行预处理 `multiThread()`
3. 再执行节点主逻辑 `doApply()`

这类抽象类的作用是：

- 实现公共流程
- 避免重复代码
- 强制所有子类按统一模板执行

这本质上就是模板方法模式。

### 11.3 `abstract method` 是父类留给子类的扩展点

典型例子：

```java
protected abstract void multiThread(T var1, D var2)
protected abstract R doApply(T var1, D var2) throws Exception;
```

抽象方法的意思不是“这里没写完”，而是：

- 父类知道这里必须有一个步骤
- 但父类不知道不同业务场景下该怎么做
- 所以强制子类自己实现

可以把它理解成“预留插槽”。

比如父类知道：

- 所有节点都要走 `apply()`
- 但不是所有节点都做同样的业务

所以：

- `multiThread()` 留给子类决定是否需要并行逻辑
- `doApply()` 留给子类决定当前节点具体干什么

### 11.4 为什么还要有业务抽象类

典型例子：

- `AbstractMcpSessionSupport`

它是项目里的“业务域基类”，作用不是再造一层复杂度，而是把 session 场景的共性收敛起来。

它做的事：

- 固定泛型为 `String + DynamicContext + Flux<ServerSentEvent<String>>`
- 注入公共依赖 `ISessionManagementService`
- 给所有 session 节点提供统一父类

这样 `RootNode`、`VerifyNode`、`SessionNode`、`EndNode` 就不用重复写：

- 一长串泛型
- 一次依赖注入
- 一次 `multiThread` 空实现

所以这层可以理解成：

- 上接框架抽象
- 下接 session 业务节点

### 11.5 业务实现类负责补具体逻辑

典型例子：

- `RootNode`
- `VerifyNode`
- `SessionNode`
- `EndNode`

它们不是在定义规则，而是在填规则。

父类已经规定：

- 节点要能 `apply()`
- 节点执行要走统一模板
- 节点可以通过 `router()` 传给下一个节点

业务子类要补的是：

- `doApply()`：当前节点到底做什么
- `get()`：当前节点之后应该跳到谁

例如：

- `RootNode.doApply()`：打日志并路由给下一个节点
- `SessionNode.doApply()`：创建 session，写入 context，再继续路由
- `EndNode.doApply()`：从 context 取 session，组装 SSE Flux 并返回

### 11.6 用一句话区分这几层

可以这样背：

- `interface`：规定“你必须会什么”
- `abstract class`：规定“你必须按什么步骤做”
- `abstract method`：规定“这里必须做，但具体怎么做由你实现”
- 业务实现类：真正把“怎么做”写出来

### 11.7 这套设计为什么值得这样拆

因为它想把：

- 框架能力
- 通用流程
- 业务差异

分开。

如果以后要往责任链里加节点，比如：

- `AuthNode`
- `TenantCheckNode`
- `RateLimitNode`
- `AuditNode`

就只需要：

- 继承同一套父类
- 实现自己的 `doApply()`
- 调整 `get()` 的下一跳

而不需要把整条主流程重写一遍。

### 11.8 看到类似代码时，优先问自己三个问题

1. 这个 `interface` 规定了什么能力？
2. 这个 `abstract class` 已经帮我实现了什么公共流程？
3. 这个业务子类只需要补哪些具体步骤？

这三个问题想清楚，抽象层次就不会打架。

## 12. 当前实现的一个现状

这套代码目前完整实现的是：

- session 创建
- SSE 建链
- 心跳
- 断连清理
- 超时清理

但我在仓库里没有看到 `/mcp/message` 的实际处理入口。

也就是说，现在已经把：

- “先建立 SSE session”

这一半写完了，但：

- “客户端后续如何用 `sessionId` 发 message，再把消息写回对应 sink”

这半段还没完整落地出来。

## 13. 最后一句

你现在觉得复杂，不代表你理解慢，而是这段代码确实不是“业务直写型代码”。

它的难点不在某个 if/else，而在：

- 框架抽象
- 流式编程
- 责任链组合

先把这份笔记里的 4 个词记住就够了：

- `apply`
- `doApply`
- `get`
- `router`

它们就是这条责任链的骨架。
