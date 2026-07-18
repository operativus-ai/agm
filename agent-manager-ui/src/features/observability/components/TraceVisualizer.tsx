import React, { useEffect, useState } from 'react';
import { observabilityApi } from '../api/observabilityApi';
import type { TraceSpan } from '../api/observabilityApi';
import { LuClock, LuCircleAlert } from 'react-icons/lu';

interface TraceVisualizerProps {
    runId: string;
}

const COLORS = [
    'bg-blue-500', 
    'bg-purple-500', 
    'bg-emerald-500', 
    'bg-amber-500', 
    'bg-rose-500', 
    'bg-cyan-500'
];

export const TraceVisualizer: React.FC<TraceVisualizerProps> = ({ runId }) => {
    const [spans, setSpans] = useState<TraceSpan[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let isMounted = true;
        const fetchTraces = async () => {
            try {
                setLoading(true);
                const response = await observabilityApi.getRunTraces(runId);
                // Convert list into tree structure (if not already hierarchical)
                // Assuming backend returns flat list
                const rootSpans = buildSpanTree(response);
                if (isMounted) setSpans(rootSpans);
            } catch (err: any) {
                if (isMounted) setError(err.message || 'Failed to load OpenTelemetry traces.');
            } finally {
                if (isMounted) setLoading(false);
            }
        };

        if (runId) {
            fetchTraces();
        }
        return () => { isMounted = false; };
    }, [runId]);

    const buildSpanTree = (flatSpans: TraceSpan[]): TraceSpan[] => {
        const spanMap = new Map<string, TraceSpan>();
        const roots: TraceSpan[] = [];

        flatSpans.forEach(span => {
            spanMap.set(span.id, { ...span, children: [] });
        });

        flatSpans.forEach(span => {
            const mappedSpan = spanMap.get(span.id)!;
            if (span.parentId && spanMap.has(span.parentId)) {
                spanMap.get(span.parentId)!.children!.push(mappedSpan);
            } else {
                roots.push(mappedSpan);
            }
        });

        return roots;
    };

    if (loading) return <div className="p-4 text-center text-sm opacity-50">Loading telemetry traces...</div>;
    if (error) return <div className="p-4 text-center text-rose-500 text-sm"><LuCircleAlert className="inline mr-2" />{error}</div>;
    if (!spans.length) return <div className="p-4 text-center text-sm opacity-50">No traces available for this run.</div>;

    // Calculate absolute start and end times to establish scale
    let minTime = Infinity;
    let maxTime = -Infinity;
    
    // Recursive pass to find absolute bounds
    const findBounds = (node: TraceSpan) => {
        const start = new Date(node.startTime).getTime();
        const end = new Date(node.endTime).getTime();
        if (start < minTime) minTime = start;
        if (end > maxTime) maxTime = end;
        node.children?.forEach(findBounds);
    };

    spans.forEach(findBounds);
    const totalDurationMs = maxTime - minTime;

    return (
        <div className="bg-obsidian-elevated rounded-xl p-4 md:p-6 w-full overflow-x-auto border border-obsidian-stroke">
            <h3 className="text-lg font-bold mb-4 flex items-center gap-2">
                <LuClock className="text-primary" />
                Execution Trace Timeline
                <span className="text-xs font-normal opacity-50 ml-auto">
                    Total: {totalDurationMs}ms
                </span>
            </h3>

            <div className="relative w-full min-w-[600px] border-l border-obsidian-stroke pl-4 py-2 space-y-3">
                {spans.map((rootSpan, idx) => (
                    <SpanBar 
                        key={rootSpan.id} 
                        span={rootSpan} 
                        minTime={minTime} 
                        totalDurationMs={totalDurationMs} 
                        depth={0} 
                        colorIndex={idx % COLORS.length}
                    />
                ))}
            </div>
        </div>
    );
};

const SpanBar: React.FC<{
    span: TraceSpan;
    minTime: number;
    totalDurationMs: number;
    depth: number;
    colorIndex: number;
}> = ({ span, minTime, totalDurationMs, depth, colorIndex }) => {
    
    const startOffsetMs = new Date(span.startTime).getTime() - minTime;
    
    // Safety check for duration
    const safeDuration = span.durationMs > 0 ? span.durationMs : 2; // min 2ms for visibility

    // Convert to percentages for CSS fluid grid
    // Prevent division by zero if totalDurationMs is 0
    const safeTotalRange = totalDurationMs > 0 ? totalDurationMs : 100;
    const marginLeftPerc = (startOffsetMs / safeTotalRange) * 100;
    const widthPerc = (safeDuration / safeTotalRange) * 100;

    const barColor = COLORS[colorIndex % COLORS.length];

    return (
        <div className="w-full flex flex-col gap-1">
            <div className="relative group w-full flex flex-col items-start cursor-pointer">
                {/* Information Header */}
                <div 
                    className="flex text-xs items-center gap-2 mb-1" 
                    style={{ marginLeft: `max(0px, calc(${marginLeftPerc}% - 100px))` }} // Try to keep near the bar but visible
                >
                    <span className="font-semibold">{span.name}</span>
                    <span className="opacity-50">{span.durationMs}ms</span>
                    {span.attributes['error'] && <LuCircleAlert className="text-error" />}
                </div>

                {/* The Timeline Bar */}
                <div 
                    className={`h-4 rounded-sm ${barColor} shadow-sm transition-all duration-300 hover:brightness-110 relative`}
                    style={{ 
                        marginLeft: `${marginLeftPerc}%`, 
                        width: `${Math.max(widthPerc, 0.5)}%` // Enforce min width for visibility
                    }}
                    title={`Span: ${span.name}\nDuration: ${span.durationMs}ms\nAttributes: ${JSON.stringify(span.attributes, null, 2)}`}
                />
            </div>

            {/* Recursively Render Children */}
            {span.children && span.children.length > 0 && (
                <div className="flex flex-col gap-2 mt-1 w-full pl-2 md:pl-4 border-l border-obsidian-stroke/50">
                    {span.children.map((child) => (
                        <SpanBar 
                            key={child.id}
                            span={child}
                            minTime={minTime}
                            totalDurationMs={totalDurationMs}
                            depth={depth + 1}
                            colorIndex={colorIndex + 1}
                        />
                    ))}
                </div>
            )}
        </div>
    );
};
