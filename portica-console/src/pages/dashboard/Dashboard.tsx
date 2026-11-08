import { Card, Col, List, Row, Statistic, Steps, Tag, Typography } from 'antd';
import { ApiOutlined, AppstoreOutlined, MobileOutlined, TeamOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listAllApps, listDevelopers, listEnvironmentDeployments, listProducts, listProxies } from '../../api/endpoints';
import { useWorkspace } from '../../app/Workspace';
import { config } from '../../config';
import { fromNow } from '../../components/format';
import { Page } from '../../components/Page';

/** Organization overview: counts, what's live in the selected environment, and a getting-started path. */
export function Dashboard() {
  const { org, env, can, organizations } = useWorkspace();
  const proxies = useQuery({ queryKey: ['proxies', org], queryFn: () => listProxies(org), enabled: can('PROXY_READ') });
  const products = useQuery({ queryKey: ['products', org], queryFn: () => listProducts(org), enabled: can('PRODUCT_READ') });
  const developers = useQuery({ queryKey: ['developers', org], queryFn: () => listDevelopers(org), enabled: can('DEVELOPER_READ') });
  const apps = useQuery({ queryKey: ['apps', org], queryFn: () => listAllApps(org), enabled: can('DEVELOPER_READ') });
  const deployments = useQuery({
    queryKey: ['deployments', org, env],
    queryFn: () => listEnvironmentDeployments(org, env as string),
    enabled: !!env && can('PROXY_READ'),
  });
  const orgName = organizations.find((o) => o.name === org)?.displayName ?? org;

  const stats = [
    { title: 'API proxies', value: proxies.data?.totalElements, icon: <ApiOutlined />, to: 'proxies', loading: proxies.isLoading, show: can('PROXY_READ') },
    { title: 'API products', value: products.data?.totalElements, icon: <AppstoreOutlined />, to: 'products', loading: products.isLoading, show: can('PRODUCT_READ') },
    { title: 'Developers', value: developers.data?.totalElements, icon: <TeamOutlined />, to: 'developers', loading: developers.isLoading, show: can('DEVELOPER_READ') },
    { title: 'Apps', value: apps.data?.totalElements, icon: <MobileOutlined />, to: 'apps', loading: apps.isLoading, show: can('DEVELOPER_READ') },
  ].filter((s) => s.show);

  const step = !proxies.data?.totalElements ? 0 : !deployments.data?.length ? 1 : !products.data?.totalElements ? 2 : !apps.data?.totalElements ? 3 : 4;

  return (
    <Page title={orgName} subtitle={`Organization overview${env ? ` · environment ${env}` : ''}`}>
      <Row gutter={[16, 16]}>
        {stats.map((s) => (
          <Col key={s.title} xs={12} md={6}>
            <Link to={`/o/${org}/${s.to}`}>
              <Card hoverable loading={s.loading}>
                <Statistic title={s.title} value={s.value ?? 0} prefix={s.icon} />
              </Card>
            </Link>
          </Col>
        ))}
        <Col xs={24} lg={14}>
          <Card title={`Live in ${env ?? '–'}`}>
            <List
              loading={deployments.isLoading}
              dataSource={deployments.data ?? []}
              locale={{ emptyText: 'Nothing deployed in this environment yet.' }}
              renderItem={(d) => (
                <List.Item extra={<Typography.Text type="secondary">{fromNow(d.deployedAt)}</Typography.Text>}>
                  <List.Item.Meta
                    title={<span><Link to={`/o/${org}/proxies/${d.apiProxy}`}>{d.apiProxy}</Link> <Tag color="green">r{d.revision}</Tag></span>}
                    description={<Typography.Text code copyable>{`${config.gatewayUrl}${d.gatewayPath}`}</Typography.Text>}
                  />
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title="Getting started">
            <Steps
              direction="vertical"
              size="small"
              current={step}
              items={[
                { title: <Link to={`/o/${org}/proxies/new`}>Create an API proxy</Link>, description: 'From a backend URL or an OpenAPI spec.' },
                { title: 'Deploy it', description: 'Pick an environment; the gateway picks it up within seconds.' },
                { title: <Link to={`/o/${org}/products`}>Bundle it in an API product</Link>, description: 'Proxies, scopes and a quota apps can subscribe to.' },
                { title: <Link to={`/o/${org}/developers`}>Register a developer and an app</Link>, description: 'The app gets a key and secret.' },
                { title: 'Call it', description: 'With the API key, or an OAuth token from /oauth2/token.' },
              ]}
            />
          </Card>
        </Col>
      </Row>
    </Page>
  );
}
