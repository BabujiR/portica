import { useMemo, useState } from 'react';
import { Button, Card, Col, Descriptions, Form, Input, Modal, Popconfirm, Row, Space, Switch, Table, Tag, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  createDeveloper, deleteDeveloper, getDeveloper, listDeveloperApps, listDevelopers, updateDeveloper,
} from '../../api/endpoints';
import type { Developer, DeveloperApp } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { dateTime, fromNow } from '../../components/format';
import { fromPairs, KeyValueList, toPairs, type Pairs } from '../../components/KeyValueList';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';
import { CreateAppModal } from '../apps/CreateAppModal';

export function DeveloperList() {
  const { org, can } = useWorkspace();
  const navigate = useNavigate();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const developers = useQuery({ queryKey: ['developers', org], queryFn: () => listDevelopers(org), enabled: can('DEVELOPER_READ') });
  const [creating, setCreating] = useState(false);
  const [q, setQ] = useState('');
  const create = useMutation({
    mutationFn: (v: { email: string; firstName: string; lastName: string; userName?: string }) => createDeveloper(org, v),
    onSuccess: (d) => {
      void queryClient.invalidateQueries({ queryKey: ['developers', org] });
      setCreating(false);
      navigate(`/o/${org}/developers/${encodeURIComponent(d.email)}`);
    },
    onError: (e) => notify.error(e, 'Couldn’t register the developer'),
  });
  const rows = useMemo(() => {
    const term = q.trim().toLowerCase();
    return (developers.data?.items ?? []).filter((d) => !term || d.email.includes(term)
      || `${d.firstName} ${d.lastName}`.toLowerCase().includes(term));
  }, [developers.data, q]);

  if (!can('DEVELOPER_READ')) return <NoAccess what="reading developers" />;
  return (
    <Page
      title="Developers"
      subtitle="The people and teams who consume your APIs. Each developer owns apps and their keys."
      extra={can('DEVELOPER_WRITE') && <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>Register developer</Button>}
    >
      <Input allowClear prefix={<SearchOutlined />} placeholder="Search by name or email" value={q} onChange={(e) => setQ(e.target.value)}
        style={{ maxWidth: 320, marginBottom: 16 }} />
      <Loadable loading={developers.isLoading} error={developers.error}>
        <Table<Developer>
          rowKey="id"
          dataSource={rows}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: 'No developers yet.' }}
          columns={[
            {
              title: 'Developer', key: 'name', sorter: (a, b) => a.email.localeCompare(b.email),
              render: (_, d) => (
                <div>
                  <Link to={`/o/${org}/developers/${encodeURIComponent(d.email)}`}><b>{d.firstName} {d.lastName}</b></Link>
                  <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{d.email}</Typography.Text></div>
                </div>
              ),
            },
            { title: 'Status', dataIndex: 'status', render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s.toLowerCase()}</Tag> },
            { title: 'Registered', dataIndex: 'createdAt', render: (v: string) => fromNow(v) },
          ]}
        />
      </Loadable>
      <Modal open={creating} title="Register a developer" onCancel={() => setCreating(false)} footer={null} destroyOnClose>
        <Form layout="vertical" onFinish={(v) => create.mutate(v)}>
          <Form.Item name="email" label="Email" rules={[{ required: true, type: 'email' }]}><Input autoComplete="off" /></Form.Item>
          <Row gutter={12}>
            <Col span={12}><Form.Item name="firstName" label="First name" rules={[{ required: true }]}><Input /></Form.Item></Col>
            <Col span={12}><Form.Item name="lastName" label="Last name" rules={[{ required: true }]}><Input /></Form.Item></Col>
          </Row>
          <Form.Item name="userName" label="User name (optional)"><Input /></Form.Item>
          <Button type="primary" htmlType="submit" loading={create.isPending}>Register</Button>
        </Form>
      </Modal>
    </Page>
  );
}

