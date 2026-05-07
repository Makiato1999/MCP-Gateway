package com.makiatox.ai.domain.session.model.valobj;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
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

    public static  <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return objectMapper.convertValue(data, typeRef);
    }


    /**
     * JSON-RPC 2.0 Message Types
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

    public sealed interface Request
            permits InitializeRequest {

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

}
