package com.stellarcompact.orchestrator.promotion;

import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.galaxy.gen.StarSystem;
import com.stellarcompact.galaxy.gen.SystemGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The promotion / demotion boundary (card E2-05; architecture 02 section 7,
 * data-model spec "Promotion/Demotion"): the bridge that turns a procedural star
 * into a persisted {@link ActiveSystem} on colonisation, and reverts it to pure
 * scenery on abandonment.
 *
 * <p><strong>Why this lives in the orchestrator.</strong> The {@code engine}
 * (active simulation state) and the {@code galaxy} (procedural catalog generator)
 * modules are deliberately independent and do not depend on each other. The
 * orchestrator depends on both, so the mapping {@code StarSystem -> ActiveSystem}
 * belongs here and nowhere else.
 *
 * <p><strong>The scale trick (rule 3 / architecture 02 section 7).</strong> The
 * simulation only ever touches active systems - a tiny bounded set. The
 * billion-star catalog is scenery and frontier and is <em>never iterated</em>.
 * Every method here acts on an <em>explicit</em> {@link SystemId}: there is no API
 * that enumerates the catalog, by construction.
 *
 * <p><strong>Determinism (principle 1).</strong> The galaxy generator is the
 * single source of truth for a star's content. {@link #promote} reads the roster
 * purely from {@code (gameSeed, starId)} via {@link SystemGenerator} and invents no
 * data, so the same {@code (gameSeed, SystemId)} materialises a byte-identical
 * {@link ActiveSystem} on every call and across JVMs. Because no procedural content
 * is ever stored, {@link #demote} simply drops the active row and the star is once
 * again fully regenerable from the seed - so promote-after-demote-after-promote
 * round-trips to an identical {@code ActiveSystem}.
 *
 * <p>Pure and framework-free (a plain class, not a Spring bean): no I/O, no clock,
 * no randomness.
 */
public final class PromotionService {

    /**
     * Loyalty a freely colonised (not captured) system starts at: full. Capture
     * loyalty penalties are a combat/diplomacy concern modelled by the resolver
     * (game-design 05 section 6); a frontier colony promoted by its founder is
     * fully loyal. This is the only non-seed scalar promotion sets and it is the
     * neutral identity value, not a tunable gameplay number.
     */
    public static final double FOUNDING_LOYALTY = 1.0;

    public PromotionService() {
    }

    /**
     * Materialise the active system for a colonised star, purely from the seed.
     *
     * <p>Regenerates the procedural roster for {@code id} via {@link SystemGenerator}
     * (the single source of truth) and maps it one-to-one into an engine
     * {@link ActiveSystem} owned by {@code owner}: each galaxy planet becomes an
     * engine {@link Planet} carrying the same biome (mapped by {@code name()}) and
     * total slot count, with a stable seed-derived {@link PlanetId}. No buildings are
     * placed - the colony's starter building is a gameplay effect applied by the
     * {@code Colonize} resolver step (a later card), not part of materialising
     * scenery; promotion only lays down the physical, regenerable substrate.
     *
     * <p>Deterministic: same {@code (gameSeed, id)} yields an identical
     * {@code ActiveSystem}.
     *
     * @param gameSeed the per-match galaxy seed (root of all procedural content)
     * @param id       the explicit system to promote (its star id is decoded via
     *                 {@link SystemAddress})
     * @param owner    the colonising faction that will own the system
     * @return the materialised, owned {@link ActiveSystem}; never {@code null}
     */
    public ActiveSystem promote(long gameSeed, SystemId id, FactionId owner) {
        if (id == null) {
            throw new IllegalArgumentException("promote.id must be set");
        }
        if (owner == null) {
            throw new IllegalArgumentException("promote.owner must be set");
        }

        long starId = SystemAddress.toStarId(id);
        StarSystem source = SystemGenerator.generate(gameSeed, starId);

        List<Planet> planets = new ArrayList<>(source.planetCount());
        for (com.stellarcompact.galaxy.gen.Planet p : source.planets()) {
            planets.add(materialisePlanet(id, p));
        }

        return new ActiveSystem(
                id,
                deriveName(starId),
                deriveCoords(starId),
                Optional.of(owner),
                planets,
                0L,                 // population starts at zero; growth is an economy effect
                FOUNDING_LOYALTY);
    }

    /**
     * Promote {@code id} and insert the resulting {@link ActiveSystem} into
     * {@code state} (via {@link GameState#withSystem}), returning the next snapshot.
     * Convenience composition of {@link #promote} + insertion.
     */
    public GameState promoteInto(GameState state, SystemId id, FactionId owner) {
        if (state == null) {
            throw new IllegalArgumentException("promoteInto.state must be set");
        }
        ActiveSystem system = promote(state.gameSeed(), id, owner);
        return state.withSystem(system);
    }

    /**
     * Demote {@code id}: remove its active row from {@code state} (via
     * {@link GameState#withoutSystem}), reverting the star to pure procedural
     * scenery. Clean by construction - the planet/building records were nested
     * inside the {@code ActiveSystem} value, so dropping it leaves no orphan rows,
     * and the star remains fully regenerable from {@code (gameSeed, id)} alone.
     * Demoting an absent id is a no-op.
     */
    public GameState demote(GameState state, SystemId id) {
        if (state == null) {
            throw new IllegalArgumentException("demote.state must be set");
        }
        if (id == null) {
            throw new IllegalArgumentException("demote.id must be set");
        }
        return state.withoutSystem(id);
    }

    /**
     * Map one procedural galaxy planet to an engine {@link Planet}. Biome maps
     * one-to-one by {@code name()} (the two enums are kept in lock-step on purpose);
     * the engine's single {@code slotsTotal} is the galaxy planet's total build
     * capacity (ground + orbital). No buildings yet (see {@link #promote}).
     */
    private static Planet materialisePlanet(SystemId systemId, com.stellarcompact.galaxy.gen.Planet source) {
        Biome biome = Biome.valueOf(source.biome().name());
        PlanetId planetId = derivePlanetId(systemId, source.orbitIndex());
        return new Planet(
                planetId,
                biome,
                source.totalSlots(),
                0L,                 // planet population starts at zero
                List.<Building>of());
    }

    /** Stable, seed-derived planet id: the system id plus the planet's stable orbit. */
    private static PlanetId derivePlanetId(SystemId systemId, int orbitIndex) {
        return new PlanetId(systemId.value() + "-p" + orbitIndex);
    }

    /**
     * A stable, human-readable system name derived from the star id. Purely
     * presentational; deterministic so the materialisation is byte-identical on
     * every promote. (A later card may swap in a richer name table; the address is
     * what the simulation keys on, not the display name.)
     */
    private static String deriveName(long starId) {
        return "System " + Long.toUnsignedString(starId);
    }

    /**
     * Engine integer {@link Coords} for the active system: a stable seed-relative
     * address derived from the star id (high/low 32-bit split). The engine compares
     * and identifies by these integer coords (it never uses the galaxy's continuous
     * float {@code StarCoords}); they only need to be reproducible, which the id
     * split guarantees.
     */
    private static Coords deriveCoords(long starId) {
        long x = starId >>> 32;
        long y = starId & 0xFFFFFFFFL;
        return new Coords(x, y);
    }
}
