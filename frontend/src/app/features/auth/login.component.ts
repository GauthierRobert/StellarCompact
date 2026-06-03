import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

/** Regex that must match the backend username validation rule (^[A-Za-z0-9_.-]{1,32}$). */
const USERNAME_REGEX = /^[A-Za-z0-9_.\-]{1,32}$/;

/**
 * Login component -- username-only dev auth (feat/dev-jwt-auth).
 *
 * Mounted at /login. On successful POST /api/auth/login the user is bounced
 * to the returnUrl query param (default: /me). Client-side regex validation
 * mirrors the backend rule so the user gets immediate feedback before the
 * round-trip.
 *
 * Constraints (angular21-signals skill):
 * - Standalone component; no NgModules.
 * - All state in signals; zoneless CD.
 * - inject() not constructor DI.
 */
@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="login-root">
      <div class="login-box">
        <div class="login-title">STELLAR COMPACT</div>
        <div class="login-subtitle">Commander Identification</div>

        <form class="login-form" (ngSubmit)="onSubmit()" novalidate>
          <label class="login-label" for="username-input">Username</label>
          <input
            id="username-input"
            class="login-input"
            [class.login-input--error]="!!validationError()"
            type="text"
            autocomplete="username"
            autocapitalize="none"
            spellcheck="false"
            placeholder="e.g. alice"
            [ngModel]="usernameValue()"
            (ngModelChange)="onUsernameChange($event)"
            name="username"
            [disabled]="loading()"
            aria-describedby="login-error"
          />

          @if (validationError()) {
            <div id="login-error" class="login-error" role="alert" aria-live="polite">
              {{ validationError() }}
            </div>
          }

          <button
            type="submit"
            class="login-btn"
            [disabled]="loading() || !canSubmit()"
          >
            @if (loading()) {
              <span class="login-spinner" aria-hidden="true"></span>
              Authenticating...
            } @else {
              Enter
            }
          </button>
        </form>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      background: #000308;
      font-family: 'Courier New', monospace;
    }
    .login-root {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
    }
    .login-box {
      text-align: center;
      padding: 40px 48px;
      border: 1px solid rgba(80, 180, 255, 0.2);
      border-radius: 6px;
      background: rgba(0, 3, 10, 0.85);
      min-width: 320px;
      max-width: 400px;
      width: 100%;
    }
    .login-title {
      font-size: 13px;
      letter-spacing: 4px;
      color: rgba(80, 180, 255, 0.7);
      text-transform: uppercase;
      margin-bottom: 6px;
    }
    .login-subtitle {
      font-size: 10px;
      letter-spacing: 1.5px;
      color: rgba(80, 180, 255, 0.4);
      text-transform: uppercase;
      margin-bottom: 28px;
    }
    .login-form {
      display: flex;
      flex-direction: column;
      gap: 10px;
      text-align: left;
    }
    .login-label {
      font-size: 9px;
      letter-spacing: 2px;
      text-transform: uppercase;
      color: rgba(80, 180, 255, 0.5);
    }
    .login-input {
      width: 100%;
      box-sizing: border-box;
      background: rgba(0, 3, 10, 0.9);
      border: 1px solid rgba(80, 180, 255, 0.25);
      border-radius: 3px;
      color: #b0d8f0;
      font-family: 'Courier New', monospace;
      font-size: 13px;
      padding: 8px 10px;
      outline: none;
      transition: border-color 0.15s;
    }
    .login-input:focus {
      border-color: rgba(80, 180, 255, 0.6);
    }
    .login-input--error {
      border-color: rgba(240, 96, 96, 0.7);
    }
    .login-input:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .login-error {
      font-size: 10px;
      color: #f06060;
      min-height: 14px;
    }
    .login-btn {
      margin-top: 8px;
      width: 100%;
      padding: 9px 0;
      background: rgba(80, 180, 255, 0.12);
      border: 1px solid rgba(80, 180, 255, 0.35);
      border-radius: 3px;
      color: #b0d8f0;
      font-family: 'Courier New', monospace;
      font-size: 11px;
      letter-spacing: 2px;
      text-transform: uppercase;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      transition: background 0.15s, border-color 0.15s;
    }
    .login-btn:hover:not(:disabled) {
      background: rgba(80, 180, 255, 0.2);
      border-color: rgba(80, 180, 255, 0.55);
    }
    .login-btn:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
    .login-spinner {
      display: inline-block;
      width: 12px;
      height: 12px;
      border: 2px solid rgba(80, 180, 255, 0.2);
      border-top-color: rgba(80, 180, 255, 0.8);
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
  `],
})
export class LoginComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** Current value of the username text field. */
  readonly usernameValue = signal<string>('');
  /** True while the login HTTP call is in flight. */
  readonly loading = signal<boolean>(false);
  /** Inline validation / API error message, or null when clean. */
  readonly validationError = signal<string | null>(null);

  /** True when the field passes the regex and we are not already loading. */
  readonly canSubmit = computed(
    () => USERNAME_REGEX.test(this.usernameValue()) && !this.loading(),
  );

  ngOnInit(): void {
    // Skip straight to the destination if already authenticated.
    if (this.auth.isAuthenticated()) {
      const returnUrl =
        this.route.snapshot.queryParamMap.get('returnUrl') ?? '/me';
      void this.router.navigateByUrl(returnUrl);
    }
  }

  onUsernameChange(value: string): void {
    this.usernameValue.set(value);
    // Clear the error as the user edits so they get fresh feedback.
    if (this.validationError()) {
      this.validationError.set(null);
    }
  }

  async onSubmit(): Promise<void> {
    const username = this.usernameValue().trim();

    // Client-side guard -- mirrors backend regex to give immediate feedback.
    if (!USERNAME_REGEX.test(username)) {
      this.validationError.set(
        'Username must be 1-32 characters: letters, digits, _ . -',
      );
      return;
    }

    this.loading.set(true);
    this.validationError.set(null);

    try {
      await this.auth.login(username);
      const returnUrl =
        this.route.snapshot.queryParamMap.get('returnUrl') ?? '/me';
      void this.router.navigateByUrl(returnUrl);
    } catch (err: unknown) {
      const status = (err as { status?: number }).status;
      if (status === 400) {
        this.validationError.set(
          'Invalid username. Use 1-32 characters: letters, digits, _ . -',
        );
      } else {
        this.validationError.set(
          'Could not reach the authentication service. Try again.',
        );
      }
    } finally {
      this.loading.set(false);
    }
  }
}
