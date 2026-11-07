import { describe, expect, it } from 'vitest';
import {
  cleanFlows, deletePolicy, EMPTY_FLOWS, getSteps, insertStep, moveStep, normalizeFlows, renamePolicy,
  uniquePolicyName, upsertPolicy, usageCount, type Bundle, type Lane,
} from './model';

const preReq: Lane = { kind: 'preFlow', side: 'request' };
const postResp: Lane = { kind: 'postFlow', side: 'response' };

function bundle(): Bundle {
  return {
    policies: [
      { name: 'verify-key', type: 'VerifyAPIKey', config: {} },
      { name: 'quota', type: 'Quota', config: { count: 5 } },
    ],
    flows: normalizeFlows({
      preFlow: { request: [{ policy: 'verify-key' }, { policy: 'quota' }], response: [] },
      faultRules: [{ name: 'auth', condition: 'fault.name == "InvalidApiKey"', steps: [{ policy: 'verify-key' }] }],
    }),
    steps: [],
  };
}

describe('flow model', () => {
  it('normalizes missing parts', () => {
    const flows = normalizeFlows({ preFlow: { request: [{ policy: 'a' }], response: [] } });
    expect(flows.postFlow).toEqual({ request: [], response: [] });
    expect(flows.conditionalFlows).toEqual([]);
    expect(flows.defaultFaultRule).toBeNull();
    expect(normalizeFlows(null)).toEqual(EMPTY_FLOWS);
  });

  it('inserts, moves within and across lanes', () => {
    let b = insertStep(bundle(), postResp, { policy: 'quota' });
    expect(getSteps(b.flows, postResp)).toEqual([{ policy: 'quota' }]);

    // Move "verify-key" after "quota" in the same lane (target index counts the original position).
    b = moveStep(b, preReq, 0, preReq, 2);
    expect(getSteps(b.flows, preReq).map((s) => s.policy)).toEqual(['quota', 'verify-key']);

    // Move it to the response lane, before the existing step.
    b = moveStep(b, preReq, 1, postResp, 0);
    expect(getSteps(b.flows, preReq).map((s) => s.policy)).toEqual(['quota']);
    expect(getSteps(b.flows, postResp).map((s) => s.policy)).toEqual(['verify-key', 'quota']);
  });

  it('renames and deletes policies everywhere', () => {
    let b = renamePolicy(bundle(), 'verify-key', 'check-key');
    expect(b.policies.map((p) => p.name)).toEqual(['check-key', 'quota']);
    expect(b.flows.faultRules[0].steps[0].policy).toBe('check-key');
    expect(usageCount(b, 'check-key')).toBe(2);

    b = deletePolicy(b, 'check-key');
    expect(b.policies.map((p) => p.name)).toEqual(['quota']);
    expect(usageCount(b, 'check-key')).toBe(0);
    expect(b.flows.faultRules[0].steps).toEqual([]);
  });

  it('upserts policies, renaming steps with them', () => {
    const b = upsertPolicy(bundle(), { name: 'limit', type: 'Quota', config: { count: 9 } }, 'quota');
    expect(b.policies.find((p) => p.name === 'limit')?.config).toEqual({ count: 9 });
    expect(getSteps(b.flows, preReq).map((s) => s.policy)).toEqual(['verify-key', 'limit']);
    expect(upsertPolicy(b, { name: 'cors', type: 'CORS', config: {} }).policies).toHaveLength(3);
  });

  it('derives unique policy names from types', () => {
    expect(uniquePolicyName('VerifyAPIKey', [])).toBe('verify-apikey');
    expect(uniquePolicyName('SpikeArrest', ['spike-arrest'])).toBe('spike-arrest-2');
    expect(uniquePolicyName('SpikeArrest', ['spike-arrest', 'spike-arrest-2'])).toBe('spike-arrest-3');
  });

  it('cleans empty conditions and empty default fault rules', () => {
    const flows = normalizeFlows({
      preFlow: { request: [{ policy: 'a', condition: '  ' }, { policy: 'b', condition: ' x == 1 ' }], response: [] },
      defaultFaultRule: { name: 'default', steps: [] },
    });
    const clean = cleanFlows(flows);
    expect(clean.preFlow.request).toEqual([{ policy: 'a' }, { policy: 'b', condition: 'x == 1' }]);
    expect(clean.defaultFaultRule).toBeNull();
  });

  it('edits shared flow steps', () => {
    const b = insertStep({ policies: [], flows: EMPTY_FLOWS, steps: [{ policy: 'x' }] }, { kind: 'sharedFlow' }, { policy: 'y' }, 0);
    expect(b.steps.map((s) => s.policy)).toEqual(['y', 'x']);
  });
});
