/**
 * Unit tests for ConfigComponent.
 *
 * Tests form validation, persona preset selection, submit flows (create + edit),
 * the 409 locked-while-running banner, and error message display — all without
 * a real HTTP call (FactionConfigService is mocked via vi.fn()).
 */
import { TestBed } from '@angular/core/testing';
import { ComponentFixture } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideZonelessChangeDetection } from '@angular/core';
import { vi } from 'vitest';
import {
  ConfigComponent,
  PERSONA_PRESETS,
  validate,
  parseLines,
} from './config.component';
import {
  FactionConfigService,
  DirectivesLockedException,
  AttachFactionResponse,
  FactionConfigView,
} from '../../services/faction-config.service';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createMockService() {
  return {
    attach: vi.fn(),
    getConfig: vi.fn(),
    updateDirectives: vi.fn(),
  };
}

// ---------------------------------------------------------------------------
// Pure helper tests
// ---------------------------------------------------------------------------

describe('parseLines()', () => {
  it('splits on newlines and trims', () => {
    expect(parseLines('  goal one  \n  goal two\n\n')).toEqual([
      'goal one',
      'goal two',
    ]);
  });
  it('returns empty array for blank input', () => {
    expect(parseLines('')).toEqual([]);
    expect(parseLines('   \n  \n')).toEqual([]);
  });
});

describe('validate()', () => {
  const base = () => ({
    gameId: 'game-1',
    personaPresetId: 'expansionist',
    customPersona: '',
    goalsRaw: 'Expand fast',
    hardConstraintsRaw: '',
    modelTier: 'SMALL' as const,
  });

  it('returns no errors for a valid form', () => {
    expect(Object.keys(validate(base())).length).toBe(0);
  });

  it('requires gameId', () => {
    expect(validate({ ...base(), gameId: '' }).gameId).toBeTruthy();
  });

  it('requires customPersona when custom is selected', () => {
    expect(
      validate({ ...base(), personaPresetId: 'custom', customPersona: '' }).persona,
    ).toBeTruthy();
  });

  it('passes when customPersona is filled for custom preset', () => {
    expect(
      validate({ ...base(), personaPresetId: 'custom', customPersona: 'My persona' }).persona,
    ).toBeUndefined();
  });

  it('requires at least one goal', () => {
    expect(validate({ ...base(), goalsRaw: '  \n  ' }).goals).toBeTruthy();
  });
});

// ---------------------------------------------------------------------------
// Component tests
// ---------------------------------------------------------------------------

describe('ConfigComponent', () => {
  let fixture: ComponentFixture<ConfigComponent>;
  let component: ConfigComponent;
  let mockSvc: ReturnType<typeof createMockService>;

  beforeEach(async () => {
    mockSvc = createMockService();

    await TestBed.configureTestingModule({
      imports: [ConfigComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: FactionConfigService, useValue: mockSvc },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ConfigComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders in create mode by default', () => {
    expect(component.mode()).toBe('create');
  });

  it('shows the preset grid', () => {
    const cards = fixture.debugElement.queryAll(By.css('.sc-preset-card'));
    expect(cards.length).toBe(PERSONA_PRESETS.length);
  });

  it('does not show validation errors before first submit attempt', () => {
    const errors = fixture.debugElement.queryAll(By.css('.sc-error'));
    expect(errors.length).toBe(0);
  });

  it('shows validation errors on submit with empty form', async () => {
    component.patchField('gameId', '');
    component.patchField('goalsRaw', '');
    await component.submit();
    fixture.detectChanges();

    const errors = fixture.debugElement.queryAll(By.css('.sc-error'));
    expect(errors.length).toBeGreaterThan(0);
  });

  it('submit calls svc.attach in create mode', async () => {
    const resp: AttachFactionResponse = {
      factionId: 'g1:faction-1',
      gameId: 'g1',
      seatId: 'faction-1',
    };
    mockSvc.attach.mockResolvedValue(resp);

    component.patchField('gameId', 'g1');
    component.patchField('goalsRaw', 'Expand fast');
    await component.submit();
    fixture.detectChanges();

    expect(mockSvc.attach).toHaveBeenCalledWith(
      'g1',
      expect.objectContaining({ goals: ['Expand fast'] }),
    );
    expect(component.attachedFactionId()).toBe('g1:faction-1');
    expect(component.mode()).toBe('edit');
    expect(component.successMsg()).toContain('g1:faction-1');
  });

  it('submit calls svc.updateDirectives in edit mode', async () => {
    const view: FactionConfigView = {
      factionId: 'g1:faction-1',
      gameId: 'g1',
      seatId: 'faction-1',
      configured: true,
      owner: true,
      persona: 'expansionist',
      goals: ['Old goal'],
      hardConstraints: [],
      modelTier: 'SMALL',
    };
    component.loadForEdit(view);
    fixture.detectChanges();

    mockSvc.updateDirectives.mockResolvedValue({ ...view, goals: ['New goal'] });

    component.patchField('goalsRaw', 'New goal');
    await component.submit();
    fixture.detectChanges();

    expect(mockSvc.updateDirectives).toHaveBeenCalledWith(
      'g1:faction-1',
      expect.objectContaining({ goals: ['New goal'] }),
    );
    expect(component.successMsg()).toBeTruthy();
  });

  it('shows locked-while-running banner on DirectivesLockedException', async () => {
    const view: FactionConfigView = {
      factionId: 'g1:faction-1',
      gameId: 'g1',
      seatId: 'faction-1',
      configured: true,
      owner: true,
      persona: 'expansionist',
      goals: ['Goal'],
      hardConstraints: [],
      modelTier: 'SMALL',
    };
    component.loadForEdit(view);
    fixture.detectChanges();

    mockSvc.updateDirectives.mockRejectedValue(new DirectivesLockedException());

    component.patchField('goalsRaw', 'Goal');
    await component.submit();
    fixture.detectChanges();

    expect(component.lockedWhileRunning()).toBe(true);
    const banner = fixture.debugElement.query(By.css('.sc-banner--locked'));
    expect(banner).toBeTruthy();
  });

  it('shows generic error banner on unexpected error', async () => {
    const view: FactionConfigView = {
      factionId: 'g1:faction-1',
      gameId: 'g1',
      seatId: 'faction-1',
      configured: true,
      owner: true,
      persona: 'expansionist',
      goals: ['Goal'],
      hardConstraints: [],
      modelTier: 'SMALL',
    };
    component.loadForEdit(view);
    fixture.detectChanges();

    mockSvc.updateDirectives.mockRejectedValue(new Error('Server exploded'));

    component.patchField('goalsRaw', 'Goal');
    await component.submit();
    fixture.detectChanges();

    expect(component.errorMsg()).toContain('Server exploded');
    const banner = fixture.debugElement.query(By.css('.sc-banner--error'));
    expect(banner).toBeTruthy();
  });

  it('loadForEdit in non-owner mode does not populate sensitive fields', () => {
    const redactedView: FactionConfigView = {
      factionId: 'g1:faction-2',
      gameId: 'g1',
      seatId: 'faction-2',
      configured: true,
      owner: false,
      persona: null,
      goals: [],
      hardConstraints: [],
      modelTier: null,
    };
    component.loadForEdit(redactedView);
    // goalsRaw should remain blank — never fill fields from a redacted (non-owner) view
    expect(component.formState().goalsRaw).toBe('');
  });

  it('selecting custom preset reveals the textarea', async () => {
    component.selectPreset('custom');
    fixture.detectChanges();
    const textarea = fixture.debugElement.query(By.css('#customPersona'));
    expect(textarea).toBeTruthy();
  });
});
