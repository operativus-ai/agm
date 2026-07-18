# Service Run Modes

The **Agent Manager** backend supports two primary run modes for development. Both run the Java application on your local machine (Desktop), but they differ in how they manage the infrastructure dependencies (PostgreSQL + PgVector).

## Mode 1: Desktop / Dev Mode (Recommended)

In this mode, you manually start the database (via Docker) and run the Spring Boot application independently. This is ideal for rapid development cycles as the DB persists across app restarts.

*   **Profile**: `dev`
*   **Infrastructure**: Manually managed (via `start-dev.sh`)
*   **App**: Runs on Host (Desktop)

### Startup
1.  **Start Infrastructure**:
    ```bash
    ./start-dev.sh
    ```
    *Starts PostgreSQL container (`agent-manager-db`) on port 5432.*

2.  **Run Application**:
    *   **VSCode**: Run "Spring Boot-AgentManager (Dev)" configuration.
    *   **Command Line**:
        ```bash
        # Set DB Credentials manually or assume local defaults (admin / !Yamaha#69Gto)
        # LLM API keys are NOT read from env — set them post-boot via the admin endpoint
        # (POST /api/v1/provider-credentials). See LLM_CONFIG.md.
        ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
        ```

---

## Mode 2: Integrated / Docker Support Mode

In this mode, Spring Boot's **Docker Compose Support** automatically starts/stops the database container when the application starts/exits.

*   **Profile**: `default` (or none)
*   **Infrastructure**: Managed automatically by Spring Boot
*   **App**: Runs on Host (Desktop)

### Startup
1.  **Stop Existing Containers**:
    Ensure port 5432 is free (run `docker stop agent-manager-db`).

2.  **Run Application**:
    *   **VSCode**: Run default configuration (without `dev` profile).
    *   **Command Line**:
        ```bash
        # LLM API keys are NOT read from env — set them post-boot via the admin endpoint
        # (POST /api/v1/provider-credentials). See LLM_CONFIG.md.
        ./mvnw spring-boot:run
        ```

### Note on "Running IN Docker"
Currently, the `docker-compose.yml` **does not** contain a service entry for the `agent-manager` application itself, only for its dependencies (`db`, `pgadmin`).
To run the *entire* stack including the backend inside Docker, you would need to:
1.  Build a Docker image for the app (`./mvnw spring-boot:build-image`).
2.  Add an `agent-manager` service to `docker-compose.yml`.
