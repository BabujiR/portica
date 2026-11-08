import { useState } from 'react';
import { Button, Result, Space, Spin } from 'antd';
import { Navigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { AppLayout } from './AppLayout';
import { CreateOrganizationModal } from './CreateOrganizationModal';
import { useMe, useOrganizations, WorkspaceProvider } from './Workspace';

function FullPageSpin() {
  return (
    <div style={{ display: 'grid', placeItems: 'center', minHeight: '60vh' }}>
      <Spin size="large" />
    </div>
  );
}

/** `/`: go to the user's first organization. */
export function Home() {
  const me = useMe();
  const organizations = useOrganizations(me.data);
  const { logout } = useAuth();
  const [creating, setCreating] = useState(false);
  if (me.isLoading) return <FullPageSpin />;
  if (me.error) return <Result status="error" title="Couldn't reach the management API" subTitle={errorMessage(me.error)} />;
  if (organizations.length === 0) {
    const admin = !!me.data?.platformAdmin;
    return (
      <>
        <Result
          status="info"
          title={admin ? 'Create your first organization' : "You're not in any organization yet"}
          subTitle={admin
            ? 'Organizations hold environments, API proxies, products and apps, and their own members.'
            : 'Ask an organization admin to add you, or a platform admin to create an organization for you.'}
          extra={
            <Space>
              {admin && <Button type="primary" onClick={() => setCreating(true)}>New organization</Button>}
              <Button onClick={() => void logout()}>Sign out</Button>
            </Space>
          }
        />
        <CreateOrganizationModal open={creating} onClose={() => setCreating(false)} />
      </>
    );
  }
  return <Navigate to={`/o/${organizations[0].name}/dashboard`} replace />;
}

/** `/o/:org/*`: workspace state plus the application shell. */
export function OrgShell() {
  const { org } = useParams();
  const me = useMe();
  const organizations = useOrganizations(me.data);
  if (me.isLoading || !me.data) {
    return me.error
      ? <Result status="error" title="Couldn't reach the management API" subTitle={errorMessage(me.error)} />
      : <FullPageSpin />;
  }
  const known = me.data.platformAdmin || organizations.some((o) => o.name === org);
  if (!known) {
    return (
      <Result
        status="404"
        title="Organization not found"
        subTitle={`You don't have access to '${org}'.`}
        extra={<Button type="primary" href="/">Go to my organizations</Button>}
      />
    );
  }
  return (
    <WorkspaceProvider me={me.data} organizations={organizations}>
      <AppLayout />
    </WorkspaceProvider>
  );
}

export function NotFound() {
  return <Result status="404" title="Page not found" extra={<Button href="/">Home</Button>} />;
}

