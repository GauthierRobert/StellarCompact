import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, ActivatedRoute } from '@angular/router';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { vi } from 'vitest';
import { LoginComponent } from './login.component';
import { AuthService } from '../../services/auth.service';

class AuthStub {
  readonly token = signal<string | null>(null);
  readonly username = signal<string | null>(null);
  readonly expiresAt = signal<string | null>(null);
  readonly isAuthenticated = signal<boolean>(false);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  login = vi.fn() as any;
  logout = vi.fn();
}

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let authStub: AuthStub;
  let routerSpy: { navigate: ReturnType<typeof vi.fn>; navigateByUrl: ReturnType<typeof vi.fn> };

  const activatedRouteStub = {
    snapshot: { queryParamMap: { get: (_key: string) => null } },
  };

  beforeEach(async () => {
    authStub = new AuthStub();
    routerSpy = {
      navigate: vi.fn().mockResolvedValue(true),
      navigateByUrl: vi.fn().mockResolvedValue(true),
    };

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AuthService, useValue: authStub },
        { provide: Router, useValue: routerSpy },
        { provide: ActivatedRoute, useValue: activatedRouteStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('creates', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('renders the username input', () => {
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('#username-input');
    expect(input).toBeTruthy();
  });

  it('submit button is disabled when username is empty', () => {
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('button[type=submit]') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
  });

  it('calls authService.login with the entered username on submit', async () => {
    authStub.login.mockResolvedValue(undefined);
    fixture.detectChanges();

    component.onUsernameChange('alice');
    fixture.detectChanges();

    await component.onSubmit();
    expect(authStub.login).toHaveBeenCalledWith('alice');
  });

  it('navigates to /me after successful login with no returnUrl', async () => {
    authStub.login.mockResolvedValue(undefined);
    component.onUsernameChange('bob');
    await component.onSubmit();
    expect(routerSpy.navigateByUrl).toHaveBeenCalledWith('/me');
  });

  it('shows a validation error when username is too long', async () => {
    component.onUsernameChange('a'.repeat(33));
    await component.onSubmit();
    expect(component.validationError()).toBeTruthy();
    expect(authStub.login).not.toHaveBeenCalled();
  });

  it('shows a 400 error message when backend rejects the username', async () => {
    authStub.login.mockRejectedValue({ status: 400 });
    component.onUsernameChange('alice');
    await component.onSubmit();
    fixture.detectChanges();
    expect(component.validationError()).toContain('Invalid username');
  });

  it('shows a generic error message on non-400 failure', async () => {
    authStub.login.mockRejectedValue({ status: 503 });
    component.onUsernameChange('alice');
    await component.onSubmit();
    fixture.detectChanges();
    expect(component.validationError()).toContain('Could not reach');
  });

  it('clears validation error when user edits the field', async () => {
    authStub.login.mockRejectedValue({ status: 503 });
    component.onUsernameChange('alice');
    await component.onSubmit();
    expect(component.validationError()).toBeTruthy();
    component.onUsernameChange('alice2');
    expect(component.validationError()).toBeNull();
  });
});
