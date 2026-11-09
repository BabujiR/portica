import { Alert, Col, Form, Input, InputNumber, Radio, Row, Select, Typography } from 'antd';
import type { FormInstance } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { listProxies } from '../../api/endpoints';
import type { McpServer, McpServerBody, RemoteAuth } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { RESOURCE_NAME_RULE } from '../../components/format';
import { REMOTE_AUTH_LABELS } from './mcpModel';

export interface McpForm extends Omit<McpServerBody, 'remote'> {
  remote?: {
    url?: string;
    auth?: RemoteAuth;
    bearerToken?: string;
    clientId?: string;
    clientSecret?: string;
    scopes?: string[];
    authorizationServer?: string;
  };
}

export function initialMcpForm(server?: McpServer): McpForm {
  if (!server) {
    return { type: 'OPENAPI', auth: 'API_KEY', environments: [], tools: [], timeoutSeconds: 60, remote: { auth: 'NONE', scopes: [] } };
  }
  return {
    displayName: server.displayName, description: server.description ?? undefined, environments: server.environments,
    auth: server.auth, type: server.type, apiProxy: server.apiProxy ?? undefined, tools: server.tools,
    timeoutSeconds: server.timeoutSeconds,
    remote: server.remote ? {
      url: server.remote.url, auth: server.remote.auth, clientId: server.remote.clientId ?? undefined,
      scopes: server.remote.scopes, authorizationServer: server.remote.authorizationServer ?? undefined,
    } : { auth: 'NONE', scopes: [] },
  };
}

/** The request body: secrets left empty are omitted, so stored ones are kept. */
export function toMcpBody(v: McpForm, creating: boolean): McpServerBody {
  const { remote, ...rest } = v;
  const body: McpServerBody = { ...rest };
  if (!creating) delete body.name;
  if (!creating) delete body.type;
  if (v.type === 'REMOTE' && remote?.url) {
    body.apiProxy = undefined;
    body.remote = {
      url: remote.url,
      auth: remote.auth ?? 'NONE',
      bearerToken: remote.bearerToken || undefined,
      clientId: remote.clientId || undefined,
      clientSecret: remote.clientSecret || undefined,
      scopes: remote.scopes ?? [],
      authorizationServer: remote.authorizationServer || undefined,
    };
  }
  return body;
}

