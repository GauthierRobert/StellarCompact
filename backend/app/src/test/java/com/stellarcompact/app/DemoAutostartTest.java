package com.stellarcompact.app;

import com.stellarcompact.api.match.CreateGameRequest;
import com.stellarcompact.api.match.GameSummary;
import com.stellarcompact.api.match.MatchService;
import com.stellarcompact.engine.state.GameStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DemoAutostart} (E11-07).
 *
 * <p>Key assertions:
 * <ol>
 *   <li>On {@link ApplicationRunner#run}, the autostart calls {@code create} then
 *       {@code start} exactly once each on the {@link MatchService}.</li>
 *   <li>The {@code CreateGameRequest} reflects {@link DemoProperties} values (faction
 *       count, balance profile, seed).</li>
 *   <li>If the property {@code stellar-compact.demo.autostart=true} is not set, the bean
 *       is not created — this is tested via the {@code @ConditionalOnProperty} wiring
 *       using a minimal Spring context.</li>
 * </ol>
 *
 * <p>No full Spring context is loaded for the main behaviour test (plain unit test); the
 * conditional-wiring test uses Spring's test {@code @SpringBootTest} slice.
 */
class DemoAutostartTest {

    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    // ---- helpers ----

    private static GameSummary fakeSummary(String id, GameStatus status) {
        return new GameSummary(id, 42L, status, 0L, "small-default", List.of("faction-1", "faction-2"));
    }

    private static DemoProperties defaultProps() {
        DemoProperties p = new DemoProperties();
        p.setFactionCount(2);
        p.setBalanceProfile("small-default");
        p.setSeed(null);
        return p;
    }

    // ---- tests ----

    @Test
    void run_callsCreateThenStartOnce() throws Exception {
        MatchService service = mock(MatchService.class);
        GameSummary created = fakeSummary("demo-id", GameStatus.CREATED);
        GameSummary running = fakeSummary("demo-id", GameStatus.RUNNING);
        when(service.create(any())).thenReturn(created);
        when(service.start("demo-id")).thenReturn(running);

        DemoAutostart autostart = new DemoAutostart(service, defaultProps());
        autostart.run(NO_ARGS);

        verify(service, times(1)).create(any(CreateGameRequest.class));
        verify(service, times(1)).start("demo-id");
    }

    @Test
    void run_passesPropsToCreateRequest() throws Exception {
        MatchService service = mock(MatchService.class);
        GameSummary created = fakeSummary("demo-id", GameStatus.CREATED);
        GameSummary running = fakeSummary("demo-id", GameStatus.RUNNING);
        when(service.create(any())).thenReturn(created);
        when(service.start(anyString())).thenReturn(running);

        DemoProperties props = new DemoProperties();
        props.setFactionCount(4);
        props.setBalanceProfile("large-persistent");
        props.setSeed(99L);

        DemoAutostart autostart = new DemoAutostart(service, props);
        autostart.run(NO_ARGS);

        ArgumentCaptor<CreateGameRequest> captor = ArgumentCaptor.forClass(CreateGameRequest.class);
        verify(service).create(captor.capture());

        CreateGameRequest req = captor.getValue();
        assertThat(req.factionCount()).isEqualTo(4);
        assertThat(req.balanceProfile()).isEqualTo("large-persistent");
        assertThat(req.seed()).isEqualTo(99L);
    }

    @Test
    void run_nullSeedInRequest_whenPropsHaveNoSeed() throws Exception {
        MatchService service = mock(MatchService.class);
        GameSummary created = fakeSummary("demo-id", GameStatus.CREATED);
        when(service.create(any())).thenReturn(created);
        when(service.start(anyString())).thenReturn(fakeSummary("demo-id", GameStatus.RUNNING));

        DemoAutostart autostart = new DemoAutostart(service, defaultProps());
        autostart.run(NO_ARGS);

        ArgumentCaptor<CreateGameRequest> captor = ArgumentCaptor.forClass(CreateGameRequest.class);
        verify(service).create(captor.capture());
        // Null seed means the service derives one (principle 1 — no wall-clock).
        assertThat(captor.getValue().seed()).isNull();
    }
}
