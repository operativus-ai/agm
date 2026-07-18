import React, { useEffect, useRef } from 'react';
import { LineChart, Line, ResponsiveContainer, Tooltip } from 'recharts';

interface VelocitySparklineProps {
    /** Current burn rate value (USD/hr or cumulativeUsd) — appended on each render. */
    currentValue: number;
    /** Maximum number of data points to keep in the trailing buffer (default: 12). */
    maxPoints?: number;
    /** Whether this session is flagged as high-velocity (renders in error color). */
    isHighVelocity?: boolean;
}

interface SparkPoint {
    v: number;
}

/**
 * VelocitySparkline — A 30-second trailing micro-chart for live session burn rate.
 *
 * Maintains an internal rolling buffer (via useRef, invisible to React renders unless
 * the buffer array reference changes) that accumulates `currentValue` snapshots on
 * each polling cycle. Allows administrators to visually distinguish a spiking burn
 * rate from one that is holding steady at a glance — replacing the static "$1.40/hr" text.
 *
 * Architecture: No external state. The buffer is managed in a ref to avoid triggering
 * parent re-renders. The component re-renders when `currentValue` changes (from the
 * parent's 5-second polling interval), updating the chart with the new trailing window.
 *
 * Lifted into shared/components/charts to prevent cross-feature domain leakage.
 * Consumed by both the FinOps Live Telemetry tab and the Master Dashboard header ribbon.
 */
export const VelocitySparkline: React.FC<VelocitySparklineProps> = ({
    currentValue,
    maxPoints = 12,
    isHighVelocity = false,
}) => {
    const bufferRef = useRef<SparkPoint[]>([]);

    // Append the current value to the rolling buffer on each poll tick
    useEffect(() => {
        bufferRef.current = [
            ...bufferRef.current.slice(-(maxPoints - 1)),
            { v: currentValue },
        ];
    }, [currentValue, maxPoints]);

    // Seed initial point so the chart never renders empty on first paint
    const chartData = bufferRef.current.length > 0
        ? bufferRef.current
        : [{ v: currentValue }];

    const strokeColor = isHighVelocity
        ? 'oklch(65% 0.20 0)'      // error red
        : 'oklch(65% 0.20 270)';   // primary blue

    return (
        <div className="w-20 h-8" title={`Trailing velocity: ${currentValue.toFixed(4)}/hr`}>
            <ResponsiveContainer width="100%" height="100%">
                <LineChart data={chartData} margin={{ top: 2, right: 2, left: 2, bottom: 2 }}>
                    <Tooltip
                        content={({ active, payload }) =>
                            active && payload?.length ? (
                                <div className="bg-(--theme-card) border border-(--theme-muted)/20 rounded px-1.5 py-1 text-[10px]">
                                    ${(payload[0].value as number).toFixed(4)}
                                </div>
                            ) : null
                        }
                    />
                    <Line
                        type="monotone"
                        dataKey="v"
                        stroke={strokeColor}
                        strokeWidth={1.5}
                        dot={false}
                        isAnimationActive={false}
                    />
                </LineChart>
            </ResponsiveContainer>
        </div>
    );
};
