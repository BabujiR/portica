import { useMemo, useState } from 'react';
import {
  Button, Col, Drawer, Flex, Form, Input, InputNumber, Popconfirm, Radio, Row, Select, Space, Switch, Table, Tag,
  Typography,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createProduct, deleteProduct, listProducts, listProxies, updateProduct } from '../../api/endpoints';
import type { ApiProduct, ApiProductRequest, ProductQuota } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { fromNow, RESOURCE_NAME_RULE, toResourceName } from '../../components/format';
import { fromPairs, KeyValueList, toPairs, type Pairs } from '../../components/KeyValueList';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';

interface ProductForm {
  name: string;
  displayName?: string;
  description?: string;
  approvalType: 'AUTO' | 'MANUAL';
  access: 'PUBLIC' | 'PRIVATE' | 'INTERNAL';
  environments: string[];
  proxies: string[];
  paths: string[];
  scopes: string[];
  quotaEnabled: boolean;
  quota?: ProductQuota;
  attributes: Pairs;
}

export function Products() {
  const { org, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const products = useQuery({ queryKey: ['products', org], queryFn: () => listProducts(org), enabled: can('PRODUCT_READ') });
  const [editing, setEditing] = useState<ApiProduct | 'new' | null>(null);
  const [q, setQ] = useState('');
  const remove = useMutation({
    mutationFn: (name: string) => deleteProduct(org, name),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['products', org] }),
    onError: (e) => notify.error(e, 'Couldn’t delete the product'),
  });
  const rows = useMemo(() => {
    const term = q.trim().toLowerCase();
    return (products.data?.items ?? []).filter((p) => !term || p.name.includes(term) || p.displayName.toLowerCase().includes(term));
  }, [products.data, q]);

  if (!can('PRODUCT_READ')) return <NoAccess what="reading API products" />;
  const writable = can('PRODUCT_WRITE');

  return (
    <Page
      title="API Products"
      subtitle="What apps subscribe to: a set of proxies (and paths) in some environments, with scopes and a quota."
      extra={writable && <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>New product</Button>}
    >
      <Input allowClear prefix={<SearchOutlined />} placeholder="Search products" value={q} onChange={(e) => setQ(e.target.value)}
        style={{ maxWidth: 320, marginBottom: 16 }} />
      <Loadable loading={products.isLoading} error={products.error}>
        <Table<ApiProduct>
          rowKey="id"
          dataSource={rows}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: 'No API products yet. A product is how apps get access to your proxies.' }}
          columns={[
            {
              title: 'Product', dataIndex: 'name', sorter: (a, b) => a.name.localeCompare(b.name),
              render: (_, p) => (
                <div>
                  <Typography.Text strong>{p.displayName}</Typography.Text>
                  <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{p.name}</Typography.Text></div>
                </div>
              ),
            },
            { title: 'Proxies', dataIndex: 'proxies', render: (v: string[]) => v.map((x) => <Tag key={x}>{x}</Tag>) },
            { title: 'Environments', dataIndex: 'environments', render: (v: string[]) => (v.length ? v.map((x) => <Tag key={x} color="blue">{x}</Tag>) : <Typography.Text type="secondary">all</Typography.Text>) },
            { title: 'Quota', dataIndex: 'quota', render: (q?: ProductQuota | null) => (q ? `${q.count.toLocaleString()} / ${q.interval > 1 ? `${q.interval} ` : ''}${q.timeUnit}${q.interval > 1 ? 's' : ''}` : '–') },
            { title: 'Approval', dataIndex: 'approvalType', render: (v: string) => <Tag color={v === 'AUTO' ? 'green' : 'orange'}>{v.toLowerCase()}</Tag> },
            { title: 'Updated', dataIndex: 'updatedAt', render: (v: string) => fromNow(v) },
            {
              title: '', key: 'actions', align: 'right', render: (_, p) => writable && (
                <Space>
                  <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(p)} aria-label={`Edit ${p.name}`} />
                  <Popconfirm title={`Delete ${p.name}?`} description="Refused while apps hold it." okButtonProps={{ danger: true }}
                    onConfirm={() => remove.mutate(p.name)}>
                    <Button size="small" danger icon={<DeleteOutlined />} aria-label={`Delete ${p.name}`} />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Loadable>
      <ProductDrawer editing={editing} onClose={() => setEditing(null)} />
    </Page>
  );
}

function ProductDrawer({ editing, onClose }: { editing: ApiProduct | 'new' | null; onClose: () => void }) {
  const { org, environments } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ProductForm>();
  const proxies = useQuery({ queryKey: ['proxies', org], queryFn: () => listProxies(org), enabled: !!editing });
  const quotaEnabled = Form.useWatch('quotaEnabled', form);
  const isNew = editing === 'new';
  const product = editing && editing !== 'new' ? editing : null;

  const save = useMutation({
    mutationFn: (v: ProductForm) => {
      const body: ApiProductRequest = {
        displayName: v.displayName || v.name,
        description: v.description,
        approvalType: v.approvalType,
        access: v.access,
        environments: v.environments ?? [],
        proxies: v.proxies ?? [],
        paths: v.paths ?? [],
        scopes: v.scopes ?? [],
        quota: v.quotaEnabled && v.quota ? v.quota : null,
        attributes: fromPairs(v.attributes),
      };
      return isNew ? createProduct(org, { ...body, name: v.name }) : updateProduct(org, product?.name as string, body);
    },
    onSuccess: (p) => {
      notify.success(`Saved ${p.name}`);
      void queryClient.invalidateQueries({ queryKey: ['products', org] });
      onClose();
    },
    onError: (e) => notify.error(e, 'Couldn’t save the product'),
  });

  return (
    <Drawer
      open={!!editing}
      width={620}
      title={isNew ? 'New API product' : `Edit ${product?.name ?? ''}`}
      onClose={onClose}
      destroyOnClose
      extra={<Button type="primary" loading={save.isPending} onClick={() => form.submit()}>Save</Button>}
    >
      <Form<ProductForm>
        form={form}
        layout="vertical"
        preserve={false}
        onFinish={(v) => save.mutate(v)}
        initialValues={product ? {
          name: product.name, displayName: product.displayName, description: product.description,
          approvalType: product.approvalType, access: product.access, environments: product.environments,
          proxies: product.proxies, paths: product.paths, scopes: product.scopes, quotaEnabled: !!product.quota,
          quota: product.quota ?? { count: 1000, interval: 1, timeUnit: 'hour' }, attributes: toPairs(product.attributes),
        } : {
          approvalType: 'AUTO', access: 'PUBLIC', environments: [], proxies: [], paths: [], scopes: [], quotaEnabled: true,
          quota: { count: 1000, interval: 1, timeUnit: 'hour' }, attributes: [],
        }}
      >
        <Row gutter={12}>
          <Col span={12}>
            <Form.Item name="displayName" label="Display name">
              <Input placeholder="Pets Gold" onChange={(e) => {
                if (isNew && !form.isFieldTouched('name')) form.setFieldValue('name', toResourceName(e.target.value));
              }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="name" label="Name" rules={[{ required: true }, RESOURCE_NAME_RULE]}>
              <Input disabled={!isNew} placeholder="pets-gold" />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
        <Form.Item name="proxies" label="API proxies" extra="Requests to these proxies are allowed for apps holding this product.">
          <Select mode="tags" placeholder="Pick or type proxy names" loading={proxies.isLoading}
            options={(proxies.data?.items ?? []).map((p) => ({ value: p.name, label: p.name }))} />
        </Form.Item>
        <Form.Item name="paths" label="Paths (optional)" extra="Limit to paths inside those proxies: * matches one segment, ** any number. Empty means all.">
          <Select mode="tags" placeholder="/pets/**" open={false} suffixIcon={null} />
        </Form.Item>
        <Form.Item name="environments" label="Environments" extra="Empty means every environment.">
          <Select mode="multiple" allowClear placeholder="All environments"
            options={environments.map((e) => ({ value: e.name, label: e.name }))} />
        </Form.Item>
        <Form.Item name="scopes" label="OAuth scopes" extra="Scopes apps may request in client-credentials tokens.">
          <Select mode="tags" placeholder="pets.read" open={false} suffixIcon={null} />
        </Form.Item>
        <Flex gap={24} wrap>
          <Form.Item name="approvalType" label="Approval">
            <Radio.Group optionType="button" options={[{ value: 'AUTO', label: 'Automatic' }, { value: 'MANUAL', label: 'Manual' }]} />
          </Form.Item>
          <Form.Item name="access" label="Portal visibility">
            <Radio.Group optionType="button" options={[
              { value: 'PUBLIC', label: 'Public' }, { value: 'PRIVATE', label: 'Private' }, { value: 'INTERNAL', label: 'Internal' },
            ]} />
          </Form.Item>
        </Flex>
        <Form.Item name="quotaEnabled" label="Quota" valuePropName="checked"
          extra="Enforced by Quota policies with useProductQuota, per app.">
          <Switch />
        </Form.Item>
        {quotaEnabled && (
          <Space.Compact style={{ marginBottom: 24 }}>
            <Form.Item name={['quota', 'count']} noStyle rules={[{ required: true }]}>
              <InputNumber min={1} placeholder="requests" style={{ width: 160 }} />
            </Form.Item>
            <Form.Item noStyle><Input disabled value="per" style={{ width: 56, textAlign: 'center', pointerEvents: 'none' }} /></Form.Item>
            <Form.Item name={['quota', 'interval']} noStyle><InputNumber min={1} style={{ width: 80 }} /></Form.Item>
            <Form.Item name={['quota', 'timeUnit']} noStyle>
              <Select style={{ width: 120 }} options={['second', 'minute', 'hour', 'day', 'week'].map((u) => ({ value: u, label: u }))} />
            </Form.Item>
          </Space.Compact>
        )}
        <Form.Item name="attributes" label="Attributes" extra="Available to policies as apiproduct.attribute.<name>.">
          <KeyValueList />
        </Form.Item>
      </Form>
    </Drawer>
  );
}
