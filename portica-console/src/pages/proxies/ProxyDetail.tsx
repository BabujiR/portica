import { useEffect, useMemo, useState } from 'react';
import {
  Alert, App, Button, Card, Col, Descriptions, Empty, Flex, Form, Input, InputNumber, Modal, Popconfirm, Radio, Row, Select,
  Space, Table, Tabs, Tag, Typography,
} from 'antd';
import { DeleteOutlined, EditOutlined, SaveOutlined, UndoOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError, errorMessage } from '../../api/client';
import {
  createRevision, deleteProxy, deleteRevision, deployRevision, getOpenApiSpec, getOperations, getProxy, getRevision,
  listProxyDeployments, listRevisions, listTargetServers, undeployRevision, updateProxy,
} from '../../api/endpoints';
import type { Deployment, Revision, TargetSpec } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { config } from '../../config';
import { dateTime, fromNow } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';
import { useUnsavedChanges } from '../../components/useUnsavedChanges';
import { FlowEditor } from '../flow/FlowEditor';
import { cleanFlows, normalizeFlows, type Bundle } from '../flow/model';
import { TracePanel } from '../trace/TracePanel';
import { DeployControl } from './DeployControl';

export function ProxyDetail() {
  const { org, can } = useWorkspace();
  const { api = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'overview';
  const proxy = useQuery({ queryKey: ['proxy', org, api], queryFn: () => getProxy(org, api) });
  const deployments = useQuery({ queryKey: ['proxy-deployments', org, api], queryFn: () => listProxyDeployments(org, api) });

  if (!can('PROXY_READ')) return <NoAccess what="reading API proxies" />;

  return (
    <Page
      title={proxy.data?.displayName ?? api}
      subtitle={proxy.data && <>{proxy.data.name} · latest revision {proxy.data.latestRevision}</>}
      crumbs={[{ title: 'API Proxies', to: `/o/${org}/proxies` }, { title: api }]}
      extra={proxy.data && <DeployControl api={api} revision={proxy.data.latestRevision} />}
    >
      <Loadable loading={proxy.isLoading} error={proxy.error}>
        {proxy.data && (
          <Tabs
            activeKey={tab}
            onChange={(key) => setParams((p) => { p.set('tab', key); return p; })}
            items={[
              { key: 'overview', label: 'Overview', children: <Overview api={api} deployments={deployments.data ?? []} /> },
              { key: 'develop', label: 'Develop', children: <Develop api={api} latest={proxy.data.latestRevision} /> },
              { key: 'revisions', label: 'Revisions', children: <Revisions api={api} deployments={deployments.data ?? []} /> },
              { key: 'deployments', label: 'Deployments', children: <Deployments api={api} deployments={deployments.data ?? []} loading={deployments.isLoading} /> },
              ...(can('TRACE_RUN')
                ? [{ key: 'trace', label: 'Trace', children: <TracePanel api={api} deployments={deployments.data ?? []} /> }]
                : []),
            ]}
          />
        )}
      </Loadable>
    </Page>
  );
}

// ------------------------------------------------------------------ overview

function Overview({ api, deployments }: { api: string; deployments: Deployment[] }) {
  const { org, can } = useWorkspace();
  const navigate = useNavigate();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const proxy = useQuery({ queryKey: ['proxy', org, api], queryFn: () => getProxy(org, api) });
  const latest = useQuery({
    queryKey: ['revision', org, api, proxy.data?.latestRevision],
    queryFn: () => getRevision(org, api, proxy.data?.latestRevision as number),
    enabled: !!proxy.data,
  });
  const operations = useQuery({
    queryKey: ['operations', org, api, proxy.data?.latestRevision],
    queryFn: () => getOperations(org, api, proxy.data?.latestRevision as number),
    enabled: !!latest.data?.hasOpenApiSpec,
  });
  const [editing, setEditing] = useState(false);
  const update = useMutation({
    mutationFn: (v: { displayName?: string; description?: string }) => updateProxy(org, api, v),
    onSuccess: () => {
      setEditing(false);
      void queryClient.invalidateQueries({ queryKey: ['proxy', org, api] });
      void queryClient.invalidateQueries({ queryKey: ['proxies', org] });
    },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: () => deleteProxy(org, api),
    onSuccess: () => {
      notify.success(`Deleted ${api}`);
      void queryClient.invalidateQueries({ queryKey: ['proxies', org] });
      navigate(`/o/${org}/proxies`);
    },
    onError: (e) => notify.error(e, 'Couldn’t delete the proxy'),
  });
  const p = proxy.data;
  if (!p) return null;

  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} xl={14}>
        <Card title="Details" extra={can('PROXY_WRITE') && <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>Edit</Button>}>
          <Descriptions column={1} size="small">
            <Descriptions.Item label="Name">{p.name}</Descriptions.Item>
            <Descriptions.Item label="Display name">{p.displayName}</Descriptions.Item>
            <Descriptions.Item label="Description">{p.description || '–'}</Descriptions.Item>
            <Descriptions.Item label="Base path">{latest.data?.basePath}</Descriptions.Item>
            <Descriptions.Item label="Target">{targetLabel(latest.data?.target)}</Descriptions.Item>
            <Descriptions.Item label="Policies (latest)">
              {latest.data?.policies.length ? latest.data.policies.map((x) => <Tag key={x.name}>{x.type}</Tag>) : 'None'}
            </Descriptions.Item>
            <Descriptions.Item label="Created">{dateTime(p.createdAt)} by {p.createdBy ?? '–'}</Descriptions.Item>
          </Descriptions>
        </Card>
        {latest.data?.hasOpenApiSpec && (
          <Card title="Operations (OpenAPI)" style={{ marginTop: 16 }} loading={operations.isLoading}>
            <Table
              size="small"
              rowKey={(o) => `${o.method} ${o.path}`}
              dataSource={operations.data?.operations ?? []}
              pagination={false}
              columns={[
                { title: 'Method', dataIndex: 'method', width: 100, render: (m: string) => <Tag>{m}</Tag> },
                { title: 'Path', dataIndex: 'path', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
                { title: 'Summary', dataIndex: 'summary' },
              ]}
            />
          </Card>
        )}
      </Col>
      <Col xs={24} xl={10}>
        <Card title="Live in">
          {deployments.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Not deployed anywhere yet." />
          ) : (
            <Space direction="vertical" style={{ width: '100%' }}>
              {deployments.map((d) => (
                <div key={d.environment}>
                  <Space><Tag color="green">{d.environment}</Tag> revision {d.revision}</Space>
                  <div><Typography.Text code copyable>{`${config.gatewayUrl}${d.gatewayPath}`}</Typography.Text></div>
                </div>
              ))}
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 8 }}>
                Try it: <Typography.Text code copyable>{`curl -i ${config.gatewayUrl}${deployments[0].gatewayPath}/`}</Typography.Text>
              </Typography.Paragraph>
            </Space>
          )}
        </Card>
        {can('PROXY_WRITE') && (
          <Card title="Danger zone" style={{ marginTop: 16 }}>
            <Popconfirm
              title={`Delete ${api}?`}
              description="All revisions are deleted. Undeploy it everywhere first."
              okButtonProps={{ danger: true }}
              onConfirm={() => remove.mutate()}
              disabled={deployments.length > 0}
            >
              <Button danger icon={<DeleteOutlined />} disabled={deployments.length > 0} loading={remove.isPending}>Delete proxy</Button>
            </Popconfirm>
            {deployments.length > 0 && <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>Undeploy it from every environment to delete it.</Typography.Paragraph>}
          </Card>
        )}
      </Col>
      <Modal open={editing} title="Edit proxy" onCancel={() => setEditing(false)} footer={null} destroyOnClose>
        <Form layout="vertical" initialValues={{ displayName: p.displayName, description: p.description }} onFinish={(v) => update.mutate(v)}>
          <Form.Item name="displayName" label="Display name" rules={[{ required: true, max: 128 }]}><Input /></Form.Item>
          <Form.Item name="description" label="Description" rules={[{ max: 1024 }]}><Input.TextArea autoSize={{ minRows: 3 }} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={update.isPending}>Save</Button>
        </Form>
      </Modal>
    </Row>
  );
}

