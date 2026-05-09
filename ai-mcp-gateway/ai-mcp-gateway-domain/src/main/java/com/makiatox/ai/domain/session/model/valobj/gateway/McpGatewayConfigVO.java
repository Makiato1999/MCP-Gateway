package com.makiatox.ai.domain.session.model.valobj.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 网关级摘要配置对象。
 * <p>
 * 这个对象不关心字段树细节，也不关心具体 HTTP 调用参数，
 * 它更像“当前 gateway 挂了哪个 tool、这个 tool 叫什么、描述和版本是什么”的概览信息。
 * <p>
 * 主要用途：
 * - `initialize`：组装服务端基础信息
 * - `tools/list`：补充 tool 的 name / description / version 等摘要信息
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpGatewayConfigVO {
    /**
     * 网关ID
     */
    private String gatewayId;

    /**
     * 网关名称
     */
    private String gatewayName;

    /**
     * 工具ID
     */
    private Long toolId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具描述
     */
    private String toolDesc;

    /**
     * 工具版本
     */
    private String toolVersion;
}
