package com.stellarcompact.engine.action;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteKind;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Proves the sealed {@link Action} hierarchy and {@link AgentResponse} satisfy
 * the closed agent-I/O contract: a representative response containing many
 * different variants (including the two polymorphic-target variants) round-trips
 * through Jackson and compares equal, and the {@code type} discriminator drives
 * deserialisation back to the correct concrete record.
 *
 * <p>Purity seam: Jackson is configured here in TEST scope, mirroring the strict
 * mapper used elsewhere in the engine tests; the engine MAIN code performs no I/O.
 */
class ActionRoundTripTest {

    /** Strict mapper, except it must tolerate the leniency the records opt into. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new Jdk8Module())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static AgentResponse sampleResponse() {
        FactionId fA = new FactionId("faction-A");
        FactionId fB = new FactionId("faction-B");
        SystemId sHome = new SystemId("sys-home");
        SystemId sFrontier = new SystemId("sys-frontier");
        FleetId fleet = new FleetId("fleet-1");
        PlanetId planet = new PlanetId("planet-cradle");

        List<Action> actions = List.of(
                new Action.Explore(sFrontier),
                new Action.Colonize(planet, fleet),
                new Action.Build(planet, 2, BuildingType.SHIPYARD),
                new Action.Research(new TechId("improvedExtraction")),
                new Action.Terraform(planet),
                new Action.BuildFleet(sHome, "cruiser"),
                new Action.MoveFleet(fleet, List.of(sHome, sFrontier), sFrontier),
                new Action.EstablishRoute(sHome, sFrontier, RouteKind.COMMERCIAL,
                        List.of(PhysicalResource.MINERALS, PhysicalResource.ENERGY), 12.0),
                new Action.SendMessage(fB, "Trade?"),
                new Action.ProposeTrade(fB,
                        new ResourceBundle(10, 0, 0, 0, 0),
                        new ResourceBundle(0, 8, 0, 0, 0),
                        Optional.of(5)),
                new Action.AcceptTrade(new MarketOrderId("offer-1")),
                new Action.DeclineTrade(new MarketOrderId("offer-2")),
                new Action.WithdrawTrade(new MarketOrderId("offer-3")),
                new Action.ProposeTreaty(fB, TreatyType.NON_AGGRESSION,
                        Map.of("clause", "no-raid"), Optional.of(50)),
                new Action.AcceptTreaty(new TreatyId("treaty-1")),
                new Action.DeclineTreaty(new TreatyId("treaty-2")),
                new Action.BreakTreaty(new TreatyId("treaty-3")),
                new Action.Tribute(fB, new ResourceBundle(0, 0, 5, 0, 0)),
                new Action.DemandTribute(fB, new ResourceBundle(0, 0, 0, 3, 0),
                        Optional.of("or war")),
                new Action.DeclareWar(fB),
                new Action.Attack(fleet, new AttackTarget.OnSystem(sFrontier)),
                new Action.Attack(fleet, new AttackTarget.OnFleet(new FleetId("enemy-fleet"))),
                new Action.Blockade(fleet, new BlockadeTarget.OnRoute(new RouteId("route-1"))),
                new Action.Blockade(fleet, new BlockadeTarget.OnSystem(sFrontier)),
                new Action.Raid(fleet, new RouteId("route-2")),
                new Action.Espionage(fB, EspionageOperation.STEAL_INTEL),
                new Action.Hold());

        List<AgentResponse.Message> messages = List.of(
                new AgentResponse.Message(fB, "Greetings from faction A."),
                new AgentResponse.Message(fA, "Note to self."));

        return AgentResponse.now(messages, actions);
    }

    @Test
    void agentResponseWithManyVariantsRoundTrips() throws Exception {
        AgentResponse original = sampleResponse();

        String json = MAPPER.writeValueAsString(original);
        AgentResponse roundTripped = MAPPER.readValue(json, AgentResponse.class);

        assertEquals(original, roundTripped,
                "record equals() must survive a JSON round-trip across every variant");
        assertEquals(AgentResponse.CURRENT_SCHEMA_VERSION, roundTripped.schemaVersion());
    }

    @Test
    void discriminatorDeserialisesToConcreteVariant() throws Exception {
        String json = "{\"type\":\"Explore\",\"targetSystem\":\"sys-x\"}";
        Action action = MAPPER.readValue(json, Action.class);
        assertInstanceOf(Action.Explore.class, action);
        assertEquals(new SystemId("sys-x"), ((Action.Explore) action).targetSystem());
    }

    @Test
    void polymorphicAttackTargetRoundTripsBothShapes() throws Exception {
        Action onSystem = new Action.Attack(new FleetId("f"),
                new AttackTarget.OnSystem(new SystemId("s")));
        Action onFleet = new Action.Attack(new FleetId("f"),
                new AttackTarget.OnFleet(new FleetId("e")));

        assertEquals(onSystem, MAPPER.readValue(MAPPER.writeValueAsString(onSystem), Action.class));
        assertEquals(onFleet, MAPPER.readValue(MAPPER.writeValueAsString(onFleet), Action.class));
    }
}
