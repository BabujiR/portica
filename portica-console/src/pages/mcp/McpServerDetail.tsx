import { useEffect, useState } from 'react';
import {
  Alert, Button, Card, Col, Descriptions, Drawer, Empty, Flex, Form, Popconfirm, Row, Select, Space, Statistic, Table,
  Tabs, Tag, Typography,
} from 'antd';
import { DeleteOutlined, EditOutlined, LinkOutlined, LoginOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { errorMessage } from '../../api/client';
import {
  authorizeMcpServer, connectMcpServer, deleteMcpServer, disconnectMcpServer, getMcpServer, getMcpSummary, getMcpTop,
  listMcpCalls, listMcpTools, updateMcpServer,
} from '../../api/endpoints';
import type { McpCall, McpServer, McpToolView } from '../../api/types';
import { config } from '../../config';
import { useWorkspace } from '../../app/Workspace';
import { dateTime, fromNow } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';
import { RANGES, formatCount, formatMs, formatPercent, rangeWindow, type RangeKey } from '../analytics/analyticsModel';
import { McpServerFields, initialMcpForm, toMcpBody, type McpForm } from './McpServerForm';
import { REMOTE_AUTH_LABELS, clientConfig, mcpEndpoint, toolArguments, toolHints } from './mcpModel';

export function McpServerDetail() {
  const { name = '' } = useParams();
  const { org, env, can } = useWorkspace();
  const notify = useNotify();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [editing, setEditing] = useState(false);
  const [form] = Form.useForm<McpForm>();
  const server = useQuery({ queryKey: ['mcp-server', org, name], queryFn: () => getMcpServer(org, name), enabled: can('MCP_READ') });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['mcp-server', org, name] });
    void queryClient.invalidateQueries({ queryKey: ['mcp-servers', org] });
  };
  const save = useMutation({
    mutationFn: (v: McpForm) => updateMcpServer(org, name, toMcpBody(v, false)),
    onSuccess: () => { refresh(); setEditing(false); },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: () => deleteMcpServer(org, name),
    onSuccess: () => { refresh(); navigate(`/o/${org}/mcp`); },
    onError: (e) => notify.error(e),
  });

  // back from an OAuth sign-in: ?oauth=connected or ?oauth=error&message=...
  const oauthResult = params.get('oauth');
  useEffect(() => {
    if (!oauthResult) return;
    if (oauthResult === 'connected') notify.success('Signed in: Portica now holds a token for this MCP server.');
    else notify.error(new Error(params.get('message') ?? 'The sign-in failed'), 'Sign-in failed');
    refresh();
    setParams({}, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [oauthResult]);

  if (!can('MCP_READ')) return <NoAccess what="MCP servers" />;
  const s = server.data;
  const writable = can('MCP_WRITE');

  return (
    <Page
      title={s?.displayName || name}
      crumbs={[{ title: 'MCP Servers', to: `/o/${org}/mcp` }, { title: name }]}
      extra={s && writable && (
        <Space>
          <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>Edit</Button>
          <Popconfirm title={`Delete ${name}?`} description="Clients using it get 404 within seconds." okButtonProps={{ danger: true }}
            onConfirm={() => remove.mutate()}>
            <Button danger icon={<DeleteOutlined />}>Delete</Button>
          </Popconfirm>
        </Space>
      )}
    >
      <Loadable loading={server.isLoading} error={server.error}>
        {s && (
          <Tabs items={[
            { key: 'overview', label: 'Overview', children: <Overview server={s} org={org} env={env ?? 'dev'} /> },
            { key: 'tools', label: 'Tools', children: <Tools server={s} /> },
            ...(s.type === 'REMOTE' ? [{ key: 'auth', label: 'Upstream authentication', children: <UpstreamAuth server={s} onChange={refresh} /> }] : []),
            ...(can('ANALYTICS_READ') ? [{ key: 'usage', label: 'Tool calls', children: <Usage server={s} /> }] : []),
          ]} />
        )}
      </Loadable>
      <Drawer open={editing} width={680} title={`Edit ${name}`} onClose={() => setEditing(false)} destroyOnClose
        extra={<Button type="primary" loading={save.isPending} onClick={() => form.submit()}>Save</Button>}>
        {s && (
          <Form<McpForm> form={form} layout="vertical" initialValues={initialMcpForm(s)} onFinish={(v) => save.mutate(v)}>
            <McpServerFields form={form} editing server={s} />
          </Form>
        )}
      </Drawer>
    </Page>
  );
}

function Overview({ server, org, env }: { server: McpServer; org: string; env: string }) {
  const endpoint = mcpEndpoint(config.gatewayUrl, org, env, server);
  const clientJson = clientConfig(config.gatewayUrl, org, env, server);
  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} lg={12}>
        <Card title="Connect an MCP client">
          <Descriptions column={1} size="small">
            <Descriptions.Item label="Endpoint"><Typography.Text copyable code>{endpoint}</Typography.Text></Descriptions.Item>
            <Descriptions.Item label="Transport">Streamable HTTP (JSON responses)</Descriptions.Item>
            <Descriptions.Item label="Clients authenticate">
              {server.auth === 'OAUTH'
                ? <>OAuth bearer token from <Typography.Text code>{config.gatewayUrl}/{org}/{env}/oauth2/token</Typography.Text> (discoverable via the 401's <Typography.Text code>resource_metadata</Typography.Text>)</>
                : <>App API key in <Typography.Text code>x-api-key</Typography.Text></>}
            </Descriptions.Item>
            <Descriptions.Item label="Access">API products that include <Typography.Text code>{server.gatewayProxy}</Typography.Text>
              {server.type === 'OPENAPI' && <> and <Typography.Text code>{server.apiProxy}</Typography.Text></>}</Descriptions.Item>
          </Descriptions>
          <Typography.Text strong>Client configuration</Typography.Text>
          <Typography.Paragraph copyable={{ text: clientJson }}>
            <pre style={{ margin: 0 }}>{clientJson}</pre>
          </Typography.Paragraph>
        </Card>
      </Col>
      <Col xs={24} lg={12}>
        <Card title="Definition">
          <Descriptions column={1} size="small">
            <Descriptions.Item label="Tools from">
              {server.type === 'OPENAPI' ? <>OpenAPI spec of API proxy <b>{server.apiProxy}</b> (the revision deployed in each environment)</>
                : <>Remote MCP server <Typography.Text code>{server.remote?.url}</Typography.Text></>}
            </Descriptions.Item>
            {server.remote && <Descriptions.Item label="Upstream auth">{REMOTE_AUTH_LABELS[server.remote.auth]}</Descriptions.Item>}
            <Descriptions.Item label="Allowed tools">{server.tools.length ? server.tools.join(', ') : 'all'}</Descriptions.Item>
            <Descriptions.Item label="Environments">{server.environments.length ? server.environments.join(', ') : 'all'}</Descriptions.Item>
            <Descriptions.Item label="Tool timeout">{server.timeoutSeconds} s</Descriptions.Item>
            <Descriptions.Item label="Description">{server.description || '—'}</Descriptions.Item>
            <Descriptions.Item label="Updated">{dateTime(server.updatedAt)} by {server.updatedBy ?? '—'}</Descriptions.Item>
          </Descriptions>
        </Card>
      </Col>
    </Row>
  );
}

