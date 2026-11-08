import { useState } from 'react';
import { Alert, Button, Drawer, Form, Table, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { createMcpServer, listMcpServers } from '../../api/endpoints';
import type { McpServer } from '../../api/types';
import { config } from '../../config';
import { useWorkspace } from '../../app/Workspace';
import { fromNow } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';
import { McpServerFields, initialMcpForm, toMcpBody, type McpForm } from './McpServerForm';
import { REMOTE_AUTH_LABELS, mcpEndpoint } from './mcpModel';

/** MCP servers: tools generated from API proxies, and remote MCP servers Portica fronts. */
export function McpServers() {
  const { org, env, can } = useWorkspace();
  const notify = useNotify();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<McpForm>();
  const servers = useQuery({ queryKey: ['mcp-servers', org], queryFn: () => listMcpServers(org), enabled: can('MCP_READ') });
  const create = useMutation({
    mutationFn: (v: McpForm) => createMcpServer(org, toMcpBody(v, true)),
    onSuccess: (server) => {
      void queryClient.invalidateQueries({ queryKey: ['mcp-servers', org] });
      setCreating(false);
      navigate(`/o/${org}/mcp/${server.name}`);
    },
    onError: (e) => notify.error(e),
  });

  if (!can('MCP_READ')) return <NoAccess what="MCP servers" />;
  const oauthError = params.get('mcpOAuth');

  return (
    <Page
      title="MCP Servers"
      subtitle="Model Context Protocol endpoints for AI agents, governed by the gateway like any API"
      extra={can('MCP_WRITE') && (
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
          New MCP server
        </Button>
      )}
    >
      {oauthError && <Alert type="warning" showIcon closable style={{ marginBottom: 16 }} message={oauthError} />}
      <Loadable loading={servers.isLoading} error={servers.error}>
        <Table<McpServer>
          rowKey="id"
          pagination={false}
          dataSource={servers.data ?? []}
          locale={{ emptyText: 'No MCP servers yet. Turn an API proxy with an OpenAPI spec into tools, or front a remote MCP server.' }}
          columns={[
            { title: 'Name', key: 'name', render: (_, s) => <Link to={`/o/${org}/mcp/${s.name}`}><b>{s.name}</b></Link> },
            {
              title: 'Source', key: 'source', render: (_, s) => (s.type === 'OPENAPI'
                ? <><Tag color="blue">OpenAPI</Tag>{s.apiProxy}</>
                : <><Tag color="purple">Remote</Tag><Typography.Text type="secondary">{s.remote?.url}</Typography.Text></>),
            },
            {
              title: 'Upstream auth', key: 'upstream', render: (_, s) => (s.type === 'REMOTE' && s.remote
                ? <>{REMOTE_AUTH_LABELS[s.remote.auth]}{s.remote.oauth && (s.remote.oauth.connected
                  ? <Tag color="green" style={{ marginLeft: 6 }}>connected</Tag>
                  : <Tag color="orange" style={{ marginLeft: 6 }}>not connected</Tag>)}</>
                : '—'),
            },
            { title: 'Tools', key: 'tools', render: (_, s) => (s.tools.length ? `${s.tools.length} allowed` : 'all') },
            {
              title: 'Endpoint', key: 'endpoint', render: (_, s) => (
                <Typography.Text copyable code style={{ fontSize: 12 }}>{mcpEndpoint(config.gatewayUrl, org, env ?? 'dev', s)}</Typography.Text>
              ),
            },
            { title: 'Updated', dataIndex: 'updatedAt', render: (v: string) => fromNow(v) },
          ]}
        />
      </Loadable>
      <Drawer open={creating} width={680} title="New MCP server" onClose={() => setCreating(false)} destroyOnClose
        extra={<Button type="primary" loading={create.isPending} onClick={() => form.submit()}>Create</Button>}>
        <Form<McpForm> form={form} layout="vertical" initialValues={initialMcpForm()} onFinish={(v) => create.mutate(v)}>
          <McpServerFields form={form} editing={false} />
        </Form>
      </Drawer>
    </Page>
  );
}
