import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { LuMessageSquare, LuShieldCheck, LuRefreshCw, LuCheck } from 'react-icons/lu';
import { Button } from '../../../shared/components/ui/Button';
import { AgentsApi } from '../../agents/api/agents-api';
import { logger } from '../../../utils/logger';

/**
 * Quick-action CTAs on the dashboard for the three most-frequent operator paths.
 * Replaces the prior pattern of nested sidebar drilling for standard operations.
 *
 * Actions:
 *   - New Chat: navigates to /chat (the agent-session entry point)
 *   - Review Approvals: navigates to /approvals (HITL queue review surface)
 *   - Reload Agent Configs: POST /api/agents/cache/clear — evicts the @Cacheable
 *     agent-registry caches so the next agent run reads fresh config from the DB.
 *     (The "Flush Semantic Cache" item from the original gaps doc maps to this
 *     existing backend endpoint; semantic-cache flush is a separate concept that
 *     doesn't have an endpoint today.)
 */
export const QuickActionsWidget: React.FC = () => {
    const [cacheState, setCacheState] = useState<'idle' | 'busy' | 'done' | 'error'>('idle');

    const handleClearCache = async () => {
        setCacheState('busy');
        try {
            await AgentsApi.clearCache();
            setCacheState('done');
            setTimeout(() => setCacheState('idle'), 3_000);
        } catch (e) {
            logger.error('Cache clear failed:', e);
            setCacheState('error');
            setTimeout(() => setCacheState('idle'), 3_000);
        }
    };

    return (
        <div className="rounded-xl border border-obsidian-stroke bg-(--theme-card) p-5 mb-6">
            <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted) mb-4">Quick Actions</h3>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                <Link to="/chat" className="block">
                    <Button variant="outline" className="w-full justify-start gap-2 h-12">
                        <LuMessageSquare className="w-4 h-4" />
                        <div className="flex flex-col items-start leading-tight">
                            <span className="text-sm font-semibold">New Chat</span>
                            <span className="text-[10px] text-(--theme-muted) font-normal">Start an agent session</span>
                        </div>
                    </Button>
                </Link>

                <Link to="/approvals" className="block">
                    <Button variant="outline" className="w-full justify-start gap-2 h-12">
                        <LuShieldCheck className="w-4 h-4" />
                        <div className="flex flex-col items-start leading-tight">
                            <span className="text-sm font-semibold">Review Approvals</span>
                            <span className="text-[10px] text-(--theme-muted) font-normal">HITL queue</span>
                        </div>
                    </Button>
                </Link>

                <Button
                    variant="outline"
                    className="w-full justify-start gap-2 h-12"
                    disabled={cacheState === 'busy'}
                    onClick={handleClearCache}
                >
                    {cacheState === 'done' ? (
                        <LuCheck className="w-4 h-4 text-active-green" />
                    ) : (
                        <LuRefreshCw className={`w-4 h-4 ${cacheState === 'busy' ? 'animate-spin' : ''}`} />
                    )}
                    <div className="flex flex-col items-start leading-tight">
                        <span className="text-sm font-semibold">
                            {cacheState === 'done' ? 'Cache Cleared' : cacheState === 'error' ? 'Clear Failed' : 'Reload Agent Configs'}
                        </span>
                        <span className="text-[10px] text-(--theme-muted) font-normal">
                            {cacheState === 'busy' ? 'Evicting…' : 'Force fresh DB reload'}
                        </span>
                    </div>
                </Button>
            </div>
        </div>
    );
};
