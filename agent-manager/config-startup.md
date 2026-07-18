# Backend Configuration & Startup Guide

This document outlines how to configure and start the **Agent Manager** backend service.

## 📋 Prerequisites

*   **Java**: JDK 21+
*   **Database**: PostgreSQL 16+ with `pgvector` extension.
*   **Build Tool**: Maven (wrapper included `./mvnw`).
*   **AI Service**: OpenAI API Key.

---

## ⚙️ Configuration

The application is configured via `src/main/resources/application.properties`.
However, because the `docker-compose.yml` uses custom credentials, you **MUST** provide the matching credentials to the application, either by updating properties or setting environment variables.

### 1. Database Credentials
The local Docker database is configured with the following credentials (defined in `docker-compose.yml`):

*   **Username**: `admin`
*   **Password**: `!Yamaha#69Gto`
*   **Database**: `agent_manager`
*   **Port**: `5432`

### 2. Required Environment Variables

You must set these environment variables before starting the Java application so it can reach Postgres. **LLM API keys are NOT read from the environment** — configure them post-boot via `POST /api/v1/provider-credentials`. See [`LLM_CONFIG.md`](LLM_CONFIG.md).

| Variable | Value (Local Dev) | Description |
| :--- | :--- | :--- |
| `SPRING_DATASOURCE_USERNAME` | `admin` | Matches `POSTGRES_USER` |
| `SPRING_DATASOURCE_PASSWORD` | `!Yamaha#69Gto` | Matches `POSTGRES_PASSWORD` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/agent_manager` | Matches `POSTGRES_DB` |

---

## 🚀 Startup Instructions

### Step 1: Start Infrastructure (DB + Frontend)
Run the helper script from the **root of the repository** to launch the PostgreSQL container and the Frontend dev server.
**Note:** This script does *not* start the Java backend application itself, but it does start the required containers (DB) and the UI.

```bash
./start-dev.sh
```

### Step 2: Start the Backend (Agent Manager)

You can run the backend using your IDE or the command line.

#### Option A: VSCode (Recommended)
1.  Open the **Run and Debug** view in VSCode.
2.  Select **"Spring Boot-AgentManager (Dev)"**.
3.  **Critical**: You must ensure the `env` in `.vscode/launch.json` includes the DB credentials, OR you have set them in your system environment.
    *   *Tip*: It is safer to create a local `.env` file or update `launch.json` locally (gitignored) rather than committing secrets.

#### Option B: Comand Line (Maven)
Run the application using the Maven wrapper, passing all necessary arguments:

```bash
export SPRING_DATASOURCE_USERNAME=admin
export SPRING_DATASOURCE_PASSWORD='!Yamaha#69Gto'
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agent_manager

./mvnw spring-boot:run
```

---

## 🔍 Troubleshooting

**Authenticaiton Failed / Connection Refused**
*   **Cause**: The Java app is trying to use default `postgres`/`postgres` credentials while the Docker container expects `admin`.
*   **Fix**: Verify `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` match variables in `docker-compose.yml`.

**BeanDefinitionOverrideException**
*   **Cause**: Conflict between manual bean configuration and Spring AI auto-configuration.
*   **Fix**: This was resolved in the Jan 31 patch. Ensure you are on the latest commit.
