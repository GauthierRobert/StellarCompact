package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.SystemId;

import java.util.List;
import java.util.Optional;

/**
 * A galaxy-wide, common-knowledge public event emitted during resolution
 * (board card E1-16; game-design 03 step 11 "Public event emission",
 * docs/specs/websocket-protocol.md {@code /topic/games/{gameId}/events}).
 *
 * <p><b>What this is.</b> The closed set of public event kinds every spectator and
 * Sovereign sees over the live WebSocket {@code events} topic. The diplomacy/combat/
 * economy/lifecycle kinds land in E1-16/E1-15; the exploration/development milestones
 * ({@code ColonyFounded}, {@code FirstContact}, {@code TechUnlocked}) are added in E12-04
 * (P7d) so meaningful expansion/contact/tech milestones surface to the feed + overlay.
 * Each carries the spec
 * payload <em>field-for-field</em>: {@code { type, parties[], systemId?, tick }} -
 * {@link #type()} is the wire discriminator (the variant simple name), {@link #parties()}
 * the involved faction ids, {@link #systemId()} the system the event concerns (empty for
 * the faction-/treaty-scoped kinds), and {@link #tick()} the tick it occurred on.
 *
 * <p><b>Transient, not hashed state.</b> Public events are per-tick output, NOT part of
 * the persistent, hashed {@link com.stellarcompact.engine.state.GameState} snapshot.
 * They are returned alongside the next state in a {@link ResolveResult}; the event log is
 * append-only and ordered by tick (the determinism/replay contract -
 * {@code .claude/skills/game-engine-determinism} "the event log is append-only, ordered
 * by tick"). Keeping them out of {@code GameState} keeps the golden state hash stable.
 *
 * <p><b>Closed by design.</b> A {@code sealed interface} so any consumer switches
 * exhaustively with no default; adding a kind is a compile error until handled.
 *
 * <p><b>Deterministic ordering.</b> Events are recorded by the resolution steps in the
 * fixed step order, each step folding its already-(step, actor, submissionOrder)-ordered
 * action slice, so the emitted list is a pure function of the tick's ordered action
 * stream and seeded outcomes - replay-stable, no map iteration order participates.
 *
 * <p>Immutable records; no I/O, no clock, no RNG.
 */
