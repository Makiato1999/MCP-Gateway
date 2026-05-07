package com.makiatox.ai.domain.session.service;

import com.makiatox.ai.domain.session.model.valobj.McpSchemaVO;

public interface ISessionMessageService {
    McpSchemaVO.JSONRPCResponse processHandlerMessage(String gatewayId, McpSchemaVO.JSONRPCMessage message);

}
