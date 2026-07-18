// Session context: holds the authenticated AgmClient for the whole console.
// baseUrl is '' so the SDK fetches /api/... which the Vite proxy forwards to :8080.

import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { AgmClient, type AuthResponse } from '@agm/sdk';

export const BASE_URL = ''; // dev proxy handles /api → :8080

interface Session {
  client: AgmClient;
  auth: AuthResponse;
}

interface SessionCtx {
  session: Session | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<SessionCtx | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);

  const value = useMemo<SessionCtx>(
    () => ({
      session,
      async login(username, password) {
        const client = new AgmClient(BASE_URL);
        const auth = await client.login(username, password);
        setSession({ client, auth });
      },
      logout() {
        session?.client.logout().catch(() => {});
        setSession(null);
      },
    }),
    [session],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): SessionCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSession outside SessionProvider');
  return v;
}

/** The authed client — call only inside authenticated views. */
export function useClient(): AgmClient {
  const { session } = useSession();
  if (!session) throw new Error('no session');
  return session.client;
}
