import { useState } from 'react';
import { DatePicker, Descriptions, Flex, Input, Select, Table, Tag, Typography } from 'antd';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { Dayjs } from 'dayjs';
import { listAuditEvents, listAuditResourceTypes } from '../../api/endpoints';
import type { AuditEvent } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { dateTime, fromNow } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';

const VERB_COLOR: Record<string, string> = {
  created: 'green', deployed: 'green', started: 'blue', updated: 'blue', rotated: 'blue',
  deleted: 'red', undeployed: 'orange', revoked: 'red', stopped: 'orange', removed: 'red',
};

/** Color by the last word of the action (apiproxy.created → created). */
function actionColor(action: string): string | undefined {
  const verb = action.split('.').pop() ?? '';
  return VERB_COLOR[verb];
}

/** Who changed what, and when: the organization's audit trail with search and filters. */
export function AuditLog() {
  const { org, can } = useWorkspace();
  const [q, setQ] = useState('');
  const [resourceType, setResourceType] = useState<string | undefined>();
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const allowed = can('AUDIT_READ');

  const from = range?.[0]?.startOf('day').toISOString();
  const to = range?.[1]?.endOf('day').toISOString();
  const types = useQuery({ queryKey: ['audit-types', org], queryFn: () => listAuditResourceTypes(org), enabled: allowed });
  const events = useQuery({
    queryKey: ['audit', org, q, resourceType ?? '', from ?? '', to ?? '', page, size],
    queryFn: () => listAuditEvents(org, { q: q || undefined, resourceType, from, to, page, size }),
    enabled: allowed,
    placeholderData: keepPreviousData,
  });

  if (!allowed) return <NoAccess what="the audit log" />;

  return (
    <Page title="Audit Log" subtitle="Every configuration change in this organization, newest first">
      <Flex gap={12} wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="Search action, resource, actor or request id"
          style={{ width: 340 }}
          onSearch={(value) => { setQ(value.trim()); setPage(0); }}
        />
        <Select
          aria-label="Resource type"
          allowClear
          placeholder="All resource types"
          style={{ minWidth: 200 }}
          value={resourceType}
          onChange={(value?: string) => { setResourceType(value); setPage(0); }}
          options={(types.data ?? []).map((t) => ({ value: t, label: t }))}
        />
        <DatePicker.RangePicker
          aria-label="Date range"
          value={range}
          onChange={(value) => { setRange(value as [Dayjs | null, Dayjs | null] | null); setPage(0); }}
        />
      </Flex>
      <Loadable loading={false} error={events.error}>
        <Table<AuditEvent>
          rowKey="id"
          size="middle"
          loading={events.isFetching}
          dataSource={events.data?.items ?? []}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: events.data?.totalElements ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [25, 50, 100],
            onChange: (p, s) => { setPage(s !== size ? 0 : p - 1); setSize(s); },
          }}
          expandable={{
            rowExpandable: (e) => Object.keys(e.details ?? {}).length > 0 || !!e.requestId,
            expandedRowRender: (e) => (
              <Descriptions size="small" column={1} bordered>
                {Object.entries(e.details ?? {}).map(([k, v]) => (
                  <Descriptions.Item key={k} label={k}>
                    <Typography.Text code>{typeof v === 'string' ? v : JSON.stringify(v)}</Typography.Text>
                  </Descriptions.Item>
                ))}
                {e.requestId && (
                  <Descriptions.Item label="Request id"><Typography.Text copyable>{e.requestId}</Typography.Text></Descriptions.Item>
                )}
              </Descriptions>
            ),
          }}
          columns={[
            {
              title: 'When', key: 'when', width: 170,
              render: (_, e) => <span title={dateTime(e.occurredAt)}>{fromNow(e.occurredAt)}</span>,
            },
            { title: 'Action', key: 'action', render: (_, e) => <Tag color={actionColor(e.action)}>{e.action}</Tag> },
            { title: 'Resource', key: 'resource', render: (_, e) => <><Typography.Text type="secondary">{e.resourceType}</Typography.Text> {e.resourceName}</> },
            { title: 'Actor', key: 'actor', render: (_, e) => e.actor ?? '–' },
          ]}
        />
      </Loadable>
    </Page>
  );
}
