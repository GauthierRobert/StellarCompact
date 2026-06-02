package com.stellarcompact.engine.action;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.SystemId;

/**
 * The polymorphic target of an {@link Action.Blockade}: either a trade route or a
 * system's market - spec section 3, {@code Blockade.target = routeId | systemId}.
 *
 * <p>Modelled as a sealed two-variant interface for the same reasons as
 * {@link AttackTarget}: "exactly one of route | system" becomes a compile-time
 * guarantee instead of a runtime check over nullable fields, and the resolver can
 * branch with an exhaustive switch.
 *
 * <p>Wire shape mirrors {@link AttackTarget}: polymorphic on a {@code kind}
 * discriminator ({@code "route"} | {@code "system"}), distinct from the
 * top-level {@code type} property. Jackson injects/consumes {@code kind} via
 * {@code As.PROPERTY}; the {@code kind()} accessor is {@code @JsonIgnore}d.
 * Unknown {@code kind}s fail fast (closed set).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BlockadeTarget.OnRoute.class, name = "route"),
        @JsonSubTypes.Type(value = BlockadeTarget.OnSystem.class, name = "system")
})
public sealed interface BlockadeTarget permits BlockadeTarget.OnRoute, BlockadeTarget.OnSystem {

    /** Stable wire discriminator: {@code "route"} or {@code "system"}. */
    @JsonIgnore
    String kind();

    /** Chokes the throughput of {@code route}. */
    record OnRoute(RouteId route) implements BlockadeTarget {
        public OnRoute {
            if (route == null) {
                throw new IllegalArgumentException("BlockadeTarget.OnRoute.route must be set");
            }
        }

        @Override
        public String kind() {
            return "route";
        }
    }

    /** Chokes the market of {@code system}. */
    record OnSystem(SystemId system) implements BlockadeTarget {
        public OnSystem {
            if (system == null) {
                throw new IllegalArgumentException("BlockadeTarget.OnSystem.system must be set");
            }
        }

        @Override
        public String kind() {
            return "system";
        }
    }
}
