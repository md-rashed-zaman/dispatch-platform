import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from "oidc-client-ts";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

/**
 * Login with Keycloak: authorization code + PKCE, as a public client (a browser can't keep a secret).
 * Tokens live in memory only, not in localStorage or sessionStorage, where any injected script could read
 * them. A reload therefore goes back through Keycloak, which answers at once while its session cookie lasts.
 */
const manager = new UserManager({
  authority: import.meta.env.VITE_KEYCLOAK_ISSUER ?? "http://localhost:8180/realms/dispatch",
  client_id: "dispatch-web",
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: window.location.origin,
  scope: "openid profile",
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  automaticSilentRenew: true, // with the refresh token, before the 5-minute access token expires
});

/** The current access token, renewed if it's about to expire. */
export async function accessToken(): Promise<string> {
  let user = await manager.getUser();
  if (!user || (user.expires_in ?? 0) < 30) {
    user = await manager.signinSilent();
  }
  if (!user) {
    throw new Error("Not signed in");
  }
  return user.access_token;
}

/**
 * Once per page load, whoever asks. An authorization code can be exchanged only once, and React (strict mode,
 * in development) runs effects twice: the second exchange would fail with "Code not valid".
 */
let signingIn: Promise<User | null> | null = null;

function signInOnce(): Promise<User | null> {
  signingIn ??= (async () => {
    if (window.location.pathname === "/auth/callback") {
      const signedIn = await manager.signinRedirectCallback();
      window.history.replaceState(null, "", typeof signedIn.state === "string" ? signedIn.state : "/");
      return signedIn;
    }
    const existing = await manager.getUser();
    if (existing && !existing.expired) {
      return existing;
    }
    await manager.signinRedirect({ state: window.location.pathname + window.location.search });
    return null; // the browser is leaving for Keycloak
  })();
  return signingIn;
}

type Auth = { user: User; signOut: () => void };
const AuthContext = createContext<Auth | null>(null);

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (!auth) {
    throw new Error("useAuth outside AuthGate");
  }
  return auth;
}

/** Renders its children only once signed in; otherwise sends the browser to Keycloak. */
export function AuthGate({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    signInOnce()
      .then((signedIn) => !cancelled && signedIn && setUser(signedIn))
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return (
      <div className="grid min-h-screen place-items-center p-6 text-center">
        <div>
          <p className="text-lg font-semibold">Couldn't sign you in</p>
          <p className="mt-1 text-sm text-muted-foreground">{error}</p>
          <button className="mt-4 text-link underline" onClick={() => window.location.assign("/")}>
            Try again
          </button>
        </div>
      </div>
    );
  }
  if (!user) {
    return <div className="grid min-h-screen place-items-center text-sm text-muted-foreground">Signing in…</div>;
  }
  return (
    <AuthContext value={{ user, signOut: () => void manager.signoutRedirect() }}>{children}</AuthContext>
  );
}