public sealed interface PublicEvent permits
        PublicEvent.WarDeclared, PublicEvent.TreatySigned, PublicEvent.TreatyBroken,
        PublicEvent.AllianceFormed, PublicEvent.SystemCaptured, PublicEvent.BattleResolved,
        PublicEvent.RouteEstablished, PublicEvent.RouteRaided, PublicEvent.FactionEliminated,
        PublicEvent.VictoryAchieved, PublicEvent.ColonyFounded, PublicEvent.FirstContact,
        PublicEvent.TechUnlocked {

    /** Wire discriminator: the variant simple name (e.g. {@code "WarDeclared"}). */
    String type();

    /** The faction ids the event involves (never {@code null}; defensively copied). */
    List<FactionId> parties();

    /** The system the event concerns, or empty for faction-/treaty-scoped events. */
    Optional<SystemId> systemId();

    /** The tick on which the event occurred. */
    long tick();

    // ===== diplomacy events (DIPLOMATIC_STATE step) ===========================

    /** A faction declared war (parties = [declarer, target]). */
    record WarDeclared(FactionId declarer, FactionId target, long tick) implements PublicEvent {
        public WarDeclared {
            requireNonNull(declarer, "WarDeclared.declarer");
            requireNonNull(target, "WarDeclared.target");
        }

        @Override
        public String type() {
            return "WarDeclared";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(declarer, target);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.empty();
        }
    }

    /** A treaty was accepted and became engine-enforced (parties = signatories). */
    record TreatySigned(List<FactionId> signatories, long tick) implements PublicEvent {
        public TreatySigned {
            requireNonNull(signatories, "TreatySigned.signatories");
            signatories = List.copyOf(signatories);
        }

        @Override
        public String type() {
            return "TreatySigned";
        }

        @Override
        public List<FactionId> parties() {
            return signatories;
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.empty();
        }
    }

    /** An active treaty was terminated early by a signatory (parties = signatories). */
    record TreatyBroken(List<FactionId> signatories, long tick) implements PublicEvent {
        public TreatyBroken {
            requireNonNull(signatories, "TreatyBroken.signatories");
            signatories = List.copyOf(signatories);
        }

        @Override
        public String type() {
            return "TreatyBroken";
        }

        @Override
        public List<FactionId> parties() {
            return signatories;
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.empty();
        }
    }

    /** An Alliance treaty was formed (parties = the allied signatories). */
    record AllianceFormed(List<FactionId> signatories, long tick) implements PublicEvent {
        public AllianceFormed {
            requireNonNull(signatories, "AllianceFormed.signatories");
            signatories = List.copyOf(signatories);
        }

        @Override
        public String type() {
            return "AllianceFormed";
        }

        @Override
        public List<FactionId> parties() {
            return signatories;
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.empty();
        }
    }

    // ===== combat events (COMBAT step) ========================================

    /** A system changed ownership by assault (parties = [newOwner], systemId set). */
    record SystemCaptured(FactionId newOwner, SystemId system, long tick) implements PublicEvent {
        public SystemCaptured {
            requireNonNull(newOwner, "SystemCaptured.newOwner");
            requireNonNull(system, "SystemCaptured.system");
        }

        @Override
        public String type() {
            return "SystemCaptured";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(newOwner);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.of(system);
        }
    }

    /**
     * A battle was fought (parties = [attacker, defender]). {@code system} is set for a
     * system assault / interception at a system, empty for a pure fleet-vs-fleet clash.
     */
    record BattleResolved(FactionId attacker, FactionId defender,
                          Optional<SystemId> system, long tick) implements PublicEvent {
        public BattleResolved {
            requireNonNull(attacker, "BattleResolved.attacker");
            requireNonNull(defender, "BattleResolved.defender");
            requireNonNull(system, "BattleResolved.system (use Optional.empty())");
        }

        @Override
        public String type() {
            return "BattleResolved";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(attacker, defender);
        }

        @Override
        public Optional<SystemId> systemId() {
            return system;
        }
    }

    // ===== economy events =====================================================

    /** A trade route was activated (parties = [owner]; endpoint system set). */
    record RouteEstablished(FactionId owner, SystemId endpoint, long tick) implements PublicEvent {
        public RouteEstablished {
            requireNonNull(owner, "RouteEstablished.owner");
            requireNonNull(endpoint, "RouteEstablished.endpoint");
        }

        @Override
        public String type() {
            return "RouteEstablished";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(owner);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.of(endpoint);
        }
    }

    /** A route shipment was raided (parties = [raider, victim]). */
    record RouteRaided(FactionId raider, FactionId victim, long tick) implements PublicEvent {
        public RouteRaided {
            requireNonNull(raider, "RouteRaided.raider");
            requireNonNull(victim, "RouteRaided.victim");
        }

        @Override
        public String type() {
            return "RouteRaided";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(raider, victim);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.empty();
        }
    }

    // ===== lifecycle events ===================================================

    /** A faction was eliminated (parties = [eliminated]). */
    record FactionEliminated(FactionId faction, long tick) implements PublicEvent {
        public FactionEliminated {
            requireNonNull(faction, "FactionEliminated.faction");
        }

        @Override
        public String type() {
            return "FactionEliminated";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(faction);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.empty();
        }
    }

    /** A faction achieved a victory condition (parties = [winner]). */
    record VictoryAchieved(FactionId winner, long tick) implements PublicEvent {
        public VictoryAchieved {
            requireNonNull(winner, "VictoryAchieved.winner");
        }

        @Override
        public String type() {
            return "VictoryAchieved";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(winner);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.empty();
        }
    }

    // ===== exploration / development milestones (E12-04) ======================

    /**
     * A faction successfully colonised a system (parties = [coloniser], systemId set).
     * Recorded at the COLONISATION step when a validated {@code Colonize} resolves -
     * a public, galaxy-wide fact (the new colony is visible on the map), derived
     * deterministically from the validated action (P7d: surfaces a meaningful expansion
     * milestone to the spectator feed + galaxy overlay).
     */
    record ColonyFounded(FactionId coloniser, SystemId system, long tick) implements PublicEvent {
        public ColonyFounded {
            requireNonNull(coloniser, "ColonyFounded.coloniser");
            requireNonNull(system, "ColonyFounded.system");
        }

        @Override
        public String type() {
            return "ColonyFounded";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(coloniser);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.of(system);
        }
    }

    /**
     * Two factions made first contact: the {@code discoverer} revealed (via Explore) a
     * system owned by another faction it had not previously known (parties =
     * [discoverer, contacted], systemId = the system where contact occurred). Recorded
     * once, deterministically, in the DEVELOPMENT step's Explore handler the first time
     * the discoverer reveals an owned system - monotone within a match (P7d: surfaces the
     * "first time two empires meet" milestone).
     */
    record FirstContact(FactionId discoverer, FactionId contacted, SystemId system, long tick)
            implements PublicEvent {
        public FirstContact {
            requireNonNull(discoverer, "FirstContact.discoverer");
            requireNonNull(contacted, "FirstContact.contacted");
            requireNonNull(system, "FirstContact.system");
        }

        @Override
        public String type() {
            return "FirstContact";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(discoverer, contacted);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.of(system);
        }
    }

    /**
     * A faction completed researching a tech (parties = [faction]). Recorded at the
     * DEVELOPMENT step the tick a {@code RESEARCHING} node flips to {@code UNLOCKED} in
     * the research advance sweep - a public milestone derived deterministically from the
     * progress sweep (P7d). The tech id is carried as a plain string for the wire payload
     * (the tech web is public knowledge); it is faction-scoped, so no system is set.
     */
    record TechUnlocked(FactionId faction, String techId, long tick) implements PublicEvent {
        public TechUnlocked {
            requireNonNull(faction, "TechUnlocked.faction");
            requireNonNull(techId, "TechUnlocked.techId");
        }

        @Override
        public String type() {
            return "TechUnlocked";
        }

        @Override
        public List<FactionId> parties() {
            return List.of(faction);
        }

        @Override
        public Optional<SystemId> systemId() {
            return Optional.empty();
        }
    }

    // ===== shared helper ======================================================

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be set");
        }
    }
}
