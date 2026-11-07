import type { FlowStep, PolicyDefinition, ProxyFlows } from '../../api/types';

/** Where a step lives in a proxy bundle. */
export type Lane =
  | { kind: 'preFlow' | 'postFlow'; side: 'request' | 'response' }
  | { kind: 'conditional'; index: number; side: 'request' | 'response' }
  | { kind: 'faultRule'; index: number }
  | { kind: 'defaultFaultRule' }
  | { kind: 'sharedFlow' };

export function laneKey(lane: Lane): string {
  switch (lane.kind) {
    case 'preFlow':
    case 'postFlow':
      return `${lane.kind}.${lane.side}`;
    case 'conditional':
      return `conditional.${lane.index}.${lane.side}`;
    case 'faultRule':
      return `faultRule.${lane.index}`;
    default:
      return lane.kind;
  }
}

export const EMPTY_FLOWS: ProxyFlows = {
  preFlow: { request: [], response: [] },
  conditionalFlows: [],
  postFlow: { request: [], response: [] },
  faultRules: [],
  defaultFaultRule: null,
};

/** Fills in what the API may omit, so the editor can rely on every list being present. */
export function normalizeFlows(flows?: Partial<ProxyFlows> | null): ProxyFlows {
  const pair = (p?: { request?: FlowStep[] | null; response?: FlowStep[] | null } | null) => ({
    request: [...(p?.request ?? [])],
    response: [...(p?.response ?? [])],
  });
  return {
    preFlow: pair(flows?.preFlow),
    conditionalFlows: (flows?.conditionalFlows ?? []).map((f) => ({
      name: f.name,
      condition: f.condition ?? null,
      request: [...(f.request ?? [])],
      response: [...(f.response ?? [])],
    })),
    postFlow: pair(flows?.postFlow),
    faultRules: (flows?.faultRules ?? []).map((r) => ({ name: r.name, condition: r.condition ?? null, steps: [...(r.steps ?? [])] })),
    defaultFaultRule: flows?.defaultFaultRule
      ? { name: flows.defaultFaultRule.name, condition: flows.defaultFaultRule.condition ?? null, steps: [...(flows.defaultFaultRule.steps ?? [])] }
      : null,
  };
}

/** Steps of a lane. Shared flows keep their steps outside {@link ProxyFlows}: pass them as {@code shared}. */
export function getSteps(flows: ProxyFlows, lane: Lane, shared: FlowStep[] = []): FlowStep[] {
  switch (lane.kind) {
    case 'preFlow':
    case 'postFlow':
      return flows[lane.kind][lane.side];
    case 'conditional':
      return flows.conditionalFlows[lane.index]?.[lane.side] ?? [];
    case 'faultRule':
      return flows.faultRules[lane.index]?.steps ?? [];
    case 'defaultFaultRule':
      return flows.defaultFaultRule?.steps ?? [];
    case 'sharedFlow':
      return shared;
  }
}

/** A copy of {@code flows} with the lane's steps replaced. */
export function setSteps(flows: ProxyFlows, lane: Lane, steps: FlowStep[]): ProxyFlows {
  switch (lane.kind) {
    case 'preFlow':
    case 'postFlow':
      return { ...flows, [lane.kind]: { ...flows[lane.kind], [lane.side]: steps } };
    case 'conditional':
      return {
        ...flows,
        conditionalFlows: flows.conditionalFlows.map((f, i) => (i === lane.index ? { ...f, [lane.side]: steps } : f)),
      };
    case 'faultRule':
      return { ...flows, faultRules: flows.faultRules.map((r, i) => (i === lane.index ? { ...r, steps } : r)) };
    case 'defaultFaultRule':
      return { ...flows, defaultFaultRule: { name: flows.defaultFaultRule?.name ?? 'default', condition: null, steps } };
    case 'sharedFlow':
      return flows;
  }
}

/** The editor's whole state: proxy flows plus (for shared flows) a single step list. */
export interface Bundle {
  policies: PolicyDefinition[];
  flows: ProxyFlows;
  steps: FlowStep[];
}

function withLane(bundle: Bundle, lane: Lane, steps: FlowStep[]): Bundle {
  return lane.kind === 'sharedFlow' ? { ...bundle, steps } : { ...bundle, flows: setSteps(bundle.flows, lane, steps) };
}

export function insertStep(bundle: Bundle, lane: Lane, step: FlowStep, index?: number): Bundle {
  const steps = [...getSteps(bundle.flows, lane, bundle.steps)];
  steps.splice(index === undefined ? steps.length : Math.max(0, Math.min(index, steps.length)), 0, step);
  return withLane(bundle, lane, steps);
}

export function removeStep(bundle: Bundle, lane: Lane, index: number): Bundle {
  const steps = getSteps(bundle.flows, lane, bundle.steps).filter((_, i) => i !== index);
  return withLane(bundle, lane, steps);
}

export function updateStep(bundle: Bundle, lane: Lane, index: number, step: FlowStep): Bundle {
  const steps = getSteps(bundle.flows, lane, bundle.steps).map((s, i) => (i === index ? step : s));
  return withLane(bundle, lane, steps);
}

