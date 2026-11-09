import type { AiProvider, AiProxy, AiRoute, TokenQuota } from '../../api/types';

/** "provider::model" keys used by the route editor's target picker. */
export const targetKey = (provider: string, model: string) => `${provider}::${model}`;

export function parseTargetKey(key: string): { provider: string; model: string } {
  const i = key.indexOf('::');
  return { provider: key.slice(0, i), model: key.slice(i + 2) };
}

/** Every model of every enabled provider, as picker options grouped by provider. */
export function targetOptions(providers: AiProvider[]) {
  return providers.map((p) => ({
    label: `${p.displayName || p.name}${p.enabled ? '' : ' (disabled)'}`,
    options: p.models.map((m) => ({ value: targetKey(p.name, m.name), label: `${p.name} / ${m.name}` })),
  }));
}

/** Routes as the form edits them: targets as picker keys, in fallback order. */
export interface RouteForm {
  name: string;
  targets: string[];
}

export const toRouteForms = (routes: AiRoute[]): RouteForm[] =>
  routes.map((r) => ({ name: r.name, targets: r.targets.map((t) => targetKey(t.provider, t.model)) }));

export const fromRouteForms = (forms: RouteForm[]): AiRoute[] =>
  forms.map((f) => ({ name: f.name.trim(), targets: (f.targets ?? []).map(parseTargetKey) }));

/** "100k tokens / day", "2M tokens / 6 hours", or "unlimited". */
export function describeQuota(q?: TokenQuota | null): string {
  if (!q) return 'unlimited';
  const n = q.tokens >= 1_000_000 ? `${+(q.tokens / 1_000_000).toFixed(1)}M`
    : q.tokens >= 1000 ? `${+(q.tokens / 1000).toFixed(1)}k` : String(q.tokens);
  const interval = q.interval ?? 1;
  const unit = q.timeUnit ?? 'day';
  return `${n} tokens / ${interval === 1 ? unit : `${interval} ${unit}s`}`;
}

/** Dollars with enough precision for tiny per-call costs. */
export function formatUsd(v: number): string {
  if (v === 0) return '$0';
  if (v < 0.01) return `$${v.toFixed(6).replace(/0+$/, '')}`;
  if (v < 100) return `$${v.toFixed(2)}`;
  return `$${Math.round(v).toLocaleString()}`;
}

export function aiEndpoint(gatewayUrl: string, org: string, env: string, proxy: AiProxy | string): string {
  const name = typeof proxy === 'string' ? proxy : proxy.name;
  return `${gatewayUrl}/${org}/${env}/ai/${name}/v1`;
}

/** A copy-paste request for the "How to call" panel. */
export function curlExample(gatewayUrl: string, org: string, env: string, proxy: AiProxy): string {
  const credential = proxy.auth === 'OAUTH' ? 'Authorization: Bearer $ACCESS_TOKEN' : 'x-api-key: $API_KEY';
  return [
    `curl ${aiEndpoint(gatewayUrl, org, env, proxy)}/chat/completions \\`,
    `  -H '${credential}' -H 'Content-Type: application/json' \\`,
    `  -d '{"model":"${proxy.defaultRoute}","messages":[{"role":"user","content":"Hello"}]}'`,
  ].join('\n');
}

export function sdkExample(gatewayUrl: string, org: string, env: string, proxy: AiProxy): string {
  const header = proxy.auth === 'OAUTH' ? '' : `,\n  defaultHeaders: { 'x-api-key': process.env.PORTICA_API_KEY },`;
  return [
    "import OpenAI from 'openai';",
    '',
    'const client = new OpenAI({',
    `  baseURL: '${aiEndpoint(gatewayUrl, org, env, proxy)}',`,
    `  apiKey: ${proxy.auth === 'OAUTH' ? 'accessToken' : "'unused'"}${header}`,
    '});',
    `const reply = await client.chat.completions.create({ model: '${proxy.defaultRoute}', messages: [{ role: 'user', content: 'Hello' }] });`,
  ].join('\n');
}
