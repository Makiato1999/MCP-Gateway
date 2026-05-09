# MCP Gateway Workspace

## Overview

This repository currently centers on the `ai-mcp-gateway` project: a configurable MCP gateway that exposes backend HTTP capabilities as MCP tools for LLM clients.

At a high level, the project sits between an MCP client and downstream services:

```text
MCP Client / LLM
    -> MCP Gateway (this project)
        -> Database-driven gateway/tool/protocol config
        -> Downstream HTTP services
```

The gateway speaks MCP/JSON-RPC on the northbound side and uses configuration-driven HTTP invocation on the southbound side.
It is not just a local hardcoded MCP server. Its core value is protocol adaptation:

- session-based SSE communication
- JSON-RPC request dispatch
- dynamic `tools/list` generation from database mappings
- `tools/call` translation into downstream HTTP requests
- gateway-level routing by `gatewayId`

## Repository Layout

The repository currently contains:

- `ai-mcp-gateway/`
  The main multi-module Spring Boot project.
- `NOTE.md`
  Broader learning notes and implementation walkthroughs.
- `data/`
  Local data-related files used during development.

## `ai-mcp-gateway` Structure

`ai-mcp-gateway` is organized as a multi-module Maven project:

- `ai-mcp-gateway-api`
  Public API contracts such as `IMcpGatewayService`.
- `ai-mcp-gateway-app`
  Spring Boot bootstrap module and application configuration.
- `ai-mcp-gateway-trigger`
  HTTP controller entrypoints.
- `ai-mcp-gateway-case`
  Session orchestration and node-based pipeline.
- `ai-mcp-gateway-domain`
  MCP protocol objects, session services, and message handlers.
- `ai-mcp-gateway-infrastructure`
  DAO, repository adapters, and downstream HTTP calling adapters.
- `ai-mcp-gateway-types`
  shared enums, constants, and exceptions.

## Current Core Capabilities

As of the current implementation, the main project has already formed a meaningful MCP gateway skeleton:

- `initialize`
  Parses MCP initialize requests and returns gateway-aware server metadata.
- `tools/list`
  Builds tool schemas dynamically from database mapping records.
- `tools/call`
  Parses tool call requests, looks up downstream protocol config, invokes HTTP services, and wraps the result back into MCP response format.
- SSE session lifecycle
  Supports session creation, sink-based message pushing, heartbeat, and cleanup.

## Main Runtime Flow

The northbound runtime flow is:

```text
GET /{gatewayId}/mcp/sse
    -> create session
    -> return SSE stream

POST /{gatewayId}/mcp/sse?sessionId=...
    -> accept JSON-RPC message
    -> deserialize outer JSON-RPC envelope
    -> dispatch by method
    -> handle initialize / tools/list / tools/call / resources/list
    -> push JSON-RPC response back through SSE sink
```

The most important message handlers currently are:

- `InitializeHandler`
- `ToolsListHandler`
- `ToolsCallHandler`

## Database Model

The gateway is backed by four main tables:

- `mcp_gateway`
  Gateway identity and metadata.
- `mcp_gateway_auth`
  Gateway-level auth information.
- `mcp_protocol_registry`
  Tool registration and downstream HTTP protocol configuration.
- `mcp_protocol_mapping`
  Field-level MCP mapping records used to build tool schemas.

Among them, `mcp_protocol_mapping` is the most structurally interesting table. It stores a flattened field tree using:

- `parent_path`
- `mcp_path`
- `field_name`
- `mcp_type`
- `is_required`

This allows the project to reconstruct nested JSON Schema for `tools/list`.

## Local Development

### MySQL

- Start MySQL via `ai-mcp-gateway/docs/dev-ops/docker-compose-environment.yml`
- Initialize schema with:
  - `ai-mcp-gateway/docs/dev-ops/mysql/sql/ai_mcp_gateway.sql`
  - or branch-aligned backup SQL under `ai-mcp-gateway/docs/dev-ops/bak/`

### Application

- The Spring Boot entrypoint is `ai-mcp-gateway/ai-mcp-gateway-app/src/main/java/com/makiatox/ai/Application.java`
- Controller entrypoint is `McpGatewayController`
- Session message dispatch happens in `SessionMessageService`

### Testing Modes

There are two useful testing levels:

- project-internal testing
  Test handlers, repositories, and protocol assembly inside the current project.
- end-to-end MCP testing
  Use an external demo client and downstream demo HTTP service to validate the full chain:

```text
Demo ApiTest
    -> ai-mcp-gateway
        -> downstream demo HTTP server
    -> ai-mcp-gateway
    -> Demo ApiTest
```

## Documentation Pointers

- Root-level `NOTE.md`
  Broader implementation notes.
- `ai-mcp-gateway/README.md`
  Detailed stage-by-stage notes plus project-specific summaries.
- `ai-mcp-gateway/docs/dev-ops/`
  SQL, Docker Compose, and app scripts.

## Project Positioning

The best way to describe this project is:

> a configuration-driven MCP gateway that exposes MCP northbound and adapts to HTTP southbound.

From the client perspective, it looks like an MCP server.
From the implementation perspective, it behaves much like a traditional API gateway / protocol adapter layer with MCP as its external contract.
