-- Enable new job types (non-agent jobs have no agent_id)
ALTER TABLE background_jobs ALTER COLUMN agent_id DROP NOT NULL;

-- Dispatcher key — required for JobHandlerRegistry routing
ALTER TABLE background_jobs ADD COLUMN job_type VARCHAR(100);
UPDATE background_jobs SET job_type = 'AGENT_RUN' WHERE job_type IS NULL;
ALTER TABLE background_jobs ALTER COLUMN job_type SET NOT NULL;

-- Job output for GET /api/jobs/{jobId} polling
ALTER TABLE background_jobs ADD COLUMN result TEXT;

-- Dedup key (nullable; cleared on terminal state)
ALTER TABLE background_jobs ADD COLUMN job_key VARCHAR(255);
CREATE UNIQUE INDEX idx_background_jobs_job_key
    ON background_jobs(job_key)
    WHERE job_key IS NOT NULL;

-- Crash recovery: detect PROCESSING jobs whose JVM died before completion
ALTER TABLE background_jobs ADD COLUMN locked_at TIMESTAMP;

