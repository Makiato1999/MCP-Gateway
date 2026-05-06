# MCP-Gateway

### About

MCP-Gateway is a configurable MCP access layer that turns backend HTTP capabilities into tool-oriented interfaces for LLM clients. It provides session-based SSE communication, JSON-RPC message handling, tool discovery and invocation, and gateway-level routing so multiple logical gateways and tools can be exposed through one unified entrypoint.

In traditional systems, service invocation is deterministically orchestrated by application logic.
In an MCP-based architecture, backend services are exposed as semantic capabilities, and the decision of which capability to invoke is delegated to the LLM through tool descriptions and schemas.

### Session Management

To support this interaction model, the gateway maintains explicit session boundaries for each client connection.  
Session handling is organized as an extensible, node-based pipeline rather than a monolithic service.  
This design allows validation, session state, and routing concerns to evolve independently as the gateway grows.

### How To Set Up Local MySQL

- Use Docker to start MySQL from `ai-mcp-gateway/docs/dev-ops/docker-compose-environment.yml`.
- Initialize the schema with `ai-mcp-gateway/docs/dev-ops/mysql/sql/ai_mcp_gateway.sql`.
- Check the datasource connection settings in the project `application-*.yml`.
- Pay attention to Docker port mapping. If the compose file maps `13306:3306`, then the local connection port is `13306`.
- Use IntelliJ IDEA `Database` tool window / data source view to connect and inspect tables visually.
