package com.stellarcompact.agentruntime.prompt;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.ResourceBundle;

import java.util.List;
import java.util.Optional;

/**
 * A small, fixed, token-compact WorldView stand-in for the prompt-assembly tests.
 *
 * <p>agent-runtime does not depend on the orchestrator (where the production
 * {@code WorldView} record lives), so the assembler takes the view as an opaque
 * serialisable {@code Object} at the module boundary. This fixture mirrors that
 * contract: it is a deeply-immutable, JSON-serialisable record graph built from
 * engine value types (so it exercises real {@code FactionId}/{@code ResourceBundle}
 * serialisation and {@code Optional<>} handling) — exactly what the orchestrator will
 * hand in. It is intentionally minimal and deterministic so the golden snapshot is
 * stable.
 */
record TestWorldView(
        long tick,
        Self self,
        List<Neighbour> neighbours
) {

    record Self(
            FactionId id,
            String name,
            double reputation,
            ResourceBundle stockpiles,
            List<String> techKnown
    ) {
    }

    record Neighbour(
            String systemId,
            Optional<FactionId> owner,
            int roughStrength,
            long lastSeenTick
    ) {
    }

    /** The single fixed fixture used by the golden test. */
    static TestWorldView fixture() {
        return new TestWorldView(
                42L,
                new Self(
                        FactionId.of("faction-1"),
                        "Mercantile Republic",
                        12.5,
                        new ResourceBundle(100, 50, 30, 10, 5),
                        List.of("tech-mining-1", "tech-shipyards-1")),
                List.of(
                        new Neighbour("system-9", Optional.of(FactionId.of("faction-2")), 3, 41L),
                        new Neighbour("system-12", Optional.empty(), 0, 40L)));
    }
}