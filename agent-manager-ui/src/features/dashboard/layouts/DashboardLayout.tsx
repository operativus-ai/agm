import React, { useState } from 'react';
import { NavLink as RouterNavLink, Outlet, useLocation, useMatches } from 'react-router-dom';
import { useAuth } from '../../auth/context/AuthContext';
import { incidentResponseApi } from '../../agents/api/incidentResponseApi';
import { cn } from '../../../shared/utils/cn';
import { Typography } from '../../../shared/components/ui/Typography';
import {
  LuSettings, LuMenu, LuLogOut, LuChevronDown, LuOctagonAlert, LuMail,
} from 'react-icons/lu';
import { NAV_PINNED, NAV_GROUPS, mergeNavGroups } from '../../../shared/nav/navManifest';
import type { NavItem as NavItemDef } from '../../../shared/nav/navManifest';
import { useLicense } from '../../../shared/license/useLicense';
// Edition nav manifest — resolves to an empty stub in the Core build (agm-core-oss-execution.md §4.5).
import { eeNavGroups } from '@ee/nav';
import { eeTopbarWidgets } from '@ee/topbar';

// Operator-configurable support contact. Set VITE_SUPPORT_EMAIL at build time
// to override the placeholder. Without this, the footer mailto link falls
// back to the example address and the operator must remember to set it
// before deploy. See agm-launch-checklist.md "operator email reachable from
// the UI" — Phase 0 requirement so first users have a way to report bugs.
const SUPPORT_EMAIL: string =
  (import.meta.env.VITE_SUPPORT_EMAIL as string | undefined) || 'support@example.com';
import { ActiveRunsTracker } from '../../agents/components/ActiveRunsTracker';
import { Tooltip } from '../../../shared/components/ui/Tooltip';
import { ErrorBoundary } from '../../../components/common/ErrorBoundary';

interface RouteHandle {
  title?: string;
}

// Nav structure comes from the manifest (Core groups + edition contributions).
// SECTION_PATHS maps each section key to its items' path prefixes, used to
// auto-expand a section when the user navigates directly to one of its routes.
const ALL_NAV_GROUPS = mergeNavGroups(NAV_GROUPS, eeNavGroups);
const SECTION_PATHS: Record<string, string[]> = Object.fromEntries(
  ALL_NAV_GROUPS.map(group => [group.key, group.items.map(item => item.to)])
);

function initSectionOpen(key: string, paths: string[], pathname: string): boolean {
  // Always expand if a child route is active (even if user previously collapsed)
  if (paths.some(p => pathname.startsWith(p))) return true;
  const stored = localStorage.getItem(`agm:nav:section:${key}`);
  return stored !== 'false'; // default open
}

