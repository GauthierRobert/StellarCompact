---
name: backend-jdk25-build
description: How to run the Stellar Compact backend Maven build (JDK 25 is not the system default)
metadata:
  type: project
---

The backend requires **JDK 25**, installed at `C:\Users\GAR\.jdks\openjdk-25` (Temurin 25+36). The machine's *default* `java`/Maven point at JDK 21, so every Maven invocation must set `JAVA_HOME` explicitly.

**Why:** the reactor compiles with `release=25` and the `orchestrator` module uses preview `StructuredTaskScope`; JDK 21 cannot build it.

**How to apply:** from `backend/`, run e.g.
`JAVA_HOME=/c/Users/GAR/.jdks/openjdk-25 mvn clean verify`
(Bash tool) — confirmed BUILD SUCCESS for all 7 modules. `--enable-preview` is wired per-module and only `orchestrator` opts in (parent passes it via `preview.compiler.arg`/`preview.surefire.arg` props, empty by default). The JaCoCo 80% gate on engine+agent-runtime is present but `haltOnFailure=false` until E1/E4 land real code.
