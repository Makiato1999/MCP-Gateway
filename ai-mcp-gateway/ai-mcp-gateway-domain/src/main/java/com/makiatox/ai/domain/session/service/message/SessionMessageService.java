package com.makiatox.ai.domain.session.service.message;

import com.alibaba.fastjson.JSON;
import com.makiatox.ai.domain.session.model.valobj.McpSchemaVO;
import com.makiatox.ai.domain.session.service.ISessionMessageService;
import com.makiatox.ai.domain.session.service.message.handler.IRequestHandler;
import com.makiatox.ai.types.enums.SessionMessageHandlerMethodEnum;
import com.makiatox.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.makiatox.ai.types.enums.ResponseCode.METHOD_NOT_FOUND;

/**
 * 会话消息服务
 *
 */
@Slf4j
@Service
public class SessionMessageService implements ISessionMessageService {

    @Resource
    private Map<String, IRequestHandler> requestHandlerMap;
    // 可以注入具体实现类，但注入接口更解耦；如果接口有多个实现，Spring 不能随便猜一个，
    // 所以要么指定具体 Bean，要么像这里一样把所有实现注入成 Map<String, 接口> 再按名字动态选。

    // 在处理 AI 客户端，调用 MCP 服务的时候，会有多种类型参数，
    // JSONRPCResponse、JSONRPCRequest、JSONRPCNotification，
    // 所以 processHandlerMessage 方法的入参也要做对应的调整。并在方法内，根据 instanceof 判断类的类型分别进行使用
    @Override
    public McpSchemaVO.JSONRPCResponse processHandlerMessage(String gatewayId, McpSchemaVO.JSONRPCMessage message) {
        if (message instanceof McpSchemaVO.JSONRPCResponse response) {
            log.info("收到结果消息");
        }

        if (message instanceof McpSchemaVO.JSONRPCRequest request) {
            String method = request.method();
            log.info("开始处理请求，方法: {}", method);

            SessionMessageHandlerMethodEnum sessionMessageHandlerMethodEnum = SessionMessageHandlerMethodEnum.getByMethod(method);
            if (null == sessionMessageHandlerMethodEnum) {
                throw new AppException(METHOD_NOT_FOUND.getCode(), METHOD_NOT_FOUND.getInfo());
            }

            String handlerName = sessionMessageHandlerMethodEnum.getHandlerName();
            IRequestHandler requestHandler = requestHandlerMap.get(handlerName);

            if (null == requestHandler) {
                throw new AppException(METHOD_NOT_FOUND.getCode(), METHOD_NOT_FOUND.getInfo());
            }

            // 使用枚举策略模式处理请求
            return requestHandler.handle(gatewayId, request);
        }

        if (message instanceof McpSchemaVO.JSONRPCNotification notification) {
            log.info("收到即将处理的通知 {} {}", notification.method(), JSON.toJSONString(notification.params()));
        }
        return null;
    }
}