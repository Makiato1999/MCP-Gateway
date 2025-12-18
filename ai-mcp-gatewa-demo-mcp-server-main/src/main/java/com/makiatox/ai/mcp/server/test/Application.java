package com.makiatox.ai.mcp.server.test;

import com.makiatox.ai.mcp.server.test.service.TestService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 入口：http://localhost:8701/sse
 * <br/>官网：https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public ToolCallbackProvider testTools(TestService testService) {
        return MethodToolCallbackProvider.builder().toolObjects(testService).build();
    }

}
