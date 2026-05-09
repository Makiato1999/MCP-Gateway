package com.makiatox.ai.infrastructure.adapter.port;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.makiatox.ai.domain.session.adapter.port.ISessionPort;
import com.makiatox.ai.domain.session.model.valobj.gateway.McpGatewayProtocolConfigVO;
import com.makiatox.ai.infrastructure.gateway.GenericHttpGateway;
import com.makiatox.ai.types.enums.ResponseCode;
import com.makiatox.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;
import retrofit2.Call;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话端口实现。
 * <p>
 * 这个类在 `tools/call` 链路里的职责，不是解析 JSON-RPC，也不是查数据库配置，
 * 而是拿到已经准备好的协议调用配置和参数之后，真正把它翻译成一次 HTTP 请求发出去。
 * <p>
 * 可以把它理解成：
 * <pre>
 * ToolsCallHandler
 * -> 查 McpGatewayProtocolConfigVO
 * -> 解析 arguments
 * -> SessionPort.toolCall(...)
 * -> 调 GenericHttpGateway
 * -> 拿到下游 HTTP 结果
 * </pre>
 * <p>
 * 也就是说：
 * - `ToolsCallHandler` 更偏协议入口层
 * - `SessionPort` 更偏调用执行层
 * - `GenericHttpGateway` 更偏底层 HTTP Client
 * <p>
 * 当前实现是“先把链路跑通”的版本：
 * - 只处理 GET / POST
 * - 参数结构假设较强
 * - 返回值先直接使用下游 HTTP 响应字符串
 * - 使用 Retrofit `call.execute()` 同步阻塞调用，而不是异步调用
 */
@Component
public class SessionPort implements ISessionPort {
    @Resource
    private GenericHttpGateway gateway;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Object toolCall(McpGatewayProtocolConfigVO.HTTPConfig httpConfig, Object params) throws IOException {
        // 1. 构建请求头。
        // 数据库里存的是 JSON 字符串，这里先反序列化成 Map，后面交给 GenericHttpGateway 使用。
        String httpHeadersJson = httpConfig.getHttpHeaders();

        Map<String, Object> headers = objectMapper.readValue(httpHeadersJson, Map.class);

        // 2. 判断请求方法。
        String httpMethod = httpConfig.getHttpMethod().toLowerCase();

        // 3. 参数校验。
        // 当前约定 tools/call 的 arguments 最终会转换为 Map 结构再传进来。
        if (!(params instanceof Map<?, ?> arguments)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        switch (httpMethod) {
            // POST：把 arguments 中的业务对象取出来，序列化成 JSON body，再同步执行 HTTP 请求。
            case "post": {
                RequestBody requestBody = RequestBody.create(JSON.toJSONString(arguments.values().toArray()[0]),
                        MediaType.parse("application/json"));

                Call<ResponseBody> call = gateway.post(httpConfig.getHttpUrl(), headers, requestBody);
                // 这里用的是 execute()，表示同步阻塞调用；当前线程会等待对方 HTTP 返回后再继续。
                ResponseBody responseBody = call.execute().body();

                assert responseBody != null;

                return responseBody.string();
            }
            // GET：先处理路径参数，再把剩余字段作为 query 参数带出去。
            case "get": {
                Map<String, Object> objMapRequest = new java.util.HashMap<>((Map<String, Object>) arguments.values().toArray()[0]);

                String url = httpConfig.getHttpUrl();
                // 替换路径参数，例如 /user/{id} -> /user/1001
                Matcher matcher = Pattern.compile("\\{([^}]+)\\}").matcher(url);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (objMapRequest.containsKey(name)) {
                        url = url.replace("{" + name + "}", String.valueOf(objMapRequest.get(name)));
                        objMapRequest.remove(name);
                    }
                }

                Call<ResponseBody> call = gateway.get(url, headers, objMapRequest);

                // 同样是同步阻塞执行，先拿到 HTTP 响应，再把结果返回给上层包装成 MCP 响应。
                ResponseBody responseBody = call.execute().body();

                assert responseBody != null;

                return responseBody.string();
            }
        }

        throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), ResponseCode.METHOD_NOT_FOUND.getInfo());
    }

}
