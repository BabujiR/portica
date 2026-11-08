import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError, errorMessage } from '../../api/client';
import {
  createSharedFlow, createSharedFlowRevision, deleteSharedFlow, deploySharedFlow, listSharedFlowDeployments,
  listSharedFlowRevisions, listSharedFlows, undeploySharedFlow,
} from '../../api/endpoints';
import type { SharedFlow, SharedFlowDeployment } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { fromNow, RESOURCE_NAME_RULE } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';
import { useUnsavedChanges } from '../../components/useUnsavedChanges';
import { FlowEditor } from '../flow/FlowEditor';
import { cleanSteps, EMPTY_FLOWS, type Bundle } from '../flow/model';

export function SharedFlowList() {
  const { org, can } = useWorkspace();
  const navigate = useNavigate();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const flows = useQuery({ queryKey: ['sharedflows', org], queryFn: () => listSharedFlows(org), enabled: can('PROXY_READ') });
  const [creating, setCreating] = useState(false);
  const create = useMutation({
    mutationFn: (v: { name: string; displayName?: string; description?: string }) => createSharedFlow(org, {
      ...v,
      // A shared flow needs at least one step: start with a harmless spike arrest the user can replace.
      revision: {
        description: 'Initial revision',
        policies: [{ name: 'spike-arrest', type: 'SpikeArrest', config: { rate: '1000ps' } }],
        steps: [{ policy: 'spike-arrest' }],
      },
    }),
    onSuccess: (r) => {
      void queryClient.invalidateQueries({ queryKey: ['sharedflows', org] });
      navigate(`/o/${org}/sharedflows/${r.sharedFlow.name}`);
    },
    onError: (e) => notify.error(e),
  });

  if (!can('PROXY_READ')) return <NoAccess what="reading shared flows" />;
  return (
    <Page
      title="Shared Flows"
      subtitle="Reusable policy sequences, deployed per environment and called from proxies with FlowCallout."
      extra={can('PROXY_WRITE') && <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>New shared flow</Button>}
    >
      <Loadable loading={flows.isLoading} error={flows.error}>
        <Table<SharedFlow>
          rowKey="id"
          dataSource={flows.data?.items ?? []}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: 'No shared flows yet.' }}
          columns={[
            { title: 'Name', dataIndex: 'name', render: (_, f) => <Link to={`/o/${org}/sharedflows/${f.name}`}><b>{f.displayName}</b></Link> },
            { title: 'Latest revision', dataIndex: 'latestRevision', render: (r: number) => `r${r}` },
            { title: 'Description', dataIndex: 'description' },
            { title: 'Updated', dataIndex: 'updatedAt', render: (v: string) => fromNow(v) },
          ]}
        />
      </Loadable>
      <Modal open={creating} title="New shared flow" onCancel={() => setCreating(false)} footer={null} destroyOnClose>
        <Form layout="vertical" onFinish={(v) => create.mutate(v)}>
          <Form.Item name="name" label="Name" rules={[{ required: true }, RESOURCE_NAME_RULE]}><Input placeholder="security" /></Form.Item>
          <Form.Item name="displayName" label="Display name"><Input /></Form.Item>
          <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={create.isPending}>Create</Button>
        </Form>
      </Modal>
    </Page>
  );
}

