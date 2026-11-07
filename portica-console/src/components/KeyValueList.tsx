import { Button, Flex, Input } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';

export type Pairs = Array<{ key: string; value: string }>;

export function toPairs(map?: Record<string, string> | null): Pairs {
  return Object.entries(map ?? {}).map(([key, value]) => ({ key, value }));
}

export function fromPairs(pairs?: Pairs): Record<string, string> {
  const out: Record<string, string> = {};
  (pairs ?? []).forEach((p) => {
    if (p.key?.trim()) out[p.key.trim()] = p.value ?? '';
  });
  return out;
}

/** Controlled editor for string attributes (use as a Form.Item child). */
export function KeyValueList({ value = [], onChange, disabled }: {
  value?: Pairs;
  onChange?: (pairs: Pairs) => void;
  disabled?: boolean;
}) {
  const set = (next: Pairs) => onChange?.(next);
  return (
    <div>
      {value.map((p, i) => (
        <Flex key={i} gap={8} style={{ marginBottom: 8 }}>
          <Input placeholder="name" value={p.key} disabled={disabled} aria-label="Attribute name"
            onChange={(e) => set(value.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))} />
          <Input placeholder="value" value={p.value} disabled={disabled} aria-label="Attribute value"
            onChange={(e) => set(value.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
          <Button icon={<DeleteOutlined />} disabled={disabled} aria-label="Remove attribute"
            onClick={() => set(value.filter((_, j) => j !== i))} />
        </Flex>
      ))}
      <Button type="dashed" icon={<PlusOutlined />} disabled={disabled} onClick={() => set([...value, { key: '', value: '' }])}>
        Add attribute
      </Button>
    </div>
  );
}
