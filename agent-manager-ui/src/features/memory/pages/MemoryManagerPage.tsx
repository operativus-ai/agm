import React, { useEffect, useMemo, useState } from 'react';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Tabs } from '../../../shared/components/ui/Tabs';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

import { LuBrain, LuSearch, LuDownload, LuX } from 'react-icons/lu';
import { memoryApi } from '../api/memoryApi';
import type { MemoryStats, MemoryEntry } from '../api/memoryApi';
import { AddMemoryModal } from '../components/AddMemoryModal';
import { TagMemoryModal } from '../components/TagMemoryModal';
import { RtbfWipePanel } from '../components/RtbfWipePanel';
import { createSearchColumns, createTimelineColumns } from '../components/memoryColumns';

// ── Main Page ────────────────────────────────────────────────────────────────
export const MemoryManagerPage: React.FC = () => {
    const [stats, setStats] = useState<MemoryStats | null>(null);
    const [topics, setTopics] = useState<string[]>([]);
    const [timeline, setTimeline] = useState<MemoryEntry[]>([]);
    const [loadingStats, setLoadingStats] = useState(true);
    const [loadingTimeline, setLoadingTimeline] = useState(false);

    const [searchQuery, setSearchQuery] = useState('');
    const [topicFilter, setTopicFilter] = useState<string | null>(null);
    const [searchResults, setSearchResults] = useState<string[]>([]);
    const [loadingSearch, setLoadingSearch] = useState(false);

    const [isOptimizing, setIsOptimizing] = useState(false);
    const [isExporting, setIsExporting] = useState(false);
    const [isAddModalOpen, setIsAddModalOpen] = useState(false);
    const [tagTarget, setTagTarget] = useState<MemoryEntry | null>(null);

    const DEFAULT_USER = 'default';

    useEffect(() => {
        loadDashboardData();
    }, []);

    const loadDashboardData = async () => {
        setLoadingStats(true);
        try {
            const [statsData, topicsData] = await Promise.all([
                memoryApi.getMemoryStats(),
                memoryApi.getMemoryTopics(),
            ]);
            setStats(statsData);
            setTopics(topicsData ?? []);
        } catch (err) {
            console.error('Failed to load memory dashboard data', err);
        } finally {
            setLoadingStats(false);
        }
    };

    const loadTimeline = async () => {
        setLoadingTimeline(true);
        try {
            const data = await memoryApi.getTimeline(DEFAULT_USER);
            setTimeline(data ?? []);
        } catch (err) {
            console.error('Failed to load timeline', err);
        } finally {
            setLoadingTimeline(false);
        }
    };

    const handleTabChange = (tab: string) => {
        if (tab === 'timeline' && timeline.length === 0) {
            loadTimeline();
        }
    };

    const handleSearch = async (e?: React.FormEvent, overrideQuery?: string) => {
        e?.preventDefault();
        const q = overrideQuery ?? searchQuery;
        if (!q.trim()) return;

        setLoadingSearch(true);
        try {
            const results = await memoryApi.searchMemories(q);
            setSearchResults(results ?? []);
        } catch (err) {
            console.error('Search failed', err);
            setSearchResults([]);
        } finally {
            setLoadingSearch(false);
        }
    };

    const handleTopicClick = (topic: string) => {
        if (topicFilter === topic) {
            setTopicFilter(null);
            setSearchQuery('');
            setSearchResults([]);
        } else {
            setTopicFilter(topic);
            setSearchQuery(topic);
            handleSearch(undefined, topic);
        }
    };

    const handleDelete = async (content: string) => {
        if (!confirm('Delete this memory entry?')) return;
        try {
            await memoryApi.deleteMemories([content]);
            setSearchResults(prev => prev.filter(r => r !== content));
            loadDashboardData();
        } catch (err) {
            console.error('Delete failed', err);
            alert('Failed to delete memory.');
        }
    };

    const handleOptimize = async () => {
        setIsOptimizing(true);
        try {
            const { jobId } = await memoryApi.optimizeMemories();
            alert(`Memory optimization queued (job: ${jobId}). Topics will update once complete.`);
            await loadDashboardData();
        } catch (err) {
            console.error('Optimization failed', err);
            alert('Failed to start optimization.');
        } finally {
            setIsOptimizing(false);
        }
    };

    const handleExport = async () => {
        setIsExporting(true);
        try {
            const data = await memoryApi.exportMemories(DEFAULT_USER);
            const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `memories-${DEFAULT_USER}-${new Date().toISOString().split('T')[0]}.json`;
            a.click();
            URL.revokeObjectURL(url);
        } catch (err) {
            console.error('Export failed', err);
            alert('Failed to export memories.');
        } finally {
            setIsExporting(false);
        }
    };

    const searchColumns = useMemo(() => createSearchColumns(handleDelete), []);
    const timelineColumns = useMemo(() => createTimelineColumns(setTagTarget), []);

    const searchData = useMemo(
        () => searchResults.map((content, index) => ({ content, index })),
        [searchResults]
    );

    return (
        <PageContainer>
            <PageHeader
                icon={LuBrain}
                title="Memory Vector Store"
                subtitle="Manage agent long-term memory, knowledge injections, and pgvector state."
                actions={
                    <>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={handleExport}
                            disabled={isExporting}
                            title="Export memories as JSON"
                        >
                            {isExporting
                                ? <span className="loading loading-spinner loading-sm" />
                                : <><LuDownload className="w-3.5 h-3.5 mr-1" />Export</>}
                        </Button>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={handleOptimize}
                            disabled={isOptimizing}
                        >
                            {isOptimizing ? <span className="loading loading-spinner loading-sm" /> : 'Optimize'}
                        </Button>
                        <Button size="sm" onClick={() => setIsAddModalOpen(true)}>
                            Inject Memory
                        </Button>
                    </>
                }
            />

            <Tabs defaultValue="overview" onValueChange={handleTabChange}>
                <Tabs.List>
                    <Tabs.Trigger value="overview">Overview</Tabs.Trigger>
                    <Tabs.Trigger value="timeline">Timeline</Tabs.Trigger>
                    <Tabs.Trigger value="compliance">Compliance</Tabs.Trigger>
                </Tabs.List>

                {/* ── Overview Tab ────────────────────────────────────── */}
                <Tabs.Content value="overview">
                    <div className="flex flex-col gap-6">
                        {/* Stats row */}
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-5">
                                <div className="text-xs uppercase tracking-wider text-(--theme-muted) mb-1">Total Memories</div>
                                <div className="text-2xl font-bold text-primary">
                                    {loadingStats
                                        ? <span className="loading loading-spinner loading-md" />
                                        : (stats?.totalMemories ?? 0)}
                                </div>
                                <div className="text-xs text-(--theme-muted) mt-1">Vectors in pgvector store</div>
                            </div>

                            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-5 md:col-span-2">
                                <div className="text-xs uppercase tracking-wider text-(--theme-muted) mb-1">Discovered Topics</div>
                                <div className="text-2xl font-bold text-secondary mb-2">
                                    {loadingStats
                                        ? <span className="loading loading-spinner loading-md" />
                                        : topics.length}
                                </div>
                                {topics.length > 0 ? (
                                    <div className="flex flex-wrap gap-1">
                                        {topics.map(t => (
                                            <button
                                                key={t}
                                                onClick={() => handleTopicClick(t)}
                                                className="focus:outline-none"
                                            >
                                                <Badge
                                                    variant={topicFilter === t ? 'primary' : 'neutral'}
                                                    outline={topicFilter !== t}
                                                    className="text-xs cursor-pointer hover:opacity-80 transition-opacity"
                                                >
                                                    {t}
                                                </Badge>
                                            </button>
                                        ))}
                                    </div>
                                ) : (
                                    !loadingStats && (
                                        <p className="text-xs text-(--theme-muted)">
                                            No topics found. Run <strong>Optimize</strong> to extract topics from existing memories.
                                        </p>
                                    )
                                )}
                            </div>
                        </div>

                        {/* Search */}
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6">
                            <div className="flex items-center justify-between mb-4">
                                <h2 className="text-lg font-semibold text-(--theme-foreground)">Search Vector Store</h2>
                                {topicFilter && (
                                    <button
                                        onClick={() => { setTopicFilter(null); setSearchQuery(''); setSearchResults([]); }}
                                        className="flex items-center gap-1 text-xs text-(--theme-muted) hover:text-(--theme-foreground) transition-colors"
                                    >
                                        <LuX className="w-3 h-3" /> Clear filter: <strong>{topicFilter}</strong>
                                    </button>
                                )}
                            </div>
                            <form onSubmit={handleSearch} className="flex gap-3 mb-6">
                                <div className="relative flex-1 max-w-md">
                                    <LuSearch className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-(--theme-muted)" />
                                    <input
                                        type="text"
                                        placeholder="Query similarity..."
                                        className="input input-bordered w-full pl-9"
                                        value={searchQuery}
                                        onChange={(e) => { setSearchQuery(e.target.value); setTopicFilter(null); }}
                                    />
                                </div>
                                <Button type="submit" variant="outline" disabled={loadingSearch || !searchQuery.trim()}>
                                    {loadingSearch ? <span className="loading loading-spinner loading-sm" /> : 'Search'}
                                </Button>
                            </form>

                            {searchResults.length > 0 ? (
                                <DataTable
                                    columns={searchColumns}
                                    data={searchData}
                                    enablePagination
                                    defaultPageSize={10}
                                    emptyMessage="No results."
                                />
                            ) : (
                                searchQuery && !loadingSearch && (
                                    <div className="text-center py-8 text-(--theme-muted)">
                                        No similar memories found for this query.
                                    </div>
                                )
                            )}
                        </div>
                    </div>
                </Tabs.Content>

                {/* ── Timeline Tab ─────────────────────────────────────── */}
                <Tabs.Content value="timeline">
                    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6">
                        <div className="flex items-center justify-between mb-4">
                            <h2 className="text-lg font-semibold text-(--theme-foreground)">Memory Timeline</h2>
                            <Button variant="outline" size="sm" onClick={loadTimeline} disabled={loadingTimeline}>
                                {loadingTimeline ? <span className="loading loading-spinner loading-sm" /> : 'Refresh'}
                            </Button>
                        </div>
                        {loadingTimeline ? (
                            <div className="flex justify-center py-12">
                                <span className="loading loading-spinner loading-lg" />
                            </div>
                        ) : timeline.length === 0 ? (
                            <div className="text-center py-12 text-(--theme-muted)">
                                No memories found for this user.
                            </div>
                        ) : (
                            <DataTable
                                columns={timelineColumns}
                                data={timeline}
                                enablePagination
                                defaultPageSize={20}
                                emptyMessage="No memories."
                            />
                        )}
                    </div>
                </Tabs.Content>

                {/* ── Compliance Tab ───────────────────────────────────── */}
                <Tabs.Content value="compliance">
                    <RtbfWipePanel />
                </Tabs.Content>
            </Tabs>

            <AddMemoryModal
                isOpen={isAddModalOpen}
                onClose={() => setIsAddModalOpen(false)}
                onSuccess={loadDashboardData}
            />

            {tagTarget && (
                <TagMemoryModal
                    memory={tagTarget}
                    onClose={() => setTagTarget(null)}
                    onSaved={() => {
                        loadTimeline();
                        loadDashboardData();
                    }}
                />
            )}
        </PageContainer>
    );
};
