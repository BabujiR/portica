import { useState } from 'react';
import { Alert, Button, Col, Form, Input, InputNumber, Modal, Popconfirm, Radio, Row, Space, Switch, Table, Tag, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createTargetServer, deleteTargetServer, listTargetServers, updateTargetServer, type TargetServerBody } from '../../api/endpoints';
import type { TargetServer } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { fromNow, RESOURCE_NAME_RULE } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';

export function TargetServers() {
  const { org, env, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const servers = useQuery({
    queryKey: ['target-servers', org, env],
    queryFn: () => listTargetServers(org, env as string),
    enabled: !!env && can('ENV_READ'),
  });
  const [editing, setEditing] = useState<TargetServer | 'new' | null>(null);
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['target-servers', org, env] });
  const save = useMutation({
    mutationFn: (v: TargetServerBody) => {
      const { name, ...rest } = v;
      return editing === 'new' ? createTargetServer(org, env as string, v) : updateTargetServer(org, env as string, name, rest);
    },
    onSuccess: () => {
      invalidate();
      setEditing(null);
    },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: (name: string) => deleteTargetServer(org, env as string, name),
    onSuccess: invalidate,
    onError: (e) => notify.error(e, 'Couldn’t delete the target server'),
  });

  if (!can('ENV_READ')) return <NoAccess what="reading target servers" />;
  const writable = can('TARGET_SERVER_WRITE');

  return (
    <Page
      title="Target Servers"
      subtitle={<>Named backends per environment, so one proxy revision can reach different hosts in each. Showing <b>{env ?? 'no environment'}</b> (switch at the top).</>}
      extra={writable && env && <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>New target server</Button>}
    >
      {!env ? <Alert type="info" showIcon message="Create an environment first." /> : (
        <Loadable loading={servers.isLoading} error={servers.error}>
          <Table<TargetServer>
            rowKey="id"
            dataSource={servers.data?.items ?? []}
            pagination={false}
            locale={{ emptyText: `No target servers in ${env}.` }}
            columns={[
              { title: 'Name', dataIndex: 'name', render: (n: string) => <b>{n}</b> },
              { title: 'Base URI', dataIndex: 'baseUri', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
              { title: 'Enabled', dataIndex: 'enabled', render: (v: boolean) => (v ? <Tag color="green">enabled</Tag> : <Tag>disabled</Tag>) },
              { title: 'Description', dataIndex: 'description' },
              { title: 'Updated', dataIndex: 'updatedAt', render: (v: string) => fromNow(v) },
              {
                title: '', key: 'actions', align: 'right', render: (_, s) => writable && (
                  <Space>
                    <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(s)} aria-label={`Edit ${s.name}`} />
                    <Popconfirm title={`Delete ${s.name}?`} description="Refused while deployed proxies use it." okButtonProps={{ danger: true }}
                      onConfirm={() => remove.mutate(s.name)}>
                      <Button size="small" danger icon={<DeleteOutlined />} aria-label={`Delete ${s.name}`} />
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        </Loadable>
      )}
      <Modal open={!!editing} title={editing === 'new' ? `New target server in ${env}` : 'Edit target server'} footer={null}
        onCancel={() => setEditing(null)} destroyOnClose>
        <Form<TargetServerBody>
          layout="vertical"
          onFinish={(v) => save.mutate(v)}
          initialValues={editing && editing !== 'new'
            ? { name: editing.name, host: editing.host, port: editing.port, protocol: editing.protocol, enabled: editing.enabled, description: editing.description }
            : { protocol: 'HTTPS', port: 443, enabled: true }}
        >
          <Form.Item name="name" label="Name" rules={[{ required: true }, RESOURCE_NAME_RULE]}><Input disabled={editing !== 'new'} placeholder="orders-backend" /></Form.Item>
          <Row gutter={12}>
            <Col span={16}><Form.Item name="host" label="Host" rules={[{ required: true }]}><Input placeholder="orders.internal" /></Form.Item></Col>
            <Col span={8}><Form.Item name="port" label="Port" rules={[{ required: true }]}><InputNumber min={1} max={65535} style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Space size="large">
            <Form.Item name="protocol" label="Protocol">
              <Radio.Group optionType="button" options={[{ value: 'HTTPS', label: 'HTTPS' }, { value: 'HTTP', label: 'HTTP' }]} />
            </Form.Item>
            <Form.Item name="enabled" label="Enabled" valuePropName="checked"><Switch /></Form.Item>
          </Space>
          <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={save.isPending}>Save</Button>
        </Form>
      </Modal>
    </Page>
  );
}
