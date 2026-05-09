package com.makiatox.ai.domain.session.model.valobj;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 架构值对象
 * <p>
 * public：访问修饰符，表示该接口和 record 是公开的，任何地方都可以访问。
 * sealed：限制继承，实现密封接口的类必须在 permits 中声明。
 * interface：定义接口。
 * record：定义不可变数据载体类。
 * implements：表示实现接口。
 * <p>
 * Jackson 注解用于控制 JSON 序列化和反序列化行为。
 *
 */
@Slf4j
public class McpSchemaVO {
    /**
     * 当前项目默认支持的 MCP 协议版本。
     */
    public static final String LATEST_PROTOCOL_VERSION = "2024-11-05";

    /**
     * JSON-RPC 协议版本。
     */
    public static final String JSONRPC_VERSION = "2.0";

    private static final TypeReference<HashMap<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
    };

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 反序列化外层 JSON-RPC 消息。
     * <p>
     * 这个方法处理的是“整段原始 JSON 文本”，目标是先判断这条消息属于：
     * - Request
     * - Notification
     * - Response
     * <p>
     * 也就是说，这一步只负责把最外层协议壳子拆出来，
     * 例如先得到 JSONRPCRequest，再由后续代码根据 method 决定 params 应该转成什么具体对象。
     * <p>
     * 可以把它理解成：先拆信封，先知道这是一封什么类型的信。
     */
    public static JSONRPCMessage deserializeJsonRpcMessage(String jsonText)
            throws IOException {

        log.debug("Received JSON message: {}", jsonText);

        var map = objectMapper.readValue(jsonText, MAP_TYPE_REF);

        if (map.containsKey("method") && map.containsKey("id")) {
            return objectMapper.convertValue(map, JSONRPCRequest.class);
        } else if (map.containsKey("method") && !map.containsKey("id")) {
            return objectMapper.convertValue(map, JSONRPCNotification.class);
        } else if (map.containsKey("result") || map.containsKey("error")) {
            return objectMapper.convertValue(map, JSONRPCResponse.class);
        }

        throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
    }

    /**
     * 把外层 JSON-RPC 对象里的某一块数据，再转换成内层强类型对象。
     * <p>
     * 常见场景：
     * - `message.params()` -> `InitializeRequest`
     * - `message.params()` -> `CallToolRequest`
     * - `response.result()` -> 某个 method 对应的 result 对象
     * <p>
     * 和 deserializeJsonRpcMessage 的区别：
     * - deserializeJsonRpcMessage：处理整段 JSON 文本，先得到外层壳子
     * - unmarshalFrom：处理壳子里的 params / result，把它转成 method 专属对象
     * <p>
     * 可以把它理解成：信封已经拆开了，现在把里面的内容按目标结构重新整理出来。
     */
    public static  <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return objectMapper.convertValue(data, typeRef);
    }


    /**
     * JSON-RPC 2.0 外层消息壳子。
     * <p>
     * 可以把 MCP 消息先统一理解成两层：
     * 1. 外层：JSON-RPC 壳子，负责 method / id / params / result 这些通用字段。
     * 2. 内层：某个具体 method 自己的业务对象，例如 InitializeRequest、ListToolsResult。
     * <p>
     * 先进入系统的一定是这一层：
     * - Request：客户端发请求
     * - Response：服务端回结果
     * - Notification：客户端或服务端发通知
     */
    public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCResponse, JSONRPCNotification {

        String jsonrpc();

    }

    /**
     * 请求对象
     *
     * @param jsonrpc 协议版本 2.0
     * @param method  请求方法；initialize、tools/list、tools/call、resources/list
     * @param id      请求ID
     * @param params  请求参数
     * <p>
     * 注意：这里的 params 还只是外层壳子里的原始对象。
     * 真正进入具体 handler 后，才会根据 method 再转换成对应的强类型对象，例如：
     * - method = initialize -> params 转成 InitializeRequest
     * - method = tools/list  -> 当前通常无复杂 params，可直接处理
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JSONRPCRequest(@JsonProperty("jsonrpc") String jsonrpc,
                                 @JsonProperty("method") String method,
                                 @JsonProperty("id") Object id,
                                 @JsonProperty("params") Object params
    ) implements JSONRPCMessage {
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JSONRPCNotification(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("method") String method,
            @JsonProperty("params") Object params) implements JSONRPCMessage {
    }

    /**
     * 响应对象
     *
     * @param jsonrpc 协议版本 2.0
     * @param id      请求ID
     * @param result  响应结果
     * @param error   异常结果
     * <p>
     * 注意：result 也是一个“外层槽位”，里面装的是什么取决于当前 method。
     * 常见情况：
     * - initialize -> result 是 InitializeResult
     * - tools/list -> result 是 ListToolsResult，或者当前过渡阶段也可能是 Map
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JSONRPCResponse(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") Object id,
            @JsonProperty("result") Object result,
            @JsonProperty("error") JSONRPCError error
    ) implements JSONRPCMessage {
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record JSONRPCError(
                @JsonProperty("code") int code,
                @JsonProperty("message") String message,
                @JsonProperty("data") Object data) {
        }
    }

    /**
     * method 专属请求对象的标记接口。
     * <p>
     * 当前只有 initialize 显式定义了专属请求结构，所以这里只有 InitializeRequest。
     * 如果后面 tools/call、resources/read 等 method 需要强类型 params，也可以继续往这里扩展。
     */
    public sealed interface Request
            permits CallToolRequest, InitializeRequest {

    }

    // ---------------------------
    // Initialization
    // ---------------------------
    /**
     * initialize 请求参数对象。
     * <p>
     * 这部分对应客户端发来的初始化请求内容：
     * - protocolVersion：客户端请求使用的协议版本
     * - capabilities：客户端支持的能力
     * - clientInfo：客户端自身信息
     * <p>
     * 可以把它理解成：客户端第一次连上 MCP Server 时，主动报上来的“握手信息”。
     * <p>
     * initialize 的层级关系：
     * <pre>
     * JSONRPCRequest
     * `- params
     *    `- InitializeRequest
     *       |- protocolVersion
     *       |- capabilities
     *       `- clientInfo
     * </pre>
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitializeRequest( // @formatter:off
                                     @JsonProperty("protocolVersion") String protocolVersion,
                                     @JsonProperty("capabilities") ClientCapabilities capabilities,
                                     @JsonProperty("clientInfo") Implementation clientInfo) implements Request {
    } // @formatter:on

    /**
     * initialize 响应结果对象。
     * <p>
     * 这部分对应服务端返回给客户端的初始化结果：
     * - protocolVersion：服务端最终确认使用的协议版本
     * - capabilities：服务端支持的能力
     * - serverInfo：服务端自身信息
     * - instructions：给客户端的额外说明
     * <p>
     * 可以把它理解成：服务端对 initialize 请求的正式响应内容。
     * <p>
     * initialize 响应层级关系：
     * <pre>
     * JSONRPCResponse
     * `- result
     *    `- InitializeResult
     *       |- protocolVersion
     *       |- capabilities
     *       |- serverInfo
     *       `- instructions
     * </pre>
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitializeResult( // @formatter:off
                                    @JsonProperty("protocolVersion") String protocolVersion,
                                    @JsonProperty("capabilities") ServerCapabilities capabilities,
                                    @JsonProperty("serverInfo") Implementation serverInfo,
                                    @JsonProperty("instructions") String instructions) {
    }

    /**
     * 客户端能力定义。
     * <p>
     * 表示客户端在 initialize 请求里告诉服务端：
     * “我支持哪些能力，你后续可以按这些能力和我协作”。
     * <p>
     * 注意：这里的 capabilities 不是服务端能力，而是客户端自己的能力声明。
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientCapabilities( // @formatter:off
                                      @JsonProperty("experimental") Map<String, Object> experimental,
                                      @JsonProperty("roots") RootCapabilities roots,
                                      @JsonProperty("sampling") Sampling sampling) {

        /**
         * Roots define the boundaries of where servers can operate within the filesystem,
         * allowing them to understand which directories and files they have access to.
         * Servers can request the list of roots from supporting clients and
         * receive notifications when that list changes.
         *
         * @param listChanged Whether the client would send notification about roots
         * 		  has changed since the last time the server checked.
         */
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record RootCapabilities(
                @JsonProperty("listChanged") Boolean listChanged) {
        }

        /**
         * Provides a standardized way for servers to request LLM
         * sampling ("completions" or "generations") from language
         * models via clients. This flow allows clients to maintain
         * control over model access, selection, and permissions
         * while enabling servers to leverage AI capabilities—with
         * no server API keys necessary. Servers can request text or
         * image-based interactions and optionally include context
         * from MCP servers in their prompts.
         */
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public record Sampling() {
        }

        /**
         * 构建客户端能力对象，便于按需组装字段。
         * <p>
         * 这里的 Builder 只是帮助代码里更清晰地构建嵌套结构。
         */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Map<String, Object> experimental;
            private RootCapabilities roots;
            private Sampling sampling;

            public Builder experimental(Map<String, Object> experimental) {
                this.experimental = experimental;
                return this;
            }

            public Builder roots(Boolean listChanged) {
                this.roots = new RootCapabilities(listChanged);
                return this;
            }

            public Builder sampling() {
                this.sampling = new Sampling();
                return this;
            }

            public ClientCapabilities build() {
                return new ClientCapabilities(experimental, roots, sampling);
            }
        }
    }// @formatter:on

    /**
     * 服务端能力定义。
     * <p>
     * 表示服务端在 initialize 响应里告诉客户端：
     * “我支持哪些 MCP 能力，例如 tools、resources、prompts 等”。
     * <p>
     * 可以把它理解成：服务端对外公开的能力清单。
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerCapabilities( // @formatter:off
                                      @JsonProperty("completions") CompletionCapabilities completions,
                                      @JsonProperty("experimental") Map<String, Object> experimental,
                                      @JsonProperty("logging") LoggingCapabilities logging,
                                      @JsonProperty("prompts") PromptCapabilities prompts,
                                      @JsonProperty("resources") ResourceCapabilities resources,
                                      @JsonProperty("tools") ToolCapabilities tools) {

        /**
         * 表示服务端支持 completions 能力。
         */
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public record CompletionCapabilities() {
        }

        /**
         * 表示服务端支持 logging 能力。
         */
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public record LoggingCapabilities() {
        }

        /**
         * 表示服务端支持 prompts 能力。
         */
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public record PromptCapabilities(
                @JsonProperty("listChanged") Boolean listChanged) {
        }

        /**
         * 表示服务端支持 resources 能力。
         */
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public record ResourceCapabilities(
                @JsonProperty("subscribe") Boolean subscribe,
                @JsonProperty("listChanged") Boolean listChanged) {
        }

        /**
         * 表示服务端支持 tools 能力。
         */
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        public record ToolCapabilities(
                @JsonProperty("listChanged") Boolean listChanged) {
        }

        /**
         * 构建服务端能力对象，便于按需组装能力字段。
         * <p>
         * 和客户端能力一样，这里主要是为了少写多层 new。
         */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {

            private CompletionCapabilities completions;
            private Map<String, Object> experimental;
            private LoggingCapabilities logging = new LoggingCapabilities();
            private PromptCapabilities prompts;
            private ResourceCapabilities resources;
            private ToolCapabilities tools;

            public Builder completions() {
                this.completions = new CompletionCapabilities();
                return this;
            }

            public Builder experimental(Map<String, Object> experimental) {
                this.experimental = experimental;
                return this;
            }

            public Builder logging() {
                this.logging = new LoggingCapabilities();
                return this;
            }

            public Builder prompts(Boolean listChanged) {
                this.prompts = new PromptCapabilities(listChanged);
                return this;
            }

            public Builder resources(Boolean subscribe, Boolean listChanged) {
                this.resources = new ResourceCapabilities(subscribe, listChanged);
                return this;
            }

            public Builder tools(Boolean listChanged) {
                this.tools = new ToolCapabilities(listChanged);
                return this;
            }

            public ServerCapabilities build() {
                return new ServerCapabilities(completions, experimental, logging, prompts, resources, tools);
            }
        }
    } // @formatter:on

    /**
     * MCP 实现方的基础信息对象。
     * <p>
     * 在 initialize 流程里：
     * - 客户端侧用它表示 clientInfo
     * - 服务端侧用它表示 serverInfo
     * <p>
     * 也就是“这一方是谁、版本是多少”的公共结构。
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Implementation(// @formatter:off
                                 @JsonProperty("name") String name,
                                 @JsonProperty("version") String version) {
    } // @formatter:on

    /*
     * record 使用记忆：
     * 1. record 适合表示“只负责装数据”的协议对象。
     * 2. 创建对象时直接 new，例如：new Implementation("client-a", "1.0.0")
     * 3. 取值不是 getName()，而是 name()、version() 这种同名方法。
     * 4. 这里大量使用 record，是因为 MCP initialize 的请求和响应本质上都是结构化数据。
     * 5. initialize 这条链路可以简单记为：
     *    JSONRPCRequest -> InitializeRequest -> InitializeResult -> JSONRPCResponse
     */

    /**
     * `tools/list` 的 result 对象。
     * <p>
     * 对应 MCP 返回里的：
     * <pre>
     * {
     *   "tools": [...],
     *   "nextCursor": "..."
     * }
     * </pre>
     * <p>
     * tools/list 的响应层级关系：
     * <pre>
     * JSONRPCResponse
     * `- result
     *    `- ListToolsResult
     *       |- tools
     *       |  `- List<Tool>
     *       `- nextCursor
     * </pre>
     * <p>
     * 当前项目里的 ToolsListHandler 仍处于过渡实现阶段，最外层 result 可能暂时直接返回 Map；
     * 但结构意图上，推荐理解成这里的 ListToolsResult。
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListToolsResult( // @formatter:off
                                   @JsonProperty("tools") List<Tool> tools,
                                   @JsonProperty("nextCursor") String nextCursor) {
    }// @formatter:on

    /**
     * 单个 MCP Tool 的定义。
     * <p>
     * 对应 `tools/list` 结果里的一个元素：
     * <pre>
     * {
     *   "name": "...",
     *   "description": "...",
     *   "inputSchema": { ... }
     * }
     * </pre>
     * <p>
     * 层级关系：
     * <pre>
     * ListToolsResult
     * `- tools[]
     *    `- Tool
     *       |- name
     *       |- description
     *       `- inputSchema
     *          `- JsonSchema
     * </pre>
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tool( // @formatter:off
                        @JsonProperty("name") String name,
                        @JsonProperty("description") String description,
                        @JsonProperty("inputSchema") JsonSchema inputSchema) {

        public Tool(String name, String description, String schema) {
            this(name, description, parseSchema(schema));
        }

    } // @formatter:on


    /**
     * Tool 的输入参数 Schema。
     * <p>
     * 这部分本质上是在表达 JSON Schema，描述一个 Tool 需要什么输入参数。
     * 当前项目里，这个结构通常不是手写死的，而是由数据库中的字段配置树递归构造出来。
     * <p>
     * 层级关系：
     * <pre>
     * Tool
     * `- inputSchema
     *    `- JsonSchema
     *       |- type
     *       |- properties
     *       |- required
     *       |- additionalProperties
     *       |- $defs
     *       `- definitions
     * </pre>
     * <p>
     * 其中最关键的是：
     * - type：当前节点类型，例如 object / string
     * - properties：子字段定义
     * - required：必填字段名列表
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsonSchema( // @formatter:off
                              @JsonProperty("type") String type,
                              @JsonProperty("properties") Map<String, Object> properties,
                              @JsonProperty("required") List<String> required,
                              @JsonProperty("additionalProperties") Boolean additionalProperties,
                              @JsonProperty("$defs") Map<String, Object> defs,
                              @JsonProperty("definitions") Map<String, Object> definitions) {
    } // @formatter:on

    /**
     * 把字符串形式的 JSON Schema 解析成 JsonSchema 对象。
     * <p>
     * 这个辅助方法主要服务于 Tool 的便捷构造函数：
     * - 外部如果已经拿到一段 schema JSON 字符串
     * - 可以直接 new Tool(name, description, schemaText)
     * - 内部再统一转成 JsonSchema
     */
    private static JsonSchema parseSchema(String schema) {
        try {
            return objectMapper.readValue(schema, JsonSchema.class);
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Invalid schema: " + schema, e);
        }
    }

    /**
     * `tools/call` 的请求参数对象。
     * <p>
     * 这一层对应的是 JSON-RPC request 里的 params 部分，例如：
     * <pre>
     * {
     *   "method": "tools/call",
     *   "params": {
     *     "name": "getCompanyEmployee",
     *     "arguments": { ... }
     *   }
     * }
     * </pre>
     * <p>
     * 字段含义：
     * - name：本次要调用哪个 MCP Tool
     * - arguments：传给这个 Tool 的输入参数
     * <p>
     * 这里 `implements Request` 不是为了实现某个方法，而是为了表明：
     * `CallToolRequest` 也属于“method 专属请求对象”这一类。
     * <p>
     * record 自带主构造函数：
     * - new CallToolRequest(name, argumentsMap)
     * <p>
     * 下面额外补的构造函数只是一个便捷入口，允许直接传 JSON 字符串，
     * 再在内部自动解析成 `Map<String, Object>`，并不是 record 工作所必需的。
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallToolRequest(// @formatter:off
                                  @JsonProperty("name") String name,
                                  @JsonProperty("arguments") Map<String, Object> arguments) implements Request {

        /**
         * 便捷构造函数：
         * 支持直接传入 JSON 字符串形式的 arguments，
         * 内部自动解析成 Map，再复用 record 的主构造函数。
         */
        public CallToolRequest(String name, String jsonArguments) {
            this(name, parseJsonArguments(jsonArguments));
        }

        /**
         * 把 JSON 字符串解析成 arguments Map。
         */
        private static Map<String, Object> parseJsonArguments(String jsonArguments) {
            try {
                return objectMapper.readValue(jsonArguments, MAP_TYPE_REF);
            }
            catch (IOException e) {
                throw new IllegalArgumentException("Invalid arguments: " + jsonArguments, e);
            }
        }
    }// @formatter:off
}
