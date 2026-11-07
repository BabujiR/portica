import { Button, Checkbox, Form, Input, Modal } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { createOrganization } from '../api/endpoints';
import { RESOURCE_NAME_RULE, toResourceName } from '../components/format';
import { useNotify } from '../components/useNotify';

interface OrgForm {
  displayName: string;
  name: string;
  description?: string;
  adminEmail?: string;
  createDefaultEnvironments: boolean;
}

/** Platform administrators only: creates an organization (optionally with dev/test/prod) and opens it. */
export function CreateOrganizationModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [form] = Form.useForm<OrgForm>();
  const navigate = useNavigate();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const create = useMutation({
    mutationFn: (v: OrgForm) => createOrganization({
      name: v.name,
      displayName: v.displayName,
      description: v.description || undefined,
      adminEmail: v.adminEmail || undefined,
      createDefaultEnvironments: v.createDefaultEnvironments,
    }),
    onSuccess: (o) => {
      void queryClient.invalidateQueries({ queryKey: ['me'] });
      void queryClient.invalidateQueries({ queryKey: ['organizations'] });
      onClose();
      notify.success(`Created ${o.displayName}`);
      navigate(`/o/${o.name}/dashboard`);
    },
    onError: (e) => notify.error(e, 'Couldn’t create the organization'),
  });
  return (
    <Modal open={open} title="New organization" footer={null} onCancel={onClose} destroyOnClose>
      <Form<OrgForm> form={form} layout="vertical" preserve={false} initialValues={{ createDefaultEnvironments: true }}
        onFinish={(v) => create.mutate(v)}>
        <Form.Item name="displayName" label="Display name" rules={[{ required: true }]}>
          <Input placeholder="Acme Corporation" onChange={(e) => {
            if (!form.isFieldTouched('name')) form.setFieldValue('name', toResourceName(e.target.value));
          }} />
        </Form.Item>
        <Form.Item name="name" label="Name" rules={[{ required: true }, RESOURCE_NAME_RULE]} extra="Appears in every gateway URL: /<name>/<environment>/...">
          <Input placeholder="acme" />
        </Form.Item>
        <Form.Item name="adminEmail" label="Organization admin" rules={[{ type: 'email' }]} extra="Defaults to you. Invited if they haven't signed in yet.">
          <Input placeholder="alice@portica.local" />
        </Form.Item>
        <Form.Item name="description" label="Description"><Input.TextArea autoSize={{ minRows: 2 }} /></Form.Item>
        <Form.Item name="createDefaultEnvironments" valuePropName="checked">
          <Checkbox>Create dev, test and prod environments</Checkbox>
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={create.isPending}>Create</Button>
      </Form>
    </Modal>
  );
}
