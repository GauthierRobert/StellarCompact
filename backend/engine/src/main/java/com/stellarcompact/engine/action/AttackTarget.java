package com.stellarcompact.engine.action;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.SystemId;

/**
 * The polymorphic target of an {@link Action.Attack}: either a system (a planetary
 * assault) or an enemy fleet (a ship engagement) - spec section 3,
 * {@code Attack.target = systemId | fleetId}.
 *
 * <p><b>Why a sealed wrapper rather than two nullable fields.</b> Modelling the
 * target as {@code (SystemId?, FleetId?)} would admit illegal shapes - both set,
 * or neither - that the validator (E1-04) would have to reject at runtime.
 * A sealed two-variant interface makes "exactly one of system | fleet" a
 * compile-time guarantee and lets the resolver branch with an exhaustive switch,
 * matching the determinism contract (closed sets, no {@code default}).
 *
 * <p><b>Wire shape.</b> Polymorphic on a {@code kind} discriminator
 * ({@code "system"} | {@code "fleet"}) - deliberately a different property name
 * from {@code Action}'s {@code type} so a nested target can never be confused
 * with a top-level action. Jackson injects/consumes {@code kind} via
 * {@code As.PROPERTY}; the {@code kind()} accessor is {@code @JsonIgnore}d so it
 * is not also emitted as a duplicate bean property. Closed {@code @JsonSubTypes}
 * means an unknown {@code kind} fails fast here (a malformed target is not a
 * forward-compat concern - only unknown top-level action {@code type}s degrade to
 * Hold).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AttackTarget.OnSystem.class, name = "system"),
        @JsonSubTypes.Type(value = AttackTarget.OnFleet.class, name = "fleet")
})
public sealed interface AttackTarget permits AttackTarget.OnSystem, AttackTarget.OnFleet {

    /** Stable wire discriminator: {@code "system"} or {@code "fleet"}. */
    @JsonIgnore
    String kind();

    /** A planetary assault against {@code system}. */
    record OnSystem(SystemId system) implements AttackTarget {
        public OnSystem {
            if (system == null) {
                throw new IllegalArgumentException("AttackTarget.OnSystem.system must be set");
            }
        }

        @Override
        public String kind() {
            return "system";
        }
    }

    /** A ship engagement against enemy {@code fleet}. */
    record OnFleet(FleetId fleet) implements AttackTarget {
        public OnFleet {
            if (fleet == null) {
                throw new IllegalArgumentException("AttackTarget.OnFleet.fleet must be set");
            }
        }

        @Override
        public String kind() {
            return "fleet";
        }
    }
}
