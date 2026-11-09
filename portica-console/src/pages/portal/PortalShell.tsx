import { Button, Layout, Menu, Space, Typography } from 'antd';
import { ApiOutlined, AppstoreOutlined, LineChartOutlined, LogoutOutlined } from '@ant-design/icons';
import { Link, Outlet, useLocation, useParams } from 'react-router-dom';
import { useAuth } from '../../auth/AuthProvider';

/** The developer portal frame: its own header and navigation, no console chrome. */
export function PortalShell() {
  const { org = '' } = useParams();
  const { pathname } = useLocation();
  const { user, logout } = useAuth();
  const base = `/portal/${org}`;
  const selected = pathname.startsWith(`${base}/apps`) ? 'apps' : pathname.startsWith(`${base}/usage`) ? 'usage' : 'catalog';
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 24, paddingInline: 24 }}>
        <Link to={base} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <img src="/portica.svg" alt="" width={24} height={24} />
          <Typography.Text strong style={{ color: '#fff', fontSize: 16 }}>{org} developer portal</Typography.Text>
        </Link>
        <Menu
          theme="dark"
          mode="horizontal"
          selectedKeys={[selected]}
          style={{ flex: 1, minWidth: 0 }}
          items={[
            { key: 'catalog', icon: <ApiOutlined />, label: <Link to={base}>APIs &amp; tools</Link> },
            { key: 'apps', icon: <AppstoreOutlined />, label: <Link to={`${base}/apps`}>My apps</Link> },
            { key: 'usage', icon: <LineChartOutlined />, label: <Link to={`${base}/usage`}>Usage</Link> },
          ]}
        />
        <Space>
          <Typography.Text style={{ color: 'rgba(255,255,255,0.75)' }}>{user.profile.name}</Typography.Text>
          <Button size="small" icon={<LogoutOutlined />} onClick={() => void logout()}>Sign out</Button>
        </Space>
      </Layout.Header>
      <Layout.Content style={{ padding: 24, maxWidth: 1280, width: '100%', margin: '0 auto' }}>
        <Outlet />
      </Layout.Content>
    </Layout>
  );
}
