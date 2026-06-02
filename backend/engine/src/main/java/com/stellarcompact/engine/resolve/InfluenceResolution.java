package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The passive INFLUENCE resolution step (board card E1-14; game-design
 * {@code 02-economy} sections 1, 5, 7). Runs once per tick, in its fixed slot
 * (step 10, after production/upkeep and before the public-event emission), folding
 * each faction's Influence accrual and decay.
 *
 * <p><b>Influence is political capital, not a physical good.</b> Unlike the four
 * physical resources (Energy/Minerals/Food/Tech), Influence is <em>never</em> hauled
 * along a route nor matched on the market order book (game-design 02 section 1 - it
 * can only be granted via a treaty term, never sold, which prevents a pay-to-win
 * spiral). This step therefore accrues and decays it <em>directly</em> on the
 * faction's stockpile (copy-on-write), <b>not</b> through the tick-wide
 * {@link SpendLedger} - the ledger is the escrow seam for tradeable/haulable flows and
 * spends, and routing influence through it would risk it being treated as a
 * commodity. Nothing here lets influence be traded.
 *
 * <p><b>Accrual sources (game-design 02 section 1/7: "Influence accrues from capitals,
 * trade volume, monuments and honoured diplomacy").</b> For each faction, in stable id
 * order, the per-tick gain is the sum of:
 * <ol>
 *   <li>{@code perCapitalSystem} per owned system (the soft-power base of holding
 *       territory);</li>
 *   <li>{@code perMonument} per active {@link BuildingType#MONUMENT} the faction holds
 *       (the prestige building's ongoing political output);</li>
 *   <li>{@code perTradeVolume} per unit of throughput of each ACTIVE route the faction
 *       owns - a BLOCKADED route's throughput is scaled by
 *       {@code (1 - market.blockadeThroughputFactor)} so a choked route earns less
 *       (commerce builds soft power, game-design 02 section 5);</li>
 *   <li>{@code perActiveTreaty} per ACTIVE treaty the faction signs (honoured
 *       diplomacy - standing agreements project prestige).</li>
 * </ol>
 *
 * <p><b>Decay (game-design 02 section 7).</b> After accrual the faction's Influence is
 * multiplied by {@code (1 - decayRate)} so an idle faction's influence bleeds toward
 * zero and only sustained behaviour keeps it high. Decay is applied to the
 * <em>post-accrual</em> total (this tick's gains decay too, a uniform exponential
 * bleed).
 *
 * <p><b>Purity / determinism.</b> Pure arithmetic and config lookups only: no I/O, no
 * wall-clock, no randomness (influence flow is fully determined by state + profile).
 * Factions, systems and routes are visited in a stable id order so the fold is
 * replay-stable regardless of map iteration order. Every number reads from the
 * {@link BalanceProfile.Influence} block (rule 6); nothing is hardcoded.
 */
final class InfluenceResolution {

    private InfluenceResolution() {
    }

    /**
     * Apply one tick of Influence accrual (capitals + monuments + trade volume +
     * honoured treaties) and decay to every faction, writing the new Influence
     * stockpile directly via copy-on-write. Only the {@code influence()} component of
     * each faction's bundle is touched; the four physical resources are left exactly as
     * the production/upkeep settle pass left them.
     *
     * @param state   the snapshot entering the influence step (after production/upkeep)
     * @param profile the active balance profile - source of every number (rule 6)
     * @return the next snapshot with each faction's Influence accrued and decayed
     */
    static GameState resolve(GameState state, BalanceProfile profile) {
        BalanceProfile.Influence cfg = profile.influence();

        // Visit factions in a stable id order so the fold is replay-stable.
        List<FactionId> factionOrder = new ArrayList<>(state.factions().keySet());
        factionOrder.sort(Comparator.comparing(FactionId::value));

        GameState next = state;
        for (FactionId fid : factionOrder) {
            Faction faction = state.factions().get(fid);

            double accrual = capitalInfluence(state, fid, cfg)
                    + monumentInfluence(state, fid, cfg)
                    + tradeInfluence(state, fid, cfg, profile.market())
                    + treatyInfluence(state, fid, cfg);

            ResourceBundle stock = faction.stockpiles();
            double accrued = stock.influence() + accrual;
            double decayed = accrued * (1.0 - cfg.decayRate());

            if (decayed != stock.influence()) {
                ResourceBundle updated = new ResourceBundle(
                        stock.energy(), stock.minerals(), stock.food(), stock.tech(), decayed);
                next = next.withFaction(faction.withStockpiles(updated));
            }
        }
        return next;
    }

    // ===== accrual sources (pure) =============================================

    /** Flat Influence per owned system (the capital / soft-power-base source). */
    private static double capitalInfluence(GameState state, FactionId fid,
                                           BalanceProfile.Influence cfg) {
        if (cfg.perCapitalSystem() <= 0.0) {
            return 0.0;
        }
        long owned = 0;
        for (ActiveSystem system : state.systems().values()) {
            if (ownedBy(system, fid)) {
                owned++;
            }
        }
        return owned * cfg.perCapitalSystem();
    }

    /** Flat Influence per active {@link BuildingType#MONUMENT} the faction holds. */
    private static double monumentInfluence(GameState state, FactionId fid,
                                            BalanceProfile.Influence cfg) {
        if (cfg.perMonument() <= 0.0) {
            return 0.0;
        }
        long monuments = 0;
        for (ActiveSystem system : state.systems().values()) {
            if (!ownedBy(system, fid)) {
                continue;
            }
            for (Planet planet : system.planets()) {
                for (Building b : planet.buildings()) {
                    if (b.type() == BuildingType.MONUMENT && b.status() == BuildingStatus.ACTIVE) {
                        monuments++;
                    }
                }
            }
        }
        return monuments * cfg.perMonument();
    }

    /**
     * Influence from trade volume: {@code perTradeVolume x effectiveVolume} summed over
     * every route the faction owns, in stable route id order. A BLOCKADED route's
     * volume is scaled by {@code (1 - blockadeThroughputFactor)} (a choked route still
     * earns the un-choked remainder of its commerce); a SUSPENDED route earns nothing.
     */
    private static double tradeInfluence(GameState state, FactionId fid,
                                         BalanceProfile.Influence cfg,
                                         BalanceProfile.Market market) {
        if (cfg.perTradeVolume() <= 0.0) {
            return 0.0;
        }
        double total = 0.0;
        for (Route route : sortedRoutes(state)) {
            if (!route.owner().equals(fid)) {
                continue;
            }
            double effectiveVolume = switch (route.status()) {
                case ACTIVE -> route.volume();
                case BLOCKADED -> route.volume() * (1.0 - market.blockadeThroughputFactor());
                case SUSPENDED -> 0.0;
            };
            total += effectiveVolume * cfg.perTradeVolume();
        }
        return total;
    }

    /** Flat Influence per ACTIVE treaty the faction is a signatory to (honoured diplomacy). */
    private static double treatyInfluence(GameState state, FactionId fid,
                                          BalanceProfile.Influence cfg) {
        if (cfg.perActiveTreaty() <= 0.0) {
            return 0.0;
        }
        long active = 0;
        for (Treaty treaty : state.treaties().values()) {
            if (treaty.status() == TreatyStatus.ACTIVE && treaty.involves(fid)) {
                active++;
            }
        }
        return active * cfg.perActiveTreaty();
    }

    // ===== helpers (pure) =====================================================

    private static boolean ownedBy(ActiveSystem system, FactionId fid) {
        Optional<FactionId> owner = system.owner();
        return owner.isPresent() && owner.get().equals(fid);
    }

    private static List<Route> sortedRoutes(GameState state) {
        List<Route> routes = new ArrayList<>(state.routes().values());
        routes.sort(Comparator.comparing(r -> r.id().value()));
        return routes;
    }
}
