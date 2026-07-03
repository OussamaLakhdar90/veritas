import React from 'react';
import { useReducedMotion } from 'framer-motion';
import { useCountUp } from './ui';
import { formatShortDate } from '../lib/format';

/** Dependency-free, token-coloured SVG charts. Each is an accessible image (role="img" + aria-label). */

const R = 54;
const C = 2 * Math.PI * R; // donut/gauge circumference

const SEV_STROKE: Record<string, string> = {
  BLOCKER: 'stroke-sev-blocker', CRITICAL: 'stroke-sev-critical', MAJOR: 'stroke-sev-major',
  MINOR: 'stroke-sev-minor', INFO: 'stroke-sev-info',
};
export const SEV_SWATCH: Record<string, string> = {
  BLOCKER: 'bg-sev-blocker', CRITICAL: 'bg-sev-critical', MAJOR: 'bg-sev-major',
  MINOR: 'bg-sev-minor', INFO: 'bg-sev-info',
};
const SEV_ORDER = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO'];

export interface DonutSlice { label: string; value: number; stroke: string }

/** Build donut slices from a severity→count map: known severities first (blocker→info), others after; empties dropped. */
export function severitySlices(counts: Record<string, number>): DonutSlice[] {
  const rank = (k: string) => { const i = SEV_ORDER.indexOf(k); return i < 0 ? SEV_ORDER.length : i; };
  return Object.entries(counts)
    .filter(([, n]) => n > 0)
    .map(([k, n]) => ({ key: k.toUpperCase(), value: n }))
    .sort((a, b) => rank(a.key) - rank(b.key) || a.key.localeCompare(b.key))
    .map(({ key, value }) => ({ label: key, value, stroke: SEV_STROKE[key] ?? 'stroke-sev-info' }));
}

export function Donut({ slices, ariaLabel, centerValue, centerLabel, size = 140 }: {
  slices: DonutSlice[]; ariaLabel: string; centerValue?: React.ReactNode; centerLabel?: string; size?: number }) {
  const total = slices.reduce((n, s) => n + s.value, 0);
  let offset = 0;
  return (
    <svg viewBox="0 0 140 140" role="img" aria-label={ariaLabel} style={{ width: size, height: size }} className="chart-in shrink-0">
      <g transform="rotate(-90 70 70)" fill="none" strokeWidth={15}>
        <circle cx="70" cy="70" r={R} className="stroke-border/60" />
        {total > 0 && slices.map((s, i) => {
          const len = (s.value / total) * C;
          const seg = (
            <circle key={s.label} cx="70" cy="70" r={R} className={`arc-sweep ${s.stroke}`}
              style={{ animationDelay: `${i * 80}ms` }}
              strokeDasharray={`${len.toFixed(2)} ${(C - len).toFixed(2)}`} strokeDashoffset={(-offset).toFixed(2)} />
          );
          offset += len;
          return seg;
        })}
      </g>
      {centerValue != null && <text x="70" y="68" textAnchor="middle" className="fill-ink-900 font-bold" fontSize="26">{centerValue}</text>}
      {/* fontSize 12 at the 140 render size renders ≈12px — above the ~10px legibility floor a VP reads at a glance. */}
      {centerLabel && <text x="70" y="87" textAnchor="middle" className="fill-muted" fontSize="12">{centerLabel}</text>}
    </svg>
  );
}

export function Gauge({ value, max, ariaLabel, centerLabel, size = 140, tone }: {
  value: number; max: number; ariaLabel: string; centerLabel?: string; size?: number; tone?: string }) {
  const pct = max > 0 ? Math.round((value / max) * 100) : 0;
  const shownPct = useCountUp(pct);
  const len = (pct / 100) * C;
  // Threshold tone by default — a 20% resolution rate must never render success-green.
  const stroke = tone ?? (pct >= 75 ? 'stroke-success' : pct >= 40 ? 'stroke-warning' : 'stroke-danger');
  return (
    <svg viewBox="0 0 140 140" role="img" aria-label={ariaLabel} style={{ width: size, height: size }} className="chart-in shrink-0">
      <g transform="rotate(-90 70 70)" fill="none" strokeWidth={15}>
        <circle cx="70" cy="70" r={R} className="stroke-border/60" />
        <circle cx="70" cy="70" r={R} className={`arc-sweep ${stroke}`} strokeLinecap="round"
          strokeDasharray={`${len.toFixed(2)} ${(C - len).toFixed(2)}`} />
      </g>
      <text x="70" y="68" textAnchor="middle" className="fill-ink-900 font-bold" fontSize="24">{shownPct}%</text>
      {centerLabel && <text x="70" y="87" textAnchor="middle" className="fill-muted" fontSize="12">{centerLabel}</text>}
    </svg>
  );
}

/** Line-colour token per chart tone → a text-* utility whose `currentColor` the stroke/fill inherit. Neutral
 *  by default: a sparkline is data, not a call to action, so it shouldn't spend the brand red. */
const TONE_TEXT: Record<ChartTone, string> = {
  neutral: 'text-ink-700', brand: 'text-brand', success: 'text-success',
  info: 'text-info', warning: 'text-warning', danger: 'text-danger',
};
export type ChartTone = 'neutral' | 'brand' | 'success' | 'info' | 'warning' | 'danger';

