import { useEffect, useMemo, useRef, useState, type DragEvent, type ReactNode } from 'react';
import {
  Badge, Button, Card, Col, Drawer, Empty, Flex, Form, Input, Modal, Popconfirm, Row, Space, Switch, Tag, Tooltip,
  Typography, theme,
} from 'antd';
import {
  ArrowRightOutlined, CloseOutlined, DeleteOutlined, DownOutlined, FilterOutlined, HolderOutlined, PlusOutlined,
  SearchOutlined, UpOutlined, WarningOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { listPolicyTypes } from '../../api/endpoints';
import type { FlowStep, PolicyDefinition, PolicyType } from '../../api/types';
import {
  deletePolicy, getSteps, insertStep, laneKey, moveStep, POLICY_NAME, removeStep, uniquePolicyName, updateStep,
  upsertPolicy, usageCount, type Bundle, type Lane,
} from './model';
import { CATEGORY_COLORS, templateFor } from './templates';

type DragPayload =
  | { kind: 'new'; type: string }
  | { kind: 'existing'; policy: string }
  | { kind: 'step'; lane: Lane; index: number };

const DND_MIME = 'application/x-portica-flow';

export interface FlowEditorProps {
  /** `proxy`: PreFlow, conditional flows, PostFlow and fault rules. `sharedFlow`: one list of steps. */
  mode: 'proxy' | 'sharedFlow';
  value: Bundle;
  onChange: (bundle: Bundle) => void;
  readOnly?: boolean;
}

/**
 * Drag-and-drop editor for policy bundles. Drag a policy type from the palette onto a flow to create
 * a policy and its step, drag an existing policy to run it again elsewhere, and drag steps to reorder
 * or move them. Clicking a step opens the policy; the filter icon edits the step's condition. Every
 * action also has a button, so the editor works without a mouse.
 */
export function FlowEditor({ mode, value, onChange, readOnly = false }: FlowEditorProps) {
  const types = useQuery({ queryKey: ['policy-types'], queryFn: listPolicyTypes, staleTime: Infinity });
  const typeByName = useMemo(() => new Map((types.data ?? []).map((t) => [t.type, t])), [types.data]);
  const drag = useRef<DragPayload | null>(null);
  const [editing, setEditing] = useState<{ policy: PolicyDefinition; isNew: boolean } | null>(null);
  const [conditionFor, setConditionFor] = useState<{ lane: Lane; index: number; step: FlowStep } | null>(null);
  const [addTo, setAddTo] = useState<Lane | null>(null);

  const policyByName = useMemo(() => new Map(value.policies.map((p) => [p.name, p])), [value.policies]);

  /** Adds a new policy of {@code type} and a step running it. */
  function addNewPolicy(type: string, lane: Lane, index?: number) {
    const name = uniquePolicyName(type, value.policies.map((p) => p.name));
    const policy: PolicyDefinition = { name, type, config: templateFor(type) };
    onChange(insertStep(upsertPolicy(value, policy), lane, { policy: name }, index));
    setEditing({ policy, isNew: true });
  }

  function drop(lane: Lane, index?: number) {
    const payload = drag.current;
    drag.current = null;
    if (!payload || readOnly) return;
    if (payload.kind === 'new') addNewPolicy(payload.type, lane, index);
    else if (payload.kind === 'existing') onChange(insertStep(value, lane, { policy: payload.policy }, index));
    else onChange(moveStep(value, payload.lane, payload.index, lane, index ?? getSteps(value.flows, lane, value.steps).length));
  }

  const startDrag = (payload: DragPayload) => (e: DragEvent) => {
    drag.current = payload;
    e.dataTransfer.effectAllowed = payload.kind === 'step' ? 'move' : 'copy';
    e.dataTransfer.setData(DND_MIME, JSON.stringify(payload)); // required by some browsers to start a drag
  };

  const lane = (l: Lane, title: string, hint?: string) => (
    <LaneView
      key={laneKey(l)}
      lane={l}
      title={title}
      hint={hint}
      steps={getSteps(value.flows, l, value.steps)}
      policies={policyByName}
      types={typeByName}
      readOnly={readOnly}
      onDrop={drop}
      onStepDragStart={(index) => startDrag({ kind: 'step', lane: l, index })}
      onOpen={(name) => {
        const p = policyByName.get(name);
        if (p) setEditing({ policy: p, isNew: false });
      }}
      onRemove={(index) => onChange(removeStep(value, l, index))}
      onCondition={(index, step) => setConditionFor({ lane: l, index, step })}
      onAdd={() => setAddTo(l)}
    />
  );

  return (
    <Row gutter={16} wrap>
      <Col xs={24} lg={6}>
        <Palette
          types={types.data ?? []}
          loading={types.isLoading}
          readOnly={readOnly}
          onDragStart={(type) => startDrag({ kind: 'new', type })}
        />
        <PoliciesPanel
          bundle={value}
          types={typeByName}
          readOnly={readOnly}
          onOpen={(p) => setEditing({ policy: p, isNew: false })}
          onDelete={(name) => onChange(deletePolicy(value, name))}
          onDragStart={(policy) => startDrag({ kind: 'existing', policy })}
        />
      </Col>
      <Col xs={24} lg={18}>
        {mode === 'sharedFlow' ? (
          <Card size="small" title="Shared flow steps">
            {lane({ kind: 'sharedFlow' }, 'Steps', 'Run in order wherever a FlowCallout calls this shared flow')}
          </Card>
        ) : (
          <ProxyFlowsView value={value} onChange={onChange} readOnly={readOnly} lane={lane} />
        )}
      </Col>

      <PolicyDrawer
        editing={editing}
        bundle={value}
        types={typeByName}
        readOnly={readOnly}
        onClose={() => setEditing(null)}
        onSave={(policy, previousName) => {
          onChange(upsertPolicy(value, policy, previousName));
          setEditing(null);
        }}
      />
      <ConditionModal
        target={conditionFor}
        readOnly={readOnly}
        onClose={() => setConditionFor(null)}
        onSave={(condition) => {
          if (!conditionFor) return;
          onChange(updateStep(value, conditionFor.lane, conditionFor.index, { ...conditionFor.step, condition: condition || null }));
          setConditionFor(null);
        }}
      />
      <AddStepModal
        lane={addTo}
        bundle={value}
        types={types.data ?? []}
        onClose={() => setAddTo(null)}
        onAddExisting={(l, policy) => {
          onChange(insertStep(value, l, { policy }));
          setAddTo(null);
        }}
        onAddNew={(l, type) => {
          setAddTo(null);
          addNewPolicy(type, l);
        }}
      />
    </Row>
  );
}

// ------------------------------------------------------------------ proxy flow sections

function ProxyFlowsView({ value, onChange, readOnly, lane }: {
  value: Bundle;
  onChange: (b: Bundle) => void;
  readOnly: boolean;
  lane: (l: Lane, title: string, hint?: string) => ReactNode;
}) {
  const flows = value.flows;
  const setFlows = (next: typeof flows) => onChange({ ...value, flows: next });

  const moveItem = <T,>(list: T[], from: number, to: number): T[] => {
    if (to < 0 || to >= list.length) return list;
    const copy = [...list];
    const [item] = copy.splice(from, 1);
    copy.splice(to, 0, item);
    return copy;
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" title="PreFlow" extra={<Typography.Text type="secondary">Runs first, for every request</Typography.Text>}>
        {lane({ kind: 'preFlow', side: 'request' }, 'Request')}
        {lane({ kind: 'preFlow', side: 'response' }, 'Response')}
      </Card>

      <Card
        size="small"
        title="Conditional flows"
        extra={
          <Space>
            <Typography.Text type="secondary">The first flow whose condition matches runs</Typography.Text>
            {!readOnly && (
              <Button
                size="small"
                icon={<PlusOutlined />}
                onClick={() => setFlows({
                  ...flows,
                  conditionalFlows: [...flows.conditionalFlows, {
                    name: `flow-${flows.conditionalFlows.length + 1}`,
                    condition: 'request.verb == "GET"',
                    request: [],
                    response: [],
                  }],
                })}
              >
                Add flow
              </Button>
            )}
          </Space>
        }
      >
        {flows.conditionalFlows.length === 0 && (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No conditional flows. Add one to run policies only for some requests, e.g. one operation." />
        )}
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {flows.conditionalFlows.map((f, index) => (
            <Card
              key={index}
              size="small"
              type="inner"
              title={
                <Flex gap={8} align="center" wrap>
                  <Input
                    aria-label="Flow name"
                    value={f.name}
                    disabled={readOnly}
                    style={{ width: 180 }}
                    onChange={(e) => setFlows({
                      ...flows,
                      conditionalFlows: flows.conditionalFlows.map((x, i) => (i === index ? { ...x, name: e.target.value } : x)),
                    })}
                  />
                  <Input
                    aria-label="Flow condition"
                    prefix={<FilterOutlined />}
                    placeholder='e.g. request.verb == "GET" and proxy.pathsuffix ~ "/pets/*"'
                    value={f.condition ?? ''}
                    disabled={readOnly}
                    style={{ flex: 1, minWidth: 240, fontFamily: 'monospace' }}
                    onChange={(e) => setFlows({
                      ...flows,
                      conditionalFlows: flows.conditionalFlows.map((x, i) => (i === index ? { ...x, condition: e.target.value } : x)),
                    })}
                  />
                </Flex>
              }
              extra={!readOnly && (
                <Space size={0}>
                  <Tooltip title="Move up"><Button type="text" size="small" icon={<UpOutlined />} aria-label="Move up"
                    onClick={() => setFlows({ ...flows, conditionalFlows: moveItem(flows.conditionalFlows, index, index - 1) })} /></Tooltip>
                  <Tooltip title="Move down"><Button type="text" size="small" icon={<DownOutlined />} aria-label="Move down"
                    onClick={() => setFlows({ ...flows, conditionalFlows: moveItem(flows.conditionalFlows, index, index + 1) })} /></Tooltip>
                  <Popconfirm title="Delete this flow?" description="Its steps are removed; the policies stay." onConfirm={() =>
                    setFlows({ ...flows, conditionalFlows: flows.conditionalFlows.filter((_, i) => i !== index) })}>
                    <Button type="text" size="small" danger icon={<DeleteOutlined />} aria-label="Delete flow" />
                  </Popconfirm>
                </Space>
              )}
            >
              {lane({ kind: 'conditional', index, side: 'request' }, 'Request')}
              {lane({ kind: 'conditional', index, side: 'response' }, 'Response')}
            </Card>
          ))}
        </Space>
      </Card>

      <Card size="small" title="PostFlow" extra={<Typography.Text type="secondary">Runs last on the way to the target, first on the way back</Typography.Text>}>
        {lane({ kind: 'postFlow', side: 'request' }, 'Request')}
        {lane({ kind: 'postFlow', side: 'response' }, 'Response')}
      </Card>

      <Card
        size="small"
        title="Fault rules"
        extra={!readOnly && (
          <Button
            size="small"
            icon={<PlusOutlined />}
            onClick={() => setFlows({
              ...flows,
              faultRules: [...flows.faultRules, { name: `fault-rule-${flows.faultRules.length + 1}`, condition: 'fault.name == "InvalidApiKey"', steps: [] }],
            })}
          >
            Add fault rule
          </Button>
        )}
      >
        <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
          When a policy fails, the first fault rule whose condition matches shapes the error response; otherwise the default fault rule runs.
        </Typography.Paragraph>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {flows.faultRules.map((r, index) => (
            <Card
              key={index}
              size="small"
              type="inner"
              title={
                <Flex gap={8} wrap>
                  <Input aria-label="Fault rule name" value={r.name} disabled={readOnly} style={{ width: 180 }}
                    onChange={(e) => setFlows({ ...flows, faultRules: flows.faultRules.map((x, i) => (i === index ? { ...x, name: e.target.value } : x)) })} />
                  <Input aria-label="Fault rule condition" prefix={<FilterOutlined />} value={r.condition ?? ''} disabled={readOnly}
                    style={{ flex: 1, minWidth: 240, fontFamily: 'monospace' }}
                    onChange={(e) => setFlows({ ...flows, faultRules: flows.faultRules.map((x, i) => (i === index ? { ...x, condition: e.target.value } : x)) })} />
                </Flex>
              }
              extra={!readOnly && (
                <Popconfirm title="Delete this fault rule?" onConfirm={() => setFlows({ ...flows, faultRules: flows.faultRules.filter((_, i) => i !== index) })}>
                  <Button type="text" size="small" danger icon={<DeleteOutlined />} aria-label="Delete fault rule" />
                </Popconfirm>
              )}
            >
              {lane({ kind: 'faultRule', index }, 'Steps')}
            </Card>
          ))}
          <Card size="small" type="inner" title="Default fault rule"
            extra={!readOnly && (
              <Switch
                size="small"
                checked={!!flows.defaultFaultRule}
                checkedChildren="On"
                unCheckedChildren="Off"
                onChange={(on) => setFlows({ ...flows, defaultFaultRule: on ? { name: 'default', condition: null, steps: [] } : null })}
              />
            )}
          >
            {flows.defaultFaultRule
              ? lane({ kind: 'defaultFaultRule' }, 'Steps')
              : <Typography.Text type="secondary">Off: unmatched faults get Portica's standard error body.</Typography.Text>}
          </Card>
        </Space>
      </Card>
    </Space>
  );
}

// ------------------------------------------------------------------ lanes and steps

function LaneView({ lane, title, hint, steps, policies, types, readOnly, onDrop, onStepDragStart, onOpen, onRemove, onCondition, onAdd }: {
  lane: Lane;
  title: string;
  hint?: string;
  steps: FlowStep[];
  policies: Map<string, PolicyDefinition>;
  types: Map<string, PolicyType>;
  readOnly: boolean;
  onDrop: (lane: Lane, index?: number) => void;
  onStepDragStart: (index: number) => (e: DragEvent) => void;
  onOpen: (policy: string) => void;
  onRemove: (index: number) => void;
  onCondition: (index: number, step: FlowStep) => void;
  onAdd: () => void;
}) {
  const { token } = theme.useToken();
  const [over, setOver] = useState(false);

  const allow = (e: DragEvent) => {
    if (readOnly) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
  };

  return (
    <div
      role="list"
      aria-label={`${title} steps`}
      onDragOver={(e) => {
        allow(e);
        if (!readOnly) setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        onDrop(lane);
      }}
      style={{
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 8,
        minHeight: 52,
        padding: '8px 10px',
        margin: '6px 0',
        borderRadius: token.borderRadius,
        border: `1px dashed ${over ? token.colorPrimary : token.colorBorder}`,
        background: over ? token.colorPrimaryBg : token.colorFillQuaternary,
        transition: 'background .15s, border-color .15s',
      }}
    >
      <Tooltip title={hint}>
        <Typography.Text type="secondary" style={{ width: 72, flex: 'none', fontSize: 12, textTransform: 'uppercase', letterSpacing: 0.5 }}>
          {title}
        </Typography.Text>
      </Tooltip>
      {steps.length === 0 && (
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {readOnly ? 'No steps' : 'Drop policies here'}
        </Typography.Text>
      )}
      {steps.map((step, index) => {
        const policy = policies.get(step.policy);
        const type = policy ? types.get(policy.type) : undefined;
        const color = type ? CATEGORY_COLORS[type.category] ?? 'default' : 'error';
        return (
          <Flex key={`${step.policy}-${index}`} align="center" gap={6}>
            {index > 0 && <ArrowRightOutlined style={{ color: token.colorTextQuaternary, fontSize: 11 }} />}
            <div
              role="listitem"
              draggable={!readOnly}
              onDragStart={onStepDragStart(index)}
              onDragOver={allow}
              onDrop={(e) => {
                e.preventDefault();
                e.stopPropagation();
                setOver(false);
                onDrop(lane, index);
              }}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 4,
                padding: '4px 6px 4px 4px',
                borderRadius: token.borderRadiusSM,
                background: token.colorBgContainer,
                border: `1px solid ${token.colorBorderSecondary}`,
                boxShadow: token.boxShadowTertiary,
                cursor: readOnly ? 'pointer' : 'grab',
                opacity: policy?.enabled === false ? 0.55 : 1,
              }}
            >
              {!readOnly && <HolderOutlined style={{ color: token.colorTextQuaternary }} />}
              <Tag color={color} bordered={false} style={{ marginInlineEnd: 0, cursor: 'pointer' }} onClick={() => onOpen(step.policy)}>
                {policy ? step.policy : <><WarningOutlined /> {step.policy}</>}
              </Tag>
              {type && (
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>{type.type}</Typography.Text>
              )}
              <Tooltip title={step.condition ? `Runs if: ${step.condition}` : readOnly ? 'Always runs' : 'Add a condition'}>
                <Badge dot={!!step.condition} offset={[-2, 2]}>
                  <Button
                    type="text"
                    size="small"
                    aria-label="Step condition"
                    icon={<FilterOutlined style={{ color: step.condition ? token.colorPrimary : undefined }} />}
                    onClick={() => onCondition(index, step)}
                  />
                </Badge>
              </Tooltip>
              {!readOnly && (
                <Tooltip title="Remove step">
                  <Button type="text" size="small" aria-label="Remove step" icon={<CloseOutlined />} onClick={() => onRemove(index)} />
                </Tooltip>
              )}
            </div>
          </Flex>
        );
      })}
      {!readOnly && (
        <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={onAdd} aria-label={`Add step to ${title}`}>
          Step
        </Button>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ palette and policy list

function Palette({ types, loading, readOnly, onDragStart }: {
  types: PolicyType[];
  loading: boolean;
  readOnly: boolean;
  onDragStart: (type: string) => (e: DragEvent) => void;
}) {
  const [filter, setFilter] = useState('');
  const grouped = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const groups = new Map<string, PolicyType[]>();
    types
      .filter((t) => !q || t.type.toLowerCase().includes(q) || t.description.toLowerCase().includes(q))
      .forEach((t) => groups.set(t.category, [...(groups.get(t.category) ?? []), t]));
    return [...groups.entries()];
  }, [types, filter]);

  return (
    <Card size="small" title="Policy palette" loading={loading} style={{ marginBottom: 16 }}
      extra={!readOnly && <Typography.Text type="secondary" style={{ fontSize: 12 }}>Drag onto a flow</Typography.Text>}>
      <Input allowClear size="small" prefix={<SearchOutlined />} placeholder="Find a policy" value={filter}
        onChange={(e) => setFilter(e.target.value)} style={{ marginBottom: 8 }} />
      <div style={{ maxHeight: 360, overflow: 'auto' }}>
        {grouped.map(([category, list]) => (
          <div key={category} style={{ marginBottom: 8 }}>
            <Typography.Text type="secondary" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5 }}>{category}</Typography.Text>
            <Flex wrap gap={4} style={{ marginTop: 4 }}>
              {list.map((t) => (
                <Tooltip key={t.type} title={t.description} placement="right">
                  <Tag
                    color={CATEGORY_COLORS[t.category] ?? 'default'}
                    draggable={!readOnly}
                    onDragStart={onDragStart(t.type)}
                    style={{ cursor: readOnly ? 'default' : 'grab', userSelect: 'none' }}
                  >
                    {t.type}
                  </Tag>
                </Tooltip>
              ))}
            </Flex>
          </div>
        ))}
      </div>
    </Card>
  );
}

