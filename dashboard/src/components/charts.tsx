import React from 'react';
import { useCountUp } from './ui';

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

export function Donut({ slices, ariaLabel, centerValue, centerLabel, size = 120 }: {
  slices: DonutSlice[]; ariaLabel: string; centerValue?: React.ReactNode; centerLabel?: string; size?: number }) {
  const total = slices.reduce((n, s) => n + s.value, 0);
  let offset = 0;
  return (
    <svg viewBox="0 0 140 140" role="img" aria-label={ariaLabel} style={{ width: size, height: size }} className="chart-in shrink-0">
      <g transform="rotate(-90 70 70)" fill="none" strokeWidth={16}>
        <circle cx="70" cy="70" r={R} className="stroke-border/60" />
        {total > 0 && slices.map((s) => {
          const len = (s.value / total) * C;
          const seg = (
            <circle key={s.label} cx="70" cy="70" r={R} className={s.stroke}
              strokeDasharray={`${len.toFixed(2)} ${(C - len).toFixed(2)}`} strokeDashoffset={(-offset).toFixed(2)} />
          );
          offset += len;
          return seg;
        })}
      </g>
      {centerValue != null && <text x="70" y="68" textAnchor="middle" className="fill-ink-900 font-bold" fontSize="26">{centerValue}</text>}
      {centerLabel && <text x="70" y="86" textAnchor="middle" className="fill-muted" fontSize="10">{centerLabel}</text>}
    </svg>
  );
}

export function Gauge({ value, max, ariaLabel, centerLabel, size = 120, tone = 'stroke-success' }: {
  value: number; max: number; ariaLabel: string; centerLabel?: string; size?: number; tone?: string }) {
  const pct = max > 0 ? Math.round((value / max) * 100) : 0;
  const len = (pct / 100) * C;
  return (
    <svg viewBox="0 0 140 140" role="img" aria-label={ariaLabel} style={{ width: size, height: size }} className="chart-in shrink-0">
      <g transform="rotate(-90 70 70)" fill="none" strokeWidth={15}>
        <circle cx="70" cy="70" r={R} className="stroke-border/60" />
        <circle cx="70" cy="70" r={R} className={tone} strokeLinecap="round"
          strokeDasharray={`${len.toFixed(2)} ${(C - len).toFixed(2)}`} />
      </g>
      <text x="70" y="68" textAnchor="middle" className="fill-ink-900 font-bold" fontSize="24">{pct}%</text>
      {centerLabel && <text x="70" y="86" textAnchor="middle" className="fill-muted" fontSize="10">{centerLabel}</text>}
    </svg>
  );
}

export function Sparkline({ values, ariaLabel }: { values: number[]; ariaLabel: string }) {
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
    <svg viewBox={`0 0 ${w} ${h}`} role="img" aria-label={ariaLabel} preserveAspectRatio="none" className="chart-in h-20 w-full">
      <polyline fill="none" className="stroke-brand" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" points={pts} />
      <circle cx={lx.toFixed(1)} cy={ly.toFixed(1)} r={3.5} className="fill-brand" />
    </svg>
  );
}

export function MiniBar({ data, ariaLabel, format }: {
  data: { label: string; value: number }[]; ariaLabel: string; format?: (n: number) => string }) {
  const max = Math.max(...data.map((d) => d.value), 1);
  return (
    <div role="img" aria-label={ariaLabel} className="space-y-2">
      {data.map((d) => (
        <div key={d.label} className="flex items-center gap-3 text-xs">
          <span className="w-32 shrink-0 truncate text-ink-700" title={d.label}>{d.label}</span>
          <div className="h-2 flex-1 rounded-full bg-ink-100">
            <div className="h-2 rounded-full bg-brand" style={{ width: `${Math.max((d.value / max) * 100, 2)}%` }} />
          </div>
          <span className="w-16 shrink-0 text-right tabular-nums text-ink-900">{format ? format(d.value) : d.value}</span>
        </div>
      ))}
    </div>
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
      {centerLabel && <text x="70" y="96" textAnchor="middle" className="fill-muted" fontSize="8.5">{centerLabel}</text>}
    </svg>
  );
}
