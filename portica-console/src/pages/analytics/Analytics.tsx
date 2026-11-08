import { useMemo, useState } from 'react';
import { Alert, Button, Card, Col, Empty, Flex, Row, Segmented, Select, Space, Statistic, Switch, Table, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { errorMessage } from '../../api/client';
import {
  getAnalyticsEvents, getAnalyticsSummary, getAnalyticsTimeseries, getAnalyticsTop, listProxies, type AnalyticsFilter,
} from '../../api/endpoints';
import type { AnalyticsTimeseries, AnalyticsTop, ApiEvent } from '../../api/types';
import { useThemeMode, type ThemeMode } from '../../app/ThemeProvider';
import { useWorkspace } from '../../app/Workspace';
import { EChart, axisStyle, baseOption, chartPalette, type ChartOption } from '../../components/EChart';
import { NoAccess, Page } from '../../components/Page';
import {
  RANGES, bucketLabel, formatCount, formatMs, formatPercent, rangeWindow, statusClass, type RangeKey,
} from './analyticsModel';

const STATUS_TAG: Record<string, string> = { good: 'success', warning: 'warning', serious: 'orange', critical: 'error' };

/** Traffic, errors and latency of the organization's API calls, from ClickHouse via the management API. */
export function Analytics() {
  const { org, environments, can } = useWorkspace();
  const { mode } = useThemeMode();
  const queryClient = useQueryClient();
  const [range, setRange] = useState<RangeKey>('1h');
  const [environment, setEnvironment] = useState<string | undefined>();
  const [apiProxy, setApiProxy] = useState<string | undefined>();
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [live, setLive] = useState(true);

  const allowed = can('ANALYTICS_READ');
  const proxies = useQuery({ queryKey: ['proxies', org], queryFn: () => listProxies(org), enabled: can('PROXY_READ') });
  const key = ['analytics', org, range, environment ?? '', apiProxy ?? ''];
  // the window is computed when each request runs, so refreshes move it forward
  const filter = (): AnalyticsFilter => ({ ...rangeWindow(range), environment, apiProxy });
  const common = {
    enabled: allowed,
    placeholderData: keepPreviousData,
    refetchInterval: live ? 30_000 : (false as const),
  };

  const summary = useQuery({ queryKey: [...key, 'summary'], queryFn: () => getAnalyticsSummary(org, filter()), ...common });
  const series = useQuery({ queryKey: [...key, 'timeseries'], queryFn: () => getAnalyticsTimeseries(org, filter()), ...common });
  const topProxies = useQuery({ queryKey: [...key, 'top', 'api_proxy'], queryFn: () => getAnalyticsTop(org, 'api_proxy', filter(), 8), ...common });
  const topApps = useQuery({ queryKey: [...key, 'top', 'app'], queryFn: () => getAnalyticsTop(org, 'app', filter(), 8), ...common });
  const statuses = useQuery({ queryKey: [...key, 'top', 'status'], queryFn: () => getAnalyticsTop(org, 'status', filter(), 10), ...common });
  const events = useQuery({
    queryKey: [...key, 'events', statusFilter ?? ''],
    queryFn: () => getAnalyticsEvents(org, filter(), statusFilter, 100),
    ...common,
  });

  const traffic = useMemo(() => series.data && trafficOption(series.data, mode), [series.data, mode]);
  const latency = useMemo(() => series.data && latencyOption(series.data, mode), [series.data, mode]);
  const proxyBars = useMemo(() => topProxies.data && barOption(topProxies.data, mode, 'Requests'), [topProxies.data, mode]);
  const appBars = useMemo(() => topApps.data && barOption(topApps.data, mode, 'Requests', '(no app)'), [topApps.data, mode]);
  const statusBars = useMemo(() => statuses.data && statusOption(statuses.data, mode), [statuses.data, mode]);

  if (!allowed) return <NoAccess what="analytics" />;

  const s = summary.data;
  const failure = summary.error ?? series.error;
  const empty = s && s.requests === 0;

  return (
    <Page
      title="Analytics"
      subtitle="Calls through the gateway, by proxy, app and status"
      extra={(
        <>
          <Tooltip title="Refresh every 30 seconds">
            <Space size={6}><Switch size="small" checked={live} onChange={setLive} aria-label="Auto refresh" />Live</Space>
          </Tooltip>
          <Button icon={<ReloadOutlined />} onClick={() => void queryClient.invalidateQueries({ queryKey: ['analytics', org] })}>
            Refresh
          </Button>
        </>
      )}
    >
      <Flex gap={12} wrap align="center" style={{ marginBottom: 16 }}>
        <Segmented<RangeKey>
          value={range}
          onChange={setRange}
          options={RANGES.map((r) => ({ value: r.key, label: r.label }))}
        />
        <Select
          aria-label="Environment"
          allowClear
          placeholder="All environments"
          style={{ minWidth: 170 }}
          value={environment}
          onChange={setEnvironment}
          options={environments.map((e) => ({ value: e.name, label: e.displayName || e.name }))}
        />
        <Select
          aria-label="API proxy"
          allowClear
          showSearch
          placeholder="All API proxies"
          style={{ minWidth: 200 }}
          value={apiProxy}
          onChange={setApiProxy}
          options={(proxies.data?.items ?? []).map((p) => ({ value: p.name, label: p.name }))}
        />
      </Flex>

      {failure && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Analytics couldn't be loaded"
          description={errorMessage(failure)}
        />
      )}

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <Card loading={summary.isLoading}>
            <Statistic title="Requests" value={s ? formatCount(s.requests) : '–'} />
            <Typography.Text type="secondary">{s?.cacheHits ? `${formatCount(s.cacheHits)} from cache` : ' '}</Typography.Text>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={summary.isLoading}>
            <Statistic title="Error rate" value={s ? formatPercent(s.errorRate) : '–'} />
            <Typography.Text type="secondary">{s ? `${formatCount(s.errors4xx)} 4xx · ${formatCount(s.errors5xx)} 5xx` : ' '}</Typography.Text>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={summary.isLoading}>
            <Statistic title="p95 latency" value={s ? formatMs(s.p95Ms) : '–'} />
            <Typography.Text type="secondary">{s ? `avg ${formatMs(s.avgMs)} · p99 ${formatMs(s.p99Ms)}` : ' '}</Typography.Text>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={summary.isLoading}>
            <Statistic title="Avg target time" value={s ? formatMs(s.avgTargetMs) : '–'} />
            <Typography.Text type="secondary">time spent waiting for backends</Typography.Text>
          </Card>
        </Col>

        {empty ? (
          <Col span={24}>
            <Card>
              <Empty description="No calls in this window. Calls through the gateway show up here within a few seconds." />
            </Card>
          </Col>
        ) : (
          <>
            <Col xs={24} xl={12}>
              <Card title="Traffic" loading={series.isLoading}>
                {traffic && <EChart option={traffic} ariaLabel="Requests and errors over time" />}
              </Card>
            </Col>
            <Col xs={24} xl={12}>
              <Card title="Latency" loading={series.isLoading}>
                {latency && <EChart option={latency} ariaLabel="Average and p95 latency over time" />}
              </Card>
            </Col>
            <Col xs={24} lg={8}>
              <Card title="Top API proxies" loading={topProxies.isLoading}>
                {proxyBars && <EChart option={proxyBars} ariaLabel="Busiest API proxies" />}
              </Card>
            </Col>
            <Col xs={24} lg={8}>
              <Card title="Top apps" loading={topApps.isLoading}>
                {appBars && <EChart option={appBars} ariaLabel="Busiest apps" />}
              </Card>
            </Col>
            <Col xs={24} lg={8}>
              <Card title="Status codes" loading={statuses.isLoading}>
                {statusBars && <EChart option={statusBars} ariaLabel="Responses by status code" />}
              </Card>
            </Col>
          </>
        )}

        <Col span={24}>
          <Card
            title="Recent calls"
            extra={(
              <Select
                aria-label="Status filter"
                allowClear
                placeholder="All statuses"
                style={{ width: 150 }}
                value={statusFilter}
                onChange={setStatusFilter}
                options={[
                  { value: 'errors', label: 'Errors (4xx, 5xx)' },
                  { value: '2xx', label: '2xx' },
                  { value: '4xx', label: '4xx' },
                  { value: '5xx', label: '5xx' },
                ]}
              />
            )}
          >
            <EventTable events={events.data?.events ?? []} loading={events.isLoading} org={org} />
          </Card>
        </Col>
      </Row>
    </Page>
  );
}

