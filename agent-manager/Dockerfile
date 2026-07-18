# syntax=docker/dockerfile:1.7
#
# Agent Manager — production container image.
#
# Two-stage build:
#   1. builder — Eclipse Temurin 25 JDK + Maven wrapper builds the Spring Boot
#                fat jar with -DskipTests (the CI pipeline runs the test suite;
#                this image is the deployment artifact, not the test target).
#                The fat jar is then extracted via `jarmode=tools extract` into
#                a thin application jar (~2 MB) + a sibling `lib/` directory
#                (~260 MB of dependency jars). Each lands on a separate Docker
#                layer, so a code-only change rebuilds only the ~2 MB
#                application layer rather than the full ~260 MB deps layer.
#   2. runtime — Eclipse Temurin 25 JRE only. Runs as a dedicated non-root
#                user so a compromised JVM has no path to root in the container.
#
# Build:  docker build -t agent-manager:local agent-manager/
# Run:    docker run --rm -p 8080:8080 \
#           -e SPRING_PROFILES_ACTIVE=prod \
#           -e JWT_SECRET="$(openssl rand -base64 32)" \
#           -e SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/agent_manager \
#           -e SPRING_DATASOURCE_USERNAME=admin \
#           -e SPRING_DATASOURCE_PASSWORD=... \
#           -e APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://your.domain \
#           agent-manager:local
#
# The app refuses to start in the `prod` profile if APP_CORS_ALLOWED_ORIGIN_PATTERNS
# is left at its placeholder value — pinned by WebConfig.validateCorsOriginsOrFail.

# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /workspace

# Copy only the Maven wrapper + pom first so the dependency-download layer
# stays cached when source code changes. `dependency:go-offline` fetches every
# declared dependency and plugin once; subsequent `mvnw package` then runs from
# the local repo with no network hops needed for routine builds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw \
 && ./mvnw -B dependency:go-offline

# Now the volatile bit: source. A code-only edit invalidates this layer and
# everything below, but the dependency-download layer above stays in cache.
COPY src/ src/
RUN ./mvnw -B -DskipTests package

# Extract the Spring Boot fat jar into its constituents:
#   - lib/                       ~260 MB of dependency jars
#   - agm-core-*-exec.jar         ~2 MB thin application jar; its manifest
#                                  carries Main-Class + a Class-Path that
#                                  references the sibling lib/ jars, so the
#                                  thin jar is directly runnable with `java -jar`
# (Spring Boot 4's `jarmode=tools extract` replaces the 2.x `jarmode=layertools`.)
#
# Important: do NOT WORKDIR into /workspace/extracted before running extract —
# Spring Boot's ExtractCommand.checkDirectoryIsEmpty refuses to write into a
# non-empty destination, and WORKDIR creates the dir (empty initially) but any
# subsequent file drop into it would trip the check. Let extract create the
# directory itself from a clean state.
RUN java -Djarmode=tools -jar /workspace/target/agm-core-*-exec.jar \
        extract --destination /workspace/extracted \
 && mv /workspace/extracted/agm-core-*.jar /workspace/extracted/app.jar

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-noble AS runtime

# wget powers the HEALTHCHECK below. It's on the temurin noble base by default
# but install explicitly so a base-image change doesn't silently break the
# probe. ~600 KB.
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/*

# Run as a dedicated unprivileged user. A JVM compromise still has access to
# /home/agm and the JAR, but nothing outside.
RUN groupadd --system --gid 1001 agm \
 && useradd  --system --uid 1001 --gid agm --create-home --home-dir /home/agm agm
USER agm
WORKDIR /home/agm

# Copy in increasing volatility order so the cache wins on a code-only rebuild:
#   1. lib/ — changes only on pom.xml edits (~260 MB).
#   2. app.jar — changes on every source edit (~2 MB).
# The thin app.jar's manifest contains Class-Path entries pointing at lib/,
# so `java -jar app.jar` finds its dependencies relative to the working dir.
COPY --from=builder --chown=agm:agm /workspace/extracted/lib/    ./lib/
COPY --from=builder --chown=agm:agm /workspace/extracted/app.jar ./app.jar

# Spring Boot default. Override at runtime with -e SERVER_PORT=...
EXPOSE 8080

# JVM tuning:
#   -XX:+ExitOnOutOfMemoryError    fail fast on OOM so the orchestrator
#                                  restarts the container (lingering on a
#                                  half-broken JVM masks the root cause and
#                                  serves stale state).
#   -XX:MaxRAMPercentage=75.0      JDK 25 already reads cgroup limits, but pin
#                                  a percentage so 25% headroom remains for
#                                  off-heap allocations (Netty buffers,
#                                  metaspace, pgvector native I/O, virtual
#                                  thread stack pools).
ENV JAVA_OPTS="-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75.0"

# Hits the public, shallow liveness endpoint /api/v1/health (returns {"status":"UP"}).
# NOT /actuator/health: the prod profile drops /actuator/** from app.security.public-paths
# (application-prod.properties), so an unauthenticated probe there gets 401 and the container
# would never go healthy. /actuator/health is also a DEEP check that 503s when any non-critical
# component (e.g. mail) is down — wrong for a liveness probe. The 60s start-period covers
# Liquibase migrations + initial DB pool warmup on a cold boot.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/api/v1/health || exit 1

# Exec form (no shell wrapper between PID 1 and the JVM) so the JVM receives
# SIGTERM directly for graceful shutdown. The intermediate `sh -c` is still
# needed to expand $JAVA_OPTS — `exec java ...` then replaces the shell so
# PID 1 is the JVM at runtime.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar \"$@\"", "--"]
