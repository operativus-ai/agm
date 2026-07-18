import { create } from 'zustand';
import type {
  EvaluationSuite,
  EvaluationCase,
  EvaluationRun,
  CreateEvaluationSuiteRequest,
  CreateEvaluationCaseRequest
} from '../../../shared/types/evaluation';
import { evaluationApi } from '../../../shared/api/evaluationApi';
import { JobsApi } from '../../../shared/api/jobs-api';

interface EvaluationState {
  suites: EvaluationSuite[];
  selectedSuite: EvaluationSuite | null;
  cases: EvaluationCase[];
  runs: EvaluationRun[];
  isLoading: boolean;
  error: string | null;

  // Actions
  fetchSuites: () => Promise<void>;
  selectSuite: (suiteId: string) => Promise<void>;
  clearSelectedSuite: () => void;
  createSuite: (data: CreateEvaluationSuiteRequest) => Promise<void>;
  deleteSuite: (suiteId: string) => Promise<void>;

  fetchCases: (suiteId: string) => Promise<void>;
  addCase: (suiteId: string, data: CreateEvaluationCaseRequest) => Promise<void>;
  deleteCase: (suiteId: string, caseId: string) => Promise<void>;

  fetchRuns: (suiteId: string) => Promise<void>;
  triggerEvaluation: (suiteId: string, agentId: string) => Promise<void>;
}

export const useEvaluationStore = create<EvaluationState>((set, get) => ({
  suites: [],
  selectedSuite: null,
  cases: [],
  runs: [],
  isLoading: false,
  error: null,

  fetchSuites: async () => {
    set({ isLoading: true, error: null });
    try {
      const suites = await evaluationApi.getSuites();
      set({ suites, isLoading: false });
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  selectSuite: async (suiteId: string) => {
    set({ isLoading: true, error: null });
    try {
      const selectedSuite = await evaluationApi.getSuite(suiteId);
      set({ selectedSuite, isLoading: false });
      // Pre-fetch related data
      await get().fetchCases(suiteId);
      await get().fetchRuns(suiteId);
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  /**
   * Closes the suite-details view by clearing the selection and the pre-fetched
   * cases/runs. Synchronous — does NOT call the API. The modal renders on
   * `selectedSuite` being non-null, so setting it to null closes the modal
   * immediately. Previously the modal abused `selectSuite('')` to clear, which
   * issued a `GET /api/evaluation/suites/` that errored without resetting state,
   * so the Close button appeared broken.
   */
  clearSelectedSuite: () => {
    set({ selectedSuite: null, cases: [], runs: [], error: null });
  },

  createSuite: async (data: CreateEvaluationSuiteRequest) => {
    set({ isLoading: true, error: null });
    try {
      const newSuite = await evaluationApi.createSuite(data);
      set((state) => ({ 
        suites: [...state.suites, newSuite],
        isLoading: false 
      }));
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  deleteSuite: async (suiteId: string) => {
    set({ isLoading: true, error: null });
    try {
      await evaluationApi.deleteSuite(suiteId);
      set((state) => ({ 
        suites: state.suites.filter(s => s.id !== suiteId),
        selectedSuite: state.selectedSuite?.id === suiteId ? null : state.selectedSuite,
        isLoading: false 
      }));
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  fetchCases: async (suiteId: string) => {
    try {
      const cases = await evaluationApi.getCasesForSuite(suiteId);
      set({ cases });
    } catch (error: any) {
      set({ error: error.message });
    }
  },

  addCase: async (suiteId: string, data: CreateEvaluationCaseRequest) => {
    set({ isLoading: true, error: null });
    try {
      const newCase = await evaluationApi.addCaseToSuite(suiteId, data);
      set((state) => ({ 
        cases: [...state.cases, newCase],
        isLoading: false 
      }));
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  deleteCase: async (suiteId: string, caseId: string) => {
    set({ isLoading: true, error: null });
    try {
      await evaluationApi.deleteCase(suiteId, caseId);
      set((state) => ({ 
        cases: state.cases.filter(c => c.id !== caseId),
        isLoading: false 
      }));
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  fetchRuns: async (suiteId: string) => {
    try {
      const runs = await evaluationApi.getRunsForSuite(suiteId);
      set({ runs });
    } catch (error: any) {
      set({ error: error.message });
    }
  },

  triggerEvaluation: async (suiteId: string, agentId: string) => {
    set({ isLoading: true, error: null });
    try {
      const { jobId } = await evaluationApi.triggerEvaluation(suiteId, agentId);
      // Poll until job completes, then fetch the completed run
      let completed = false;
      while (!completed) {
        await new Promise(resolve => setTimeout(resolve, 3000));
        const job = await JobsApi.getJobStatus(jobId);
        if (job.status === 'COMPLETED' && job.result) {
          const run = await evaluationApi.getRun(job.result);
          set((state) => ({ runs: [run, ...state.runs], isLoading: false }));
          completed = true;
        } else if (job.status === 'FAILED' || job.status === 'DLQ') {
          throw new Error(job.errorMessage ?? 'Evaluation run failed');
        }
      }
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },
}));
