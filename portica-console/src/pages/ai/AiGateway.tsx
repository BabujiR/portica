import { useMemo, useState } from 'react';
import {
  Alert, Button, Card, Checkbox, Col, Collapse, Drawer, Empty, Flex, Form, Input, InputNumber, Modal, Popconfirm, Radio,
  Row, Segmented, Select, Space, Statistic, Switch, Table, Tabs, Tag, Typography,
} from 'antd';
import { DeleteOutlined, EditOutlined, MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { errorMessage } from '../../api/client';
import {
  createAiProvider, createAiProxy, deleteAiProvider, deleteAiProxy, getAiSummary, getAiTimeseries, getAiTop,
  listAiProviders, listAiProxies, updateAiProvider, updateAiProxy, type AiFilter,
} from '../../api/endpoints';
import type {
  AiProvider, AiProviderBody, AiProxy, AiProxyBody, AiTimeseries, AiTop, PiiType, TokenQuota,
} from '../../api/types';
import { config } from '../../config';
import { useThemeMode, type ThemeMode } from '../../app/ThemeProvider';
import { useWorkspace } from '../../app/Workspace';
import { EChart, axisStyle, baseOption, chartPalette, type ChartOption } from '../../components/EChart';
import { fromNow, RESOURCE_NAME_RULE } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';
import { RANGES, bucketLabel, formatCount, formatMs, rangeWindow, type RangeKey } from '../analytics/analyticsModel';
import {
  curlExample, describeQuota, formatUsd, fromRouteForms, sdkExample, targetOptions, toRouteForms, type RouteForm,
} from './aiModel';

const PII_TYPES: PiiType[] = ['EMAIL', 'PHONE', 'CREDIT_CARD', 'SSN', 'IP_ADDRESS', 'IBAN'];

/** LLM providers, AI proxies (one OpenAI-compatible endpoint each) and their usage and cost. */
export function AiGateway() {
  const { can } = useWorkspace();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'proxies';
  if (!can('AI_READ')) return <NoAccess what="the AI gateway" />;
  return (
    <Page title="AI Gateway" subtitle="One OpenAI-compatible endpoint per AI proxy, with model routing and fallback, token quotas, cost tracking and PII redaction">
      <Tabs
        activeKey={tab}
        onChange={(key) => setParams({ tab: key }, { replace: true })}
        items={[
          { key: 'proxies', label: 'AI proxies', children: <AiProxies /> },
          { key: 'providers', label: 'Providers', children: <Providers /> },
          ...(can('ANALYTICS_READ') ? [{ key: 'usage', label: 'Usage & cost', children: <Usage /> }] : []),
        ]}
      />
    </Page>
  );
}

// ------------------------------------------------------------------ providers

function Providers() {
  const { org, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const writable = can('AI_WRITE');
  const providers = useQuery({ queryKey: ['ai-providers', org], queryFn: () => listAiProviders(org) });
  const [editing, setEditing] = useState<AiProvider | 'new' | null>(null);
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['ai-providers', org] });
  const save = useMutation({
    mutationFn: (v: AiProviderBody) => (editing === 'new'
      ? createAiProvider(org, v)
      : updateAiProvider(org, (editing as AiProvider).name, { ...v, name: undefined, type: undefined })),
    onSuccess: () => { invalidate(); setEditing(null); },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: (name: string) => deleteAiProvider(org, name),
    onSuccess: invalidate,
    onError: (e) => notify.error(e, 'Couldn’t delete the provider'),
  });

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">
          Where AI proxies send calls. API keys are encrypted and never shown again; list prices drive cost tracking.
        </Typography.Text>
        {writable && <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>New provider</Button>}
      </Flex>
      <Loadable loading={providers.isLoading} error={providers.error}>
        <Table<AiProvider>
          rowKey="id"
          pagination={false}
          dataSource={providers.data ?? []}
          locale={{ emptyText: 'No providers yet. Add OpenAI, Anthropic or any OpenAI-compatible server.' }}
          columns={[
            { title: 'Name', key: 'name', render: (_, p) => <><b>{p.name}</b>{p.displayName !== p.name && <Typography.Text type="secondary"> {p.displayName}</Typography.Text>}</> },
            { title: 'Type', dataIndex: 'type', render: (t: string) => <Tag color={t === 'ANTHROPIC' ? 'orange' : 'green'}>{t === 'ANTHROPIC' ? 'Anthropic' : 'OpenAI API'}</Tag> },
            { title: 'Base URL', dataIndex: 'baseUrl', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            {
              title: 'Models (input / output $ per 1M tokens)', key: 'models',
              render: (_, p) => <Space size={[4, 4]} wrap>{p.models.map((m) => <Tag key={m.name}>{m.name} · {m.inputCostPerMillion} / {m.outputCostPerMillion}</Tag>)}</Space>,
            },
            { title: 'API key', key: 'key', render: (_, p) => (p.apiKeySet ? <Tag color="blue">set {p.apiKeyHint ?? ''}</Tag> : <Tag>none</Tag>) },
            { title: 'Status', dataIndex: 'enabled', render: (v: boolean) => (v ? <Tag color="green">enabled</Tag> : <Tag>disabled</Tag>) },
            {
              title: '', key: 'actions', align: 'right', render: (_, p) => writable && (
                <Space>
                  <Button size="small" icon={<EditOutlined />} aria-label={`Edit ${p.name}`} onClick={() => setEditing(p)} />
                  <Popconfirm title={`Delete ${p.name}?`} description="Refused while AI proxies route to it." okButtonProps={{ danger: true }}
                    onConfirm={() => remove.mutate(p.name)}>
                    <Button size="small" danger icon={<DeleteOutlined />} aria-label={`Delete ${p.name}`} />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Loadable>
      <Modal open={!!editing} width={720} footer={null} destroyOnClose onCancel={() => setEditing(null)}
        title={editing === 'new' ? 'New AI provider' : `Edit ${(editing as AiProvider | null)?.name ?? ''}`}>
        <Form<AiProviderBody>
          layout="vertical"
          onFinish={(v) => save.mutate({ ...v, apiKey: v.apiKey === '' ? undefined : v.apiKey })}
          initialValues={editing && editing !== 'new'
            ? { displayName: editing.displayName, baseUrl: editing.baseUrl, models: editing.models, enabled: editing.enabled }
            : { type: 'OPENAI', enabled: true, models: [{ name: 'gpt-4o-mini', inputCostPerMillion: 0.15, outputCostPerMillion: 0.6 }] }}
        >
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="name" label="Name" rules={editing === 'new' ? [{ required: true }, RESOURCE_NAME_RULE] : []}>
                <Input disabled={editing !== 'new'} placeholder={editing === 'new' ? 'openai' : (editing as AiProvider | null)?.name} />
              </Form.Item>
            </Col>
            <Col span={12}><Form.Item name="displayName" label="Display name"><Input /></Form.Item></Col>
          </Row>
          {editing === 'new' && (
            <Form.Item name="type" label="API">
              <Radio.Group optionType="button" options={[
                { value: 'OPENAI', label: 'OpenAI (and compatible)' },
                { value: 'ANTHROPIC', label: 'Anthropic Messages' },
              ]} />
            </Form.Item>
          )}
          <Form.Item name="baseUrl" label="Base URL" extra="Leave empty for the provider's public API (https://api.openai.com/v1, https://api.anthropic.com/v1).">
            <Input placeholder="https://api.openai.com/v1" />
          </Form.Item>
          <Form.Item name="apiKey" label="API key" extra={editing !== 'new' ? 'Leave empty to keep the stored key.' : 'Stored encrypted; never shown again.'}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Typography.Text strong>Models and list prices (US$ per million tokens)</Typography.Text>
          <Form.List name="models">
            {(fields, { add, remove: removeModel }) => (
              <div style={{ marginTop: 8 }}>
                {fields.map(({ key, name }) => (
                  <Flex key={key} gap={8} align="baseline">
                    <Form.Item name={[name, 'name']} rules={[{ required: true, message: 'Model name' }]} style={{ flex: 2 }}>
                      <Input placeholder="model name" />
                    </Form.Item>
                    <Form.Item name={[name, 'inputCostPerMillion']} rules={[{ required: true, message: 'Input price' }]} style={{ flex: 1 }}>
                      <InputNumber min={0} step={0.01} placeholder="input" style={{ width: '100%' }} />
                    </Form.Item>
                    <Form.Item name={[name, 'outputCostPerMillion']} rules={[{ required: true, message: 'Output price' }]} style={{ flex: 1 }}>
                      <InputNumber min={0} step={0.01} placeholder="output" style={{ width: '100%' }} />
                    </Form.Item>
                    <MinusCircleOutlined onClick={() => removeModel(name)} aria-label="Remove model" />
                  </Flex>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ inputCostPerMillion: 0, outputCostPerMillion: 0 })}>Add model</Button>
              </div>
            )}
          </Form.List>
          <Form.Item name="enabled" label="Enabled" valuePropName="checked" style={{ marginTop: 16 }}><Switch /></Form.Item>
          <Button type="primary" htmlType="submit" loading={save.isPending}>Save</Button>
        </Form>
      </Modal>
    </>
  );
}

// ------------------------------------------------------------------ AI proxies

interface ProxyForm extends Omit<AiProxyBody, 'routes' | 'tokenQuota'> {
  routes: RouteForm[];
  quotaEnabled?: boolean;
  tokenQuota?: TokenQuota;
}

function AiProxies() {
  const { org, env, environments, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const writable = can('AI_WRITE');
  const proxies = useQuery({ queryKey: ['ai-proxies', org], queryFn: () => listAiProxies(org) });
  const providers = useQuery({ queryKey: ['ai-providers', org], queryFn: () => listAiProviders(org) });
  const [editing, setEditing] = useState<AiProxy | 'new' | null>(null);
  const [form] = Form.useForm<ProxyForm>();
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['ai-proxies', org] });
  const save = useMutation({
    mutationFn: (v: ProxyForm) => {
      const { quotaEnabled: limited, routes: routeForms, tokenQuota, ...rest } = v;
      const body: AiProxyBody = { ...rest, routes: fromRouteForms(routeForms ?? []), tokenQuota: limited ? tokenQuota : null };
      return editing === 'new' ? createAiProxy(org, body) : updateAiProxy(org, (editing as AiProxy).name, { ...body, name: undefined });
    },
    onSuccess: () => { invalidate(); setEditing(null); },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: (name: string) => deleteAiProxy(org, name),
    onSuccess: invalidate,
    onError: (e) => notify.error(e, 'Couldn’t delete the AI proxy'),
  });
  const options = useMemo(() => targetOptions(providers.data ?? []), [providers.data]);
  const routes = Form.useWatch('routes', form) ?? [];
  const quotaEnabled = Form.useWatch('quotaEnabled', form);
  const piiAction = Form.useWatch(['pii', 'action'], form);

  const initial = (p: AiProxy | 'new'): ProxyForm => (p === 'new'
    ? {
      auth: 'API_KEY', routes: [{ name: 'default', targets: [] }], allowProviderModels: false, environments: [],
      pii: { action: 'OFF', types: [], scanResponses: false }, timeoutSeconds: 120, quotaEnabled: false,
      tokenQuota: { tokens: 100000, interval: 1, timeUnit: 'day' },
    }
    : {
      displayName: p.displayName, description: p.description ?? undefined, environments: p.environments, auth: p.auth,
      routes: toRouteForms(p.routes), defaultRoute: p.defaultRoute, allowProviderModels: p.allowProviderModels,
      quotaEnabled: !!p.tokenQuota, tokenQuota: p.tokenQuota ?? { tokens: 100000, interval: 1, timeUnit: 'day' },
      pii: p.pii, timeoutSeconds: p.timeoutSeconds, maxOutputTokens: p.maxOutputTokens ?? undefined,
    });

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">
          Apps call <Typography.Text code>{config.gatewayUrl}/{org}/{env ?? '{env}'}/ai/&lt;name&gt;/v1/chat/completions</Typography.Text>.
          Grant access by adding the gateway proxy (<Typography.Text code>ai-&lt;name&gt;</Typography.Text>) to an API product.
        </Typography.Text>
        {writable && (
          <Button type="primary" icon={<PlusOutlined />} disabled={!providers.data?.length}
            onClick={() => setEditing('new')}>
            New AI proxy
          </Button>
        )}
      </Flex>
      {providers.data && providers.data.length === 0 && (
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message="Add a provider first (Providers tab), then create an AI proxy that routes to its models." />
      )}
      <Loadable loading={proxies.isLoading} error={proxies.error}>
        <Table<AiProxy>
          rowKey="id"
          pagination={false}
          dataSource={proxies.data ?? []}
          locale={{ emptyText: 'No AI proxies yet.' }}
          expandable={{
            expandedRowRender: (p) => (
              <Row gutter={16}>
                <Col xs={24} lg={12}>
                  <Typography.Text strong>curl</Typography.Text>
                  <Typography.Paragraph copyable={{ text: curlExample(config.gatewayUrl, org, env ?? 'dev', p) }}>
                    <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{curlExample(config.gatewayUrl, org, env ?? 'dev', p)}</pre>
                  </Typography.Paragraph>
                </Col>
                <Col xs={24} lg={12}>
                  <Typography.Text strong>OpenAI SDK (JavaScript)</Typography.Text>
                  <Typography.Paragraph copyable={{ text: sdkExample(config.gatewayUrl, org, env ?? 'dev', p) }}>
                    <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{sdkExample(config.gatewayUrl, org, env ?? 'dev', p)}</pre>
                  </Typography.Paragraph>
                </Col>
              </Row>
            ),
          }}
          columns={[
            { title: 'Name', key: 'name', render: (_, p) => <><b>{p.name}</b><br /><Typography.Text type="secondary" style={{ fontSize: 12 }}>{p.gatewayProxy}</Typography.Text></> },
            {
              title: 'Routes (fallback order)', key: 'routes', render: (_, p) => (
                <Space direction="vertical" size={2}>
                  {p.routes.map((r) => (
                    <span key={r.name}>
                      <Tag color={r.name === p.defaultRoute ? 'blue' : undefined}>{r.name}</Tag>
                      <Typography.Text type="secondary">{r.targets.map((t) => `${t.provider}/${t.model}`).join(' → ')}</Typography.Text>
                    </span>
                  ))}
                </Space>
              ),
            },
            { title: 'Client auth', dataIndex: 'auth', render: (a: string) => (a === 'OAUTH' ? 'OAuth token' : 'API key') },
            { title: 'Token quota (per app)', key: 'quota', render: (_, p) => describeQuota(p.tokenQuota) },
            {
              title: 'PII', key: 'pii', render: (_, p) => (p.pii.action === 'OFF' ? <Tag>off</Tag>
                : <Tag color={p.pii.action === 'BLOCK' ? 'red' : 'gold'}>{p.pii.action.toLowerCase()}{p.pii.types.length ? ` · ${p.pii.types.length} types` : ''}</Tag>),
            },
            { title: 'Environments', key: 'envs', render: (_, p) => (p.environments.length ? p.environments.join(', ') : 'all') },
            { title: 'Updated', dataIndex: 'updatedAt', render: (v: string) => fromNow(v) },
            {
              title: '', key: 'actions', align: 'right', render: (_, p) => writable && (
                <Space>
                  <Button size="small" icon={<EditOutlined />} aria-label={`Edit ${p.name}`}
                    onClick={() => setEditing(p)} />
                  <Popconfirm title={`Delete ${p.name}?`} description="Apps calling it get 404 within seconds." okButtonProps={{ danger: true }}
                    onConfirm={() => remove.mutate(p.name)}>
                    <Button size="small" danger icon={<DeleteOutlined />} aria-label={`Delete ${p.name}`} />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Loadable>

      <Drawer open={!!editing} width={720} onClose={() => setEditing(null)} destroyOnClose
        title={editing === 'new' ? 'New AI proxy' : `Edit ${(editing as AiProxy | null)?.name ?? ''}`}
        extra={<Button type="primary" loading={save.isPending} onClick={() => form.submit()}>Save</Button>}>
        <Form<ProxyForm> form={form} layout="vertical" initialValues={editing ? initial(editing) : undefined} onFinish={(v) => save.mutate(v)}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="name" label="Name" rules={editing === 'new' ? [{ required: true }, RESOURCE_NAME_RULE] : []}
                extra="Part of the URL: /ai/<name>/v1">
                <Input disabled={editing !== 'new'} placeholder={editing === 'new' ? 'assistant' : (editing as AiProxy | null)?.name} />
              </Form.Item>
            </Col>
            <Col span={12}><Form.Item name="displayName" label="Display name"><Input /></Form.Item></Col>
          </Row>
          <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 1 }} /></Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="environments" label="Environments" extra="Empty: every environment">
                <Select mode="multiple" allowClear options={environments.map((e) => ({ value: e.name, label: e.name }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="auth" label="Clients authenticate with">
                <Radio.Group optionType="button" options={[{ value: 'API_KEY', label: 'App API key' }, { value: 'OAUTH', label: 'OAuth token' }]} />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title level={5}>Routes</Typography.Title>
          <Typography.Paragraph type="secondary">
            Clients name a route as <Typography.Text code>model</Typography.Text>. Targets are tried in order; rate limits,
            server errors and timeouts move the call to the next one.
          </Typography.Paragraph>
          <Form.List name="routes">
            {(fields, { add, remove: removeRoute }) => (
              <>
                {fields.map(({ key, name }) => (
                  <Flex key={key} gap={8} align="baseline">
                    <Form.Item name={[name, 'name']} rules={[{ required: true, message: 'Route name' }, { pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, message: 'Letters, digits, . _ -' }]} style={{ width: 160 }}>
                      <Input placeholder="fast" />
                    </Form.Item>
                    <Form.Item name={[name, 'targets']} rules={[{ required: true, type: 'array', min: 1, message: 'Pick at least one model' }]} style={{ flex: 1 }}>
                      <Select mode="multiple" placeholder="provider / model, in fallback order" options={options} />
                    </Form.Item>
                    {fields.length > 1 && <MinusCircleOutlined onClick={() => removeRoute(name)} aria-label="Remove route" />}
                  </Flex>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ name: '', targets: [] })} style={{ marginBottom: 16 }}>Add route</Button>
              </>
            )}
          </Form.List>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="defaultRoute" label="Default route" extra="Used when a request names no model">
                <Select allowClear options={(routes as RouteForm[]).filter((r) => r?.name).map((r) => ({ value: r.name, label: r.name }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="allowProviderModels" label="Also accept provider/model" valuePropName="checked"
                extra="e.g. openai/gpt-4o for any listed model of a provider the routes use">
                <Switch />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title level={5}>Token quota per app</Typography.Title>
          <Flex gap={8} align="baseline" wrap>
            <Form.Item name="quotaEnabled" valuePropName="checked"><Switch checkedChildren="on" unCheckedChildren="off" /></Form.Item>
            <Form.Item name={['tokenQuota', 'tokens']} rules={quotaEnabled ? [{ required: true }] : []}>
              <InputNumber min={1} disabled={!quotaEnabled} addonAfter="tokens" style={{ width: 200 }} />
            </Form.Item>
            <span>per</span>
            <Form.Item name={['tokenQuota', 'interval']}><InputNumber min={1} max={10000} disabled={!quotaEnabled} style={{ width: 80 }} /></Form.Item>
            <Form.Item name={['tokenQuota', 'timeUnit']}>
              <Select disabled={!quotaEnabled} style={{ width: 110 }} options={['minute', 'hour', 'day', 'week', 'month'].map((u) => ({ value: u, label: u }))} />
            </Form.Item>
          </Flex>

          <Typography.Title level={5}>Personal data in prompts</Typography.Title>
          <Form.Item name={['pii', 'action']}>
            <Radio.Group options={[
              { value: 'OFF', label: 'Allow' },
              { value: 'REDACT', label: 'Redact (replace with [EMAIL], …)' },
              { value: 'BLOCK', label: 'Block the call' },
            ]} />
          </Form.Item>
          {piiAction && piiAction !== 'OFF' && (
            <>
              <Form.Item name={['pii', 'types']} label="Detect" extra="None ticked: all types">
                <Checkbox.Group options={PII_TYPES.map((t) => ({ value: t, label: t.replace('_', ' ').toLowerCase() }))} />
              </Form.Item>
              {piiAction === 'REDACT' && (
                <Form.Item name={['pii', 'scanResponses']} valuePropName="checked" label="Also redact responses (non-streaming)">
                  <Switch />
                </Form.Item>
              )}
            </>
          )}
          <Collapse ghost items={[{
            key: 'limits', label: 'Limits',
            children: (
              <Row gutter={12}>
                <Col span={12}><Form.Item name="timeoutSeconds" label="Timeout per provider call (s)"><InputNumber min={5} max={600} style={{ width: '100%' }} /></Form.Item></Col>
                <Col span={12}><Form.Item name="maxOutputTokens" label="Max output tokens per call"><InputNumber min={1} style={{ width: '100%' }} /></Form.Item></Col>
              </Row>
            ),
          }]} />
        </Form>
      </Drawer>
    </>
  );
}

// ------------------------------------------------------------------ usage

function Usage() {
  const { org, environments } = useWorkspace();
  const { mode } = useThemeMode();
  const [range, setRange] = useState<RangeKey>('24h');
  const [environment, setEnvironment] = useState<string | undefined>();
  const [aiProxy, setAiProxy] = useState<string | undefined>();
  const proxies = useQuery({ queryKey: ['ai-proxies', org], queryFn: () => listAiProxies(org) });
  const filter = (): AiFilter => ({ ...rangeWindow(range), environment, aiProxy });
  const key = ['ai-usage', org, range, environment ?? '', aiProxy ?? ''];
  const common = { placeholderData: keepPreviousData, refetchInterval: 60_000 };
  const summary = useQuery({ queryKey: [...key, 'summary'], queryFn: () => getAiSummary(org, filter()), ...common });
  const series = useQuery({ queryKey: [...key, 'series'], queryFn: () => getAiTimeseries(org, filter()), ...common });
  const byModel = useQuery({ queryKey: [...key, 'model'], queryFn: () => getAiTop(org, 'model', filter()), ...common });
  const byApp = useQuery({ queryKey: [...key, 'app'], queryFn: () => getAiTop(org, 'app', filter()), ...common });
  const s = summary.data;
  const costChart = useMemo(() => series.data && costOption(series.data, mode), [series.data, mode]);
  const tokenChart = useMemo(() => series.data && tokensOption(series.data, mode), [series.data, mode]);

  return (
    <>
      <Flex gap={12} wrap style={{ marginBottom: 16 }}>
        <Segmented<RangeKey> value={range} onChange={setRange} options={RANGES.map((r) => ({ value: r.key, label: r.label }))} />
        <Select aria-label="Environment" allowClear placeholder="All environments" style={{ minWidth: 170 }} value={environment}
          onChange={setEnvironment} options={environments.map((e) => ({ value: e.name, label: e.name }))} />
        <Select aria-label="AI proxy" allowClear placeholder="All AI proxies" style={{ minWidth: 170 }} value={aiProxy}
          onChange={setAiProxy} options={(proxies.data ?? []).map((p) => ({ value: p.name, label: p.name }))} />
      </Flex>
      {summary.error && <Alert type="error" showIcon style={{ marginBottom: 16 }} message="Usage couldn't be loaded" description={errorMessage(summary.error)} />}
      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}><Card loading={summary.isLoading}><Statistic title="Cost" value={s ? formatUsd(s.costUsd) : '–'} />
          <Typography.Text type="secondary">at list prices</Typography.Text></Card></Col>
        <Col xs={12} lg={6}><Card loading={summary.isLoading}><Statistic title="Tokens" value={s ? formatCount(s.totalTokens) : '–'} />
          <Typography.Text type="secondary">{s ? `${formatCount(s.promptTokens)} in · ${formatCount(s.completionTokens)} out` : ' '}</Typography.Text></Card></Col>
        <Col xs={12} lg={6}><Card loading={summary.isLoading}><Statistic title="Calls" value={s ? formatCount(s.requests) : '–'} />
          <Typography.Text type="secondary">{s ? `${formatCount(s.errors)} failed · p95 ${formatMs(s.p95Ms)}` : ' '}</Typography.Text></Card></Col>
        <Col xs={12} lg={6}><Card loading={summary.isLoading}><Statistic title="Fallbacks" value={s ? formatCount(s.fallbacks) : '–'} />
          <Typography.Text type="secondary">{s ? `${formatCount(s.piiRedactions)} PII redactions` : ' '}</Typography.Text></Card></Col>
        {s && s.requests === 0 ? (
          <Col span={24}><Card><Empty description="No AI calls in this window." /></Card></Col>
        ) : (
          <>
            <Col xs={24} xl={12}><Card title="Cost over time" loading={series.isLoading}>{costChart && <EChart option={costChart} ariaLabel="Cost over time" />}</Card></Col>
            <Col xs={24} xl={12}><Card title="Tokens over time" loading={series.isLoading}>{tokenChart && <EChart option={tokenChart} ariaLabel="Tokens over time" />}</Card></Col>
            <Col xs={24} lg={12}><Card title="By model"><TopTable top={byModel.data} loading={byModel.isLoading} label="Model" /></Card></Col>
            <Col xs={24} lg={12}><Card title="By app"><TopTable top={byApp.data} loading={byApp.isLoading} label="App" /></Card></Col>
          </>
        )}
      </Row>
    </>
  );
}

function TopTable({ top, loading, label }: { top?: AiTop; loading: boolean; label: string }) {
  return (
    <Table
      size="small"
      rowKey="name"
      loading={loading}
      pagination={false}
      dataSource={top?.entries ?? []}
      columns={[
        { title: label, dataIndex: 'name', render: (n: string) => n || <Typography.Text type="secondary">(none)</Typography.Text> },
        { title: 'Calls', dataIndex: 'requests', align: 'right', render: (v: number) => formatCount(v) },
        { title: 'Tokens', dataIndex: 'tokens', align: 'right', render: (v: number) => formatCount(v) },
        { title: 'Cost', dataIndex: 'costUsd', align: 'right', render: (v: number) => formatUsd(v) },
        { title: 'Avg time', dataIndex: 'avgMs', align: 'right', render: (v: number) => formatMs(v) },
      ]}
    />
  );
}

function axis(data: AiTimeseries, mode: ThemeMode) {
  return {
    type: 'category',
    data: data.points.map((p) => bucketLabel(p.time, data.bucketSeconds)),
    ...axisStyle(mode),
    splitLine: { show: false },
  };
}

function costOption(data: AiTimeseries, mode: ThemeMode): ChartOption {
  const c = chartPalette(mode);
  return {
    ...baseOption(mode),
    legend: { show: false },
    tooltip: { ...(baseOption(mode).tooltip as object), trigger: 'axis', valueFormatter: (v: unknown) => formatUsd(Number(v)) },
    xAxis: axis(data, mode),
    yAxis: { type: 'value', ...axisStyle(mode), axisLabel: { color: c.muted, formatter: (v: number) => formatUsd(v) } },
    series: [{ name: 'Cost', type: 'bar', data: data.points.map((p) => p.costUsd), barMaxWidth: 14, itemStyle: { color: c.series[0], borderRadius: [4, 4, 0, 0] } }],
  };
}

function tokensOption(data: AiTimeseries, mode: ThemeMode): ChartOption {
  const c = chartPalette(mode);
  return {
    ...baseOption(mode),
    legend: { show: false },
    tooltip: { ...(baseOption(mode).tooltip as object), trigger: 'axis', valueFormatter: (v: unknown) => formatCount(Number(v)) },
    xAxis: { ...axis(data, mode), boundaryGap: false },
    yAxis: { type: 'value', minInterval: 1, ...axisStyle(mode) },
    series: [{ name: 'Tokens', type: 'line', data: data.points.map((p) => p.tokens), showSymbol: false, lineStyle: { width: 2, color: c.series[1] }, itemStyle: { color: c.series[1] } }],
  };
}