function targetLabel(t?: TargetSpec): string {
  if (!t) return '–';
  const base = t.server ? `target server ${t.server}` : (t.url ?? '');
  return `${base}${t.path && t.path !== '/' ? t.path : ''}`;
}

// ------------------------------------------------------------------ develop (flow editor)

interface Settings {
  basePath: string;
  targetType: 'url' | 'server';
  url?: string;
  server?: string;
  path?: string;
  connectTimeoutMs: number;
  readTimeoutMs: number;
}

function settingsOf(r: Revision): Settings {
  return {
    basePath: r.basePath,
    targetType: r.target.server ? 'server' : 'url',
    url: r.target.url ?? undefined,
    server: r.target.server ?? undefined,
    path: r.target.path && r.target.path !== '/' ? r.target.path : undefined,
    connectTimeoutMs: r.connectTimeoutMs,
    readTimeoutMs: r.readTimeoutMs,
  };
}

function Develop({ api, latest }: { api: string; latest: number }) {
  const { org, env, can } = useWorkspace();
  const notify = useNotify();
  const { modal } = App.useApp();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const rev = Number(params.get('rev') ?? latest) || latest;
  const revisions = useQuery({ queryKey: ['revisions', org, api], queryFn: () => listRevisions(org, api) });
  const revision = useQuery({ queryKey: ['revision', org, api, rev], queryFn: () => getRevision(org, api, rev) });
  const servers = useQuery({ queryKey: ['target-servers', org, env], queryFn: () => listTargetServers(org, env as string), enabled: !!env });

  const [bundle, setBundle] = useState<Bundle | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saveOpen, setSaveOpen] = useState(false);
  const [problems, setProblems] = useState<string[]>([]);
  const readOnly = !can('PROXY_WRITE');
  useUnsavedChanges(dirty);

  const reset = () => {
    if (!revision.data) return;
    setBundle({ policies: revision.data.policies ?? [], flows: normalizeFlows(revision.data.flows), steps: [] });
    setSettings(settingsOf(revision.data));
    setDirty(false);
    setProblems([]);
  };
  useEffect(reset, [revision.data]);

  const save = useMutation({
    mutationFn: async ({ description, deployTo }: { description?: string; deployTo?: string }) => {
      if (!bundle || !settings || !revision.data) throw new Error('Nothing to save');
      const openApiSpec = revision.data.hasOpenApiSpec ? await getOpenApiSpec(org, api, rev) : undefined;
      const target: TargetSpec = settings.targetType === 'server'
        ? { server: settings.server, path: settings.path || undefined }
        : { url: settings.url, path: settings.path || undefined };
      const created = await createRevision(org, api, {
        basePath: settings.basePath,
        target,
        connectTimeoutMs: settings.connectTimeoutMs,
        readTimeoutMs: settings.readTimeoutMs,
        description,
        openApiSpec,
        policies: bundle.policies,
        flows: cleanFlows(bundle.flows),
      });
      if (deployTo) await deployRevision(org, deployTo, api, created.revision);
      return { created, deployTo };
    },
    onSuccess: ({ created, deployTo }) => {
      setDirty(false);
      setSaveOpen(false);
      setProblems([]);
      notify.success(deployTo ? `Saved revision ${created.revision} and deployed it to ${deployTo}` : `Saved revision ${created.revision}`);
      void queryClient.invalidateQueries({ queryKey: ['proxy', org, api] });
      void queryClient.invalidateQueries({ queryKey: ['revisions', org, api] });
      void queryClient.invalidateQueries({ queryKey: ['proxy-deployments', org, api] });
      setParams((p) => { p.set('rev', String(created.revision)); return p; });
    },
    onError: (e) => {
      setSaveOpen(false);
      if (e instanceof ApiError && e.details.length) setProblems(e.details);
      else setProblems([errorMessage(e)]);
    },
  });

  const change = (b: Bundle) => {
    setBundle(b);
    setDirty(true);
  };
  const changeSettings = (s: Partial<Settings>) => {
    setSettings((cur) => (cur ? { ...cur, ...s } : cur));
    setDirty(true);
  };

  const revisionOptions = useMemo(() => (revisions.data?.items ?? [])
    .map((r) => ({ value: r.revision, label: `Revision ${r.revision}${r.description ? ` – ${r.description}` : ''}` })), [revisions.data]);

  return (
    <Loadable loading={revision.isLoading || !bundle || !settings} error={revision.error}>
      {bundle && settings && (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Flex justify="space-between" align="center" wrap gap={8}>
            <Space wrap>
              <Select
                aria-label="Revision"
                value={rev}
                style={{ minWidth: 260 }}
                options={revisionOptions}
                onChange={(value: number) => {
                  const go = () => setParams((p) => { p.set('rev', String(value)); return p; });
                  if (!dirty) return go();
                  modal.confirm({ title: 'Discard unsaved changes?', okText: 'Discard', okButtonProps: { danger: true }, onOk: go });
                }}
              />
              {dirty && <Tag color="orange">Unsaved changes</Tag>}
              {readOnly && <Tag>Read only</Tag>}
            </Space>
            {!readOnly && (
              <Space>
                <Button icon={<UndoOutlined />} disabled={!dirty} onClick={reset}>
                  Discard
                </Button>
                <Button type="primary" icon={<SaveOutlined />} disabled={!dirty} onClick={() => setSaveOpen(true)}>
                  Save as new revision
                </Button>
              </Space>
            )}
          </Flex>

          {problems.length > 0 && (
            <Alert
              type="error"
              showIcon
              closable
              onClose={() => setProblems([])}
              message="The revision wasn't saved"
              description={<ul style={{ margin: 0, paddingInlineStart: 18 }}>{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
            />
          )}

          <Card size="small" title="Base path and target">
            <Flex gap={12} wrap align="end">
              <label>
                <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>Base path</Typography.Text>
                <Input value={settings.basePath} disabled={readOnly} style={{ width: 180 }} onChange={(e) => changeSettings({ basePath: e.target.value })} />
              </label>
              <Radio.Group
                value={settings.targetType}
                disabled={readOnly}
                optionType="button"
                options={[{ value: 'url', label: 'URL' }, { value: 'server', label: 'Target server' }]}
                onChange={(e) => changeSettings({ targetType: e.target.value as 'url' | 'server' })}
              />
              {settings.targetType === 'url' ? (
                <Input aria-label="Target URL" value={settings.url} disabled={readOnly} style={{ width: 300 }} placeholder="https://backend.example.com"
                  onChange={(e) => changeSettings({ url: e.target.value })} />
              ) : (
                <Select aria-label="Target server" value={settings.server} disabled={readOnly} style={{ width: 220 }}
                  options={(servers.data?.items ?? []).map((s) => ({ value: s.name, label: s.name }))}
                  onChange={(v: string) => changeSettings({ server: v })} />
              )}
              <Input aria-label="Target path" value={settings.path} disabled={readOnly} style={{ width: 140 }} placeholder="/path prefix"
                onChange={(e) => changeSettings({ path: e.target.value })} />
              <Space.Compact>
                <InputNumber aria-label="Connect timeout" addonBefore="connect" value={settings.connectTimeoutMs} disabled={readOnly} min={100} max={60000}
                  onChange={(v) => changeSettings({ connectTimeoutMs: v ?? 5000 })} />
                <InputNumber aria-label="Read timeout" addonBefore="read" addonAfter="ms" value={settings.readTimeoutMs} disabled={readOnly} min={100} max={300000}
                  onChange={(v) => changeSettings({ readTimeoutMs: v ?? 30000 })} />
              </Space.Compact>
            </Flex>
          </Card>

          <FlowEditor mode="proxy" value={bundle} onChange={change} readOnly={readOnly} />

          <SaveRevisionModal
            open={saveOpen}
            saving={save.isPending}
            onCancel={() => setSaveOpen(false)}
            onSave={(description, deployTo) => save.mutate({ description, deployTo })}
          />
        </Space>
      )}
    </Loadable>
  );
}

function SaveRevisionModal({ open, saving, onCancel, onSave }: {
  open: boolean;
  saving: boolean;
  onCancel: () => void;
  onSave: (description?: string, deployTo?: string) => void;
}) {
  const { environments, can } = useWorkspace();
  const [form] = Form.useForm<{ description?: string; deployTo?: string }>();
  return (
    <Modal
      open={open}
      title="Save as a new revision"
      okText="Save"
      confirmLoading={saving}
      onCancel={onCancel}
      onOk={() => form.submit()}
      destroyOnClose
    >
      <Typography.Paragraph type="secondary">
        Revisions are immutable: saving creates the next revision. Deployed revisions keep running until you deploy the new one.
      </Typography.Paragraph>
      <Form form={form} layout="vertical" preserve={false} onFinish={(v) => onSave(v.description || undefined, v.deployTo || undefined)}>
        <Form.Item name="description" label="What changed?" rules={[{ max: 1024 }]}>
          <Input placeholder="e.g. Add quota and CORS" />
        </Form.Item>
        {can('PROXY_DEPLOY') && (
          <Form.Item name="deployTo" label="Then deploy to">
            <Select allowClear placeholder="Don't deploy" options={environments.map((e) => ({ value: e.name, label: e.name }))} />
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
}

// ------------------------------------------------------------------ revisions

function Revisions({ api, deployments }: { api: string; deployments: Deployment[] }) {
  const { org, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const [, setParams] = useSearchParams();
  const revisions = useQuery({ queryKey: ['revisions', org, api], queryFn: () => listRevisions(org, api) });
  const remove = useMutation({
    mutationFn: (rev: number) => deleteRevision(org, api, rev),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['revisions', org, api] });
      void queryClient.invalidateQueries({ queryKey: ['proxy', org, api] });
    },
    onError: (e) => notify.error(e, 'Couldn’t delete the revision'),
  });
  const liveIn = (rev: number) => deployments.filter((d) => d.revision === rev).map((d) => d.environment);

  return (
    <Loadable loading={revisions.isLoading} error={revisions.error}>
      <Table<Revision>
        rowKey="revision"
        dataSource={revisions.data?.items ?? []}
        pagination={{ pageSize: 20, hideOnSinglePage: true }}
        columns={[
          { title: 'Revision', dataIndex: 'revision', width: 100, render: (r: number) => <b>r{r}</b> },
          { title: 'Description', dataIndex: 'description', render: (d?: string) => d || <Typography.Text type="secondary">–</Typography.Text> },
          { title: 'Base path', dataIndex: 'basePath', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
          { title: 'Policies', key: 'policies', render: (_, r) => r.policies.length },
          { title: 'Live in', key: 'live', render: (_, r) => liveIn(r.revision).map((e) => <Tag color="green" key={e}>{e}</Tag>) },
          { title: 'Created', key: 'created', render: (_, r) => <span title={dateTime(r.createdAt)}>{fromNow(r.createdAt)} · {r.createdBy ?? '–'}</span> },
          {
            title: '',
            key: 'actions',
            align: 'right',
            render: (_, r) => (
              <Space>
                <Button size="small" onClick={() => setParams((p) => { p.set('tab', 'develop'); p.set('rev', String(r.revision)); return p; })}>
                  Open
                </Button>
                <DeployControl api={api} revision={r.revision} size="small" />
                {can('PROXY_WRITE') && (
                  <Popconfirm title={`Delete revision ${r.revision}?`} onConfirm={() => remove.mutate(r.revision)}
                    disabled={liveIn(r.revision).length > 0}>
                    <Button size="small" danger icon={<DeleteOutlined />} disabled={liveIn(r.revision).length > 0} aria-label="Delete revision" />
                  </Popconfirm>
                )}
              </Space>
            ),
          },
        ]}
      />
    </Loadable>
  );
}

// ------------------------------------------------------------------ deployments

function Deployments({ api, deployments, loading }: { api: string; deployments: Deployment[]; loading: boolean }) {
  const { org, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const undeploy = useMutation({
    mutationFn: (d: Deployment) => undeployRevision(org, d.environment, api, d.revision),
    onSuccess: (_, d) => {
      notify.success(`Undeployed from ${d.environment}`);
      void queryClient.invalidateQueries({ queryKey: ['proxy-deployments', org, api] });
      void queryClient.invalidateQueries({ queryKey: ['deployments', org] });
    },
    onError: (e) => notify.error(e, 'Couldn’t undeploy'),
  });
  return (
    <Table<Deployment>
      rowKey="environment"
      loading={loading}
      dataSource={deployments}
      pagination={false}
      locale={{ emptyText: 'Not deployed. Use “Deploy” at the top of the page or on a revision.' }}
      columns={[
        { title: 'Environment', dataIndex: 'environment', render: (e: string) => <Link to={`/o/${org}/environments`}><Tag color="green">{e}</Tag></Link> },
        { title: 'Revision', dataIndex: 'revision', render: (r: number) => `r${r}` },
        { title: 'URL', key: 'url', render: (_, d) => <Typography.Text code copyable>{`${config.gatewayUrl}${d.gatewayPath}`}</Typography.Text> },
        { title: 'Deployed', key: 'at', render: (_, d) => <span title={dateTime(d.deployedAt)}>{fromNow(d.deployedAt)} · {d.deployedBy ?? '–'}</span> },
        {
          title: '',
          key: 'actions',
          align: 'right',
          render: (_, d) => can('PROXY_DEPLOY') && (
            <Popconfirm title={`Undeploy from ${d.environment}?`} description="Requests to it will get 404 within a few seconds."
              okButtonProps={{ danger: true }} onConfirm={() => undeploy.mutate(d)}>
              <Button size="small" danger>Undeploy</Button>
            </Popconfirm>
          ),
        },
      ]}
    />
  );
}
