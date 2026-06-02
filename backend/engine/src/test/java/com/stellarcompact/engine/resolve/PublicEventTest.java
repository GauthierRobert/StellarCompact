package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AttackTarget;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.FleetStance;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteKind;
import com.stellarcompact.engine.state.RouteStatus;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1-16 public-event emission tests. Drives a hand-authored, multi-step scenario
 * (war declaration + treaty sign/break + a system assault + a route raid + a route
 * establishment) through the full {@link Resolver#resolveResult} and asserts the
 * galaxy-wide {@link PublicEvent} stream: spec-matching payloads (parties / systemId /
 * tick), deterministic resolution-step ordering, append-only, and reproducible per
 * seed. Every payload field is checked against the websocket-protocol spec
 * ({@code { type, parties[], systemId?, tick }}).
 */
class PublicEventTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final FactionId GAMMA = new FactionId("gamma");
    private static final SystemId TARGET = new SystemId("target-sys");
    private static final SystemId ENDPOINT = new SystemId("endpoint-sys");
    private static final FleetId STRIKE = new FleetId("alpha-strike");
    private static final RouteId BETAS_ROUTE = new RouteId("betas-route");
    private static final TreatyId PACT = new TreatyId("alpha-beta-alliance");

    private static final long TICK = 9L;
    private static final long SEED = 123L;

    private static final BalanceProfile SMALL = loadSmallDefault();

    /**
     * The shipped {@code small-default} profile but with the E1-15 lifecycle switches OFF
     * (no victory condition, no elimination). These two isolation tests assert the E1-16
     * <em>kinetic</em>-event set/ordering on a quiet or single-tick scenario; the
     * {@code scenarioState()} incidentally has a faction with no systems and ends with a
     * dominating capture, which - under the shipped switches-on profile - would also emit
     * the (correct) E1-15 {@code FactionEliminated}/{@code VictoryAchieved} lifecycle
     * events. Turning the switches off here keeps these tests focused on E1-16 alone; the
     * E1-15 events have their own coverage in {@code VictoryEvaluationTest}.
     */
    private static final BalanceProfile SMALL_NO_LIFECYCLE = withLifecycleOff(SMALL);

    private static BalanceProfile withLifecycleOff(BalanceProfile base) {
        BalanceProfile.Victory v = base.victory();
        BalanceProfile.Victory off = new BalanceProfile.Victory(
                v.active(), false, false,
                v.domination(), v.economic(), v.diplomatic(), v.survival(), v.wonder(),
                v.scoreWeights());
        return new BalanceProfile(
                base.name(), base.version(), base.resources(), base.population(), base.market(),
                base.construction(), base.combat(), base.movement(), base.tech(), base.diplomacy(),
                off, base.tick(), base.homePlacement(), base.espionage(), base.influence());
    }

    private static BalanceProfile loadSmallDefault() {
        try (InputStream in = PublicEventTest.class.getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- fixture --------------------------------------------------------------

    private static Faction faction(FactionId id) {
        return new Faction(id, "F-" + id.value(), 0.0,
                new ResourceBundle(1000, 1000, 1000, 1000, 1000), Map.of());
    }

    /**
     * ALPHA owns a strike fleet of corvettes sitting at TARGET; BETA owns TARGET (no
     * garrison fleet there, so the assault is undefended -&gt; ALPHA always wins and
     * captures it) and a route GAMMA can raid; an ACTIVE alliance treaty between ALPHA
     * and BETA exists (so BreakTreaty has something to break).
     */
    private static GameState scenarioState() {
        ActiveSystem target = new ActiveSystem(TARGET, "Target", new Coords(2, 2),
                Optional.of(BETA), List.of(), 0, 1.0);
        ActiveSystem endpoint = new ActiveSystem(ENDPOINT, "Endpoint", new Coords(3, 3),
                Optional.of(ALPHA), List.of(), 0, 1.0);
        Fleet strike = new Fleet(STRIKE, ALPHA, Optional.of(TARGET), Optional.empty(),
                FleetStance.AGGRESSIVE, List.of(new Ship("corvette", 5)));
        Route betasRoute = new Route(BETAS_ROUTE, BETA, TARGET, ENDPOINT, RouteKind.COMMERCIAL,
                List.of(PhysicalResource.MINERALS), 100.0, RouteStatus.ACTIVE);
        Treaty alliance = new Treaty(PACT, TreatyType.ALLIANCE, List.of(ALPHA, BETA),
                Map.of(), 1L, Long.MAX_VALUE, TreatyStatus.ACTIVE);
        return new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA), BETA, faction(BETA), GAMMA, faction(GAMMA)),
                Map.of(TARGET, target, ENDPOINT, endpoint),
                Map.of(STRIKE, strike),
                Map.of(PACT, alliance),
                Map.of(BETAS_ROUTE, betasRoute),
                Map.of(), Set.of());
    }

    /**
     * The action batch. The resolver imposes its own canonical (step, actor,
     * submissionOrder) order, so the listed submission order is only the within-faction
     * tie-break. This batch exercises WarDeclared, TreatyBroken, BattleResolved,
     * SystemCaptured, RouteRaided and RouteEstablished in one tick; TreatySigned /
     * AllianceFormed are exercised separately (they need a PROPOSED treaty to accept).
     */
    private static List<SubmittedAction> batch() {
        List<SubmittedAction> b = new ArrayList<>();
        b.add(new SubmittedAction(ALPHA, new Action.DeclareWar(BETA), 0));
        b.add(new SubmittedAction(ALPHA, new Action.BreakTreaty(PACT), 1));
        b.add(new SubmittedAction(ALPHA,
                new Action.Attack(STRIKE, new AttackTarget.OnSystem(TARGET)), 2));
        b.add(new SubmittedAction(ALPHA,
                new Action.EstablishRoute(ENDPOINT, TARGET, RouteKind.COMMERCIAL,
                        List.of(PhysicalResource.MINERALS), 50.0), 3));
        b.add(new SubmittedAction(GAMMA, new Action.Raid(new FleetId("gamma-raider"), BETAS_ROUTE), 0));
        return b;
    }

    // ---- tests ----------------------------------------------------------------

    @Test
    void emitsTheExpectedEventsWithSpecMatchingPayloads() {
        ResolveResult result = Resolver.resolveResult(scenarioState(), batch(), SMALL, SEED);
        List<PublicEvent> events = result.events();

        // Every event carries this tick (ordered by tick - all events of one tick share it).
        for (PublicEvent e : events) {
            assertEquals(TICK, e.tick(), "every event is stamped with the resolution tick");
        }

        // WarDeclared: parties = [declarer, target], no system.
        PublicEvent.WarDeclared war = single(events, PublicEvent.WarDeclared.class);
        assertEquals(List.of(ALPHA, BETA), war.parties());
        assertEquals(Optional.empty(), war.systemId());
        assertEquals("WarDeclared", war.type());

        // TreatyBroken: parties = the treaty's signatories.
        PublicEvent.TreatyBroken broken = single(events, PublicEvent.TreatyBroken.class);
        assertEquals(List.of(ALPHA, BETA), broken.parties());
        assertEquals(Optional.empty(), broken.systemId());

        // BattleResolved: parties = [attacker, defender=system owner], system set.
        PublicEvent.BattleResolved battle = single(events, PublicEvent.BattleResolved.class);
        assertEquals(List.of(ALPHA, BETA), battle.parties());
        assertEquals(Optional.of(TARGET), battle.systemId());

        // SystemCaptured: undefended assault -> ALPHA wins and captures TARGET.
        PublicEvent.SystemCaptured captured = single(events, PublicEvent.SystemCaptured.class);
        assertEquals(List.of(ALPHA), captured.parties());
        assertEquals(Optional.of(TARGET), captured.systemId());
        assertEquals(ALPHA, result.state().systems().get(TARGET).owner().orElseThrow(),
                "the public event matches the actual capture in state");

        // RouteRaided: parties = [raider, victim/route owner].
        PublicEvent.RouteRaided raided = single(events, PublicEvent.RouteRaided.class);
        assertEquals(List.of(GAMMA, BETA), raided.parties());
        assertEquals(Optional.empty(), raided.systemId());

        // RouteEstablished: parties = [owner], endpoint system set.
        PublicEvent.RouteEstablished established = single(events, PublicEvent.RouteEstablished.class);
        assertEquals(List.of(ALPHA), established.parties());
        assertEquals(Optional.of(ENDPOINT), established.systemId());
    }

    @Test
    void acceptingAnAllianceEmitsBothTreatySignedAndAllianceFormed() {
        // A PROPOSED alliance + an AcceptTreaty in the same tick yields the two distinct
        // WS event kinds the spec lists (TreatySigned and AllianceFormed).
        TreatyId proposedId = new TreatyId("proposed-alliance");
        Treaty proposed = new Treaty(proposedId, TreatyType.ALLIANCE, List.of(ALPHA, GAMMA),
                Map.of(), TICK, Long.MAX_VALUE, TreatyStatus.PROPOSED);
        GameState state = scenarioState().withTreaty(proposed);

        List<SubmittedAction> b = List.of(
                new SubmittedAction(GAMMA, new Action.AcceptTreaty(proposedId), 0));
        ResolveResult result = Resolver.resolveResult(state, b, SMALL, SEED);

        PublicEvent.TreatySigned signed = single(result.events(), PublicEvent.TreatySigned.class);
        assertEquals(List.of(ALPHA, GAMMA), signed.parties());
        PublicEvent.AllianceFormed alliance = single(result.events(), PublicEvent.AllianceFormed.class);
        assertEquals(List.of(ALPHA, GAMMA), alliance.parties());
    }

    @Test
    void eventsAreOrderedByResolutionStepAndAreAppendOnly() {
        List<PublicEvent> events =
                Resolver.resolveResult(scenarioState(), batch(), SMALL_NO_LIFECYCLE, SEED).events();
        // The fixed resolution order: DIPLOMATIC_STATE (war, broken) -> COMBAT
        // (battle, captured) -> INTERDICTION (raided) -> MARKET (established).
        List<String> types = events.stream().map(PublicEvent::type).toList();
        assertEquals(
                List.of("WarDeclared", "TreatyBroken", "BattleResolved", "SystemCaptured",
                        "RouteRaided", "RouteEstablished"),
                types,
                "events are emitted in the fixed resolution-step order, append-only");
    }

    @Test
    void eventStreamIsDeterministicPerSeed() {
        List<PublicEvent> a = Resolver.resolveResult(scenarioState(), batch(), SMALL, SEED).events();
        List<PublicEvent> b = Resolver.resolveResult(scenarioState(), batch(), SMALL, SEED).events();
        assertEquals(a, b, "same seed + same ordered actions -> identical public-event stream");
    }

    @Test
    void bareResolveStillReturnsStateAndEventsDoNotPerturbIt() {
        // The legacy GameState-returning resolve must equal the ResolveResult's state:
        // events are transient output, never part of the (hashed) snapshot.
        GameState bare = Resolver.resolve(scenarioState(), batch(), SMALL, SEED);
        ResolveResult result = Resolver.resolveResult(scenarioState(), batch(), SMALL, SEED);
        assertEquals(bare, result.state(),
                "resolve(...) and resolveResult(...).state() are the same next snapshot");
        assertFalse(result.events().isEmpty(), "the scenario emitted events");
    }

    @Test
    void aQuietTickEmitsNoEvents() {
        // No actions -> the passive steps run but emit nothing (lifecycle switches off, so
        // the E1-15 evaluation stays inert here; see SMALL_NO_LIFECYCLE).
        List<PublicEvent> events =
                Resolver.resolveResult(scenarioState(), List.of(), SMALL_NO_LIFECYCLE, SEED).events();
        assertTrue(events.isEmpty(), "a tick with no event-producing action emits an empty stream");
    }

    // ---- helper ---------------------------------------------------------------

    private static <T extends PublicEvent> T single(List<PublicEvent> events, Class<T> kind) {
        List<T> matches = events.stream().filter(kind::isInstance).map(kind::cast).toList();
        assertEquals(1, matches.size(),
                "expected exactly one " + kind.getSimpleName() + " in " + events);
        return matches.get(0);
    }
}
