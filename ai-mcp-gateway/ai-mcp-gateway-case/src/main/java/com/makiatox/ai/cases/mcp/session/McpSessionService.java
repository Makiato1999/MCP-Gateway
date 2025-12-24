package com.makiatox.ai.cases.mcp.session;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.makiatox.ai.cases.mcp.IMcpSessionService;
import com.makiatox.ai.cases.mcp.session.factory.DefaultMcpSessionFactory;
import jakarta.annotation.Resource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 会话服务接口, 对外暴露一个方法
 *
 * 入口函数：createMcpSession(gatewayId)
 * 上下文背包：DynamicContext（在链路里传递状态）
 * 处理管线：rootNode.apply(...)（按节点顺序处理并返回 SSE stream）
 */
@Service
public class McpSessionService implements IMcpSessionService {

    @Resource
    private DefaultMcpSessionFactory defaultMcpSessionFactory;

    @Override
    public Flux<ServerSentEvent<String>> createMcpSession(String gatewayId) throws Exception {

        StrategyHandler<String, DefaultMcpSessionFactory.DynamicContext, Flux<ServerSentEvent<String>>> strategyHandler = defaultMcpSessionFactory.strategyHandler();

        return strategyHandler.apply(gatewayId, new DefaultMcpSessionFactory.DynamicContext());
    }

}
