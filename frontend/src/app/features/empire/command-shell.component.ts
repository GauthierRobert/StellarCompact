import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { DemoModeService } from '../../services/demo-mode.service';
import { EmpireStore } from '../../stores/empire.store';
import { FactionStore } from '../../stores';
import { formatK } from './kardashev';
import { fmtN } from './ui-format';

/**
 * Command shell — the chrome hosting the empire command pages (Overview,
 * Planets, Fleets, Trade, Wars, Technology). A persistent top nav + a compact
 * status strip (Kardashev badge, tick, key resources) sit above a router-outlet,
 * so navigating between command pages never tears down the shell (the demo sim
 * keeps running). Reads the same signal stores as the galaxy HUD.
 */
@Component({
  selector: 'app-command-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <header class="shell-head">
        <a class="brand" routerLink="/empire">
          <span class="brand-mark">◆</span>
          <span class="brand-name">{{ factionName() }}</span>
        </a>

        <nav class="nav">
          @for (l of links; track l.path) {
            <a
              class="nav-link"
              [routerLink]="l.path"
              routerLinkActive="active"
              [routerLinkActiveOptions]="{ exact: l.exact }"
            >
              <span class="nav-glyph">{{ l.glyph }}</span>
              <span class="nav-label">{{ l.label }}</span>
            </a>
          }
        </nav>

        <div class="status">
          <a class="kbadge" routerLink="/empire/tech" [style.borderColor]="kAccent()">
            <span class="kbadge-tier" [style.color]="kAccent()">{{ k().tierLabel }}</span>
            <span class="kbadge-k">{{ formatK(k().k) }}</span>
          </a>
          <div class="res-strip">
            <span class="res res-energy" title="Energy">⚡ {{ ledgerN('energy') }}</span>
            <span class="res res-minerals" title="Minerals">⬢ {{ ledgerN('minerals') }}</span>
            <span class="res res-credits" title="Credits">◈ {{ ledgerN('credits') }}</span>
            <span class="res res-influence" title="Influence">✶ {{ ledgerN('influence') }}</span>
          </div>
          <span class="tick">T+{{ tick() }}</span>
          <a class="to-galaxy" routerLink="/galaxy">Galaxy ↗</a>
        </div>
      </header>

      <main class="shell-main">
        <router-outlet />
      </main>
    </div>
  `,
  styles: [`
    :host { display: block; width: 100%; height: 100%; background: var(--sc-bg); color: var(--sc-text); }
    .shell { display: flex; flex-direction: column; width: 100%; height: 100%; }
    .shell-head {
      display: flex; align-items: center; gap: 16px;
      height: 54px; flex-shrink: 0; padding: 0 16px;
      border-bottom: 1px solid var(--sc-border);
      background: linear-gradient(180deg, rgba(14,16,22,0.96), rgba(8,9,13,0.92));
      backdrop-filter: blur(8px);
    }
    .brand { display: flex; align-items: center; gap: 8px; text-decoration: none; color: var(--sc-text); flex-shrink: 0; }
    .brand-mark { color: var(--sc-energy); font-size: 14px; }
    .brand-name { font: 700 13px/1 var(--din-display); letter-spacing: 0.5px; white-space: nowrap; max-width: 180px; overflow: hidden; text-overflow: ellipsis; }
    .nav { display: flex; gap: 2px; flex: 1; overflow-x: auto; scrollbar-width: none; }
    .nav::-webkit-scrollbar { display: none; }
    .nav-link {
      display: flex; align-items: center; gap: 6px; padding: 7px 11px;
      border-radius: var(--rounded-xs); text-decoration: none;
      color: var(--sc-text-dim); border: 1px solid transparent;
      font: 600 11px/1 var(--din-body); letter-spacing: 0.4px; white-space: nowrap;
      transition: background 0.15s, color 0.15s, border-color 0.15s;
    }
    .nav-link:hover { color: var(--sc-text); background: rgba(255,255,255,0.05); }
    .nav-link.active { color: var(--sc-text); background: rgba(127,216,239,0.10); border-color: rgba(127,216,239,0.30); }
    .nav-glyph { font-size: 12px; opacity: 0.9; }
    .status { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
    .kbadge {
      display: flex; flex-direction: column; align-items: flex-end; gap: 1px;
      padding: 5px 10px; border: 1px solid var(--sc-border); border-radius: var(--rounded-xs);
      text-decoration: none; background: rgba(0,0,0,0.3);
    }
    .kbadge-tier { font: 700 11px/1 var(--din-display); letter-spacing: 0.5px; }
    .kbadge-k { font: 500 9px/1 var(--din-body); color: var(--sc-text-faint); font-variant-numeric: tabular-nums; }
    .res-strip { display: flex; gap: 10px; }
    .res { font: 600 11px/1 var(--din-body); font-variant-numeric: tabular-nums; white-space: nowrap; }
    .res-energy { color: var(--sc-energy); }
    .res-minerals { color: var(--sc-minerals); }
    .res-credits { color: var(--sc-credits); }
    .res-influence { color: var(--sc-influence); }
    .tick { font: 600 11px/1 var(--din-body); color: var(--sc-text-faint); font-variant-numeric: tabular-nums; }
    .to-galaxy { font: 600 11px/1 var(--din-body); color: var(--sc-text-dim); text-decoration: none; padding: 6px 10px; border: 1px solid var(--sc-border); border-radius: var(--rounded-pill); }
    .to-galaxy:hover { color: var(--sc-text); border-color: var(--sc-border-bright); }
    .shell-main { flex: 1; overflow-y: auto; overflow-x: hidden; }
    @media (max-width: 900px) { .res-strip { display: none; } .nav-label { display: none; } }
  `],
})
export class CommandShellComponent implements OnInit {
  private readonly demo = inject(DemoModeService);
  protected readonly empire = inject(EmpireStore);
  protected readonly factionStore = inject(FactionStore);
  protected readonly formatK = formatK;
  protected readonly fmtN = fmtN;

  protected readonly links = [
    { path: '/empire', exact: true, glyph: '◎', label: 'Overview' },
    { path: '/empire/planets', exact: false, glyph: '🪐', label: 'Planets' },
    { path: '/empire/fleets', exact: false, glyph: '➤', label: 'Fleets' },
    { path: '/empire/trade', exact: false, glyph: '⇄', label: 'Trade' },
    { path: '/empire/wars', exact: false, glyph: '⚔', label: 'Wars' },
    { path: '/empire/tech', exact: false, glyph: '⚛', label: 'Technology' },
  ];

  protected readonly tick = this.demo.tick;
  protected readonly k = this.empire.kardashev;
  protected readonly kAccent = computed(() => {
    const tier = this.k().tierId;
    return tier === 'K3' ? '#e89ac4' : tier === 'K2' ? '#e8c061' : tier === 'K1' ? '#7fd8ef' : '#9fb2c8';
  });

  protected readonly factionName = computed(() => {
    const id = this.demo.playerFactionId();
    return this.factionStore.getById(id)?.name ?? 'Command';
  });

  ngOnInit(): void {
    // Ensure the offline demo is feeding the stores (idempotent if already live).
    this.demo.start(1);
  }

  protected ledgerN(key: 'energy' | 'minerals' | 'credits' | 'influence'): string {
    const l = this.empire.ledger();
    return l ? fmtN(l[key].value) : '0';
  }
}