export const DashboardLayout: React.FC = () => {
  const { user, logout } = useAuth();
  const location = useLocation();
  const matches = useMatches();

  const [isSidebarOpen, setIsSidebarOpen] = React.useState(
    () => localStorage.getItem('agm:sidebar:open') !== 'false'
  );

  const [openSections, setOpenSections] = React.useState<Record<string, boolean>>(() =>
    Object.fromEntries(
      Object.entries(SECTION_PATHS).map(([key, paths]) => [
        key,
        initSectionOpen(key, paths, location.pathname),
      ])
    )
  );

  // Auto-expand any section whose child route becomes active via direct navigation
  React.useEffect(() => {
    setOpenSections(prev => {
      const next = { ...prev };
      let changed = false;
      for (const [key, paths] of Object.entries(SECTION_PATHS)) {
        if (!prev[key] && paths.some(p => location.pathname.startsWith(p))) {
          next[key] = true;
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [location.pathname]);

  const toggleSidebar = () => {
    setIsSidebarOpen(prev => {
      const next = !prev;
      localStorage.setItem('agm:sidebar:open', String(next));
      return next;
    });
  };

  const toggleSection = (key: string) => {
    setOpenSections(prev => {
      const next = { ...prev, [key]: !prev[key] };
      localStorage.setItem(`agm:nav:section:${key}`, String(next[key]));
      return next;
    });
  };

  const isSuperAdmin = user?.roles?.includes('ROLE_SUPER_ADMIN') ?? false;
  const isAdmin = (user?.roles?.some((r) => r === 'ROLE_ADMIN' || r === 'ROLE_SUPER_ADMIN')) ?? false;
  const { license } = useLicense();
  const canSee = (item: NavItemDef): boolean => {
    if (item.featureKey && !license.features.includes(item.featureKey)) return false;
    return item.role === 'SUPER_ADMIN' ? isSuperAdmin : item.role === 'ADMIN' ? isAdmin : true;
  };
  const [halting, setHalting] = useState(false);
  const handleHaltAllRuns = async () => {
    if (!window.confirm('HALT ALL RUNS? This will immediately cancel every active run across all tenants. This cannot be undone.')) return;
    setHalting(true);
    try {
      const result = await incidentResponseApi.haltAllRuns('Emergency halt via dashboard');
      window.alert(`Halt complete. ${result.runsCancelled} run(s) cancelled across ${result.tenantsAffected} tenant(s).`);
    } catch (err) {
      window.alert(`Halt failed: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setHalting(false);
    }
  };

  const currentHandle = matches.at(-1)?.handle as RouteHandle | undefined;
  const pageTitle = currentHandle?.title ?? 'Dashboard';

  return (
    <div className="flex h-screen bg-obsidian-base text-theme-foreground font-sans">
      {/* Mobile backdrop — tap outside sidebar to close it */}
      {isSidebarOpen && (
        <div
          className="fixed inset-0 z-10 bg-black/50 md:hidden"
          onClick={() => {
            setIsSidebarOpen(false);
            localStorage.setItem('agm:sidebar:open', 'false');
          }}
        />
      )}

      {/* Sidebar */}
      <aside
        className={cn(
          "bg-obsidian-surface border-r border-obsidian-stroke/50 transition-all duration-300 flex flex-col fixed md:relative z-20 h-full",
          isSidebarOpen ? "w-64" : "w-16"
        )}
      >
        {/* Sidebar header / logo */}
        <div className="h-16 flex items-center justify-between px-4 border-b border-obsidian-stroke/50 shrink-0">
          {isSidebarOpen ? (
            <div className="font-bold text-lg text-agent-blue tracking-wider font-mono truncate">OperativusAI</div>
          ) : (
            <div className="mx-auto font-bold text-lg text-agent-blue font-mono">OAI</div>
          )}
        </div>

        {/* Navigation — rendered from the manifest (Core + edition contributions) */}
        <nav className="flex-1 py-4 overflow-y-auto px-2 scrollbar-thin scrollbar-thumb-obsidian-stroke scrollbar-track-transparent">
          {/* Pinned top — Dashboard & Chat */}
          <div className="space-y-1 mb-4">
            {NAV_PINNED.filter(canSee).map(item => (
              <NavLink key={item.to} href={item.to} icon={item.icon} isSidebarOpen={isSidebarOpen}>{item.label}</NavLink>
            ))}
          </div>

          {ALL_NAV_GROUPS.map(group => {
            const visibleItems = group.items.filter(canSee);
            if (visibleItems.length === 0) return null;
            return (
              <NavSection
                key={group.key}
                title={group.title}
                isOpen={openSections[group.key]}
                onToggle={() => toggleSection(group.key)}
                isSidebarOpen={isSidebarOpen}
                isTree={group.isTree}
              >
                {visibleItems.map(item => (
                  <NavLink key={item.to} href={item.to} icon={item.icon} isSidebarOpen={isSidebarOpen}>{item.label}</NavLink>
                ))}
              </NavSection>
            );
          })}
        </nav>

        {/* Footer — avatar first, then settings + logout */}
        <div className="p-4 border-t border-obsidian-stroke/50 shrink-0 mt-auto">
          {isSuperAdmin && (
            <div className={`mb-3 ${isSidebarOpen ? '' : 'flex justify-center'}`}>
              <Tooltip label="Halt All Runs (SUPER_ADMIN)">
                <button
                  onClick={handleHaltAllRuns}
                  disabled={halting}
                  className="flex items-center gap-2 w-full px-3 py-2 rounded-lg bg-red-950/40 border border-red-800/50 text-red-400 hover:bg-red-900/50 hover:border-red-600 transition-colors text-xs font-bold uppercase tracking-wider disabled:opacity-50"
                  title="Halt All Runs"
                >
                  <LuOctagonAlert className="w-4 h-4 shrink-0" />
                  {isSidebarOpen && <span>{halting ? 'Halting…' : 'Halt All Runs'}</span>}
                </button>
              </Tooltip>
            </div>
          )}
          {isSidebarOpen ? (
            <div className="flex items-center gap-2">
              <div className="avatar placeholder shrink-0">
                <div className="bg-obsidian-elevated text-theme-muted rounded-full w-8 h-8 flex items-center justify-center border border-obsidian-stroke">
                  <span className="text-xs font-mono font-bold">
                    {user?.username?.substring(0, 2).toUpperCase() || 'US'}
                  </span>
                </div>
              </div>
              <div className="overflow-hidden min-w-0 flex-1">
                <p className="text-sm font-medium text-theme-foreground truncate">{user?.username || 'User'}</p>
                <p className="text-xs text-theme-muted font-mono truncate">{user?.roles?.[0] || 'USER'}</p>
              </div>
              <a
                href={`mailto:${SUPPORT_EMAIL}`}
                className="p-1.5 rounded-md text-(--theme-muted) hover:text-(--theme-foreground) transition-colors shrink-0"
                title={`Email support (${SUPPORT_EMAIL})`}
              >
                <LuMail className="w-4 h-4" />
              </a>
              <RouterNavLink
                to="/settings"
                className={({ isActive }) => cn(
                  "p-1.5 rounded-md transition-colors shrink-0",
                  isActive ? "text-(--agent-blue)" : "text-(--theme-muted) hover:text-(--theme-foreground)"
                )}
                title="Settings"
              >
                <LuSettings className="w-4 h-4" />
              </RouterNavLink>
              <button
                onClick={logout}
                className="p-1.5 rounded-md text-red-500 hover:bg-red-500/10 transition-colors shrink-0"
                title="Logout"
              >
                <LuLogOut className="w-4 h-4" />
              </button>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-3">
              <div className="avatar placeholder">
                <div className="bg-obsidian-elevated text-theme-muted rounded-full w-8 h-8 flex items-center justify-center border border-obsidian-stroke">
                  <span className="text-xs font-mono font-bold">
                    {user?.username?.substring(0, 2).toUpperCase() || 'US'}
                  </span>
                </div>
              </div>
              <a
                href={`mailto:${SUPPORT_EMAIL}`}
                className="p-1.5 rounded-md text-(--theme-muted) hover:text-(--theme-foreground) transition-colors"
                title={`Email support (${SUPPORT_EMAIL})`}
              >
                <LuMail className="w-4 h-4" />
              </a>
              <RouterNavLink
                to="/settings"
                className={({ isActive }) => cn(
                  "p-1.5 rounded-md transition-colors",
                  isActive ? "text-(--agent-blue)" : "text-(--theme-muted) hover:text-(--theme-foreground)"
                )}
                title="Settings"
              >
                <LuSettings className="w-4 h-4" />
              </RouterNavLink>
              <button
                onClick={logout}
                className="p-1.5 rounded-md text-red-500 hover:bg-red-500/10 transition-colors"
                title="Logout"
              >
                <LuLogOut className="w-4 h-4" />
              </button>
            </div>
          )}
        </div>
      </aside>

      {/* Main Content */}
      <main className="flex-1 flex flex-col min-w-0 h-screen bg-obsidian-base">
        {/* Top Header */}
        <header className="h-16 bg-obsidian-base border-b border-obsidian-stroke/50 flex items-center px-6 justify-between shrink-0 z-10 relative shadow-sm">
          <div className="flex items-center gap-4">
            <button onClick={toggleSidebar} className="btn btn-ghost btn-square btn-sm text-theme-muted hover:bg-obsidian-elevated hover:text-white">
              <LuMenu className="h-4 w-4 shrink-0" />
            </button>
            <Typography.Text className="font-medium text-(--theme-muted) hidden sm:block tracking-wide">
              {pageTitle}
            </Typography.Text>
          </div>

          <div className="flex items-center gap-6">
            {eeTopbarWidgets}
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-active-green shadow-[0_0_8px_rgba(34,197,94,0.6)] animate-pulse"></div>
              <span className="text-[10px] sm:text-xs font-mono text-active-green">SYSTEM_ONLINE</span>
            </div>
            <ActiveRunsTracker />
          </div>
        </header>

        {/* Page Content */}
        <div className="flex-1 overflow-auto px-4 py-4 sm:px-6 sm:py-6 lg:px-8 scrollbar-thin scrollbar-thumb-obsidian-stroke scrollbar-track-transparent">
          {/* Key the boundary by the matched ROUTE id, not the full pathname, so a
              crash on one route still clears when the user navigates to a different
              route — WITHOUT remounting the page on a param-only change within the
              same route (e.g. chat new→/:sessionId). Keying on location.pathname
              remounted ChatPage on the first message: state was wiped, the restore
              effect ran, and the in-flight stream was orphaned, so every chat's
              first prompt failed. The leaf match id is stable across param changes. */}
          <ErrorBoundary key={matches.length ? matches[matches.length - 1].id : location.pathname}>
            <Outlet />
          </ErrorBoundary>
        </div>
      </main>
    </div>
  );
};

// ── Sub-Components — defined outside DashboardLayout to preserve useState identity ──

const NavLink: React.FC<{
  children: React.ReactNode;
  href: string;
  icon?: React.ElementType;
  isSidebarOpen: boolean;
}> = ({ children, href, icon: Icon, isSidebarOpen }) => {
  const location = useLocation();
  const isActive = href === '/' ? location.pathname === '/' : location.pathname.startsWith(href);

  const link = (
    <RouterNavLink
      to={href}
      className={cn(
        "flex items-center gap-3 py-2 rounded-md transition-colors duration-200 w-full",
        isSidebarOpen ? "px-3 text-sm font-medium" : "justify-center px-0 py-2.5",
        isActive
          ? "bg-(--agent-blue)/10 text-(--agent-blue) shadow-[0_0_10px_rgba(59,130,246,0.1)] border border-(--agent-blue)/20"
          : "text-(--theme-muted) hover:bg-(--theme-muted)/5 hover:text-(--theme-foreground) border border-transparent"
      )}
    >
      {Icon && <Icon className="w-4 h-4 shrink-0" />}
      {isSidebarOpen && <span className="truncate">{children}</span>}
    </RouterNavLink>
  );

  if (!isSidebarOpen) {
    return <Tooltip label={children as string}>{link}</Tooltip>;
  }

  return link;
};

const NavSection: React.FC<{
  title: string;
  isOpen: boolean;
  onToggle: () => void;
  children: React.ReactNode;
  isSidebarOpen: boolean;
  isTree?: boolean;
}> = ({ title, isOpen, onToggle, children, isSidebarOpen, isTree = false }) => {
  if (!isSidebarOpen) {
    return (
      <div className="space-y-1 mb-4 flex flex-col items-center">
        <div className="w-6 h-px bg-obsidian-stroke/50 my-2" />
        {children}
      </div>
    );
  }

  return (
    <div className="mb-2">
      <button
        type="button"
        onClick={onToggle}
        className="flex items-center justify-between w-full pt-3 pb-1 px-3 mb-1 group"
      >
        <Typography.Text className="text-[10px] font-bold text-theme-muted tracking-wide uppercase group-hover:text-theme-foreground transition-colors">
          {title}
        </Typography.Text>
        <LuChevronDown
          className={cn(
            "w-3 h-3 text-(--theme-muted) group-hover:text-(--theme-foreground) transition-transform duration-200 opacity-60",
            isOpen && "rotate-180"
          )}
        />
      </button>
      {isOpen && (
        <div className={cn(
          "space-y-0.5",
          isTree && "ml-3 pl-2 border-l border-obsidian-stroke/30"
        )}>
          {children}
        </div>
      )}
    </div>
  );
};
