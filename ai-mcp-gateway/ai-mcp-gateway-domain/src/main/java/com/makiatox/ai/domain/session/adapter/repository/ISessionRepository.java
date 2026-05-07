package com.makiatox.ai.domain.session.adapter.repository;

import com.makiatox.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;

/**
 * 会话仓储接口
 *
 */

public interface ISessionRepository {
    McpGatewayConfigVO queryMcpGatewayConfigByGatewayId(String gatewayId);
}
