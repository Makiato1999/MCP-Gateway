package com.makiatox.ai.api;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

// This is the entry point for client-side streaming communication with the MCP Gateway
public interface IMcpGatewayService {
    /**
     * 建立 SSE 连接
     * @param gatewayId 网关ID
     * @return 流式响应
     */
    Flux<ServerSentEvent<String>> establishSSEConnection(String gatewayId) throws Exception;

}
