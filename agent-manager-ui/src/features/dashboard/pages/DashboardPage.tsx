import React from 'react';
import { BudgetExceededBanner } from '../components/BudgetExceededBanner';
import { DashboardStats } from '../components/DashboardStats';
import { RecentActivityWidget } from '../components/RecentActivityWidget';
import { SystemInfoWidget } from '../../settings/components/SystemInfoWidget';
import { AgentGrid } from '../components/AgentGrid';
import { AlertSummaryWidget } from '../components/AlertSummaryWidget';
import { AnomalyDetectionWidget } from '../components/AnomalyDetectionWidget';
import { HitlQueueWidget } from '../components/HitlQueueWidget';
import { QuickActionsWidget } from '../components/QuickActionsWidget';
import { SecurityInterceptsWidget } from '../components/SecurityInterceptsWidget';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { LuLayoutDashboard } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

export const DashboardPage: React.FC = () => {
  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuLayoutDashboard}
        title="Dashboard"
        subtitle="Monitor and manage your autonomous agents."
      />

      <BudgetExceededBanner />

      <SystemInfoWidget />

      <DashboardStats />

      <QuickActionsWidget />

      <HitlQueueWidget />

      <SecurityInterceptsWidget />

      <RecentActivityWidget />

      <AlertSummaryWidget />

      <AnomalyDetectionWidget />

      <div className="divider my-4"></div>

      <AgentGrid />
    </PageContainer>
  );
};

export default DashboardPage;