export function SharedFlowDetail() {
  const { org, environments, env, can } = useWorkspace();
  const { name = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const revisions = useQuery({ queryKey: ['sharedflow-revisions', org, name], queryFn: () => listSharedFlowRevisions(org, name) });
  const deployments = useQuery({ queryKey: ['sharedflow-deployments', org, name], queryFn: () => listSharedFlowDeployments(org, name) });
  const latest = revisions.data?.items.reduce((a, b) => (b.revision > a.revision ? b : a), revisions.data.items[0]);
  const [bundle, setBundle] = useState<Bundle | null>(null);
  const [dirty, setDirty] = useState(false);
  const [problems, setProblems] = useState<string[]>([]);
  const [deployEnv, setDeployEnv] = useState<string | undefined>(env ?? undefined);
  const readOnly = !can('PROXY_WRITE');
  useUnsavedChanges(dirty);

  useEffect(() => {
    if (latest) {
      setBundle({ policies: latest.policies, flows: EMPTY_FLOWS, steps: latest.steps });
      setDirty(false);
    }
  }, [latest]);

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['sharedflow-revisions', org, name] });
    void queryClient.invalidateQueries({ queryKey: ['sharedflow-deployments', org, name] });
    void queryClient.invalidateQueries({ queryKey: ['sharedflows', org] });
  };
  const save = useMutation({
    mutationFn: () => createSharedFlowRevision(org, name, { policies: bundle?.policies ?? [], steps: cleanSteps(bundle?.steps ?? []) }),
    onSuccess: (r) => {
      notify.success(`Saved revision ${r.revision}`);
      setProblems([]);
      invalidate();
    },
    onError: (e) => setProblems(e instanceof ApiError && e.details.length ? e.details : [errorMessage(e)]),
  });
  const deploy = useMutation({
    mutationFn: ({ e, rev }: { e: string; rev: number }) => deploySharedFlow(org, e, name, rev),
    onSuccess: (d) => {
      notify.success(`Revision ${d.revision} deployed to ${d.environment}`);
      invalidate();
    },
    onError: (e) => notify.error(e, 'Deployment failed'),
  });
  const undeploy = useMutation({
    mutationFn: (d: SharedFlowDeployment) => undeploySharedFlow(org, d.environment, name, d.revision),
    onSuccess: invalidate,
    onError: (e) => notify.error(e, 'Couldn’t undeploy'),
  });
  const remove = useMutation({
    mutationFn: () => deleteSharedFlow(org, name),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['sharedflows', org] });
      navigate(`/o/${org}/sharedflows`);
    },
    onError: (e) => notify.error(e, 'Couldn’t delete'),
  });

  return (
    <Page
      title={name}
      subtitle={latest && `latest revision ${latest.revision}`}
      crumbs={[{ title: 'Shared Flows', to: `/o/${org}/sharedflows` }, { title: name }]}
      extra={
        <>
          {can('PROXY_DEPLOY') && latest && (
            <Space.Compact>
              <Select value={deployEnv} onChange={setDeployEnv} style={{ minWidth: 120 }} aria-label="Environment"
                options={environments.map((e) => ({ value: e.name, label: e.name }))} />
              <Button disabled={!deployEnv} loading={deploy.isPending}
                onClick={() => deployEnv && deploy.mutate({ e: deployEnv, rev: latest.revision })}>Deploy r{latest.revision}</Button>
            </Space.Compact>
          )}
          {!readOnly && (
            <Button type="primary" icon={<SaveOutlined />} disabled={!dirty} loading={save.isPending}
              onClick={() => save.mutate(undefined, { onSuccess: () => setDirty(false) })}>
              Save as new revision
            </Button>
          )}
        </>
      }
    >
      <Loadable loading={revisions.isLoading || !bundle} error={revisions.error}>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {problems.length > 0 && (
            <Alert type="error" showIcon closable onClose={() => setProblems([])} message="The revision wasn't saved"
              description={<ul style={{ margin: 0, paddingInlineStart: 18 }}>{problems.map((p) => <li key={p}>{p}</li>)}</ul>} />
          )}
          <Card size="small" title="Deployments">
            <Table<SharedFlowDeployment>
              size="small"
              rowKey="environment"
              dataSource={deployments.data ?? []}
              pagination={false}
              locale={{ emptyText: 'Not deployed. Proxies calling it can only be deployed where it is.' }}
              columns={[
                { title: 'Environment', dataIndex: 'environment', render: (e: string) => <Tag color="green">{e}</Tag> },
                { title: 'Revision', dataIndex: 'revision', render: (r: number) => `r${r}` },
                { title: 'Deployed', dataIndex: 'deployedAt', render: (v: string) => fromNow(v) },
                {
                  title: '', key: 'a', align: 'right', render: (_, d) => can('PROXY_DEPLOY') && (
                    <Popconfirm title={`Undeploy from ${d.environment}?`} onConfirm={() => undeploy.mutate(d)}>
                      <Button size="small" danger>Undeploy</Button>
                    </Popconfirm>
                  ),
                },
              ]}
            />
          </Card>
          {bundle && (
            <FlowEditor mode="sharedFlow" value={bundle} readOnly={readOnly} onChange={(b) => { setBundle(b); setDirty(true); }} />
          )}
          {!readOnly && (
            <Popconfirm title={`Delete ${name}?`} description="Refused while it is deployed." okButtonProps={{ danger: true }} onConfirm={() => remove.mutate()}>
              <Button danger icon={<DeleteOutlined />}>Delete shared flow</Button>
            </Popconfirm>
          )}
          <Typography.Text type="secondary">
            Call it from a proxy with a FlowCallout policy: <Typography.Text code>{`{"sharedFlow": "${name}"}`}</Typography.Text>
          </Typography.Text>
        </Space>
      </Loadable>
    </Page>
  );
}
