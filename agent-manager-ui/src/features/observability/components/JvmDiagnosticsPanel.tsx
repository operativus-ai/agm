import React, { useState } from 'react';
import { observabilityApi } from '../api/observabilityApi';
import type { ThreadInfo } from '../api/observabilityApi';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { LuRefreshCw, LuCpu } from 'react-icons/lu';

export const JvmDiagnosticsPanel: React.FC = () => {
    const [threadInfo, setThreadInfo] = useState<ThreadInfo | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [fetchedAt, setFetchedAt] = useState<string | null>(null);

    const fetchThreadInfo = async () => {
        setLoading(true);
        setError(null);
        try {
            const data = await observabilityApi.getThreadInfo();
            setThreadInfo(data);
            setFetchedAt(new Date().toLocaleTimeString());
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to fetch thread info');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="space-y-6 py-2">
            <div className="flex items-center justify-between">
                <div>
                    <h3 className="text-sm font-semibold text-(--theme-foreground)">JVM Thread Diagnostics</h3>
                    <p className="text-xs text-(--theme-muted) mt-0.5">
                        Verify virtual thread configuration on the active request handler. No auth required.
                    </p>
                </div>
                <Button
                    size="sm"
                    variant="outline"
                    onClick={fetchThreadInfo}
                    disabled={loading}
                    className="gap-1.5"
                >
                    {loading
                        ? <span className="loading loading-spinner loading-xs" />
                        : <LuRefreshCw className="w-3.5 h-3.5" />}
                    {threadInfo ? 'Refresh' : 'Query Thread Info'}
                </Button>
            </div>

            {error && <Alert severity="error">{error}</Alert>}

            {threadInfo && (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-5 space-y-4">
                    <div className="flex items-center gap-2 text-xs text-(--theme-muted)">
                        <LuCpu className="w-3.5 h-3.5" />
                        <span>Snapshot at {fetchedAt}</span>
                    </div>

                    <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                        <div className="bg-(--theme-bg) border border-(--theme-muted)/10 rounded-lg p-4 space-y-1">
                            <p className="text-xs font-bold uppercase tracking-wide text-(--theme-muted)">Virtual Threads</p>
                            <p className={`text-lg font-semibold font-mono ${threadInfo.virtual ? 'text-success' : 'text-error'}`}>
                                {threadInfo.virtual ? 'Enabled' : 'Disabled'}
                            </p>
                            <p className="text-xs text-(--theme-muted)">
                                {threadInfo.virtual
                                    ? 'Carrier threads in use — scalable I/O'
                                    : 'Platform threads — check spring.threads.virtual.enabled'}
                            </p>
                        </div>

                        <div className="bg-(--theme-bg) border border-(--theme-muted)/10 rounded-lg p-4 space-y-1">
                            <p className="text-xs font-bold uppercase tracking-wide text-(--theme-muted)">Thread Name</p>
                            <p className="text-sm font-mono text-(--theme-foreground) break-all">{threadInfo.name}</p>
                        </div>

                        <div className="bg-(--theme-bg) border border-(--theme-muted)/10 rounded-lg p-4 space-y-1">
                            <p className="text-xs font-bold uppercase tracking-wide text-(--theme-muted)">Daemon</p>
                            <p className={`text-lg font-semibold font-mono ${threadInfo.daemon ? 'text-info' : 'text-(--theme-muted)'}`}>
                                {threadInfo.daemon ? 'Yes' : 'No'}
                            </p>
                        </div>
                    </div>

                    {!threadInfo.virtual && (
                        <Alert severity="warning">
                            Virtual threads are not active. Ensure <code className="font-mono text-xs">spring.threads.virtual.enabled=true</code> is set in <code className="font-mono text-xs">application.properties</code>.
                        </Alert>
                    )}
                </div>
            )}
        </div>
    );
};
