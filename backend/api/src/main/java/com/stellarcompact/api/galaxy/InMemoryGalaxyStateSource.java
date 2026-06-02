package com.stellarcompact.api.galaxy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, deterministic in-memory stub {@link GalaxyStateSource} so the overlay
 * endpoint is contract-correct and testable before the live match wiring exists
 * (E6-01 supplies the real engine-backed source; E6-04 the live WS push). It
 * holds a handful of fixed active systems and enforces the same bbox + sinceTick
 * scoping the real source must - proving the endpoint stays thin.
 *
 * <p>STUB: the data is invented, not read from a running game. It exists only to
 * exercise the overlay contract; it must be replaced (not extended) when E6-01
 * lands a real engine-state-backed implementation. It deliberately carries NO
 * scenery/star data - only active state - keeping the overlay layer thin.
 */
@Component
public class InMemoryGalaxyStateSource implements GalaxyStateSource {

    /** A fixed active system the stub knows about. */
    private record Entry(long systemId, double x, double y, long lastChangedTick,
                         String owner, String tint, boolean contested,
                         List<String> fleets, List<Long> routes, boolean blockaded) {
    }

    private final List<Entry> entries = List.of(
            new Entry(1001L, 0.0, 0.0, 5L, "faction-A", "#33aaff", false,
                    List.of("faction-A"), List.of(1002L), false),
            new Entry(1002L, 120.0, 60.0, 12L, "faction-B", "#ff5533", true,
                    List.of("faction-A", "faction-B"), List.of(1001L), true),
            new Entry(1003L, -300.0, 200.0, 3L, null, null, false,
                    List.of(), List.of(), false)
    );

    @Override
    public OverlayPayload overlay(String gameId, TileGrid.Bbox bbox, long sinceTick) {
        long asOf = entries.stream().mapToLong(Entry::lastChangedTick).max().orElse(0L);

        List<OverlayPayload.SystemOverlay> systems = new ArrayList<>();
        List<OverlayPayload.Blockade> blockades = new ArrayList<>();
        for (Entry e : entries) {
            if (!bbox.contains(e.x(), e.y())) {
                continue; // bbox scoping
            }
            if (e.lastChangedTick() <= sinceTick) {
                continue; // sinceTick diff scoping
            }
            List<OverlayPayload.Route> routes = new ArrayList<>();
            for (Long to : e.routes()) {
                routes.add(new OverlayPayload.Route(to));
            }
            systems.add(new OverlayPayload.SystemOverlay(
                    e.systemId(), e.owner(), e.tint(), e.contested(),
                    e.fleets(), routes));
            if (e.blockaded()) {
                blockades.add(new OverlayPayload.Blockade(e.systemId()));
            }
        }
        return new OverlayPayload(gameId, asOf, sinceTick, bbox, systems, blockades);
    }
}
