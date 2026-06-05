import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { EmpireStore } from '../../stores/empire.store';
import type { PlanetDetail } from '../../stores/empire.store';
import { formatWatts } from './kardashev';
import { fmtN, biomeMeta, sizeLabel } from './ui-format';

type SortKey = 'population' | 'capture' | 'name' | 'yield';

@Component({
  selector: 'app-empire-planets',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <!-- Page header -->
    <div class="page-header">
      <h1 class="page-title">My Planets</h1>
      <p class="page-sub">
        <span class="stat-pill">{{ planets().length }} planet{{ planets().length === 1 ? '' : 's' }}</span>
        <span class="sep">&middot;</span>
        <span class="stat-pill">Pop {{ fmtN(totalPop()) }}</span>
        <span class="sep">&middot;</span>
        <span class="stat-pill">{{ formatWatts(totalCapture()) }} captured</span>
      </p>
    </div>

    <!-- Controls row -->
    <div class="controls-row">
      <div class="sort-group">
        <span class="ctrl-label">Sort</span>
        @for (s of sortOptions; track s.key) {
          <button
            class="ctrl-btn"
            [class.active]="sort() === s.key"
            (click)="sort.set(s.key)"
          >{{ s.label }}</button>
        }
      </div>
      <div class="biome-group">
        <span class="ctrl-label">Biome</span>
        <button
          class="ctrl-btn"
          [class.active]="biomeFilter() === null"
          (click)="biomeFilter.set(null)"
        >All</button>
        @for (b of biomeOptions(); track b.key) {
          <button
            class="ctrl-btn biome-chip"
            [class.active]="biomeFilter() === b.key"
            [style.--biome-col]="b.colour"
            (click)="toggleBiome(b.key)"
          >{{ b.glyph }} {{ b.label }}</button>
        }
      </div>
    </div>

    <!-- Planet grid -->
    @if (view().length > 0) {
      <div class="planet-grid">
        @for (p of view(); track p.id) {
          <a class="sc-panel planet-card" [routerLink]="['/empire/planet', p.id]">
            <!-- Name row -->
            <div class="card-name-row">
              <span class="planet-name">
                {{ p.name }}
                @if (p.isCapital) { <span class="capital-star" title="Capital">&#x2605;</span> }
              </span>
              <span class="system-name">{{ p.systemName }}</span>
            </div>

            <!-- Biome + size + specialisation -->
            <div class="card-meta-row">
              <span
                class="biome-tag"
                [style.borderColor]="biomeMeta(p.biome).colour"
                [style.color]="biomeMeta(p.biome).colour"
              >{{ biomeMeta(p.biome).glyph }} {{ biomeMeta(p.biome).label }}</span>
              <span class="size-tag">{{ sizeLabel(p.size) }}</span>
              @if (p.specialisation) {
                <span class="sc-tag spec-tag">{{ p.specialisation }}</span>
              }
            </div>

            <!-- Population bar -->
            <div class="card-section">
              <div class="bar-label-row">
                <span class="bar-lbl">Population</span>
                <span class="bar-nums">{{ fmtN(p.population) }} / {{ fmtN(p.maxPopulation) }}</span>
              </div>
              <div class="sc-bar-track">
                <div
                  class="sc-bar-fill pop-fill"
                  [style.width]="popPct(p) + '%'"
                ></div>
              </div>
            </div>

            <!-- Slots -->
            <div class="slots-row">
              <span class="slots-lbl">{{ p.slotsUsed }}/{{ p.slotsTotal }} slots</span>
            </div>

            <!-- Per-tick yields -->
            @if (hasYields(p)) {
              <div class="yields-row">
                @if (p.yields.energy > 0) {
                  <span class="yield-chip yield-energy">+{{ p.yields.energy }} E</span>
                }
                @if (p.yields.minerals > 0) {
                  <span class="yield-chip yield-minerals">+{{ p.yields.minerals }} Min</span>
                }
                @if (p.yields.food > 0) {
                  <span class="yield-chip yield-food">+{{ p.yields.food }} Food</span>
                }
                @if (p.yields.tech > 0) {
                  <span class="yield-chip yield-tech">+{{ p.yields.tech }} Tech</span>
                }
              </div>
            }

            <!-- Terraform progress -->
            @if (p.terraform) {
              <div class="terraform-row">
                <span class="terraform-lbl">Terraforming &#8594; {{ p.terraform.target }} {{ terraformPct(p) }}%</span>
                <div class="sc-bar-track tf-track">
                  <div
                    class="sc-bar-fill tf-fill"
                    [style.width]="terraformPct(p) + '%'"
                  ></div>
                </div>
              </div>
            }

            <!-- Kardashev capture -->
            <div class="capture-row">
              <span class="capture-val">{{ formatWatts(p.captureWatts) }}</span>
            </div>
          </a>
        }
      </div>
    } @else {
      <div class="sc-panel empty-state">
        No colonies yet &#8212; expand to claim worlds.
      </div>
    }
  `,
  styles: [`
    :host {
      display: block;
      padding: 20px 24px 32px;
      font-family: var(--din-body);
      color: var(--sc-text);
    }

    /* ---- Page header ---- */
    .page-header { margin-bottom: 18px; }
    .page-title {
      font: 700 22px/1 var(--din-display);
      letter-spacing: 1px;
      text-transform: uppercase;
      color: var(--sc-text);
      margin: 0 0 6px;
    }
    .page-sub {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0;
      font: 400 12px/1 var(--din-body);
      color: var(--sc-text-dim);
    }
    .stat-pill { font-variant-numeric: tabular-nums; }
    .sep { color: var(--sc-text-faint); }

    /* ---- Controls row ---- */
    .controls-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 16px;
      margin-bottom: 16px;
    }
    .sort-group, .biome-group {
      display: flex;
      align-items: center;
      gap: 5px;
      flex-wrap: wrap;
    }
    .ctrl-label {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 1.6px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
      margin-right: 3px;
    }
    .ctrl-btn {
      background: transparent;
      border: 1px solid var(--sc-border);
      border-radius: var(--rounded-xs);
      color: var(--sc-text-dim);
      font: 600 10px/1 var(--din-body);
      letter-spacing: 0.4px;
      padding: 4px 9px;
      cursor: pointer;
      transition: border-color 0.15s, color 0.15s;
    }
    .ctrl-btn:hover {
      border-color: var(--sc-border-bright);
      color: var(--sc-text);
    }
    .ctrl-btn.active {
      border-color: var(--sc-border-bright);
      color: var(--sc-text);
      background: rgba(255,255,255,0.06);
    }
    .biome-chip.active {
      border-color: var(--biome-col, var(--sc-border-bright));
      color: var(--biome-col, var(--sc-text));
    }

    /* ---- Planet grid ---- */
    .planet-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 12px;
    }

    /* ---- Planet card ---- */
    .planet-card {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 12px 14px 10px;
      text-decoration: none;
      color: inherit;
      cursor: pointer;
      transition: border-color 0.15s ease;
    }
    .planet-card:hover { border-color: var(--sc-border-bright); }

    /* Name row */
    .card-name-row { display: flex; align-items: baseline; gap: 7px; }
    .planet-name {
      font: 700 13px/1 var(--din-display);
      color: var(--sc-text);
      letter-spacing: 0.5px;
      flex: 1;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .capital-star { color: var(--sc-credits); font-size: 11px; margin-left: 3px; }
    .system-name {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
      flex-shrink: 0;
      white-space: nowrap;
    }

    /* Biome + meta row */
    .card-meta-row { display: flex; align-items: center; gap: 5px; flex-wrap: wrap; }
    .biome-tag {
      font: 600 10px/1 var(--din-body);
      padding: 2px 7px;
      border-radius: var(--rounded-pill);
      border: 1px solid currentColor;
    }
    .size-tag {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
      padding: 2px 6px;
      border: 1px solid var(--sc-border);
      border-radius: var(--rounded-xs);
    }
    .spec-tag { color: var(--sc-text-dim); }

    /* Population */
    .card-section { display: flex; flex-direction: column; gap: 3px; }
    .bar-label-row { display: flex; align-items: center; justify-content: space-between; }
    .bar-lbl {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 1.2px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
    }
    .bar-nums {
      font: 600 10px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text-dim);
    }
    .pop-fill { background: var(--sc-text-dim); }

    /* Slots */
    .slots-row { display: flex; align-items: center; }
    .slots-lbl { font: 400 10px/1 var(--din-body); color: var(--sc-text-faint); }

    /* Yields */
    .yields-row { display: flex; flex-wrap: wrap; gap: 4px; }
    .yield-chip {
      font: 600 9px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      padding: 2px 6px;
      border-radius: var(--rounded-xs);
      border: 1px solid currentColor;
    }
    .yield-energy   { color: var(--sc-energy); }
    .yield-minerals { color: var(--sc-minerals); }
    .yield-food     { color: var(--sc-good); }
    .yield-tech     { color: var(--sc-influence); }

    /* Terraform */
    .terraform-row { display: flex; flex-direction: column; gap: 3px; }
    .terraform-lbl { font: 400 10px/1 var(--din-body); color: var(--sc-warn); }
    .tf-track { height: 3px; }
    .tf-fill  { background: var(--sc-warn); }

    /* Capture */
    .capture-row { margin-top: auto; padding-top: 2px; }
    .capture-val {
      font: 400 10px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text-faint);
    }

    /* Empty state */
    .empty-state {
      padding: 32px 24px;
      text-align: center;
      font: 400 13px/1.5 var(--din-body);
      color: var(--sc-text-faint);
    }
  `],
})
export class PlanetsComponent {
  protected readonly empire = inject(EmpireStore);

  // expose pure helpers to the template
  protected readonly fmtN = fmtN;
  protected readonly formatWatts = formatWatts;
  protected readonly biomeMeta = biomeMeta;
  protected readonly sizeLabel = sizeLabel;

  // local UI state
  readonly sort = signal<SortKey>('population');
  readonly biomeFilter = signal<string | null>(null);

  readonly sortOptions: { key: SortKey; label: string }[] = [
    { key: 'population', label: 'Pop' },
    { key: 'capture',    label: 'K-capture' },
    { key: 'yield',      label: 'Yield' },
    { key: 'name',       label: 'Name' },
  ];

  // shorthand for the raw planet list
  protected readonly planets = this.empire.planets;

  // unique biomes present in the current planet list, for filter chips
  protected readonly biomeOptions = computed(() => {
    const seen = new Set<string>();
    const opts: { key: string; label: string; colour: string; glyph: string }[] = [];
    for (const p of this.planets()) {
      if (!seen.has(p.biome)) {
        seen.add(p.biome);
        const m = biomeMeta(p.biome);
        opts.push({ key: p.biome, label: m.label, colour: m.colour, glyph: m.glyph });
      }
    }
    return opts.sort((a, b) => a.label.localeCompare(b.label));
  });

  // filtered + sorted view
  readonly view = computed<readonly PlanetDetail[]>(() => {
    const filter = this.biomeFilter();
    const sortKey = this.sort();
    let list = this.planets().slice();
    if (filter !== null) {
      list = list.filter((p) => p.biome === filter);
    }
    switch (sortKey) {
      case 'population':
        list.sort((a, b) => b.population - a.population);
        break;
      case 'capture':
        list.sort((a, b) => b.captureWatts - a.captureWatts);
        break;
      case 'yield':
        list.sort((a, b) => totalYield(b) - totalYield(a));
        break;
      case 'name':
        list.sort((a, b) => a.name.localeCompare(b.name));
        break;
    }
    return list;
  });

  // aggregate header stats
  protected readonly totalPop = computed(() =>
    this.planets().reduce((s, p) => s + p.population, 0),
  );
  protected readonly totalCapture = computed(() =>
    this.planets().reduce((s, p) => s + p.captureWatts, 0),
  );

  // template helpers
  protected popPct(p: PlanetDetail): number {
    if (p.maxPopulation <= 0) return 0;
    return Math.min(100, (p.population / p.maxPopulation) * 100);
  }

  protected terraformPct(p: PlanetDetail): number {
    if (!p.terraform) return 0;
    return Math.round(p.terraform.progress * 100);
  }

  protected hasYields(p: PlanetDetail): boolean {
    const y = p.yields;
    return y.energy > 0 || y.minerals > 0 || y.food > 0 || y.tech > 0;
  }

  protected toggleBiome(key: string): void {
    this.biomeFilter.set(this.biomeFilter() === key ? null : key);
  }
}

function totalYield(p: PlanetDetail): number {
  return p.yields.energy + p.yields.minerals + p.yields.food + p.yields.tech;
}
