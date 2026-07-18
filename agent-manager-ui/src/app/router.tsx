import { ROLES } from '../shared/constants/roles';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
// Edition route manifest — resolves to an empty stub in the Core build (agm-core-oss-execution.md §4.5).
import { eeRoutes } from '@ee/routes';
import { DashboardLayout } from '../features/dashboard/layouts/DashboardLayout';
import { DashboardPage } from '../features/dashboard/pages/DashboardPage';
import { ChatPage } from '../features/chat/pages/ChatPage';
import { ChatAgentPickerPage } from '../features/chat/pages/ChatAgentPickerPage';
import App from '../App'; // Fallback / Component Showcase
import React from 'react';
import { AuthProvider } from '../features/auth/context/AuthContext';
import { AuthGuard } from '../features/auth/components/AuthGuard';
import { LoginPage } from '../features/auth/pages/LoginPage';
import { RegisterPage } from '../features/auth/pages/RegisterPage';
import { ForgotPasswordPage } from '../features/auth/pages/ForgotPasswordPage';
import { ResetPasswordPage } from '../features/auth/pages/ResetPasswordPage';
import { AgentsPage } from '../features/agents/pages/AgentsPage';
import { AgentCreatePage } from '../features/agents/pages/AgentCreatePage';
import { AgentCredentialsPage } from '../features/agents/pages/AgentCredentialsPage';
import { AgentDetailsPage } from '../features/agents/pages/AgentDetailsPage';
import { AgentEventsPage } from '../features/agents/pages/AgentEventsPage';
import { AgentAdminDashboardPage } from '../features/agents/pages/admin/AgentAdminDashboardPage';
import { KnowledgePage } from '../features/knowledge/pages/KnowledgePage';
import { SettingsPage } from '../features/settings/pages/SettingsPage';
import { EvaluationDashboard } from '../features/evaluations/pages/EvaluationDashboard';
import { TeamsPage } from '../features/teams/pages/TeamsPage';
import { TeamDetailsPage } from '../features/teams/pages/TeamDetailsPage';
import { TeamsManifestsPage } from '../features/teams/pages/TeamsManifestsPage';
import { WorkflowsPage } from '../features/workflows/pages/WorkflowsPage';
import { WorkflowEditorPage } from '../features/workflows/pages/WorkflowEditorPage';
import { WorkflowRunHistoryPage } from '../features/workflows/pages/WorkflowRunHistoryPage';
import WorkflowGraphEditorPage from '../features/workflows/pages/WorkflowGraphEditorPage';
import WorkflowRunGraphPage from '../features/workflows/pages/WorkflowRunGraphPage';
import { ApprovalsPage } from '../features/approvals/pages/ApprovalsPage';
import { SchedulesPage } from '../features/schedules/pages/SchedulesPage';
import { ScheduleHistoryPage } from '../features/schedules/pages/ScheduleHistoryPage';
import { MemoryManagerPage } from '../features/memory/pages/MemoryManagerPage';
import { SessionsPage } from '../features/sessions/pages/SessionsPage';
import { SessionDetailsPage } from '../features/sessions/pages/SessionDetailsPage';
import { RegistryPage } from '../features/registry/pages/RegistryPage';
import { ExtensionsConfigurationPage } from '../features/extensions/pages/ExtensionsConfigurationPage';
import { McpAdminPage } from '../features/mcp/pages/McpAdminPage';
import { ModelsPage } from '../features/models/pages/ModelsPage';
import { SecurityPage } from '../features/security/pages/SecurityPage';
import { ObservabilityPage } from '../features/observability/pages/ObservabilityPage';
import { A2aMeshPage } from '../features/a2a/pages/A2aMeshPage';
import { UserManagementPage } from '../features/users/pages/UserManagementPage';
import { ProviderCredentialsPage } from '../features/provider-credentials/pages/ProviderCredentialsPage';
import { AuditLogPage } from '../features/auditlogs/pages/AuditLogPage';
import { ComposioAdminPage } from '../features/composio/pages/ComposioAdminPage';
import { RequireRole } from '../shared/components/RequireRole';
import { RequireAnyRole } from '../shared/components/RequireAnyRole';
import { SkillsPage } from '../features/skills/pages/SkillsPage';
import { RunsPage } from '../features/runs/pages/RunsPage';
import { RunDetailPage } from '../features/runs/pages/RunDetailPage';
import { NotFoundPage } from '../features/errors/pages/NotFoundPage';

