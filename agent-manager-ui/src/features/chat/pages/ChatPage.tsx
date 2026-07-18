import React, { useState, useRef, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ChatSidebar } from '../components/ChatSidebar';
import { MessageBubble } from '../components/MessageBubble';
import { ChatInput } from '../components/ChatInput';
import { ChatApi } from '../api/chat-api';
import { AgentsApi } from '../../agents/api/agents-api';
import { sessionApi } from '../../sessions/api/sessionApi';
import { useAuth } from '../../auth/context/AuthContext';
import type { ChatMessage, StreamChunk, MediaInput, RunOptions } from '../types';
import { MessageRole, RunStatus } from '../../../shared/types/enums';
import type { AgentConfig } from '../../../shared/types/api';
import { ChatSettings } from '../components/ChatSettings';
import { LuBot, LuUsers, LuArrowRightLeft, LuCheck } from 'react-icons/lu';
// uuid removed, using fallback generator

// Simple UUID generator fallback if package missing
const generateId = () => Math.random().toString(36).substring(2, 15);

export const ChatPage: React.FC = () => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [abortController, setAbortController] = useState<(() => void) | null>(null);
  const { agentId: routeAgentId, sessionId: urlSessionId } = useParams<{ agentId: string; sessionId?: string }>();
  const navigate = useNavigate();

  // /chat with no agentId is now handled by ChatAgentPickerPage at the router
  // level. We still guard against literal "undefined" strings landing here via
  // malformed navigation — those bounce the user back to the picker.
  useEffect(() => {
    if (!routeAgentId || routeAgentId === 'undefined') {
      navigate('/chat', { replace: true });
    }
  }, [routeAgentId, navigate]);
  const agentId = routeAgentId && routeAgentId !== 'undefined' ? routeAgentId : '';

  const [sessionId, setSessionId] = useState<string>(urlSessionId || 'new');
  const [runOptions, setRunOptions] = useState<RunOptions>({});
  const [refreshTrigger, setRefreshTrigger] = useState(0);
  const skipRestoreRef = useRef(false);

  const [agentConfig, setAgentConfig] = useState<AgentConfig | null>(null);
  const [agentNotFound, setAgentNotFound] = useState(false);
  const { user } = useAuth();
  const isAdmin = user?.roles?.includes('ROLE_ADMIN') || user?.roles?.includes('ROLE_SUPER_ADMIN');

  /** All agents available to switch to — only fetched for admins, since non-admins
   *  pick their starting agent from /chat (ChatAgentPickerPage) but can't reassign
   *  the active session mid-conversation. */
  const [allAgents, setAllAgents] = useState<AgentConfig[]>([]);
  const [agentSwitcherOpen, setAgentSwitcherOpen] = useState(false);

  useEffect(() => {
    if (!agentId) return; // Router-level redirect handles this case
    const fetchAgent = async () => {
        try {
            setAgentNotFound(false);
            const config = await AgentsApi.getAgent(agentId);
            setAgentConfig(config);
        } catch (error: unknown) {
            const apiError = error as { status?: number };
            if (apiError?.status === 404) {
                setAgentNotFound(true);
            }
            console.error("Failed to load agent configuration", error);
        }
    };
    fetchAgent();
  }, [agentId]);

  // Admin-only: load the agent list once so the in-chat agent switcher has
  // something to render. Non-admins skip the call entirely.
  useEffect(() => {
    if (!isAdmin) return;
    AgentsApi.getAgents()
        .then(data => setAllAgents(Array.isArray(data) ? data : []))
        .catch(err => console.error('Failed to load agent list for switcher', err));
  }, [isAdmin]);

  const handleSwitchAgent = (newAgentId: string) => {
    setAgentSwitcherOpen(false);
    if (newAgentId === agentId) return;
    // Navigate to a fresh session under the new agent — preserving the old session
    // id would be incorrect because sessions are scoped to a single agent on the BE.
    navigate(`/chat/${newAgentId}`);
  };

  // Auto-restore session from URL path segment on mount/external navigation only.
  // Programmatic navigate() calls set skipRestoreRef to avoid clearing mid-stream.
  useEffect(() => {
    if (skipRestoreRef.current) {
      skipRestoreRef.current = false;
      return;
    }
    if (urlSessionId && urlSessionId !== 'new') {
      handleSelectSession(urlSessionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlSessionId]);

  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const handleSend = async (text: string, media?: MediaInput[], useSync?: boolean, useBackground?: boolean) => {
    // Negotiate session identity: generate UUID on first message, then sync URL
    const activeSessionId = sessionId === 'new' ? crypto.randomUUID() : sessionId;
    if (activeSessionId !== sessionId) {
      setSessionId(activeSessionId);
      skipRestoreRef.current = true;
      navigate(`/chat/${agentId}/${activeSessionId}`, { replace: true });
    }

    // 1. Add User Message
    const userMsg: ChatMessage = {
      id: generateId(),
      role: MessageRole.USER,
      content: text,
      timestamp: new Date().toISOString(),
    };

    setMessages(prev => [...prev, userMsg]);
    setIsLoading(true);

    // 2. Prepare Placeholder Agent Message
    const agentMsgId = generateId();
    const startTimeMs = Date.now();
    const agentMsg: ChatMessage = {
      id: agentMsgId,
      role: MessageRole.ASSISTANT,
      content: '', // Starts empty
      timestamp: new Date().toISOString(),
      thoughts: '',
      startTimeMs,
    };
    
    setMessages(prev => [...prev, agentMsg]);

    // 3. Call API
    if (useBackground) {
        try {
            const response = await ChatApi.startBackgroundRun(agentId, {
                message: text,
                stream: false,
                media,
                userId: user?.id,
                orgId: user?.orgId,
                sessionId: activeSessionId,
                options: Object.keys(runOptions).length > 0 ? runOptions : undefined,
            });
            setRefreshTrigger(prev => prev + 1);
            setMessages(prev => {
                const newMessages = [...prev];
                const targetIndex = newMessages.findIndex(m => m.id === agentMsgId);
                if (targetIndex !== -1) {
                    newMessages[targetIndex] = {
                        ...newMessages[targetIndex],
                        content: `**Background Task Started**\n\nRun ID: \`${response.runId}\`\nStatus: \`${response.status}\`\n\nYou can safely navigate away. The result will appear in the session history once completed.`,
                        status: response.status as RunStatus,
                        runId: response.runId,
                        executionTimeMs: 0
                    };
                }
                return newMessages;
            });
        } catch (err: unknown) {
             const errMsg = err instanceof Error ? err.message : 'Failed to start background run.';
             console.error("Background message error", err);
             setMessages(prev => {
                const newMessages = [...prev];
                const targetIndex = newMessages.findIndex(m => m.id === agentMsgId);
                if (targetIndex !== -1) {
                    newMessages[targetIndex] = {
                        ...newMessages[targetIndex],
                        content: `Error: ${errMsg}`,
                        executionTimeMs: Date.now() - startTimeMs
                    };
                }
                return newMessages;
            });
        } finally {
            setIsLoading(false);
        }
        return;
    }

    if (useSync) {
        try {
            const response = await ChatApi.sendMessage(agentId, {
                message: text,
                stream: false,
                media,
                userId: user?.id,
                orgId: user?.orgId,
                sessionId: activeSessionId,
                options: Object.keys(runOptions).length > 0 ? runOptions : undefined,
            });
            setRefreshTrigger(prev => prev + 1);
            setMessages(prev => {
                const newMessages = [...prev];
                const targetIndex = newMessages.findIndex(m => m.id === agentMsgId);
                if (targetIndex !== -1) {
                    newMessages[targetIndex] = {
                        ...newMessages[targetIndex],
                        content: response.content || '',
                        thoughts: response.reasoningSteps?.join('\n') || '',
                        toolCalls: response.tools,
                        status: response.status,
                        runId: response.runId,
                        metadata: response.metadata,
                        executionTimeMs: Date.now() - startTimeMs
                    };
                }
                return newMessages;
            });
        } catch (err: unknown) {
             const errMsg = err instanceof Error ? err.message : 'Failed to send message.';
             console.error("Sync message error", err);
             setMessages(prev => {
                const newMessages = [...prev];
                const targetIndex = newMessages.findIndex(m => m.id === agentMsgId);
                if (targetIndex !== -1) {
                    newMessages[targetIndex] = {
                        ...newMessages[targetIndex],
                        content: `Error: ${errMsg}`,
                        executionTimeMs: Date.now() - startTimeMs
                    };
                }
                return newMessages;
            });
        } finally {
            setIsLoading(false);
        }
        return;
    }

    try {
        const stopParam = ChatApi.streamMessage(
            agentId,
            {
                message: text,
                stream: true,
                media,
                userId: user?.id,
                orgId: user?.orgId,
                sessionId: activeSessionId,
                generateFollowups: true,
                options: Object.keys(runOptions).length > 0 ? runOptions : undefined,
            },
            (chunk: StreamChunk) => {
                setMessages(prev => {
                    const newMessages = [...prev];
                    const targetIndex = newMessages.findIndex(m => m.id === agentMsgId);
                    if (targetIndex === -1) return prev;

                    const targetMsg = { ...newMessages[targetIndex] };
                    
                    if (chunk.type === 'thought' && chunk.content) {
                        targetMsg.thoughts = (targetMsg.thoughts || '') + chunk.content;
                    } else if (chunk.type === 'content' && chunk.content) {
                        targetMsg.content = (targetMsg.content || '') + chunk.content;
                    }
                    
                    // Handle AgentStreamEvent format
                    if (chunk.event === 'REASONING_DELTA' && chunk.data) {
                        targetMsg.thoughts = (targetMsg.thoughts || '') + chunk.data;
                    } else if (chunk.event === 'CONTENT_DELTA' && chunk.data) {
                        targetMsg.content = (targetMsg.content || '') + chunk.data;
                    } else if (chunk.event === 'FOLLOWUP_SUGGESTION' && chunk.data) {
                        try {
                            // Extract the JSON array from potentially wrapped conversational text
                            const arrayMatch = chunk.data.match(/\[[\s\S]*\]/);
                            if (arrayMatch) {
                                const parsedFollowups = JSON.parse(arrayMatch[0]);
                                if (Array.isArray(parsedFollowups)) {
                                    targetMsg.followUpSuggestions = parsedFollowups;
                                }
                            }
                        } catch (_e) {
                            console.warn("Failed to parse FOLLOWUP_SUGGESTION array", chunk.data);
                        }
                    } else if (chunk.event === 'CONTENT_DONE') {
                        if (!targetMsg.executionTimeMs) {
                            targetMsg.executionTimeMs = Date.now() - startTimeMs;
                        }
                    } else if (chunk.event === 'START') {
                        if (chunk.data) targetMsg.runId = chunk.data;
                        setRefreshTrigger(prev => prev + 1);
                    } else if (chunk.event === 'PAUSED') {
                        targetMsg.status = RunStatus.PAUSED;
                    } else if (chunk.event === 'STOP') {
                        targetMsg.status = RunStatus.COMPLETED;
                    } else if (chunk.event === 'ERROR') {
                        targetMsg.status = RunStatus.FAILED;
                        targetMsg.content = (targetMsg.content || '') + '\n\n**Error:** ' + (chunk.data || 'Unknown stream error');
                    } else if (chunk.event === 'AGENT_SWITCH') {
                        targetMsg.metadata = { ...targetMsg.metadata, switchedToAgent: chunk.data };
                    } else if (chunk.event === 'METRICS') {
                        try {
                            targetMsg.usage = JSON.parse(chunk.data || '{}');
                        } catch (_e) { /* ignore malformed metrics */ }
                    } else if (chunk.event === 'TOOL_START') {
                        try {
                            const tStart = JSON.parse(chunk.data || '{}');
                            if (tStart.name) {
                                targetMsg.toolCalls = [...(targetMsg.toolCalls || []), {
                                    id: tStart.id || Date.now().toString(),
                                    name: tStart.name,
                                    arguments: tStart.arguments || '',
                                    status: 'pending'
                                }];
                            }
                        } catch (_e) { /* ignore malformed tool start */ }
                    } else if (chunk.event === 'TOOL_END' || chunk.event === 'TOOL_ERROR') {
                        try {
                            const tEnd = JSON.parse(chunk.data || '{}');
                            if (tEnd.id && targetMsg.toolCalls) {
                                targetMsg.toolCalls = targetMsg.toolCalls.map(t => 
                                    t.id === tEnd.id ? { 
                                        ...t, 
                                        status: chunk.event === 'TOOL_END' ? 'success' : 'error',
                                        result: tEnd.result || tEnd.error
                                    } : t
                                );
                            }
                        } catch (_e) { /* ignore malformed tool end */ }
                    }
                    newMessages[targetIndex] = targetMsg;
                    return newMessages;
                });
            },
            (err) => {
                console.error("Stream error", err);
                setMessages(prev => {
                    const newMessages = [...prev];
                    const targetIndex = newMessages.findIndex(m => m.id === agentMsgId);
                    if (targetIndex !== -1 && !newMessages[targetIndex].executionTimeMs) {
                        newMessages[targetIndex] = {
                            ...newMessages[targetIndex],
                            status: RunStatus.FAILED,
                            content: newMessages[targetIndex].content + `\n\n**Error:** Cannot connect to agent stream. (${err instanceof Error ? err.message : 'Unknown error'})`,
                            executionTimeMs: Date.now() - startTimeMs
                        };
                    }
                    return newMessages;
                });
                setIsLoading(false);
            },
            () => {
                setMessages(prev => {
                    const newMessages = [...prev];
                    const targetIndex = newMessages.findIndex(m => m.id === agentMsgId);
                    if (targetIndex !== -1 && !newMessages[targetIndex].executionTimeMs) {
                        newMessages[targetIndex] = {
                            ...newMessages[targetIndex],
                            executionTimeMs: Date.now() - startTimeMs
                        };
                    }
                    return newMessages;
                });
                setIsLoading(false);
                setAbortController(null);
                setRefreshTrigger(prev => prev + 1);
            }
        );
        setAbortController(() => stopParam);
    } catch (e) {
        setIsLoading(false);
        console.error("Setup error", e);
    }
  };

  const handleStop = () => {
    if (abortController) {
        abortController();
        setAbortController(null);
        setIsLoading(false);
        setMessages(prev => {
            const newMessages = [...prev];
            const lastMsg = newMessages[newMessages.length - 1];
            if (lastMsg && lastMsg.role === MessageRole.ASSISTANT && !lastMsg.executionTimeMs && lastMsg.startTimeMs) {
                lastMsg.executionTimeMs = Date.now() - lastMsg.startTimeMs;
                if (lastMsg.runId) {
                    ChatApi.cancelRun(agentId, lastMsg.runId).catch(e => console.warn('Failed to cancel on backend', e));
                }
            }
            return newMessages;
        });
    }
  };

  const handleNewChat = () => {
      if (isLoading) handleStop();
      navigate(`/chat/${agentId}`, { replace: true });
      setMessages([]);
      setSessionId('new');
      setRunOptions({});
  };

  const handleSelectSession = async (selectedSessionId: string) => {
      if (isLoading) handleStop();
      skipRestoreRef.current = true;
      navigate(`/chat/${agentId}/${selectedSessionId}`, { replace: true });
      setMessages([]);
      setSessionId(selectedSessionId);
      setRunOptions({});

      try {
          const runs = await sessionApi.getSessionRuns(selectedSessionId);
          if (runs && runs.length > 0) {
              const restoredMessages: ChatMessage[] = [];
              // Sort runs oldest to newest
              const sortedRuns = [...runs].sort((a,b) => new Date(a.startedAt || 0).getTime() - new Date(b.startedAt || 0).getTime());
              
              sortedRuns.forEach(run => {
                  if (run.input) {
                      restoredMessages.push({
                          id: `usr-${run.id}`,
                          role: MessageRole.USER,
                          content: run.input,
                          timestamp: run.startedAt || new Date().toISOString()
                      });
                  }
                  if (run.output || run.logOutput) {
                      restoredMessages.push({
                          id: `ast-${run.id}`,
                          runId: run.id,
                          role: MessageRole.ASSISTANT,
                          content: run.output || run.logOutput || '',
                          timestamp: run.completedAt || run.startedAt || new Date().toISOString(),
                          status: (run.status as RunStatus) || RunStatus.COMPLETED,
                          metadata: run.metrics
                      });
                  }
              });
              setMessages(restoredMessages);
          }
      } catch (e) {
          console.error("Failed to restore session history", e);
      }
  };

// MainLayout import removed as it is handled in App.tsx layout wrapper

  return (
        <div className="flex h-full overflow-hidden bg-obsidian-base">
            {/* 
               h-full because parent container (MainLayout with disablePadding) is flex-1 flex-col relative.
            */}
            
            {/* Sidebar */}
            <div className="hidden md:block">
                <ChatSidebar 
                    agentId={agentId}
                    activeSessionId={sessionId}
                    onNewChat={handleNewChat}
                    onSelectSession={handleSelectSession}
                    refreshTrigger={refreshTrigger}
                />
            </div>

            {/* Main Content */}
            <div className="flex-1 flex flex-col relative w-full">

                {/* Active-agent header bar — always visible above messages so the operator
                    knows which agent is fielding the conversation. Admins get a Switch
                    Agent dropdown; non-admins see the agent identity only (they pick at
                    /chat via ChatAgentPickerPage and can't reassign mid-session). */}
                {agentConfig && (
                    <div className="flex items-center justify-between gap-3 px-4 md:px-8 py-3 border-b border-obsidian-stroke/50 bg-obsidian-base/60 backdrop-blur shrink-0">
                        <div className="flex items-center gap-3 min-w-0">
                            <div className={`shrink-0 p-2 rounded-lg ${agentConfig.isTeam ? 'bg-purple-500/10 text-purple-300' : 'bg-agent-blue/10 text-agent-blue'}`}>
                                {agentConfig.isTeam ? <LuUsers className="w-4 h-4" /> : <LuBot className="w-4 h-4" />}
                            </div>
                            <div className="min-w-0">
                                <div className="font-semibold text-sm text-theme-foreground truncate" title={agentConfig.name}>
                                    {agentConfig.name}
                                    {agentConfig.isTeam && <span className="ml-2 text-[10px] uppercase tracking-wider text-purple-300 bg-purple-500/10 border border-purple-500/30 rounded px-1.5 py-0.5">Team</span>}
                                </div>
                                {agentConfig.model && (
                                    <div className="text-xs text-(--theme-muted) truncate font-mono" title={agentConfig.model}>{agentConfig.model}</div>
                                )}
                            </div>
                        </div>

                        {isAdmin && (
                            <details
                                className="dropdown dropdown-end shrink-0"
                                open={agentSwitcherOpen}
                                onToggle={(e) => setAgentSwitcherOpen((e.target as HTMLDetailsElement).open)}
                            >
                                <summary className="btn btn-ghost btn-sm gap-1.5 normal-case">
                                    <LuArrowRightLeft className="w-3.5 h-3.5" />
                                    Switch Agent
                                </summary>
                                <ul className="dropdown-content menu bg-(--theme-card) border border-(--theme-muted)/10 rounded-lg shadow-xl z-30 w-72 p-1.5 max-h-96 overflow-y-auto">
                                    {allAgents.length === 0 && (
                                        <li className="px-3 py-2 text-xs text-(--theme-muted) italic">Loading…</li>
                                    )}
                                    {allAgents.map(a => (
                                        <li key={a.agentId}>
                                            <button
                                                onClick={() => handleSwitchAgent(a.agentId)}
                                                className="text-sm gap-2 items-start py-2"
                                            >
                                                <span className="shrink-0 pt-0.5">
                                                    {a.agentId === agentId
                                                        ? <LuCheck className="w-3.5 h-3.5 text-success" />
                                                        : (a.isTeam ? <LuUsers className="w-3.5 h-3.5 text-purple-300" /> : <LuBot className="w-3.5 h-3.5 text-agent-blue" />)}
                                                </span>
                                                <span className="min-w-0">
                                                    <span className="block font-medium truncate">{a.name}</span>
                                                    {a.model && <span className="block text-[10px] font-mono text-(--theme-muted) truncate">{a.model}</span>}
                                                </span>
                                            </button>
                                        </li>
                                    ))}
                                </ul>
                            </details>
                        )}
                    </div>
                )}

                {/* Messages Area */}
                <div className="flex-1 overflow-y-auto w-full p-4 md:p-8 scroll-smooth">
                    <div className="max-w-3xl mx-auto space-y-6 pb-4">
                        {messages.length === 0 ? (
                            <div className="flex flex-col items-center justify-center h-full min-h-100 text-center opacity-50 space-y-4">
                                <div className="w-16 h-16 bg-agent-blue/10 rounded-full flex items-center justify-center text-agent-blue">
                                    <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" x2="12" y1="19" y2="22"/></svg>
                                </div>
                                <div>
                                    <h3 className="font-semibold text-lg">
                                        {agentNotFound ? 'Agent Not Found' : agentConfig ? agentConfig.name : 'Loading Agent...'}
                                    </h3>
                                    <p className="text-sm">
                                        {agentNotFound
                                            ? `The agent "${agentId}" does not exist or has been deleted.`
                                            : agentConfig ? agentConfig.description : 'Ready to assist. How can I help you today?'}
                                    </p>
                                </div>
                            </div>
                        ) : (
                            messages.map((msg, index) => (
                                <MessageBubble 
                                    key={msg.id} 
                                    message={msg} 
                                    agentId={agentId}
                                    runId={msg.runId} 
                                    isPaused={msg.status === RunStatus.PAUSED}
                                    isGenerating={isLoading && !msg.executionTimeMs && index === messages.length - 1 && msg.role === MessageRole.ASSISTANT}
                                    onFollowUpClick={(text: string) => handleSend(text)}
                                />
                            ))
                        )}
                        {/* Invisible element for scrolling */}
                        <div ref={messagesEndRef} />
                    </div>
                </div>

                {/* Input Area */}
                <div className="shrink-0 p-4 border-t border-obsidian-stroke/50 bg-obsidian-surface/80 backdrop-blur-md">
                     <div className="max-w-3xl mx-auto">
                        <ChatInput
                            onSend={handleSend}
                            isLoading={isLoading}
                            disabled={agentNotFound}
                            onStop={handleStop}
                        />
                        <div className="flex justify-end mt-1">
                            <ChatSettings options={runOptions} onChange={setRunOptions} />
                        </div>
                     </div>
                </div>
                
            </div>
        </div>
  );
};

export default ChatPage;
