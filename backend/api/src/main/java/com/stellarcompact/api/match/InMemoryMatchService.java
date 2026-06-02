package com.stellarcompact.api.match;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.ResolveResult;
import com.stellarcompact.engine.resolve.Resolver;
import com.stellarcompact.engine.resolve.Scoring;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.LifecycleTransitions;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.validation.ActionValidator;
import com.stellarcompact.engine.validation.ValidationResult;
import com.stellarcompact.orchestrator.sovereign.ScriptedSovereign;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;
import com.stellarcompact.orchestrator.sovereign.WorldView;
import com.stellarcompact.orchestrator.sovereign.WorldViewBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * In-process, engine-backed {@link MatchService} (board card E6-01). It owns the live
 * match registry, drives the tick loop (start kicks a per-match loop on a virtual thread;
 * pause/resume toggle it) and serves the fog-correct state, paginated event-log and
 * config-weighted leaderboard reads.
 *
 * <p><b>Why in-memory now (the E6-03 seam pattern).</b> Like {@code InMemoryGalaxyStateSource},
 * this is the current backing of the {@link MatchService} seam: a registry of running
 * matches with their authoritative {@link GameState} in memory and the public event log
 * accumulated per tick. A later card swaps in a persistence-backed implementation (commit
 * each tick via {@code TickCommitService}, resume from the {@code event_log}) behind this
 * same interface, without touching the controller. Keeping live state here keeps the api
 * tests free of a database (the standalone-MockMvc style of E6-03).
 *
 * <p><b>How a tick resolves (the {@code HeadlessMatchRunner} pattern, E3-03).</b> Each
 * seat is a provider-neutral {@link Sovereign} (a {@link ScriptedSovereign} bot for now -
 * no LLM in this REST card). One tick: for each faction in ascending id order build its
 * fog-filtered {@link WorldView} ({@link WorldViewBuilder}), ask the bot to decide,
 * validate every emitted {@link Action} against authoritative state via the engine
 * {@link ActionValidator} (closed agent I/O, principle 5 - only {@code Valid} actions
 * survive), then fold the gathered {@link SubmittedAction} batch through the pure engine
 * {@link Resolver} exactly once. Resolution is single-threaded and pure; the only
 * concurrency is the per-match background loop. The api never mutates engine state itself.
 *
 * <p><b>Fog boundary (principle 2).</b> {@link #stateFor} delegates to the authoritative
 * {@link WorldViewBuilder}; a spectator gets only {@link SpectatorView} public state.
 */
@Component
public class InMemoryMatchService implements MatchService {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMatchService.class);

    /** Default balance profile when the create request names none. */
    static final String DEFAULT_PROFILE = "small-default";
    static final int MIN_FACTIONS = 2;
    static final int MAX_FACTIONS = 8;
    static final int DEFAULT_FACTIONS = 2;
    static final int DEFAULT_EVENT_PAGE = 200;
    static final int MAX_EVENT_PAGE = 1000;

    /** Background-loop wall-clock cadence (orchestration timing only, never an engine input). */
    private static final long LOOP_INTERVAL_MS = 200;

    private final Map<String, Match> matches = new ConcurrentHashMap<>();
    private final AtomicLong seedCounter = new AtomicLong(0xCAFEBABEL);

    public InMemoryMatchService() {
    }

    // ===== lifecycle ===========================================================

    @Override
    public GameSummary create(CreateGameRequest request) {
        CreateGameRequest req = request == null
                ? new CreateGameRequest(null, null, null, null, null, null) : request;

        int factionCount = clampFactions(req.factionCount());
        long seed = req.seed() != null ? req.seed() : deriveSeed();
        BalanceProfile profile = loadProfile(req.balanceProfile());

        GameState initial = MatchBootstrap.initialState(seed, factionCount, profile);
        LaneNetwork network = MatchBootstrap.laneNetwork(factionCount);
        SystemAdjacency adjacency = MatchBootstrap.adjacency(factionCount);

        List<Sovereign> seats = new ArrayList<>();
        for (FactionId fid : MatchBootstrap.factionIds(factionCount)) {
            seats.add(new ScriptedSovereign(fid));
        }

        String gameId = UUID.randomUUID().toString();
        Match match = new Match(gameId, profile, network, adjacency, seats, initial);
        matches.put(gameId, match);
        LOG.debug("created match {} seed={} factions={} profile={}",
                gameId, seed, factionCount, profile.name());
        return summaryOf(match);
    }

    @Override
    public GameSummary summary(String gameId) {
        return summaryOf(require(gameId));
    }

    @Override
    public GameSummary start(String gameId) {
        Match match = require(gameId);
        synchronized (match) {
            transition(match, GameStatus.LOBBY);
            transition(match, GameStatus.RUNNING);
            match.startLoop(this::runLoopTick);
        }
        return summaryOf(match);
    }

    @Override
    public GameSummary pause(String gameId) {
        Match match = require(gameId);
        synchronized (match) {
            transition(match, GameStatus.PAUSED);
            match.stopLoop();
        }
        return summaryOf(match);
    }

    @Override
    public GameSummary resume(String gameId) {
        Match match = require(gameId);
        synchronized (match) {
            transition(match, GameStatus.RUNNING);
            match.startLoop(this::runLoopTick);
        }
        return summaryOf(match);
    }

    // ===== reads ===============================================================

    @Override
    public WorldView stateFor(String gameId, FactionId requester) {
        Match match = require(gameId);
        if (requester == null) {
            throw new MatchNotFoundException("requester faction must be set");
        }
        GameState state = match.state();
        if (state.factions().get(requester) == null) {
            throw new MatchNotFoundException(
                    "faction " + requester.value() + " is not a seat in match " + gameId);
        }
        return WorldViewBuilder.build(state, match.adjacency(), requester);
    }

    @Override
    public SpectatorView spectatorState(String gameId) {
        Match match = require(gameId);
        GameState state = match.state();
        List<SpectatorView.Reputation> reps = state.factions().values().stream()
                .sorted(Comparator.comparing(f -> f.id().value()))
                .map(f -> new SpectatorView.Reputation(f.id().value(), f.reputation()))
                .toList();
        return new SpectatorView(gameId, state.tick(), state.status().name(), reps);
    }

    @Override
    public EventsPage events(String gameId, long fromTick, int limit) {
        Match match = require(gameId);
        long from = Math.max(0, fromTick);
        int cap = limit <= 0 ? DEFAULT_EVENT_PAGE : Math.min(limit, MAX_EVENT_PAGE);

        List<EventsPage.Event> page = new ArrayList<>();
        long lastTick = -1;
        boolean truncated = false;
        for (LoggedEvent le : match.events()) {
            if (le.tick() < from) {
                continue;
            }
            if (page.size() >= cap) {
                truncated = true;
                break;
            }
            page.add(le.toDto());
            lastTick = le.tick();
        }
        long next = truncated && lastTick >= 0 ? lastTick + 1 : -1;
        return new EventsPage(gameId, from, page, next);
    }

    @Override
    public LeaderboardResponse leaderboard(String gameId) {
        Match match = require(gameId);
        GameState state = match.state();
        List<LeaderboardResponse.Entry> entries = new ArrayList<>();
        int rank = 1;
        for (Scoring.FactionScore fs : Scoring.rank(state, match.profile())) {
            entries.add(new LeaderboardResponse.Entry(rank++, fs.faction().value(), fs.score()));
        }
        return new LeaderboardResponse(gameId, state.tick(), entries);
    }

    // ===== test seam ===========================================================

    /**
     * Synchronously resolve up to {@code ticks} ticks for a running match (test/diagnostic
     * seam, mirroring the headless runner). Lets tests advance deterministically without the
     * background loop. No-op once the match concludes.
     *
     * @return the number of ticks actually resolved
     */
    int advanceForTest(String gameId, int ticks) {
        Match match = require(gameId);
        int done = 0;
        for (int i = 0; i < ticks; i++) {
            synchronized (match) {
                if (match.state().status() != GameStatus.RUNNING) {
                    break;
                }
                resolveOneTick(match);
            }
            done++;
            if (match.state().status() == GameStatus.CONCLUDED) {
                break;
            }
        }
        return done;
    }

    // ===== tick loop ===========================================================

    private boolean runLoopTick(Match match) {
        synchronized (match) {
            if (match.state().status() != GameStatus.RUNNING) {
                return false;
            }
            resolveOneTick(match);
            return match.state().status() == GameStatus.RUNNING;
        }
    }

    /**
     * Resolve exactly one tick (the {@code HeadlessMatchRunner} pattern) and fold the result
     * back: record the tick's public events (append-only, tick-ordered) and advance the
     * snapshot. A concluded match is not advanced past the concluded tick (mirrors the
     * headless runner). Caller holds the match lock so the snapshot read-resolve-write is
     * atomic w.r.t. concurrent reads.
     */
    private void resolveOneTick(Match match) {
        GameState state = match.state();
        long tick = state.tick();
        long gameSeed = state.gameSeed();

        List<SubmittedAction> batch = gatherTick(match, state);
        ResolveResult result = Resolver.resolveResult(
                state, batch, match.profile(), gameSeed, match.network());

        match.appendEvents(tick, result.events());

        GameState resolved = result.state();
        if (resolved.status() == GameStatus.CONCLUDED) {
            match.setState(resolved);
        } else {
            match.setState(resolved.withTick(tick + 1));
        }
    }

    /**
     * One tick's perceive -&gt; decide -&gt; validate pass over the seats in ascending faction-id
     * order (a total order, so the submitted batch never depends on map iteration): project
     * each seat's fog-filtered {@link WorldView}, ask it to decide, and keep only the actions
     * that pass the engine {@link ActionValidator}. Pure single-threaded gathering; the
     * actual mutation is the one {@link Resolver} call in {@link #resolveOneTick}.
     */
    private static List<SubmittedAction> gatherTick(Match match, GameState state) {
        List<SubmittedAction> batch = new ArrayList<>();
        for (Sovereign seat : match.seats()) {
            FactionId actor = seat.factionId();
            WorldView view = WorldViewBuilder.build(state, match.adjacency(), actor);
            AgentResponse response = seat.decide(view);
            int submissionOrder = 0;
            for (Action action : response.actions()) {
                ValidationResult result = ActionValidator.validate(
                        state, actor, action, match.profile(), match.network());
                if (result.isValid()) {
                    batch.add(new SubmittedAction(actor, action, submissionOrder++));
                }
            }
        }
        return batch;
    }

    // ===== helpers =============================================================

    private Match require(String gameId) {
        Match match = gameId == null ? null : matches.get(gameId);
        if (match == null) {
            throw new MatchNotFoundException("no match with id " + gameId);
        }
        return match;
    }

    private static void transition(Match match, GameStatus to) {
        GameStatus from = match.state().status();
        if (!LifecycleTransitions.canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
        match.setState(LifecycleTransitions.transition(match.state(), to));
    }

    private static int clampFactions(Integer requested) {
        int n = requested == null ? DEFAULT_FACTIONS : requested;
        if (n < MIN_FACTIONS) {
            n = MIN_FACTIONS;
        }
        if (n > MAX_FACTIONS) {
            n = MAX_FACTIONS;
        }
        return n;
    }

    private long deriveSeed() {
        return seedCounter.incrementAndGet() * 0x9E3779B97F4A7C15L;
    }

    private BalanceProfile loadProfile(String name) {
        String profileName = name == null || name.isBlank() ? DEFAULT_PROFILE : name;
        String path = "/balance/" + profileName + ".json";
        try (InputStream in = InMemoryMatchService.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new MatchNotFoundException("unknown balance profile: " + profileName);
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("failed reading balance profile " + profileName, e);
        }
    }

    private static GameSummary summaryOf(Match match) {
        GameState state = match.state();
        List<String> factions = state.factions().keySet().stream()
                .map(FactionId::value)
                .sorted()
                .toList();
        return new GameSummary(match.id(), state.gameSeed(), state.status(),
                state.tick(), state.balanceProfileName(), factions);
    }

    // ===== match holder ========================================================

    /** A logged public event with its tick + intra-tick sequence (the total-order key). */
    private record LoggedEvent(long tick, int seq, PublicEvent event) {
        EventsPage.Event toDto() {
            List<String> parties = event.parties().stream().map(FactionId::value).toList();
            String systemId = event.systemId().map(SystemId::value).orElse(null);
            return new EventsPage.Event(tick, seq, event.type(), parties, systemId);
        }
    }

    /**
     * One live match: its static-per-match inputs, the authoritative snapshot (guarded by the
     * instance monitor), the append-only event log and the background loop handle.
     */
    private static final class Match {
        private final String id;
        private final BalanceProfile profile;
        private final LaneNetwork network;
        private final SystemAdjacency adjacency;
        private final List<Sovereign> seats;
        private GameState state;
        private final List<LoggedEvent> events = new ArrayList<>();
        private volatile Thread loop;

        Match(String id, BalanceProfile profile, LaneNetwork network, SystemAdjacency adjacency,
              List<Sovereign> seats, GameState initial) {
            this.id = id;
            this.profile = profile;
            this.network = network;
            this.adjacency = adjacency;
            // Fixed ascending faction-id seat order so the gathered batch is replay-stable.
            this.seats = seats.stream()
                    .sorted(Comparator.comparing(s -> s.factionId().value()))
                    .toList();
            this.state = initial;
        }

        String id() {
            return id;
        }

        BalanceProfile profile() {
            return profile;
        }

        LaneNetwork network() {
            return network;
        }

        SystemAdjacency adjacency() {
            return adjacency;
        }

        List<Sovereign> seats() {
            return seats;
        }

        synchronized GameState state() {
            return state;
        }

        synchronized void setState(GameState next) {
            this.state = next;
        }

        synchronized void appendEvents(long tick, List<PublicEvent> tickEvents) {
            int seq = 0;
            for (PublicEvent e : tickEvents) {
                events.add(new LoggedEvent(tick, seq++, e));
            }
        }

        synchronized List<LoggedEvent> events() {
            return new ArrayList<>(events);
        }

        /**
         * Launch the background tick loop on a virtual thread (one per running match). It
         * resolves a tick, sleeps the configured interval, and exits when the match is no
         * longer RUNNING (pause/conclude) or it is interrupted (stopLoop). Idempotent.
         */
        void startLoop(Predicate<Match> step) {
            Thread existing = loop;
            if (existing != null && existing.isAlive()) {
                return;
            }
            Match self = this;
            Thread t = Thread.ofVirtual().name("match-loop-" + id).unstarted(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (!step.test(self)) {
                            break;
                        }
                        Thread.sleep(LOOP_INTERVAL_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger(Match.class).warn("match {} loop stopped on error", id, e);
                }
            });
            loop = t;
            t.start();
        }

        /** Stop the background loop (pause / shutdown). Idempotent. */
        void stopLoop() {
            Thread t = loop;
            if (t != null) {
                t.interrupt();
                loop = null;
            }
        }
    }
}
