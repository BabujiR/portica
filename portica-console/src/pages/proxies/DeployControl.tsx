import { useState } from 'react';
import { Button, Select, Space } from 'antd';
import { CloudUploadOutlined } from '@ant-design/icons';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { deployRevision } from '../../api/endpoints';
import { useWorkspace } from '../../app/Workspace';
import { useNotify } from '../../components/useNotify';

/** Environment picker plus Deploy button for one proxy revision. */
export function DeployControl({ api, revision, size = 'middle' }: { api: string; revision: number; size?: 'small' | 'middle' }) {
  const { org, env, environments, can } = useWorkspace();
  const notify = useNotify();
  const queryClient = useQueryClient();
  const [target, setTarget] = useState<string | undefined>(env ?? undefined);
  const deploy = useMutation({
    mutationFn: (e: string) => deployRevision(org, e, api, revision),
    onSuccess: (d) => {
      notify.success(`Revision ${d.revision} is live in ${d.environment} at ${d.gatewayPath}`);
      void queryClient.invalidateQueries({ queryKey: ['proxy-deployments', org, api] });
      void queryClient.invalidateQueries({ queryKey: ['deployments', org] });
    },
    onError: (e) => notify.error(e, 'Deployment failed'),
  });
  if (!can('PROXY_DEPLOY')) return null;
  return (
    <Space.Compact size={size}>
      <Select
        aria-label="Environment"
        value={target}
        onChange={setTarget}
        style={{ minWidth: 120 }}
        options={environments.map((e) => ({ value: e.name, label: e.name }))}
      />
      <Button icon={<CloudUploadOutlined />} disabled={!target} loading={deploy.isPending} onClick={() => target && deploy.mutate(target)}>
        Deploy r{revision}
      </Button>
    </Space.Compact>
  );
}
