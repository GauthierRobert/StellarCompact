package com.stellarcompact.persistence.repo;

import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.state.GameState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transactional tick commit + resume-from-log (board card E5-03; architecture 03
 * section 6 "Engine resolution is transactional against PostgreSQL: a tick either fully
 * commits or rolls back; the event log is append-only and ordered by tick. A paused/
 * crashed galaxy resumes from the last committed tick + event log.").
 *
 * <h2>The atomic boundary</h2>
 * {@link #commitTick} is one Spring {@code @Transactional} unit that bundles, for a single
 * resolved tick:
 * <ol>
 *   <li>the resolved active-set state ({@link GameStateRepository#save}: the game +
 *       faction upsert, the child-table delete-then-insert diff, and therefore every
 *       promotion/demotion of E5-02 - a system dropped from {@code state.systems()} is not
 *       re-inserted, a promoted system is), and</li>
 *   <li>the tick's public events appended in resolver-emission order
 *       ({@link EventLogRepository#appendTick}).</li>
 * </ol>
 * Both run in the <em>same</em> transaction because {@code save} and {@code appendTick} are
 * invoked from inside this {@code @Transactional} method (Spring's default {@code REQUIRED}
 * propagation joins the outer transaction rather than starting a nested one). Consequence:
 * if <em>anything</em> in the unit throws - a constraint violation while writing state, the
 * event log's tick-order trigger, a mid-commit failure - the whole tick rolls back. There is
 * never a half-written tick: no state rows for a tick whose events did not land, and no
 * orphan {@code event_log} rows for a tick whose state did not land.
 *
 * <p><b>Why this lives in persistence, keyed by engine types.</b> A tick's output is
 * {@code (GameState resolvedState, List<PublicEvent> events)} - both pure engine types (the
 * orchestrator's {@code TickResult} merely bundles these same two). Taking them directly
 * keeps persistence free of any orchestrator dependency (no module cycle) and keeps the
 * engine pure (it never sees Spring or a transaction). The orchestrator/api layer unwraps
 * its {@code TickResult} into {@code (resolvedState, events)} and calls this.
 *
 * <h2>Resume</h2>
 * {@link #resume} is the load path: it reconstructs the engine {@link GameState} at the last
 * committed tick straight from the active set (delegating to {@link GameStateRepository#load}).
 * Because the snapshot is persisted post-resolution and carries its own {@code tick}, the
 * reloaded state IS the last committed tick - the caller advances it to {@code tick + 1} and
 * resolves the next tick exactly as an uninterrupted run would (the event log is the
 * append-only public record; the authoritative resume state is the saved snapshot, not a
 * fold of events). Returns {@code null} for an unknown game.
 */
@Service
public class TickCommitService {

    private final GameStateRepository stateRepo;
    private final EventLogRepository eventRepo;

    public TickCommitService(GameStateRepository stateRepo, EventLogRepository eventRepo) {
        this.stateRepo = stateRepo;
        this.eventRepo = eventRepo;
    }

    /**
     * Atomically persist one resolved tick: the resolved active-set state and the tick's
     * public events, in a single transaction. Either the whole tick commits or it all rolls
     * back.
     *
     * @param gameId        the match id (the surrogate {@code game.id})
     * @param resolvedState the post-resolution snapshot to persist (carries its own
     *                      {@code tick}); never {@code null}
     * @param events        the tick's public events in resolver-emission order; may be empty
     *                      (a quiet tick still commits its state)
     */
    @Transactional
    public void commitTick(UUID gameId, GameState resolvedState, List<PublicEvent> events) {
        commitTick(gameId, resolvedState, events, null);
    }

    /**
     * Test/diagnostic seam for the atomicity guarantee: as {@link #commitTick(UUID, GameState,
     * List)} but runs {@code afterSave} <em>after</em> the active-set save and <em>before</em>
     * the event append, all inside the one transaction. A test injects a failure there to prove
     * a mid-commit throw rolls the whole tick back (state rows written by {@code save} must
     * vanish, and no event rows may exist). Production callers use the two-arg overload, which
     * passes {@code null}.
     */
    @Transactional
    public void commitTick(UUID gameId, GameState resolvedState, List<PublicEvent> events,
                           Runnable afterSave) {
        if (gameId == null) {
            throw new IllegalArgumentException("commitTick.gameId must be set");
        }
        if (resolvedState == null) {
            throw new IllegalArgumentException("commitTick.resolvedState must be set");
        }
        // 1. Persist the resolved active set (upsert + prune => promotions/demotions land).
        stateRepo.save(gameId, resolvedState);
        // Injected mid-commit failure point (tests only): a throw here must roll back step 1.
        if (afterSave != null) {
            afterSave.run();
        }
        // 2. Append this tick's events, in resolver-emission order, in the SAME transaction.
        eventRepo.appendTick(gameId, resolvedState.tick(), events);
    }

    /**
     * Reconstruct the engine {@link GameState} for {@code gameId} at the last committed tick,
     * so a paused/crashed galaxy can resume. Returns {@code null} if no such game exists.
     */
    @Transactional(readOnly = true)
    public GameState resume(UUID gameId) {
        if (gameId == null) {
            throw new IllegalArgumentException("resume.gameId must be set");
        }
        return stateRepo.load(gameId);
    }
}
