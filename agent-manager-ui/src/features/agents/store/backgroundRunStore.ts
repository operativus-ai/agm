import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { RunStatus } from '../../../shared/types/enums';

export interface BackgroundRun {
  runId: string;
  agentId: string;
  agentName: string;
  status: RunStatus;
  startedAt: number;
}

interface BackgroundRunState {
  runs: BackgroundRun[];
  addRun: (run: BackgroundRun) => void;
  updateRunStatus: (runId: string, status: BackgroundRun['status']) => void;
  removeRun: (runId: string) => void;
  getActiveRuns: () => BackgroundRun[];
}

export const useBackgroundRunStore = create<BackgroundRunState>()(
  persist(
    (set, get) => ({
      runs: [],
      addRun: (run) => set((state) => ({ 
          runs: [...state.runs.filter(r => r.runId !== run.runId), run] 
      })),
      updateRunStatus: (runId, status) => set((state) => ({
          runs: state.runs.map(r => r.runId === runId ? { ...r, status } : r)
      })),
      removeRun: (runId) => set((state) => ({
          runs: state.runs.filter(r => r.runId !== runId)
      })),
      getActiveRuns: () => get().runs.filter(r => ([RunStatus.PENDING, RunStatus.RUNNING] as RunStatus[]).includes(r.status))
    }),
    {
      name: 'agent-background-runs',
    }
  )
);
