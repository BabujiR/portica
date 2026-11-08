import { useEffect, useMemo, useState } from 'react';
import {
  Alert, Badge, Button, Card, Col, Collapse, Descriptions, Empty, Flex, Form, Input, InputNumber, List, Row, Select,
  Space, Table, Tag, Timeline, Typography,
} from 'antd';
import { BugOutlined, PauseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listTraceSessions, startTraceSession, stopTraceSession, traceStreamPath } from '../../api/endpoints';
import { openStream } from '../../api/sse';
import type { Deployment, StartTraceRequest, TraceSession, TraceStep, TraceTransaction } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { fromNow } from '../../components/format';
import { useNotify } from '../../components/useNotify';
import { statusClass } from '../analytics/analyticsModel';

const STATUS_TAG: Record<string, string> = { good: 'success', warning: 'warning', serious: 'orange', critical: 'error' };
const OUTCOME: Record<TraceStep['outcome'], { color: string; label: string }> = {
  executed: { color: 'green', label: 'ran' },
  skipped: { color: 'gray', label: 'skipped' },
  continued: { color: 'orange', label: 'failed, continued' },
  fault: { color: 'red', label: 'fault' },
};

/**
 * Debug trace for one proxy: start a session in an environment where it's deployed, watch calls arrive
 * live (Server-Sent Events) and inspect each one step by step.
 */
