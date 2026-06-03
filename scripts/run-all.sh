#!/usr/bin/env bash
# Stellar Compact — one-command local boot (POSIX mirror of run-all.ps1).
#
# Requires: JDK 25 on $JAVA_HOME (preview features), Maven 3.6.3+ on PATH,
#           Node + npm, Docker with the compose plugin.
#
# URLs:  UI -> http://localhost:4200   API -> http://localhost:8080
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$REPO_ROOT/backend"
JAR="$BACKEND_DIR/app/target/app-0.1.0-SNAPSHOT.jar"

# DB connection (env-overridable; defaults match docker-compose.yml).
DB_HOST="${STELLAR_DB_HOST:-localhost}"
DB_PORT="${STELLAR_DB_PORT:-5433}"
DB_NAME="${STELLAR_DB_NAME:-stellar}"
DB_USER="${STELLAR_DB_USER:-stellar}"
DB_PASS="${STELLAR_DB_PASSWORD:-stellar}"
JDBC="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"

# --- JDK 25 check ------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  echo "ERROR: JAVA_HOME is not set; Stellar Compact needs JDK 25 (preview features)." >&2
  exit 1
fi
JAVA="$JAVA_HOME/bin/java"
if ! "$JAVA" -version 2>&1 | grep -q 'version "\?25'; then
  echo "ERROR: JAVA_HOME ($JAVA_HOME) is not a JDK 25." >&2
  exit 1
fi

# --- Postgres ----------------------------------------------------------------
echo "==> Bringing up Postgres on ${DB_HOST}:${DB_PORT} ..."
( cd "$REPO_ROOT" && docker compose up -d db )
echo "==> Waiting for db healthcheck ..."
for _ in $(seq 1 45); do
  status="$(docker inspect -f '{{.State.Health.Status}}' stellar-db 2>/dev/null || echo none)"
  [ "$status" = "healthy" ] && break
  sleep 2
done
[ "$status" = "healthy" ] || { echo "ERROR: Postgres not healthy; see 'docker compose logs db'." >&2; exit 1; }

# --- Build if missing --------------------------------------------------------
if [ ! -f "$JAR" ]; then
  echo "==> Building backend jar (mvn -DskipTests install) ..."
  ( cd "$BACKEND_DIR" && mvn -DskipTests install )
fi

# --- Backend (background) ----------------------------------------------------
echo "==> Starting backend -> http://localhost:8080"
"$JAVA" --enable-preview -jar "$JAR" \
  "--spring.datasource.url=$JDBC" \
  "--spring.datasource.username=$DB_USER" \
  "--spring.datasource.password=$DB_PASS" \
  "--spring.flyway.url=$JDBC" \
  "--spring.flyway.user=$DB_USER" \
  "--spring.flyway.password=$DB_PASS" &
BACKEND_PID=$!
trap 'kill "$BACKEND_PID" 2>/dev/null || true' EXIT

# --- Frontend (foreground) ---------------------------------------------------
echo "==> Starting frontend -> http://localhost:4200  (Ctrl+C to stop)"
cd "$REPO_ROOT/frontend"
[ -d node_modules ] || npm install
npm start
