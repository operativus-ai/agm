import React from 'react';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip, Legend } from 'recharts';
import { useModelCostAllocations } from '../../hooks/useFinOps';
import { formatUsd } from '../../utils/formatCurrency';

/**
 * OKLch color palette for model slices — distinct hues for visual separation.
 * Matches the palette convention established in CostAllocationChart.
 */
const MODEL_COLORS = [
    'oklch(65% 0.20 270)',  // primary blue (Anthropic)
    'oklch(65% 0.18 150)',  // success green (OpenAI)
    'oklch(65% 0.22 330)',  // secondary pink (Google)
    'oklch(65% 0.20 45)',   // warning amber (Mistral)
    'oklch(65% 0.20 210)',  // info cyan
    'oklch(55% 0.18 0)',    // error red
    'oklch(65% 0.15 90)',   // accent lime
    'oklch(55% 0.12 270)',  // muted purple
];

interface ModelCostSliceChartProps {
    days?: number;
    className?: string;
}

/**
 * ModelCostSliceChart — Donut chart visualizing expenditure separated by LLM vendor model.
 *
 * Hooks directly into useModelCostAllocations to fetch data at the component level
 * (per architectural anti-pattern check: no prop drilling from FinOpsPage).
 * Renders a zero-state fallback when no data is available.
 */
export const ModelCostSliceChart: React.FC<ModelCostSliceChartProps> = ({
    days = 7,
    className,
}) => {
    const { data: slices = [], isLoading, isError } = useModelCostAllocations(days);

    if (isLoading) {
        return (
            <div className={className}>
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                    <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Model Cost Distribution</h3>
                    <div className="animate-pulse h-52 bg-obsidian-elevated rounded-lg" />
                </div>
            </div>
        );
    }

    if (isError) {
        return (
            <div className={className}>
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                    <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Model Cost Distribution</h3>
                    <div className="alert alert-error text-xs">Failed to load model cost data.</div>
                </div>
            </div>
        );
    }

    if (slices.length === 0) {
        return (
            <div className={className}>
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                    <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Model Cost Distribution</h3>
                    <div className="h-52 flex items-center justify-center">
                        <span className="badge badge-ghost text-xs">No model cost data available</span>
                    </div>
                </div>
            </div>
        );
    }

    const totalRuns = slices.reduce((sum, s) => sum + s.runCount, 0);

    return (
        <div className={className}>
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Model Cost Distribution</h3>
                <div className="h-52">
                    <ResponsiveContainer width="100%" height="100%">
                        <PieChart>
                            <Pie
                                data={slices}
                                cx="50%"
                                cy="50%"
                                innerRadius="42%"
                                outerRadius="72%"
                                dataKey="runCount"
                                nameKey="modelId"
                                paddingAngle={2}
                            >
                                {slices.map((_entry, index) => (
                                    <Cell key={index} fill={MODEL_COLORS[index % MODEL_COLORS.length]} />
                                ))}
                            </Pie>
                            <Tooltip
                                content={({ active, payload }) =>
                                    active && payload?.length ? (
                                        <div className="bg-(--theme-card) border border-(--theme-muted)/20 rounded px-2 py-1.5 text-xs space-y-0.5">
                                            <div className="font-semibold text-(--theme-foreground)">{payload[0].name}</div>
                                            <div className="text-(--theme-muted)">
                                                Runs: {(payload[0].value as number).toLocaleString()} ({((payload[0].value as number / totalRuns) * 100).toFixed(1)}%)
                                            </div>
                                            <div className="font-mono text-(--theme-foreground)">
                                                Est. {formatUsd((payload[0].payload as { estimatedUsd: number }).estimatedUsd)}
                                            </div>
                                        </div>
                                    ) : null
                                }
                            />
                            <Legend
                                verticalAlign="bottom"
                                iconSize={8}
                                wrapperStyle={{ fontSize: '10px' }}
                            />
                        </PieChart>
                    </ResponsiveContainer>
                </div>
            </div>
        </div>
    );
};
