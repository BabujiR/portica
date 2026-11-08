import { api, path } from './client';
import type {
  AnalyticsDimension, AnalyticsEvents, AnalyticsSummary, AnalyticsTimeseries, AnalyticsTop, AuditEvent,
  StartTraceRequest, TraceSession, TraceTransaction,
  ApiProduct, ApiProductRequest, ApiProxy, Credential, Deployment, Developer, DeveloperApp, Environment,
  FlowStep, ImportResult, Operation, IssuedCredential, KeyValueEntry, KeyValueMap, Me, Member, Organization, Page,
  PolicyDefinition, PolicyType, Revision, RevisionRequest, Role, SharedFlow, SharedFlowDeployment,
  SharedFlowRevision, TargetServer, TargetSpec,
} from './types';

/** Lists load up to 100 items (the API maximum); tables page and filter them client-side. */
const ALL = { size: 100 };

// ------------------------------------------------------------------ identity

export const getMe = () => api<Me>('/me');
export const listRoles = () => api<Role[]>('/roles');
export const listOrganizations = () => api<Page<Organization>>('/organizations', { query: ALL });
export const createOrganization = (body: {
  name: string; displayName?: string; description?: string; adminEmail?: string; createDefaultEnvironments?: boolean;
}) => api<Organization>('/organizations', { method: 'POST', body });

// ------------------------------------------------------------------ environments & target servers

export const listEnvironments = (org: string) =>
  api<Page<Environment>>(path('/organizations/{0}/environments', org), { query: ALL });
export const createEnvironment = (org: string, body: { name: string; displayName?: string; description?: string }) =>
  api<Environment>(path('/organizations/{0}/environments', org), { method: 'POST', body });
export const updateEnvironment = (org: string, env: string, body: { displayName?: string; description?: string }) =>
  api<Environment>(path('/organizations/{0}/environments/{1}', org, env), { method: 'PATCH', body });
export const deleteEnvironment = (org: string, env: string) =>
  api<void>(path('/organizations/{0}/environments/{1}', org, env), { method: 'DELETE' });

export type TargetServerBody = Omit<TargetServer, 'id' | 'baseUri' | 'createdAt' | 'createdBy' | 'updatedAt' | 'updatedBy'>;
export const listTargetServers = (org: string, env: string) =>
  api<Page<TargetServer>>(path('/organizations/{0}/environments/{1}/target-servers', org, env), { query: ALL });
export const createTargetServer = (org: string, env: string, body: TargetServerBody) =>
  api<TargetServer>(path('/organizations/{0}/environments/{1}/target-servers', org, env), { method: 'POST', body });
export const updateTargetServer = (org: string, env: string, name: string, body: Omit<TargetServerBody, 'name'>) =>
  api<TargetServer>(path('/organizations/{0}/environments/{1}/target-servers/{2}', org, env, name), { method: 'PUT', body });
export const deleteTargetServer = (org: string, env: string, name: string) =>
  api<void>(path('/organizations/{0}/environments/{1}/target-servers/{2}', org, env, name), { method: 'DELETE' });

// ------------------------------------------------------------------ members

export const listMembers = (org: string) =>
  api<Page<Member>>(path('/organizations/{0}/members', org), { query: ALL });
export const addMember = (org: string, email: string, roles: string[]) =>
  api<Member>(path('/organizations/{0}/members', org), { method: 'POST', body: { email, roles } });
export const updateMemberRoles = (org: string, userId: string, roles: string[]) =>
  api<Member>(path('/organizations/{0}/members/{1}/roles', org, userId), { method: 'PUT', body: { roles } });
export const removeMember = (org: string, userId: string) =>
  api<void>(path('/organizations/{0}/members/{1}', org, userId), { method: 'DELETE' });

// ------------------------------------------------------------------ API proxies

export const listPolicyTypes = () => api<PolicyType[]>('/policy-types');
export const listProxies = (org: string) => api<Page<ApiProxy>>(path('/organizations/{0}/apis', org), { query: ALL });
export const getProxy = (org: string, api_: string) => api<ApiProxy>(path('/organizations/{0}/apis/{1}', org, api_));
export const createProxy = (org: string, body: {
  name: string; displayName?: string; description?: string; revision: RevisionRequest;
}) => api<{ apiProxy: ApiProxy; revision: Revision }>(path('/organizations/{0}/apis', org), { method: 'POST', body });
export const importOpenApi = (org: string, body: {
  name: string; displayName?: string; basePath?: string; target: TargetSpec; spec: string;
}) => api<ImportResult>(path('/organizations/{0}/apis/import', org), { method: 'POST', body });
export const updateProxy = (org: string, name: string, body: { displayName?: string; description?: string }) =>
  api<ApiProxy>(path('/organizations/{0}/apis/{1}', org, name), { method: 'PATCH', body });
