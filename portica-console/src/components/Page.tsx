import type { ReactNode } from 'react';
import { Alert, Breadcrumb, Flex, Result, Skeleton, Space, Typography } from 'antd';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';

export interface Crumb {
  title: string;
  to?: string;
}

/** Standard page frame: breadcrumbs, title, actions on the right, then content. */
export function Page({ title, subtitle, crumbs, extra, children }: {
  title: ReactNode;
  subtitle?: ReactNode;
  crumbs?: Crumb[];
  extra?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="page">
      {crumbs && crumbs.length > 0 && (
        <Breadcrumb
          style={{ marginBottom: 8 }}
          items={crumbs.map((c) => ({ title: c.to ? <Link to={c.to}>{c.title}</Link> : c.title }))}
        />
      )}
      <Flex justify="space-between" align="flex-start" gap={16} wrap style={{ marginBottom: 16 }}>
        <div style={{ minWidth: 0 }}>
          <Typography.Title level={3} style={{ margin: 0 }}>{title}</Typography.Title>
          {subtitle && <Typography.Text type="secondary">{subtitle}</Typography.Text>}
        </div>
        {extra && <Space wrap>{extra}</Space>}
      </Flex>
      {children}
    </div>
  );
}

/** Loading skeleton, error banner or the content. */
export function Loadable({ loading, error, children }: { loading: boolean; error: unknown; children: ReactNode }) {
  if (error) return <Alert type="error" showIcon message="Couldn't load this" description={errorMessage(error)} />;
  if (loading) return <Skeleton active paragraph={{ rows: 6 }} />;
  return <>{children}</>;
}

export function NoAccess({ what }: { what: string }) {
  return (
    <Result status="403" title="No access" subTitle={`Your role in this organization doesn't include ${what}. Ask an organization admin.`} />
  );
}
