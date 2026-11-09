import { useState } from 'react';
import {
  Alert, Button, Card, Checkbox, Col, Descriptions, Flex, Form, Input, InputNumber, Radio, Row, Select, Space, Steps,
  Typography, Upload,
} from 'antd';
import { ApiOutlined, FileTextOutlined, UploadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { createProxy, createRevision, deployRevision, getRevision, importOpenApi, listTargetServers } from '../../api/endpoints';
import type { TargetSpec } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { RESOURCE_NAME_RULE, toResourceName } from '../../components/format';
import { NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';
import { presetBundle, type Security } from './presets';

interface WizardValues {
  source: 'blank' | 'openapi';
  spec?: string;
  name: string;
  displayName?: string;
  description?: string;
  basePath?: string;
  targetType: 'url' | 'server';
  url?: string;
  server?: string;
  path?: string;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  security: Security;
  cors: boolean;
  spikeArrest: boolean;
  quota: boolean;
  deployTo?: string;
}

const STEP_FIELDS: Array<Array<keyof WizardValues>> = [
  ['source', 'spec'],
  ['name', 'displayName', 'description', 'basePath'],
  ['targetType', 'url', 'server', 'path', 'connectTimeoutMs', 'readTimeoutMs'],
  ['security', 'cors', 'spikeArrest', 'quota'],
  ['deployTo'],
];

const BASE_PATH = /^(\/[A-Za-z0-9._~-]+)+$/;

/** Wizard: source (blank or OpenAPI), basics, target, starter policies, review and optional deployment. */
export function ProxyCreate() {
  const { org, env, environments, can } = useWorkspace();
  const navigate = useNavigate();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<WizardValues>();
  const [step, setStep] = useState(0);
  const source = Form.useWatch('source', form);
  const targetType = Form.useWatch('targetType', form);
  const values = Form.useWatch((all: WizardValues) => all, form) as WizardValues | undefined;
  const servers = useQuery({
    queryKey: ['target-servers', org, env],
    queryFn: () => listTargetServers(org, env as string),
    enabled: !!env,
  });

  const create = useMutation({
    mutationFn: async (v: WizardValues) => {
      const target: TargetSpec = v.targetType === 'server'
        ? { server: v.server, path: v.path || undefined }
        : { url: v.url, path: v.path || undefined };
      const preset = presetBundle({ security: v.security, cors: v.cors, spikeArrest: v.spikeArrest, quota: v.quota });
      let revision: number;
      if (v.source === 'openapi') {
        const imported = await importOpenApi(org, {
          name: v.name, displayName: v.displayName, basePath: v.basePath || undefined, target, spec: v.spec ?? '',
        });
        revision = imported.revision.revision;
        if (preset.policies.length > 0) {
          // The import creates a revision without policies; add them in a second revision with the same spec.
          const base = await getRevision(org, v.name, revision);
          const next = await createRevision(org, v.name, {
            basePath: base.basePath, target, openApiSpec: v.spec, description: 'Starter policies',
            policies: preset.policies, flows: preset.flows,
          });
          revision = next.revision;
        }
      } else {
        const created = await createProxy(org, {
          name: v.name,
          displayName: v.displayName,
          description: v.description,
          revision: {
            basePath: v.basePath as string,
            target,
            connectTimeoutMs: v.connectTimeoutMs,
            readTimeoutMs: v.readTimeoutMs,
            policies: preset.policies,
            flows: preset.flows,
          },
        });
        revision = created.revision.revision;
      }
      if (v.deployTo) await deployRevision(org, v.deployTo, v.name, revision);
      return { name: v.name, revision };
    },
    onSuccess: ({ name, revision }) => {
      void queryClient.invalidateQueries({ queryKey: ['proxies', org] });
      notify.success(`Created ${name} (revision ${revision})`);
      navigate(`/o/${org}/proxies/${name}`);
    },
    onError: (e) => notify.error(e, 'Couldn’t create the proxy'),
  });

  if (!can('PROXY_WRITE')) return <NoAccess what="creating API proxies" />;

  const next = async () => {
    try {
      await form.validateFields(STEP_FIELDS[step] as string[]);
      setStep((s) => s + 1);
    } catch {
      // field errors are shown inline
    }
  };

  const titles = ['Source', 'Basics', 'Target', 'Policies', 'Review'];

  return (
    <Page title="New API proxy" crumbs={[{ title: 'API Proxies', to: `/o/${org}/proxies` }, { title: 'New' }]}>
      <Card>
        <Steps current={step} items={titles.map((title) => ({ title }))} style={{ marginBottom: 32 }} size="small" />
        <Form<WizardValues>
          form={form}
          layout="vertical"
          style={{ maxWidth: 760 }}
          initialValues={{
            source: 'blank', targetType: 'url', security: 'apiKey', cors: false, spikeArrest: false, quota: true,
            deployTo: env ?? undefined,
          }}
          onFinish={(v) => create.mutate(v)}
          preserve
        >
          {/* Every step stays mounted so values survive going back and forth. */}
          <div hidden={step !== 0}>
            <Form.Item name="source" label="Start from">
              <Radio.Group style={{ width: '100%' }}>
                <Row gutter={16}>
                  <Col xs={24} md={12}>
                    <Radio.Button value="blank" style={{ height: 'auto', width: '100%', padding: 16 }}>
                      <Space align="start"><ApiOutlined style={{ fontSize: 22 }} />
                        <div><b>A backend URL</b><div style={{ fontSize: 12, whiteSpace: 'normal' }}>Reverse proxy for any HTTP API.</div></div>
                      </Space>
                    </Radio.Button>
                  </Col>
                  <Col xs={24} md={12}>
                    <Radio.Button value="openapi" style={{ height: 'auto', width: '100%', padding: 16 }}>
                      <Space align="start"><FileTextOutlined style={{ fontSize: 22 }} />
                        <div><b>An OpenAPI spec</b><div style={{ fontSize: 12, whiteSpace: 'normal' }}>Imports name, base path and operations.</div></div>
                      </Space>
                    </Radio.Button>
                  </Col>
                </Row>
              </Radio.Group>
            </Form.Item>
            {source === 'openapi' && (
              <>
                <Upload
                  accept=".json,.yaml,.yml"
                  maxCount={1}
                  showUploadList={false}
                  beforeUpload={(file) => {
                    void file.text().then((text) => form.setFieldValue('spec', text));
                    return false;
                  }}
                >
                  <Button icon={<UploadOutlined />} style={{ marginBottom: 12 }}>Load a file (JSON or YAML)</Button>
                </Upload>
                <Form.Item name="spec" label="OpenAPI 3 or Swagger 2 document" rules={[{ required: true, message: 'Paste or load a spec' }]}>
                  <Input.TextArea autoSize={{ minRows: 8, maxRows: 18 }} spellCheck={false} style={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 12 }} />
                </Form.Item>
              </>
            )}
          </div>

          <div hidden={step !== 1}>
            <Form.Item name="displayName" label="Display name">
              <Input placeholder="Petstore API" onChange={(e) => {
                if (!form.isFieldTouched('name')) form.setFieldValue('name', toResourceName(e.target.value));
              }} />
            </Form.Item>
            <Form.Item name="name" label="Name" rules={[{ required: true }, RESOURCE_NAME_RULE]} extra="Used in URLs and the API; can't be changed later.">
              <Input placeholder="petstore" />
            </Form.Item>
            <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
            <Form.Item
              name="basePath"
              label="Base path"
              rules={[
                { required: source !== 'openapi', message: 'A base path is required' },
                { pattern: BASE_PATH, message: 'Like /pets or /v1/pets (no trailing slash)' },
                { validator: (_, v?: string) => (v && /^\/oauth2(\/|$)/i.test(v) ? Promise.reject(new Error('/oauth2 is reserved for the gateway')) : Promise.resolve()) },
              ]}
              extra={source === 'openapi' ? 'Leave empty to use the spec’s server path.' : `Clients will call /${org}/<environment><base path>.`}
            >
              <Input placeholder="/pets" />
            </Form.Item>
          </div>

          <div hidden={step !== 2}>
            <Form.Item name="targetType" label="Backend">
              <Radio.Group options={[{ value: 'url', label: 'URL' }, { value: 'server', label: 'Target server (per environment)' }]} />
            </Form.Item>
            {targetType === 'server' ? (
              <Form.Item name="server" label="Target server" rules={[{ required: true }]}
                extra={`Resolved in each environment it's deployed to. Showing servers in ${env ?? 'the selected environment'}.`}>
                <Select showSearch loading={servers.isLoading}
                  options={(servers.data?.items ?? []).map((s) => ({ value: s.name, label: `${s.name} (${s.baseUri})` }))}
                  notFoundContent="No target servers in this environment" />
              </Form.Item>
            ) : (
              <Form.Item name="url" label="Target URL" rules={[{ required: true }, { pattern: /^https?:\/\/[^\s/:?#]+(:\d{1,5})?([/?#]\S*)?$/, message: 'http(s)://host[:port][/path]' }]}>
                <Input placeholder="https://petstore.example.com/v2" />
              </Form.Item>
            )}
            <Form.Item name="path" label="Path prefix on the backend" extra="Optional, e.g. /v2 (added before the request path).">
              <Input placeholder="/v2" />
            </Form.Item>
            {source !== 'openapi' && (
              <Flex gap={16}>
                <Form.Item name="connectTimeoutMs" label="Connect timeout (ms)"><InputNumber min={100} max={60000} placeholder="5000" /></Form.Item>
                <Form.Item name="readTimeoutMs" label="Read timeout (ms)"><InputNumber min={100} max={300000} placeholder="30000" /></Form.Item>
              </Flex>
            )}
          </div>

          <div hidden={step !== 3}>
            <Form.Item name="security" label="Who can call it">
              <Radio.Group>
                <Space direction="vertical">
                  <Radio value="apiKey">Apps with an API key <Typography.Text type="secondary">(VerifyAPIKey, x-api-key header)</Typography.Text></Radio>
                  <Radio value="oauth">Apps with an OAuth access token <Typography.Text type="secondary">(OAuthV2, client credentials)</Typography.Text></Radio>
                  <Radio value="none">Anyone <Typography.Text type="secondary">(no authentication)</Typography.Text></Radio>
                </Space>
              </Radio.Group>
            </Form.Item>
            <Form.Item name="quota" valuePropName="checked">
              <Checkbox>Quota: the app’s API product quota (or 1,000 requests a minute per client IP without authentication)</Checkbox>
            </Form.Item>
            <Form.Item name="spikeArrest" valuePropName="checked"><Checkbox>Spike arrest: at most 100 requests a second per client IP</Checkbox></Form.Item>
            <Form.Item name="cors" valuePropName="checked"><Checkbox>CORS for browser apps (any origin)</Checkbox></Form.Item>
            <Alert type="info" showIcon message="You can change all of this later in the flow editor." />
          </div>

          <div hidden={step !== 4}>
            {values && (
              <Descriptions bordered size="small" column={1} style={{ marginBottom: 24 }}>
                <Descriptions.Item label="Name">{values.displayName || values.name} ({values.name})</Descriptions.Item>
                <Descriptions.Item label="Source">{values.source === 'openapi' ? 'OpenAPI import' : 'Backend URL'}</Descriptions.Item>
                <Descriptions.Item label="Base path">{values.basePath || '(from the spec)'}</Descriptions.Item>
                <Descriptions.Item label="Target">{values.targetType === 'server' ? `Target server ${values.server ?? ''}` : values.url}{values.path ?? ''}</Descriptions.Item>
                <Descriptions.Item label="Policies">
                  {presetBundle({ security: values.security, cors: values.cors, spikeArrest: values.spikeArrest, quota: values.quota })
                    .policies.map((p) => p.type).join(' → ') || 'None'}
                </Descriptions.Item>
              </Descriptions>
            )}
            <Form.Item name="deployTo" label="Deploy revision 1 to" extra="Leave empty to deploy later.">
              <Select allowClear placeholder="Don't deploy yet"
                disabled={!can('PROXY_DEPLOY')}
                options={environments.map((e) => ({ value: e.name, label: e.displayName || e.name }))} />
            </Form.Item>
          </div>

          <Flex gap={8} style={{ marginTop: 24 }}>
            {step > 0 && <Button onClick={() => setStep((s) => s - 1)}>Back</Button>}
            {step < titles.length - 1 && <Button type="primary" onClick={() => void next()}>Next</Button>}
            {step === titles.length - 1 && (
              <Button type="primary" htmlType="submit" loading={create.isPending}>Create proxy</Button>
            )}
            <Button type="text" onClick={() => navigate(`/o/${org}/proxies`)}>Cancel</Button>
          </Flex>
        </Form>
      </Card>
    </Page>
  );
}
