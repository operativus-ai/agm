import React, { useState } from 'react';
import {
    PieChart,
    Pie,
    Cell,
    Tooltip,
    ResponsiveContainer,
    Legend,
} from 'recharts';
import type { CostAllocationEntry } from '../../hooks/useFinOps';
import { Typography } from '../../../../shared/components/ui/Typography';

interface CostAllocationChartProps {
    data: CostAllocationEntry[];
    isLoading?: boolean;
    isError?: boolean;
}

type DimensionFilter = 'agent' | 'org';

/** Palette using CSS custom properties so the chart reacts to light/dark theme switching. */
const SLICE_COLORS = [
    'oklch(65% 0.20 270)',  // primary-ish
    'oklch(65% 0.18 150)',  // success-ish
    'oklch(65% 0.22 330)',  // secondary-ish
    'oklch(65% 0.20 45)',   // warning-ish
    'oklch(65% 0.20 210)',  // info-ish
    'oklch(55% 0.18 0)',    // error-ish
    'oklch(65% 0.15 90)',
    'oklch(55% 0.12 270)',
];

const CustomTooltip: React.FC<{
    active?: boolean;
    payload?: { name: string; value: number; payload: CostAllocationEntry }[];
}> = ({ active, payload }) => {
    if (!active || !payload?.length) return null;
    const entry = payload[0].payload;
    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/20 rounded-lg px-3 py-2 text-xs shadow-lg">
            <p className="font-semibold text-(--theme-text) mb-1 truncate max-w-[160px]">{entry.label}</p>
            <p className="text-(--theme-muted)">Runs: {entry.runCount}</p>
            <p className="text-(--theme-muted)">Share: {entry.allocationPercent.toFixed(1)}%</p>
        </div>
    );
};

export const CostAllocationChart: React.FC<CostAllocationChartProps> = ({
    data,
    isLoading = false,
    isError = false,
}) => {
    const [dimension, setDimension] = useState<DimensionFilter>('agent');

    const filtered = data.filter(d => d.dimension === dimension);

    if (isLoading) {
        return <div className="w-full h-48 animate-pulse bg-base-300 rounded-xl" />;
    }

    if (isError) {
        return (
            <div role="alert" className="alert alert-error rounded-xl text-sm">
                <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <span>Failed to load cost allocation data. Please try again.</span>
            </div>
        );
    }

    return (
        <div className="card bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-5 h-full">
            <div className="flex items-center justify-between mb-4 flex-wrap gap-2">
                <div>
                    <Typography.Heading level={4}>Cost Allocation</Typography.Heading>
                    <p className="text-xs text-(--theme-muted) mt-0.5">Run distribution by dimension</p>
                </div>
                <div className="join">
                    <button
                        type="button"
                        className={`join-item btn btn-xs ${dimension === 'agent' ? 'btn-primary' : 'btn-ghost'}`}
                        onClick={() => setDimension('agent')}
                    >
                        By Agent
                    </button>
                    <button
                        type="button"
                        className={`join-item btn btn-xs ${dimension === 'org' ? 'btn-primary' : 'btn-ghost'}`}
                        onClick={() => setDimension('org')}
                    >
                        By Org
                    </button>
                </div>
            </div>

            {filtered.length === 0 ? (
                <div className="flex items-center justify-center h-40 border border-dashed border-(--theme-muted)/20 rounded-lg text-(--theme-muted) text-xs">
                    No allocation data available.
                </div>
            ) : (
                <div className="w-full h-52">
                    <ResponsiveContainer width="100%" height="100%">
                        <PieChart>
                            <Pie
                                data={filtered}
                                cx="50%"
                                cy="50%"
                                innerRadius="52%"
                                outerRadius="75%"
                                dataKey="allocationPercent"
                                nameKey="label"
                                paddingAngle={2}
                                strokeWidth={0}
                            >
                                {filtered.map((_, idx) => (
                                    <Cell
                                        key={`cell-${idx}`}
                                        fill={SLICE_COLORS[idx % SLICE_COLORS.length]}
                                    />
                                ))}
                            </Pie>
                            <Tooltip content={<CustomTooltip />} />
                            <Legend
                                iconType="circle"
                                iconSize={8}
                                formatter={(value: string) =>
                                    <span className="text-[10px] text-(--theme-muted) truncate">{value}</span>
                                }
                            />
                        </PieChart>
                    </ResponsiveContainer>
                </div>
            )}
        </div>
    );
};
