package com.makiatox.ai.domain.session.model.valobj.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 工具字段级配置对象。
 * <p>
 * 这个对象描述的是某个 tool 的字段树节点信息，
 * 例如 parentPath、fieldName、mcpPath、字段类型、必填、排序等。
 * <p>
 * 主要用途：
 * - `tools/list`：把数据库中的扁平字段配置恢复成树
 * - 递归生成 `JsonSchema`
 * <p>
 * 可以把它理解成：生成 `inputSchema` 时使用的“字段节点 VO”。
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpGatewayToolConfigVO {
    /**
     * 所属网关ID
     */
    private String gatewayId;
    /**
     * 所属工具ID
     */
    private Long toolId;
    /**
     * 映射类型：request-请求参数映射，response-响应数据映射
     */
    private String mappingType;
    /**
     * 父级路径（如：xxxRequest01，用于构建嵌套结构，根节点为NULL）
     */
    private String parentPath;
    /**
     * 字段名称（如：city、company、name）
     */
    private String fieldName;
    /**
     * MCP完整路径（如：xxxRequest01.city、xxxRequest01.company.name）
     */
    private String mcpPath;
    /**
     * MCP数据类型：string/number/boolean/object/array
     */
    private String mcpType;
    /**
     * MCP字段描述
     */
    private String mcpDesc;
    /**
     * 是否必填：0-否，1-是（用于生成required数组）
     */
    private Integer isRequired;
    /**
     * 排序顺序（同级字段排序）
     */
    private Integer sortOrder;
}
