package com.makiatox.ai.domain.session.service.message.handler;

import com.makiatox.ai.domain.session.model.valobj.McpSchemaVO;

public interface IRequestHandler {
    McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message);
}
