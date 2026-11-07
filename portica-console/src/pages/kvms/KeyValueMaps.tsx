import { useEffect, useState } from 'react';
import {
  Alert, Button, Card, Col, Empty, Form, Input, List, Modal, Popconfirm, Row, Segmented, Space, Switch, Table, Tag,
  Typography,
} from 'antd';
import { DeleteOutlined, EditOutlined, LockOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createKvm, deleteKvm, deleteKvmEntry, listKvmEntries, listKvms, putKvmEntry } from '../../api/endpoints';
import type { KeyValueEntry, KeyValueMap } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { fromNow } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';

const MAP_NAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const ENTRY_KEY = /^[A-Za-z0-9._:-]{1,255}$/;

export function KeyValueMaps() {
  const { org, env, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const [scope, setScope] = useState<'environment' | 'organization'>('environment');
  const scopeEnv = scope === 'environment' ? env : null;
  const maps = useQuery({
    queryKey: ['kvms', org, scopeEnv],
    queryFn: () => listKvms(org, scopeEnv),
    enabled: can('PROXY_READ') && (scope === 'organization' || !!env),
  });
  const [selected, setSelected] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    const names = (maps.data ?? []).map((m) => m.name);
    if (!selected || !names.includes(selected)) setSelected(names[0] ?? null);
  }, [maps.data, selected]);

  const create = useMutation({
    mutationFn: (v: { name: string; encrypted: boolean }) => createKvm(org, scopeEnv, v),
    onSuccess: (m) => {
      void queryClient.invalidateQueries({ queryKey: ['kvms', org, scopeEnv] });
      setCreating(false);
      setSelected(m.name);
    },
    onError: (e) => notify.error(e, 'Couldn’t create the map'),
  });
  const remove = useMutation({
    mutationFn: (name: string) => deleteKvm(org, scopeEnv, name),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['kvms', org, scopeEnv] }),
    onError: (e) => notify.error(e),
  });

  if (!can('PROXY_READ')) return <NoAccess what="reading key value maps" />;
  const writable = can('PROXY_WRITE');
  const map = (maps.data ?? []).find((m) => m.name === selected) ?? null;

  return (
    <Page
      title="Key Value Maps"
      subtitle="Configuration and secrets that policies read at runtime with KeyValueMapOperations."
      extra={
        <>
          <Segmented
            value={scope}
            onChange={(v) => setScope(v as 'environment' | 'organization')}
            options={[{ value: 'environment', label: `Environment: ${env ?? '–'}` }, { value: 'organization', label: 'Organization' }]}
          />
          {writable && (scope === 'organization' || env) && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>New map</Button>
          )}
        </>
      }
    >
      <Row gutter={16}>
        <Col xs={24} md={8}>
          <Card size="small" title="Maps">
            <Loadable loading={maps.isLoading} error={maps.error}>
              <List<KeyValueMap>
                dataSource={maps.data ?? []}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No maps in this scope" /> }}
                renderItem={(m) => (
                  <List.Item
                    onClick={() => setSelected(m.name)}
                    style={{ cursor: 'pointer', paddingInline: 8, borderRadius: 6, background: m.name === selected ? 'var(--portica-selected)' : undefined }}
                    actions={writable ? [
                      <Popconfirm key="d" title={`Delete ${m.name} and its entries?`} description="Proxies reading it get default values."
                        okButtonProps={{ danger: true }} onConfirm={() => remove.mutate(m.name)}>
                        <Button size="small" type="text" danger icon={<DeleteOutlined />} aria-label={`Delete ${m.name}`} onClick={(e) => e.stopPropagation()} />
                      </Popconfirm>,
                    ] : []}
                  >
                    <List.Item.Meta
                      title={<Space>{m.name}{m.encrypted && <Tag icon={<LockOutlined />} color="purple">encrypted</Tag>}</Space>}
                      description={`${m.entryCount} entr${m.entryCount === 1 ? 'y' : 'ies'}`}
                    />
                  </List.Item>
                )}
              />
            </Loadable>
          </Card>
        </Col>
        <Col xs={24} md={16}>
          {map ? <Entries map={map} env={scopeEnv} writable={writable} />
            : <Card><Empty description="Select or create a map" /></Card>}
        </Col>
      </Row>
      <Modal open={creating} title={`New ${scope === 'environment' ? `${env} ` : 'organization '}key value map`} footer={null}
        onCancel={() => setCreating(false)} destroyOnClose>
        <Form<{ name: string; encrypted: boolean }> layout="vertical" initialValues={{ encrypted: false }} onFinish={(v) => create.mutate(v)}>
          <Form.Item name="name" label="Name" rules={[{ required: true }, { pattern: MAP_NAME, message: 'Lower-case letters, digits and hyphens' }]}>
            <Input placeholder="backend-config" />
          </Form.Item>
          <Form.Item name="encrypted" label="Encrypted" valuePropName="checked"
            extra="Values are encrypted at rest, never shown again, and can only be read into private.* variables. Can't be changed later.">
            <Switch />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={create.isPending}>Create</Button>
        </Form>
      </Modal>
    </Page>
  );
}

