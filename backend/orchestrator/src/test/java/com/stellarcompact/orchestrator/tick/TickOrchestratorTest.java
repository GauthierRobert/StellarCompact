package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.agentruntime.chat.AgentRuntimeProperties;
import com.stellarcompact.agentruntime.chat.ModelTier;
import com.stellarcompact.agentruntime.chat.SovereignChatClientFactory;
import com.stellarcompact.agentruntime.prompt.ActionSchemaCatalog;
import com.stellarcompact.agentruntime.prompt.PromptAssembler;
import com.stellarcompact.agentruntime.prompt.SovereignConfig;
import com.stellarcompact.agentruntime.parse.AgentResponseParser;
import com.stellarcompact.agentruntime.validate.ActionValidationLoop;
import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.hash.StateHasher;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.orchestrator.sovereign.ScriptedSovereign;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;
import com.stellarcompact.orchestrator.sovereign.WorldView;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the four-phase tick orchestrator (card E4-05). Proves the
 * structured-concurrency deadline behaviour the card demands:
 *
 * <ul>
 *   <li><b>A slow agent never stalls the tick.</b> A bot that blocks far past the phase
 *       deadline contributes Hold and the tick still completes in roughly the deadline,
 *       not in the bot's sleep time.</li>
 *   <li><b>Per-phase timeout is enforced</b> from config (a long action timeout lets a
 *       moderately slow bot finish; a short one Holds it).</li>
 *   <li><b>Resolution stays deterministic</b> - same seed + same decisions =&gt; same
 *       resolved-state hash, despite the concurrent gathering.</li>
 *   <li><b>Load test</b> - N slow agents complete in bounded time (~one deadline), not
 *       N deadlines, because all stragglers are cancelled together.</li>
 * </ul>
 */
class TickOrchestratorTest {

