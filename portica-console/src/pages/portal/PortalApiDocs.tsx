import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Col, Descriptions, Empty, Flex, Form, Input, Row, Segmented, Select, Space, Table, Tag, Typography } from 'antd';
import { DownloadOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { errorMessage } from '../../api/client';
import { getCatalog, getDocs, tryIt, type PortalOperation, type TryItResponse } from '../../api/portal';
import { Loadable, Page } from '../../components/Page';
import { curlFor, fillPath, METHOD_COLORS, parseHeaders, pathParameters, sortOperations } from './portalModel';

interface TryForm {
  method: string;
  path: string;
  headers: string;
  body?: string;
  [param: string]: string | undefined;
}

/** Interactive documentation of one API: operations of the live revision, OpenAPI download and try-it. */
export function PortalApiDocs() {
  const { org = '', api = '' } = useParams();
  const catalog = useQuery({ queryKey: ['portal-catalog', org], queryFn: () => getCatalog(org) });
  const entry = catalog.data?.apis.find((a) => a.name === api);
  const [env, setEnv] = useState<string | undefined>();
  useEffect(() => {
    if (!env && entry?.deployments.length) setEnv(entry.deployments[0].environment);
  }, [entry, env]);
  const docs = useQuery({ queryKey: ['portal-docs', org, api, env], queryFn: () => getDocs(org, api, env as string), enabled: !!env });
  const [operation, setOperation] = useState<PortalOperation | null>(null);
  const [form] = Form.useForm<TryForm>();
  const [result, setResult] = useState<TryItResponse | null>(null);
  const send = useMutation({
    mutationFn: (v: TryForm) => tryIt(org, {
      environment: env as string, api, method: v.method, path: fillPath(v.path, v), headers: parseHeaders(v.headers ?? ''),
      body: v.body || undefined,
    }),
    onSuccess: (r) => setResult(r),
  });
  const operations = useMemo(() => sortOperations(docs.data?.operations ?? []), [docs.data]);
  const watched = Form.useWatch([], form) as TryForm | undefined;
  const params = pathParameters(watched?.path ?? operation?.path ?? '');

  const choose = (op: PortalOperation) => {
    setOperation(op);
    setResult(null);
    form.setFieldValue('method', op.method.toUpperCase());
    form.setFieldValue('path', op.path);
  };

  const download = () => {
    if (!docs.data?.openApiSpec) return;
    const blob = new Blob([docs.data.openApiSpec], { type: 'application/yaml' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${api}-openapi.yaml`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <Page
      title={entry?.displayName ?? api}
      subtitle={entry?.description}
      crumbs={[{ title: 'APIs and tools', to: `/portal/${org}` }, { title: api }]}
      extra={
        <>
          {entry && entry.deployments.length > 0 && (
            <Segmented value={env} onChange={(v) => { setEnv(String(v)); setResult(null); }}
              options={entry.deployments.map((d) => d.environment)} />
          )}
          <Button icon={<DownloadOutlined />} disabled={!docs.data?.openApiSpec} onClick={download}>OpenAPI</Button>
        </>
      }
    >
      <Loadable loading={catalog.isLoading || docs.isLoading} error={catalog.error ?? docs.error}>
        {!entry ? <Empty description="This API isn't published" /> : !env ? <Empty description="Not live in any environment yet" /> : docs.data && (
          <Row gutter={[16, 16]}>
            <Col xs={24} xl={12}>
              <Card title="Operations" size="small">
                <Descriptions size="small" column={1} style={{ marginBottom: 12 }}>
                  <Descriptions.Item label="Base URL"><Typography.Text code copyable>{docs.data.url}</Typography.Text></Descriptions.Item>
                  {docs.data.version && <Descriptions.Item label="Version">{docs.data.version}</Descriptions.Item>}
                  <Descriptions.Item label="Products">{entry.products.join(', ')}</Descriptions.Item>
                </Descriptions>
                {operations.length === 0 ? <Alert type="info" showIcon message="No OpenAPI document: you can still send requests below." /> : (
                  <Table<PortalOperation>
                    rowKey={(o) => `${o.method} ${o.path}`}
                    size="small"
                    pagination={false}
                    dataSource={operations}
                    onRow={(o) => ({ onClick: () => choose(o), style: { cursor: 'pointer' } })}
                    rowClassName={(o) => (operation && o.method === operation.method && o.path === operation.path ? 'ant-table-row-selected' : '')}
                    columns={[
                      { title: '', key: 'm', width: 90, render: (_, o) => <Tag color={METHOD_COLORS[o.method.toUpperCase()]}>{o.method.toUpperCase()}</Tag> },
                      { title: 'Path', dataIndex: 'path', render: (p: string) => <Typography.Text code>{p}</Typography.Text> },
                      { title: 'Summary', key: 's', render: (_, o) => o.summary || o.operationId },
                    ]}
                  />
                )}
              </Card>
            </Col>
            <Col xs={24} xl={12}>
              <Card title="Try it" size="small" extra={<Typography.Text type="secondary">sent through the gateway; your key's quota applies</Typography.Text>}>
                <Form<TryForm>
                  form={form}
                  layout="vertical"
                  initialValues={{ method: 'GET', path: '/', headers: 'x-api-key: ' }}
                  onFinish={(v) => send.mutate(v)}
                >
                  <Space.Compact style={{ width: '100%' }}>
                    <Form.Item name="method" noStyle>
                      <Select style={{ width: 110 }} options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => ({ value: m, label: m }))} />
                    </Form.Item>
                    <Form.Item name="path" noStyle rules={[{ required: true }]}>
                      <Input placeholder="/pets?limit=10" />
                    </Form.Item>
                  </Space.Compact>
                  {params.length > 0 && (
                    <Flex gap={8} wrap style={{ marginTop: 12 }}>
                      {params.map((p) => (
                        <Form.Item key={p} name={p} label={p} style={{ marginBottom: 0 }}><Input style={{ width: 160 }} /></Form.Item>
                      ))}
                    </Flex>
                  )}
                  <Form.Item name="headers" label="Headers (one per line)" style={{ marginTop: 12 }}
                    extra="Use your app's key, or Authorization: Bearer … for OAuth APIs.">
                    <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} spellCheck={false} />
                  </Form.Item>
                  <Form.Item name="body" label="Body">
                    <Input.TextArea autoSize={{ minRows: 2, maxRows: 10 }} spellCheck={false} placeholder='{"name": "Rex"}' />
                  </Form.Item>
                  <Space>
                    <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={send.isPending}>Send</Button>
                    {watched && (
                      <Typography.Text type="secondary" copyable={{ text: curlFor(docs.data.url, {
                        method: watched.method ?? 'GET', path: fillPath(watched.path ?? '/', watched), headers: parseHeaders(watched.headers ?? ''),
                        body: watched.body,
                      }) }}>copy as curl</Typography.Text>
                    )}
                  </Space>
                </Form>
                {send.error && <Alert style={{ marginTop: 12 }} type="error" showIcon message={errorMessage(send.error)} />}
                {result && (
                  <div style={{ marginTop: 16 }}>
                    <Space style={{ marginBottom: 8 }}>
                      <Tag color={result.status < 300 ? 'green' : result.status < 500 ? 'orange' : 'red'}>{result.status}</Tag>
                      <Typography.Text type="secondary">{result.durationMs} ms</Typography.Text>
                      {result.truncated && <Tag>truncated</Tag>}
                    </Space>
                    <pre className="portal-response">{Object.entries(result.headers).map(([k, v]) => `${k}: ${v}`).join('\n')}</pre>
                    <pre className="portal-response">{pretty(result.body)}</pre>
                  </div>
                )}
              </Card>
            </Col>
          </Row>
        )}
      </Loadable>
    </Page>
  );
}

function pretty(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}
