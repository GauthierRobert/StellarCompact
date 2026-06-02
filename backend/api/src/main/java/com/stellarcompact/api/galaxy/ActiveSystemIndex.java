package com.stellarcompact.api.galaxy;

/**
 * The seam through which a fine-level star tile learns which procedural stars have
 * been promoted to live, persisted systems, so it can carry the active-system
 * pointer the client joins overlay data by (E8-04 "merge active-system state
 * pointers at fine levels"; architecture 02 sections 3 and 5).
 *
 * <p><strong>Pointer only, never state.</strong> A star tile is an immutable,
 * cacheable scenery artifact; the live, every-tick game state (ownership, fleets,
 * routes) is the separate thin overlay layer ({@link GalaxyStateSource} /
 * {@code OverlayController}, E6-04). This index returns ONLY a stable reference -
 * the persisted system id - that lets the client join the two layers by id. It
 * MUST NOT leak mutable state into the tile, or the tile stops being cacheable.
 *
 * <p>An interface on purpose: the real implementation maps a galaxy-space star id
 * to its live system id once promotion (E2-05/E6-01) lands. Until then
 * {@link NoActiveSystems} returns "no promotion" for every star, so scenery tiles
 * are correct and testable now and gain pointers transparently later.
 *
 * <p>Implementations MUST be a pure function of the star id for a given game (no
 * per-call mutation visible in the tile) so the promotion set is stable across the
 * tile's long cache lifetime; when the set changes the tile schema version is
 * bumped to invalidate caches (the active overlay still updates live and
 * separately every tick).
 */
public interface ActiveSystemIndex {

    /**
     * @param gameSeed the per-match galaxy seed identifying the catalog
     * @param starId   the procedural star's stable seed-derived id
     * @return the live persisted system id if this star has been promoted, else
     *         {@code null} (pure scenery)
     */
    Long activeSystemId(long gameSeed, long starId);

    /**
     * The default "nothing promoted yet" index: every star is pure procedural
     * scenery. Replaced (not extended) when promotion wiring lands.
     */
    final class NoActiveSystems implements ActiveSystemIndex {
        @Override
        public Long activeSystemId(long gameSeed, long starId) {
            return null;
        }
    }
}
