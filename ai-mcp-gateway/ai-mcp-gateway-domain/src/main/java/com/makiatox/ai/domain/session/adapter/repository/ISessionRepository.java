package com.makiatox.ai.domain.session.adapter.repository;

import com.makiatox.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import com.makiatox.ai.domain.session.model.valobj.gateway.McpGatewayProtocolConfigVO;
import com.makiatox.ai.domain.session.model.valobj.gateway.McpGatewayToolConfigVO;

import java.util.List;

/**
 * 会话仓储接口
 *
 */

public interface ISessionRepository {
    McpGatewayConfigVO queryMcpGatewayConfigByGatewayId(String gatewayId);

    List<McpGatewayToolConfigVO> queryMcpGatewayToolConfigListByGatewayId(String gatewayId);

    McpGatewayProtocolConfigVO queryMcpGatewayProtocolConfig(String gatewayId);
}
