import { useState } from 'react';
import { Button, Form, Input, Modal, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  createEnvironment, deleteEnvironment, listEnvironmentDeployments, listEnvironments, updateEnvironment,
} from '../../api/endpoints';
import type { Deployment, Environment } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { config } from '../../config';
import { fromNow, RESOURCE_NAME_RULE } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';

export function Environments() {
  const { org, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const environments = useQuery({ queryKey: ['environments', org], queryFn: () => listEnvironments(org), enabled: can('ENV_READ') });
  const [editing, setEditing] = useState<Environment | 'new' | null>(null);
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['environments', org] });
  const save = useMutation({
    mutationFn: (v: { name: string; displayName?: string; description?: string }) => (editing === 'new'
      ? createEnvironment(org, v)
      : updateEnvironment(org, (editing as Environment).name, { displayName: v.displayName, description: v.description })),
    onSuccess: () => {
      invalidate();
      setEditing(null);
    },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: (name: string) => deleteEnvironment(org, name),
    onSuccess: invalidate,
    onError: (e) => notify.error(e, 'Couldn’t delete the environment'),
  });

  if (!can('ENV_READ')) return <NoAccess what="reading environments" />;
  return (
    <Page
      title="Environments"
      subtitle="Isolated runtime stages (dev, test, prod...). Proxies, shared flows, target servers and key value maps are deployed per environment."
      extra={can('ENV_CREATE') && <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>New environment</Button>}
    >
      <Loadable loading={environments.isLoading} error={environments.error}>
        <Table<Environment>
          rowKey="id"
          dataSource={environments.data?.items ?? []}
          pagination={false}
          expandable={{ expandedRowRender: (e) => <EnvironmentDeployments env={e.name} />, rowExpandable: () => can('PROXY_READ') }}
          columns={[
            { title: 'Name', dataIndex: 'name', render: (_, e) => <span><b>{e.displayName}</b> <Typography.Text type="secondary">({e.name})</Typography.Text></span> },
            { title: 'Gateway base URL', key: 'url', render: (_, e) => <Typography.Text code copyable>{`${config.gatewayUrl}/${org}/${e.name}`}</Typography.Text> },
            { title: 'Description', dataIndex: 'description' },
            { title: 'Updated', dataIndex: 'updatedAt', render: (v: string) => fromNow(v) },
            {
              title: '', key: 'actions', align: 'right', render: (_, e) => (
                <Space>
                  {can('ENV_UPDATE') && <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(e)} aria-label={`Edit ${e.name}`} />}
                  {can('ENV_DELETE') && (
                    <Popconfirm title={`Delete ${e.name}?`} description="Refused while anything is deployed to it." okButtonProps={{ danger: true }}
                      onConfirm={() => remove.mutate(e.name)}>
                      <Button size="small" danger icon={<DeleteOutlined />} aria-label={`Delete ${e.name}`} />
                    </Popconfirm>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Loadable>
      <Modal open={!!editing} title={editing === 'new' ? 'New environment' : 'Edit environment'} footer={null} onCancel={() => setEditing(null)} destroyOnClose>
        <Form layout="vertical" onFinish={(v) => save.mutate(v)}
          initialValues={editing && editing !== 'new' ? { name: editing.name, displayName: editing.displayName, description: editing.description } : undefined}>
          <Form.Item name="name" label="Name" rules={[{ required: true }, RESOURCE_NAME_RULE]}><Input disabled={editing !== 'new'} placeholder="staging" /></Form.Item>
          <Form.Item name="displayName" label="Display name"><Input placeholder="Staging" /></Form.Item>
          <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={save.isPending}>Save</Button>
        </Form>
      </Modal>
    </Page>
  );
}

function EnvironmentDeployments({ env }: { env: string }) {
  const { org } = useWorkspace();
  const deployments = useQuery({ queryKey: ['deployments', org, env], queryFn: () => listEnvironmentDeployments(org, env) });
  return (
    <Table<Deployment>
      size="small"
      rowKey="apiProxy"
      loading={deployments.isLoading}
      dataSource={deployments.data ?? []}
      pagination={false}
      locale={{ emptyText: 'No API proxies deployed here.' }}
      columns={[
        { title: 'API proxy', dataIndex: 'apiProxy', render: (p: string) => <Link to={`/o/${org}/proxies/${p}`}>{p}</Link> },
        { title: 'Revision', dataIndex: 'revision', render: (r: number) => <Tag color="green">r{r}</Tag> },
        { title: 'Path', dataIndex: 'gatewayPath', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
        { title: 'Deployed', dataIndex: 'deployedAt', render: (v: string) => fromNow(v) },
      ]}
    />
  );
}