function PoliciesPanel({ bundle, types, readOnly, onOpen, onDelete, onDragStart }: {
  bundle: Bundle;
  types: Map<string, PolicyType>;
  readOnly: boolean;
  onOpen: (p: PolicyDefinition) => void;
  onDelete: (name: string) => void;
  onDragStart: (policy: string) => (e: DragEvent) => void;
}) {
  return (
    <Card size="small" title={`Policies (${bundle.policies.length})`}>
      {bundle.policies.length === 0 && <Typography.Text type="secondary">None yet.</Typography.Text>}
      <Space direction="vertical" size={4} style={{ width: '100%' }}>
        {bundle.policies.map((p) => {
          const uses = usageCount(bundle, p.name);
          const type = types.get(p.type);
          return (
            <Flex key={p.name} align="center" justify="space-between" gap={4}
              draggable={!readOnly} onDragStart={onDragStart(p.name)} style={{ cursor: readOnly ? 'default' : 'grab' }}>
              <Space size={4} style={{ minWidth: 0 }}>
                <Tag color={type ? CATEGORY_COLORS[type.category] ?? 'default' : 'error'} bordered={false}
                  style={{ cursor: 'pointer', maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis' }} onClick={() => onOpen(p)}>
                  {p.name}
                </Tag>
                {p.enabled === false && <Tag>disabled</Tag>}
                {uses === 0 && <Tooltip title="Not used by any flow"><Tag color="warning" bordered={false}>unused</Tag></Tooltip>}
              </Space>
              {!readOnly && (
                <Popconfirm title={`Delete ${p.name}?`} description={uses ? `It's used by ${uses} step(s); they are removed too.` : undefined}
                  onConfirm={() => onDelete(p.name)}>
                  <Button type="text" size="small" danger icon={<DeleteOutlined />} aria-label={`Delete ${p.name}`} />
                </Popconfirm>
              )}
            </Flex>
          );
        })}
      </Space>
    </Card>
  );
}

// ------------------------------------------------------------------ dialogs

function PolicyDrawer({ editing, bundle, types, readOnly, onClose, onSave }: {
  editing: { policy: PolicyDefinition; isNew: boolean } | null;
  bundle: Bundle;
  types: Map<string, PolicyType>;
  readOnly: boolean;
  onClose: () => void;
  onSave: (policy: PolicyDefinition, previousName: string) => void;
}) {
  const policy = editing?.policy;
  const type = policy ? types.get(policy.type) : undefined;
  return (
    <Drawer
      open={!!editing}
      width={560}
      title={policy ? <Space>{policy.name}<Tag color={type ? CATEGORY_COLORS[type.category] : undefined}>{policy.type}</Tag></Space> : null}
      onClose={onClose}
      destroyOnClose
    >
      {policy && (
        <PolicyForm key={policy.name} policy={policy} type={type} bundle={bundle} readOnly={readOnly} onSave={onSave} />
      )}
    </Drawer>
  );
}

function PolicyForm({ policy, type, bundle, readOnly, onSave }: {
  policy: PolicyDefinition;
  type?: PolicyType;
  bundle: Bundle;
  readOnly: boolean;
  onSave: (policy: PolicyDefinition, previousName: string) => void;
}) {
  const [form] = Form.useForm<{ name: string; enabled: boolean; continueOnError: boolean; config: string }>();
  return (
    <Form
      form={form}
      layout="vertical"
      disabled={readOnly}
      initialValues={{
        name: policy.name,
        enabled: policy.enabled !== false,
        continueOnError: policy.continueOnError === true,
        config: JSON.stringify(policy.config ?? {}, null, 2),
      }}
      onFinish={(v) => onSave({
        name: v.name.trim(),
        type: policy.type,
        enabled: v.enabled ? null : false,
        continueOnError: v.continueOnError ? true : null,
        config: JSON.parse(v.config) as Record<string, unknown>,
      }, policy.name)}
    >
      {type && <Typography.Paragraph type="secondary">{type.description}</Typography.Paragraph>}
      <Form.Item name="name" label="Name" rules={[
        { required: true },
        { pattern: POLICY_NAME, message: "Letters, digits, '.', '_' or '-' (up to 64)" },
        {
          validator: (_, v: string) => (v && v.trim() !== policy.name && bundle.policies.some((p) => p.name === v.trim())
            ? Promise.reject(new Error('Another policy has this name')) : Promise.resolve()),
        },
      ]}>
        <Input />
      </Form.Item>
      <Space size="large">
        <Form.Item name="enabled" label="Enabled" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="continueOnError" label="Continue on error" valuePropName="checked"
          tooltip="On failure, record the fault in <name>.failed and carry on instead of raising it">
          <Switch />
        </Form.Item>
      </Space>
      <Form.Item
        name="config"
        label="Configuration (JSON)"
        extra={type && type.configFields.length > 0 && (
          <span>Fields: {type.configFields.map((f) => <Typography.Text code key={f}>{f}</Typography.Text>)}</span>
        )}
        rules={[{
          validator: (_, v: string) => {
            try {
              const parsed: unknown = JSON.parse(v);
              return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
                ? Promise.resolve() : Promise.reject(new Error('Must be a JSON object'));
            } catch (e) {
              return Promise.reject(new Error(`Invalid JSON: ${(e as Error).message}`));
            }
          },
        }]}
      >
        <Input.TextArea autoSize={{ minRows: 10, maxRows: 28 }} spellCheck={false}
          style={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 13 }} />
      </Form.Item>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
        Text values can use templates such as <Typography.Text code>{'{request.header.x-tenant}'}</Typography.Text>.
        The management API validates the configuration when you save the revision.
      </Typography.Paragraph>
      {!readOnly && (
        <Space>
          <Button type="primary" htmlType="submit">Apply</Button>
          <Button onClick={() => form.setFieldValue('config', JSON.stringify(templateFor(policy.type), null, 2))}>
            Reset to example
          </Button>
        </Space>
      )}
    </Form>
  );
}

const CONDITION_EXAMPLES = [
  'request.verb == "POST"',
  'proxy.pathsuffix ~ "/admin/**"',
  'request.header.x-debug == "true"',
  'response.status.code >= 500',
  'not (client.ip =| "10.")',
];

function ConditionModal({ target, readOnly, onClose, onSave }: {
  target: { step: FlowStep } | null;
  readOnly: boolean;
  onClose: () => void;
  onSave: (condition: string) => void;
}) {
  const [text, setText] = useState('');
  useEffect(() => setText(target?.step.condition ?? ''), [target]);
  return (
    <Modal
      open={!!target}
      title={target ? `When should ${target.step.policy} run?` : ''}
      onCancel={onClose}
      onOk={() => onSave(text.trim())}
      okButtonProps={{ disabled: readOnly }}
      okText="Apply"
      destroyOnClose
    >
      <Input.TextArea
        autoSize={{ minRows: 2 }}
        value={text}
        disabled={readOnly}
        onChange={(e) => setText(e.target.value)}
        placeholder="Leave empty to always run"
        style={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace' }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 4 }}>Examples:</Typography.Paragraph>
      <Flex wrap gap={4}>
        {CONDITION_EXAMPLES.map((ex) => (
          <Tag key={ex} style={{ cursor: readOnly ? 'default' : 'pointer', fontFamily: 'monospace' }} onClick={() => !readOnly && setText(ex)}>{ex}</Tag>
        ))}
      </Flex>
    </Modal>
  );
}

function AddStepModal({ lane, bundle, types, onClose, onAddExisting, onAddNew }: {
  lane: Lane | null;
  bundle: Bundle;
  types: PolicyType[];
  onClose: () => void;
  onAddExisting: (lane: Lane, policy: string) => void;
  onAddNew: (lane: Lane, type: string) => void;
}) {
  return (
    <Modal open={!!lane} title="Add a step" footer={null} onCancel={onClose} destroyOnClose>
      {lane && (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {bundle.policies.length > 0 && (
            <div>
              <Typography.Text strong>Run an existing policy</Typography.Text>
              <Flex wrap gap={4} style={{ marginTop: 8 }}>
                {bundle.policies.map((p) => (
                  <Button key={p.name} size="small" onClick={() => onAddExisting(lane, p.name)}>{p.name}</Button>
                ))}
              </Flex>
            </div>
          )}
          <div>
            <Typography.Text strong>Add a new policy</Typography.Text>
            <Flex wrap gap={4} style={{ marginTop: 8 }}>
              {types.map((t) => (
                <Tooltip key={t.type} title={t.description}>
                  <Button size="small" onClick={() => onAddNew(lane, t.type)}>
                    <Tag color={CATEGORY_COLORS[t.category] ?? 'default'} bordered={false} style={{ margin: 0 }}>{t.type}</Tag>
                  </Button>
                </Tooltip>
              ))}
            </Flex>
          </div>
        </Space>
      )}
    </Modal>
  );
}