const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <AuthGuard>
        <DashboardLayout />
      </AuthGuard>
    ),
    errorElement: <NotFoundPage />,
    children: [
      {
        index: true,
        element: <DashboardPage />,
        handle: { title: 'Dashboard' },
      },
      {
        path: 'agents',
        element: <AgentsPage />,
        handle: { title: 'Agents' },
      },
      {
        path: 'agents/new',
        element: <AgentCreatePage />,
        handle: { title: 'New Agent' },
      },
      {
        path: 'agents/:agentId',
        element: <AgentDetailsPage />,
        handle: { title: 'Agent Details' },
      },
      {
        path: 'agents/:agentId/events',
        element: <AgentEventsPage />,
        handle: { title: 'Agent Events' },
      },
      {
        path: 'agents/:agentId/credentials',
        element: <AgentCredentialsPage />,
        handle: { title: 'Agent Credentials' },
      },
      {
        path: 'knowledge',
        element: <KnowledgePage />,
        handle: { title: 'Knowledge' },
      },
      {
        path: 'chat',
        element: <ChatAgentPickerPage />,
        handle: { title: 'Chat' },
      },
      {
        // ONE route with an optional :sessionId — NOT two. The first message of
        // a new chat does navigate('/chat/:agentId' → '/chat/:agentId/:sessionId').
        // With two separate route entries those are different route ids, so
        // react-router remounted ChatPage on that navigate: skipRestoreRef (a
        // useRef) reset to false, the [urlSessionId] restore effect then ran
        // handleSelectSession → setMessages([]), wiping the just-sent prompt and
        // orphaning the in-flight stream — so every chat's FIRST prompt failed,
        // and only the first (subsequent sends don't navigate). A single route
        // matches both paths, so new→uuid is a param update, not a remount.
        path: 'chat/:agentId/:sessionId?',
        element: <ChatPage />,
        handle: { title: 'Chat' },
      },
      {
        path: 'settings',
        element: <SettingsPage />,
        handle: { title: 'Settings' },
      },
      {
        path: 'evaluations',
        element: <EvaluationDashboard />,
        handle: { title: 'Evaluations' },
      },
      { path: 'teams', element: <TeamsPage />, handle: { title: 'Teams' } },
      { path: 'teams/manifests', element: <TeamsManifestsPage />, handle: { title: 'Team Manifests' } },
      { path: 'teams/:id', element: <TeamDetailsPage />, handle: { title: 'Team Details' } },
      { path: 'workflows', element: <WorkflowsPage />, handle: { title: 'Workflows' } },
      { path: 'workflows/:id', element: <WorkflowEditorPage />, handle: { title: 'Workflow Editor' } },
      { path: 'workflows/:id/graph', element: <WorkflowGraphEditorPage />, handle: { title: 'Workflow Graph Editor' } },
      { path: 'workflows/:id/runs', element: <WorkflowRunHistoryPage />, handle: { title: 'Workflow Runs' } },
      { path: 'workflows/:id/runs/:runId/graph', element: <WorkflowRunGraphPage />, handle: { title: 'Run Graph' } },
      { path: 'approvals', element: <ApprovalsPage />, handle: { title: 'Approvals' } },
      { path: 'schedules', element: <SchedulesPage />, handle: { title: 'Schedules' } },
      { path: 'schedules/:id/history', element: <ScheduleHistoryPage />, handle: { title: 'Schedule History' } },
      {
        path: 'memory',
        element: (
          <RequireAnyRole roles={[ROLES.ADMIN, ROLES.SUPER_ADMIN]}>
            <MemoryManagerPage />
          </RequireAnyRole>
        ),
        handle: { title: 'Memory' },
      },
      { path: 'sessions', element: <SessionsPage />, handle: { title: 'Sessions' } },
      { path: 'sessions/:id', element: <SessionDetailsPage />, handle: { title: 'Session Details' } },
      { path: 'runs', element: <RunsPage />, handle: { title: 'Runs' } },
      { path: 'runs/:runId', element: <RunDetailPage />, handle: { title: 'Run Details' } },
      { path: 'registry', element: <RegistryPage />, handle: { title: 'Tool Registry' } },
      { path: 'extensions', element: <ExtensionsConfigurationPage />, handle: { title: 'Extensions' } },
      { path: 'mcp', element: <McpAdminPage />, handle: { title: 'MCP Servers' } },
      { path: 'models', element: <ModelsPage />, handle: { title: 'Models' } },
      { path: 'security', element: <SecurityPage />, handle: { title: 'Security & Audit' } },
      { path: 'observability', element: <ObservabilityPage />, handle: { title: 'Observability' } },
      { path: 'a2a', element: <A2aMeshPage />, handle: { title: 'A2A Mesh' } },
      {
        path: 'admin/composio',
        element: (
          <RequireRole role={ROLES.SUPER_ADMIN}>
            <ComposioAdminPage />
          </RequireRole>
        ),
        handle: { title: 'Composio Admin' },
      },
      { path: 'admin/users', element: <UserManagementPage />, handle: { title: 'Users' } },
      { path: 'admin/provider-credentials', element: <ProviderCredentialsPage />, handle: { title: 'Provider Credentials' } },
      { path: 'admin/agents', element: <AgentAdminDashboardPage />, handle: { title: 'Manage Agents' } },
      { path: 'admin/audit-logs', element: <AuditLogPage />, handle: { title: 'Audit Logs' } },
      { path: 'admin/skills', element: <SkillsPage />, handle: { title: 'Skills' } },
      // Edition routes join the dashboard layout here; empty in the Core build.
      ...eeRoutes,
      { path: '*', element: <NotFoundPage />, handle: { title: 'Not Found' } },
    ],
  },
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/register',
    element: <RegisterPage />,
  },
  {
    path: '/forgot-password',
    element: <ForgotPasswordPage />,
  },
  {
    path: '/reset-password',
    element: <ResetPasswordPage />,
  },
  {
    // Keeping the original "Kitchen Sink" app accessible for dev verification
    path: '/design-system',
    element: <div className="p-8 bg-base-200 min-h-screen"><App /></div>,
  },
], {
  future: {
    v7_relativeSplatPath: true,
    v7_fetcherPersist: true,
    v7_normalizeFormMethod: true,
    v7_partialHydration: true,
    v7_skipActionErrorRevalidation: true,
  }
});

export const AppRouter: React.FC = () => {
  return (
    <AuthProvider>
      <RouterProvider router={router} future={{ v7_startTransition: true }} />
    </AuthProvider>
  );
};
