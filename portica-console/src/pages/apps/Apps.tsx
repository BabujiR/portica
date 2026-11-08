import { useMemo, useState } from 'react';
import {
  Button, Card, Col, DatePicker, Descriptions, Dropdown, Empty, Flex, Form, Input, InputNumber, Modal, Popconfirm, Row,
  Select, Space, Switch, Table, Tag, Tooltip, Typography,
} from 'antd';
import type { MenuProps } from 'antd';
import {
  CheckOutlined, DeleteOutlined, EditOutlined, KeyOutlined, MoreOutlined, PlusOutlined, SafetyCertificateOutlined,
  SearchOutlined, StopOutlined, SyncOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  addCertificate, addKeyProduct, createKey, deleteApp, deleteKey, getApp, listAllApps, listProducts, removeCertificate,
  removeKeyProduct, rotateKey, setKeyProductStatus, updateApp, updateKey,
} from '../../api/endpoints';
import type { Credential, DeveloperApp, GrantStatus, IssuedCredential } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { dateTime, fromNow } from '../../components/format';
import { fromPairs, KeyValueList, toPairs, type Pairs } from '../../components/KeyValueList';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';
import { CreateAppModal } from './CreateAppModal';
import { IssuedKeyModal } from './IssuedKeyModal';

const GRANT_COLOR: Record<GrantStatus, string> = { APPROVED: 'green', PENDING: 'orange', REVOKED: 'red' };

export function AppList() {
  const { org, can } = useWorkspace();
  const apps = useQuery({ queryKey: ['apps', org], queryFn: () => listAllApps(org), enabled: can('DEVELOPER_READ') });
  const [q, setQ] = useState('');
  const [creating, setCreating] = useState(false);
  const rows = useMemo(() => {
    const term = q.trim().toLowerCase();
    return (apps.data?.items ?? []).filter((a) => !term || a.name.includes(term) || a.displayName.toLowerCase().includes(term)
      || a.developerEmail.includes(term));
  }, [apps.data, q]);

  if (!can('DEVELOPER_READ')) return <NoAccess what="reading apps" />;
  const pending = rows.filter((a) => a.credentials.some((c) => c.apiProducts.some((g) => g.status === 'PENDING'))).length;

  return (
    <Page
      title="Apps"
      subtitle="Every developer app in the organization, with its keys and API products."
      extra={can('DEVELOPER_WRITE') && <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>New app</Button>}
    >
      <Flex gap={12} align="center" wrap style={{ marginBottom: 16 }}>
        <Input allowClear prefix={<SearchOutlined />} placeholder="Search apps or developer email" value={q}
          onChange={(e) => setQ(e.target.value)} style={{ maxWidth: 320 }} />
        {pending > 0 && <Tag color="orange">{pending} app(s) waiting for product approval</Tag>}
      </Flex>
      <Loadable loading={apps.isLoading} error={apps.error}>
        <Table<DeveloperApp>
          rowKey="id"
          dataSource={rows}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: 'No apps yet. Register a developer, then create an app for them.' }}
          columns={[
            {
              title: 'App', dataIndex: 'name', sorter: (a, b) => a.name.localeCompare(b.name),
              render: (_, a) => (
                <div>
                  <Link to={`/o/${org}/apps/${encodeURIComponent(a.developerEmail)}/${a.name}`}><b>{a.displayName}</b></Link>
                  <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{a.developerEmail}</Typography.Text></div>
                </div>
              ),
            },
            { title: 'Status', dataIndex: 'status', render: (s: string) => <Tag color={s === 'APPROVED' ? 'green' : 'red'}>{s.toLowerCase()}</Tag> },
            {
              title: 'API products', key: 'products', render: (_, a) => {
                const grants = new Map<string, GrantStatus>();
                a.credentials.filter((c) => c.status === 'APPROVED' && !c.expired)
                  .forEach((c) => c.apiProducts.forEach((g) => grants.set(g.apiProduct, g.status)));
                return [...grants.entries()].map(([p, s]) => <Tag key={p} color={GRANT_COLOR[s]}>{p}</Tag>);
              },
            },
            { title: 'Active keys', key: 'keys', render: (_, a) => a.credentials.filter((c) => c.status === 'APPROVED' && !c.expired).length },
            { title: 'Created', dataIndex: 'createdAt', render: (v: string) => fromNow(v) },
          ]}
        />
      </Loadable>
      <CreateAppModal open={creating} onClose={() => setCreating(false)} />
    </Page>
  );
}

