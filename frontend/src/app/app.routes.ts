import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'galaxy',
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
    path: 'spectator',
    loadComponent: () =>
      import('./features/spectator/spectator.component').then(
        (m) => m.SpectatorComponent,
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
