import { config } from '../config';
import { accessToken, login } from '../auth/oidc';

/** An RFC 7807 problem from the management API. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  requestId?: string;
  errors?: Array<string | { field: string; message: string }>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(problem.detail || problem.title || `Request failed with status ${status}`);
    this.status = status;
    this.problem = problem;
  }

  /** Field and bundle errors, flattened for display. */
  get details(): string[] {
    return (this.problem.errors ?? []).map((e) => (typeof e === 'string' ? e : `${e.field}: ${e.message}`));
  }
}

type Query = Record<string, string | number | boolean | undefined | null>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Query;
  /** Response as text instead of JSON. */
  text?: boolean;
}

/** Encodes path segments: api('/organizations/{0}/apis/{1}', org, api). */
export function path(template: string, ...segments: Array<string | number>): string {
  return template.replace(/\{(\d+)}/g, (_, i: string) => encodeURIComponent(String(segments[Number(i)])));
}

function url(p: string, query?: Query): string {
  const u = new URL(`${config.apiBaseUrl}/api/v1${p}`);
  Object.entries(query ?? {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') u.searchParams.set(k, String(v));
  });
  return u.toString();
}

export async function api<T>(p: string, options: RequestOptions = {}): Promise<T> {
  const token = await accessToken();
  if (!token) {
    await login();
    throw new ApiError(401, { title: 'Signing in again' });
  }
  // text responses (OpenAPI documents) are served as text/plain; problems still come back as JSON
  const accept = options.text ? 'text/plain, application/problem+json, application/json' : 'application/json';
  const headers: Record<string, string> = { Authorization: `Bearer ${token}`, Accept: accept };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(url(p, options.query), {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (response.status === 401) {
    await login();
    throw new ApiError(401, { title: 'Your session ended; signing in again' });
  }
  if (!response.ok) {
    let problem: Problem = { status: response.status, title: response.statusText };
    try {
      problem = (await response.json()) as Problem;
    } catch {
      // not JSON: keep the status text
    }
    throw new ApiError(response.status, problem);
  }
  if (response.status === 204) return undefined as T;
  return (options.text ? await response.text() : await response.json()) as T;
}

/** Human-readable message for any error thrown by {@link api}. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const details = error.details;
    const ref = error.problem.requestId ? ` (request ${error.problem.requestId})` : '';
    return details.length ? `${error.message}: ${details.join('; ')}${ref}` : `${error.message}${ref}`;
  }
  return error instanceof Error ? error.message : String(error);
}