function EventTable({ events, loading, org }: { events: ApiEvent[]; loading: boolean; org: string }) {
  return (
    <Table<ApiEvent>
      rowKey={(e) => `${e.requestId}-${e.time}`}
      size="small"
      loading={loading}
      dataSource={events}
      pagination={{ pageSize: 20, hideOnSinglePage: true }}
      scroll={{ x: 900 }}
      columns={[
        { title: 'Time', key: 'time', width: 170, render: (_, e) => new Date(e.time).toLocaleString() },
        {
          title: 'Status', key: 'status', width: 80,
          render: (_, e) => <Tag color={STATUS_TAG[statusClass(e.status)]}>{e.status}</Tag>,
        },
        { title: 'Request', key: 'request', render: (_, e) => <Typography.Text code>{e.method} {e.path}</Typography.Text> },
        { title: 'API proxy', key: 'proxy', render: (_, e) => <Link to={`/o/${org}/proxies/${e.apiProxy}`}>{e.apiProxy}</Link> },
        { title: 'Env', dataIndex: 'environment', width: 90 },
        { title: 'App', key: 'app', render: (_, e) => e.app || <Typography.Text type="secondary">–</Typography.Text> },
        { title: 'Time taken', key: 'ms', width: 120, align: 'right', render: (_, e) => `${formatMs(e.totalMs)}` },
        { title: 'Fault', key: 'fault', render: (_, e) => e.fault && <Typography.Text type="danger">{e.fault}</Typography.Text> },
        { title: 'Request id', key: 'rid', width: 120, render: (_, e) => <Typography.Text copyable={{ text: e.requestId }} type="secondary">{e.requestId.slice(0, 8)}</Typography.Text> },
      ]}
    />
  );
}

