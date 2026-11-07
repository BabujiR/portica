/** Where the console finds its backends. Runtime values (public/config.js) win over build-time VITE_* values. */
export interface PorticaConfig {
  apiBaseUrl: string;
  gatewayUrl: string;
  oidcAuthority: string;
  oidcClientId: string;
}

declare global {
  interface Window {
    __PORTICA_CONFIG__?: Partial<PorticaConfig>;
  }
}

const runtime: Partial<PorticaConfig> = (typeof window !== 'undefined' && window.__PORTICA_CONFIG__) || {};

function pick(runtimeValue: string | undefined, buildValue: string | undefined, fallback: string): string {
  const value = runtimeValue || buildValue || fallback;
  return value.replace(/\/+$/, '');
}

export const config: PorticaConfig = {
  apiBaseUrl: pick(runtime.apiBaseUrl, import.meta.env.VITE_API_BASE_URL, 'http://localhost:8080'),
  gatewayUrl: pick(runtime.gatewayUrl, import.meta.env.VITE_GATEWAY_URL, 'http://localhost:8081'),
  oidcAuthority: pick(runtime.oidcAuthority, import.meta.env.VITE_OIDC_AUTHORITY, 'http://localhost:8180/realms/portica'),
  oidcClientId: runtime.oidcClientId || import.meta.env.VITE_OIDC_CLIENT_ID || 'portica-console',
};
