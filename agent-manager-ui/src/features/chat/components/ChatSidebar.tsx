import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../../shared/components/ui/Button';
import { Typography } from '../../../shared/components/ui/Typography';
import { logger } from '../../../utils/logger';
import { sessionApi, type AgentSession } from '../../sessions/api/sessionApi';

const PlusIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14"/><path d="M12 5v14"/></svg>
);

const SwitchIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m16 3 4 4-4 4"/><path d="M20 7H4"/><path d="m8 21-4-4 4-4"/><path d="M4 17h16"/></svg>
);

const MessageSquareIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
);

const TrashIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/></svg>
);

interface ChatSidebarProps {
  agentId: string;
  activeSessionId?: string;
  onNewChat: () => void;
  onSelectSession: (sessionId: string) => void;
  refreshTrigger?: number;
}

function formatRelativeTime(dateString: string): string {
  try {
    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    
    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    const diffHours = Math.floor(diffMins / 60);
    if (diffHours < 24) return `${diffHours}h ago`;
    const diffDays = Math.floor(diffHours / 24);
    if (diffDays < 7) return `${diffDays}d ago`;
    return date.toLocaleDateString();
  } catch {
    return '';
  }
}

export const ChatSidebar: React.FC<ChatSidebarProps> = ({ agentId, activeSessionId, onNewChat, onSelectSession, refreshTrigger }) => {
  const navigate = useNavigate();
  const [sessions, setSessions] = useState<AgentSession[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const fetchSessions = async () => {
    setIsLoading(true);
    try {
      const response = await sessionApi.listSessions({ agentId, size: 50 });
      setSessions(response.content || []);
    } catch (error) {
      logger.error('Failed to load sessions', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchSessions();
  }, [agentId, refreshTrigger]);

  const handleDelete = async (e: React.MouseEvent, sessionId: string) => {
    e.stopPropagation();
    try {
      await sessionApi.deleteSession(sessionId);
      setSessions(prev => prev.filter(s => s.id !== sessionId));
    } catch (error) {
      logger.error('Failed to delete session', error);
    }
  };

  return (
    <div className="flex flex-col h-full bg-obsidian-surface border-r border-obsidian-stroke/50 w-64 shrink-0 transition-all">
        
        {/* Header */}
        <div className="p-4 border-b border-obsidian-stroke/50 flex items-center justify-between">
           <div className="flex items-center gap-2">
               <div className="w-6 h-6 bg-agent-blue/10 text-agent-blue rounded-md flex items-center justify-center">
                    <MessageSquareIcon />
               </div>
               <span className="font-semibold text-sm">Chats</span>
           </div>
        </div>

        {/* New Chat + Switch Agent Actions */}
        <div className="p-4 space-y-2">
            <Button
                variant="outline"
                fullWidth
                onClick={onNewChat}
                className="justify-start gap-2 shadow-sm border-dashed"
            >
                <PlusIcon />
                New Chat
            </Button>
            <Button
                variant="ghost"
                fullWidth
                onClick={() => navigate('/chat')}
                className="justify-start gap-2 text-(--theme-muted) hover:text-(--theme-foreground)"
                title="Pick a different agent or team"
            >
                <SwitchIcon />
                Switch Agent
            </Button>
        </div>

        {/* History List */}
        <div className="flex-1 overflow-y-auto px-2 pb-4 space-y-1">
            <Typography.Text variant="muted" className="px-2 text-xs font-medium uppercase tracking-wider opacity-50 mt-4 mb-2">
                Recent
            </Typography.Text>
            
            {isLoading && (
              <div className="flex justify-center py-4">
                <span className="loading loading-spinner loading-sm text-agent-blue"></span>
              </div>
            )}

            {!isLoading && sessions.length === 0 && (
              <div className="px-2 py-4 text-center">
                <p className="text-xs text-theme-muted">No previous sessions</p>
              </div>
            )}

            {sessions.map((session) => (
              <button 
                key={session.id}
                onClick={() => onSelectSession(session.id)}
                className={`
                  w-full text-left p-2 rounded-lg text-sm truncate transition-colors group flex items-center justify-between
                  ${activeSessionId === session.id
                    ? 'bg-agent-blue/10 text-agent-blue font-medium'
                    : 'hover:bg-obsidian-elevated text-theme-muted hover:text-theme-foreground'
                  }
                `}
              >
                <div className="min-w-0 flex-1">
                  <span className="opacity-80 group-hover:opacity-100 block truncate">
                    {session.title || `Session ${session.id.substring(0, 8)}`}
                  </span>
                  <div className="text-[10px] opacity-40">
                    {formatRelativeTime(session.updatedAt || session.createdAt)}
                  </div>
                </div>
                <button
                  onClick={(e) => handleDelete(e, session.id)}
                  className="opacity-0 group-hover:opacity-60 hover:opacity-100! hover:text-error transition-opacity p-1 rounded"
                  title="Delete session"
                >
                  <TrashIcon />
                </button>
              </button>
            ))}
        </div>
    </div>
  );
};
