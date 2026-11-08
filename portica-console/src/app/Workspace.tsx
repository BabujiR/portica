import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getMe, listEnvironments, listOrganizations } from '../api/endpoints';
import type { Environment, Me, Permission } from '../api/types';

export interface OrgOption {
  name: string;
  displayName: string;
}

interface WorkspaceState {
  me: Me;
  organizations: OrgOption[];
  org: string;
  environments: Environment[];
  /** Selected environment (remembered per organization); null when the organization has none. */
  env: string | null;
  setEnv: (env: string) => void;
  can: (permission: Permission) => boolean;
}

const WorkspaceContext = createContext<WorkspaceState | null>(null);

export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: getMe, staleTime: 60_000 });
}

/** Organizations the user can switch to: memberships, or all of them for platform administrators. */
export function useOrganizations(me: Me | undefined) {
  const all = useQuery({
    queryKey: ['organizations'],
    queryFn: listOrganizations,
    enabled: !!me?.platformAdmin,
    staleTime: 60_000,
  });
  return useMemo<OrgOption[]>(() => {
    if (!me) return [];
    const options = new Map<string, OrgOption>();
    me.memberships.forEach((m) => options.set(m.organization, { name: m.organization, displayName: m.organizationDisplayName }));
    all.data?.items.forEach((o) => options.set(o.name, { name: o.name, displayName: o.displayName }));
    return [...options.values()].sort((a, b) => a.displayName.localeCompare(b.displayName));
  }, [me, all.data]);
}

const envKey = (org: string) => `portica.env.${org}`;

export function WorkspaceProvider({ me, organizations, children }: { me: Me; organizations: OrgOption[]; children: ReactNode }) {
  const { org = '' } = useParams();
  const environments = useQuery({ queryKey: ['environments', org], queryFn: () => listEnvironments(org), enabled: !!org });
  const [env, setEnvState] = useState<string | null>(null);

  useEffect(() => {
    const items = environments.data?.items ?? [];
    let saved: string | null = null;
    try {
      saved = window.localStorage.getItem(envKey(org));
    } catch {
      saved = null;
    }
    const names = items.map((e) => e.name);
    setEnvState(saved && names.includes(saved) ? saved : names.includes('dev') ? 'dev' : (names[0] ?? null));
  }, [org, environments.data]);

  const setEnv = useCallback((value: string) => {
    setEnvState(value);
    try {
      window.localStorage.setItem(envKey(org), value);
    } catch {
      // ignore
    }
  }, [org]);

  const can = useCallback((permission: Permission) => {
    if (me.platformAdmin) return true;
    return me.memberships.some((m) => m.organization === org && m.permissions.includes(permission));
  }, [me, org]);

  const value = useMemo<WorkspaceState>(() => ({
    me, organizations, org, environments: environments.data?.items ?? [], env, setEnv, can,
  }), [me, organizations, org, environments.data, env, setEnv, can]);

  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace(): WorkspaceState {
  const ctx = useContext(WorkspaceContext);
  if (!ctx) throw new Error('useWorkspace must be used inside WorkspaceProvider');
  return ctx;
}
