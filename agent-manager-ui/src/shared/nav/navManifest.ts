import type { IconType } from 'react-icons';
import {
  LuLayoutDashboard, LuMessageSquare, LuBot, LuUsers, LuWaypoints,
  LuBookOpen, LuDatabase, LuPlug, LuPackage, LuHistory, LuSquareCheck,
  LuCalendar, LuShield, LuActivity, LuNetwork, LuCpu, LuUserCog, LuClipboardList,
  LuBell, LuBellRing, LuDollarSign, LuPuzzle, LuPlay,
  LuChartBar, LuFlaskConical, LuShieldCheck, LuZap, LuKey, LuSparkles,
} from 'react-icons/lu';

export interface NavItem {
  to: string;
  label: string;
  icon: IconType;
  /** Minimum role required to see the item. Absent = visible to all authenticated users. */
  role?: 'ADMIN' | 'SUPER_ADMIN';
  /** Edition feature key — reserved for the license gate (agm-core-oss-execution.md §5.2). */
  featureKey?: string;
}

export interface NavGroup {
  key: string;
  title: string;
  /** Renders with the parent-child tree treatment (see the Alerts group). */
  isTree?: boolean;
  items: NavItem[];
}

export const NAV_PINNED: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: LuLayoutDashboard },
  { to: '/chat', label: 'Chat', icon: LuMessageSquare },
];

export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'workforce',
    title: 'Workforce',
    items: [
      { to: '/agents', label: 'Agents', icon: LuBot },
      { to: '/teams', label: 'Teams', icon: LuUsers },
      { to: '/workflows', label: 'Workflows', icon: LuWaypoints },
      { to: '/evaluations', label: 'Evaluations', icon: LuFlaskConical },
    ],
  },
  {
    key: 'infrastructure',
    title: 'Infrastructure',
    items: [
      { to: '/models', label: 'Models', icon: LuCpu },
      { to: '/mcp', label: 'MCP Servers', icon: LuPlug },
      { to: '/registry', label: 'Tool Registry', icon: LuPackage },
      { to: '/extensions', label: 'Extensions', icon: LuPuzzle },
    ],
  },
  {
    key: 'data',
    title: 'Data',
    items: [
      { to: '/knowledge', label: 'Knowledge', icon: LuBookOpen },
      { to: '/memory', label: 'Memory', icon: LuDatabase, role: 'ADMIN' },
    ],
  },
  {
    key: 'operations',
    title: 'Operations',
    items: [
      { to: '/sessions', label: 'Sessions', icon: LuHistory },
      { to: '/runs', label: 'Runs', icon: LuPlay },
      { to: '/approvals', label: 'Approvals', icon: LuSquareCheck },
      { to: '/schedules', label: 'Schedules', icon: LuCalendar },
      { to: '/a2a', label: 'A2A Mesh', icon: LuNetwork },
    ],
  },
  {
    key: 'monitoring',
    title: 'Monitoring',
    items: [
      { to: '/observability', label: 'Observability', icon: LuActivity },
      { to: '/finops', label: 'FinOps & Gateway', icon: LuChartBar },
    ],
  },
  {
    key: 'alerts',
    title: 'Alerts',
    isTree: true,
    items: [
      { to: '/admin/alert-rules', label: 'Alert Rules', icon: LuBell },
      { to: '/admin/alert-integrations', label: 'Alert Integrations', icon: LuBellRing },
      { to: '/alerts/budget-exceeded', label: 'Budget Alerts', icon: LuDollarSign },
    ],
  },
  {
    key: 'admin',
    title: 'Admin',
    items: [
      { to: '/admin/users', label: 'Users', icon: LuUserCog },
      { to: '/admin/provider-credentials', label: 'Provider Credentials', icon: LuKey },
      { to: '/admin/agents', label: 'Manage Agents', icon: LuShieldCheck },
      { to: '/security', label: 'Security & Audit', icon: LuShield },
      { to: '/admin/audit-logs', label: 'Audit Logs', icon: LuClipboardList },
      { to: '/admin/skills', label: 'Skills', icon: LuSparkles },
      { to: '/admin/composio', label: 'Composio', icon: LuZap, role: 'SUPER_ADMIN' },
    ],
  },
];

/**
 * Merges edition nav contributions into the core manifest. A contribution whose `key`
 * matches an existing group extends that group's items; otherwise it appends as a new
 * group. Core builds pass an empty contribution (the @ee/nav alias resolves to a stub).
 */
export function mergeNavGroups(core: NavGroup[], contributions: NavGroup[]): NavGroup[] {
  if (contributions.length === 0) return core;
  const merged = core.map(group => {
    const extension = contributions.find(c => c.key === group.key);
    return extension ? { ...group, items: [...group.items, ...extension.items] } : group;
  });
  const newGroups = contributions.filter(c => !core.some(g => g.key === c.key));
  return [...merged, ...newGroups];
}
