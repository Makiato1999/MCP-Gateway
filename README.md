# MCP-Gateway

In traditional systems, service invocation is deterministically orchestrated by application logic.
In an MCP-based architecture, backend services are exposed as semantic capabilities, and the decision of which capability to invoke is delegated to the LLM through tool descriptions and schemas.

### Session Management

To support this interaction model, the gateway maintains explicit session boundaries for each client connection.  
Session handling is organized as an extensible, node-based pipeline rather than a monolithic service.  
This design allows validation, session state, and routing concerns to evolve independently as the gateway grows.

