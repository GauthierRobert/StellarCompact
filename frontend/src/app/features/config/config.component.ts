import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  FactionConfigService,
  AttachFactionResponse,
  FactionConfigView,
  ModelTier,
  DirectivesLockedException,
} from '../../services/faction-config.service';

// ---------------------------------------------------------------------------
// Persona preset catalogue — non-exhaustive; user may choose "custom" instead.
// Names & descriptions are display-only; actual prompt text is assembled server-
// side from the SovereignConfig (principle 2 — frontend is untrusted).
// ---------------------------------------------------------------------------

export interface PersonaPreset {
  readonly id: string;
  readonly label: string;
  readonly description: string;
}

export const PERSONA_PRESETS: readonly PersonaPreset[] = [
  {
    id: 'expansionist',
    label: 'Expansionist',
    description:
      'Prioritises rapid territorial growth; colonises aggressively and contests frontier systems.',
  },
  {
    id: 'merchant',
    label: 'Merchant',
    description:
      'Builds wealth through trade pacts and commercial routes; avoids open conflict.',
  },
  {
    id: 'diplomat',
    label: 'Diplomat',
    description:
      'Pursues alliances and reputation; negotiates from a position of goodwill.',
  },
  {
    id: 'warlord',
    label: 'Warlord',
    description:
      'Projects military force; takes what it needs and breaks treaties when convenient.',
  },
  {
    id: 'isolationist',
    label: 'Isolationist',
    description:
      'Fortifies its own systems; engages diplomatically only when threatened.',
  },
  {
    id: 'custom',
    label: 'Custom',
    description: 'Write your own persona instructions.',
  },
] as const;

// ---------------------------------------------------------------------------
// Form state — plain signal-based model (no reactive forms dependency)
// ---------------------------------------------------------------------------

export interface ConfigFormState {
  gameId: string;
  personaPresetId: string;
  customPersona: string;
  goalsRaw: string; // newline-separated
  hardConstraintsRaw: string; // newline-separated
  modelTier: ModelTier;
}

/** Map form state to the REST body. Exported for unit tests. */
export function parseLines(raw: string): string[] {
  return raw
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.length > 0);
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

export interface FormErrors {
  gameId?: string;
  persona?: string;
  goals?: string;
  modelTier?: string;
}

export function validate(f: ConfigFormState): FormErrors {
  const errors: FormErrors = {};
  if (!f.gameId.trim()) errors.gameId = 'Game ID is required.';
  if (f.personaPresetId === 'custom' && !f.customPersona.trim()) {
    errors.persona = 'Custom persona text is required when Custom is selected.';
  }
  if (parseLines(f.goalsRaw).length === 0) {
    errors.goals = 'At least one goal is required.';
  }
  if (!f.modelTier) errors.modelTier = 'Model tier is required.';
  return errors;
}

// ---------------------------------------------------------------------------
// Screen mode — create a new config or edit directives for an existing one
// ---------------------------------------------------------------------------

type ScreenMode = 'create' | 'edit';

/**
 * Sovereign config screen — create/edit a Sovereign's persona, goals, hard
 * constraints, and model tier.
 *
 * Create mode: fills in all fields, POSTs to attach the config to a game.
 * Edit mode (activated when a factionId is pre-loaded): shows current config
 *   and sends PATCH for directive updates; surfaces the 409 "locked" state.
 *
 * Uses signal-based reactive state; standalone; no NgModules; zoneless-friendly.
 * Styling matches the HUD dark palette (monospace, dark bg, accent blues/greens).
 */
