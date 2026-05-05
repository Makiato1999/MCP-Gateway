package com.makiatox.ai.test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.Duration;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ApiTest {

    @Resource
    private ChatClient.Builder chatClientBuilder;

    @Test
    public void test_chat_only() {
        ChatClient chatClient = chatClientBuilder.defaultOptions(
                        OpenAiChatOptions.builder()
                                .model("gpt-4.1-mini-2025-04-14")
                                .build())
                .build();

        log.info("chat result: {}", chatClient.prompt("你好，GPT！").call().content());
    }

    /**
     * 在这条链路里，百度提供的是一个符合 MCP 协议的远端 MCP Server，本地测试代码通过 McpSyncClient 作
     *   为 MCP Client 与其完成 initialize 和 tools/list 交互，从而获取该服务暴露的工具元数据。随后，
     *   SyncMcpToolCallbackProvider 将这些 MCP 工具适配为 Spring AI 可消费的 tool callbacks，并注册到
     *   ChatClient 的调用配置中。这样，模型侧不再直接面向 MCP 协议，而是通过 Spring AI 的工具调用抽象访
     *   问外部能力；当 chatClient.prompt(...).call() 执行时，Spring AI 在内部消费这些 callback，将模型可
     *   见的工具描述、工具调用、参数传递与结果回填统一纳入其 tool-calling 流程。
     */
    @Test
    public void test_mcp() {
        ChatClient chatClient = chatClientBuilder.defaultOptions(
                        OpenAiChatOptions.builder()
                                .model("gpt-4.1-mini-2025-04-14")
                                .toolCallbacks(new SyncMcpToolCallbackProvider(sseMcpClient01()).getToolCallbacks())
                                .build())
                .build();

        // 有哪些工具可以使用
        log.info("测试结果:{}", chatClient.prompt("有哪些工具可以使用").call().content());
    }

    public McpSyncClient sseMcpClient02() {
        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport
                .builder("http://127.0.0.1:8777")
                .sseEndpoint("/api-gateway/test10001/mcp/sse")
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofMinutes(36000)).build();
        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP02 Initialized {}", init_sse);

        return mcpSyncClient;
    }

    /**
     * 百度搜索MCP服务(url)；https://sai.baidu.com/zh/detail/e014c6ffd555697deabf00d058baf388
     * 百度搜索MCP服务(key - 可自行申请)；https://console.bce.baidu.com/iam/?_=1753597622044#/iam/apikey/list
     */
    public McpSyncClient sseMcpClient01() {
        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport
                .builder("http://appbuilder.baidu.com")
                .sseEndpoint("/v2/ai_search/mcp/sse?api_key=Bearer+bce-v3/ALTAK-K0uVxzJCQfyI0oKt4hmqV/986e9801d2120d4c8a0a9da19f4cfda14bf2dc07")
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofMinutes(36000)).build();
        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP01 Initialized {}", init_sse);

        return mcpSyncClient;
    }

    @Test
    public void test_baidu_mcp_only() {
        McpSyncClient client = sseMcpClient01();
        var tools = client.listTools();
        log.info("tools: {}", tools);
    }

}
