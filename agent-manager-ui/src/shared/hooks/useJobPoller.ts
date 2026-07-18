import { useState, useEffect, useRef } from 'react';
import { JobsApi, TERMINAL_JOB_STATUSES } from '../api/jobs-api';
import type { JobStatusResponse } from '../api/jobs-api';

interface UseJobPollerOptions {
  intervalMs?: number;
  onComplete?: (job: JobStatusResponse) => void;
  onError?: (job: JobStatusResponse) => void;
}

interface UseJobPollerResult {
  job: JobStatusResponse | null;
  isPolling: boolean;
  error: string | null;
}

export function useJobPoller(
  jobId: string | null,
  options: UseJobPollerOptions = {}
): UseJobPollerResult {
  const { intervalMs = 3000, onComplete, onError } = options;
  const [job, setJob] = useState<JobStatusResponse | null>(null);
  const [isPolling, setIsPolling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const callbacksRef = useRef({ onComplete, onError });
  callbacksRef.current = { onComplete, onError };

  useEffect(() => {
    if (!jobId) {
      setJob(null);
      setIsPolling(false);
      return;
    }

    setIsPolling(true);
    setError(null);

    const poll = async () => {
      try {
        const result = await JobsApi.getJobStatus(jobId);
        setJob(result);

        if (TERMINAL_JOB_STATUSES.includes(result.status)) {
          if (intervalRef.current) clearInterval(intervalRef.current);
          setIsPolling(false);

          if (result.status === 'COMPLETED') {
            callbacksRef.current.onComplete?.(result);
          } else {
            callbacksRef.current.onError?.(result);
          }
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to fetch job status');
        if (intervalRef.current) clearInterval(intervalRef.current);
        setIsPolling(false);
      }
    };

    poll();
    intervalRef.current = setInterval(poll, intervalMs);

    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [jobId, intervalMs]);

  return { job, isPolling, error };
}
