import { useMemo, useState } from 'react';
import { Card, Col, Empty, Result, Row, Segmented, Statistic, Table } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getMyUsage } from '../../api/portal';
import type { AnalyticsTimeseries, TopEntry } from '../../api/types';
import { useThemeMode, type ThemeMode } from '../../app/ThemeProvider';
import { EChart, axisStyle, baseOption, chartPalette, type ChartOption } from '../../components/EChart';
import { Loadable, Page } from '../../components/Page';
import { bucketLabel, formatCount, formatMs, formatPercent, rangeWindow, type RangeKey } from '../analytics/analyticsModel';
import { RegisterCard, useDeveloper } from './PortalApps';

const RANGE_OPTIONS: Array<{ value: RangeKey; label: string }> = [
  { value: '1h', label: '1 hour' }, { value: '24h', label: '24 hours' }, { value: '7d', label: '7 days' }, { value: '30d', label: '30 days' },
];

/** The developer's own traffic across their apps. */
export function PortalUsage() {
  const { org = '' } = useParams();
  const me = useDeveloper(org);
  const [range, setRange] = useState<RangeKey>('24h');
  const span = useMemo(() => rangeWindow(range), [range]);
  const usage = useQuery({
    queryKey: ['portal-usage', org, span.from, span.to],
    queryFn: () => getMyUsage(org, span.from, span.to),
    enabled: !!me.data,
  });
  const { mode } = useThemeMode();
  const chart = useMemo(() => usage.data && trafficOption(usage.data.timeseries, mode), [usage.data, mode]);

  if (me.isLoading || me.error) return <Loadable loading={me.isLoading} error={me.error}>{null}</Loadable>;
  if (!me.data) return <RegisterCard org={org} />;
  if (me.data.status !== 'ACTIVE') return <Result status="403" title="Your developer account is inactive" />;
  const s = usage.data?.summary;

  return (
    <Page title="Usage" subtitle="Requests made with your apps' keys."
      extra={<Segmented value={range} onChange={(v) => setRange(v as RangeKey)} options={RANGE_OPTIONS} />}>
      <Loadable loading={usage.isLoading} error={usage.error}>
        {s && usage.data && (
          <Row gutter={[16, 16]}>
            <Col xs={12} md={6}><Card><Statistic title="Requests" value={formatCount(s.requests)} /></Card></Col>
            <Col xs={12} md={6}><Card><Statistic title="Error rate" value={formatPercent(s.errorRate)} /></Card></Col>
            <Col xs={12} md={6}><Card><Statistic title="Average latency" value={formatMs(s.avgMs)} /></Card></Col>
            <Col xs={12} md={6}><Card><Statistic title="p95 latency" value={formatMs(s.p95Ms)} /></Card></Col>
            <Col span={24}>
              <Card title="Traffic">
                {s.requests === 0 ? <Empty description="No requests in this period" /> : chart && <EChart option={chart} ariaLabel="Requests and errors over time" />}
              </Card>
            </Col>
            <Col xs={24} lg={12}><Card title="By app"><TopTable entries={usage.data.byApp.entries} label="App" /></Card></Col>
            <Col xs={24} lg={12}><Card title="By API"><TopTable entries={usage.data.byApi.entries} label="API" /></Card></Col>
          </Row>
        )}
      </Loadable>
    </Page>
  );
}

function TopTable({ entries, label }: { entries: TopEntry[]; label: string }) {
  return (
    <Table<TopEntry>
      rowKey="name"
      size="small"
      pagination={false}
      dataSource={entries}
      locale={{ emptyText: 'No requests' }}
      columns={[
        { title: label, dataIndex: 'name', render: (n: string) => n || '–' },
        { title: 'Requests', dataIndex: 'requests', align: 'right', render: (n: number) => formatCount(n) },
        { title: 'Errors', dataIndex: 'errors', align: 'right', render: (n: number) => formatCount(n) },
        { title: 'Avg', dataIndex: 'avgMs', align: 'right', render: (n: number) => formatMs(n) },
      ]}
    />
  );
}

function trafficOption(data: AnalyticsTimeseries, mode: ThemeMode): ChartOption {
  const c = chartPalette(mode);
  const base = baseOption(mode);
  const series = (name: string, values: number[], color: string) => ({
    name, type: 'line', data: values, showSymbol: false, lineStyle: { width: 2, color }, itemStyle: { color },
  });
  return {
    ...base,
    tooltip: { ...(base.tooltip as object), trigger: 'axis' },
    xAxis: { type: 'category', boundaryGap: false, data: data.points.map((p) => bucketLabel(p.time, data.bucketSeconds)), ...axisStyle(mode) },
    yAxis: { type: 'value', minInterval: 1, ...axisStyle(mode) },
    series: [
      series('Requests', data.points.map((p) => p.requests), c.series[0]),
      series('Errors', data.points.map((p) => p.errors), c.critical),
    ],
  };
}
