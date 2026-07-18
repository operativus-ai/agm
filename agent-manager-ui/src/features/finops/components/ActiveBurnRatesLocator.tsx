import React from 'react';
import { useActiveBurnRates } from '../hooks/useFinOps';
import { formatUsdPrecision } from '../utils/formatCurrency';
import { Typography } from '../../../shared/components/ui/Typography';
import { cn } from '../../../shared/utils/cn';
import { VelocitySparkline } from '../../../shared/components/charts/VelocitySparkline';

export const ActiveBurnRatesLocator: React.FC = () => {
    const { data: activeWindows = [], isLoading } = useActiveBurnRates();

    return (
        <div className="card bg-obsidian-elevated border border-obsidian-stroke shadow-sm rounded-box overflow-hidden h-full">
            <div className="card-body p-6 flex flex-col h-full">
                <div className="flex justify-between items-center mb-4">
                    <Typography.Heading level={3}>Live Burn Rate Telemetry</Typography.Heading>
                    <span className="relative flex h-3 w-3">
                        <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-primary opacity-75"></span>
                        <span className="relative inline-flex rounded-full h-3 w-3 bg-primary"></span>
                    </span>
                </div>
                
                <Typography.Text className="text-sm text-base-content/70 mb-4">
                    Real-time observation of active session velocity. Polling every 5s.
                </Typography.Text>

                <div className="flex-1 overflow-y-auto pr-2">
                    {isLoading ? (
                        <div className="space-y-3">
                            <div className="animate-pulse h-12 bg-base-100 rounded-md" />
                            <div className="animate-pulse h-12 bg-base-100 rounded-md" />
                        </div>
                    ) : activeWindows.length === 0 ? (
                        <div className="h-full flex flex-col items-center justify-center text-base-content/40 p-8 border border-dashed border-obsidian-stroke rounded-md min-h-[120px]">
                            <svg xmlns="http://www.w3.org/2000/svg" className="h-10 w-10 mb-3 opacity-50" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M5 13l4 4L19 7" />
                            </svg>
                            <p>No active sessions detected.</p>
                        </div>
                    ) : (
                        <div className="space-y-3">
                            {activeWindows.map((window) => {
                                const isHighVelocity = window.cumulativeUsd > 10.0;
                                return (
                                    <div
                                        key={window.sessionId}
                                        className={cn(
                                            "flex items-center justify-between p-3 rounded-md bg-base-100 border transition-all duration-300",
                                            isHighVelocity ? "border-error shadow-[0_0_8px_rgba(255,0,0,0.1)]" : "border-obsidian-stroke"
                                        )}
                                    >
                                        <div className="flex flex-col">
                                            <span className="text-xs font-mono text-base-content/60 uppercase tracking-widest mb-1">Session</span>
                                            <span className="font-medium text-sm truncate max-w-[160px]" title={window.sessionId}>
                                                {window.sessionId}
                                            </span>
                                        </div>
                                        <VelocitySparkline
                                            currentValue={window.cumulativeUsd}
                                            isHighVelocity={isHighVelocity}
                                        />
                                        <div className="flex flex-col items-end">
                                            <span className="text-xs font-mono text-base-content/60 uppercase tracking-widest mb-1">Velocity</span>
                                            <span className={cn("font-bold font-mono", isHighVelocity ? "text-error" : "text-primary")}>
                                                {formatUsdPrecision(window.cumulativeUsd)} <span className="text-xs font-sans font-normal opacity-60">/hr</span>
                                            </span>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};
