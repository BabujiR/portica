import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { config } from '../config';

export const CALLBACK_PATH = '/auth/callback';

/**
 * OpenID Connect against Keycloak (or any OIDC provider): Authorization Code + PKCE, tokens kept in
 * session storage (gone when the tab closes), renewed with the refresh token before they expire.
 */
export const userManager = new UserManager({
  authority: config.oidcAuthority,
  client_id: config.oidcClientId,
  redirect_uri: `${window.location.origin}${CALLBACK_PATH}`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: 'openid profile email',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

/** Current access token, or null when the user isn't signed in (or the session expired). */
export async function accessToken(): Promise<string | null> {
  const user = await userManager.getUser();
  if (!user || user.expired) {
    return null;
  }
  return user.access_token;
}

/** Starts the login redirect, coming back to {@code returnTo} afterwards. */
export function login(returnTo: string = currentLocation()): Promise<void> {
  return userManager.signinRedirect({ state: { returnTo } });
}

export function currentLocation(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}
