package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.resolve.Resolver;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.validation.ActionValidator;
import com.stellarcompact.engine.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-match smoke test: drives scripted bots for several ticks through the real
 * engine {@link Resolver}, proving the bot produces only valid actions across
 * resolved ticks and that the whole loop is deterministic per seed.
 *
 * <p><b>Scope.</b> This is a minimal in-test tick loop, not the production headless
 * runner (card E3-03) nor the four-phase orchestrator (later cards). It exercises
 * exactly the E3-01 deliverable: project a {@link WorldView} per faction, ask each
 * {@link ScriptedSovereign} to {@link Sovereign#decide(WorldView)}, validate every
 * emitted action against authoritative state, fold the valid ones through
 * {@link Resolver#resolve}, advance the tick, repeat.
 */
class ScriptedMatchTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final int TICKS = 8;

    @Test
    void scriptedBotsPlayAFullHeadlessMatchWithOnlyValidActions() {
        GameState finalState = runMatch();
        // Eight ticks resolved without an exception or an invalid action reaching the
        // resolver (the per-tick assertions live inside runMatch). The match advanced.
        assertEquals(5L + TICKS, finalState.tick(), "every tick must advance the clock");
    }

    @Test
    void theMatchIsDeterministicPerSeed() {
        GameState a = runMatch();
        GameState b = runMatch();
        // Same seed + same scripted bots + same starting state => identical end state.
        assertEquals(a, b, "a scripted match must be byte-for-byte reproducible");
    }

    /**
     * Run {@link #TICKS} resolved ticks of two scripted bots. Asserts, every tick,
     * that each emitted action validates. Returns the final state.
     */
    private GameState runMatch() {
        BalanceProfile profile = SovereignFixtures.profile();
        GameState state = SovereignFixtures.richTwoFactionState();

        List<Sovereign> sovereigns = List.of(
                new ScriptedSovereign(ALPHA),
                new ScriptedSovereign(BETA));

        for (int t = 0; t < TICKS; t++) {
            List<SubmittedAction> submitted = new ArrayList<>();

            // Action phase: deterministic faction order (sorted by id) so the submitted
            // batch is stable regardless of list construction order.
            List<Sovereign> ordered = sovereigns.stream()
                    .sorted(Comparator.comparing(s -> s.factionId().value()))
                    .toList();

            for (Sovereign s : ordered) {
                FactionId actor = s.factionId();
                // E3-02: the scripted bot now consumes a real, fog-filtered WorldView
                // from the production builder (no lane graph supplied in this minimal
                // loop, so adjacency-based sensor reveal is off; the bot's heuristics
                // need only its own owned assets, which are always in full view).
                WorldView view = WorldViewBuilder.build(state, actor);
                AgentResponse response = s.decide(view);

                int submissionOrder = 0;
                for (Action action : response.actions()) {
                    ValidationResult result =
                            ActionValidator.validate(state, actor, action, profile);
                    assertTrue(result.isValid(),
                            "tick " + state.tick() + ": bot " + actor.value()
                                    + " emitted an invalid " + action.type() + ": " + result);
                    submitted.add(new SubmittedAction(actor, action, submissionOrder++));
                }
            }

            long seed = state.gameSeed() ^ state.tick();
            GameState resolved = Resolver.resolve(state, submitted, profile, seed);
            state = resolved.withTick(state.tick() + 1);
        }
        return state;
    }
}
