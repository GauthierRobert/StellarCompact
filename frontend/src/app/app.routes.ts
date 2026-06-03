import { Routes } from '@angular/router';

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
];
