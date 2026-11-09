import { useState } from 'react';
import {
  Alert, Button, Card, Empty, Flex, Form, Input, Modal, Popconfirm, Result, Select, Space, Table, Tag, Tooltip, Typography,
} from 'antd';
import { DeleteOutlined, KeyOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import {
  addMyCertificate, createMyApp, createMyKey, deleteMyApp, deleteMyKey, getCatalog, getMe, listMyApps, register,
  removeMyCertificate, type PortalProduct,
} from '../../api/portal';
import type { Credential, DeveloperApp, IssuedCredential } from '../../api/types';
import { dateTime, fromNow, RESOURCE_NAME_RULE } from '../../components/format';
import { Loadable, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';

/** Gate for pages about the developer: register with the organization first. */
export function useDeveloper(org: string) {
  return useQuery({ queryKey: ['portal-me', org], queryFn: () => getMe(org) });
}

export function RegisterCard({ org }: { org: string }) {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const save = useMutation({
    mutationFn: (v: { firstName: string; lastName: string }) => register(org, v),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['portal-me', org] }),
    onError: (e) => notify.error(e),
  });
  return (
    <Card style={{ maxWidth: 480, margin: '32px auto' }} title={`Join ${org} as a developer`}>
      <Typography.Paragraph type="secondary">
        Registering lets you create apps and get keys for the published APIs. We use the e-mail address you signed in with.
      </Typography.Paragraph>
      <Form layout="vertical" onFinish={(v: { firstName: string; lastName: string }) => save.mutate(v)}>
        <Form.Item name="firstName" label="First name" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="lastName" label="Last name" rules={[{ required: true }]}><Input /></Form.Item>
        <Button type="primary" htmlType="submit" loading={save.isPending}>Register</Button>
      </Form>
    </Card>
  );
}

export function PortalApps() {
  const { org = '' } = useParams();
  const me = useDeveloper(org);
  if (me.isLoading || me.error) return <Loadable loading={me.isLoading} error={me.error}>{null}</Loadable>;
  if (!me.data) return <RegisterCard org={org} />;
  if (me.data.status !== 'ACTIVE') return <Result status="403" title="Your developer account is inactive" subTitle="Contact the API team." />;
  return <AppsList org={org} />;
}

