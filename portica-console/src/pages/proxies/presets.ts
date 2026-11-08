import type { PolicyDefinition, ProxyFlows } from '../../api/types';
import { EMPTY_FLOWS } from '../flow/model';

export type Security = 'none' | 'apiKey' | 'oauth';

export interface PresetOptions {
  security: Security;
  cors: boolean;
  spikeArrest: boolean;
  quota: boolean;
}

/**
 * Starter policies for a new proxy. Order in the request PreFlow: CORS (answers preflights before
 * authentication), SpikeArrest, authentication, then Quota (which can use the app's product quota).
 */
export function presetBundle(o: PresetOptions): { policies: PolicyDefinition[]; flows: ProxyFlows } {
  const policies: PolicyDefinition[] = [];
  const steps: string[] = [];
  const add = (p: PolicyDefinition) => {
    policies.push(p);
    steps.push(p.name);
  };
  if (o.cors) add({ name: 'cors', type: 'CORS', config: { allowOrigins: ['*'], allowHeaders: ['*'] } });
  if (o.spikeArrest) add({ name: 'spike-arrest', type: 'SpikeArrest', config: { rate: '100ps', identifier: '{client.ip}' } });
  if (o.security === 'apiKey') add({ name: 'verify-api-key', type: 'VerifyAPIKey', config: {} });
  if (o.security === 'oauth') add({ name: 'verify-token', type: 'OAuthV2', config: { operation: 'VerifyAccessToken' } });
  if (o.quota) {
    add(o.security === 'none'
      ? { name: 'quota', type: 'Quota', config: { count: 1000, interval: 1, timeUnit: 'minute', identifier: '{client.ip}' } }
      : { name: 'quota', type: 'Quota', config: { useProductQuota: true, count: 1000, interval: 1, timeUnit: 'hour' } });
  }
  return {
    policies,
    flows: { ...EMPTY_FLOWS, preFlow: { request: steps.map((policy) => ({ policy })), response: [] } },
  };
}
