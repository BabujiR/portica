import { describe, expect, it } from 'vitest';
import { presetBundle } from './presets';

describe('proxy presets', () => {
  it('orders CORS, spike arrest, authentication, quota', () => {
    const b = presetBundle({ security: 'apiKey', cors: true, spikeArrest: true, quota: true });
    expect(b.flows.preFlow.request.map((s) => s.policy)).toEqual(['cors', 'spike-arrest', 'verify-api-key', 'quota']);
    expect(b.policies.find((p) => p.name === 'quota')?.config.useProductQuota).toBe(true);
  });

  it('uses an IP quota without authentication and nothing at all for none', () => {
    expect(presetBundle({ security: 'none', cors: false, spikeArrest: false, quota: true }).policies[0].config.identifier)
      .toBe('{client.ip}');
    expect(presetBundle({ security: 'none', cors: false, spikeArrest: false, quota: false }).policies).toEqual([]);
  });
});
