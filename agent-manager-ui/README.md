# Agent Manager UI (Frontend)

> The **Agentic Control Plane**. A modern, reactive administrative interface for orchestrating AI Agents, managing knowledge, and observing execution in real-time.

[![React 19](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![Vite](https://img.shields.io/badge/Vite-6.0-646CFF?logo=vite&logoColor=white)](https://vitejs.dev/)
[![Tailwind 4](https://img.shields.io/badge/Tailwind-4.0-38B2AC?logo=tailwindcss&logoColor=white)](https://tailwindcss.com/)
[![DaisyUI 5](https://img.shields.io/badge/DaisyUI-5.0-5A0EF8?logo=daisyui&logoColor=white)](https://daisyui.com/)

---

## 📖 Overview

The **Agent Manager UI** is a Single Page Application (SPA) designed to provide a "Mission Control" experience for the Procurator backend. It moves beyond simple chat interfaces to offers deep inspection of agent internal states, memory management, and configuration.

**Key Design Principles:**
-   **Feature-First Architecture**: Code is organized by domain (`chat`, `agents`, `knowledge`) rather than technical type.
-   **Real-Time**: Heavy use of **Server-Sent Events (SSE)** for streaming agent thoughts and token generation.
-   **Type Safety**: Shared types strictly mapped to Backend DTOs.

---

## ✨ Features

### 1. 💬 Advanced Chat Interface
-   **Streaming Thoughts**: View the agent's internal reasoning ("thinking") process in real-time, separate from the final answer.
-   **Rich Markdown**: Rendering of tables, code blocks (with syntax highlighting), and lists.
-   **Multimodal Support**: Drag-and-drop image uploads for Vision-capable agents.
-   **HITL Controls**: Visual indicators when an agent is **PAUSED**, offering "Approve" and "Reject" actions to resume execution.

### 2. 🤖 Agent Management
-   **Registry Browser**: View all available agents (`procurator_assistant`, `finance_agent`, etc.).
-   **Configuration**: Inspect agent tools, models, and system prompts.
-   **Knowledge Loader**: Trigger URL scraping or file ingestion directly from the Agent detail view.

### 3. 🧠 Knowledge Base (RAG)
-   **Upload Center**: Drag-and-drop PDF/TXT files for ingestion.
-   **Vector Inspector**: View ingested documents and their status.
-   **Semantic Search**: Test retrieval performance by running queries against the vector store.

### 4. ⚙️ Session & Memory
-   **Session History**: Browse past conversations, filtered by Agent or Date.
-   **Memory Inspector**: View and delete long-term user memories stored in `pgvector`.

---

## 🏗 Architecture

The project follows a **Feature-Sliced Design** to ensure scalability:

```bash
src/
├── features/               # 📦 Domain Modules
│   ├── agents/             # Agent Registry & Config
│   ├── chat/               # Chat UI, Input, Message Bubble
│   ├── dashboard/          # Home / Stats
│   ├── knowledge/          # RAG Management
│   └── settings/           # App Configuration
├── shared/                 # 🧱 Shared Kernel
│   ├── api/                # Axios/Fetch Clients
│   ├── components/         # Atomic UI (Buttons, Cards - DaisyUI)
│   ├── hooks/              # Global Hooks (useTheme)
│   └── types/              # TS Interfaces (Agent, Session, Message)
├── App.tsx                 # Router & Layout
└── main.tsx                # Entry Point
```

### State Management
-   **Server State**: `React Query` (TanStack Query) handles caching, polling, and synchronization with the backend.
-   **UI State**: `Zustand` manages global client-only state (like Theme preferences or Sidebar toggle).

---

## 🚀 Getting Started

### Prerequisites
-   **Node.js 20+**
-   **Backend Running**: Ensure `agent-manager` is running on `http://localhost:8080`.

### Installation

1.  **Install Dependencies**:
    ```bash
    npm install
    ```

2.  **Start Development Server**:
    ```bash
    npm run dev
    ```

3.  **Open Browser**:
    Navigate to `http://localhost:5173`.

### Configuration
The app expects the backend at `http://localhost:8080/api`.
To change this (e.g., for Docker), update the proxy config in `vite.config.ts`.

---

## 🔌 API Integration

The frontend uses a typed `apiClient` pattern located in `src/shared/api`.

**Example: Chat Stream**
```typescript
// features/chat/api/chat-api.ts
export const streamChat = async (request: ChatRequest, onToken: (t: string) => void) => {
    const eventSource = new EventSourcePolyfill(`${BASE_URL}/agent/stream`, {
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
    });
    
    eventSource.onmessage = (e) => {
        const data = JSON.parse(e.data);
        onToken(data.content);
    };
};
```

---

## 🧪 Development Guidelines

-   **Styling**: Use `Tailwind` utility classes. Component logic should be separated from layout where possible.
-   **Components**: Prefer functional components with named exports.
-   **Icons**: Use `react-icons` (e.g., `FaRobot`, `IoMdSend`).

---
*Built with ❤️ for Procurator*