export const deleteProxy = (org: string, name: string) =>
  api<void>(path('/organizations/{0}/apis/{1}', org, name), { method: 'DELETE' });
export const listRevisions = (org: string, name: string) =>
  api<Page<Revision>>(path('/organizations/{0}/apis/{1}/revisions', org, name), { query: ALL });
export const getRevision = (org: string, name: string, rev: number) =>
  api<Revision>(path('/organizations/{0}/apis/{1}/revisions/{2}', org, name, rev));
export const createRevision = (org: string, name: string, body: RevisionRequest) =>
  api<Revision>(path('/organizations/{0}/apis/{1}/revisions', org, name), { method: 'POST', body });
export const deleteRevision = (org: string, name: string, rev: number) =>
  api<void>(path('/organizations/{0}/apis/{1}/revisions/{2}', org, name, rev), { method: 'DELETE' });
export const getOperations = (org: string, name: string, rev: number) =>
  api<{ specVersion: string; title?: string; apiVersion?: string; operations: Operation[] }>(
    path('/organizations/{0}/apis/{1}/revisions/{2}/operations', org, name, rev));
export const getOpenApiSpec = (org: string, name: string, rev: number) =>
  api<string>(path('/organizations/{0}/apis/{1}/revisions/{2}/openapi', org, name, rev), { text: true });
export const listProxyDeployments = (org: string, name: string) =>
  api<Deployment[]>(path('/organizations/{0}/apis/{1}/deployments', org, name));
export const listEnvironmentDeployments = (org: string, env: string) =>
  api<Deployment[]>(path('/organizations/{0}/environments/{1}/deployments', org, env));
export const deployRevision = (org: string, env: string, name: string, rev: number) =>
  api<Deployment>(path('/organizations/{0}/environments/{1}/apis/{2}/revisions/{3}/deployments', org, env, name, rev),
    { method: 'POST' });
export const undeployRevision = (org: string, env: string, name: string, rev: number) =>
  api<void>(path('/organizations/{0}/environments/{1}/apis/{2}/revisions/{3}/deployments', org, env, name, rev),
    { method: 'DELETE' });

// ------------------------------------------------------------------ shared flows

export const listSharedFlows = (org: string) =>
  api<Page<SharedFlow>>(path('/organizations/{0}/sharedflows', org), { query: ALL });
export const createSharedFlow = (org: string, body: {
  name: string; displayName?: string; description?: string;
  revision: { description?: string; policies: PolicyDefinition[]; steps: FlowStep[] };
}) => api<{ sharedFlow: SharedFlow; revision: SharedFlowRevision }>(path('/organizations/{0}/sharedflows', org),
  { method: 'POST', body });
export const deleteSharedFlow = (org: string, name: string) =>
  api<void>(path('/organizations/{0}/sharedflows/{1}', org, name), { method: 'DELETE' });
export const listSharedFlowRevisions = (org: string, name: string) =>
  api<Page<SharedFlowRevision>>(path('/organizations/{0}/sharedflows/{1}/revisions', org, name),
    { query: ALL });
export const createSharedFlowRevision = (org: string, name: string, body: {
  description?: string; policies: PolicyDefinition[]; steps: FlowStep[];
}) => api<SharedFlowRevision>(path('/organizations/{0}/sharedflows/{1}/revisions', org, name), { method: 'POST', body });
export const listSharedFlowDeployments = (org: string, name: string) =>
  api<SharedFlowDeployment[]>(path('/organizations/{0}/sharedflows/{1}/deployments', org, name));
export const deploySharedFlow = (org: string, env: string, name: string, rev: number) =>
  api<SharedFlowDeployment>(path('/organizations/{0}/environments/{1}/sharedflows/{2}/revisions/{3}/deployments',
    org, env, name, rev), { method: 'POST' });
export const undeploySharedFlow = (org: string, env: string, name: string, rev: number) =>
  api<void>(path('/organizations/{0}/environments/{1}/sharedflows/{2}/revisions/{3}/deployments',
    org, env, name, rev), { method: 'DELETE' });

// ------------------------------------------------------------------ products

export const listProducts = (org: string) =>
  api<Page<ApiProduct>>(path('/organizations/{0}/apiproducts', org), { query: ALL });
