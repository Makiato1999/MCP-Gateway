package com.makiatox.ai.infrastructure.dao;

import com.makiatox.ai.infrastructure.dao.po.McpProtocolRegistryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IMcpProtocolRegistryDao {

    int insert(McpProtocolRegistryPO po);

    int deleteById(Long id);

    int updateById(McpProtocolRegistryPO po);

    McpProtocolRegistryPO queryById(Long id);

    List<McpProtocolRegistryPO> queryAll();

}
