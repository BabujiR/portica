import { describe, expect, it } from 'vitest';
import { curlExample, describeQuota, formatUsd, fromRouteForms, parseTargetKey, targetKey, toRouteForms } from './aiModel';
import type { AiProxy } from '../../api/types';

describe('AI gateway model', () => {
  it('round-trips routes through the form shape', () => {
    const routes = [{ name: 'fast', targets: [{ provider: 'openai', model: 'gpt-4o-mini' }, { provider: 'anthropic', model: 'claude::x' }] }];
    expect(fromRouteForms(toRouteForms(routes))).toEqual(routes);
    expect(parseTargetKey(targetKey('a', 'b::c'))).toEqual({ provider: 'a', model: 'b::c' });
  });

  it('describes quotas and costs', () => {
    expect(describeQuota(null)).toBe('unlimited');
    expect(describeQuota({ tokens: 100000, timeUnit: 'day' })).toBe('100k tokens / day');
    expect(describeQuota({ tokens: 2500000, interval: 6, timeUnit: 'hour' })).toBe('2.5M tokens / 6 hours');
    expect(formatUsd(0)).toBe('$0');
    expect(formatUsd(0.0000066)).toBe('$0.000007');
    expect(formatUsd(12.346)).toBe('$12.35');
    expect(formatUsd(1234.5)).toBe('$1,235');
  });

  it('builds a curl example for the proxy', () => {
    const proxy = { name: 'assistant', auth: 'API_KEY', defaultRoute: 'fast' } as AiProxy;
    const curl = curlExample('https://gw.example.com', 'acme', 'dev', proxy);
    expect(curl).toContain('https://gw.example.com/acme/dev/ai/assistant/v1/chat/completions');
    expect(curl).toContain('x-api-key: $API_KEY');
    expect(curl).toContain('"model":"fast"');
  });
});
