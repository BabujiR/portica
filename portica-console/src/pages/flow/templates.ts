/** Starter configuration for each policy type, shown when a policy is added. The API validates the result. */
export const POLICY_TEMPLATES: Record<string, Record<string, unknown>> = {
  VerifyAPIKey: { apiKey: '{request.header.x-api-key}' },
  OAuthV2: { operation: 'VerifyAccessToken', requiredScopes: [] },
  VerifyJWT: { source: '{request.header.authorization}', algorithm: 'RS256', jwksUri: 'https://idp.example.com/.well-known/jwks.json', issuer: 'https://idp.example.com', audiences: [] },
  AccessControl: { allow: ['10.0.0.0/8'], deny: [] },
  RegularExpressionProtection: { path: ['\\.\\./'], queryParams: { '*': ['(?i)\\bunion\\b.+\\bselect\\b'] }, headers: {}, body: ['(?i)<script'] },
  JSONThreatProtection: { maxDepth: 10, maxObjectEntries: 100, maxArrayElements: 1000, maxStringLength: 10000, maxNameLength: 128, maxBodyBytes: 1048576 },
  Quota: { count: 1000, interval: 1, timeUnit: 'hour', identifier: '{client_id}' },
  SpikeArrest: { rate: '100ps', identifier: '{client.ip}', failOpen: true },
  VerifyClientCertificate: { bindToApp: false },
  ResponseCache: { ttlSeconds: 60 },
  CORS: { allowOrigins: ['https://app.example.com'], allowMethods: ['GET', 'POST'], allowHeaders: ['*'], maxAgeSeconds: 600 },
  AssignMessage: { target: 'request', setHeaders: { 'X-Forwarded-By': 'portica' }, removeHeaders: [] },
  JSONToXML: { target: 'response', rootElement: 'root' },
  XMLToJSON: { target: 'response', recognizeNumbers: true, recognizeBooleans: true },
  RaiseFault: { statusCode: 403, contentType: 'application/json', body: '{"error":"forbidden"}' },
  ServiceCallout: { url: 'https://lookup.example.com/items/{request.queryparam.id}', method: 'GET', timeoutMs: 2000, failOnError: true },
  FlowCallout: { sharedFlow: 'common' },
  KeyValueMapOperations: { mapName: 'backend-config', scope: 'environment', get: [{ key: 'url', assignTo: 'backend.url' }] },
};

export function templateFor(type: string): Record<string, unknown> {
  return JSON.parse(JSON.stringify(POLICY_TEMPLATES[type] ?? {})) as Record<string, unknown>;
}

/** Colours per category, used on palette items and flow steps. */
export const CATEGORY_COLORS: Record<string, string> = {
  Security: 'red',
  'Threat protection': 'volcano',
  'Traffic management': 'gold',
  Mediation: 'blue',
  Extension: 'purple',
};
