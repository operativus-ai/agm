import type { JobStatus } from '../../api/jobs-api';

interface JobStatusBadgeProps {
  status: JobStatus;
  className?: string;
}

const STATUS_CONFIG: Record<JobStatus, { label: string; className: string }> = {
  QUEUED:     { label: 'Queued',     className: 'bg-slate-500/20 text-slate-300 border-slate-500/30' },
  PROCESSING: { label: 'Processing', className: 'bg-blue-500/20 text-blue-300 border-blue-500/30 animate-pulse' },
  COMPLETED:  { label: 'Completed',  className: 'bg-green-500/20 text-green-300 border-green-500/30' },
  FAILED:     { label: 'Failed',     className: 'bg-red-500/20 text-red-300 border-red-500/30' },
  DLQ:        { label: 'Dead Letter', className: 'bg-orange-500/20 text-orange-300 border-orange-500/30' },
};

export function JobStatusBadge({ status, className = '' }: JobStatusBadgeProps) {
  const config = STATUS_CONFIG[status];
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border ${config.className} ${className}`}
    >
      {config.label}
    </span>
  );
}
