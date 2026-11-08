/** Shapes of the management API (see the Swagger UI at /swagger-ui.html). Timestamps are ISO-8601 strings. */

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Audit {
  createdAt: string;
  createdBy?: string;
  updatedAt: string;
  updatedBy?: string;
}

// ------------------------------------------------------------------ identity & organizations

export interface Membership {
  organization: string;
  organizationDisplayName: string;
  roles: string[];
  permissions: string[];
}

export interface Me {
  id: string;
  subject: string;
  email: string;
  displayName: string;
  platformAdmin: boolean;
  memberships: Membership[];
}

export interface Organization extends Audit {
  id: string;
  name: string;
  displayName: string;
  description?: string;
  status: string;
}

export interface Environment extends Audit {
  id: string;
  name: string;
  displayName: string;
  description?: string;
}

export interface Member {
  userId: string;
  email: string;
  displayName?: string;
  status: string;
  roles: string[];
  lastLoginAt?: string;
}

export interface Role {
  code: string;
  displayName: string;
  description?: string;
  builtIn: boolean;
  permissions: string[];
}

export type Permission =
  | 'ORG_READ' | 'ORG_UPDATE'
  | 'ENV_READ' | 'ENV_CREATE' | 'ENV_UPDATE' | 'ENV_DELETE'
  | 'TARGET_SERVER_WRITE'
  | 'MEMBER_READ' | 'MEMBER_MANAGE'
  | 'AUDIT_READ'
  | 'PROXY_READ' | 'PROXY_WRITE' | 'PROXY_DEPLOY'
  | 'PRODUCT_READ' | 'PRODUCT_WRITE'
  | 'DEVELOPER_READ' | 'DEVELOPER_WRITE'
  | 'ANALYTICS_READ' | 'TRACE_RUN' | 'AI_READ' | 'AI_WRITE' | 'MCP_READ' | 'MCP_WRITE';

export interface TargetServer extends Audit {
  id: string;
  name: string;
  host: string;
  port: number;
  protocol: 'HTTP' | 'HTTPS';
  enabled: boolean;
  description?: string;
  baseUri: string;
}

// ------------------------------------------------------------------ proxies and policies

export interface FlowStep {
  policy: string;
  condition?: string | null;
}

export interface FlowPair {
  request: FlowStep[];
  response: FlowStep[];
}

export interface ConditionalFlow {
  name: string;
  condition?: string | null;
  request: FlowStep[];
  response: FlowStep[];
}

export interface FaultRule {
  name: string;
  condition?: string | null;
  steps: FlowStep[];
}

export interface ProxyFlows {
  preFlow: FlowPair;
  conditionalFlows: ConditionalFlow[];
  postFlow: FlowPair;
  faultRules: FaultRule[];
  defaultFaultRule?: FaultRule | null;
}

export interface PolicyDefinition {
  name: string;
  type: string;
  enabled?: boolean | null;
  continueOnError?: boolean | null;
  config: Record<string, unknown>;
}

export interface PolicyType {
  type: string;
  category: string;
  description: string;
  configFields: string[];
}

export interface ApiProxy extends Audit {
  id: string;
  name: string;
  displayName: string;
  description?: string;
  latestRevision: number;
}

export interface TargetSpec {
  url?: string | null;
  server?: string | null;
  path?: string | null;
}

export interface Revision {
  apiProxy: string;
  revision: number;
  description?: string;
  basePath: string;
  target: TargetSpec;
  connectTimeoutMs: number;
  readTimeoutMs: number;
  hasOpenApiSpec: boolean;
  policies: PolicyDefinition[];
  flows: ProxyFlows;
  createdAt: string;
  createdBy?: string;
}

export interface RevisionRequest {
  basePath: string;
  target: TargetSpec;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  description?: string;
  openApiSpec?: string;
  policies?: PolicyDefinition[];
  flows?: ProxyFlows;
}

export interface Deployment {
  environment: string;
  apiProxy: string;
  revision: number;
  basePath: string;
  gatewayPath: string;
  previousRevision?: number | null;
  deployedAt: string;
  deployedBy?: string;
}

export interface Operation {
  method: string;
  path: string;
  operationId?: string;
  summary?: string;
}

export interface ImportResult {
  apiProxy: ApiProxy;
  revision: Revision;
  createdApiProxy: boolean;
  specVersion: string;
  apiVersion?: string;
  operationCount: number;
}

export interface SharedFlow extends Audit {
  id: string;
  name: string;
  displayName: string;
  description?: string;
  latestRevision: number;
}

export interface SharedFlowRevision {
  sharedFlow: string;
  revision: number;
  description?: string;
  policies: PolicyDefinition[];
  steps: FlowStep[];
  createdAt: string;
  createdBy?: string;
}

export interface SharedFlowDeployment {
  environment: string;
  sharedFlow: string;
  revision: number;
  previousRevision?: number | null;
  deployedAt: string;
  deployedBy?: string;
}

// ------------------------------------------------------------------ products, developers, apps

export interface ProductQuota {
  count: number;
  interval: number;
  timeUnit: 'second' | 'minute' | 'hour' | 'day' | 'week';
}

