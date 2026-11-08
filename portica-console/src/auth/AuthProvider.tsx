import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Button, Result, Spin } from 'antd';
import type { User } from 'oidc-client-ts';
import { CALLBACK_PATH, login, userManager } from './oidc';

interface AuthState {
  user: User;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

// The OIDC callback can be processed only once; React may run effects twice in development.
let callback: Promise<User> | null = null;

type Status = { kind: 'loading' } | { kind: 'ready'; user: User } | { kind: 'error'; message: string };

function safeReturnTo(value: unknown): string {
  // Only same-origin paths: never redirect to a URL carried in the OIDC state.
  // Browsers treat "\" like "/", so "/\evil.com" would mean another site.
  return typeof value === 'string'
    && value.startsWith('/')
    && !value.startsWith('//')
    && !value.includes('\\')
    ? value
    : '/';
}

/**
 * Requires a signed-in user before rendering the console. Handles the OIDC callback, keeps the
 * user in sync with token renewal, and redirects to the login page when the session ends.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });

  useEffect(() => {
    let cancelled = false;

    async function start() {
      try {
        if (window.location.pathname === CALLBACK_PATH) {
          callback ??= userManager.signinRedirectCallback();
          const user = await callback;
          const state = user.state as { returnTo?: unknown } | undefined;
          window.history.replaceState(null, '', safeReturnTo(state?.returnTo));
          if (!cancelled) setStatus({ kind: 'ready', user });
          return;
        }
        const user = await userManager.getUser();
        if (user && !user.expired) {
          if (!cancelled) setStatus({ kind: 'ready', user });
          return;
        }
        await login();
      } catch (e) {
        if (!cancelled) setStatus({ kind: 'error', message: e instanceof Error ? e.message : String(e) });
      }
    }

    const onLoaded = (user: User) => setStatus({ kind: 'ready', user });
    const onExpired = () => void login();
    userManager.events.addUserLoaded(onLoaded);
    userManager.events.addAccessTokenExpired(onExpired);
    userManager.events.addSilentRenewError(onExpired);
    void start();
    return () => {
      cancelled = true;
      userManager.events.removeUserLoaded(onLoaded);
      userManager.events.removeAccessTokenExpired(onExpired);
      userManager.events.removeSilentRenewError(onExpired);
    };
  }, []);

  const value = useMemo<AuthState | null>(
    () =>
      status.kind === 'ready'
        ? { user: status.user, logout: () => userManager.signoutRedirect() }
        : null,
    [status],
  );

  if (status.kind === 'error') {
    return (
      <Result
        status="warning"
        title="Sign-in failed"
        subTitle={`${status.message}. Check that Keycloak is running (docker compose up -d) and try again.`}
        extra={
          <Button type="primary" onClick={() => void login('/')}>
            Sign in again
          </Button>
        }
      />
    );
  }
  if (!value) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '100vh' }}>
        <Spin size="large" tip="Signing in…">
          <div style={{ width: 200, height: 80 }} />
        </Spin>
      </div>
    );
  }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
