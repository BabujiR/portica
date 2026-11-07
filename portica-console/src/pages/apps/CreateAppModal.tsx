import { useState } from 'react';
import { Button, Form, Input, InputNumber, Modal, Select } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { createApp, listDevelopers, listProducts } from '../../api/endpoints';
import type { IssuedCredential } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { RESOURCE_NAME_RULE, toResourceName } from '../../components/format';
import { useNotify } from '../../components/useNotify';
import { IssuedKeyModal } from './IssuedKeyModal';

interface AppForm {
  developer: string;
  displayName?: string;
  name: string;
  description?: string;
  callbackUrl?: string;
  apiProducts: string[];
  keyExpiresInDays?: number;
}

/** Registers an app (and its first key); then shows the key and secret once and opens the app. */
export function CreateAppModal({ open, developerEmail, onClose }: { open: boolean; developerEmail?: string; onClose: () => void }) {
  const { org } = useWorkspace();
  const notify = useNotify();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<AppForm>();
  const [issued, setIssued] = useState<{ credential: IssuedCredential; path: string } | null>(null);
  const developers = useQuery({ queryKey: ['developers', org], queryFn: () => listDevelopers(org), enabled: open && !developerEmail });
  const products = useQuery({ queryKey: ['products', org], queryFn: () => listProducts(org), enabled: open });

  const create = useMutation({
    mutationFn: (v: AppForm) => createApp(org, v.developer, {
      name: v.name,
      displayName: v.displayName || undefined,
      description: v.description || undefined,
      callbackUrl: v.callbackUrl || undefined,
      apiProducts: v.apiProducts ?? [],
      keyExpiresInSeconds: v.keyExpiresInDays ? v.keyExpiresInDays * 86_400 : undefined,
    }),
    onSuccess: (r) => {
      void queryClient.invalidateQueries({ queryKey: ['apps', org] });
      void queryClient.invalidateQueries({ queryKey: ['developer-apps', org, r.app.developerEmail] });
      onClose();
      setIssued({ credential: r.credential, path: `/o/${org}/apps/${encodeURIComponent(r.app.developerEmail)}/${r.app.name}` });
    },
    onError: (e) => notify.error(e, 'Couldn’t create the app'),
  });

  return (
    <>
      <Modal open={open} title="New app" onCancel={onClose} footer={null} destroyOnClose>
        <Form<AppForm>
          form={form}
          layout="vertical"
          preserve={false}
          initialValues={{ developer: developerEmail, apiProducts: [] }}
          onFinish={(v) => create.mutate(v)}
        >
          <Form.Item name="developer" label="Developer" rules={[{ required: true }]} hidden={!!developerEmail}>
            <Select showSearch loading={developers.isLoading} placeholder="Pick the owner"
              options={(developers.data?.items ?? []).map((d) => ({ value: d.email, label: `${d.firstName} ${d.lastName} <${d.email}>` }))} />
          </Form.Item>
          <Form.Item name="displayName" label="Display name">
            <Input placeholder="Mobile app" onChange={(e) => {
              if (!form.isFieldTouched('name')) form.setFieldValue('name', toResourceName(e.target.value));
            }} />
          </Form.Item>
          <Form.Item name="name" label="Name" rules={[{ required: true }, RESOURCE_NAME_RULE]}><Input placeholder="mobile-app" /></Form.Item>
          <Form.Item name="apiProducts" label="API products" extra="Products with manual approval stay pending until approved.">
            <Select mode="multiple" loading={products.isLoading} placeholder="What the app may call"
              options={(products.data?.items ?? []).map((p) => ({ value: p.name, label: `${p.displayName}${p.approvalType === 'MANUAL' ? ' (manual approval)' : ''}` }))} />
          </Form.Item>
          <Form.Item name="callbackUrl" label="Callback URL" rules={[{ type: 'url' }]}><Input placeholder="https://app.example.com/callback" /></Form.Item>
          <Form.Item name="keyExpiresInDays" label="Key expires after (days)" extra="Leave empty for keys that don't expire.">
            <InputNumber min={1} max={3650} />
          </Form.Item>
          <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={create.isPending}>Create app and key</Button>
        </Form>
      </Modal>
      <IssuedKeyModal
        credential={issued?.credential ?? null}
        onClose={() => {
          const path = issued?.path;
          setIssued(null);
          if (path) navigate(path);
        }}
      />
    </>
  );
}
