package com.makiatox.ai.cases.mcp.session.factory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.makiatox.ai.cases.mcp.session.node.RootNode;
import com.makiatox.ai.domain.session.model.valobj.SessionConfigVO;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * MCP 会话服务工厂, 告诉流程入口是谁 + 定义 context
 *
 */
@Service
public class DefaultMcpSessionFactory {

    @Resource
    private RootNode rootNode;

    public StrategyHandler<String, DynamicContext, Flux<ServerSentEvent<String>>> strategyHandler() {
        return rootNode;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {
        private SessionConfigVO sessionConfigVO;
    }

}

