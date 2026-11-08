import type { PortalCatalog, PortalOperation, TryItRequest } from '../../api/portal';

/** Path parameters of an OpenAPI path template, in order: /pets/{petId}/toys/{toyId} → [petId, toyId]. */
export function pathParameters(template: string): string[] {
  return [...template.matchAll(/\{([^}/]+)}/g)].map((m) => m[1]);
}

/** Fills {name} placeholders (URL-encoded); unfilled ones stay as they are so the developer notices. */
export function fillPath(template: string, values: Record<string, string | undefined>): string {
  return template.replace(/\{([^}/]+)}/g, (whole, name: string) => {
    const v = values[name];
    return v === undefined || v === '' ? whole : encodeURIComponent(v);
  });
}

/** "Name: value" lines to a header map; blank and malformed lines are ignored. */
export function parseHeaders(text: string): Record<string, string> {
  const headers: Record<string, string> = {};
  text.split('\n').forEach((line) => {
    const i = line.indexOf(':');
    if (i <= 0) return;
    const name = line.slice(0, i).trim();
    const value = line.slice(i + 1).trim();
    if (/^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/.test(name)) headers[name] = value;
  });
  return headers;
}

const SAFE = /^[A-Za-z0-9_./:@%?&=+,-]*$/;

function quote(s: string): string {
  return SAFE.test(s) ? s : `'${s.replace(/'/g, `'\\''`)}'`;
}

/** The same request as a curl command against the public gateway URL. */
export function curlFor(baseUrl: string, request: Pick<TryItRequest, 'method' | 'path' | 'headers' | 'body'>): string {
  const parts = ['curl', '-i'];
  if (request.method.toUpperCase() !== 'GET') parts.push('-X', request.method.toUpperCase());
  Object.entries(request.headers).forEach(([k, v]) => parts.push('-H', quote(`${k}: ${v}`)));
  if (request.body) parts.push('--data', quote(request.body));
  parts.push(quote(baseUrl.replace(/\/+$/, '') + request.path));
  return parts.join(' ');
}

/** Operations sorted by path, then by the usual method order. */
export function sortOperations(ops: PortalOperation[]): PortalOperation[] {
  const order = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];
  return [...ops].sort((a, b) => a.path.localeCompare(b.path)
    || order.indexOf(a.method.toUpperCase()) - order.indexOf(b.method.toUpperCase()));
}

/** Catalog search over APIs and MCP servers (name, title, description, product). */
export function searchCatalog(catalog: PortalCatalog, q: string): PortalCatalog {
  const term = q.trim().toLowerCase();
  if (!term) return catalog;
  const hit = (...values: Array<string | null | undefined>) => values.some((v) => v?.toLowerCase().includes(term));
  return {
    ...catalog,
    apis: catalog.apis.filter((a) => hit(a.name, a.displayName, a.description, ...a.products)),
    mcpServers: catalog.mcpServers.filter((s) => hit(s.name, s.displayName, s.description, ...s.products)),
  };
}

/** Environments where any API or MCP server of the catalog is live, sorted. */
export function catalogEnvironments(catalog: PortalCatalog): string[] {
  const envs = new Set<string>();
  catalog.apis.forEach((a) => a.deployments.forEach((d) => envs.add(d.environment)));
  catalog.mcpServers.forEach((s) => s.endpoints.forEach((e) => envs.add(e.environment)));
  return [...envs].sort();
}

export const METHOD_COLORS: Record<string, string> = {
  GET: 'blue', POST: 'green', PUT: 'orange', PATCH: 'gold', DELETE: 'red', HEAD: 'default', OPTIONS: 'default',
};