export const createProduct = (org: string, body: ApiProductRequest & { name: string }) =>
  api<ApiProduct>(path('/organizations/{0}/apiproducts', org), { method: 'POST', body });
export const updateProduct = (org: string, name: string, body: ApiProductRequest) =>
  api<ApiProduct>(path('/organizations/{0}/apiproducts/{1}', org, name), { method: 'PUT', body });
export const deleteProduct = (org: string, name: string) =>
  api<void>(path('/organizations/{0}/apiproducts/{1}', org, name), { method: 'DELETE' });

// ------------------------------------------------------------------ developers & apps

export const listDevelopers = (org: string) =>
  api<Page<Developer>>(path('/organizations/{0}/developers', org), { query: ALL });
export const getDeveloper = (org: string, email: string) =>
  api<Developer>(path('/organizations/{0}/developers/{1}', org, email));
export const createDeveloper = (org: string, body: {
  email: string; firstName: string; lastName: string; userName?: string; attributes?: Record<string, string>;
}) => api<Developer>(path('/organizations/{0}/developers', org), { method: 'POST', body });
export const updateDeveloper = (org: string, email: string, body: Partial<Pick<Developer,
  'firstName' | 'lastName' | 'userName' | 'attributes' | 'status'>>) =>
  api<Developer>(path('/organizations/{0}/developers/{1}', org, email), { method: 'PATCH', body });
export const deleteDeveloper = (org: string, email: string) =>
  api<void>(path('/organizations/{0}/developers/{1}', org, email), { method: 'DELETE' });

export const listAllApps = (org: string) =>
  api<Page<DeveloperApp>>(path('/organizations/{0}/apps', org), { query: ALL });
export const listDeveloperApps = (org: string, email: string) =>
  api<Page<DeveloperApp>>(path('/organizations/{0}/developers/{1}/apps', org, email), { query: ALL });
export const getApp = (org: string, email: string, app: string) =>
  api<DeveloperApp>(path('/organizations/{0}/developers/{1}/apps/{2}', org, email, app));
export const createApp = (org: string, email: string, body: {
  name: string; displayName?: string; description?: string; callbackUrl?: string; apiProducts?: string[];
  keyExpiresInSeconds?: number; attributes?: Record<string, string>;
}) => api<{ app: DeveloperApp; credential: IssuedCredential }>(path('/organizations/{0}/developers/{1}/apps', org, email),
  { method: 'POST', body });
export const updateApp = (org: string, email: string, app: string, body: Partial<Pick<DeveloperApp,
  'displayName' | 'description' | 'callbackUrl' | 'attributes' | 'status'>>) =>
  api<DeveloperApp>(path('/organizations/{0}/developers/{1}/apps/{2}', org, email, app), { method: 'PATCH', body });
export const deleteApp = (org: string, email: string, app: string) =>
  api<void>(path('/organizations/{0}/developers/{1}/apps/{2}', org, email, app), { method: 'DELETE' });

const keys = (org: string, email: string, app: string) =>
  path('/organizations/{0}/developers/{1}/apps/{2}/keys', org, email, app);
export const createKey = (org: string, email: string, app: string, body: { apiProducts?: string[]; expiresInSeconds?: number }) =>
  api<IssuedCredential>(keys(org, email, app), { method: 'POST', body });
export const updateKey = (org: string, email: string, app: string, keyId: string,
  body: { status?: 'APPROVED' | 'REVOKED'; expiresAt?: string }) =>
  api<Credential>(`${keys(org, email, app)}/${encodeURIComponent(keyId)}`, { method: 'PATCH', body });
export const rotateKey = (org: string, email: string, app: string, keyId: string,
  body: { graceSeconds?: number; expiresInSeconds?: number }) =>
  api<{ credential: IssuedCredential; previous: Credential }>(`${keys(org, email, app)}/${encodeURIComponent(keyId)}/rotate`,
    { method: 'POST', body });
export const deleteKey = (org: string, email: string, app: string, keyId: string) =>
  api<void>(`${keys(org, email, app)}/${encodeURIComponent(keyId)}`, { method: 'DELETE' });
export const addKeyProduct = (org: string, email: string, app: string, keyId: string, apiProduct: string) =>
  api<Credential>(`${keys(org, email, app)}/${encodeURIComponent(keyId)}/apiproducts`, { method: 'POST', body: { apiProduct } });
