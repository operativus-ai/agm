import React, { useEffect, useState } from 'react';

import { Typography } from '../../../shared/components/ui/Typography';
import { useEvaluationStore } from '../store/evaluationStore';
import { CreateSuiteModal } from '../components/CreateSuiteModal';
import { SuiteDetailsModal } from '../components/SuiteDetailsModal';
import { SuiteCard } from '../components/SuiteCard';
import { EvaluationMetricsPanel } from '../components/EvaluationMetricsPanel';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { LuFlaskConical } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

export const EvaluationDashboard: React.FC = () => {
  const { suites, isLoading, error, fetchSuites } = useEvaluationStore();
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);

  useEffect(() => {
    fetchSuites();
  }, [fetchSuites]);

  return (
    <>
      <PageContainer variant="dashboard">
        
        <PageHeader
          icon={LuFlaskConical}
          title="Evaluation Suites"
          subtitle="Manage and run evaluation benchmarks against your agents."
          actions={
            <Button onClick={() => setIsCreateModalOpen(true)}>
              Create Suite
            </Button>
          }
        />

        {error && (
          <Alert severity="error" title="Error Loading Suites">{error}</Alert>
        )}

        <EvaluationMetricsPanel />

        {/* Grid */}
        {isLoading ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {[1, 2, 3].map(i => (
              <div key={i} className="h-40 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
            ))}
          </div>
        ) : suites.length > 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {suites.map(suite => (
              <SuiteCard key={suite.id} suite={suite} />
            ))}
          </div>
        ) : (
          <div className="text-center py-12 bg-(--theme-card) rounded-xl border border-(--theme-muted)/10">
            <Typography.Text className="text-(--theme-muted)">
              No evaluation suites found. Create your first suite to begin testing.
            </Typography.Text>
          </div>
        )}

      </PageContainer>

      {isCreateModalOpen && (
        <CreateSuiteModal onClose={() => setIsCreateModalOpen(false)} />
      )}
      <SuiteDetailsModal />
    </>
  );
};