function Tools({ server }: { server: McpServer }) {
  const { org, environments } = useWorkspace();
  const [environment, setEnvironment] = useState<string | undefined>();
  const tools = useQuery({
    queryKey: ['mcp-tools', org, server.name, environment ?? ''],
    queryFn: () => listMcpTools(org, server.name, environment),
    retry: false,
  });
  return (
    <>
      <Flex gap={12} align="center" style={{ marginBottom: 12 }}>
        {server.type === 'OPENAPI' && (
          <Select aria-label="Environment" allowClear placeholder="Latest revision" style={{ minWidth: 200 }} value={environment}
            onChange={setEnvironment} options={environments.map((e) => ({ value: e.name, label: `Deployed in ${e.name}` }))} />
        )}
        <Typography.Text type="secondary">
          {server.type === 'OPENAPI' ? 'Generated from the OpenAPI spec; each call becomes an HTTP request through the gateway.'
            : 'As the remote server lists them (Portica asks it with its own credential).'}
        </Typography.Text>
      </Flex>
      {tools.error ? <Alert type="warning" showIcon message="Tools couldn't be listed" description={errorMessage(tools.error)} /> : (
        <Table<McpToolView>
          rowKey="name"
          loading={tools.isLoading}
          dataSource={tools.data ?? []}
          pagination={{ pageSize: 25, hideOnSinglePage: true }}
          locale={{ emptyText: <Empty description="No tools" /> }}
          expandable={{
            expandedRowRender: (t) => (
              <Typography.Paragraph copyable={{ text: JSON.stringify(t.inputSchema, null, 2) }}>
                <pre style={{ margin: 0, maxHeight: 320, overflow: 'auto' }}>{JSON.stringify(t.inputSchema, null, 2)}</pre>
              </Typography.Paragraph>
            ),
          }}
          columns={[
            { title: 'Tool', key: 'name', render: (_, t) => <><Typography.Text code>{t.name}</Typography.Text>{toolHints(t).map((h) => <Tag key={h} style={{ marginLeft: 6 }}>{h}</Tag>)}</> },
            { title: 'Description', key: 'description', render: (_, t) => <Typography.Paragraph ellipsis={{ rows: 2 }} style={{ margin: 0 }}>{t.description || t.title}</Typography.Paragraph> },
            { title: 'Arguments', key: 'args', render: (_, t) => <Typography.Text type="secondary">{toolArguments(t).join(', ') || '—'}</Typography.Text> },
            { title: 'Request', key: 'http', render: (_, t) => t.http && <Typography.Text code>{t.http.method} {t.http.path}</Typography.Text> },
          ]}
        />
      )}
    </>
  );
}