export interface ApiProduct extends Audit {
  id: string;
  name: string;
  displayName: string;
  description?: string;
  approvalType: 'AUTO' | 'MANUAL';
  access: 'PUBLIC' | 'PRIVATE' | 'INTERNAL';
  environments: string[];
  proxies: string[];
  paths: string[];
  scopes: string[];
  quota?: ProductQuota | null;
  attributes: Record<string, string>;
}

export type ApiProductRequest = Omit<ApiProduct, keyof Audit | 'id' | 'name'>;

export interface Developer extends Audit {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  userName?: string;
  status: 'ACTIVE' | 'INACTIVE';
  attributes: Record<string, string>;
}

export type GrantStatus = 'APPROVED' | 'PENDING' | 'REVOKED';

export interface ProductGrant {
  apiProduct: string;
  status: GrantStatus;
}

export interface Credential {
  id: string;
  keyPrefix: string;
  status: 'APPROVED' | 'REVOKED';
  expiresAt?: string | null;
  expired: boolean;
  apiProducts: ProductGrant[];
  createdAt: string;
}

export interface IssuedCredential {
  id: string;
  consumerKey: string;
  consumerSecret: string;
  keyPrefix: string;
  status: 'APPROVED' | 'REVOKED';
  expiresAt?: string | null;
  apiProducts: ProductGrant[];
}

export interface DeveloperApp extends Audit {
  id: string;
  name: string;
  displayName: string;
  developerEmail: string;
  description?: string;
  callbackUrl?: string;
  status: 'APPROVED' | 'REVOKED';
  attributes: Record<string, string>;
  credentials: Credential[];
}

export interface KeyValueMap {
  id: string;
  name: string;
  scope: 'environment' | 'organization';
  environment?: string | null;
  encrypted: boolean;
  entryCount: number;
  createdAt: string;
  createdBy?: string;
}

export interface KeyValueEntry {
  key: string;
  value: string;
  updatedAt: string;
  updatedBy?: string;
}

// ------------------------------------------------------------------ analytics (Phase 6)

export interface AnalyticsWindow {
  from: string;
  to: string;
  environment?: string | null;
  apiProxy?: string | null;
}

export interface AnalyticsSummary {
  window: AnalyticsWindow;
  requests: number;
  errors4xx: number;
  errors5xx: number;
  errorRate: number;
  avgMs: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  avgTargetMs: number;
  cacheHits: number;
}

export interface TimeseriesPoint {
  time: string;
  requests: number;
  errors: number;
  avgMs: number;
  p95Ms: number;
}

export interface AnalyticsTimeseries {
  window: AnalyticsWindow;
  bucketSeconds: number;
  points: TimeseriesPoint[];
}

export type AnalyticsDimension = 'api_proxy' | 'environment' | 'app' | 'api_product' | 'developer' | 'status' | 'fault' | 'gateway';

export interface TopEntry {
  name: string;
  requests: number;
  errors: number;
  avgMs: number;
}

export interface AnalyticsTop {
  window: AnalyticsWindow;
  dimension: AnalyticsDimension;
  entries: TopEntry[];
}

export interface ApiEvent {
  time: string;
  requestId: string;
  environment: string;
  apiProxy: string;
  revision: number;
  method: string;
  path: string;
  status: number;
  totalMs: number;
  targetMs: number;
  app: string;
  developer: string;
  apiProduct: string;
  fault: string;
  clientIp: string;
  gateway: string;
}

export interface AnalyticsEvents {
  window: AnalyticsWindow;
  events: ApiEvent[];
}

// ------------------------------------------------------------------ trace (Phase 6)

export type TraceState = 'ACTIVE' | 'EXPIRED' | 'STOPPED';

export interface TraceSession {
  id: string;
  environment: string;
  apiProxy: string;
  filterHeader?: string | null;
  filterValue?: string | null;
  filterClientIp?: string | null;
  maxTransactions: number;
  state: TraceState;
  createdBy?: string | null;
  createdAt: string;
  expiresAt: string;
  stoppedAt?: string | null;
}

export interface StartTraceRequest {
  filterHeader?: string;
  filterValue?: string;
  filterClientIp?: string;
  durationSeconds?: number;
  maxTransactions?: number;
}

export type TraceOutcome = 'executed' | 'skipped' | 'fault' | 'continued';

export interface TraceStep {
  flow: string;
  policy: string;
  type: string;
  condition?: string;
  outcome: TraceOutcome;
  durationMs: number;
  atMs: number;
  fault?: string;
}

export interface TracePayload {
  requestId?: string;
  startedAt?: string;
  totalMs?: number;
  request?: { method: string; uri: string; headers: Record<string, string>; clientIp?: string };
  steps?: TraceStep[];
  target?: { url: string; status: number; durationMs: number } | null;
  response?: { status: number; headers: Record<string, string> };
  fault?: { name: string; status: number; message: string } | null;
  variables?: Record<string, unknown>;
}

export interface TraceTransaction {
  requestId: string;
  time: string;
  status: number;
  totalMs: number;
  payload: TracePayload;
}

// ------------------------------------------------------------------ audit

export interface AuditEvent {
  id: string;
  action: string;
  resourceType: string;
  resourceName: string;
  actor?: string | null;
  details: Record<string, unknown>;
  requestId?: string | null;
  occurredAt: string;
}
