import React, { useState } from 'react';
import { useEvaluationStore } from '../store/evaluationStore';
import { RunStatus } from '../../../shared/types/enums';
import { Typography } from '../../../shared/components/ui/Typography';
import { Button } from '../../../shared/components/ui/Button';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import { RunDetailsModal } from './RunDetailsModal';

export const SuiteDetailsModal: React.FC = () => {
  const { selectedSuite, cases, runs, clearSelectedSuite } = useEvaluationStore();
  const [activeTab, setActiveTab] = useState<'cases' | 'runs'>('runs');
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  useEscapeToClose(clearSelectedSuite, !!selectedSuite);

  if (!selectedSuite) return null;

  const handleClose = () => {
    clearSelectedSuite();
  };

  return (
    <dialog className="modal modal-open">
      <div className="modal-box w-11/12 max-w-5xl h-[85vh] flex flex-col">
        <h3 className="font-bold text-xl mb-1">{selectedSuite.name}</h3>
        <Typography.Text variant="muted" className="mb-4">{selectedSuite.description}</Typography.Text>
        
        <div className="tabs tabs-boxed mb-4 shrink-0">
          <button 
             className={`tab ${activeTab === 'cases' ? 'tab-active' : ''}`}
             onClick={() => setActiveTab('cases')}
          >
             Test Cases ({cases.length})
          </button>
          <button 
             className={`tab ${activeTab === 'runs' ? 'tab-active' : ''}`}
             onClick={() => setActiveTab('runs')}
          >
             Execution History ({runs.length})
          </button>
        </div>
        
        <div className="flex-1 overflow-y-auto min-h-0 bg-obsidian-elevated/30 rounded-box p-4 border border-obsidian-stroke">
            {activeTab === 'cases' && (
                <div className="space-y-4">
                    <div className="flex justify-between items-center">
                        <Typography.Heading level={4}>Configured Cases</Typography.Heading>
                        <Button variant="outline" size="sm">Add Case</Button>
                    </div>
                    {cases.length === 0 ? (
                        <div className="text-center py-8 opacity-50">No cases defined for this suite.</div>
                    ) : (
                        <div className="grid gap-4">
                            {cases.map(c => (
                                <div key={c.id} className="bg-base-100 p-4 rounded-box shadow-sm border border-obsidian-stroke">
                                    <div className="font-semibold mb-2">Case: {c.id.substring(0,8)}</div>
                                    <div className="text-sm bg-obsidian-elevated p-2 rounded line-clamp-2 mb-2 font-mono">
                                        {c.input}
                                    </div>
                                    <div className="text-xs opacity-70">
                                        Expected output: {c.expectedOutput || 'None'}
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            )}

            {activeTab === 'runs' && (
                <div className="space-y-4">
                    <Typography.Heading level={4}>Past Executions</Typography.Heading>
                    {runs.length === 0 ? (
                        <div className="text-center py-8 opacity-50">No execution history available.</div>
                    ) : (
                        <div className="overflow-x-auto">
                            <table className="table bg-base-100 rounded-box shadow-sm">
                                <thead>
                                    <tr>
                                        <th>Date</th>
                                        <th>Status</th>
                                        <th>Agent ID</th>
                                        <th>Score</th>
                                        <th>Action</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {runs.map(run => {
                                        const score = run.metrics?.aggregateScore;
                                        return (
                                        <tr key={run.id} className="hover">
                                            <td>{run.startedAt ? new Date(run.startedAt).toLocaleString() : 'N/A'}</td>
                                            <td>
                                                <span className={`badge ${run.status === RunStatus.COMPLETED ? 'badge-success' : run.status === RunStatus.FAILED ? 'badge-error' : 'badge-warning'}`}>
                                                    {run.status}
                                                </span>
                                            </td>
                                            <td className="font-mono text-xs">{run.agentId}</td>
                                            <td className="font-mono">{typeof score === 'number' ? score.toFixed(2) : '-'}</td>
                                            <td>
                                                <Button size="sm" variant="ghost" onClick={() => setSelectedRunId(run.id)}>
                                                    View Details
                                                </Button>
                                            </td>
                                        </tr>
                                    )})}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>
            )}
        </div>

        <div className="modal-action shrink-0 mt-4">
          <Button onClick={handleClose}>Close Suite</Button>
        </div>
      </div>
      <form method="dialog" className="modal-backdrop">
        <button onClick={handleClose}>close</button>
      </form>

      {selectedRunId && (
          <RunDetailsModal 
              suiteId={selectedSuite.id} 
              runId={selectedRunId} 
              onClose={() => setSelectedRunId(null)} 
          />
      )}
    </dialog>
  );
};