function AppsList({ org }: { org: string }) {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const apps = useQuery({ queryKey: ['portal-apps', org], queryFn: () => listMyApps(org) });
  const catalog = useQuery({ queryKey: ['portal-catalog', org], queryFn: () => getCatalog(org) });
  const [creating, setCreating] = useState(false);
  const [issued, setIssued] = useState<IssuedCredential | null>(null);
  const [certifying, setCertifying] = useState<{ app: string; key: Credential } | null>(null);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['portal-apps', org] });
  const run = <T,>(fn: () => Promise<T>, done?: string) => fn().then((r) => {
    refresh();
    if (done) notify.success(done);
    return r;
  }, (e: unknown) => {
    notify.error(e);
    return undefined;
  });
  const products: PortalProduct[] = catalog.data?.products ?? [];

  return (
    <Page title="My apps" subtitle="Each app gets keys for the API products you choose."
      extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>New app</Button>}>
      <Loadable loading={apps.isLoading} error={apps.error}>
        {(apps.data ?? []).length === 0 ? <Empty description="No apps yet" /> : (
          <Flex vertical gap={16}>
            {(apps.data ?? []).map((a: DeveloperApp) => (
              <Card key={a.name} title={<Space>{a.displayName}<Typography.Text type="secondary" code>{a.name}</Typography.Text>
                {a.status !== 'APPROVED' && <Tag color="red">revoked</Tag>}</Space>}
                extra={
                  <Space>
                    <Button size="small" icon={<KeyOutlined />} onClick={() => void run(() => createMyKey(org, a.name, {})).then((k) => k && setIssued(k))}>New key</Button>
                    <Popconfirm title={`Delete ${a.name}?`} description="All its keys stop working." okButtonProps={{ danger: true }}
                      onConfirm={() => void run(() => deleteMyApp(org, a.name), 'App deleted')}>
                      <Button size="small" danger icon={<DeleteOutlined />} aria-label={`Delete ${a.name}`} />
                    </Popconfirm>
                  </Space>
                }>
                <Table<Credential>
                  rowKey="id"
                  size="small"
                  pagination={false}
                  dataSource={[...a.credentials].reverse()}
                  columns={[
                    { title: 'Key', key: 'k', render: (_, c) => <Space direction="vertical" size={0}>
                      <Typography.Text code>{c.keyPrefix}…</Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>created {fromNow(c.createdAt)}</Typography.Text></Space> },
                    { title: 'Status', key: 's', render: (_, c) => (c.status === 'REVOKED' ? <Tag color="red">revoked</Tag>
                      : c.expired ? <Tag>expired</Tag> : <Tag color="green">active</Tag>) },
                    { title: 'API products', key: 'p', render: (_, c) => <Flex wrap gap={4}>{c.apiProducts.map((g) => (
                      <Tag key={g.apiProduct} color={g.status === 'APPROVED' ? 'green' : g.status === 'PENDING' ? 'orange' : 'red'}>
                        {g.apiProduct} · {g.status.toLowerCase()}</Tag>))}</Flex> },
                    { title: 'Client certificates', key: 'c', render: (_, c) => <Flex wrap gap={4}>
                      {(c.certificates ?? []).map((cert) => (
                        <Tooltip key={cert.fingerprint} title={<>{cert.subject}<br />expires {dateTime(cert.notAfter)}</>}>
                          <Tag icon={<SafetyCertificateOutlined />} closable onClose={(e) => {
                            e.preventDefault();
                            void run(() => removeMyCertificate(org, a.name, c.id, cert.fingerprint), 'Certificate removed');
                          }}>{cert.fingerprint.slice(0, 8)}</Tag>
                        </Tooltip>
                      ))}
                      <Button size="small" type="link" onClick={() => setCertifying({ app: a.name, key: c })}>add</Button>
                    </Flex> },
                    { title: '', key: 'x', align: 'right', render: (_, c) => (
                      <Popconfirm title="Delete this key?" okButtonProps={{ danger: true }}
                        onConfirm={() => void run(() => deleteMyKey(org, a.name, c.id), 'Key deleted')}>
                        <Button size="small" danger type="text" icon={<DeleteOutlined />} aria-label={`Delete key ${c.keyPrefix}`} />
                      </Popconfirm>
                    ) },
                  ]}
                />
              </Card>
            ))}
          </Flex>
        )}
      </Loadable>

      <Modal open={creating} title="New app" footer={null} onCancel={() => setCreating(false)} destroyOnClose>
        <Form<{ name: string; displayName?: string; description?: string; callbackUrl?: string; apiProducts: string[] }>
          layout="vertical"
          onFinish={(v) => void run(() => createMyApp(org, v)).then((r) => {
            if (!r) return;
            setCreating(false);
            setIssued(r.credential);
          })}
        >
          <Form.Item name="name" label="Name" rules={[{ required: true }, RESOURCE_NAME_RULE]}><Input placeholder="my-mobile-app" /></Form.Item>
          <Form.Item name="displayName" label="Display name"><Input /></Form.Item>
          <Form.Item name="apiProducts" label="API products" rules={[{ required: true }]}>
            <Select mode="multiple" options={products.map((p) => ({
              value: p.name, label: `${p.displayName}${p.approvalType === 'MANUAL' ? ' (needs approval)' : ''}`,
            }))} />
          </Form.Item>
          <Form.Item name="callbackUrl" label="OAuth callback URL" rules={[{ type: 'url' }]}><Input placeholder="https://app.example.com/callback" /></Form.Item>
          <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
          <Button type="primary" htmlType="submit">Create app</Button>
        </Form>
      </Modal>

      <Modal open={!!issued} title="Your new key" onCancel={() => setIssued(null)} footer={<Button type="primary" onClick={() => setIssued(null)}>I've saved it</Button>}>
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message="Copy the secret now: it is shown only once." />
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Text>Key</Typography.Text>
          <Typography.Text code copyable>{issued?.consumerKey}</Typography.Text>
          <Typography.Text>Secret</Typography.Text>
          <Typography.Text code copyable>{issued?.consumerSecret}</Typography.Text>
        </Space>
      </Modal>

      <Modal open={!!certifying} title="Register a client certificate" footer={null} onCancel={() => setCertifying(null)} destroyOnClose>
        <Typography.Paragraph type="secondary">
          For APIs that require mutual TLS: requests presenting this certificate are identified as this app. Paste the public certificate only.
        </Typography.Paragraph>
        <Form<{ certificate: string }>
          layout="vertical"
          onFinish={(v) => certifying && void run(() => addMyCertificate(org, certifying.app, certifying.key.id, v.certificate.trim()), 'Certificate registered')
            .then((r) => r && setCertifying(null))}
        >
          <Form.Item name="certificate" label="Certificate (PEM)" rules={[{ required: true }]}>
            <Input.TextArea autoSize={{ minRows: 6 }} placeholder="-----BEGIN CERTIFICATE-----" spellCheck={false} />
          </Form.Item>
          <Button type="primary" htmlType="submit">Register</Button>
        </Form>
      </Modal>
    </Page>
  );
}
