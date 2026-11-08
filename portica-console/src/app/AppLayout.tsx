import { useMemo, useState, type ReactNode } from 'react';
import { Avatar, Button, Dropdown, Flex, Layout, Menu, Select, Space, Switch, Tag, Tooltip, Typography, theme } from 'antd';
import type { MenuProps } from 'antd';
import {
  ApiOutlined, AppstoreOutlined, AuditOutlined, LineChartOutlined, RobotOutlined, ToolOutlined, CloudServerOutlined, ClusterOutlined, DashboardOutlined, DatabaseOutlined,
  LogoutOutlined, MobileOutlined, MoonOutlined, PlusOutlined, QuestionCircleOutlined, SafetyOutlined, ShareAltOutlined,
  SunOutlined, TeamOutlined, UserOutlined,
} from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { config } from '../config';
import type { Permission } from '../api/types';
import { CreateOrganizationModal } from './CreateOrganizationModal';
import { useThemeMode } from './ThemeProvider';
import { useWorkspace } from './Workspace';

interface NavItem {
  key: string;
  label: string;
  icon: ReactNode;
  permission?: Permission;
}

const NAV: Array<{ group?: string; items: NavItem[] }> = [
  {
    items: [
      { key: 'dashboard', label: 'Overview', icon: <DashboardOutlined /> },
      { key: 'analytics', label: 'Analytics', icon: <LineChartOutlined />, permission: 'ANALYTICS_READ' },
      { key: 'proxies', label: 'API Proxies', icon: <ApiOutlined />, permission: 'PROXY_READ' },
      { key: 'sharedflows', label: 'Shared Flows', icon: <ShareAltOutlined />, permission: 'PROXY_READ' },
    ],
  },
  {
    group: 'Publish',
    items: [
      { key: 'products', label: 'API Products', icon: <AppstoreOutlined />, permission: 'PRODUCT_READ' },
      { key: 'developers', label: 'Developers', icon: <TeamOutlined />, permission: 'DEVELOPER_READ' },
      { key: 'apps', label: 'Apps', icon: <MobileOutlined />, permission: 'DEVELOPER_READ' },
    ],
  },
  {
    group: 'AI',
    items: [
      { key: 'ai', label: 'AI Gateway', icon: <RobotOutlined />, permission: 'AI_READ' },
      { key: 'mcp', label: 'MCP Servers', icon: <ToolOutlined />, permission: 'MCP_READ' },
    ],
  },
  {
    group: 'Configure',
    items: [
      { key: 'environments', label: 'Environments', icon: <CloudServerOutlined />, permission: 'ENV_READ' },
      { key: 'target-servers', label: 'Target Servers', icon: <ClusterOutlined />, permission: 'ENV_READ' },
      { key: 'kvms', label: 'Key Value Maps', icon: <DatabaseOutlined />, permission: 'PROXY_READ' },
    ],
  },
  {
    group: 'Administer',
    items: [
      { key: 'members', label: 'Users & Roles', icon: <SafetyOutlined />, permission: 'MEMBER_READ' },
      { key: 'audit', label: 'Audit Log', icon: <AuditOutlined />, permission: 'AUDIT_READ' },
    ],
  },
];

