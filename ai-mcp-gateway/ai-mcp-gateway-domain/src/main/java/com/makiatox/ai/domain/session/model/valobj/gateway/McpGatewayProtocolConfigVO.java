package com.makiatox.ai.domain.session.model.valobj.gateway;

import lombok.*;

/**
 * 协议调用级配置对象。
 * <p>
 * 这个对象不关心 tool 的展示信息，也不关心字段树怎么长，
 * 它关心的是：某个 tool 最终应该如何调用下游协议，例如 HTTP URL、Method、Headers、Timeout 等。
 * <p>
 * 主要用途：
 * - `tools/call`：把 MCP 工具调用翻译成真实的 HTTP 请求
 * <p>
 * 可以把它理解成：执行阶段使用的“下游调用配置 VO”。
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpGatewayProtocolConfigVO {
    private HTTPConfig httpConfig;

    @Data
    public static class HTTPConfig {
        private String httpUrl;
        private String httpHeaders;
        private String httpMethod;
        private Integer timeout;
    }

}
