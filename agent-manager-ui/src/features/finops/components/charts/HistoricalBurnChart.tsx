import React from 'react';
import {
    AreaChart,
    Area,
    XAxis,
    YAxis,
    CartesianGrid,
    Tooltip,
    ResponsiveContainer,
} from 'recharts';
import type { HistoricalTrendPoint } from '../../hooks/useFinOps';
import { formatUsd } from '../../utils/formatCurrency';
import { Typography } from '../../../../shared/components/ui/Typography';

interface HistoricalBurnChartProps {
    data: HistoricalTrendPoint[];
    isLoading?: boolean;
    isError?: boolean;
    days?: number;
}

const CustomTooltip: React.FC<{
    active?: boolean;
    payload?: { value: number; name: string }[];
    label?: string;
}> = ({ active, payload, label }) => {
    if (!active || !payload?.length) return null;
    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/20 rounded-lg px-3 py-2 text-xs shadow-lg">
            <p className="font-semibold text-(--theme-text) mb-1">{label}</p>
            {payload.map((entry) => (
                <p key={entry.name} className="text-(--theme-muted)">
                    {entry.name === 'estimatedUsd'
                        ? `Est. Cost: ${formatUsd(entry.value)}`
                        : `Runs: ${entry.value}`}
                </p>
            ))}
        </div>
    );
};

export const HistoricalBurnChart: React.FC<HistoricalBurnChartProps> = ({
    data,
    isLoading = false,
    isError = false,
    days = 7,
}) => {
    const hasData = data.some(d => d.runCount > 0);

    if (isLoading) {
        return (
            <div className="w-full h-48 animate-pulse bg-base-300 rounded-xl" />
        );
    }

    if (isError) {
        return (
            <div role="alert" className="alert alert-error rounded-xl text-sm">
                <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <span>Failed to load historical trend data. Please try again.</span>
            </div>
        );
    }

    return (
        <div className="card bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-5">
            <div className="flex items-center justify-between mb-4">
                <div>
                    <Typography.Heading level={4}>Trailing {days}-Day Burn Rate</Typography.Heading>
                    <p className="text-xs text-(--theme-muted) mt-0.5">Daily estimated USD expenditure from agent activity</p>
                </div>
                {!hasData && (
                    <span className="badge badge-ghost badge-sm">No Activity</span>
                )}
            </div>

            {!hasData ? (
                <div className="flex items-center justify-center h-40 border border-dashed border-(--theme-muted)/20 rounded-lg text-(--theme-muted) text-xs">
                    No agent runs recorded in the last {days} days.
                </div>
            ) : (
                <div className="w-full h-48">
                    <ResponsiveContainer width="100%" height="100%">
                        <AreaChart data={data} margin={{ top: 4, right: 4, left: 0, bottom: 0 }}>
                            <defs>
                                <linearGradient id="burnGradient" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor="var(--color-primary, oklch(65% 0.2 270))" stopOpacity={0.3} />
                                    <stop offset="95%" stopColor="var(--color-primary, oklch(65% 0.2 270))" stopOpacity={0} />
                                </linearGradient>
                            </defs>
                            <CartesianGrid
                                strokeDasharray="3 3"
                                stroke="var(--theme-muted, #888)"
                                strokeOpacity={0.12}
                            />
                            <XAxis
                                dataKey="date"
                                tick={{ fontSize: 10, fill: 'var(--theme-muted, #888)' }}
                                tickFormatter={(v: string) => v.slice(5)} // "MM-DD"
                                axisLine={false}
                                tickLine={false}
                            />
                            <YAxis
                                tickFormatter={(v: number) => `$${v.toFixed(2)}`}
                                tick={{ fontSize: 10, fill: 'var(--theme-muted, #888)' }}
                                axisLine={false}
                                tickLine={false}
                                width={52}
                            />
                            <Tooltip content={<CustomTooltip />} />
                            <Area
                                type="monotone"
                                dataKey="estimatedUsd"
                                stroke="var(--color-primary, oklch(65% 0.2 270))"
                                strokeWidth={2}
                                fill="url(#burnGradient)"
                                dot={false}
                                activeDot={{ r: 4, strokeWidth: 0 }}
                            />
                        </AreaChart>
                    </ResponsiveContainer>
                </div>
            )}
        </div>
    );
};
