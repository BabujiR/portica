import type { McpServer, McpToolView } from '../../api/types';

export function mcpEndpoint(gatewayUrl: string, org: string, env: string, server: McpServer | string): string {
  const name = typeof server === 'string' ? server : server.name;
  return `${gatewayUrl}/${org}/${env}/mcp/${name}`;
}

/**
 * Client configuration in the shape most MCP clients accept for a remote (Streamable HTTP) server:
 * a named entry with the URL and the headers to send.
 */
export function clientConfig(gatewayUrl: string, org: string, env: string, server: McpServer): string {
  const entry: Record<string, unknown> = { type: 'http', url: mcpEndpoint(gatewayUrl, org, env, server) };
  if (server.auth === 'API_KEY') entry.headers = { 'x-api-key': '${PORTICA_API_KEY}' };
  return JSON.stringify({ mcpServers: { [`portica-${server.name}`]: entry } }, null, 2);
}

/** Short labels for a tool's annotations (read-only, destructive, idempotent). */
export function toolHints(tool: McpToolView): string[] {
  const a = tool.annotations ?? {};
  const hints: string[] = [];
  if (a.readOnlyHint === true) hints.push('read-only');
  if (a.destructiveHint === true) hints.push('destructive');
  if (a.idempotentHint === true && a.readOnlyHint !== true) hints.push('idempotent');
  return hints;
}

/** Argument names of a tool, required ones marked with an asterisk. */
export function toolArguments(tool: McpToolView): string[] {
  const schema = tool.inputSchema ?? {};
  const properties = (schema.properties ?? {}) as Record<string, unknown>;
  const required = new Set(Array.isArray(schema.required) ? (schema.required as string[]) : []);
  return Object.keys(properties).map((k) => (required.has(k) ? `${k}*` : k));
}

export const REMOTE_AUTH_LABELS: Record<string, string> = {
  NONE: 'No authentication',
  BEARER: 'Static bearer token',
  OAUTH_CLIENT_CREDENTIALS: 'OAuth 2.1 client credentials',
  OAUTH_AUTHORIZATION_CODE: 'OAuth 2.1 sign-in (authorization code + PKCE)',
};
