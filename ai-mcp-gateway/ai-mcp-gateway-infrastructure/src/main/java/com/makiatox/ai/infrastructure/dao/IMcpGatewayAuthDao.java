package com.makiatox.ai.infrastructure.dao;

import com.makiatox.ai.infrastructure.dao.po.McpGatewayAuthPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IMcpGatewayAuthDao {

    int insert(McpGatewayAuthPO po);

    int deleteById(Long id);

    int updateById(McpGatewayAuthPO po);

    McpGatewayAuthPO queryById(Long id);

    List<McpGatewayAuthPO> queryAll();


}
