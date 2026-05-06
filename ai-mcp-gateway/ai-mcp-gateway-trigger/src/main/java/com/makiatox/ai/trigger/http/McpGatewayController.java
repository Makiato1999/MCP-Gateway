package com.makiatox.ai.trigger.http;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.makiatox.ai.api.IMcpGatewayService;
import com.makiatox.ai.cases.mcp.IMcpSessionService;
import com.makiatox.ai.domain.session.model.valobj.McpSchemaVO;
import com.makiatox.ai.domain.session.model.valobj.SessionConfigVO;
import com.makiatox.ai.domain.session.service.ISessionManagementService;
import com.makiatox.ai.domain.session.service.ISessionMessageService;
import com.makiatox.ai.types.enums.ResponseCode;
import com.makiatox.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
/**
 * MCP 网关服务接口管理
 *
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
@RequestMapping("/")
public class McpGatewayController implements IMcpGatewayService {

    @Resource
    private IMcpSessionService mcpSessionService;

    // todo 暂时调用 domain 测试，后续调用 case 编排
    @Resource
    private ISessionMessageService serviceMessageService;

    @Resource
    private ISessionManagementService sessionManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    public McpGatewayController() {
        System.out.println("xxxx");
    }

    /**
     * 建立sse连接，创建会话
     * <br/>
     * <a href="http://localhost:8777/api-gateway/test10001/mcp/sse">http://localhost:8777/api-gateway/test10001/mcp/sse</a>
     *
     * @param gatewayId 网关ID
     */
    @GetMapping(value = "{gatewayId}/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public Flux<ServerSentEvent<String>> establishSSEConnection(@PathVariable("gatewayId") String gatewayId) throws Exception {
        try {
            log.info("建立 MCP SSE 连接，gatewayId:{}", gatewayId);
            if (StringUtils.isBlank(gatewayId)) {
                log.info("非法参数，gateway is null");
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
            }

            return mcpSessionService.createMcpSession(gatewayId);
        } catch (Exception e) {
            log.error("建立 MCP SSE 连接失败，gatewayId: {}", gatewayId, e);
            throw e;
        }
    }

    /**
     * 处理 sse 消息，响应会话
     *
     * @param gatewayId 网关ID
     * @param sessionId 会话ID
     * @param messageBody 请求消息
     * @return 响应结果
     * <br/>
     * {
     *     "jsonrpc": "2.0",
     *     "method": "initialize",
     *     "id": "95835f74-0",
     *     "params": {
     *         "protocolVersion": "2024-11-05",
     *         "capabilities": {},
     *         "clientInfo": {
     *             "name": "Java SDK MCP Client",
     *             "version": "1.0.0"
     *         }
     *     }
     * }
     *
     *  接收 MCP 客户端发送的 JSON-RPC 消息，并通过对应会话的 SSE 通道异步回推处理结果。
     *
     *  当前方法只负责消息接收、反序列化、分发处理，以及将响应写入 session 对应的 SSE sink。
     *  HTTP 响应本身不直接承载业务结果，而仅返回接收状态
     */
    @PostMapping(value = "{gatewayId}/mcp/sse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> handleMessage(@PathVariable("gatewayId") String gatewayId,
                                                      @RequestParam String sessionId,
                                                      @RequestBody String messageBody) {
        try {
            log.info("处理 MCP SSE 消息，gatewayId:{} sessionId:{} messageBody:{}", gatewayId, sessionId, messageBody);
            SessionConfigVO session = sessionManagementService.getSession(sessionId);
            if (null == session) {
                log.warn("会话不存在或已过期，gatewayId:{} sessionId:{}", gatewayId, sessionId);
                return Mono.just(ResponseEntity.notFound().build());
            }

            McpSchemaVO.JSONRPCMessage jsonrpcMessage = McpSchemaVO.deserializeJsonRpcMessage(messageBody);
            log.info("反序列化消息:{}", jsonrpcMessage.jsonrpc());

            // 暂时直接调用 domain，后续调整
            McpSchemaVO.JSONRPCResponse jsonrpcResponse = serviceMessageService.processHandlerMessage(jsonrpcMessage);

            if (null != jsonrpcResponse) {
                String responseJson = objectMapper.writeValueAsString(jsonrpcResponse);
                session.getSink().tryEmitNext(ServerSentEvent.<String>builder()
                        .event("message")
                        .data(responseJson)
                        .build());
            }

            log.info("调用结果:{}", JSON.toJSONString(jsonrpcResponse));
            return Mono.just(ResponseEntity.accepted().build());
        } catch (Exception e) {
            log.error("处理 MCP SSE 消息失败，gatewayId:{} sessionId:{} messageBody:{}", gatewayId, sessionId, messageBody, e);
            return Mono.just(ResponseEntity.internalServerError().build());
        }

    }

}

