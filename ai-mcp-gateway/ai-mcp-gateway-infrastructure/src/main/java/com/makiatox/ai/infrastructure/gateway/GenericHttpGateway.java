package com.makiatox.ai.infrastructure.gateway;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

import java.util.Map;

/**
 * 通用 HTTP 调用网关。
 * <p>
 * 这里不是在定义某个具体业务接口，而是在定义“如何统一发一个 HTTP 请求”的入口。
 * 在 tools/call 场景下，上层只需要准备好：
 * - url
 * - headers
 * - body 或 queryParams
 * 然后调用这里的 get / post 方法即可。
 * <p>
 * 这种写法更接近 Retrofit 的声明式 HTTP Client，而不是手写 client 请求代码。
 * 也就是说，这里主要是“描述请求应该怎么发”，真正的执行由底层 HTTP 框架完成。
 * <p>
 * 可以把它理解成：
 * <pre>
 * ToolsCallHandler
 * -> 查协议配置
 * -> 组装请求参数
 * -> 调用 GenericHttpGateway
 * -> 拿到远程 HTTP 响应
 * </pre>
 */
public interface GenericHttpGateway {

    /**
     * 发起通用 POST 请求。
     * <p>
     * 参数说明：
     * - url：最终请求地址
     * - headers：请求头，会展开成 Header 列表
     * - body：请求体，通常是 JSON 序列化后的内容
     * <p>
     * 注解说明：
     * - @POST：表示这是一个 POST 请求
     * - @Url：这个参数是请求地址
     * - @HeaderMap：把 Map 中的键值对展开成请求头
     * - @Body：把对象作为请求体发送
     */
    @POST
    Call<ResponseBody> post(
            @Url String url,
            @HeaderMap Map<String, Object> headers,
            @Body RequestBody body
    );

    /**
     * 发起通用 GET 请求。
     * <p>
     * 参数说明：
     * - url：最终请求地址
     * - headers：请求头，会展开成 Header 列表
     * - queryParams：查询参数，会被拼到 URL 后面
     * <p>
     * 注解说明：
     * - @GET：表示这是一个 GET 请求
     * - @Url：这个参数是请求地址
     * - @HeaderMap：把 Map 中的键值对展开成请求头
     * - @QueryMap：把 Map 中的键值对展开成 URL 查询参数
     */
    @GET
    Call<ResponseBody> get(
            @Url String url,
            @HeaderMap Map<String, Object> headers,
            @QueryMap Map<String, Object> queryParams
    );
}
