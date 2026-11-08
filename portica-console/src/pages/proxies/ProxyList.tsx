import { useMemo, useState } from 'react';
import { Button, Input, Table, Tag, Typography } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { listEnvironmentDeployments, listProxies } from '../../api/endpoints';
import type { ApiProxy } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { fromNow } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';

export function ProxyList() {
  const { org, env, can } = useWorkspace();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const proxies = useQuery({ queryKey: ['proxies', org], queryFn: () => listProxies(org), enabled: can('PROXY_READ') });
  const deployments = useQuery({
    queryKey: ['deployments', org, env],
    queryFn: () => listEnvironmentDeployments(org, env as string),
    enabled: !!env && can('PROXY_READ'),
  });
  const deployedRevision = useMemo(
    () => new Map((deployments.data ?? []).map((d) => [d.apiProxy, d])),
    [deployments.data],
  );
  const rows = useMemo(() => {
    const term = q.trim().toLowerCase();
    return (proxies.data?.items ?? []).filter((p) => !term || p.name.includes(term) || p.displayName.toLowerCase().includes(term));
  }, [proxies.data, q]);

  if (!can('PROXY_READ')) return <NoAccess what="reading API proxies" />;

  return (
    <Page
      title="API Proxies"
      subtitle="Each proxy gives a backend a stable base path on the gateway and runs its policies."
      extra={can('PROXY_WRITE') && (
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/o/${org}/proxies/new`)}>New proxy</Button>
      )}
    >
      <Input allowClear prefix={<SearchOutlined />} placeholder="Search proxies" value={q} onChange={(e) => setQ(e.target.value)}
        style={{ maxWidth: 320, marginBottom: 16 }} />
      <Loadable loading={proxies.isLoading} error={proxies.error}>
        <Table<ApiProxy>
          rowKey="id"
          dataSource={rows}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: 'No API proxies yet. Create one from a backend URL or an OpenAPI spec.' }}
          columns={[
            {
              title: 'Name',
              dataIndex: 'name',
              sorter: (a, b) => a.name.localeCompare(b.name),
              render: (_, p) => (
                <div>
                  <Link to={`/o/${org}/proxies/${p.name}`}><Typography.Text strong>{p.displayName}</Typography.Text></Link>
                  <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{p.name}</Typography.Text></div>
                </div>
              ),
            },
            { title: 'Latest revision', dataIndex: 'latestRevision', width: 140, render: (r: number) => `r${r}` },
            {
              title: env ? `Deployed in ${env}` : 'Deployed',
              key: 'deployed',
              render: (_, p) => {
                const d = deployedRevision.get(p.name);
                return d ? (
                  <span>
                    <Tag color="green">r{d.revision}</Tag>
                    <Typography.Text code copyable>{d.gatewayPath}</Typography.Text>
                  </span>
                ) : <Typography.Text type="secondary">Not deployed</Typography.Text>;
              },
            },
            { title: 'Updated', dataIndex: 'updatedAt', width: 160, render: (v: string) => fromNow(v),
              sorter: (a, b) => a.updatedAt.localeCompare(b.updatedAt) },
          ]}
        />
      </Loadable>
    </Page>
  );
}