/** Shell: navigation on the left, organization/environment switchers, theme toggle and user menu on top. */
export function AppLayout() {
  const { org, organizations, environments, env, setEnv, can, me } = useWorkspace();
  const { logout, user } = useAuth();
  const { mode, toggle } = useThemeMode();
  const { token } = theme.useToken();
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(false);
  const [creatingOrg, setCreatingOrg] = useState(false);

  const section = location.pathname.split('/')[3] ?? 'dashboard';

  const menuItems = useMemo<MenuProps['items']>(() => NAV.flatMap((block, i): NonNullable<MenuProps['items']> => {
    const visible = block.items.filter((item) => !item.permission || can(item.permission));
    if (visible.length === 0) return [];
    const entries = visible.map((item) => ({
      key: item.key,
      icon: item.icon,
      label: <Link to={`/o/${org}/${item.key}`}>{item.label}</Link>,
    }));
    return block.group
      ? [{ type: 'group' as const, key: `g${i}`, label: collapsed ? null : block.group, children: entries }]
      : entries;
  }), [can, org, collapsed]);

  const roles = me.memberships.find((m) => m.organization === org)?.roles ?? [];
  const name = me.displayName || user.profile.name || me.email;

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        width={232}
        theme={mode === 'dark' ? 'dark' : 'light'}
        style={{ borderRight: `1px solid ${token.colorBorderSecondary}` }}
      >
        <Link to={`/o/${org}/dashboard`} aria-label="Portica home">
          <Flex align="center" gap={10} style={{ height: 56, padding: collapsed ? '0 24px' : '0 20px' }}>
            <img src="/portica.svg" alt="" width={28} height={28} />
            {!collapsed && (
              <Typography.Text strong style={{ fontSize: 18, letterSpacing: 0.2 }}>Portica</Typography.Text>
            )}
          </Flex>
        </Link>
        <Menu
          mode="inline"
          theme={mode === 'dark' ? 'dark' : 'light'}
          selectedKeys={[section]}
          items={menuItems}
          style={{ borderInlineEnd: 0 }}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Header
          style={{
            background: token.colorBgContainer,
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
            padding: '0 20px',
            position: 'sticky',
            top: 0,
            zIndex: 10,
          }}
        >
          <Flex align="center" justify="space-between" gap={12} style={{ height: '100%' }}>
            <Space size="middle" wrap>
              <Tooltip title="Organization">
                <Select
                  aria-label="Organization"
                  value={org}
                  style={{ minWidth: 200 }}
                  showSearch
                  optionFilterProp="label"
                  options={organizations.map((o) => ({ value: o.name, label: o.displayName }))}
                  onChange={(value: string) => navigate(`/o/${value}/${section}`)}
                  dropdownRender={(menu) => (
                    <>
                      {menu}
                      {me.platformAdmin && (
                        <Button type="link" icon={<PlusOutlined />} block style={{ textAlign: 'start' }} onClick={() => setCreatingOrg(true)}>
                          New organization
                        </Button>
                      )}
                    </>
                  )}
                />
              </Tooltip>
              <Tooltip title="Environment (used by target servers, key value maps and deploy actions)">
                <Select
                  aria-label="Environment"
                  value={env ?? undefined}
                  placeholder="No environments"
                  style={{ minWidth: 140 }}
                  options={environments.map((e) => ({ value: e.name, label: e.displayName || e.name }))}
                  onChange={setEnv}
                />
              </Tooltip>
            </Space>
            <Space size="middle">
              <Tooltip title="API reference">
                <Button
                  type="text"
                  icon={<QuestionCircleOutlined />}
                  href={`${config.apiBaseUrl}/swagger-ui.html`}
                  target="_blank"
                  rel="noreferrer"
                  aria-label="API reference"
                />
              </Tooltip>
              <Switch
                checked={mode === 'dark'}
                onChange={toggle}
                checkedChildren={<MoonOutlined />}
                unCheckedChildren={<SunOutlined />}
                aria-label="Dark mode"
              />
              <Dropdown
                menu={{
                  items: [
                    {
                      key: 'who',
                      disabled: true,
                      label: (
                        <div>
                          <div><Typography.Text strong>{name}</Typography.Text></div>
                          <Typography.Text type="secondary">{me.email}</Typography.Text>
                          <div style={{ marginTop: 6 }}>
                            {me.platformAdmin && <Tag color="gold">Platform admin</Tag>}
                            {roles.map((r) => <Tag key={r}>{r}</Tag>)}
                          </div>
                        </div>
                      ),
                    },
                    { type: 'divider' },
                    { key: 'logout', icon: <LogoutOutlined />, label: 'Sign out', onClick: () => void logout() },
                  ],
                }}
                trigger={['click']}
              >
                <Button type="text" aria-label="Account">
                  <Space>
                    <Avatar size="small" icon={<UserOutlined />} />
                    <span className="hide-narrow">{name}</span>
                  </Space>
                </Button>
              </Dropdown>
            </Space>
          </Flex>
        </Layout.Header>
        <Layout.Content style={{ padding: 24, minWidth: 0 }}>
          <Outlet />
        </Layout.Content>
        <CreateOrganizationModal open={creatingOrg} onClose={() => setCreatingOrg(false)} />
      </Layout>
    </Layout>
  );
}
