import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { EmpireStore } from '../../stores/empire.store';
import type { PlanetBuilding } from '../../stores/empire.store';
import { formatWatts } from './kardashev';
import { fmtN, biomeMeta, sizeLabel, buildingGlyph } from './ui-format';

/**
 * Planet detail page — /empire/planet/:id
 *
 * Displays rich development data for a single owned planet: population,
 * Kardashev contribution, build slots, biome/terraform status, per-tick
 * yields and the full building-slot grid.
 */
@Component({
  selector: 'app-empire-planet-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    @if (planet(); as p) {
      <!-- Header card -->
      <section class="sc-panel detail-card">
        <div class="sc-head">
          <a class="back-link" [routerLink]="['/empire/planets']">&#8592; All planets</a>
          <span class="sc-head-rule"></span>
        </div>
        <div class="planet-header">
          <div class="planet-name-row">
            <span class="planet-name">{{ p.name }}</span>
            @if (p.isCapital) {
              <span class="sc-tag capital-tag">&#9733; Capital</span>
            }
          </div>
          <div class="planet-meta-row">
            <a class="system-link" [routerLink]="['/empire/system', p.systemId]">{{ p.systemName }}</a>
            <span class="biome-chip" [style.color]="biomeMeta(p.biome).colour" [style.border-color]="biomeMeta(p.biome).colour + '55'">
              {{ biomeMeta(p.biome).glyph }} {{ biomeMeta(p.biome).label }}
            </span>
            <span class="sc-tag size-tag">{{ sizeLabel(p.size) }}</span>
            @if (p.specialisation) {
              <span class="sc-tag spec-tag">{{ p.specialisation }}</span>
            }
          </div>
        </div>
      </section>

      <!-- Stats panel -->
      <section class="sc-panel detail-card">
        <div class="sc-head">Statistics<span class="sc-head-rule"></span></div>
        <div class="stats-body">
          <!-- Population -->
          <div class="stat-row">
            <span class="stat-key">Population</span>
            <span class="stat-val">{{ fmtN(p.population) }} / {{ fmtN(p.maxPopulation) }}</span>
          </div>
          <div class="sc-bar-track pop-bar">
            <div class="sc-bar-fill pop-fill"
                 [style.width]="(p.maxPopulation > 0 ? (p.population / p.maxPopulation) * 100 : 0) + '%'">
            </div>
          </div>
          <!-- Kardashev contribution -->
          <div class="stat-row">
            <span class="stat-key">Kardashev contribution</span>
            <span class="stat-val kard-val">{{ formatWatts(p.captureWatts) }}</span>
          </div>
          <!-- Build slots -->
          <div class="stat-row">
            <span class="stat-key">Build slots</span>
            <span class="stat-val">{{ p.slotsUsed }} / {{ p.slotsTotal }}</span>
          </div>
          <div class="sc-bar-track slot-bar">
            <div class="sc-bar-fill slot-fill"
                 [style.width]="(p.slotsTotal > 0 ? (p.slotsUsed / p.slotsTotal) * 100 : 0) + '%'">
            </div>
          </div>
        </div>
      </section>

      <!-- Biome / terraform panel -->
      <section class="sc-panel detail-card">
        <div class="sc-head">Biome<span class="sc-head-rule"></span></div>
        <div class="biome-body">
          <div class="biome-current">
            <span class="biome-glyph">{{ biomeMeta(p.biome).glyph }}</span>
            <span class="biome-label" [style.color]="biomeMeta(p.biome).colour">{{ biomeMeta(p.biome).label }}</span>
          </div>
          @if (p.terraform) {
            <div class="terraform-section">
              <div class="terraform-label">
                Terraforming
                <span class="tf-from" [style.color]="biomeMeta(p.biome).colour">{{ biomeMeta(p.biome).label }}</span>
                &#8594;
                <span class="tf-to" [style.color]="biomeMeta(p.terraform.target).colour">{{ biomeMeta(p.terraform.target).label }}</span>
              </div>
              <div class="sc-bar-track tf-bar">
                <div class="sc-bar-fill tf-fill" [style.width]="(p.terraform.progress * 100) + '%'"></div>
              </div>
              <span class="tf-pct">{{ (p.terraform.progress * 100).toFixed(0) }}%</span>
            </div>
          } @else {
            <span class="stable-label">Stable biome</span>
          }
        </div>
      </section>

      <!-- Yields panel -->
      <section class="sc-panel detail-card">
        <div class="sc-head">Yields<span class="sc-head-rule"></span><span class="per-tick">/tick</span></div>
        <ul class="yields-list">
          <li class="yield-row">
            <span class="yield-key">Energy</span>
            <span class="yield-val" style="color: var(--sc-energy)">{{ p.yields.energy }}</span>
          </li>
          <li class="yield-row">
            <span class="yield-key">Minerals</span>
            <span class="yield-val" style="color: var(--sc-minerals)">{{ p.yields.minerals }}</span>
          </li>
          <li class="yield-row">
            <span class="yield-key">Food</span>
            <span class="yield-val" style="color: var(--sc-good)">{{ p.yields.food }}</span>
          </li>
          <li class="yield-row">
            <span class="yield-key">Tech</span>
            <span class="yield-val" style="color: var(--sc-influence)">{{ p.yields.tech }}</span>
          </li>
        </ul>
      </section>

      <!-- Build-slot grid panel -->
      <section class="sc-panel detail-card">
        <div class="sc-head">Build Slots<span class="sc-head-rule"></span>
          <span class="slot-count">{{ p.slotsUsed }}/{{ p.slotsTotal }}</span>
        </div>
        <div class="slot-grid">
          @for (b of p.buildings; track b.type + b.name) {
            <div class="slot-cell slot-filled">
              <span class="slot-glyph">{{ buildingGlyph(b.type) }}</span>
              <span class="slot-name" [title]="b.name">{{ b.name }}</span>
              <span class="slot-tiers">{{ tierPips(b) }}</span>
            </div>
          }
          @for (empty of emptySlots(p.slotsTotal, p.buildings.length); track empty) {
            <div class="slot-cell slot-empty">
              <span class="slot-empty-label">Empty slot</span>
            </div>
          }
        </div>
      </section>
    } @else {
      <!-- Not found -->
      <section class="sc-panel detail-card not-found-card">
        <div class="sc-head">
          <a class="back-link" [routerLink]="['/empire/planets']">&#8592; All planets</a>
          <span class="sc-head-rule"></span>
        </div>
        <div class="not-found-body">
          <span class="not-found-text">Planet not found</span>
        </div>
      </section>
    }
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 16px;
      max-width: 640px;
      margin: 0 auto;
    }

    /* ---------- shared card ---------- */
    .detail-card { overflow: hidden; }

    /* ---------- back link ---------- */
    .back-link {
      font: 400 10px/1 var(--din-body);
      letter-spacing: 1.4px;
      text-transform: uppercase;
      color: var(--sc-text-dim);
      text-decoration: none;
      flex-shrink: 0;
      transition: color 0.15s ease;
    }
    .back-link:hover { color: var(--sc-text); }

    /* ---------- planet header ---------- */
    .planet-header { padding: 0 12px 14px; display: flex; flex-direction: column; gap: 8px; }
    .planet-name-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .planet-name {
      font: 700 22px/1 var(--din-display);
      color: var(--sc-text);
      letter-spacing: 0.5px;
    }
    .capital-tag {
      color: var(--sc-warn);
      border-color: rgba(232, 192, 97, 0.45);
      background: rgba(232, 192, 97, 0.08);
      font-size: 9px;
    }
    .planet-meta-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .system-link {
      font: 600 11px/1 var(--din-body);
      color: var(--sc-text-dim);
      text-decoration: none;
      letter-spacing: 0.3px;
      transition: color 0.15s ease;
    }
    .system-link:hover { color: var(--sc-text); }
    .biome-chip {
      font: 600 10px/1 var(--din-body);
      letter-spacing: 0.6px;
      padding: 3px 7px;
      border-radius: var(--rounded-xs);
      border: 1px solid transparent;
    }
    .size-tag {
      color: var(--sc-text-faint);
      border-color: var(--sc-border);
      font-size: 9px;
    }
    .spec-tag {
      color: var(--sc-text-dim);
      border-color: var(--sc-border);
      font-size: 9px;
    }

    /* ---------- stats ---------- */
    .stats-body { padding: 0 12px 14px; display: flex; flex-direction: column; gap: 8px; }
    .stat-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .stat-key {
      font: 400 10px/1 var(--din-body);
      letter-spacing: 1.2px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
    }
    .stat-val {
      font: 600 12px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text);
    }
    .kard-val { color: var(--sc-energy); }
    .pop-bar { margin-top: -4px; }
    .pop-fill { background: var(--sc-good); }
    .slot-bar { margin-top: -4px; }
    .slot-fill { background: var(--sc-minerals); }

    /* ---------- biome / terraform ---------- */
    .biome-body { padding: 0 12px 14px; display: flex; flex-direction: column; gap: 10px; }
    .biome-current { display: flex; align-items: center; gap: 8px; }
    .biome-glyph { font-size: 18px; line-height: 1; }
    .biome-label { font: 700 13px/1 var(--din-body); letter-spacing: 0.3px; }
    .terraform-section { display: flex; flex-direction: column; gap: 5px; }
    .terraform-label {
      font: 400 10px/1 var(--din-body);
      letter-spacing: 0.6px;
      color: var(--sc-text-dim);
      display: flex;
      align-items: center;
      gap: 4px;
      flex-wrap: wrap;
    }
    .tf-from, .tf-to { font-weight: 700; }
    .tf-bar { }
    .tf-fill { background: var(--sc-warn); }
    .tf-pct {
      font: 600 9px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-warn);
      align-self: flex-end;
    }
    .stable-label {
      font: 400 10px/1 var(--din-body);
      letter-spacing: 0.6px;
      color: var(--sc-text-faint);
    }

    /* ---------- yields ---------- */
    .yields-list { list-style: none; margin: 0; padding: 0 12px 14px; display: flex; flex-direction: column; gap: 6px; }
    .yield-row { display: flex; align-items: center; justify-content: space-between; }
    .yield-key {
      font: 400 10px/1 var(--din-body);
      letter-spacing: 1.2px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
    }
    .yield-val {
      font: 700 13px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
    }
    .per-tick {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 1px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
      flex-shrink: 0;
    }

    /* ---------- build-slot grid ---------- */
    .slot-count {
      font: 600 9px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text-faint);
      flex-shrink: 0;
    }
    .slot-grid {
      padding: 0 12px 14px;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
      gap: 6px;
    }
    .slot-cell {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 3px;
      padding: 8px 10px;
      border-radius: var(--rounded-xs);
      border: 1px solid var(--sc-border);
      min-height: 58px;
    }
    .slot-filled { background: rgba(255,255,255,0.03); }
    .slot-empty {
      background: transparent;
      border-style: dashed;
      border-color: rgba(255,255,255,0.09);
      justify-content: center;
      align-items: center;
    }
    .slot-glyph { font-size: 14px; line-height: 1; }
    .slot-name {
      font: 600 10px/1.2 var(--din-body);
      color: var(--sc-text);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      width: 100%;
    }
    .slot-tiers {
      font: 400 9px/1 var(--din-body);
      color: var(--sc-text-faint);
      letter-spacing: 1px;
    }
    .slot-empty-label {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 1.2px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
    }

    /* ---------- not-found ---------- */
    .not-found-card { }
    .not-found-body {
      padding: 32px 12px;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .not-found-text {
      font: 400 13px/1 var(--din-body);
      letter-spacing: 0.6px;
      color: var(--sc-text-faint);
    }
  `],
})
export class PlanetDetailComponent {
  private readonly route = inject(ActivatedRoute);
  protected readonly empire = inject(EmpireStore);

  private readonly id = toSignal(this.route.paramMap.pipe(map(p => p.get('id'))));

  protected readonly planet = computed(() =>
    this.empire.planets().find(p => p.id === this.id()) ?? null,
  );

  protected readonly fmtN = fmtN;
  protected readonly formatWatts = formatWatts;
  protected readonly biomeMeta = biomeMeta;
  protected readonly sizeLabel = sizeLabel;
  protected readonly buildingGlyph = buildingGlyph;

  /** Build a tier-pip string: filled circles up to tier, empty circles to 3. */
  protected tierPips(b: PlanetBuilding): string {
    const filled = Math.min(Math.max(b.tier, 0), 3);
    return '●'.repeat(filled) + '○'.repeat(Math.max(0, 3 - filled));
  }

  /** Return an array of `count` placeholder indices for @for tracking. */
  protected emptySlots(total: number, used: number): readonly number[] {
    const count = Math.max(0, total - used);
    return Array.from({ length: count }, (_, i) => i);
  }
}
