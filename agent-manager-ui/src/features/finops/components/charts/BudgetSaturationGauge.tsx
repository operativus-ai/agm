import React from 'react';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { formatUsd } from '../../utils/formatCurrency';

interface BudgetSaturationGaugeProps {
    /** Current daily expenditure in USD. */
    currentSpend: number;
    /** Maximum daily budget ceiling in USD. */
    budgetCeiling: number;
    /** Optional CSS class for the container. */
    className?: string;
}

const CONSUMED_COLOR = 'oklch(65% 0.20 270)';   // primary blue
const REMAINING_COLOR = 'oklch(30% 0.05 270)';   // muted dark
const DANGER_COLOR = 'oklch(65% 0.20 0)';        // error red

/**
 * BudgetSaturationGauge — A 180-degree half-donut gauge showing daily budget utilization.
 *
 * Uses a recharts PieChart with startAngle=180 and endAngle=0 to render a speedometer-style
 * gauge. The consumed slice transitions from primary blue to error red when utilization exceeds 90%.
 */
export const BudgetSaturationGauge: React.FC<BudgetSaturationGaugeProps> = ({
    currentSpend,
    budgetCeiling,
    className,
}) => {
    const saturation = budgetCeiling > 0 ? Math.min((currentSpend / budgetCeiling) * 100, 100) : 0;
    const isOverBudget = saturation > 90;
    const consumed = Math.min(currentSpend, budgetCeiling);
    const remaining = Math.max(budgetCeiling - consumed, 0);

    const data = [
        { name: 'Consumed', value: consumed },
        { name: 'Remaining', value: remaining },
    ];

    const consumedColor = isOverBudget ? DANGER_COLOR : CONSUMED_COLOR;

    return (
        <div className={className}>
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Budget Saturation</h3>
                <div className="h-40 relative">
                    <ResponsiveContainer width="100%" height="100%">
                        <PieChart>
                            <Pie
                                data={data}
                                cx="50%"
                                cy="85%"
                                startAngle={180}
                                endAngle={0}
                                innerRadius="60%"
                                outerRadius="90%"
                                dataKey="value"
                                stroke="none"
                                paddingAngle={1}
                            >
                                <Cell fill={consumedColor} />
                                <Cell fill={REMAINING_COLOR} />
                            </Pie>
                            <Tooltip
                                content={({ active, payload }) =>
                                    active && payload?.length ? (
                                        <div className="bg-(--theme-card) border border-(--theme-muted)/20 rounded px-2 py-1 text-xs">
                                            <span className="text-(--theme-foreground)">{payload[0].name}: </span>
                                            <span className="font-mono">{formatUsd(payload[0].value as number)}</span>
                                        </div>
                                    ) : null
                                }
                            />
                        </PieChart>
                    </ResponsiveContainer>
                    <div className="absolute inset-0 flex flex-col items-center justify-end pb-2">
                        <span className={`text-2xl font-bold font-mono ${isOverBudget ? 'text-error' : 'text-primary'}`}>
                            {saturation.toFixed(0)}%
                        </span>
                        <span className="text-[10px] text-(--theme-muted)">
                            {formatUsd(currentSpend)} / {formatUsd(budgetCeiling)}
                        </span>
                    </div>
                </div>
            </div>
        </div>
    );
};
