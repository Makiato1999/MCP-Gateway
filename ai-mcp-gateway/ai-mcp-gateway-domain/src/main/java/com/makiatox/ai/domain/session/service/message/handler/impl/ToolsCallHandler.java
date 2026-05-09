package com.makiatox.ai.domain.session.service.message.handler.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.makiatox.ai.domain.session.adapter.port.ISessionPort;
import com.makiatox.ai.domain.session.adapter.repository.ISessionRepository;
import com.makiatox.ai.domain.session.model.valobj.McpSchemaVO;
import com.makiatox.ai.domain.session.model.valobj.gateway.McpGatewayProtocolConfigVO;
import com.makiatox.ai.domain.session.service.message.handler.IRequestHandler;

import com.makiatox.ai.types.enums.McpErrorCodes;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * `tools/call` 消息处理器。
 * <p>
 * 这个类是 `tools/call` 这条链路的协议入口层，负责把客户端发来的 MCP 工具调用请求，
 * 翻译成一次真实的下游协议调用，再把结果包装回 MCP / JSON-RPC 响应。
 * <p>
 * 可以把它理解成这 4 步：
 * 1. 根据 gatewayId 查询协议调用配置（例如 HTTP URL、Method、Headers）
 * 2. 把 `message.params()` 转成 `CallToolRequest`
 * 3. 把协议配置 + arguments 交给 `SessionPort` 真正发 HTTP 请求
 * 4. 把下游结果包装成 `JSONRPCResponse.result`
 * <p>
 * 这里自己不直接做 HTTP 请求，真正执行请求的是 `ISessionPort`。
 * 当前实现先按最小链路跑通：
 * - tool name 还没有真正参与多 tool 分发
 * - 返回结构先手工用 Map 组装
 * - 错误统一包装成 JSON-RPC error
 */
@Slf4j
@Service("toolsCallHandler")
public class ToolsCallHandler implements IRequestHandler {

    @Resource
    private ISessionRepository repository;

    @Resource
    private ISessionPort port;

    @Override
    public McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message) {
        try {
            // 1. 查询当前 gateway 对应的协议调用配置。
            // 这里拿到的是“怎么调下游”的配置，而不是 tools/list 那种字段树配置。
            McpGatewayProtocolConfigVO mcpGatewayProtocolConfigVO = repository.queryMcpGatewayProtocolConfig(gatewayId);

            // 2. 把 JSON-RPC request.params 转成 tools/call 专属请求对象。
            // 到这里为止，客户端传来的 name + arguments 就有了强类型结构。
            McpSchemaVO.CallToolRequest callToolRequest =
                    McpSchemaVO.unmarshalFrom(message.params(), new TypeReference<>() {
                    });

            Object argumentsObj = callToolRequest.arguments();

            // todo 暂时工具名称还没有真正参与路由，当前默认还是 gateway -> tool 的简单关系。
            String name = callToolRequest.name();

            // 3. 把协议配置和 arguments 交给 SessionPort 执行真正的下游调用。
            // SessionPort 内部会继续判断 GET / POST，并通过 GenericHttpGateway 发 HTTP 请求。
            Object result = port.toolCall(mcpGatewayProtocolConfigVO.getHttpConfig(), argumentsObj);

            // 4. 按 MCP 的 tool call result 结构，把下游结果包装回 JSON-RPC response。
            // 当前先把结果作为 text 内容返回，后续可以继续收敛成更强类型的 VO。
            return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION, message.id(), Map.of(
                    "content", new Object[]{
                            Map.of(
                                    "type", "text",
                                    "text", result
                            ),

                    },
                    "isError", "false"
            ), null);

        } catch (Exception e) {
            // 当前实现先统一兜底，把异常包装成 JSON-RPC error 返回给客户端。
            return new McpSchemaVO.JSONRPCResponse(McpSchemaVO.JSONRPC_VERSION,
                    message.id(),
                    null,
                    new McpSchemaVO.JSONRPCResponse.JSONRPCError(McpErrorCodes.INVALID_PARAMS, e.getMessage(), null));

        }

    }

}
