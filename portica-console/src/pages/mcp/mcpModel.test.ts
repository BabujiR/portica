import { describe, expect, it } from 'vitest';
import { clientConfig, mcpEndpoint, toolArguments, toolHints } from './mcpModel';
import type { McpServer, McpToolView } from '../../api/types';

describe('MCP model', () => {
  const server = { name: 'petstore', auth: 'API_KEY' } as McpServer;

  it('builds the endpoint and a client configuration', () => {
    expect(mcpEndpoint('https://gw', 'acme', 'dev', server)).toBe('https://gw/acme/dev/mcp/petstore');
    const config = JSON.parse(clientConfig('https://gw', 'acme', 'dev', server));
    expect(config.mcpServers['portica-petstore']).toEqual({
      type: 'http', url: 'https://gw/acme/dev/mcp/petstore', headers: { 'x-api-key': '${PORTICA_API_KEY}' },
    });
    const oauth = JSON.parse(clientConfig('https://gw', 'acme', 'dev', { ...server, auth: 'OAUTH' }));
    expect(oauth.mcpServers['portica-petstore'].headers).toBeUndefined();
  });

  it('summarizes tools', () => {
    const tool = {
      name: 'get_pet',
      inputSchema: { type: 'object', properties: { petId: {}, verbose: {} }, required: ['petId'] },
      annotations: { readOnlyHint: true, idempotentHint: true },
    } as McpToolView;
    expect(toolArguments(tool)).toEqual(['petId*', 'verbose']);
    expect(toolHints(tool)).toEqual(['read-only']);
    expect(toolHints({ ...tool, annotations: { destructiveHint: true, idempotentHint: true } })).toEqual(['destructive', 'idempotent']);
  });
});