function UpstreamAuth({ server, onChange }: { server: McpServer; onChange: () => void }) {
  const { org, can } = useWorkspace();
  const notify = useNotify();
  const writable = can('MCP_WRITE');
  const remote = server.remote!;
  const status = remote.oauth;
  const authorize = useMutation({
    mutationFn: () => authorizeMcpServer(org, server.name),
    onSuccess: (r) => { window.location.assign(r.authorizationUrl); },
    onError: (e) => notify.error(e, 'Couldn’t start the sign-in'),
  });
  const connect = useMutation({
    mutationFn: () => connectMcpServer(org, server.name),
    onSuccess: () => { notify.success('Token obtained'); onChange(); },
    onError: (e) => notify.error(e, 'Couldn’t get a token'),
  });
  const disconnect = useMutation({
    mutationFn: () => disconnectMcpServer(org, server.name),
    onSuccess: () => { notify.success('Tokens removed'); onChange(); },
    onError: (e) => notify.error(e),
  });

  if (remote.auth === 'NONE' || remote.auth === 'BEARER') {
    return (
      <Alert type="info" showIcon message={REMOTE_AUTH_LABELS[remote.auth]}
        description={remote.auth === 'BEARER' ? `A static token is ${remote.secretSet ? 'stored (encrypted)' : 'missing'}; Portica sends it on every call.`
          : 'Portica calls the server without credentials.'} />
    );
  }
  return (
    <Card>
      <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label="Method">{REMOTE_AUTH_LABELS[remote.auth]}</Descriptions.Item>
        <Descriptions.Item label="Status">
          {status?.connected ? <Tag color="green">connected</Tag> : <Tag color="orange">not connected</Tag>}
          {status?.expiresAt && <Typography.Text type="secondary">access token expires {fromNow(status.expiresAt)}</Typography.Text>}
          {status?.refreshable && <Tag style={{ marginLeft: 6 }}>refreshable</Tag>}
        </Descriptions.Item>
        <Descriptions.Item label="Client id"><Typography.Text code>{remote.clientId}</Typography.Text></Descriptions.Item>
        <Descriptions.Item label="Scopes">{remote.scopes.length ? remote.scopes.join(' ') : '—'}</Descriptions.Item>
        <Descriptions.Item label="Granted scope">{status?.scope ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="Authorization server">{status?.issuer ?? remote.authorizationServer ?? 'discovered on first use'}</Descriptions.Item>
        {status?.tokenEndpoint && <Descriptions.Item label="Token endpoint"><Typography.Text code>{status.tokenEndpoint}</Typography.Text></Descriptions.Item>}
      </Descriptions>
      {writable && (
        <Space wrap>
          {remote.auth === 'OAUTH_AUTHORIZATION_CODE' && (
            <Button type="primary" icon={<LoginOutlined />} loading={authorize.isPending} onClick={() => authorize.mutate()}>
              {status?.connected ? 'Sign in again' : 'Sign in'}
            </Button>
          )}
          <Button icon={remote.auth === 'OAUTH_CLIENT_CREDENTIALS' ? <LinkOutlined /> : <ReloadOutlined />} loading={connect.isPending}
            onClick={() => connect.mutate()} disabled={remote.auth === 'OAUTH_AUTHORIZATION_CODE' && !status?.connected}>
            {remote.auth === 'OAUTH_CLIENT_CREDENTIALS' ? 'Get a token now' : 'Refresh token now'}
          </Button>
          {status?.connected && (
            <Popconfirm title="Remove the stored tokens?" description="Tool calls fail until Portica gets a new token." onConfirm={() => disconnect.mutate()}>
              <Button danger>Disconnect</Button>
            </Popconfirm>
          )}
        </Space>
      )}
      <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>
        Tokens are stored encrypted in the management plane, refreshed there, and handed to the MCP service for each server;
        MCP clients never see them, and their own credentials are never forwarded upstream.
      </Typography.Paragraph>
    </Card>
  );
}

function Usage({ server }: { server: McpServer }) {
  const { org } = useWorkspace();
  const [range, setRange] = useState<RangeKey>('24h');
  const filter = () => ({ ...rangeWindow(range), mcpServer: server.name });
  const key = ['mcp-usage', org, server.name, range];
  const summary = useQuery({ queryKey: [...key, 'summary'], queryFn: () => getMcpSummary(org, filter()), refetchInterval: 30_000 });
  const top = useQuery({ queryKey: [...key, 'top'], queryFn: () => getMcpTop(org, 'tool', filter()), refetchInterval: 30_000 });
  const calls = useQuery({ queryKey: [...key, 'calls'], queryFn: () => listMcpCalls(org, filter()), refetchInterval: 30_000 });
  const s = summary.data;
  return (
    <>
      <Flex gap={12} style={{ marginBottom: 16 }}>
        <Select<RangeKey> value={range} onChange={setRange} style={{ width: 140 }} options={RANGES.map((r) => ({ value: r.key, label: `Last ${r.label}` }))} />
      </Flex>
      {summary.error && <Alert type="error" showIcon style={{ marginBottom: 16 }} message="Tool calls couldn't be loaded" description={errorMessage(summary.error)} />}
      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}><Card loading={summary.isLoading}><Statistic title="Tool calls" value={s ? formatCount(s.calls) : '–'} /></Card></Col>
        <Col xs={12} lg={6}><Card loading={summary.isLoading}><Statistic title="Failed" value={s ? formatPercent(s.errorRate) : '–'} />
          <Typography.Text type="secondary">{s ? `${formatCount(s.errors)} calls` : ' '}</Typography.Text></Card></Col>
        <Col xs={12} lg={6}><Card loading={summary.isLoading}><Statistic title="p95 time" value={s ? formatMs(s.p95Ms) : '–'} />
          <Typography.Text type="secondary">{s ? `avg ${formatMs(s.avgMs)}` : ' '}</Typography.Text></Card></Col>
        <Col xs={12} lg={6}><Card loading={summary.isLoading}><Statistic title="Apps" value={s ? s.apps : '–'} />
          <Typography.Text type="secondary">{s ? `${s.tools} tools used` : ' '}</Typography.Text></Card></Col>
        <Col xs={24} lg={10}>
          <Card title="Busiest tools">
            <Table size="small" rowKey="name" loading={top.isLoading} pagination={false} dataSource={top.data?.entries ?? []}
              columns={[
                { title: 'Tool', dataIndex: 'name', render: (n: string) => <Typography.Text code>{n}</Typography.Text> },
                { title: 'Calls', dataIndex: 'calls', align: 'right', render: (v: number) => formatCount(v) },
                { title: 'Failed', dataIndex: 'errors', align: 'right', render: (v: number) => formatCount(v) },
                { title: 'Avg', dataIndex: 'avgMs', align: 'right', render: (v: number) => formatMs(v) },
              ]} />
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card title="Recent calls">
            <Table<McpCall> size="small" rowKey={(c) => `${c.requestId}-${c.time}-${c.tool}`} loading={calls.isLoading}
              pagination={{ pageSize: 10, hideOnSinglePage: true }} dataSource={calls.data ?? []}
              columns={[
                { title: 'When', dataIndex: 'time', render: (v: string) => fromNow(v) },
                { title: 'Tool', dataIndex: 'tool', render: (n: string) => <Typography.Text code>{n}</Typography.Text> },
                { title: 'App', dataIndex: 'app', render: (a: string) => a || '—' },
                { title: 'Result', key: 'result', render: (_, c) => (c.error ? <Tag color="red">{c.upstreamStatus || 'error'}</Tag> : <Tag color="green">{c.upstreamStatus || 'ok'}</Tag>) },
                { title: 'Time', dataIndex: 'totalMs', align: 'right', render: (v: number) => formatMs(v) },
              ]} />
          </Card>
        </Col>
      </Row>
    </>
  );
}
