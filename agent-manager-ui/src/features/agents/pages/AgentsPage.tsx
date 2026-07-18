import React, { useCallback, useEffect, useMemo, useState } from 'react';
import type { AgentConfig } from '../../../shared/types/api';
import { AgentsApi } from '../api/agents-api';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { RunBackgroundModal } from '../components/RunBackgroundModal';
import { AgentFormModal } from '../components/AgentFormModal';
import { createAgentColumns } from '../components/agentColumns';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { LuPlus, LuBot } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { AgentCardViewer } from '../../a2a/components/AgentCardViewer';

interface PaginatedResponse<T> {
  content: T[];
}

export const AgentsPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [agents, setAgents] = useState<AgentConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showInactive, setShowInactive] = useState(false);

  const [selectedAgentForRun, setSelectedAgentForRun] = useState<AgentConfig | null>(null);
  const [isFormModalOpen, setIsFormModalOpen] = useState(false);
  const [selectedAgentForEdit, setSelectedAgentForEdit] = useState<AgentConfig | null>(null);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null);
  const [cardViewAgent, setCardViewAgent] = useState<AgentConfig | null>(null);

  // Handle ?create=<templateId> from AgentCreatePage
  useEffect(() => {
    const createTemplate = searchParams.get('create');
    if (createTemplate) {
      setSelectedTemplateId(createTemplate);
      setSelectedAgentForEdit(null);
      setIsFormModalOpen(true);
      setSearchParams({}, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  const loadAgents = useCallback(async () => {
    try {
      setLoading(true);
      try {
        const data = await AgentsApi.getAgents(showInactive);
        console.log("Raw agent data received:", data);

        const maybePagedData = data as unknown as PaginatedResponse<AgentConfig>;
        if (data && Array.isArray(maybePagedData.content)) {
          setAgents(maybePagedData.content);
        } else if (Array.isArray(data)) {
          setAgents(data as AgentConfig[]);
        } else if (data) {
          setAgents([data as AgentConfig]);
        } else {
          setAgents([]);
        }
      } catch (err: unknown) {
        console.error("API Fetch failed", err);
        setError("Failed to fetch agents data: " + (err instanceof Error ? err.message : String(err)));
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to load agents');
    } finally {
      setLoading(false);
    }
  }, [showInactive]);

  useEffect(() => {
    loadAgents();
  }, [loadAgents]);

  const handleRunBackground = (agent: AgentConfig) => {
    setSelectedAgentForRun(agent);
  };

  const handleCloseModal = () => {
    setSelectedAgentForRun(null);
  };

  const handleCreateClick = () => {
    navigate('/agents/new');
  };

  const handleEditClick = useCallback((agent: AgentConfig) => {
    setSelectedAgentForEdit(agent);
    setIsFormModalOpen(true);
  }, []);

  const handleDeleteClick = useCallback(async (agent: AgentConfig) => {
    if (window.confirm(`Are you sure you want to deactivate the ${agent.isTeam ? 'team' : 'agent'} "${agent.name}"?`)) {
      try {
        await AgentsApi.deleteAgent(agent.agentId, agent.isTeam);
        setAgents(prev => prev.filter(a => a.agentId !== agent.agentId));
      } catch (err: unknown) {
        setError(err instanceof Error ? err.message : `Failed to delete ${agent.isTeam ? 'team' : 'agent'}`);
      }
    }
  }, []);

  const handleRestoreClick = useCallback(async (agent: AgentConfig) => {
    if (window.confirm(`Are you sure you want to restore the ${agent.isTeam ? 'team' : 'agent'} "${agent.name}"?`)) {
      try {
        await AgentsApi.restoreAgent(agent.agentId, agent.isTeam);
        loadAgents();
      } catch (err: unknown) {
        setError(err instanceof Error ? err.message : `Failed to restore ${agent.isTeam ? 'team' : 'agent'}`);
      }
    }
  }, [loadAgents]);

  const handleSaveAgent = async (agentData: Partial<AgentConfig>) => {
    if (selectedAgentForEdit) {
      const updated = await AgentsApi.updateAgent(selectedAgentForEdit.agentId, agentData);
      setAgents(prev => prev.map(a => a.agentId === updated.agentId ? updated : a));
    } else {
      const created = await AgentsApi.createAgent(agentData);
      setAgents(prev => [...prev, created]);
    }
  };

  const columns = useMemo(() => createAgentColumns({
    onRunBackground: handleRunBackground,
    onEdit: handleEditClick,
    onDelete: handleDeleteClick,
    onRestore: handleRestoreClick,
    onViewCard: setCardViewAgent,
    onChat: (id) => navigate(`/chat/${id}`),
  }), [handleEditClick, handleDeleteClick, handleRestoreClick, navigate]);

  return (
    <PageContainer variant="dashboard">

      {/* Header */}
      <PageHeader
        icon={LuBot}
        title="Agents Registry"
        subtitle="Manage and interact with your fleet of autonomous agents."
        actions={
          <>
            <label className="label cursor-pointer gap-2">
              <span className="label-text text-sm">Show Inactive</span>
              <input
                type="checkbox"
                className="toggle toggle-primary toggle-sm"
                checked={showInactive}
                onChange={(e) => setShowInactive(e.target.checked)}
              />
            </label>
            <Button onClick={handleCreateClick} size="sm" className="gap-2">
              <LuPlus className="w-4 h-4" /> Create Agent
            </Button>
          </>
        }
      />

      {error && (
        <Alert severity="error" title="Error Loading Agents">{error}</Alert>
      )}

      {/* Data Table */}
      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3, 4, 5].map(i => (
            <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />
          ))}
        </div>
      ) : (
        <DataTable
          columns={columns}
          data={agents}
          enablePagination
          defaultPageSize={25}
          emptyMessage="No agents found. Create one to get started."
        />
      )}

      {selectedAgentForRun && (
        <RunBackgroundModal
          isOpen={!!selectedAgentForRun}
          agent={selectedAgentForRun}
          onClose={handleCloseModal}
        />
      )}

      <AgentFormModal
        isOpen={isFormModalOpen}
        onClose={() => { setIsFormModalOpen(false); setSelectedTemplateId(null); }}
        onSave={handleSaveAgent}
        agent={selectedAgentForEdit}
        initialTemplateId={selectedTemplateId}
      />

      {cardViewAgent && (
        <AgentCardViewer
          agentId={cardViewAgent.agentId}
          agentName={cardViewAgent.name}
          isOpen={!!cardViewAgent}
          onClose={() => setCardViewAgent(null)}
        />
      )}
    </PageContainer>
  );
};
