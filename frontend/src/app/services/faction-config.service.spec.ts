/**
 * Unit tests for FactionConfigService.
 *
 * Uses HttpTestingController from @angular/common/http/testing to intercept
 * HTTP requests without a real network; verifies that:
 *  - attach() sends POST with correct body + X-Owner-Token header
 *  - getConfig() sends GET with correct path + header
 *  - updateDirectives() sends PATCH; throws DirectivesLockedException on 409
 */
import { TestBed } from '@angular/core/testing';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  FactionConfigService,
  OWNER_TOKEN,
  AttachFactionResponse,
  FactionConfigView,
} from './faction-config.service';

describe('FactionConfigService', () => {
  let svc: FactionConfigService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        FactionConfigService,
        { provide: OWNER_TOKEN, useValue: 'test-token-abc' },
      ],
    });
    svc = TestBed.inject(FactionConfigService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // ---- attach ----

  it('attach() POSTs to /api/games/{gameId}/factions with owner header', async () => {
    const promise = svc.attach('game-1', {
      personaPreset: 'expansionist',
      goals: ['Expand fast'],
      hardConstraints: [],
      modelTier: 'SMALL',
    });

    const req = http.expectOne('/api/games/game-1/factions');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Owner-Token')).toBe('test-token-abc');
    expect(req.request.body).toEqual({
      personaPreset: 'expansionist',
      goals: ['Expand fast'],
      hardConstraints: [],
      modelTier: 'SMALL',
    });

    const response: AttachFactionResponse = {
      factionId: 'game-1:faction-1',
      gameId: 'game-1',
      seatId: 'faction-1',
    };
    req.flush(response, { status: 201, statusText: 'Created' });

    const result = await promise;
    expect(result.factionId).toBe('game-1:faction-1');
    expect(result.seatId).toBe('faction-1');
  });

  // ---- getConfig ----

  it('getConfig() GETs /api/factions/{factionId} with owner header', async () => {
    const promise = svc.getConfig('game-1:faction-1');

    const req = http.expectOne('/api/factions/game-1:faction-1');
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.get('X-Owner-Token')).toBe('test-token-abc');

    const view: FactionConfigView = {
      factionId: 'game-1:faction-1',
      gameId: 'game-1',
      seatId: 'faction-1',
      configured: true,
      owner: true,
      persona: 'expansionist',
      goals: ['Expand fast'],
      hardConstraints: [],
      modelTier: 'SMALL',
    };
    req.flush(view);

    const result = await promise;
    expect(result.owner).toBe(true);
    expect(result.persona).toBe('expansionist');
  });

  it('getConfig() returns redacted view for non-owner', async () => {
    const promise = svc.getConfig('game-1:faction-2');

    const req = http.expectOne('/api/factions/game-1:faction-2');
    const redacted: FactionConfigView = {
      factionId: 'game-1:faction-2',
      gameId: 'game-1',
      seatId: 'faction-2',
      configured: true,
      owner: false,
      persona: null,
      goals: [],
      hardConstraints: [],
      modelTier: null,
    };
    req.flush(redacted);

    const result = await promise;
    expect(result.owner).toBe(false);
    expect(result.persona).toBeNull();
  });

  // ---- updateDirectives ----

  it('updateDirectives() PATCHes with updated directives', async () => {
    const promise = svc.updateDirectives('game-1:faction-1', {
      goals: ['New goal'],
      modelTier: 'MEDIUM',
    });

    const req = http.expectOne('/api/factions/game-1:faction-1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.headers.get('X-Owner-Token')).toBe('test-token-abc');
    expect(req.request.body).toEqual({ goals: ['New goal'], modelTier: 'MEDIUM' });

    const updated: FactionConfigView = {
      factionId: 'game-1:faction-1',
      gameId: 'game-1',
      seatId: 'faction-1',
      configured: true,
      owner: true,
      persona: 'expansionist',
      goals: ['New goal'],
      hardConstraints: [],
      modelTier: 'MEDIUM',
    };
    req.flush(updated);

    const result = await promise;
    expect(result.modelTier).toBe('MEDIUM');
  });

  it('updateDirectives() throws DirectivesLockedException on 409', async () => {
    const promise = svc.updateDirectives('game-1:faction-1', {
      goals: ['Blocked goal'],
    });

    const req = http.expectOne('/api/factions/game-1:faction-1');
    req.flush({ message: 'Match is RUNNING' }, { status: 409, statusText: 'Conflict' });

    await expect(promise).rejects.toMatchObject({ name: 'DirectivesLockedException' });
  });

  it('updateDirectives() re-throws non-409 errors unchanged', async () => {
    const promise = svc.updateDirectives('game-1:faction-1', {});

    const req = http.expectOne('/api/factions/game-1:faction-1');
    req.flush({ message: 'Forbidden' }, { status: 403, statusText: 'Forbidden' });

    await expect(promise).rejects.toBeDefined();
  });
});