export function TracePanel({ api, deployments }: { api: string; deployments: Deployment[] }) {
  const { org } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const deployedEnvs = useMemo(() => deployments.map((d) => d.environment), [deployments]);
  const [env, setEnv] = useState<string | undefined>(deployedEnvs[0]);
  const [selectedSession, setSelectedSession] = useState<string | null>(null);
  const [form] = Form.useForm<StartTraceRequest>();

  useEffect(() => {
    if (!env || !deployedEnvs.includes(env)) setEnv(deployedEnvs[0]);
  }, [deployedEnvs, env]);

  const sessions = useQuery({
    queryKey: ['trace-sessions', org, env, api],
    queryFn: () => listTraceSessions(org, env as string, api),
    enabled: !!env,
    refetchInterval: 10_000,
  });

  useEffect(() => {
    if (!selectedSession && sessions.data?.length) setSelectedSession(sessions.data[0].id);
  }, [sessions.data, selectedSession]);

  const start = useMutation({
    mutationFn: (body: StartTraceRequest) => startTraceSession(org, env as string, api, body),
    onSuccess: (session) => {
      setSelectedSession(session.id);
      void queryClient.invalidateQueries({ queryKey: ['trace-sessions', org, env, api] });
      notify.success('Trace started. Gateways pick it up within a few seconds.');
    },
    onError: (e) => notify.error(e),
  });
  const stop = useMutation({
    mutationFn: (id: string) => stopTraceSession(org, id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['trace-sessions', org, env, api] }),
    onError: (e) => notify.error(e),
  });

  if (deployedEnvs.length === 0) {
    return <Empty description="Deploy this proxy to an environment to trace its calls." />;
  }

  const onStart = (values: StartTraceRequest) => {
    const body: StartTraceRequest = { ...values };
    (Object.keys(body) as Array<keyof StartTraceRequest>).forEach((k) => {
      if (body[k] === '' || body[k] === null) delete body[k];
    });
    start.mutate(body);
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small">
        <Form<StartTraceRequest>
          form={form}
          layout="inline"
          onFinish={onStart}
          initialValues={{ durationSeconds: 300, maxTransactions: 20 }}
        >
          <Form.Item label="Environment">
            <Select
              aria-label="Environment"
              style={{ width: 140 }}
              value={env}
              onChange={(value: string) => { setEnv(value); setSelectedSession(null); }}
              options={deployedEnvs.map((e) => ({ value: e, label: e }))}
            />
          </Form.Item>
          <Form.Item label="Run for" name="durationSeconds">
            <Select style={{ width: 110 }} options={[60, 300, 600].map((s) => ({ value: s, label: `${s / 60} min` }))} />
          </Form.Item>
          <Form.Item label="Capture up to" name="maxTransactions">
            <InputNumber min={1} max={100} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" icon={<PlayCircleOutlined />} loading={start.isPending}>
              Start trace
            </Button>
          </Form.Item>
          <Collapse
            ghost
            size="small"
            style={{ width: '100%' }}
            items={[{
              key: 'filters',
              label: 'Only capture some calls',
              children: (
                <Flex gap={8} wrap>
                  <Form.Item name="filterHeader" label="Header" rules={[{ pattern: /^[A-Za-z0-9!#$%&'*+.^_`|~-]*$/, message: 'Not a header name' }]}>
                    <Input placeholder="X-Debug" style={{ width: 160 }} />
                  </Form.Item>
                  <Form.Item name="filterValue" label="equals">
                    <Input placeholder="any value" style={{ width: 160 }} />
                  </Form.Item>
                  <Form.Item name="filterClientIp" label="Client IP" rules={[{ pattern: /^[0-9A-Fa-f.:]*$/, message: 'Not an IP address' }]}>
                    <Input placeholder="203.0.113.7" style={{ width: 160 }} />
                  </Form.Item>
                </Flex>
              ),
            }]}
          />
        </Form>
      </Card>

      <Row gutter={16}>
        <Col xs={24} lg={6}>
          <Card size="small" title="Sessions (last 24 hours)">
            <List<TraceSession>
              size="small"
              loading={sessions.isLoading}
              dataSource={sessions.data ?? []}
              locale={{ emptyText: 'No sessions yet' }}
              renderItem={(s) => (
                <List.Item
                  onClick={() => setSelectedSession(s.id)}
                  style={{ cursor: 'pointer', fontWeight: s.id === selectedSession ? 600 : undefined }}
                  actions={s.state === 'ACTIVE' ? [
                    <Button key="stop" size="small" icon={<PauseCircleOutlined />} onClick={(e) => { e.stopPropagation(); stop.mutate(s.id); }}>
                      Stop
                    </Button>,
                  ] : []}
                >
                  <List.Item.Meta
                    title={<Space size={6}><SessionBadge session={s} />{fromNow(s.createdAt)}</Space>}
                    description={s.filterHeader ? `${s.filterHeader}${s.filterValue ? ` = ${s.filterValue}` : ''}` : s.filterClientIp ?? 'all calls'}
                  />
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col xs={24} lg={18}>
          {selectedSession
            ? <SessionView key={selectedSession} org={org} sessionId={selectedSession} />
            : <Card><Empty image={<BugOutlined style={{ fontSize: 40 }} />} description="Start a trace, then call the proxy." /></Card>}
        </Col>
      </Row>
    </Space>
  );
}

function SessionBadge({ session }: { session: TraceSession }) {
  if (session.state === 'ACTIVE') return <Badge status="processing" text="live" />;
  return <Badge status="default" text={session.state.toLowerCase()} />;
}

/** Live view of one session: transactions arrive over SSE; pick one to see its steps. */
function SessionView({ org, sessionId }: { org: string; sessionId: string }) {
  const [session, setSession] = useState<TraceSession | null>(null);
  const [transactions, setTransactions] = useState<TraceTransaction[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const handle = openStream(traceStreamPath(org, sessionId), {
      onEvent: (e) => {
        if (e.event === 'session') setSession(JSON.parse(e.data) as TraceSession);
        if (e.event === 'transaction') {
          const tx = JSON.parse(e.data) as TraceTransaction;
          setTransactions((current) => (current.some((t) => t.requestId === tx.requestId) ? current : [...current, tx]));
          setSelected((current) => current ?? tx.requestId);
          setError(null);
        }
        if (e.event === 'error') setError((JSON.parse(e.data) as { message: string }).message);
      },
      onError: setError,
      onClose: () => setStreaming(false),
    });
    return () => handle.close();
  }, [org, sessionId]);

  const current = transactions.find((t) => t.requestId === selected) ?? null;

  return (
    <Card
      size="small"
      title={(
        <Space>
          Captured calls
          <Tag>{transactions.length}{session ? ` / ${session.maxTransactions}` : ''}</Tag>
          {streaming && session?.state === 'ACTIVE' && <Badge status="processing" text="listening" />}
        </Space>
      )}
      extra={session?.state === 'ACTIVE' && <Typography.Text type="secondary">ends {fromNow(session.expiresAt)}</Typography.Text>}
    >
      {error && <Alert type="warning" showIcon message={error} style={{ marginBottom: 12 }} />}
      {transactions.length === 0 ? (
        <Empty description={session?.state === 'ACTIVE'
          ? 'Waiting for calls… send a request to the proxy (matching the filters).'
          : 'No calls were captured in this session.'}
        />
      ) : (
        <Row gutter={16}>
          <Col xs={24} md={9}>
            <List<TraceTransaction>
              size="small"
              dataSource={[...transactions].reverse()}
              renderItem={(t) => (
                <List.Item
                  onClick={() => setSelected(t.requestId)}
                  style={{ cursor: 'pointer', background: t.requestId === selected ? 'var(--ant-color-fill-tertiary, rgba(0,0,0,0.04))' : undefined, paddingInline: 8 }}
                >
                  <Flex vertical style={{ minWidth: 0, width: '100%' }}>
                    <Space size={6}>
                      <Tag color={STATUS_TAG[statusClass(t.status)]}>{t.status}</Tag>
                      <Typography.Text type="secondary">{t.totalMs} ms</Typography.Text>
                      <Typography.Text type="secondary">{new Date(t.time).toLocaleTimeString()}</Typography.Text>
                    </Space>
                    <Typography.Text ellipsis code>
                      {t.payload.request?.method} {t.payload.request?.uri}
                    </Typography.Text>
                  </Flex>
                </List.Item>
              )}
            />
          </Col>
          <Col xs={24} md={15}>
            {current && <TransactionDetail tx={current} />}
          </Col>
        </Row>
      )}
    </Card>
  );
}

function TransactionDetail({ tx }: { tx: TraceTransaction }) {
  const p = tx.payload;
  const steps = p.steps ?? [];
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Descriptions size="small" column={1} bordered>
        <Descriptions.Item label="Request">
          <Typography.Text code>{p.request?.method} {p.request?.uri}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="Response">
          <Tag color={STATUS_TAG[statusClass(tx.status)]}>{tx.status}</Tag> in {tx.totalMs} ms
        </Descriptions.Item>
        {p.target && (
          <Descriptions.Item label="Target">
            <Typography.Text code>{p.target.url}</Typography.Text> → {p.target.status} in {p.target.durationMs} ms
          </Descriptions.Item>
        )}
        {p.fault && (
          <Descriptions.Item label="Fault">
            <Typography.Text type="danger">{p.fault.name} ({p.fault.status})</Typography.Text> {p.fault.message}
          </Descriptions.Item>
        )}
        <Descriptions.Item label="Request id"><Typography.Text copyable>{tx.requestId}</Typography.Text></Descriptions.Item>
      </Descriptions>

      <Typography.Title level={5} style={{ margin: 0 }}>Steps</Typography.Title>
      {steps.length === 0 ? (
        <Typography.Text type="secondary">No policies ran.</Typography.Text>
      ) : (
        <Timeline
          items={withTarget(steps, p.target ?? null).map((s) => ('target' in s
            ? {
              color: 'blue',
              children: <span><b>Target</b> → {s.target.status} <Typography.Text type="secondary">{s.target.durationMs} ms</Typography.Text></span>,
            }
            : {
              color: OUTCOME[s.outcome].color,
              children: (
                <Flex vertical>
                  <span>
                    <b>{s.policy}</b> <Typography.Text type="secondary">{s.type}</Typography.Text>{' '}
                    <Tag color={OUTCOME[s.outcome].color}>{OUTCOME[s.outcome].label}</Tag>
                    <Typography.Text type="secondary">{s.durationMs} ms</Typography.Text>
                  </span>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {s.flow}{s.condition ? ` · when ${s.condition}` : ''}{s.fault ? ` · ${s.fault}` : ''}
                  </Typography.Text>
                </Flex>
              ),
            }))}
        />
      )}

      <Collapse
        size="small"
        items={[
          { key: 'req', label: 'Request headers', children: <KeyValues values={p.request?.headers ?? {}} /> },
          { key: 'res', label: 'Response headers', children: <KeyValues values={p.response?.headers ?? {}} /> },
          { key: 'vars', label: `Flow variables (${Object.keys(p.variables ?? {}).length})`, children: <KeyValues values={p.variables ?? {}} /> },
        ]}
      />
    </Space>
  );
}

type TimelineEntry = TraceStep | { target: { url: string; status: number; durationMs: number } };

/** Puts the target call between the request and response steps. */
function withTarget(steps: TraceStep[], target: { url: string; status: number; durationMs: number } | null): TimelineEntry[] {
  if (!target) return steps;
  const firstResponse = steps.findIndex((s) => s.flow.endsWith('response'));
  const at = firstResponse < 0 ? steps.length : firstResponse;
  return [...steps.slice(0, at), { target }, ...steps.slice(at)];
}

function KeyValues({ values }: { values: Record<string, unknown> }) {
  const rows = Object.entries(values).map(([name, value]) => ({ name, value: typeof value === 'string' ? value : JSON.stringify(value) }));
  return (
    <Table
      size="small"
      rowKey="name"
      pagination={false}
      dataSource={rows}
      locale={{ emptyText: 'None' }}
      columns={[
        { title: 'Name', dataIndex: 'name', width: '35%' },
        { title: 'Value', dataIndex: 'value', render: (v: string) => <Typography.Text code style={{ wordBreak: 'break-all' }}>{v}</Typography.Text> },
      ]}
    />
  );
}
