import { useEffect, useState } from 'react';
import { Alert, Card, Collapse, Descriptions, Empty, Segmented, Tag, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getCatalog, getMcpTools } from '../../api/portal';
import { Loadable, Page } from '../../components/Page';

/** One MCP server: its endpoint per environment, how to connect, and the tools it offers. */
export function PortalMcpServer() {
  const { org = '', server = '' } = useParams();
  const catalog = useQuery({ queryKey: ['portal-catalog', org], queryFn: () => getCatalog(org) });
  const entry = catalog.data?.mcpServers.find((s) => s.name === server);
  const [env, setEnv] = useState<string | undefined>();
  useEffect(() => {
    if (!env && entry?.endpoints.length) setEnv(entry.endpoints[0].environment);
  }, [entry, env]);
  const tools = useQuery({ queryKey: ['portal-tools', org, server, env], queryFn: () => getMcpTools(org, server, env as string), enabled: !!env });
  const endpoint = entry?.endpoints.find((e) => e.environment === env);

  return (
    <Page
      title={entry?.displayName ?? server}
      subtitle={entry?.description}
      crumbs={[{ title: 'APIs and tools', to: `/portal/${org}` }, { title: server }]}
      extra={entry && entry.endpoints.length > 0 && (
        <Segmented value={env} onChange={(v) => setEnv(String(v))} options={entry.endpoints.map((e) => e.environment)} />
      )}
    >
      <Loadable loading={catalog.isLoading} error={catalog.error}>
        {!entry ? <Empty description="This MCP server isn't published" /> : (
          <>
            <Card size="small" style={{ marginBottom: 16 }}>
              <Descriptions size="small" column={1}>
                <Descriptions.Item label="Endpoint">
                  {endpoint ? <Typography.Text code copyable>{endpoint.url}</Typography.Text> : 'not live'}
                </Descriptions.Item>
                <Descriptions.Item label="Transport">Streamable HTTP (JSON-RPC over POST)</Descriptions.Item>
                <Descriptions.Item label="Access">An app key with one of: {entry.products.join(', ')} (header x-api-key, or an OAuth token)</Descriptions.Item>
              </Descriptions>
              {endpoint && (
                <pre className="portal-response">{JSON.stringify({
                  mcpServers: { [server]: { type: 'http', url: endpoint.url, headers: { 'x-api-key': '<your key>' } } },
                }, null, 2)}</pre>
              )}
            </Card>
            <Loadable loading={tools.isLoading} error={tools.error}>
              {tools.data && (tools.data.tools.length === 0 ? <Alert type="info" showIcon message="No tools in this environment" /> : (
                <Collapse items={tools.data.tools.map((t) => ({
                  key: t.name,
                  label: <><Tag color="purple">{t.name}</Tag>{t.title && t.title !== t.name ? t.title : ''}</>,
                  children: (
                    <>
                      <Typography.Paragraph>{t.description || 'No description'}</Typography.Paragraph>
                      <Typography.Text type="secondary">Input schema</Typography.Text>
                      <pre className="portal-response">{JSON.stringify(t.inputSchema, null, 2)}</pre>
                    </>
                  ),
                }))} />
              ))}
            </Loadable>
          </>
        )}
      </Loadable>
    </Page>
  );
}