/** Moves a step within or across lanes. {@code toIndex} is the position in the target lane before the move. */
export function moveStep(bundle: Bundle, from: Lane, fromIndex: number, to: Lane, toIndex: number): Bundle {
  const step = getSteps(bundle.flows, from, bundle.steps)[fromIndex];
  if (!step) return bundle;
  const sameLane = laneKey(from) === laneKey(to);
  let target = toIndex;
  if (sameLane && fromIndex < toIndex) target -= 1; // removing the step shifts the ones after it
  if (sameLane && target === fromIndex) return bundle;
  return insertStep(removeStep(bundle, from, fromIndex), to, step, target);
}

/** All lanes of the bundle, for whole-bundle operations. */
export function allLanes(flows: ProxyFlows): Lane[] {
  const lanes: Lane[] = [
    { kind: 'preFlow', side: 'request' }, { kind: 'preFlow', side: 'response' },
    { kind: 'postFlow', side: 'request' }, { kind: 'postFlow', side: 'response' },
  ];
  flows.conditionalFlows.forEach((_, index) => {
    lanes.push({ kind: 'conditional', index, side: 'request' }, { kind: 'conditional', index, side: 'response' });
  });
  flows.faultRules.forEach((_, index) => lanes.push({ kind: 'faultRule', index }));
  if (flows.defaultFaultRule) lanes.push({ kind: 'defaultFaultRule' });
  lanes.push({ kind: 'sharedFlow' });
  return lanes;
}

function mapAllSteps(bundle: Bundle, fn: (steps: FlowStep[]) => FlowStep[]): Bundle {
  return allLanes(bundle.flows).reduce((b, lane) => withLane(b, lane, fn(getSteps(b.flows, lane, b.steps))), bundle);
}

/** How many steps run the policy. */
export function usageCount(bundle: Bundle, policy: string): number {
  return allLanes(bundle.flows).reduce(
    (n, lane) => n + getSteps(bundle.flows, lane, bundle.steps).filter((s) => s.policy === policy).length, 0);
}

/** Deletes a policy and every step that runs it. */
export function deletePolicy(bundle: Bundle, name: string): Bundle {
  const cleaned = mapAllSteps(bundle, (steps) => steps.filter((s) => s.policy !== name));
  return { ...cleaned, policies: cleaned.policies.filter((p) => p.name !== name) };
}

/** Renames a policy and the steps that refer to it. */
export function renamePolicy(bundle: Bundle, from: string, to: string): Bundle {
  if (from === to) return bundle;
  const renamed = mapAllSteps(bundle, (steps) => steps.map((s) => (s.policy === from ? { ...s, policy: to } : s)));
  return { ...renamed, policies: renamed.policies.map((p) => (p.name === from ? { ...p, name: to } : p)) };
}

export function upsertPolicy(bundle: Bundle, policy: PolicyDefinition, previousName = policy.name): Bundle {
  const renamed = renamePolicy(bundle, previousName, policy.name);
  const exists = renamed.policies.some((p) => p.name === policy.name);
  return {
    ...renamed,
    policies: exists ? renamed.policies.map((p) => (p.name === policy.name ? policy : p)) : [...renamed.policies, policy],
  };
}

/** Policy names: what the API accepts (letters, digits, '.', '_', '-'; up to 64). */
export const POLICY_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

/** {@code verify-api-key}, then {@code verify-api-key-2}, ... */
export function uniquePolicyName(type: string, taken: string[]): string {
  const base = type.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/[^A-Za-z0-9]+/g, '-').toLowerCase().slice(0, 56);
  if (!taken.includes(base)) return base;
  for (let i = 2; ; i++) {
    const candidate = `${base}-${i}`;
    if (!taken.includes(candidate)) return candidate;
  }
}

/** Strips empty conditions and empty optional parts so saved bundles stay tidy. */
export function cleanFlows(flows: ProxyFlows): ProxyFlows {
  const step = (s: FlowStep): FlowStep => (s.condition && s.condition.trim() ? { policy: s.policy, condition: s.condition.trim() } : { policy: s.policy });
  const steps = (list: FlowStep[]) => list.map(step);
  return {
    preFlow: { request: steps(flows.preFlow.request), response: steps(flows.preFlow.response) },
    conditionalFlows: flows.conditionalFlows.map((f) => ({
      name: f.name, condition: f.condition?.trim() || null, request: steps(f.request), response: steps(f.response),
    })),
    postFlow: { request: steps(flows.postFlow.request), response: steps(flows.postFlow.response) },
    faultRules: flows.faultRules.map((r) => ({ name: r.name, condition: r.condition?.trim() || null, steps: steps(r.steps) })),
    defaultFaultRule: flows.defaultFaultRule && flows.defaultFaultRule.steps.length
      ? { name: flows.defaultFaultRule.name || 'default', condition: null, steps: steps(flows.defaultFaultRule.steps) }
      : null,
  };
}

export function cleanSteps(steps: FlowStep[]): FlowStep[] {
  return cleanFlows({ ...EMPTY_FLOWS, preFlow: { request: steps, response: [] } }).preFlow.request;
}
