# Always-Follow Rules — Stack & Conventions

- **Frontend:** Angular 21 — standalone components, signals, zoneless CD, WebGL2 for the galaxy. No NgModules. No Zone.js reliance.
- **Backend:** Spring Boot 4 on Java 25 — virtual threads, structured concurrency, records, sealed interfaces, exhaustive pattern-matching switches.
- **AI:** Spring AI 2.0.0-M8; Ollama default, pluggable.
- **Transport:** REST for config/CRUD/tiles; WebSocket/STOMP for live deltas. Heavy star tiles ALWAYS over HTTP/CDN, never the socket.
- **Persistence:** PostgreSQL for mutable game state (bounded, active systems only); object-store/CDN for tiles; procedural generation for the catalog.
- **Modules:** keep `engine` and `galaxy` framework-free and unit-testable. Spring lives in agent-runtime/orchestrator/api/persistence/app.
- **Testing:** engine resolver is test-first with golden state-hash tests; 80%+ coverage target on engine and agent-runtime.