function Entries({ map, env, writable }: { map: KeyValueMap; env: string | null; writable: boolean }) {
  const { org } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const entries = useQuery({ queryKey: ['kvm-entries', org, env, map.name], queryFn: () => listKvmEntries(org, env, map.name) });
  const [editing, setEditing] = useState<KeyValueEntry | 'new' | null>(null);
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['kvm-entries', org, env, map.name] });
    void queryClient.invalidateQueries({ queryKey: ['kvms', org, env] });
  };
  const put = useMutation({
    mutationFn: (v: { key: string; value: string }) => putKvmEntry(org, env, map.name, v.key, v.value),
    onSuccess: () => {
      invalidate();
      setEditing(null);
    },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: (key: string) => deleteKvmEntry(org, env, map.name, key),
    onSuccess: invalidate,
    onError: (e) => notify.error(e),
  });

  return (
    <Card
      size="small"
      title={<Space>{map.name}{map.encrypted && <Tag icon={<LockOutlined />} color="purple">encrypted</Tag>}</Space>}
      extra={writable && <Button size="small" icon={<PlusOutlined />} onClick={() => setEditing('new')}>Add entry</Button>}
    >
      {map.encrypted && (
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="Values are hidden. Read them in a proxy into private.* variables, e.g. assignTo: private.backend.token." />
      )}
      <Loadable loading={entries.isLoading} error={entries.error}>
        <Table<KeyValueEntry>
          size="small"
          rowKey="key"
          dataSource={entries.data?.items ?? []}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: 'No entries' }}
          columns={[
            { title: 'Key', dataIndex: 'key', render: (k: string) => <Typography.Text code>{k}</Typography.Text> },
            { title: 'Value', dataIndex: 'value', render: (v: string) => (map.encrypted ? <Typography.Text type="secondary">{v}</Typography.Text>
              : <Typography.Text style={{ wordBreak: 'break-all' }}>{v}</Typography.Text>) },
            { title: 'Updated', key: 'u', render: (_, e) => `${fromNow(e.updatedAt)} · ${e.updatedBy ?? '–'}` },
            {
              title: '', key: 'a', align: 'right', render: (_, e) => writable && (
                <Space>
                  <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(e)} aria-label={`Edit ${e.key}`} />
                  <Popconfirm title={`Delete ${e.key}?`} onConfirm={() => remove.mutate(e.key)}>
                    <Button size="small" danger icon={<DeleteOutlined />} aria-label={`Delete ${e.key}`} />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Loadable>
      <Modal open={!!editing} title={editing === 'new' ? 'Add entry' : `Edit ${editing ? editing.key : ''}`} footer={null}
        onCancel={() => setEditing(null)} destroyOnClose>
        <Form<{ key: string; value: string }>
          layout="vertical"
          onFinish={(v) => put.mutate(v)}
          initialValues={editing && editing !== 'new' ? { key: editing.key, value: map.encrypted ? '' : editing.value } : undefined}
        >
          <Form.Item name="key" label="Key" rules={[{ required: true }, { pattern: ENTRY_KEY, message: "Letters, digits, '.', '_', ':' or '-'" }]}>
            <Input disabled={editing !== 'new'} placeholder="backend.url" />
          </Form.Item>
          <Form.Item name="value" label={map.encrypted ? 'Value (replaces the stored secret)' : 'Value'} rules={[{ required: true }]}>
            {map.encrypted ? <Input.Password autoComplete="new-password" /> : <Input.TextArea autoSize={{ minRows: 2 }} />}
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={put.isPending}>Save</Button>
        </Form>
      </Modal>
    </Card>
  );
}
