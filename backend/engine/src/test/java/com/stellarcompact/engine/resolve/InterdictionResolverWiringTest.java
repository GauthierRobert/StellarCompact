package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.BlockadeTarget;
import com.stellarcompact.engine.config.BalanceProfile;
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
import com.stellarcompact.engine.validation.ActionValidator;
import com.stellarcompact.engine.validation.RejectionReason;
import com.stellarcompact.engine.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1-11 end-to-end: validate then resolve. Confirms the INTERDICTION step is wired into
 * {@link Resolver#resolve} at its fixed slot (step 5), that a war-gated blockade flips
 * the route through the full tick, that a raided owner is debited and the raider credited
 * in the single settlement pass, and that a PEACE-time raid is rejected by the validator
 * and therefore never reaches the resolver (the legality gate, reused from E1-09).
 */
class InterdictionResolverWiringTest {

    private static final FactionId RAIDER = new FactionId("raider");
    private static final FactionId OWNER = new FactionId("owner");
    private static final SystemId HUB = new SystemId("hub");
    private static final SystemId FAR = new SystemId("far");
    private static final FleetId STRIKE = new FleetId("strike");
    private static final RouteId R1 = new RouteId("r1");

    private static final long SEED = 99L;
    private static final long TICK = 3L;

    private static BalanceProfile profile() {
        BalanceProfile base = ResolveFixtures.profile();
        BalanceProfile.Market market = new BalanceProfile.Market(
                "priceTimePriority", 0.5, "ENERGY", 0.75, 0.5);
        return new BalanceProfile(
                base.name(), base.version(), base.resources(), base.population(), market,
                base.construction(), base.combat(), base.movement(), base.tech(),
                base.diplomacy(), base.victory(), base.tick(), base.homePlacement());
    }

    private static Faction faction(FactionId id, ResourceBundle stock) {
        return new Faction(id, "F-" + id.value(), 0.0, stock, Map.of());
    }

    private static ActiveSystem system(SystemId id, FactionId owner) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.ofNullable(owner), List.of(), 0, 1.0);
    }

    private static Fleet strike() {
        return new Fleet(STRIKE, RAIDER, Optional.of(HUB), Optional.empty(),
                FleetStance.BALANCED, List.of(new Ship("cruiser", 2)));
    }

    private static Route route() {
        return new Route(R1, OWNER, HUB, FAR, RouteKind.COMMERCIAL,
                List.of(PhysicalResource.MINERALS), 100, RouteStatus.ACTIVE);
    }

    /** RAIDER strike fleet at HUB; OWNER owns the route; war toggled by {@code atWar}. */
    private static GameState scenario(boolean atWar) {
        GameState s = new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                Map.of(OWNER, faction(OWNER, new ResourceBundle(0, 1000, 0, 0, 0)),
                        RAIDER, faction(RAIDER, ResourceBundle.ZERO)),
                Map.of(HUB, system(HUB, OWNER), FAR, system(FAR, OWNER)),
                Map.of(STRIKE, strike()), Map.of(), Map.of(R1, route()), Map.of(), Set.of());
        return atWar ? s.withWar(RAIDER, OWNER, 0L) : s;
    }

    @Test
    void warTimeBlockadeFlipsRouteThroughTheFullTick() {
        GameState s = scenario(true);
        Action.Blockade blockade = new Action.Blockade(STRIKE, new BlockadeTarget.OnRoute(R1));
        assertInstanceOf(ValidationResult.Valid.class,
                ActionValidator.validate(s, RAIDER, blockade, profile()));

        GameState next = Resolver.resolve(s,
                List.of(new SubmittedAction(RAIDER, blockade, 0)), profile(), SEED);

        assertEquals(RouteStatus.BLOCKADED, next.routes().get(R1).status());
    }

    @Test
    void warTimeRaidDebitsOwnerAndCreditsRaiderAfterSettlement() {
        GameState s = scenario(true);
        Action.Raid raid = new Action.Raid(STRIKE, R1);
        assertInstanceOf(ValidationResult.Valid.class,
                ActionValidator.validate(s, RAIDER, raid, profile()));

        GameState next = Resolver.resolve(s,
                List.of(new SubmittedAction(RAIDER, raid, 0)), profile(), SEED);

        double raiderGain = next.factions().get(RAIDER).stockpiles().minerals();
        double ownerLoss = 1000 - next.factions().get(OWNER).stockpiles().minerals();
        assertTrue(raiderGain > 0.0, "raider banked stolen minerals");
        assertEquals(raiderGain, ownerLoss, 1e-9, "what the owner lost the raider gained");
    }

    @Test
    void peaceTimeRaidIsRejectedAndNeverReachesTheResolver() {
        GameState s = scenario(false); // no war declared
        Action.Raid raid = new Action.Raid(STRIKE, R1);

        ValidationResult vr = ActionValidator.validate(s, RAIDER, raid, profile());
        assertInstanceOf(ValidationResult.Rejected.class, vr);
        assertEquals(RejectionReason.NOT_AT_WAR, ((ValidationResult.Rejected) vr).code());

        // A rejected action never becomes a SubmittedAction; resolving an empty tick
        // leaves the owner's stockpile and the route untouched.
        GameState next = Resolver.resolve(s, List.of(), profile(), SEED);
        assertEquals(1000.0, next.factions().get(OWNER).stockpiles().minerals(), 0.0);
        assertEquals(RouteStatus.ACTIVE, next.routes().get(R1).status());
    }
}