/** Fields of an MCP server, for creating (all) and editing (name and type fixed). */
export function McpServerFields({ form, editing, server }: { form: FormInstance<McpForm>; editing: boolean; server?: McpServer }) {
  const { org, environments } = useWorkspace();
  const proxies = useQuery({ queryKey: ['proxies', org], queryFn: () => listProxies(org) });
  const type = Form.useWatch('type', form) ?? server?.type;
  const remoteAuth = Form.useWatch(['remote', 'auth'], form) as RemoteAuth | undefined;
  const oauth = remoteAuth === 'OAUTH_CLIENT_CREDENTIALS' || remoteAuth === 'OAUTH_AUTHORIZATION_CODE';

  return (
    <>
      <Row gutter={12}>
        <Col span={12}>
          <Form.Item name="name" label="Name" rules={editing ? [] : [{ required: true }, RESOURCE_NAME_RULE]} extra="Part of the URL: /mcp/<name>">
            <Input disabled={editing} placeholder={editing ? server?.name : 'petstore'} />
          </Form.Item>
        </Col>
        <Col span={12}><Form.Item name="displayName" label="Display name"><Input /></Form.Item></Col>
      </Row>
      <Form.Item name="description" label="Description" extra="Shown to MCP clients as the server's instructions">
        <Input.TextArea autoSize={{ minRows: 1 }} />
      </Form.Item>
      <Form.Item name="type" label="Tools come from">
        <Radio.Group disabled={editing} optionType="button" options={[
          { value: 'OPENAPI', label: 'An API proxy’s OpenAPI spec' },
          { value: 'REMOTE', label: 'A remote MCP server' },
        ]} />
      </Form.Item>
      {type === 'OPENAPI' ? (
        <>
          <Form.Item name="apiProxy" label="API proxy" rules={[{ required: true }]}
            extra="Each operation of the deployed revision's spec becomes a tool. Calls go through the gateway, so the proxy's policies apply.">
            <Select showSearch options={(proxies.data?.items ?? []).map((p) => ({ value: p.name, label: p.name }))} />
          </Form.Item>
        </>
      ) : (
        <>
          <Form.Item name={['remote', 'url']} label="MCP server URL" rules={[{ required: true }, { pattern: /^https?:\/\/[^\s/:?#]+(:\d{1,5})?([/?#]\S*)?$/, message: 'An http(s) URL' }]}>
            <Input placeholder="https://mcp.example.com/mcp" />
          </Form.Item>
          <Form.Item name={['remote', 'auth']} label="Portica authenticates to it with">
            <Select options={Object.entries(REMOTE_AUTH_LABELS).map(([value, label]) => ({ value, label }))} />
          </Form.Item>
          {remoteAuth === 'BEARER' && (
            <Form.Item name={['remote', 'bearerToken']} label="Bearer token" rules={editing && server?.remote?.secretSet ? [] : [{ required: true }]}
              extra={editing ? 'Leave empty to keep the stored token.' : 'Stored encrypted.'}>
              <Input.Password autoComplete="new-password" />
            </Form.Item>
          )}
          {oauth && (
            <>
              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item name={['remote', 'clientId']} label="OAuth client id" rules={[{ required: true }]}><Input /></Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name={['remote', 'clientSecret']} label="Client secret"
                    rules={remoteAuth === 'OAUTH_CLIENT_CREDENTIALS' && !(editing && server?.remote?.secretSet) ? [{ required: true }] : []}
                    extra={remoteAuth === 'OAUTH_AUTHORIZATION_CODE' ? 'Empty for a public client (PKCE only)' : editing ? 'Leave empty to keep' : undefined}>
                    <Input.Password autoComplete="new-password" />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name={['remote', 'scopes']} label="Scopes">
                <Select mode="tags" tokenSeparators={[' ', ',']} placeholder="e.g. repo read:org" />
              </Form.Item>
              <Form.Item name={['remote', 'authorizationServer']} label="Authorization server (issuer)"
                extra="Leave empty to discover it from the MCP server (RFC 9728 protected resource metadata).">
                <Input placeholder="https://auth.example.com" />
              </Form.Item>
              {remoteAuth === 'OAUTH_AUTHORIZATION_CODE' && (
                <Alert type="info" showIcon style={{ marginBottom: 16 }}
                  message="After saving, open the server and choose Sign in: an admin authorizes Portica once; Portica keeps and refreshes the tokens." />
              )}
            </>
          )}
        </>
      )}
      <Form.Item name="tools" label="Only expose these tools" extra="Empty: every tool">
        <Select mode="tags" placeholder="tool names" />
      </Form.Item>
      <Row gutter={12}>
        <Col span={12}>
          <Form.Item name="environments" label="Environments" extra="Empty: every environment">
            <Select mode="multiple" allowClear options={environments.map((e) => ({ value: e.name, label: e.name }))} />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="auth" label="MCP clients authenticate with">
            <Radio.Group optionType="button" options={[{ value: 'API_KEY', label: 'App API key' }, { value: 'OAUTH', label: 'OAuth token' }]} />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item name="timeoutSeconds" label="Tool call timeout (s)"><InputNumber min={5} max={300} /></Form.Item>
      <Typography.Paragraph type="secondary">
        Apps need an API product that includes the gateway proxy <Typography.Text code>mcp-&lt;name&gt;</Typography.Text>
        {type === 'OPENAPI' && <> and the API proxy itself (each tool call is an API call)</>}.
      </Typography.Paragraph>
    </>
  );
}
