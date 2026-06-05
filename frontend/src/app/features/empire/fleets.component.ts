import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EmpireStore } from '../../stores/empire.store';
import type { FleetSummary } from '../../stores/empire.store';
import { missionLabel, fmtN } from './ui-format';

@Component({
  selector: 'app-empire-fleets',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <!-- Page header -->
    <header class="page-header">
      <h1 class="page-title">Fleets &amp; Movements</h1>
      <p class="page-sub">
        <span class="meta-val">{{ empire.fleets().length }}</span>
        <span class="meta-lbl">fleets</span>
        <span class="meta-sep">·</span>
        <span class="meta-val">{{ fmtN(empire.totalFleetStrength()) }}</span>
        <span class="meta-lbl">total strength</span>
        @if (empire.fleetsInTransit().length > 0) {
          <span class="meta-sep">·</span>
          <span class="meta-val transit-accent">{{ empire.fleetsInTransit().length }}</span>
          <span class="meta-lbl">in transit</span>
        }
      </p>
    </header>

    <!-- IN TRANSIT section -->
    @if (empire.fleetsInTransit().length > 0) {
      <section class="sc-panel fleet-section">
        <div class="sc-head">In Transit<span class="sc-head-rule"></span></div>
        <ul class="transit-list">
          @for (f of empire.fleetsInTransit(); track f.id) {
            <li class="transit-lane">
              <!-- Lane top row: name + mission tag + ETA chip -->
              <div class="lane-top">
                <span class="fleet-dot" [class]="'fst-' + f.status"></span>
                <span class="lane-name">{{ f.name }}</span>
                @if (f.mission) {
                  <span class="sc-tag" [class]="'ftag-' + f.status">{{ missionLabel(f.mission) }}</span>
                }
                <span class="sc-tag ftag-returning eta-chip">{{ f.etaTicks ?? '?' }}t</span>
              </div>

              <!-- Lane diagram: origin •———[marker]———● dest -->
              <div class="lane-diagram">
                <span class="lane-origin">{{ f.originName ?? f.location }}</span>
                <div class="lane-track-wrap">
                  <div class="lane-track-line"></div>
                  <span class="lane-origin-dot"></span>
                  <span
                    class="lane-marker"
                    [style.left]="(f.progress ?? 0) * 100 + '%'"
                  ></span>
                  <span class="lane-dest-dot"></span>
                </div>
                <span class="lane-dest">{{ f.destName ?? '?' }}</span>
              </div>

              <!-- Progress bar -->
              <div class="sc-bar-track transit-bar">
                <div
                  class="sc-bar-fill transit-fill"
                  [style.width]="(f.progress ?? 0) * 100 + '%'"
                ></div>
              </div>

              <!-- Ship composition chips -->
              @if ((f.ships ?? []).length > 0) {
                <div class="ship-chips">
                  @for (s of (f.ships ?? []); track s.className) {
                    <span class="ship-chip">{{ s.count }}&times;&nbsp;{{ s.className }}</span>
                  }
                </div>
              }
            </li>
          }
        </ul>
      </section>
    }

    <!-- ALL FLEETS section -->
    @if (empire.fleets().length > 0) {
      <section class="sc-panel fleet-section">
        <div class="sc-head">All Fleets<span class="sc-head-rule"></span>
          <span class="fleet-count-badge">{{ empire.fleets().length }}</span>
        </div>
        <ul class="fleet-list">
          @for (f of empire.fleets(); track f.id) {
            <li class="fleet-row">
              <!-- Status dot + name + location -->
              <div class="fleet-row-main">
                <span class="fleet-dot" [class]="'fst-' + f.status"></span>
                <span class="fleet-row-name">{{ f.name }}</span>
                <span class="fleet-row-loc">{{ f.location }}</span>
                @if (f.status === 'moving' || f.status === 'returning') {
                  <span class="fleet-row-dest">&#8594;&nbsp;{{ f.destName ?? '?' }}
                    @if (f.etaTicks != null) {
                      <span class="fleet-row-eta">&nbsp;({{ f.etaTicks }}t)</span>
                    }
                  </span>
                }
                <span class="fleet-row-str">{{ fmtN(f.strength) }}</span>
                @if (f.mission) {
                  <span class="sc-tag fleet-mission-tag" [class]="'ftag-' + f.status">
                    {{ missionLabel(f.mission) }}
                  </span>
                }
              </div>

              <!-- Ship composition chips -->
              @if ((f.ships ?? []).length > 0) {
                <div class="ship-chips">
                  @for (s of (f.ships ?? []); track s.className) {
                    <span class="ship-chip">{{ s.count }}&times;&nbsp;{{ s.className }}</span>
                  }
                </div>
              }
            </li>
          }
        </ul>
      </section>
    }

    <!-- Empty state -->
    @if (empire.fleets().length === 0) {
      <div class="empty-state">
        <span class="empty-icon">&#9651;</span>
        <span class="empty-label">No fleets commissioned</span>
      </div>
    }
  `,
  styles: [`
    :host {
      display: block;
      padding: 20px 24px 32px;
    }

    /* ---- Page header ---- */
    .page-header {
      margin-bottom: 20px;
    }
    .page-title {
      font: 700 22px/1.1 var(--din-display);
      letter-spacing: 1.5px;
      text-transform: uppercase;
      color: var(--sc-text);
      margin: 0 0 6px;
    }
    .page-sub {
      margin: 0;
      display: flex;
      align-items: center;
      gap: 5px;
      flex-wrap: wrap;
    }
    .meta-val {
      font: 600 12px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text);
    }
    .meta-lbl {
      font: 400 11px/1 var(--din-body);
      color: var(--sc-text-faint);
    }
    .meta-sep {
      color: var(--sc-text-faint);
      font-size: 10px;
    }
    .transit-accent {
      color: var(--sc-energy);
    }

    /* ---- Shared section spacing ---- */
    .fleet-section {
      margin-bottom: 14px;
      overflow: hidden;
    }
    .fleet-count-badge {
      font: 600 9px/1 var(--din-body);
      color: var(--sc-text-dim);
      flex-shrink: 0;
    }

    /* ---- Status dot colours (matches empire-rail) ---- */
    .fleet-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      flex-shrink: 0;
      display: inline-block;
    }
    .fst-idle      { background: var(--sc-text-faint); }
    .fst-moving    { background: var(--sc-energy); box-shadow: 0 0 4px var(--sc-energy); }
    .fst-engaged   { background: var(--sc-bad); box-shadow: 0 0 4px var(--sc-bad); }
    .fst-defending { background: var(--sc-alloys); }
    .fst-returning { background: var(--sc-energy); box-shadow: 0 0 4px rgba(127,216,239,0.6); }

    /* ---- Tag colour variants (matches empire-rail + adds returning) ---- */
    .ftag-idle      { color: var(--sc-text-faint); border-color: var(--sc-border); }
    .ftag-moving    { color: var(--sc-energy); border-color: rgba(127,216,239,0.3); }
    .ftag-engaged   { color: var(--sc-bad); border-color: rgba(255,84,104,0.3); }
    .ftag-defending { color: var(--sc-alloys); border-color: rgba(194,168,232,0.3); }
    .ftag-returning { color: var(--sc-energy); border-color: rgba(127,216,239,0.3); }

    /* ---- In-Transit list ---- */
    .transit-list {
      list-style: none;
      margin: 0;
      padding: 0 12px 10px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .transit-lane {
      display: flex;
      flex-direction: column;
      gap: 6px;
      padding-bottom: 14px;
      border-bottom: 1px solid var(--sc-border);
    }
    .transit-lane:last-child {
      border-bottom: none;
      padding-bottom: 0;
    }

    /* Lane top row */
    .lane-top {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .lane-name {
      flex: 1;
      font: 600 12px/1 var(--din-body);
      color: var(--sc-text);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .eta-chip {
      color: var(--sc-text-dim);
      border-color: var(--sc-border);
      font-size: 9px;
    }

    /* Lane diagram */
    .lane-diagram {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .lane-origin,
    .lane-dest {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
      white-space: nowrap;
      flex-shrink: 0;
      max-width: 90px;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .lane-dest {
      text-align: right;
    }
    .lane-track-wrap {
      position: relative;
      flex: 1;
      height: 12px;
      display: flex;
      align-items: center;
    }
    /* Hairline connecting origin → dest */
    .lane-track-line {
      position: absolute;
      left: 6px;
      right: 6px;
      top: 50%;
      height: 1px;
      background: var(--sc-border-bright);
      transform: translateY(-50%);
    }
    /* Origin dot (left anchor) */
    .lane-origin-dot {
      position: absolute;
      left: 2px;
      top: 50%;
      transform: translateY(-50%);
      width: 5px;
      height: 5px;
      border-radius: 50%;
      background: var(--sc-text-faint);
      flex-shrink: 0;
    }
    /* Destination dot (right anchor) */
    .lane-dest-dot {
      position: absolute;
      right: 2px;
      top: 50%;
      transform: translateY(-50%);
      width: 7px;
      height: 7px;
      border-radius: 50%;
      border: 1.5px solid var(--sc-text-dim);
      background: transparent;
      flex-shrink: 0;
    }
    /* Moving marker — the glowing traveller */
    .lane-marker {
      position: absolute;
      top: 50%;
      transform: translate(-50%, -50%);
      width: 9px;
      height: 9px;
      border-radius: 50%;
      background: var(--sc-energy);
      box-shadow: 0 0 6px 2px rgba(127,216,239,0.55);
      transition: left 0.6s cubic-bezier(0.22, 1, 0.36, 1);
      z-index: 1;
    }

    /* Transit progress bar */
    .transit-bar {
      height: 3px;
    }
    .transit-fill {
      background: var(--sc-energy);
    }

    /* ---- Ship composition chips ---- */
    .ship-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }
    .ship-chip {
      font: 400 9px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text-dim);
      background: rgba(255,255,255,0.05);
      border: 1px solid var(--sc-border);
      border-radius: var(--rounded-xs);
      padding: 2px 6px;
      white-space: nowrap;
    }

    /* ---- All Fleets list ---- */
    .fleet-list {
      list-style: none;
      margin: 0;
      padding: 0 12px 10px;
      display: flex;
      flex-direction: column;
      gap: 0;
    }
    .fleet-row {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 8px 0;
      border-bottom: 1px solid var(--sc-border);
    }
    .fleet-row:last-child {
      border-bottom: none;
    }
    .fleet-row-main {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .fleet-row-name {
      font: 600 11px/1 var(--din-body);
      color: var(--sc-text);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      min-width: 0;
    }
    .fleet-row-loc {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      flex: 1;
      min-width: 0;
    }
    .fleet-row-dest {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-energy);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      flex-shrink: 0;
    }
    .fleet-row-eta {
      color: var(--sc-text-faint);
    }
    .fleet-row-str {
      font: 600 11px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text-dim);
      flex-shrink: 0;
    }
    .fleet-mission-tag {
      font-size: 8px;
      flex-shrink: 0;
    }

    /* ---- Empty state ---- */
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 10px;
      padding: 48px 24px;
      color: var(--sc-text-faint);
    }
    .empty-icon {
      font-size: 28px;
      opacity: 0.3;
    }
    .empty-label {
      font: 400 12px/1 var(--din-body);
      letter-spacing: 1.4px;
      text-transform: uppercase;
    }
  `],
})
export class FleetsComponent {
  protected readonly empire = inject(EmpireStore);
  protected readonly missionLabel = missionLabel;
  protected readonly fmtN = fmtN;

  /** Coerce optional progress to a clamped [0,100] percentage string. */
  protected pct(f: FleetSummary): string {
    return Math.min(100, Math.max(0, (f.progress ?? 0) * 100)) + '%';
  }
}
