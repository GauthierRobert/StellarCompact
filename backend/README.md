# backend/ — Spring Boot 4 / Java 25

Maven multi-module reactor. Modules: `engine` (pure, deterministic, framework-free),
`galaxy` (lib), `agent-runtime` (Spring AI), `orchestrator`, `api`, `persistence`,
`app` (the bootable Spring Boot entry point). The engine stays framework-free and
deterministic; read `.claude/skills/game-engine-determinism`, `agent-sovereign`,
`spring-ai-agent` and `docs/architecture/` before changing those areas.

## Build & run

The backend targets **Java 25 with preview features**, so it must run with
`--enable-preview` and `JAVA_HOME` must point at a JDK 25.

```powershell
# from repo root — handles DB + build + --enable-preview run for you
./scripts/run-backend.ps1
```

Or by hand:

```powershell
cd backend
mvn -DskipTests install          # produces app/target/app-0.1.0-SNAPSHOT.jar
java --enable-preview -jar app/target/app-0.1.0-SNAPSHOT.jar `
  --spring.datasource.url=jdbc:postgresql://localhost:5433/stellar `
  --spring.datasource.username=stellar --spring.datasource.password=stellar `
  --spring.flyway.url=jdbc:postgresql://localhost:5433/stellar `
  --spring.flyway.user=stellar --spring.flyway.password=stellar
```

PostgreSQL (host port **5433**) comes from the repo-root `docker-compose.yml`
(`docker compose up -d db`). Flyway migrations live in
`persistence/src/main/resources/db/migration`. See the root `README.md`
"Run locally" section for the full one-command path.

API runs on http://localhost:8080 — `POST /api/games`, `/api/games/{id}/start`,
`/state`, `/events`, `/leaderboard`.
