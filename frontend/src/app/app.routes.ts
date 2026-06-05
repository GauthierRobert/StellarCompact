import { Routes } from '@angular/router';
import { authGuard } from './guards/auth.guard';

export const routes: Routes = [
  {
    // Default landing: redirect to the demo auto-discovery page (E11-07).
    // /spectator fetches GET /api/games and navigates to the first running match.
    path: '',
    redirectTo: 'spectator',
    pathMatch: 'full',
  },
  {
    path: 'galaxy',
    loadComponent: () =>
      import('./features/hud/hud.component').then((m) => m.HudComponent),
  },
  {
    path: 'config',
    loadComponent: () =>
      import('./features/config/config.component').then(
        (m) => m.ConfigComponent,
      ),
  },
  {
    // Empire command pages (E13): a persistent shell hosting the deep-management
    // screens — overview (Kardashev hero), planets, fleets, trade, wars, tech.
    path: 'empire',
    loadComponent: () =>
      import('./features/empire/command-shell.component').then(
        (m) => m.CommandShellComponent,
      ),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./features/empire/overview.component').then((m) => m.OverviewComponent),
      },
      {
        path: 'planets',
        loadComponent: () =>
          import('./features/empire/planets.component').then((m) => m.PlanetsComponent),
      },
      {
        path: 'planet/:id',
        loadComponent: () =>
          import('./features/empire/planet-detail.component').then((m) => m.PlanetDetailComponent),
      },
      {
        path: 'fleets',
        loadComponent: () =>
          import('./features/empire/fleets.component').then((m) => m.FleetsComponent),
      },
      {
        path: 'trade',
        loadComponent: () =>
          import('./features/empire/trade.component').then((m) => m.TradeComponent),
      },
      {
        path: 'wars',
        loadComponent: () =>
          import('./features/empire/wars.component').then((m) => m.WarsComponent),
      },
      {
        path: 'tech',
        loadComponent: () =>
          import('./features/empire/tech.component').then((m) => m.TechComponent),
      },
    ],
  },
  {
    // /spectator (no gameId) auto-discovers the first running match via
    // GET /api/games and redirects to /spectate/:gameId (E11-07).
    path: 'spectator',
    loadComponent: () =>
      import('./features/spectator/demo-landing.component').then(
        (m) => m.DemoLandingComponent,
      ),
  },
  {
    path: 'spectate/:gameId',
    loadComponent: () =>
      import('./features/spectator/spectator.component').then(
        (m) => m.SpectatorComponent,
      ),
  },
  {
    // Username-only dev auth login page (feat/dev-jwt-auth).
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login.component').then(
        (m) => m.LoginComponent,
      ),
  },
  {
    // Authenticated commander dashboard -- shows owned games (feat/dev-jwt-auth).
    path: 'me',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/dashboard/my-games.component').then(
        (m) => m.MyGamesComponent,
      ),
  },
];