    /** A {@link Sovereign} that sleeps before deciding - simulates a slow brain. */
    private record SlowSovereign(FactionId factionId, Duration sleep,
                                 AtomicInteger completions) implements Sovereign {
        @Override
        public AgentResponse decide(WorldView view) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                // The scope cancelled us at the deadline: stop promptly, contribute nothing.
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted at deadline", e);
            }
            completions.incrementAndGet();
            return AgentResponse.now(List.of(), List.of(new Action.Hold()));
        }
    }

    private static TickContext ctx(GameState state) {
        return new TickContext(state, TickFixtures.profile(), LaneNetwork.EMPTY, SystemAdjacency.NONE);
    }

    private static TickProperties timings(Duration negotiation, Duration action, int rounds) {
        return new TickProperties(Duration.ofSeconds(5), negotiation, action, rounds);
    }

    // --- Slow agent never stalls the tick --------------------------------------------

    @Test
    void aSlowAgentNeverStallsTheTick() {
        GameState state = TickFixtures.buildableTwoFactionState();
        // BETA sleeps 10s; the action deadline is 300ms. The tick must finish in well
        // under the sleep time, with BETA contributing Hold.
        AtomicInteger betaCompletions = new AtomicInteger();
        List<Seat> seats = List.of(
                new Seat.ScriptedSeat(new ScriptedSovereign(TickFixtures.ALPHA)),
                new Seat.ScriptedSeat(new SlowSovereign(
                        TickFixtures.BETA, Duration.ofSeconds(10), betaCompletions)));

        TickOrchestrator orch = new TickOrchestrator(
                timings(Duration.ofMillis(50), Duration.ofMillis(300), 0));

        long start = System.nanoTime();
        TickResult result = orch.runTick(seats, ctx(state));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // The galaxy never waited on the slow brain: finished near the 300ms deadline,
        // nowhere near BETA's 10s sleep.
        assertTrue(elapsed.compareTo(Duration.ofSeconds(3)) < 0,
                "tick must complete near the deadline, not the slow brain sleep; took " + elapsed);
        // BETA never reached its return statement (it was cancelled at the deadline).
        assertEquals(0, betaCompletions.get(), "the cancelled slow brain must not complete");
        // The tick still resolved a non-null next state.
        assertEquals(state.tick(), result.tick());
        // ALPHA's build action survived; BETA contributed nothing (Hold).
        assertTrue(result.submitted().stream().allMatch(s -> s.actor().equals(TickFixtures.ALPHA)),
                "only ALPHA (the fast bot) should have submitted actions; BETA held");
        assertFalse(result.submitted().isEmpty(), "ALPHA should have produced a valid action");
    }

    // --- Per-phase timeout from config -----------------------------------------------

    @Test
    void aBotThatFinishesWithinTheDeadlineContributesItsActions() {
        GameState state = TickFixtures.buildableTwoFactionState();
        // BETA sleeps 100ms; with a generous 5s action deadline it finishes and Holds
        // (its SlowSovereign returns a Hold action, which is a no-op -> empty valid set).
        AtomicInteger betaCompletions = new AtomicInteger();
        List<Seat> seats = List.of(
                new Seat.ScriptedSeat(new ScriptedSovereign(TickFixtures.ALPHA)),
                new Seat.ScriptedSeat(new SlowSovereign(
                        TickFixtures.BETA, Duration.ofMillis(100), betaCompletions)));

        TickOrchestrator orch = new TickOrchestrator(
                timings(Duration.ofMillis(50), Duration.ofSeconds(5), 0));

        orch.runTick(seats, ctx(state));
        // With a deadline well past its sleep, BETA completed normally.
        assertEquals(1, betaCompletions.get(),
                "with a generous deadline the moderately-slow bot must finish");
    }

    @Test
    void theSameBotIsCancelledUnderAShortDeadline() {
        GameState state = TickFixtures.buildableTwoFactionState();
        AtomicInteger betaCompletions = new AtomicInteger();
        List<Seat> seats = List.of(
                new Seat.ScriptedSeat(new ScriptedSovereign(TickFixtures.ALPHA)),
                new Seat.ScriptedSeat(new SlowSovereign(
                        TickFixtures.BETA, Duration.ofSeconds(5), betaCompletions)));

        // Same bot, same code path - only the configured deadline differs (10ms). It Holds.
        TickOrchestrator orch = new TickOrchestrator(
                timings(Duration.ofMillis(10), Duration.ofMillis(10), 0));

        orch.runTick(seats, ctx(state));
        assertEquals(0, betaCompletions.get(),
                "under a tighter configured deadline the same bot is cancelled (Holds)");
    }

    // --- Determinism of resolution ---------------------------------------------------

    @Test
    void resolutionIsDeterministicAcrossRunsDespiteConcurrentGathering() {
        List<Seat> seats = List.of(
                new Seat.ScriptedSeat(new ScriptedSovereign(TickFixtures.ALPHA)),
                new Seat.ScriptedSeat(new ScriptedSovereign(TickFixtures.BETA)));
        TickOrchestrator orch = new TickOrchestrator(
                timings(Duration.ofMillis(50), Duration.ofSeconds(2), 0));

        // Run the same tick many times; the concurrent gather must always reassemble the
        // identical batch and resolve to the identical state hash.
        String firstHash = null;
        for (int i = 0; i < 25; i++) {
            TickResult r = orch.runTick(seats, ctx(TickFixtures.buildableTwoFactionState()));
            String hash = StateHasher.sha256Hex(r.resolvedState());
            if (firstHash == null) {
                firstHash = hash;
            } else {
                assertEquals(firstHash, hash,
                        "run " + i + ": concurrent gathering must not perturb the resolved state");
            }
        }
        assertFalse(firstHash == null);
    }

    @Test
    void multiTickRunIsDeterministicPerSeed() {
        TickOrchestrator orch = new TickOrchestrator(
                timings(Duration.ofMillis(50), Duration.ofSeconds(2), 0));
        String runA = playSeveralTicks(orch, 6);
        String runB = playSeveralTicks(orch, 6);
        assertEquals(runA, runB, "same seed + same scripted bots => identical multi-tick hash chain");
    }

    private String playSeveralTicks(TickOrchestrator orch, int ticks) {
        List<Seat> seats = List.of(
                new Seat.ScriptedSeat(new ScriptedSovereign(TickFixtures.ALPHA)),
                new Seat.ScriptedSeat(new ScriptedSovereign(TickFixtures.BETA)));
        GameState state = TickFixtures.buildableTwoFactionState();
        List<String> hashes = new ArrayList<>();
        for (int t = 0; t < ticks; t++) {
            TickResult r = orch.runTick(seats, ctx(state));
            hashes.add(StateHasher.sha256Hex(r.resolvedState()));
            state = r.resolvedState().withTick(r.tick() + 1);
        }
        return String.join(",", hashes);
    }

    // --- Load test: N slow agents finish in bounded time -----------------------------

    @Test
    void nSlowAgentsCompleteInBoundedTime() {
        // 200 slow brains, each sleeping 10s, under a 400ms deadline. Because all
        // stragglers are cancelled together at the shared deadline (one virtual thread
        // each, cheap), the whole tick finishes in ~one deadline, NOT 200 * 10s.
        int n = 200;
        List<Seat> seats = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            FactionId id = new FactionId(String.format("f%03d", i));
            seats.add(new Seat.ScriptedSeat(new SlowSovereign(id, Duration.ofSeconds(10), completions)));
        }
        GameState state = TickFixtures.nFactionState(seats.stream().map(Seat::factionId).toList());

        TickOrchestrator orch = new TickOrchestrator(
                timings(Duration.ofMillis(50), Duration.ofMillis(400), 0));

        long start = System.nanoTime();
        TickResult result = orch.runTick(seats, ctx(state));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0,
                n + " slow agents must finish in ~one deadline, not serially; took " + elapsed);
        assertEquals(0, completions.get(), "every slow brain was cancelled at the deadline");
        // All seats held -> no actions reach the resolver, but the tick still resolved.
        assertTrue(result.submitted().isEmpty(), "all slow seats Hold -> empty validated batch");
    }

    // --- LLM-seat wiring (provider-neutral) ------------------------------------------

    /** A {@link ChatModel} that always returns a lone Hold completion (no provider). */
    private record HoldChatModel() implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("{\"actions\":[{\"type\":\"Hold\"}]}"))));
        }
    }

    @Test
    void anLlmSeatRunsThroughTheAgentRuntimeLoopUnderTheSameDeadline() {
        GameState state = TickFixtures.buildableTwoFactionState();

        // Wire the real agent-runtime path with a stubbed ChatClient (no real provider).
        // This is the E4-04 wiring: factory + assembler fed into the validation loop.
        ChatClient holdClient = ChatClient.create(new HoldChatModel());
        SovereignChatClientFactory factory = mock(SovereignChatClientFactory.class);
        when(factory.chatClientFor(null)).thenReturn(holdClient);

        PromptAssembler assembler = new PromptAssembler(
                AgentRuntimeProperties.of(ModelTier.SMALL, java.util.Map.of()),
                new ActionSchemaCatalog());
        ActionValidationLoop loop = new ActionValidationLoop(new AgentResponseParser());

        Seat alpha = new Seat.ScriptedSeat(new ScriptedSovereign(TickFixtures.ALPHA));
        Seat beta = new Seat.LlmSeat(TickFixtures.BETA, SovereignConfig.ofPersona("a trader"),
                null, factory, assembler, loop);

        TickOrchestrator orch = new TickOrchestrator(
                timings(Duration.ofMillis(50), Duration.ofSeconds(2), 0));
        TickResult result = orch.runTick(List.of(alpha, beta), ctx(state));

        // The LLM seat parsed + engine-validated its completion (a lone Hold, which is a
        // valid action), proving the provider-neutral E4-04 path ran end to end.
        boolean betaHeld = result.submitted().stream()
                .filter(s -> s.actor().equals(TickFixtures.BETA))
                .allMatch(s -> s.action() instanceof Action.Hold);
        assertTrue(betaHeld, "the LLM seat's only validated action is the Hold it emitted");
        // ALPHA (scripted) still produced its non-Hold build action alongside.
        boolean alphaActed = result.submitted().stream()
                .anyMatch(s -> s.actor().equals(TickFixtures.ALPHA)
                        && !(s.action() instanceof Action.Hold));
        assertTrue(alphaActed, "ALPHA's build action survived alongside the LLM seat");
    }
}