// ------------------------------------------------------------------ chart options

function timeAxis(data: AnalyticsTimeseries, mode: ThemeMode) {
  return {
    type: 'category',
    boundaryGap: false,
    data: data.points.map((p) => bucketLabel(p.time, data.bucketSeconds)),
    ...axisStyle(mode),
    splitLine: { show: false },
  };
}

function line(name: string, values: number[], color: string) {
  return {
    name,
    type: 'line',
    data: values,
    showSymbol: false,
    symbolSize: 8,
    lineStyle: { width: 2, color },
    itemStyle: { color },
    emphasis: { focus: 'series' },
  };
}

function trafficOption(data: AnalyticsTimeseries, mode: ThemeMode): ChartOption {
  const c = chartPalette(mode);
  return {
    ...baseOption(mode),
    tooltip: { ...(baseOption(mode).tooltip as object), trigger: 'axis', axisPointer: { type: 'line' } },
    xAxis: timeAxis(data, mode),
    yAxis: { type: 'value', minInterval: 1, ...axisStyle(mode) },
    series: [
      line('Requests', data.points.map((p) => p.requests), c.series[0]),
      line('Errors', data.points.map((p) => p.errors), c.critical),
    ],
  };
}

function latencyOption(data: AnalyticsTimeseries, mode: ThemeMode): ChartOption {
  const c = chartPalette(mode);
  return {
    ...baseOption(mode),
    tooltip: {
      ...(baseOption(mode).tooltip as object),
      trigger: 'axis',
      valueFormatter: (v: unknown) => formatMs(Number(v)),
    },
    xAxis: timeAxis(data, mode),
    yAxis: { type: 'value', name: 'ms', nameTextStyle: { color: c.muted }, ...axisStyle(mode) },
    series: [
      line('Average', data.points.map((p) => p.avgMs), c.series[0]),
      line('p95', data.points.map((p) => p.p95Ms), c.series[1]),
    ],
  };
}

function barOption(top: AnalyticsTop, mode: ThemeMode, name: string, emptyName = '(none)'): ChartOption {
  const c = chartPalette(mode);
  const entries = [...top.entries].reverse(); // largest on top
  return {
    ...baseOption(mode),
    legend: { show: false },
    grid: { left: 8, right: 48, top: 8, bottom: 8, containLabel: true },
    tooltip: {
      ...(baseOption(mode).tooltip as object),
      trigger: 'item',
      formatter: (p: { dataIndex: number }) => {
        const e = entries[p.dataIndex];
        return `<b>${escapeHtml(e.name || emptyName)}</b><br/>${formatCount(e.requests)} requests<br/>`
          + `${formatCount(e.errors)} errors · avg ${formatMs(e.avgMs)}`;
      },
    },
    xAxis: { type: 'value', minInterval: 1, ...axisStyle(mode), axisLabel: { show: false } },
    yAxis: {
      type: 'category',
      data: entries.map((e) => e.name || emptyName),
      ...axisStyle(mode),
      splitLine: { show: false },
      axisLabel: { color: c.text, width: 120, overflow: 'truncate' },
    },
    series: [{
      name,
      type: 'bar',
      data: entries.map((e) => e.requests),
      barMaxWidth: 18,
      itemStyle: { color: c.series[0], borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right', color: c.text, formatter: (p: { value: number }) => formatCount(p.value) },
    }],
  };
}

function statusOption(top: AnalyticsTop, mode: ThemeMode): ChartOption {
  const option = barOption({ ...top, entries: [...top.entries].sort((a, b) => b.requests - a.requests) }, mode, 'Responses');
  const c = chartPalette(mode);
  const entries = [...top.entries].sort((a, b) => b.requests - a.requests).reverse();
  const series = (option.series as Array<Record<string, unknown>>)[0];
  series.data = entries.map((e) => ({ value: e.requests, itemStyle: { color: c[statusClass(Number(e.name))] } }));
  return option;
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch] as string));
}