export const setKeyProductStatus = (org: string, email: string, app: string, keyId: string, product: string,
  status: 'APPROVED' | 'PENDING' | 'REVOKED') =>
  api<Credential>(`${keys(org, email, app)}/${encodeURIComponent(keyId)}/apiproducts/${encodeURIComponent(product)}`,
    { method: 'PATCH', body: { status } });
export const removeKeyProduct = (org: string, email: string, app: string, keyId: string, product: string) =>
  api<Credential>(`${keys(org, email, app)}/${encodeURIComponent(keyId)}/apiproducts/${encodeURIComponent(product)}`,
    { method: 'DELETE' });

// ------------------------------------------------------------------ key value maps (env null = organization scope)

const kvmBase = (org: string, env: string | null) =>
  env ? path('/organizations/{0}/environments/{1}/keyvaluemaps', org, env) : path('/organizations/{0}/keyvaluemaps', org);
export const listKvms = (org: string, env: string | null) => api<KeyValueMap[]>(kvmBase(org, env));
export const createKvm = (org: string, env: string | null, body: { name: string; encrypted: boolean }) =>
  api<KeyValueMap>(kvmBase(org, env), { method: 'POST', body });
export const deleteKvm = (org: string, env: string | null, name: string) =>
  api<void>(`${kvmBase(org, env)}/${encodeURIComponent(name)}`, { method: 'DELETE' });
export const listKvmEntries = (org: string, env: string | null, name: string) =>
  api<Page<KeyValueEntry>>(`${kvmBase(org, env)}/${encodeURIComponent(name)}/entries`, { query: ALL });
export const putKvmEntry = (org: string, env: string | null, name: string, key: string, value: string) =>
  api<KeyValueEntry>(`${kvmBase(org, env)}/${encodeURIComponent(name)}/entries/${encodeURIComponent(key)}`,
    { method: 'PUT', body: { value } });
export const deleteKvmEntry = (org: string, env: string | null, name: string, key: string) =>
  api<void>(`${kvmBase(org, env)}/${encodeURIComponent(name)}/entries/${encodeURIComponent(key)}`, { method: 'DELETE' });

// ------------------------------------------------------------------ analytics

export interface AnalyticsFilter {
  from?: string;
  to?: string;
  environment?: string;
  apiProxy?: string;
}

const analyticsPath = (org: string, what: string) => path('/organizations/{0}/analytics/', org) + what;

export const getAnalyticsSummary = (org: string, f: AnalyticsFilter) =>
  api<AnalyticsSummary>(analyticsPath(org, 'summary'), { query: { ...f } });
export const getAnalyticsTimeseries = (org: string, f: AnalyticsFilter) =>
  api<AnalyticsTimeseries>(analyticsPath(org, 'timeseries'), { query: { ...f } });
export const getAnalyticsTop = (org: string, dimension: AnalyticsDimension, f: AnalyticsFilter, limit = 10) =>
  api<AnalyticsTop>(analyticsPath(org, 'top'), { query: { ...f, dimension, limit } });
export const getAnalyticsEvents = (org: string, f: AnalyticsFilter, status?: string, limit = 100) =>
  api<AnalyticsEvents>(analyticsPath(org, 'events'), { query: { ...f, status, limit } });

// ------------------------------------------------------------------ trace

export const listTraceSessions = (org: string, env: string, apiProxy: string) =>
  api<TraceSession[]>(path('/organizations/{0}/environments/{1}/apis/{2}/trace-sessions', org, env, apiProxy));
export const startTraceSession = (org: string, env: string, apiProxy: string, body: StartTraceRequest) =>
  api<TraceSession>(path('/organizations/{0}/environments/{1}/apis/{2}/trace-sessions', org, env, apiProxy),
    { method: 'POST', body });
export const stopTraceSession = (org: string, id: string) =>
  api<TraceSession>(path('/organizations/{0}/trace-sessions/{1}', org, id), { method: 'DELETE' });
export const listTraceTransactions = (org: string, id: string) =>
  api<TraceTransaction[]>(path('/organizations/{0}/trace-sessions/{1}/transactions', org, id));
export const traceStreamPath = (org: string, id: string) => path('/organizations/{0}/trace-sessions/{1}/stream', org, id);

// ------------------------------------------------------------------ audit

export interface AuditFilter {
  q?: string;
  resourceType?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export const listAuditEvents = (org: string, f: AuditFilter) =>
  api<Page<AuditEvent>>(path('/organizations/{0}/audit-events', org), { query: { ...f } });
export const listAuditResourceTypes = (org: string) =>
  api<string[]>(path('/organizations/{0}/audit-events/resource-types', org));