export function DeveloperDetail() {
  const { org, can } = useWorkspace();
  const { email = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const developer = useQuery({ queryKey: ['developer', org, email], queryFn: () => getDeveloper(org, email) });
  const apps = useQuery({ queryKey: ['developer-apps', org, email], queryFn: () => listDeveloperApps(org, email) });
  const [editing, setEditing] = useState(false);
  const [creatingApp, setCreatingApp] = useState(false);
  const writable = can('DEVELOPER_WRITE');

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['developer', org, email] });
    void queryClient.invalidateQueries({ queryKey: ['developers', org] });
  };
  const update = useMutation({
    mutationFn: (body: Parameters<typeof updateDeveloper>[2]) => updateDeveloper(org, email, body),
    onSuccess: () => {
      invalidate();
      setEditing(false);
    },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: () => deleteDeveloper(org, email),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['developers', org] });
      void queryClient.invalidateQueries({ queryKey: ['apps', org] });
      navigate(`/o/${org}/developers`);
    },
    onError: (e) => notify.error(e),
  });
  const d = developer.data;

  return (
    <Page
      title={d ? `${d.firstName} ${d.lastName}` : email}
      subtitle={email}
      crumbs={[{ title: 'Developers', to: `/o/${org}/developers` }, { title: email }]}
      extra={writable && d && (
        <>
          <Space>
            <Typography.Text>Active</Typography.Text>
            <Switch
              checked={d.status === 'ACTIVE'}
              loading={update.isPending}
              onChange={(on) => update.mutate({ status: on ? 'ACTIVE' : 'INACTIVE' })}
              aria-label="Developer active"
            />
          </Space>
          <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>Edit</Button>
          <Popconfirm title="Delete this developer?" description="Their apps and keys stop working and are deleted."
            okButtonProps={{ danger: true }} onConfirm={() => remove.mutate()}>
            <Button danger icon={<DeleteOutlined />}>Delete</Button>
          </Popconfirm>
        </>
      )}
    >
      <Loadable loading={developer.isLoading} error={developer.error}>
        {d && (
          <Row gutter={[16, 16]}>
            <Col xs={24} lg={9}>
              <Card title="Profile">
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="Email">{d.email}</Descriptions.Item>
                  <Descriptions.Item label="User name">{d.userName || '–'}</Descriptions.Item>
                  <Descriptions.Item label="Status"><Tag color={d.status === 'ACTIVE' ? 'green' : 'default'}>{d.status.toLowerCase()}</Tag></Descriptions.Item>
                  <Descriptions.Item label="Registered">{dateTime(d.createdAt)}</Descriptions.Item>
                  {Object.entries(d.attributes).map(([k, v]) => <Descriptions.Item key={k} label={k}>{v}</Descriptions.Item>)}
                </Descriptions>
              </Card>
            </Col>
            <Col xs={24} lg={15}>
              <Card title="Apps" extra={writable && <Button icon={<PlusOutlined />} onClick={() => setCreatingApp(true)}>New app</Button>}>
                <Table<DeveloperApp>
                  rowKey="id"
                  size="small"
                  loading={apps.isLoading}
                  dataSource={apps.data?.items ?? []}
                  pagination={false}
                  locale={{ emptyText: 'No apps yet.' }}
                  columns={[
                    { title: 'App', dataIndex: 'name', render: (_, a) => <Link to={`/o/${org}/apps/${encodeURIComponent(a.developerEmail)}/${a.name}`}><b>{a.displayName}</b></Link> },
                    { title: 'Status', dataIndex: 'status', render: (s: string) => <Tag color={s === 'APPROVED' ? 'green' : 'red'}>{s.toLowerCase()}</Tag> },
                    { title: 'Keys', key: 'keys', render: (_, a) => a.credentials.filter((c) => c.status === 'APPROVED' && !c.expired).length },
                    { title: 'Products', key: 'products', render: (_, a) => [...new Set(a.credentials.flatMap((c) => c.apiProducts.map((g) => g.apiProduct)))].map((p) => <Tag key={p}>{p}</Tag>) },
                  ]}
                />
              </Card>
            </Col>
          </Row>
        )}
      </Loadable>
      {d && (
        <Modal open={editing} title="Edit developer" onCancel={() => setEditing(false)} footer={null} destroyOnClose>
          <Form<{ firstName: string; lastName: string; userName?: string; attributes: Pairs }>
            layout="vertical"
            initialValues={{ firstName: d.firstName, lastName: d.lastName, userName: d.userName, attributes: toPairs(d.attributes) }}
            onFinish={(v) => update.mutate({ firstName: v.firstName, lastName: v.lastName, userName: v.userName ?? '', attributes: fromPairs(v.attributes) })}
          >
            <Row gutter={12}>
              <Col span={12}><Form.Item name="firstName" label="First name" rules={[{ required: true }]}><Input /></Form.Item></Col>
              <Col span={12}><Form.Item name="lastName" label="Last name" rules={[{ required: true }]}><Input /></Form.Item></Col>
            </Row>
            <Form.Item name="userName" label="User name"><Input /></Form.Item>
            <Form.Item name="attributes" label="Attributes"><KeyValueList /></Form.Item>
            <Button type="primary" htmlType="submit" loading={update.isPending}>Save</Button>
          </Form>
        </Modal>
      )}
      <CreateAppModal open={creatingApp} developerEmail={email} onClose={() => setCreatingApp(false)} />
    </Page>
  );
}