@Component({
  selector: 'app-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="sc-config">
      <header class="sc-config__header">
        <span class="sc-config__title">SOVEREIGN CONFIGURATION</span>
        <span class="sc-config__mode-badge">{{ mode() === 'create' ? 'NEW' : 'EDIT DIRECTIVES' }}</span>
      </header>

      <!-- Success banner -->
      @if (successMsg()) {
        <div class="sc-banner sc-banner--success" role="status">
          {{ successMsg() }}
        </div>
      }

      <!-- Error banner -->
      @if (errorMsg()) {
        <div class="sc-banner sc-banner--error" role="alert">
          {{ errorMsg() }}
        </div>
      }

      <!-- Locked banner (409 response) -->
      @if (lockedWhileRunning()) {
        <div class="sc-banner sc-banner--locked" role="alert">
          Directives are locked while the match is RUNNING. Pause or wait for the match to conclude before re-configuring.
        </div>
      }

      <form class="sc-config__form" (ngSubmit)="submit()" novalidate>

        <!-- Game ID (create mode only) -->
        @if (mode() === 'create') {
          <div class="sc-field" [class.sc-field--error]="errors().gameId">
            <label class="sc-label" for="gameId">Game ID</label>
            <input
              id="gameId"
              class="sc-input"
              type="text"
              placeholder="e.g. game-42"
              [(ngModel)]="formState().gameId"
              (ngModelChange)="patchField('gameId', $event)"
              name="gameId"
              autocomplete="off"
            />
            @if (errors().gameId) {
              <span class="sc-error">{{ errors().gameId }}</span>
            }
          </div>
        }

        <!-- Persona preset selector -->
        <div class="sc-field" [class.sc-field--error]="errors().persona">
          <span class="sc-label" role="group" aria-label="Persona Preset">Persona Preset</span>
          <div class="sc-preset-grid">
            @for (p of presets; track p.id) {
              <button
                type="button"
                class="sc-preset-card"
                [class.sc-preset-card--selected]="formState().personaPresetId === p.id"
                (click)="selectPreset(p.id)"
              >
                <span class="sc-preset-label">{{ p.label }}</span>
                <span class="sc-preset-desc">{{ p.description }}</span>
              </button>
            }
          </div>

          <!-- Custom persona textarea — visible only when custom is selected -->
          @if (formState().personaPresetId === 'custom') {
            <textarea
              id="customPersona"
              class="sc-textarea sc-textarea--persona"
              placeholder="Describe your Sovereign's personality, priorities, and style in plain text…"
              rows="4"
              [value]="formState().customPersona"
              (input)="patchField('customPersona', $any($event.target).value)"
              name="customPersona"
            ></textarea>
          }
          @if (errors().persona) {
            <span class="sc-error">{{ errors().persona }}</span>
          }
        </div>

        <!-- Goals -->
        <div class="sc-field" [class.sc-field--error]="errors().goals">
          <label class="sc-label" for="goals">
            Goals
            <span class="sc-hint">One per line — e.g. "Colonise 10 systems before tick 50"</span>
          </label>
          <textarea
            id="goals"
            class="sc-textarea"
            rows="5"
            placeholder="Expand to the galactic rim&#10;Achieve a trade pact with every faction&#10;Never lose a system once claimed"
            [value]="formState().goalsRaw"
            (input)="patchField('goalsRaw', $any($event.target).value)"
            name="goals"
          ></textarea>
          @if (errors().goals) {
            <span class="sc-error">{{ errors().goals }}</span>
          }
        </div>

        <!-- Hard constraints -->
        <div class="sc-field">
          <label class="sc-label" for="constraints">
            Hard Constraints
            <span class="sc-hint">One per line — the Sovereign will refuse to violate these</span>
          </label>
          <textarea
            id="constraints"
            class="sc-textarea"
            rows="4"
            placeholder="Never break a treaty&#10;Never declare war unprovoked&#10;Honour all defensive pacts"
            [value]="formState().hardConstraintsRaw"
            (input)="patchField('hardConstraintsRaw', $any($event.target).value)"
            name="constraints"
          ></textarea>
        </div>

        <!-- Model tier -->
        <div class="sc-field" [class.sc-field--error]="errors().modelTier">
          <span class="sc-label" role="group" aria-label="Model Tier">Model Tier</span>
          <div class="sc-tier-row">
            @for (tier of tiers; track tier.value) {
              <button
                type="button"
                class="sc-tier-btn"
                [class.sc-tier-btn--selected]="formState().modelTier === tier.value"
                (click)="patchField('modelTier', tier.value)"
              >
                <span class="sc-tier-label">{{ tier.label }}</span>
                <span class="sc-tier-desc">{{ tier.desc }}</span>
              </button>
            }
          </div>
          @if (errors().modelTier) {
            <span class="sc-error">{{ errors().modelTier }}</span>
          }
        </div>

        <!-- Submit -->
        <div class="sc-actions">
          <button
            type="submit"
            class="sc-btn sc-btn--primary"
            [disabled]="submitting()"
          >
            @if (submitting()) {
              SENDING…
            } @else if (mode() === 'create') {
              ATTACH SOVEREIGN
            } @else {
              SAVE DIRECTIVES
            }
          </button>

          @if (attachedFactionId()) {
            <span class="sc-faction-id">
              Faction: <code>{{ attachedFactionId() }}</code>
            </span>
          }
        </div>

      </form>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      min-height: 100vh;
      background: #00060e;
      color: #b0d8f0;
      font-family: 'Courier New', monospace;
    }

    .sc-config {
      max-width: 760px;
      margin: 0 auto;
      padding: 32px 20px 64px;
    }

    /* Header */
    .sc-config__header {
      display: flex;
      align-items: baseline;
      gap: 14px;
      margin-bottom: 28px;
      border-bottom: 1px solid rgba(80,180,255,0.2);
      padding-bottom: 12px;
    }
    .sc-config__title {
      font-size: 16px;
      font-weight: bold;
      letter-spacing: 3px;
      color: #50b4ff;
    }
    .sc-config__mode-badge {
      font-size: 10px;
      letter-spacing: 2px;
      color: rgba(80,180,255,0.5);
      border: 1px solid rgba(80,180,255,0.25);
      padding: 2px 6px;
      border-radius: 2px;
    }

    /* Banners */
    .sc-banner {
      padding: 10px 14px;
      margin-bottom: 18px;
      border-radius: 2px;
      font-size: 12px;
      letter-spacing: 0.5px;
      border-left: 3px solid;
    }
    .sc-banner--success {
      background: rgba(74,214,160,0.1);
      border-color: #4ad6a0;
      color: #4ad6a0;
    }
    .sc-banner--error {
      background: rgba(240,96,96,0.1);
      border-color: #f06060;
      color: #f06060;
    }
    .sc-banner--locked {
      background: rgba(255,180,40,0.08);
      border-color: #ffb428;
      color: #ffb428;
    }

    /* Form fields */
    .sc-field {
      margin-bottom: 24px;
    }
    .sc-field--error .sc-input,
    .sc-field--error .sc-textarea {
      border-color: #f06060;
    }
    .sc-label {
      display: block;
      font-size: 11px;
      letter-spacing: 1.5px;
      text-transform: uppercase;
      color: rgba(80,180,255,0.7);
      margin-bottom: 8px;
    }
    .sc-hint {
      font-size: 10px;
      color: rgba(80,180,255,0.35);
      text-transform: none;
      letter-spacing: 0.3px;
      margin-left: 8px;
    }
    .sc-input,
    .sc-textarea {
      width: 100%;
      background: rgba(0,30,55,0.6);
      border: 1px solid rgba(80,180,255,0.2);
      border-radius: 3px;
      color: #e8f4ff;
      font-family: 'Courier New', monospace;
      font-size: 13px;
      padding: 8px 10px;
      box-sizing: border-box;
      transition: border-color 0.15s;
      resize: vertical;
    }
    .sc-input:focus,
    .sc-textarea:focus {
      outline: none;
      border-color: rgba(80,180,255,0.6);
    }
    .sc-textarea--persona {
      background: rgba(20,10,50,0.5);
      border-color: rgba(160,100,255,0.3);
    }
    .sc-error {
      display: block;
      font-size: 11px;
      color: #f06060;
      margin-top: 5px;
    }

    /* Persona preset grid */
    .sc-preset-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 8px;
      margin-bottom: 10px;
    }
    .sc-preset-card {
      background: rgba(0,30,55,0.5);
      border: 1px solid rgba(80,180,255,0.15);
      border-radius: 3px;
      padding: 10px 12px;
      text-align: left;
      cursor: pointer;
      transition: border-color 0.12s, background 0.12s;
      color: #b0d8f0;
    }
    .sc-preset-card:hover {
      border-color: rgba(80,180,255,0.45);
      background: rgba(0,40,70,0.7);
    }
    .sc-preset-card--selected {
      border-color: #50b4ff;
      background: rgba(10,50,90,0.8);
    }
    .sc-preset-label {
      display: block;
      font-size: 12px;
      font-weight: bold;
      color: #e8f4ff;
      letter-spacing: 0.5px;
      margin-bottom: 4px;
    }
    .sc-preset-desc {
      display: block;
      font-size: 10px;
      color: rgba(176,216,240,0.6);
      line-height: 1.4;
    }

    /* Model tier buttons */
    .sc-tier-row {
      display: flex;
      gap: 10px;
    }
    .sc-tier-btn {
      flex: 1;
      background: rgba(0,30,55,0.5);
      border: 1px solid rgba(80,180,255,0.15);
      border-radius: 3px;
      padding: 10px 12px;
      cursor: pointer;
      text-align: center;
      transition: border-color 0.12s, background 0.12s;
      color: #b0d8f0;
    }
    .sc-tier-btn:hover {
      border-color: rgba(80,180,255,0.45);
    }
    .sc-tier-btn--selected {
      border-color: #4ad6a0;
      background: rgba(0,50,30,0.6);
    }
    .sc-tier-label {
      display: block;
      font-size: 12px;
      font-weight: bold;
      color: #e8f4ff;
      letter-spacing: 1px;
      margin-bottom: 3px;
    }
    .sc-tier-desc {
      display: block;
      font-size: 10px;
      color: rgba(176,216,240,0.55);
    }

    /* Actions row */
    .sc-actions {
      display: flex;
      align-items: center;
      gap: 20px;
      margin-top: 8px;
    }
    .sc-btn {
      padding: 10px 24px;
      border: 1px solid;
      border-radius: 2px;
      font-family: 'Courier New', monospace;
      font-size: 12px;
      letter-spacing: 2px;
      cursor: pointer;
      transition: background 0.12s, color 0.12s;
    }
    .sc-btn--primary {
      background: rgba(80,180,255,0.12);
      border-color: #50b4ff;
      color: #50b4ff;
    }
    .sc-btn--primary:hover:not(:disabled) {
      background: rgba(80,180,255,0.25);
    }
    .sc-btn--primary:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
    .sc-faction-id {
      font-size: 11px;
      color: #4ad6a0;
    }
    .sc-faction-id code {
      font-family: 'Courier New', monospace;
      color: #4ad6a0;
    }
  `],
})
export class ConfigComponent {
  // ---------------------------------------------------------------------------
  // Public static data (template iterables)
  // ---------------------------------------------------------------------------
  readonly presets = PERSONA_PRESETS;

  readonly tiers: { value: ModelTier; label: string; desc: string }[] = [
    { value: 'SMALL', label: 'SMALL', desc: 'Fast & economical' },
    { value: 'MEDIUM', label: 'MEDIUM', desc: 'Balanced capability' },
    { value: 'LARGE', label: 'LARGE', desc: 'Maximum reasoning' },
  ];

  // ---------------------------------------------------------------------------
  // Services
  // ---------------------------------------------------------------------------
  private readonly svc = inject(FactionConfigService);

  // ---------------------------------------------------------------------------
  // Screen state
  // ---------------------------------------------------------------------------

  /** Whether we are creating (attach) or editing directives. */
  readonly mode = signal<ScreenMode>('create');

  /** The factionId returned after a successful attach (create mode). */
  readonly attachedFactionId = signal<string | null>(null);

  /** Loaded config view when in edit mode. */
  readonly loadedView = signal<FactionConfigView | null>(null);

  readonly submitting = signal(false);
  readonly successMsg = signal<string | null>(null);
  readonly errorMsg = signal<string | null>(null);
  readonly lockedWhileRunning = signal(false);

  // ---------------------------------------------------------------------------
  // Form state — signal-based, validated on each submission attempt
  // ---------------------------------------------------------------------------

  private readonly _formState = signal<ConfigFormState>({
    gameId: '',
    personaPresetId: 'expansionist',
    customPersona: '',
    goalsRaw: '',
    hardConstraintsRaw: '',
    modelTier: 'SMALL',
  });

  readonly formState = this._formState.asReadonly();

  private readonly _touched = signal(false);

  readonly errors = computed<FormErrors>(() => {
    if (!this._touched()) return {};
    return validate(this._formState());
  });

  readonly isValid = computed(
    () => Object.keys(validate(this._formState())).length === 0,
  );

  // ---------------------------------------------------------------------------
  // Form interactions
  // ---------------------------------------------------------------------------

  patchField<K extends keyof ConfigFormState>(
    field: K,
    value: ConfigFormState[K],
  ): void {
    this._formState.update((s) => ({ ...s, [field]: value }));
    // Clear outcome banners on user edit
    this.successMsg.set(null);
    this.errorMsg.set(null);
    this.lockedWhileRunning.set(false);
  }

  selectPreset(id: string): void {
    this.patchField('personaPresetId', id);
  }

  // ---------------------------------------------------------------------------
  // Public API for loading an existing config into edit mode
  // ---------------------------------------------------------------------------

  /**
   * Load an existing faction config into the form (edit mode).
   * Called programmatically (e.g. by a parent route or an integration test).
   */
  loadForEdit(view: FactionConfigView): void {
    this.mode.set('edit');
    this.loadedView.set(view);
    this.attachedFactionId.set(view.factionId);
    // Only populate editable fields when the requester is the owner; otherwise
    // the fields are redacted (principle: never render another faction's hidden state).
    if (view.owner) {
      const presetId =
        PERSONA_PRESETS.find((p) => p.id === view.persona) ? view.persona ?? 'custom' : 'custom';
      const customPersona =
        PERSONA_PRESETS.find((p) => p.id === presetId) && presetId !== 'custom'
          ? ''
          : (view.persona ?? '');
      this._formState.set({
        gameId: view.gameId,
        personaPresetId: presetId,
        customPersona,
        goalsRaw: view.goals.join('\n'),
        hardConstraintsRaw: view.hardConstraints.join('\n'),
        modelTier: view.modelTier ?? 'SMALL',
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Submit
  // ---------------------------------------------------------------------------

  async submit(): Promise<void> {
    this._touched.set(true);
    if (!this.isValid()) return;

    this.submitting.set(true);
    this.successMsg.set(null);
    this.errorMsg.set(null);
    this.lockedWhileRunning.set(false);

    const s = this._formState();
    const goals = parseLines(s.goalsRaw);
    const hardConstraints = parseLines(s.hardConstraintsRaw);

    try {
      if (this.mode() === 'create') {
        const body = {
          ...(s.personaPresetId === 'custom'
            ? { customPersona: s.customPersona.trim() }
            : { personaPreset: s.personaPresetId }),
          goals,
          hardConstraints,
          modelTier: s.modelTier,
        };
        const res: AttachFactionResponse = await this.svc.attach(
          s.gameId.trim(),
          body,
        );
        this.attachedFactionId.set(res.factionId);
        this.mode.set('edit');
        this.successMsg.set(
          `Sovereign attached — faction ID: ${res.factionId} (seat ${res.seatId})`,
        );
      } else {
        const factionId = this.attachedFactionId();
        if (!factionId) throw new Error('No faction ID loaded for edit.');
        const patch = {
          ...(s.personaPresetId === 'custom'
            ? { customPersona: s.customPersona.trim() }
            : {}),
          goals,
          hardConstraints,
          modelTier: s.modelTier,
        };
        await this.svc.updateDirectives(factionId, patch);
        this.successMsg.set('Directives saved.');
      }
    } catch (err) {
      if (err instanceof DirectivesLockedException) {
        this.lockedWhileRunning.set(true);
      } else {
        const msg =
          err instanceof Error ? err.message : 'An unexpected error occurred.';
        this.errorMsg.set(msg);
      }
    } finally {
      this.submitting.set(false);
    }
  }
}

