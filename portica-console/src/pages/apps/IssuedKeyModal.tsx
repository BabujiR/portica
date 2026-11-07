import { Alert, Descriptions, Modal, Tag, Typography } from 'antd';
import type { IssuedCredential } from '../../api/types';
import { config } from '../../config';
import { useWorkspace } from '../../app/Workspace';

/** Shows a new consumer key and secret: the only time Portica can display the secret. */
export function IssuedKeyModal({ credential, onClose }: { credential: IssuedCredential | null; onClose: () => void }) {
  const { org, env } = useWorkspace();
  return (
    <Modal
      open={!!credential}
      title="Copy the new credentials now"
      onOk={onClose}
      onCancel={onClose}
      okText="I've stored them"
      cancelButtonProps={{ style: { display: 'none' } }}
      maskClosable={false}
      width={640}
    >
      {credential && (
        <>
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="The secret is shown only once"
            description="Portica keeps only a hash of it. If it's lost, issue a new key and revoke this one."
          />
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="Consumer key">
              <Typography.Text code copyable style={{ wordBreak: 'break-all' }}>{credential.consumerKey}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Consumer secret">
              <Typography.Text code copyable style={{ wordBreak: 'break-all' }}>{credential.consumerSecret}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Expires">{credential.expiresAt ?? 'Never'}</Descriptions.Item>
            <Descriptions.Item label="API products">
              {credential.apiProducts.length
                ? credential.apiProducts.map((g) => <Tag key={g.apiProduct} color={g.status === 'APPROVED' ? 'green' : 'orange'}>{g.apiProduct} · {g.status.toLowerCase()}</Tag>)
                : 'None'}
            </Descriptions.Item>
          </Descriptions>
          <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 4 }}>Get an access token{env ? ` in ${env}` : ''}:</Typography.Paragraph>
          <Typography.Paragraph code copyable style={{ fontSize: 12, wordBreak: 'break-all' }}>
            {`curl -u '${credential.consumerKey}:${credential.consumerSecret}' -d grant_type=client_credentials ${config.gatewayUrl}/${org}/${env ?? '<env>'}/oauth2/token`}
          </Typography.Paragraph>
        </>
      )}
    </Modal>
  );
}
