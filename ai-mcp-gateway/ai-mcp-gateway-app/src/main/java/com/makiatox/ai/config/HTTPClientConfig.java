package com.makiatox.ai.config;

import com.makiatox.ai.infrastructure.gateway.GenericHttpGateway;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;

/**
 * HTTP 客户端配置。
 * <p>
 * 这部分的目标不是处理业务逻辑，而是把“怎么发 HTTP 请求”这件事提前配置好，
 * 交给 Spring 容器统一管理。后面 tools/call 如果要调用外部 HTTP 接口，
 * 就不需要每次自己 new Client、配超时、配连接池，而是直接注入已经准备好的对象。
 * <p>
 * 这里一共做了两件事：
 * 1. 创建底层真正发请求的 OkHttpClient。
 * 2. 基于 OkHttpClient 创建 Retrofit 代理对象 GenericHttpGateway。
 * <p>
 * 可以把它理解成：
 * <pre>
 * ToolsCallHandler
 * -> 注入 GenericHttpGateway
 * -> 调用 genericHttpGateway.get(...) / post(...)
 * -> Retrofit 根据接口定义发 HTTP 请求
 * -> OkHttpClient 真正执行网络通信
 * </pre>
 */
@Configuration
public class HTTPClientConfig {

    /**
     * 注册底层 HTTP 客户端。
     * <p>
     * OkHttpClient 是真正负责网络通信的对象，可以理解成 HTTP 请求执行器。
     * 这里提前把连接池、超时、重试策略配置好，后面所有通过 Retrofit 发出的请求，
     * 都会复用这一个客户端实例。
     * <p>
     * 这些参数的意义大致是：
     * - connectionPool：连接池，减少频繁建连的成本
     * - retryOnConnectionFailure：连接失败时是否自动重试
     * - connectTimeout：建立连接超时时间
     * - readTimeout：读取响应超时时间
     * - writeTimeout：发送请求体超时时间
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .connectTimeout(100, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 注册通用 HTTP 网关。
     * <p>
     * 这里使用 Retrofit 根据 GenericHttpGateway 接口动态生成一个实现类。
     * 后面业务代码虽然拿到的是 GenericHttpGateway 接口，但调用它的方法时，
     * 实际发出去的是 HTTP 请求。
     * <p>
     * 关键点：
     * - Retrofit.Builder()：创建 Retrofit 配置器
     * - baseUrl("http://127.0.0.1/")：Retrofit 要求必须提供一个 baseUrl
     *   这里更像占位值，因为后续方法里通常会用 @Url 传完整地址
     * - addConverterFactory(GsonConverterFactory.create())：配置 JSON 转换器
     * - client(okHttpClient)：指定底层真正执行请求的 OkHttpClient
     * - retrofit.create(GenericHttpGateway.class)：根据接口定义生成代理对象
     * <p>
     * 所以这段代码的本质是：
     * “把一个声明式 HTTP 接口，变成一个 Spring 可注入、可直接调用的客户端对象”。
     */
    @Bean
    public GenericHttpGateway genericHttpGateway(OkHttpClient okHttpClient) {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://127.0.0.1/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient)
                .build();
        return retrofit.create(GenericHttpGateway.class);
    }
}
