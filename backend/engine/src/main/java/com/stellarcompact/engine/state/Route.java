package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * An established trade/military route between two systems - a first-class game
 * object that carries throughput and can be raided or blockaded (game-design 01
 * section 3, economy 02 section 5; data-model {@code route(system_a, system_b,
 * kind, resources_json, volume, status)}).
 *
 * <p>{@code owner} is the faction that activated the route; {@code resources} is
 * the set of physical resources it hauls; {@code volume} its per-tick throughput
 * (drives Influence accrual at both endpoints). Endpoints are stored as an
 * unordered pair {@code (systemA, systemB)} - the constructor does not reorder
 * them, so the resolver should treat the pair symmetrically.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Route(
        RouteId id,
        FactionId owner,
        SystemId systemA,
        SystemId systemB,
        RouteKind kind,
        List<PhysicalResource> resources,
        double volume,
        RouteStatus status
) {
    public Route {
        if (id == null) {
            throw new IllegalArgumentException("Route.id must be set");
        }
        if (owner == null) {
            throw new IllegalArgumentException("Route.owner must be set");
        }
        if (systemA == null || systemB == null) {
            throw new IllegalArgumentException("Route endpoints must be set");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Route.kind must be set");
        }
        if (status == null) {
            throw new IllegalArgumentException("Route.status must be set");
        }
        if (volume < 0) {
            throw new IllegalArgumentException("Route.volume must be >= 0");
        }
        resources = List.copyOf(resources);
    }

    /**
     * Copy-on-write: this route with a different {@link RouteStatus}, every other
     * field shared structurally. The INTERDICTION step (E1-11 {@code Blockade})
     * flips an {@code ACTIVE} route to {@code BLOCKADED} (later cards lift it back);
     * the choke is a status flag here, while the throughput-reduction <em>factor</em>
     * it implies lives in config ({@code market.blockadeThroughputFactor}) so the
     * resolver hardcodes no number. Returns {@code this} when the status is unchanged.
     */
    public Route withStatus(RouteStatus newStatus) {
        if (newStatus == null) {
            throw new IllegalArgumentException("Route.withStatus: newStatus must be set");
        }
        if (newStatus == status) {
            return this;
        }
        return new Route(id, owner, systemA, systemB, kind, resources, volume, newStatus);
    }
}
