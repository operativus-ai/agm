import { useState } from 'react';
import { authApi } from './api/agm';
import { getToken } from './api/client';
import { LoginPage } from './pages/LoginPage';
import { AgentPickerPage } from './pages/AgentPickerPage';
import { ChatPage } from './pages/ChatPage';
import type { AgentSummary } from './types';

// Deliberately router-less: the demo's point is the AGM API contract
// (auth → discover → stream/HITL), not client-side routing.
export function App() {
  const [authed, setAuthed] = useState<boolean>(() => getToken() !== null);
  const [agent, setAgent] = useState<AgentSummary | null>(null);

  if (!authed) return <LoginPage onLogin={() => setAuthed(true)} />;

  return (
    <div className="app">
      <nav className="topbar">
        <span className="brand">AGM Demo Client</span>
        <button
          className="link"
          onClick={() => {
            authApi.logout();
            setAgent(null);
            setAuthed(false);
          }}
        >
          Sign out
        </button>
      </nav>
      {agent ? <ChatPage agent={agent} onBack={() => setAgent(null)} /> : <AgentPickerPage onPick={setAgent} />}
    </div>
  );
}
