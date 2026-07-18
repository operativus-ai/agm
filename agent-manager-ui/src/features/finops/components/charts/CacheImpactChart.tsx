import React from 'react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { useCacheImpactSeries } from '../../hooks/useFinOps';

const API_COLOR = 'oklch(65% 0.20 270)';       // primary blue — API-served prompts
const CACHE_COLOR = 'oklch(65% 0.18 150)';      // success green — cache-deflected prompts

interface CacheImpactChartProps {
    days?: number;
    className?: string;
}

/**
 * CacheImpactChart — Stacked AreaChart mapping semantic cache deflections over trailing days.
 *
 * Hooks directly into useCacheImpactSeries to fetch data at the component level.
 * Renders two stacked areas: total API-served prompts and cache-deflected prompts,
 * allowing administrators to visually evaluate semantic cache ROI over time.
 */
export const CacheImpactChart: React.FC<CacheImpactChartProps> = ({
    days = 7,
    className,
}) => {
    const { data: series = [], isLoading, isError } = useCacheImpactSeries(days);

    if (isLoading) {
        return (
            <div className={className}>
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                    <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Cache Impact</h3>
                    <div className="animate-pulse h-52 bg-obsidian-elevated rounded-lg" />
                </div>
            </div>
        );
    }

    if (isError) {
        return (
            <div className={className}>
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                    <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Cache Impact</h3>
                    <div className="alert alert-error text-xs">Failed to load cache impact data.</div>
                </div>
            </div>
        );
    }

    const hasData = series.some(p => p.totalPrompts > 0 || p.cacheHits > 0);
    if (!hasData) {
        return (
            <div className={className}>
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                    <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Cache Impact</h3>
                    <div className="h-52 flex items-center justify-center">
                        <span className="badge badge-ghost text-xs">No cache activity recorded</span>
                    </div>
                </div>
            </div>
        );
    }

    // Compute apiHits as totalPrompts - cacheHits for the stacked area
    const chartData = series.map(p => ({
        date: p.date.substring(5), // MM-DD format
        apiHits: Math.max(0, p.totalPrompts - p.cacheHits),
        cacheHits: p.cacheHits,
    }));

    return (
        <div className={className}>
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
                <h3 className="text-sm font-semibold text-(--theme-foreground) mb-2">Cache Impact</h3>
                <div className="h-52">
                    <ResponsiveContainer width="100%" height="100%">
                        <AreaChart data={chartData} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                            <defs>
                                <linearGradient id="cacheGradientApi" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor={API_COLOR} stopOpacity={0.4} />
                                    <stop offset="95%" stopColor={API_COLOR} stopOpacity={0.05} />
                                </linearGradient>
                                <linearGradient id="cacheGradientHit" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor={CACHE_COLOR} stopOpacity={0.4} />
                                    <stop offset="95%" stopColor={CACHE_COLOR} stopOpacity={0.05} />
                                </linearGradient>
                            </defs>
                            <CartesianGrid strokeDasharray="3 3" stroke="var(--theme-muted)" strokeOpacity={0.15} />
                            <XAxis
                                dataKey="date"
                                tick={{ fill: 'var(--theme-muted)', fontSize: 10 }}
                                axisLine={false}
                                tickLine={false}
                            />
                            <YAxis
                                tick={{ fill: 'var(--theme-muted)', fontSize: 10 }}
                                axisLine={false}
                                tickLine={false}
                                width={36}
                            />
                            <Tooltip
                                content={({ active, payload, label }) =>
                                    active && payload?.length ? (
                                        <div className="bg-(--theme-card) border border-(--theme-muted)/20 rounded px-2 py-1.5 text-xs space-y-0.5">
                                            <div className="font-semibold text-(--theme-foreground)">{label}</div>
                                            {payload.map((entry, i) => (
                                                <div key={i} style={{ color: entry.color }}>
                                                    {entry.name}: {(entry.value as number).toLocaleString()}
                                                </div>
                                            ))}
                                        </div>
                                    ) : null
                                }
                            />
                            <Legend
                                verticalAlign="top"
                                iconSize={8}
                                wrapperStyle={{ fontSize: '10px', paddingBottom: '4px' }}
                            />
                            <Area
                                type="monotone"
                                dataKey="apiHits"
                                name="API Calls"
                                stackId="1"
                                stroke={API_COLOR}
                                fill="url(#cacheGradientApi)"
                                strokeWidth={1.5}
                            />
                            <Area
                                type="monotone"
                                dataKey="cacheHits"
                                name="Cache Deflections"
                                stackId="1"
                                stroke={CACHE_COLOR}
                                fill="url(#cacheGradientHit)"
                                strokeWidth={1.5}
                            />
                        </AreaChart>
                    </ResponsiveContainer>
                </div>
            </div>
        </div>
    );
};