export function Sparkline({ values, ariaLabel, tone = 'neutral' }:
  { values: number[]; ariaLabel: string; tone?: ChartTone }) {
  if (values.length < 2) return <div role="img" aria-label={ariaLabel} className="h-20" />;
  const w = 320, h = 80;
  const max = Math.max(...values), min = Math.min(...values), range = (max - min) || 1;
  const coords = values.map((v, i) => [
    (i / (values.length - 1)) * w,
    h - ((v - min) / range) * (h - 12) - 6,
  ] as const);
  const pts = coords.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const [lx, ly] = coords[coords.length - 1];
  return (
    <svg viewBox={`0 0 ${w} ${h}`} role="img" aria-label={ariaLabel} preserveAspectRatio="none"
      className={`chart-in h-20 w-full ${TONE_TEXT[tone]}`}>
      <polyline fill="none" className="spark-draw stroke-current" pathLength={1} strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" points={pts} />
      <circle cx={lx.toFixed(1)} cy={ly.toFixed(1)} r={3.5} className="spark-dot fill-current" />
    </svg>
  );
}
/** Threshold tone for a fidelity score — mirrors the release gate language (>=90 passes). */
export function scoreTone(score: number): string {
  if (score >= 90) return 'stroke-success';
  if (score >= 70) return 'stroke-warning';
  return 'stroke-danger';
}
export function scoreTextTone(score: number): string {
  if (score >= 90) return 'text-success';
  if (score >= 70) return 'text-warning';
  return 'text-danger';
}

/**
 * The Contract Fidelity hero ring: sweeps to the score (ring-sweep keyframe, reduced-motion shows the final
 * state) while the center counts up. The one number the VP quotes upward.
 */
export function ScoreRing({ score, size = 190, ariaLabel, centerLabel }: {
  score: number; ariaLabel: string; size?: number; centerLabel?: string }) {
  const shown = useCountUp(score);
  const len = (Math.max(0, Math.min(100, score)) / 100) * C;
  return (
    <svg viewBox="0 0 140 140" role="img" aria-label={ariaLabel} style={{ width: size, height: size }}
      className="chart-in shrink-0">
      <g transform="rotate(-90 70 70)" fill="none" strokeWidth={12}>
        <circle cx="70" cy="70" r={R} className="stroke-border/60" />
        <circle cx="70" cy="70" r={R} className={`ring-sweep ${scoreTone(score)}`} strokeLinecap="round"
          strokeDasharray={`${len.toFixed(2)} ${(C - len).toFixed(2)}`} />
      </g>
      <text x="70" y="66" textAnchor="middle" className="fill-ink-900 font-bold tabular-nums" fontSize="34">{shown}</text>
      <text x="70" y="82" textAnchor="middle" className="fill-muted" fontSize="10">/100</text>
      {centerLabel && <text x="70" y="96" textAnchor="middle" className="fill-muted" fontSize="10">{centerLabel}</text>}
    </svg>
  );
}

/** One point of a dated time series (findings-per-day, fidelity-score history, daily spend…). */
export interface TrendPoint { date: string; value: number }

const TREND_W = 560;
const TREND_H = 160;
const TREND_PAD = { top: 12, right: 12, bottom: 22, left: 34 };

/**
 * A dependency-free line/area trend chart over a dated series — the table-stakes "history" chart a
 * Datadog-habituated VP expects. Gradient area fill under a token-coloured line, min/max gridlines with y
 * labels, first/last date x-labels (locale-true short dates), and a hover crosshair driving an HTML tooltip
 * rendered OUTSIDE the SVG (crisp text at any zoom). Accepts an optional fixed y-domain (e.g. 50–100 for a
 * fidelity score) and a target reference line (e.g. the release gate at 90). Reduced-motion skips the draw-in.
 */
