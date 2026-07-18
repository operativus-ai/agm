import React from 'react';
import { cn } from '../../../shared/utils/cn';
import { formatUsd } from '../utils/formatCurrency';
import { VelocitySparkline } from '../../../shared/components/charts/VelocitySparkline';

interface FinOpsOverviewProps {
    className?: string;
    tokenExpenditure: number;
    maxDailySpend: number;
    activeAgents: number;
    /** Total USD saved by semantic cache hits (from finops.cache.savings.usd Micrometer counter). */
    cacheSavingsUsd?: number;
    /** Current instantaneous burn rate in USD/hr for the velocity sparkline. */
    currentBurnRate?: number;
}

export const FinOpsOverview: React.FC<FinOpsOverviewProps> = ({
    className,
    tokenExpenditure,
    maxDailySpend,
    activeAgents,
    cacheSavingsUsd = 0,
    currentBurnRate = 0,
}) => {
    const budgetUsage = (tokenExpenditure / maxDailySpend) * 100;
    const isOverBudget = budgetUsage > 90;

    return (
        <div className={cn("grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4", className)}>
            <div className="stat bg-obsidian-elevated rounded-box border border-obsidian-stroke">
                <div className="stat-figure text-primary">
                    <VelocitySparkline currentValue={currentBurnRate} isHighVelocity={isOverBudget} />
                </div>
                <div className="stat-title">Daily Expenditure</div>
                <div className={cn("stat-value", isOverBudget ? "text-error" : "text-primary")}>{formatUsd(tokenExpenditure)}</div>
                <div className="stat-desc">Target: {formatUsd(maxDailySpend)}</div>
            </div>
            
            <div className="stat bg-obsidian-elevated rounded-box border border-obsidian-stroke">
                <div className="stat-figure text-secondary">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" className="inline-block h-8 w-8 stroke-current"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4"></path></svg>
                </div>
                <div className="stat-title">Active Agents</div>
                <div className="stat-value text-secondary">{activeAgents}</div>
                <div className="stat-desc">Consuming Capacity</div>
            </div>

            <div className="stat bg-obsidian-elevated rounded-box border border-obsidian-stroke">
                <div className="stat-figure text-success">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" className="inline-block h-8 w-8 stroke-current">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                    </svg>
                </div>
                <div className="stat-title">Semantic Cache ROI</div>
                <div className="stat-value text-success">{formatUsd(cacheSavingsUsd)}</div>
                <div className="stat-desc">Saved vs. LLM Inference</div>
            </div>

            {/* Budget Progress Bar */}
            <div className="col-span-1 md:col-span-2 lg:col-span-3 mt-4 bg-obsidian-elevated p-6 rounded-box border border-obsidian-stroke">
                <h3 className="text-lg font-semibold mb-2">Daily FinOps Envelope</h3>
                <progress className={cn("progress w-full h-4", isOverBudget ? "progress-error" : "progress-primary")} value={tokenExpenditure} max={maxDailySpend}></progress>
                <div className="flex justify-between text-sm mt-2 text-base-content/70">
                    <span>{formatUsd(0)}</span>
                    <span>{formatUsd(maxDailySpend)} Limit</span>
                </div>
            </div>
        </div>
    );
};
