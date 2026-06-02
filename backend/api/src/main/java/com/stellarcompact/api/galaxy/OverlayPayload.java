package com.stellarcompact.api.galaxy;

import java.util.List;

/**
 * The thin, dynamic active-state overlay for a region of a live game
 * (rest-api spec). This is the deliberately small layer that is composited on top
 * of the immutable star tiles client-side: ownership tint, contested flag, fleet
 * presence, live routes and blockades - and NOTHING about scenery stars
 * (architecture 02 sections 3 and 5; lod-tiling skill). Heavy star/tile data is
 * never carried here and is never designed to flow over the websocket; E6-04
 * pushes this same thin shape live.
 *
 * @param gameId    the live match id
 * @param asOfTick  the latest engine tick reflected in this payload
 * @param sinceTick the lower bound the caller asked for (echoed; payload is a diff
 *                  of systems changed at a tick &gt; sinceTick)
 * @param bbox      the region the caller asked for (echoed)
 * @param systems   per-system active state inside the bbox, changed since sinceTick
 * @param blockades active blockades inside the bbox (system ids)
 */
public record OverlayPayload(String gameId, long asOfTick, long sinceTick,
                             TileGrid.Bbox bbox, List<SystemOverlay> systems,
                             List<Blockade> blockades) {

    public OverlayPayload {
        systems = List.copyOf(systems);
        blockades = List.copyOf(blockades);
    }

    /**
     * Active state for one system (the thin per-system diff entry).
     *
     * @param systemId  the persisted active-system id
     * @param owner     owning faction id, or null if neutral/abandoned
     * @param tint      display tint (e.g. "#rrggbb") for the owner, or null
     * @param contested whether the system is currently contested
     * @param fleets    faction ids with a fleet present (thin presence list)
     * @param routes    outbound live routes from this system
     */
    public record SystemOverlay(long systemId, String owner, String tint,
                                boolean contested, List<String> fleets,
                                List<Route> routes) {
        public SystemOverlay {
            fleets = List.copyOf(fleets);
            routes = List.copyOf(routes);
        }
    }

    /** A live route endpoint reference (thin: just the destination system id). */
    public record Route(long toSystemId) {
    }

    /** An active blockade marker. */
    public record Blockade(long systemId) {
    }
}
