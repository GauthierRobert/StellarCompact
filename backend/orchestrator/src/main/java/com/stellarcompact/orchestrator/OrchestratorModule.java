package com.stellarcompact.orchestrator;

/**
 * Placeholder for the tick-scheduler module. Real scheduler (four phases,
 * virtual threads, structured concurrency with per-phase deadlines) arrives in
 * later cards. Structured concurrency is a Java 25 preview API; the reactor
 * enables {@code --enable-preview} accordingly.
 */
public final class OrchestratorModule {

    private OrchestratorModule() {
    }

    public static String name() {
        return "orchestrator";
    }
}