export function AppDetail() {
  const { org, can } = useWorkspace();
  const { email = '', app: appName = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const app = useQuery({ queryKey: ['app', org, email, appName], queryFn: () => getApp(org, email, appName) });
  const products = useQuery({ queryKey: ['products', org], queryFn: () => listProducts(org) });
  const [issued, setIssued] = useState<IssuedCredential | null>(null);
  const [newKeyOpen, setNewKeyOpen] = useState(false);
  const [rotating, setRotating] = useState<Credential | null>(null);
  const [expiring, setExpiring] = useState<Credential | null>(null);
  const [addingTo, setAddingTo] = useState<Credential | null>(null);
  const [certifying, setCertifying] = useState<Credential | null>(null);
  const [editing, setEditing] = useState(false);
  const writable = can('DEVELOPER_WRITE');

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['app', org, email, appName] });
    void queryClient.invalidateQueries({ queryKey: ['apps', org] });
    void queryClient.invalidateQueries({ queryKey: ['developer-apps', org, email] });
  };
  /** Runs an action, refreshes the app and reports the outcome; resolves to undefined on failure. */
  const run = <T,>(fn: () => Promise<T>, done?: string): Promise<T | undefined> => fn().then((r) => {
    refresh();
    if (done) notify.success(done);
    return r;
  }, (e: unknown) => {
    notify.error(e);
    return undefined;
  });

  const update = useMutation({
    mutationFn: (body: Parameters<typeof updateApp>[3]) => updateApp(org, email, appName, body),
    onSuccess: () => {
      refresh();
      setEditing(false);
    },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: () => deleteApp(org, email, appName),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['apps', org] });
      navigate(`/o/${org}/apps`);
    },
    onError: (e) => notify.error(e),
  });

  const a = app.data;
  const keyMenu = (c: Credential): MenuProps['items'] => [
    { key: 'add', icon: <PlusOutlined />, label: 'Add API product', onClick: () => setAddingTo(c) },
    { key: 'certificate', icon: <SafetyCertificateOutlined />, label: 'Register client certificate…', onClick: () => setCertifying(c) },
    { key: 'rotate', icon: <SyncOutlined />, label: 'Rotate…', disabled: c.status === 'REVOKED', onClick: () => setRotating(c) },
    { key: 'expiry', icon: <EditOutlined />, label: 'Set expiry…', onClick: () => setExpiring(c) },
    c.status === 'APPROVED'
      ? { key: 'revoke', icon: <StopOutlined />, danger: true, label: 'Revoke key',
          onClick: () => void run(() => updateKey(org, email, appName, c.id, { status: 'REVOKED' }), `Key ${c.keyPrefix}… revoked`) }
      : { key: 'approve', icon: <CheckOutlined />, label: 'Re-approve key',
          onClick: () => void run(() => updateKey(org, email, appName, c.id, { status: 'APPROVED' }), `Key ${c.keyPrefix}… approved`) },
    { type: 'divider' },
    { key: 'delete', icon: <DeleteOutlined />, danger: true, label: 'Delete key',
      onClick: () => void run(() => deleteKey(org, email, appName, c.id), 'Key deleted') },
  ];

  return (
    <Page
      title={a?.displayName ?? appName}
      subtitle={<>owned by <Link to={`/o/${org}/developers/${encodeURIComponent(email)}`}>{email}</Link></>}
      crumbs={[{ title: 'Apps', to: `/o/${org}/apps` }, { title: appName }]}
      extra={writable && a && (
        <>
          <Space>
            <Typography.Text>Approved</Typography.Text>
            <Switch checked={a.status === 'APPROVED'} loading={update.isPending} aria-label="App approved"
              onChange={(on) => update.mutate({ status: on ? 'APPROVED' : 'REVOKED' })} />
          </Space>
          <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>Edit</Button>
          <Popconfirm title="Delete this app?" description="All its keys stop working." okButtonProps={{ danger: true }} onConfirm={() => remove.mutate()}>
            <Button danger icon={<DeleteOutlined />}>Delete</Button>
          </Popconfirm>
        </>
      )}
    >
      <Loadable loading={app.isLoading} error={app.error}>
        {a && (
          <Row gutter={[16, 16]}>
            <Col xs={24} xl={8}>
              <Card title="App">
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="Name">{a.name}</Descriptions.Item>
                  <Descriptions.Item label="Status"><Tag color={a.status === 'APPROVED' ? 'green' : 'red'}>{a.status.toLowerCase()}</Tag></Descriptions.Item>
                  <Descriptions.Item label="Callback URL">{a.callbackUrl || '–'}</Descriptions.Item>
                  <Descriptions.Item label="Description">{a.description || '–'}</Descriptions.Item>
                  <Descriptions.Item label="Created">{dateTime(a.createdAt)}</Descriptions.Item>
                  {Object.entries(a.attributes).map(([k, v]) => <Descriptions.Item key={k} label={k}>{v}</Descriptions.Item>)}
                </Descriptions>
                {a.status === 'REVOKED' && <Typography.Paragraph type="danger" style={{ marginBottom: 0 }}>Revoked: none of its keys work.</Typography.Paragraph>}
              </Card>
            </Col>
            <Col xs={24} xl={16}>
              <Card title="Keys" extra={writable && <Button icon={<KeyOutlined />} onClick={() => setNewKeyOpen(true)}>New key</Button>}>
                {a.credentials.length === 0 ? <Empty description="No keys" /> : (
                  <Table<Credential>
                    rowKey="id"
                    size="small"
                    dataSource={[...a.credentials].reverse()}
                    pagination={false}
                    columns={[
                      {
                        title: 'Key', key: 'key', render: (_, c) => (
                          <Space direction="vertical" size={0}>
                            <Typography.Text code>{c.keyPrefix}…</Typography.Text>
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>created {fromNow(c.createdAt)}</Typography.Text>
                          </Space>
                        ),
                      },
                      {
                        title: 'Status', key: 'status', render: (_, c) => (
                          <Space direction="vertical" size={0}>
                            {c.status === 'REVOKED' ? <Tag color="red">revoked</Tag> : c.expired ? <Tag>expired</Tag> : <Tag color="green">active</Tag>}
                            {c.expiresAt && <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              {c.expired ? 'expired' : 'expires'} {fromNow(c.expiresAt)}</Typography.Text>}
                          </Space>
                        ),
                      },
                      {
                        title: 'API products', key: 'products', render: (_, c) => (
                          <Flex wrap gap={4}>
                            {c.apiProducts.length === 0 && <Typography.Text type="secondary">none</Typography.Text>}
                            {c.apiProducts.map((g) => (
                              <Dropdown
                                key={g.apiProduct}
                                disabled={!writable}
                                trigger={['click']}
                                menu={{
                                  items: [
                                    { key: 'APPROVED', label: 'Approve', disabled: g.status === 'APPROVED' },
                                    { key: 'REVOKED', label: 'Revoke', disabled: g.status === 'REVOKED' },
                                    { key: 'PENDING', label: 'Set pending', disabled: g.status === 'PENDING' },
                                    { type: 'divider' },
                                    { key: 'remove', label: 'Remove from key', danger: true },
                                  ],
                                  onClick: ({ key }) => void run(() => (key === 'remove'
                                    ? removeKeyProduct(org, email, appName, c.id, g.apiProduct)
                                    : setKeyProductStatus(org, email, appName, c.id, g.apiProduct, key as GrantStatus)), 'Updated'),
                                }}
                              >
                                <Tooltip title={writable ? 'Click to approve, revoke or remove' : undefined}>
                                  <Tag color={GRANT_COLOR[g.status]} style={{ cursor: writable ? 'pointer' : 'default' }}>
                                    {g.apiProduct} · {g.status.toLowerCase()}
                                  </Tag>
                                </Tooltip>
                              </Dropdown>
                            ))}
                          </Flex>
                        ),
                      },
                      {
                        title: 'Client certificates', key: 'certificates', render: (_, c) => (
                          <Flex wrap gap={4}>
                            {(c.certificates ?? []).length === 0 && <Typography.Text type="secondary">none</Typography.Text>}
                            {(c.certificates ?? []).map((cert) => (
                              <Tooltip key={cert.fingerprint}
                                title={<>{cert.subject}<br />issued by {cert.issuer}<br />expires {dateTime(cert.notAfter)}<br />SHA-256 {cert.fingerprint}</>}>
                                <Tag icon={<SafetyCertificateOutlined />} color={dayjs(cert.notAfter).isBefore(dayjs()) ? 'default' : 'blue'}
                                  closable={writable}
                                  onClose={(e) => {
                                    e.preventDefault();
                                    void run(() => removeCertificate(org, email, appName, c.id, cert.fingerprint), 'Certificate removed');
                                  }}>
                                  {cert.subject.replace(/^CN=/, '').split(',')[0]} · {cert.fingerprint.slice(0, 8)}
                                </Tag>
                              </Tooltip>
                            ))}
                          </Flex>
                        ),
                      },
                      {
                        title: '', key: 'actions', align: 'right', render: (_, c) => writable && (
                          <Dropdown menu={{ items: keyMenu(c) }} trigger={['click']}>
                            <Button size="small" icon={<MoreOutlined />} aria-label={`Actions for key ${c.keyPrefix}`} />
                          </Dropdown>
                        ),
                      },
                    ]}
                  />
                )}
              </Card>
            </Col>
          </Row>
        )}
      </Loadable>

      {/* New key */}
      <Modal open={newKeyOpen} title="Issue a new key" footer={null} onCancel={() => setNewKeyOpen(false)} destroyOnClose>
        <Form<{ apiProducts?: string[]; days?: number }>
          layout="vertical"
          onFinish={(v) => void run(() => createKey(org, email, appName, {
            apiProducts: v.apiProducts && v.apiProducts.length ? v.apiProducts : undefined,
            expiresInSeconds: v.days ? v.days * 86_400 : undefined,
          })).then((c) => {
            if (!c) return;
            setNewKeyOpen(false);
            setIssued(c);
          })}
        >
          <Form.Item name="apiProducts" label="API products" extra="Leave empty to copy the products of the newest key.">
            <Select mode="multiple" options={(products.data?.items ?? []).map((p) => ({ value: p.name, label: p.displayName }))} />
          </Form.Item>
          <Form.Item name="days" label="Expires after (days)"><InputNumber min={1} max={3650} placeholder="never" /></Form.Item>
          <Button type="primary" htmlType="submit">Issue key</Button>
        </Form>
      </Modal>

      {/* Rotate */}
      <Modal open={!!rotating} title={`Rotate key ${rotating?.keyPrefix ?? ''}…`} footer={null} onCancel={() => setRotating(null)} destroyOnClose>
        <Typography.Paragraph type="secondary">
          Issues a new key with the same API products. The old key keeps working for the grace period, so clients can switch without downtime.
        </Typography.Paragraph>
        <Form<{ graceHours: number }>
          layout="vertical"
          initialValues={{ graceHours: 24 }}
          onFinish={(v) => rotating && void run(() => rotateKey(org, email, appName, rotating.id, { graceSeconds: Math.round(v.graceHours * 3600) }))
            .then((r) => {
              if (!r) return;
              setRotating(null);
              setIssued(r.credential);
            })}
        >
          <Form.Item name="graceHours" label="Old key keeps working for (hours)" extra="0 revokes it immediately.">
            <InputNumber min={0} max={720} />
          </Form.Item>
          <Button type="primary" htmlType="submit" icon={<SyncOutlined />}>Rotate</Button>
        </Form>
      </Modal>

      {/* Expiry */}
      <Modal open={!!expiring} title={`Expiry of key ${expiring?.keyPrefix ?? ''}…`} footer={null} onCancel={() => setExpiring(null)} destroyOnClose>
        <Form<{ at: dayjs.Dayjs }>
          layout="vertical"
          initialValues={{ at: expiring?.expiresAt ? dayjs(expiring.expiresAt) : dayjs().add(30, 'day') }}
          onFinish={(v) => expiring && void run(() => updateKey(org, email, appName, expiring.id, { expiresAt: v.at.toISOString() }), 'Expiry updated')
            .then((r) => r && setExpiring(null))}
        >
          <Form.Item name="at" label="Expires at" rules={[{ required: true }]}>
            <DatePicker showTime />
          </Form.Item>
          <Button type="primary" htmlType="submit">Save</Button>
        </Form>
      </Modal>

      {/* Add product */}
      <Modal open={!!addingTo} title={`Add an API product to key ${addingTo?.keyPrefix ?? ''}…`} footer={null} onCancel={() => setAddingTo(null)} destroyOnClose>
        <Form<{ product: string }>
          layout="vertical"
          onFinish={(v) => addingTo && void run(() => addKeyProduct(org, email, appName, addingTo.id, v.product), 'Product added')
            .then((r) => r && setAddingTo(null))}
        >
          <Form.Item name="product" label="API product" rules={[{ required: true }]}>
            <Select options={(products.data?.items ?? [])
              .filter((p) => !addingTo?.apiProducts.some((g) => g.apiProduct === p.name))
              .map((p) => ({ value: p.name, label: `${p.displayName}${p.approvalType === 'MANUAL' ? ' (manual approval)' : ''}` }))} />
          </Form.Item>
          <Button type="primary" htmlType="submit">Add</Button>
        </Form>
      </Modal>

      {/* Register client certificate */}
      <Modal open={!!certifying} title={`Client certificate for key ${certifying?.keyPrefix ?? ''}…`} footer={null}
        onCancel={() => setCertifying(null)} destroyOnClose>
        <Typography.Paragraph type="secondary">
          Clients presenting this certificate over mutual TLS are identified as this app by the VerifyClientCertificate policy,
          with this key's API products. Only the public certificate is stored.
        </Typography.Paragraph>
        <Form<{ certificate: string }>
          layout="vertical"
          onFinish={(v) => certifying && void run(() => addCertificate(org, email, appName, certifying.id, v.certificate.trim()), 'Certificate registered')
            .then((r) => r && setCertifying(null))}
        >
          <Form.Item name="certificate" label="Certificate (PEM)" rules={[{ required: true }]}>
            <Input.TextArea autoSize={{ minRows: 6, maxRows: 14 }} placeholder="-----BEGIN CERTIFICATE-----" spellCheck={false} />
          </Form.Item>
          <Button type="primary" htmlType="submit" icon={<SafetyCertificateOutlined />}>Register</Button>
        </Form>
      </Modal>

      {/* Edit app */}
      {a && (
        <Modal open={editing} title="Edit app" onCancel={() => setEditing(false)} footer={null} destroyOnClose>
          <Form<{ displayName: string; description?: string; callbackUrl?: string; attributes: Pairs }>
            layout="vertical"
            initialValues={{ displayName: a.displayName, description: a.description, callbackUrl: a.callbackUrl, attributes: toPairs(a.attributes) }}
            onFinish={(v) => update.mutate({
              displayName: v.displayName, description: v.description ?? '', callbackUrl: v.callbackUrl ?? '', attributes: fromPairs(v.attributes),
            })}
          >
            <Form.Item name="displayName" label="Display name" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="callbackUrl" label="Callback URL" rules={[{ type: 'url' }]}><Input /></Form.Item>
            <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
            <Form.Item name="attributes" label="Attributes" extra="Available to policies as verifyapikey.<policy>.app.attribute.<name>.">
              <KeyValueList />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={update.isPending}>Save</Button>
          </Form>
        </Modal>
      )}

      <IssuedKeyModal credential={issued} onClose={() => setIssued(null)} />
    </Page>
  );
}
