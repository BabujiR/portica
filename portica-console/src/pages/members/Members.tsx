import { useState } from 'react';
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tabs, Tag, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, UserAddOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { addMember, listMembers, listRoles, removeMember, updateMemberRoles } from '../../api/endpoints';
import type { Member, Role } from '../../api/types';
import { useWorkspace } from '../../app/Workspace';
import { fromNow } from '../../components/format';
import { Loadable, NoAccess, Page } from '../../components/Page';
import { useNotify } from '../../components/useNotify';

export function Members() {
  const { org, can, me } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const members = useQuery({ queryKey: ['members', org], queryFn: () => listMembers(org), enabled: can('MEMBER_READ') });
  const roles = useQuery({ queryKey: ['roles'], queryFn: listRoles, staleTime: Infinity });
  const [inviting, setInviting] = useState(false);
  const [editing, setEditing] = useState<Member | null>(null);
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['members', org] });
  const invite = useMutation({
    mutationFn: (v: { email: string; roles: string[] }) => addMember(org, v.email, v.roles),
    onSuccess: (m) => {
      invalidate();
      setInviting(false);
      notify.success(m.status === 'INVITED' ? `Invited ${m.email}; they join when they first sign in` : `Added ${m.email}`);
    },
    onError: (e) => notify.error(e),
  });
  const setRoles = useMutation({
    mutationFn: (v: { userId: string; roles: string[] }) => updateMemberRoles(org, v.userId, v.roles),
    onSuccess: () => {
      invalidate();
      setEditing(null);
    },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: (userId: string) => removeMember(org, userId),
    onSuccess: invalidate,
    onError: (e) => notify.error(e, 'Couldn’t remove the member'),
  });

  if (!can('MEMBER_READ')) return <NoAccess what="reading members" />;
  const manage = can('MEMBER_MANAGE');
  const roleOptions = (roles.data ?? []).map((r) => ({ value: r.code, label: r.displayName }));

  return (
    <Page
      title="Users & Roles"
      subtitle="Who can use this organization in the console and API, and what each role allows."
      extra={manage && <Button type="primary" icon={<UserAddOutlined />} onClick={() => setInviting(true)}>Add member</Button>}
    >
      <Tabs
        items={[
          {
            key: 'members',
            label: 'Members',
            children: (
              <Loadable loading={members.isLoading} error={members.error}>
                <Table<Member>
                  rowKey="userId"
                  dataSource={members.data?.items ?? []}
                  pagination={{ pageSize: 20, hideOnSinglePage: true }}
                  columns={[
                    {
                      title: 'Member', key: 'm', render: (_, m) => (
                        <div>
                          <b>{m.displayName || m.email}</b>{m.email === me.email && <Tag style={{ marginInlineStart: 8 }}>you</Tag>}
                          <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{m.email}</Typography.Text></div>
                        </div>
                      ),
                    },
                    { title: 'Roles', dataIndex: 'roles', render: (r: string[]) => r.map((x) => <Tag key={x} color="blue">{x}</Tag>) },
                    { title: 'Status', dataIndex: 'status', render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'orange'}>{s.toLowerCase()}</Tag> },
                    { title: 'Last sign-in', dataIndex: 'lastLoginAt', render: (v?: string) => fromNow(v) },
                    {
                      title: '', key: 'a', align: 'right', render: (_, m) => manage && (
                        <Space>
                          <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(m)} aria-label={`Change roles of ${m.email}`} />
                          <Popconfirm title={`Remove ${m.email}?`} okButtonProps={{ danger: true }} onConfirm={() => remove.mutate(m.userId)}>
                            <Button size="small" danger icon={<DeleteOutlined />} aria-label={`Remove ${m.email}`} />
                          </Popconfirm>
                        </Space>
                      ),
                    },
                  ]}
                />
              </Loadable>
            ),
          },
          {
            key: 'roles',
            label: 'Roles',
            children: (
              <Table<Role>
                rowKey="code"
                loading={roles.isLoading}
                dataSource={roles.data ?? []}
                pagination={false}
                columns={[
                  { title: 'Role', key: 'r', width: 260, render: (_, r) => <div><b>{r.displayName}</b><div><Typography.Text code>{r.code}</Typography.Text></div></div> },
                  { title: 'Description', dataIndex: 'description' },
                  { title: 'Permissions', dataIndex: 'permissions', render: (p: string[]) => p.map((x) => <Tag key={x} style={{ marginBottom: 4 }}>{x}</Tag>) },
                ]}
              />
            ),
          },
        ]}
      />
      <Modal open={inviting} title="Add a member" footer={null} onCancel={() => setInviting(false)} destroyOnClose>
        <Form<{ email: string; roles: string[] }> layout="vertical" initialValues={{ roles: ['VIEWER'] }} onFinish={(v) => invite.mutate(v)}>
          <Form.Item name="email" label="Email" rules={[{ required: true, type: 'email' }]}
            extra="If they haven't signed in to Portica yet, they're invited and join on first sign-in.">
            <Input />
          </Form.Item>
          <Form.Item name="roles" label="Roles" rules={[{ required: true }]}>
            <Select mode="multiple" options={roleOptions} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={invite.isPending}>Add</Button>
        </Form>
      </Modal>
      <Modal open={!!editing} title={`Roles of ${editing?.email ?? ''}`} footer={null} onCancel={() => setEditing(null)} destroyOnClose>
        {editing && (
          <Form<{ roles: string[] }> layout="vertical" initialValues={{ roles: editing.roles }}
            onFinish={(v) => setRoles.mutate({ userId: editing.userId, roles: v.roles })}>
            <Form.Item name="roles" label="Roles" rules={[{ required: true }]}><Select mode="multiple" options={roleOptions} /></Form.Item>
            <Button type="primary" htmlType="submit" loading={setRoles.isPending}>Save</Button>
          </Form>
        )}
      </Modal>
    </Page>
  );
}