export function TrendChart({ points, ariaLabel, tone = 'neutral', domain, target, targetLabel, format }: {
  points: TrendPoint[];
  ariaLabel: string;
  /** Line colour token base — neutral (default) | 'brand' | 'success' | 'info' … maps to stroke-/fill-/text-
   *  utilities. Neutral by default: a history chart is data, not an action, so it doesn't spend the brand red. */
  tone?: ChartTone;
  /** Fixed [min,max] y-domain; omit to fit the data (with a little headroom). */
  domain?: [number, number];
  /** A horizontal reference line value (e.g. a target score). */
  target?: number;
  targetLabel?: string;
  /** Value formatter for the tooltip + y labels (defaults to a plain number). */
  format?: (v: number) => string;
}) {
  const reduce = useReducedMotion();
  const [hover, setHover] = React.useState<number | null>(null);
  const gradId = React.useId();
  const fmt = format ?? ((v: number) => String(Math.round(v)));

  if (points.length < 2) {
    return <div role="img" aria-label={ariaLabel} className="h-40" />;
  }

  const values = points.map((p) => p.value);
  const [lo, hi] = domain ?? (() => {
    const min = Math.min(...values);
    const max = Math.max(...values);
    if (min === max) return [min - 1, max + 1] as [number, number];
    const pad = (max - min) * 0.1;
    return [min - pad, max + pad] as [number, number];
  })();
  const span = (hi - lo) || 1;

  const innerW = TREND_W - TREND_PAD.left - TREND_PAD.right;
  const innerH = TREND_H - TREND_PAD.top - TREND_PAD.bottom;
  const x = (i: number) => TREND_PAD.left + (i / (points.length - 1)) * innerW;
  const y = (v: number) => TREND_PAD.top + (1 - (Math.max(lo, Math.min(hi, v)) - lo) / span) * innerH;

  const coords = points.map((p, i) => [x(i), y(p.value)] as const);
  const line = coords.map(([px, py], i) => `${i === 0 ? 'M' : 'L'}${px.toFixed(1)} ${py.toFixed(1)}`).join(' ');
  const area = `${line} L${x(points.length - 1).toFixed(1)} ${(TREND_H - TREND_PAD.bottom).toFixed(1)} `
    + `L${x(0).toFixed(1)} ${(TREND_H - TREND_PAD.bottom).toFixed(1)} Z`;

  const stroke = { neutral: 'stroke-current', brand: 'stroke-brand', success: 'stroke-success', info: 'stroke-info', warning: 'stroke-warning', danger: 'stroke-danger' }[tone];
  const fill = TONE_TEXT[tone];

  const hp = hover != null ? points[hover] : null;
  const hx = hover != null ? x(hover) : 0;
  const tooltipLeft = `${(hx / TREND_W) * 100}%`;

  return (
    <div className="relative w-full">
      <svg viewBox={`0 0 ${TREND_W} ${TREND_H}`} role="img" aria-label={ariaLabel} className={`chart-in h-40 w-full ${fill}`}>
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="currentColor" stopOpacity={0.22} />
            <stop offset="100%" stopColor="currentColor" stopOpacity={0} />
          </linearGradient>
        </defs>

        {/* Gridlines at the domain min/max + their y labels. */}
        {[hi, lo].map((v) => (
          <g key={v}>
            <line x1={TREND_PAD.left} x2={TREND_W - TREND_PAD.right} y1={y(v)} y2={y(v)}
              className="stroke-border/60" strokeWidth={1} vectorEffect="non-scaling-stroke" />
            <text x={TREND_PAD.left - 6} y={y(v) + 3} textAnchor="end" className="fill-muted" fontSize="11">{fmt(v)}</text>
          </g>
        ))}

        {/* Optional target reference line (e.g. the release gate at 90). */}
        {target != null && target >= lo && target <= hi && (
          <line x1={TREND_PAD.left} x2={TREND_W - TREND_PAD.right} y1={y(target)} y2={y(target)}
            className="stroke-success/70" strokeWidth={1} strokeDasharray="4 3" vectorEffect="non-scaling-stroke">
            {targetLabel && <title>{targetLabel}</title>}
          </line>
        )}

        <path d={area} fill={`url(#${gradId})`} stroke="none" />
        <path d={line} fill="none" className={`${stroke} ${reduce ? '' : 'spark-draw'}`} pathLength={1}
          strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />

        {/* First + last date x-labels. */}
        <text x={TREND_PAD.left} y={TREND_H - 6} textAnchor="start" className="fill-muted" fontSize="11">{formatShortDate(points[0].date)}</text>
        <text x={TREND_W - TREND_PAD.right} y={TREND_H - 6} textAnchor="end" className="fill-muted" fontSize="11">{formatShortDate(points[points.length - 1].date)}</text>

        {/* Hover crosshair + point marker. */}
        {hp && (
          <g>
            <line x1={hx} x2={hx} y1={TREND_PAD.top} y2={TREND_H - TREND_PAD.bottom}
              className="stroke-muted/50" strokeWidth={1} vectorEffect="non-scaling-stroke" />
            <circle cx={hx} cy={y(hp.value)} r={3.5} className={`${fill} fill-current`} />
          </g>
        )}

        {/* Invisible hit-columns drive the hover state (each spans one point). */}
        {points.map((p, i) => (
          <rect key={p.date + i} x={i === 0 ? 0 : (x(i - 1) + x(i)) / 2}
            y={0} width={i === 0 ? x(0) + (x(1) - x(0)) / 2 : (i === points.length - 1 ? TREND_W - (x(i - 1) + x(i)) / 2 : (x(i + 1) - x(i - 1)) / 2)}
            height={TREND_H} fill="transparent"
            onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover((h) => (h === i ? null : h))} />
        ))}
      </svg>

      {/* HTML tooltip — rendered outside the SVG so its text stays crisp at any zoom. */}
      {hp && (
        <div className="pointer-events-none absolute top-1 -translate-x-1/2 rounded-md bg-ink-900 px-2 py-1 text-2xs font-medium text-bg shadow-pop"
          style={{ left: tooltipLeft }} role="status">
          <span className="tabular-nums">{fmt(hp.value)}</span>
          <span className="ml-1.5 text-bg/70">{formatShortDate(hp.date)}</span>
        </div>
      )}
    </div>
  );
}
