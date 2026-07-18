import { ApiClient } from './client';

export type JobStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'DLQ';

export interface JobStatusResponse {
  id: string;
  jobType: string;
  status: JobStatus;
  priority: string;
  result: string | null;
  errorMessage: string | null;
  retryCount: number;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export class JobsApi {
  static async getJobStatus(jobId: string): Promise<JobStatusResponse> {
    return ApiClient.get<JobStatusResponse>(`/jobs/${jobId}`);
  }
}

export const TERMINAL_JOB_STATUSES: JobStatus[] = ['COMPLETED', 'FAILED', 'DLQ'];
