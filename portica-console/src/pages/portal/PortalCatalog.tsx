import { useMemo, useState } from 'react';
import { Card, Col, Empty, Flex, Input, Row, Space, Tag, Typography } from 'antd';
import { ApiOutlined, RobotOutlined, SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { getCatalog } from '../../api/portal';
import { Loadable, Page } from '../../components/Page';
import { searchCatalog } from './portalModel';

/** APIs, MCP servers and the products that grant them. */
export function PortalCatalog() {
  const { org = '' } = useParams();
  const catalog = useQuery({ queryKey: ['portal-catalog', org], queryFn: () => getCatalog(org) });
  const [q, setQ] = useState('');
  const shown = useMemo(() => catalog.data && searchCatalog(catalog.data, q), [catalog.data, q]);

  return (
    <Page title="APIs and tools" subtitle="Browse the documentation, try requests, then register an app to get a key.">
      <Input allowClear prefix={<SearchOutlined />} placeholder="Search APIs, tools and products" value={q}
        onChange={(e) => setQ(e.target.value)} style={{ maxWidth: 360, marginBottom: 16 }} />
      <Loadable loading={catalog.isLoading} error={catalog.error}>
        {shown && (
          <>
            <Typography.Title level={4}>APIs</Typography.Title>
            {shown.apis.length === 0 ? <Empty description="No APIs" /> : (
              <Row gutter={[16, 16]}>
                {shown.apis.map((a) => (
                  <Col key={a.name} xs={24} md={12} xl={8}>
                    <Link to={`/portal/${org}/apis/${a.name}`}>
                      <Card hoverable title={<Space><ApiOutlined />{a.displayName}</Space>} style={{ height: '100%' }}>
                        <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}>{a.description || 'No description'}</Typography.Paragraph>
                        <Flex wrap gap={4}>
                          {a.deployments.map((d) => <Tag key={d.environment} color="green">{d.environment}</Tag>)}
                          {a.deployments.length === 0 && <Tag>not live</Tag>}
                          {!a.documented && <Tag>no OpenAPI</Tag>}
                        </Flex>
                      </Card>
                    </Link>
                  </Col>
                ))}
              </Row>
            )}
            <Typography.Title level={4} style={{ marginTop: 32 }}>MCP servers</Typography.Title>
            {shown.mcpServers.length === 0 ? <Empty description="No MCP servers" /> : (
              <Row gutter={[16, 16]}>
                {shown.mcpServers.map((s) => (
                  <Col key={s.name} xs={24} md={12} xl={8}>
                    <Link to={`/portal/${org}/mcp/${s.name}`}>
                      <Card hoverable title={<Space><RobotOutlined />{s.displayName}</Space>} style={{ height: '100%' }}>
                        <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}>{s.description || 'No description'}</Typography.Paragraph>
                        <Flex wrap gap={4}>
                          <Tag color="purple">{s.type === 'OPENAPI' ? 'generated from an API' : 'remote server'}</Tag>
                          {s.endpoints.map((e) => <Tag key={e.environment} color="green">{e.environment}</Tag>)}
                        </Flex>
                      </Card>
                    </Link>
                  </Col>
                ))}
              </Row>
            )}
            <Typography.Title level={4} style={{ marginTop: 32 }}>Products</Typography.Title>
            <Row gutter={[16, 16]}>
              {shown.products.map((p) => (
                <Col key={p.name} xs={24} md={12} xl={8}>
                  <Card size="small" title={p.displayName} extra={p.approvalType === 'MANUAL' ? <Tag color="orange">needs approval</Tag> : <Tag color="green">instant</Tag>}>
                    <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>{p.description || '–'}</Typography.Paragraph>
                    <Space direction="vertical" size={2}>
                      <Typography.Text>Includes: {p.apis.join(', ') || '–'}</Typography.Text>
                      {p.quota && <Typography.Text>Quota: {p.quota}</Typography.Text>}
                      {p.scopes.length > 0 && <Typography.Text>Scopes: {p.scopes.join(', ')}</Typography.Text>}
                    </Space>
                  </Card>
                </Col>
              ))}
            </Row>
          </>
        )}
      </Loadable>
    </Page>
  );
}
