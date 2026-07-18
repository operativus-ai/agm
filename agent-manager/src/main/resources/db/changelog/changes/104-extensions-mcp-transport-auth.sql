--liquibase formatted sql

--changeset agentmanager:104-extensions-mcp-transport-auth
--comment: Add MCP transport selection + encrypted outbound auth to extensions. transport lets an MCP extension declare SSE (default, back-compatible) vs STREAMABLE_HTTP; auth_secret holds an AES-256-GCM ciphertext (OutboundApiKeyConverter) applied as an Authorization: Bearer header by McpConnectionPool. Both are NULL/SSE for existing rows, so behavior is unchanged unless explicitly opted in. Removes the need for the supergateway proxy to reach modern remote MCPs (e.g. the hosted GitHub MCP).

ALTER TABLE extensions
    ADD COLUMN IF NOT EXISTS transport   VARCHAR(32) NOT NULL DEFAULT 'SSE';

-- AES-GCM ciphertext: v{N}:Base64(IV||ciphertext||tag). Sized for a long bearer token / PAT
-- plus the encryption envelope. Nullable: SSE servers and unauthenticated MCPs carry no secret.
ALTER TABLE extensions
    ADD COLUMN IF NOT EXISTS auth_secret VARCHAR(1024);
