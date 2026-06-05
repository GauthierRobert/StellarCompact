import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { EmpireStore } from '../../stores/empire.store';
import type { TradeAgreement } from '../../stores/empire.store';
import { FactionStore } from '../../stores';
import { fmtN } from './ui-format';

/** Resource keys tracked in the flow grid. */
const RESOURCE_KEYS = ['credits', 'minerals', 'energy', 'alloys', 'influence'] as const;
type ResourceKey = (typeof RESOURCE_KEYS)[number];

const RESOURCE_LABEL: Record<ResourceKey, string> = {
  credits: 'Credits',
  minerals: 'Minerals',
  energy: 'Energy',
  alloys: 'Alloys',
  influence: 'Influence',
};

/** Per-resource export/import tally for the Resource Flows section. */
interface ResourceFlow {
  readonly key: ResourceKey;
  readonly label: string;
  readonly exports: number;
  readonly imports: number;
}

/** Sort order for trade status: strained first, then active, then pending. */
const STATUS_ORDER: Record<TradeAgreement['status'], number> = {
  strained: 0,
  active: 1,
  pending: 2,
};

@Component({
  selector: 'app-empire-trade',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <!-- Page header -->
    <header class="page-header">
      <h1 class="page-title">Trade &amp; Markets</h1>
      <div class="page-subtitle">
        @if (trades().length === 0) {
          <span class="sub-dim">No agreements</span>
        } @else {
          <span class="sub-count">{{ trades().length }} agreement{{ trades().length === 1 ? '' : 's' }}</span>
          <span class="sub-sep">·</span>
          <span class="sub-bal" [class.bal-pos]="netBalance() >= 0" [class.bal-neg]="netBalance() < 0">
            Net {{ netBalance() >= 0 ? '+' : '' }}{{ fmtN(netBalance()) }}/t
          </span>
          @if (strainedCount() > 0) {
            <span class="sub-sep">·</span>
            <span class="sub-strained">{{ strainedCount() }} strained</span>
          }
        }
      </div>
    </header>

    <!-- Empty state -->
    @if (trades().length === 0) {
      <div class="empty-state sc-panel">
        <span class="empty-icon">◈</span>
        <p class="empty-text">No open trade — the markets are quiet.</p>
      </div>
    } @else {

      <!-- Section: Open Agreements -->
      <section class="sc-panel trade-section">
        <div class="sc-head">Open Agreements<span class="sc-head-rule"></span>
          <span class="agreement-count">{{ trades().length }}</span>
        </div>
        <ul class="agreement-list">
          @for (t of sortedTrades(); track t.id) {
            <li class="agreement-row">
              <div class="agreement-partner">
                @if (partnerColour(t); as col) {
                  <span class="partner-dot" [style.background]="col"></span>
                } @else {
                  <span class="partner-dot partner-dot--empty"></span>
                }
                <span class="partner-name" [title]="partnerName(t)">{{ partnerName(t) }}</span>
                <span class="sc-tag tst-tag" [class]="'tst-' + t.status">{{ t.status }}</span>
              </div>
              <div class="agreement-detail">
                <span class="goods-flow">
                  <span class="goods-label goods-gives">{{ t.gives }}</span>
                  <span class="goods-arrow">&#8594;</span>
                  <span class="goods-label goods-gets">{{ t.gets }}</span>
                </span>
                <span class="balance-val"
                  [class.bal-pos]="t.balancePerTick >= 0"
                  [class.bal-neg]="t.balancePerTick < 0">
                  {{ t.balancePerTick >= 0 ? '+' : '' }}{{ fmtN(t.balancePerTick) }}/t
                </span>
              </div>
            </li>
          }
        </ul>
      </section>

      <!-- Section: Resource Flows -->
      <section class="sc-panel trade-section">
        <div class="sc-head">Resource Flows<span class="sc-head-rule"></span>
          <span class="flow-hint">exports / imports</span>
        </div>
        <div class="flow-grid">
          @for (rf of resourceFlows(); track rf.key) {
            @if (rf.exports > 0 || rf.imports > 0) {
              <div class="flow-cell">
                <span class="flow-res-label" [class]="'res-' + rf.key">{{ rf.label }}</span>
                <div class="flow-counts">
                  <span class="flow-export" [class.flow-active]="rf.exports > 0">
                    ↑{{ rf.exports }}
                  </span>
                  <span class="flow-sep">/</span>
                  <span class="flow-import" [class.flow-active]="rf.imports > 0">
                    ↓{{ rf.imports }}
                  </span>
                </div>
              </div>
            }
          }
          @if (noActiveFlows()) {
            <p class="flow-empty">No resource flows recorded.</p>
          }
        </div>
      </section>

    }
  `,
  styles: [`
    :host {
      display: block;
      padding: 24px 20px;
      color: var(--sc-text);
      font-family: var(--din-body);
    }

    /* ---- Page header ---- */
    .page-header {
      margin-bottom: 20px;
    }
    .page-title {
      margin: 0 0 6px;
      font: 700 22px/1.1 var(--din-display);
      letter-spacing: 1px;
      text-transform: uppercase;
      color: var(--sc-text);
    }
    .page-subtitle {
      display: flex;
      align-items: center;
      gap: 7px;
      font: 400 11px/1 var(--din-body);
      color: var(--sc-text-dim);
    }
    .sub-dim { color: var(--sc-text-faint); }
    .sub-count { color: var(--sc-text-dim); }
    .sub-sep { color: var(--sc-text-faint); }
    .sub-bal {
      font-variant-numeric: tabular-nums;
      font-weight: 600;
    }
    .bal-pos { color: var(--sc-good); }
    .bal-neg { color: var(--sc-bad); }
    .sub-strained { color: var(--sc-bad); font-weight: 600; }

    /* ---- Empty state ---- */
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 40px 24px;
      gap: 10px;
      text-align: center;
    }
    .empty-icon {
      font-size: 28px;
      color: var(--sc-text-faint);
      line-height: 1;
    }
    .empty-text {
      margin: 0;
      font: 400 12px/1.5 var(--din-body);
      color: var(--sc-text-faint);
    }

    /* ---- Shared section chrome ---- */
    .trade-section {
      margin-bottom: 12px;
      overflow: hidden;
    }
    .agreement-count {
      font: 600 9px/1 var(--din-body);
      color: var(--sc-text-faint);
      flex-shrink: 0;
    }
    .flow-hint {
      font: 400 9px/1 var(--din-body);
      color: var(--sc-text-faint);
      flex-shrink: 0;
    }

    /* ---- Agreement list ---- */
    .agreement-list {
      list-style: none;
      margin: 0;
      padding: 0 12px 10px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .agreement-row {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .agreement-partner {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .partner-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .partner-dot--empty {
      background: var(--sc-text-faint);
    }
    .partner-name {
      flex: 1;
      font: 600 12px/1 var(--din-body);
      color: var(--sc-text);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    /* Status tags — reuse empire-rail tst-* colours */
    .tst-tag {
      display: inline-flex;
      align-items: center;
      padding: 2px 7px;
      border-radius: var(--rounded-pill);
      border: 1px solid var(--sc-border);
      font: 400 8px/1 var(--din-body);
      letter-spacing: 1px;
      text-transform: uppercase;
      flex-shrink: 0;
    }
    .tst-active  { color: var(--sc-good); border-color: rgba(95,214,164,0.35); }
    .tst-pending { color: var(--sc-warn); border-color: rgba(232,192,97,0.35); }
    .tst-strained { color: var(--sc-bad); border-color: rgba(255,84,104,0.35); }

    .agreement-detail {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .goods-flow {
      display: flex;
      align-items: center;
      gap: 5px;
      min-width: 0;
    }
    .goods-label {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-dim);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 110px;
    }
    .goods-arrow {
      font-size: 11px;
      color: var(--sc-text-faint);
      flex-shrink: 0;
    }
    .balance-val {
      font: 600 11px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      flex-shrink: 0;
    }

    /* Divider between rows */
    .agreement-row + .agreement-row {
      padding-top: 10px;
      border-top: 1px solid var(--sc-border);
    }

    /* ---- Resource flow grid ---- */
    .flow-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
      gap: 6px;
      padding: 0 12px 12px;
    }
    .flow-cell {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 8px 10px;
      border: 1px solid var(--sc-border);
      border-radius: var(--rounded-xs);
      background: transparent;
    }
    .flow-res-label {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 1.4px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
    }
    /* Resource accent colours */
    .res-credits   { color: var(--sc-credits);   }
    .res-minerals  { color: var(--sc-minerals);  }
    .res-energy    { color: var(--sc-energy);    }
    .res-alloys    { color: var(--sc-alloys);    }
    .res-influence { color: var(--sc-influence); }

    .flow-counts {
      display: flex;
      align-items: center;
      gap: 4px;
      font: 600 13px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
    }
    .flow-export { color: var(--sc-text-faint); }
    .flow-import { color: var(--sc-text-faint); }
    .flow-export.flow-active { color: var(--sc-good); }
    .flow-import.flow-active { color: var(--sc-bad); }
    .flow-sep { color: var(--sc-text-faint); font-size: 11px; }

    .flow-empty {
      grid-column: 1 / -1;
      margin: 0;
      padding: 8px 0 4px;
      font: 400 11px/1 var(--din-body);
      color: var(--sc-text-faint);
    }
  `],
})
export class TradeComponent {
  protected readonly empireStore = inject(EmpireStore);
  protected readonly factionStore = inject(FactionStore);
  protected readonly fmtN = fmtN;

  // ---- base signal ----
  protected readonly trades = this.empireStore.trades;

  // ---- derived computeds ----

  protected readonly sortedTrades = computed(() =>
    [...this.trades()].sort(
      (a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status],
    ),
  );

  protected readonly netBalance = computed(() =>
    this.trades().reduce((sum, t) => sum + t.balancePerTick, 0),
  );

  protected readonly strainedCount = computed(() =>
    this.trades().filter((t) => t.status === 'strained').length,
  );

  /**
   * Per-resource export/import tallies derived purely from empire.trades().
   * For each trade: +1 export on the `gives` resource, +1 import on the `gets` resource.
   * Only resource keys matching the five tracked keys are counted.
   */
  protected readonly resourceFlows = computed<readonly ResourceFlow[]>(() => {
    const exports: Record<string, number> = {};
    const imports: Record<string, number> = {};

    for (const t of this.trades()) {
      const givesKey = t.gives.toLowerCase() as ResourceKey;
      const getsKey = t.gets.toLowerCase() as ResourceKey;
      if (RESOURCE_KEYS.includes(givesKey)) {
        exports[givesKey] = (exports[givesKey] ?? 0) + 1;
      }
      if (RESOURCE_KEYS.includes(getsKey)) {
        imports[getsKey] = (imports[getsKey] ?? 0) + 1;
      }
    }

    return RESOURCE_KEYS.map((key) => ({
      key,
      label: RESOURCE_LABEL[key],
      exports: exports[key] ?? 0,
      imports: imports[key] ?? 0,
    }));
  });

  protected readonly noActiveFlows = computed(() =>
    this.resourceFlows().every((rf) => rf.exports === 0 && rf.imports === 0),
  );

  // ---- helpers ----

  protected partnerName(t: TradeAgreement): string {
    if (t.partnerFactionId === 'MARKET') return 'Open Market';
    return this.factionStore.getById(t.partnerFactionId)?.name ?? t.partnerFactionId;
  }

  protected partnerColour(t: TradeAgreement): string | null {
    if (t.partnerFactionId === 'MARKET') return '#ffd27a';
    return this.factionStore.getById(t.partnerFactionId)?.colour ?? null;
  }
}
