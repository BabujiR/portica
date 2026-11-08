import { config } from '../config';
import { accessToken, login } from '../auth/oidc';
import { ApiError, path, type Problem } from './client';
import type { AnalyticsSummary, AnalyticsTimeseries, AnalyticsTop, Credential, DeveloperApp, IssuedCredential } from './types';

// ------------------------------------------------------------------ types (portica-portal)

export interface PortalCatalog {
  organization: string;
  products: PortalProduct[];
  apis: PortalApi[];
  mcpServers: PortalMcpServer[];
}

export interface PortalProduct {
  name: string;
  displayName: string;
  description?: string | null;
  approvalType: 'AUTO' | 'MANUAL';
  environments: string[];
  apis: string[];
  scopes: string[];
  quota?: string | null;
}

export interface PortalDeployment {
  environment: string;
  revision: number;
  basePath: string;
  url: string;
}

export interface PortalApi {
  name: string;
  displayName: string;
  description?: string | null;
  products: string[];
  deployments: PortalDeployment[];
  documented: boolean;
}

export interface PortalMcpServer {
  name: string;
  displayName: string;
  description?: string | null;
  type: 'OPENAPI' | 'REMOTE';
  products: string[];
  endpoints: { environment: string; url: string }[];
}

export interface PortalOperation {
  method: string;
  path: string;
  operationId?: string | null;
  summary?: string | null;
}

export interface PortalDocs {
  name: string;
  displayName: string;
  description?: string | null;
  environment: string;
  revision: number;
  url: string;
  title: string;
  version?: string | null;
  operations: PortalOperation[];
  openApiSpec?: string | null;
}

export interface PortalTool {
  name: string;
  title?: string | null;
  description?: string | null;
  inputSchema: Record<string, unknown>;
}

export interface PortalDeveloper {
  email: string;
  firstName: string;
  lastName: string;
  status: 'ACTIVE' | 'INACTIVE';
}

export interface PortalUsage {
  summary: AnalyticsSummary;
  timeseries: AnalyticsTimeseries;
  byApp: AnalyticsTop;
  byApi: AnalyticsTop;
}

export interface TryItRequest {
  environment: string;
  api: string;
  method: string;
  path: string;
  headers: Record<string, string>;
  body?: string;
}

export interface TryItResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
  truncated: boolean;
  durationMs: number;
  url: string;
}

// ------------------------------------------------------------------ client

type Method = 'GET' | 'POST' | 'DELETE';

/** Calls portica-portal; the token is sent when the developer is signed in (catalog calls work without). */
async function portal<T>(p: string, method: Method = 'GET', body?: unknown): Promise<T> {
  const token = await accessToken();
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${config.portalApiUrl}/api/portal${p}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 401) {
    await login();
    throw new ApiError(401, { title: 'Signing in again' });
  }
  if (!response.ok) {
    let problem: Problem = { status: response.status, title: response.statusText };
    try {
      problem = (await response.json()) as Problem;
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, problem);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

const org = (o: string) => path('/organizations/{0}', o);

export const getCatalog = (o: string) => portal<PortalCatalog>(`${org(o)}/catalog`);
export const getDocs = (o: string, api: string, env: string) =>
  portal<PortalDocs>(`${org(o)}${path('/apis/{0}/environments/{1}/docs', api, env)}`);
export const getMcpTools = (o: string, server: string, env: string) =>
  portal<{ server: string; environment: string; tools: PortalTool[] }>(`${org(o)}${path('/mcp/{0}/environments/{1}/tools', server, env)}`);

/** The signed-in developer, or null when they haven't registered with the organization yet. */
export async function getMe(o: string): Promise<PortalDeveloper | null> {
  try {
    return await portal<PortalDeveloper>(`${org(o)}/me`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}
export const register = (o: string, body: { firstName: string; lastName: string }) =>
  portal<PortalDeveloper>(`${org(o)}/me`, 'POST', body);
export const listMyApps = (o: string) => portal<DeveloperApp[]>(`${org(o)}/me/apps`);
export const createMyApp = (o: string, body: { name: string; displayName?: string; description?: string; callbackUrl?: string; apiProducts: string[] }) =>
  portal<{ app: DeveloperApp; credential: IssuedCredential }>(`${org(o)}/me/apps`, 'POST', body);
export const deleteMyApp = (o: string, app: string) => portal<void>(`${org(o)}${path('/me/apps/{0}', app)}`, 'DELETE');
export const createMyKey = (o: string, app: string, body: { apiProducts?: string[]; expiresInSeconds?: number }) =>
  portal<IssuedCredential>(`${org(o)}${path('/me/apps/{0}/keys', app)}`, 'POST', body);
export const deleteMyKey = (o: string, app: string, keyId: string) =>
  portal<void>(`${org(o)}${path('/me/apps/{0}/keys/{1}', app, keyId)}`, 'DELETE');
export const addMyCertificate = (o: string, app: string, keyId: string, certificate: string) =>
  portal<Credential>(`${org(o)}${path('/me/apps/{0}/keys/{1}/certificates', app, keyId)}`, 'POST', { certificate });
export const removeMyCertificate = (o: string, app: string, keyId: string, fingerprint: string) =>
  portal<Credential>(`${org(o)}${path('/me/apps/{0}/keys/{1}/certificates/{2}', app, keyId, fingerprint)}`, 'DELETE');
export const getMyUsage = (o: string, from: string, to: string) =>
  portal<PortalUsage>(`${org(o)}/me/usage?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
export const tryIt = (o: string, body: TryItRequest) => portal<TryItResponse>(`${org(o)}/try`, 'POST', body);
