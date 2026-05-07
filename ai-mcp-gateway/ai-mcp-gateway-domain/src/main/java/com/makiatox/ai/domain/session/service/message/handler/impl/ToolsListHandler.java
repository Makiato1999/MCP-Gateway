package com.makiatox.ai.domain.session.service.message.handler.impl;

import com.makiatox.ai.domain.session.adapter.repository.ISessionRepository;
import com.makiatox.ai.domain.session.model.valobj.McpSchemaVO;
import com.makiatox.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import com.makiatox.ai.domain.session.model.valobj.gateway.McpGatewayToolConfigVO;
import com.makiatox.ai.domain.session.service.message.handler.IRequestHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service("toolsListHandler")
public class ToolsListHandler implements IRequestHandler {
    @Resource
    private ISessionRepository repository;

    /**
     * `tools/list` 的目标是把数据库中的“字段配置表”动态还原成 MCP Tool 列表。
     *
     * 可以先把 `mcp_protocol_mapping` 理解成一棵拆平后存入数据库的树：
     *
     * 1. 根节点
     *    - xxxRequest01
     *    - xxxRequest02
     * 2. xxxRequest01 的子节点
     *    - city
     *    - company
     * 3. company 的子节点
     *    - name
     *    - type
     * 4. xxxRequest02 的子节点
     *    - employeeCount
     *
     * 还原后的树大致如下：
     *
     * <pre>
     * xxxRequest01
     * |- city
     * `- company
     *    |- name
     *    `- type
     *
     * xxxRequest02
     * `- employeeCount
     * </pre>
     *
     * 上面这棵树最终会被组装成一个 Tool 的 inputSchema。结构化后的响应示例如下：
     *
     * <pre>
     * {
     *   "tools": [
     *     {
     *       "name": "getCompanyEmployee",
     *       "description": "获取公司雇员信息",
     *       "inputSchema": {
     *         "type": "object",
     *         "additionalProperties": false,
     *         "properties": {
     *           "xxxRequest01": {
     *             "type": "object",
     *             "properties": {
     *               "city": {
     *                 "type": "string",
     *                 "description": "城市名称,如果是中文汉字请先转换为汉语拼音,例如北京:beijing"
     *               },
     *               "company": {
     *                 "type": "object",
     *                 "description": "公司信息,如果是中文汉字请先转换为汉语拼音,例如北京:jd/alibaba",
     *                 "properties": {
     *                   "name": {
     *                     "type": "string",
     *                     "description": "公司名称"
     *                   },
     *                   "type": {
     *                     "type": "string",
     *                     "description": "公司类型"
     *                   }
     *                 },
     *                 "required": [
     *                   "name",
     *                   "type"
     *                 ]
     *               }
     *             },
     *             "required": [
     *               "city",
     *               "company"
     *             ]
     *           },
     *           "xxxRequest02": {
     *             "type": "object",
     *             "properties": {
     *               "employeeCount": {
     *                 "type": "string",
     *                 "description": "雇员姓名"
     *               }
     *             },
     *             "required": [
     *               "employeeCount"
     *             ]
     *           }
     *         },
     *         "required": [
     *           "xxxRequest01",
     *           "xxxRequest02"
     *         ]
     *       }
     *     }
     *   ]
     * }
     * </pre>
     *
     * 整体过程可以理解为：
     * 1. 查数据库，拿到扁平字段列表。
     * 2. 按 `toolId` 分组，一个 `toolId` 生成一个 Tool。
     * 3. 用 `parentPath -> children` 建 childrenMap，把扁平表恢复成树。
     * 4. 从根节点开始 DFS 递归，把每个节点转换成 JSON Schema property。
     */
    @Override
    public McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message) {

        // 1. 网关配置
        McpGatewayConfigVO mcpGatewayConfigVO = repository.queryMcpGatewayConfigByGatewayId(gatewayId);

        // 2. 查询网关（gatewayId）下的工具列表配置
        List<McpGatewayToolConfigVO> mcpGatewayToolConfigVOS = repository.queryMcpGatewayToolConfigListByGatewayId(gatewayId);

        // 3. 构建工具列表
        List<McpSchemaVO.Tool> tools = buildTools(mcpGatewayConfigVO, mcpGatewayToolConfigVOS);

        return new McpSchemaVO.JSONRPCResponse("2.0", message.id(), Map.of(
                "tools", tools
        ), null);
    }

    private List<McpSchemaVO.Tool> buildTools(McpGatewayConfigVO gatewayConfig, List<McpGatewayToolConfigVO> toolConfigs) {
        // 1. 先按 toolId 分组。
        // 一个 toolId 对应一组字段配置，最终会生成一个 MCP Tool。
        Map<Long, List<McpGatewayToolConfigVO>> toolsMap = toolConfigs.stream()
                .collect(Collectors.groupingBy(McpGatewayToolConfigVO::getToolId));

        List<McpSchemaVO.Tool> tools = new ArrayList<>();

        for (Map.Entry<Long, List<McpGatewayToolConfigVO>> entry : toolsMap.entrySet()) {
            Long toolId = entry.getKey();
            List<McpGatewayToolConfigVO> configs = entry.getValue();

            // 2. 先按 sortOrder 排序，保证同级节点输出顺序稳定。
            configs.sort((o1, o2) -> {
                int s1 = o1.getSortOrder() != null ? o1.getSortOrder() : 0;
                int s2 = o2.getSortOrder() != null ? o2.getSortOrder() : 0;
                return Integer.compare(s1, s2);
            });

            // 3. 把扁平字段列表恢复成“父 -> 子列表”的邻接表结构。
            // childrenMap 的 key 是父节点路径，value 是这个父节点的直接孩子列表。
            // roots 则保存 parentPath == null 的根节点。
            Map<String, List<McpGatewayToolConfigVO>> childrenMap = new HashMap<>();
            List<McpGatewayToolConfigVO> roots = new ArrayList<>();

            for (McpGatewayToolConfigVO config : configs) {
                if (config.getParentPath() == null) {
                    roots.add(config);
                } else {
                    childrenMap.computeIfAbsent(config.getParentPath(), k -> new ArrayList<>()).add(config);
                }
            }

            // 根节点也需要按 sortOrder 排序。
            roots.sort((o1, o2) -> {
                int s1 = o1.getSortOrder() != null ? o1.getSortOrder() : 0;
                int s2 = o2.getSortOrder() != null ? o2.getSortOrder() : 0;
                return Integer.compare(s1, s2);
            });

            // 4. 从所有根节点开始构建最外层 inputSchema。
            // 这里相当于把多棵子树挂到 inputSchema.properties 下。
            Map<String, Object> properties = new HashMap<>();
            List<String> required = new ArrayList<>();

            for (McpGatewayToolConfigVO root : roots) {
                // buildProperty 是递归入口：从 root 一层层向下 DFS 生成整棵子树。
                properties.put(root.getFieldName(), buildProperty(root, childrenMap));
                if (Integer.valueOf(1).equals(root.getIsRequired())) {
                    required.add(root.getFieldName());
                }
            }

            // 如果只有一个根节点，就沿用根节点类型；多个根节点时，最外层一定是 object。
            String type = roots.size() == 1 ? roots.get(0).getMcpType() : "object";

            // 5. 组装最终的 inputSchema。
            McpSchemaVO.JsonSchema inputSchema = new McpSchemaVO.JsonSchema(
                    type,
                    properties,
                    required.isEmpty() ? null : required,
                    false,
                    null,
                    null
            );

            // 6. 根据网关配置补 Tool 的 name / description。
            String name = "unknown-tool-" + toolId;
            String desc = "";
            if (gatewayConfig != null && Objects.equals(gatewayConfig.getToolId(), toolId)) {
                name = gatewayConfig.getToolName();
                desc = gatewayConfig.getToolDesc();
            }

            tools.add(new McpSchemaVO.Tool(name, desc, inputSchema));
        }
        return tools;
    }

    private Map<String, Object> buildProperty(McpGatewayToolConfigVO current, Map<String, List<McpGatewayToolConfigVO>> childrenMap) {
        // DFS 处理当前节点：
        // 先生成当前节点自己的基础 property，再递归处理它的 children。
        Map<String, Object> property = new HashMap<>();
        property.put("type", current.getMcpType());
        if (current.getMcpDesc() != null) {
            property.put("description", current.getMcpDesc());
        }

        // childrenMap 的 key 是父节点 mcpPath，所以这里用 current.mcpPath 找直接孩子。
        List<McpGatewayToolConfigVO> children = childrenMap.get(current.getMcpPath());
        if (children != null && !children.isEmpty()) {
            Map<String, Object> props = new HashMap<>();
            List<String> reqs = new ArrayList<>();

            // 先排好孩子顺序，再递归向下构建。
            children.sort((o1, o2) -> {
                int s1 = o1.getSortOrder() != null ? o1.getSortOrder() : 0;
                int s2 = o2.getSortOrder() != null ? o2.getSortOrder() : 0;
                return Integer.compare(s1, s2);
            });

            for (McpGatewayToolConfigVO child : children) {
                // 典型的多叉树 DFS：
                // 当前节点处理完后，递归构建每个 child，再把 child 结果挂回当前 property。
                props.put(child.getFieldName(), buildProperty(child, childrenMap));
                if (Integer.valueOf(1).equals(child.getIsRequired())) {
                    reqs.add(child.getFieldName());
                }
            }

            property.put("properties", props);

            if (!reqs.isEmpty()) {
                property.put("required", reqs);
            }

        }

        return property;
    }
}
