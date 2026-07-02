import React from 'react';
import { Loader2, ArrowUp, ArrowDown, ArrowUpDown } from 'lucide-react';
import { useReducedMotion } from 'framer-motion';
import { useTranslation } from 'react-i18next';
import { cn } from './cn';
import { TONE } from '../theme/tokens';
import { isTestEnv } from '../lib/motion';

/** Animate 0 → target on mount; resolves instantly under reduced-motion or in tests (no rAF flicker / flake). */
export function useCountUp(target: number): number {
  const reduce = useReducedMotion();
  const [val, setVal] = React.useState<number>(() => (reduce || isTestEnv ? target : 0));
  React.useEffect(() => {
    if (reduce || isTestEnv) { setVal(target); return; }
    let raf = 0;
    const start = performance.now();
    const dur = 700;
    const tick = (now: number) => {
      const p = Math.min((now - start) / dur, 1);
      // Snap to the EXACT target on the final frame — rounding the eased value can land $9.99 on $10.
      setVal(p === 1 ? target : Math.round(target * (1 - Math.pow(1 - p, 3))));
      if (p < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, reduce]);
  return val;
}

/* ── Button ─────────────────────────────────────────────────────────────── */
type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md';
  loading?: boolean;
};
export function Button({ variant = 'primary', size = 'md', loading, className, children, disabled, ...rest }: ButtonProps) {
  const base = 'inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors '
    + 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/40 disabled:opacity-50 disabled:cursor-not-allowed';
  const sizes = { sm: 'h-8 px-3 text-sm', md: 'h-9 px-4 text-sm' };
  const variants = {
    primary: 'bg-brand text-white hover:bg-brand-700',
    secondary: 'bg-surface text-ink-900 ring-1 ring-border hover:bg-ink-50',
    ghost: 'text-ink-700 hover:bg-ink-50',
    danger: 'bg-danger text-white hover:opacity-90',
  };
  return (
    <button className={cn(base, sizes[size], variants[variant], className)} disabled={disabled || loading} {...rest}>
      {loading && <Loader2 className="h-4 w-4 animate-spin" />}
      {children}
    </button>
  );
}

/* ── Card ───────────────────────────────────────────────────────────────── */
/** Honest affordances: a resting card never lifts on hover — only `interactive` cards (genuinely clickable
 *  surfaces) get the lift, so hover-elevation reliably means "you can click this". */
export function Card({ className, children, interactive }:
  { className?: string; children: React.ReactNode; interactive?: boolean }) {
  return (
    <div className={cn('rounded-xl bg-surface ring-1 ring-border shadow-card',
      interactive && 'transition-[box-shadow,transform] duration-base ease-calm hover:shadow-lift motion-safe:hover:-translate-y-0.5',
      className)}>
      {children}
    </div>
  );
}
export function CardBody({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn('p-5', className)}>{children}</div>;
}
export function CardHeader({ title, subtitle, action }: { title: React.ReactNode; subtitle?: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
      <div>
        <h3 className="text-md font-semibold text-ink-900">{title}</h3>
        {subtitle && <p className="mt-0.5 text-sm text-muted">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

/* ── Badge / Pill ───────────────────────────────────────────────────────── */
export function Badge({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-2xs font-semibold uppercase tracking-wide', className)}>
      {children}
    </span>
  );
}

/* ── Spinner ────────────────────────────────────────────────────────────── */
export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn('h-4 w-4 animate-spin text-muted', className)} />;
}

/* ── Skeleton ───────────────────────────────────────────────────────────── */
export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded-md bg-ink-100', className)} />;
}

/* ── Empty state ────────────────────────────────────────────────────────── */
export function EmptyState({ icon: Icon, title, body, action }:
  { icon?: React.ComponentType<{ className?: string }>; title: string; body?: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface px-6 py-12 text-center">
      {Icon && <Icon className="mb-3 h-8 w-8 text-muted" />}
      <p className="text-sm font-semibold text-ink-900">{title}</p>
      {body && <p className="mt-1 max-w-md text-sm text-muted">{body}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

/* ── Error state ────────────────────────────────────────────────────────── */
export function ErrorState({ message }: { message?: string }) {
  const { t } = useTranslation();
  return (
    <Card className="border-l-4 border-l-danger">
      <CardBody>
        <p className="text-sm text-danger" role="alert">
          {t('common.errorTitle')}{message ? `: ${message}` : '.'} {t('common.errorRetry')}
        </p>
      </CardBody>
    </Card>
  );
}

/* ── Form fields ────────────────────────────────────────────────────────── */
export function Field({ label, hint, error, children }:
  { label: string; hint?: string; error?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-medium text-ink-700">{label}</span>
      {children}
      {error ? <span className="mt-1 block text-xs text-danger">{error}</span>
        : hint ? <span className="mt-1 block text-xs text-muted">{hint}</span> : null}
    </label>
  );
}
const fieldCls = 'h-9 w-full rounded-md bg-surface px-3 text-sm text-ink-900 ring-1 ring-border '
  + 'placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-brand/40';
export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...rest }, ref) {
    return <input ref={ref} className={cn(fieldCls, className)} {...rest} />;
  });
export function Select({ className, children, ...rest }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={cn(fieldCls, className)} {...rest}>{children}</select>;
}
export const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  function Textarea({ className, ...rest }, ref) {
    return <textarea ref={ref} className={cn(fieldCls, 'h-auto min-h-[110px] py-2 font-mono text-sm', className)} {...rest} />;
  });

/* ── KPI tile ───────────────────────────────────────────────────────────── */
export interface KpiTrend { dir: 'up' | 'down' | 'flat'; label: string; good?: boolean }
export function KpiTile({ label, value, sub, tone = 'ink', trend }:
  { label: string; value: React.ReactNode; sub?: React.ReactNode;
    tone?: 'ink' | 'brand' | 'success' | 'warning' | 'danger'; trend?: KpiTrend }) {
  const toneCls = { ink: 'text-ink-900', brand: 'text-brand', success: 'text-success', warning: 'text-warning', danger: 'text-danger' }[tone];
  const trendTone = trend?.good === undefined ? TONE.muted : trend.good ? TONE.ok : TONE.danger;
  const arrow = trend?.dir === 'up' ? '▲' : trend?.dir === 'down' ? '▼' : '•';
  const numeric = typeof value === 'number';
  const counted = useCountUp(numeric ? (value as number) : 0);
  const shown = numeric ? counted : value;
  return (
    <Card className="flex-1 transition-transform duration-base ease-calm motion-safe:hover:-translate-y-0.5">
      <CardBody>
        <p className="text-xs font-medium uppercase tracking-wide text-muted">{label}</p>
        <p className={cn('mt-1 text-4xl font-semibold tracking-tight tabular-nums', toneCls)}>{shown}</p>
        {sub && <p className="mt-1 text-sm text-muted">{sub}</p>}
        {trend && (
          <span className={cn('mt-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-2xs font-semibold', trendTone)}>
            <span aria-hidden="true">{arrow}</span> {trend.label}
          </span>
        )}
      </CardBody>
    </Card>
  );
}

/* ── Table primitives ───────────────────────────────────────────────────── */
export function Table({ head, children }: { head: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted">{head}</tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
export function Th({ className, children }: { className?: string; children?: React.ReactNode }) {
  return <th className={cn('px-5 py-3 font-medium', className)}>{children}</th>;
}
export function Td({ className, children, ...rest }: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn('px-5 py-3', className)} {...rest}>{children}</td>;
}
export function Row({ className, children, ...rest }: React.HTMLAttributes<HTMLTableRowElement>) {
  return <tr className={cn('border-b border-border/60 align-top last:border-0 hover:bg-ink-50/60', className)} {...rest}>{children}</tr>;
}

/* ── Client-side sorting ────────────────────────────────────────────────── */
export type SortDir = 'asc' | 'desc';
export interface SortState { key?: string; dir: SortDir; toggle: (k: string) => void; }

/** Sort `rows` by a column key (optional custom accessor map — keep it stable, e.g. module-level/useMemo). */
export function useSort<T>(rows: T[], initial?: { key: string; dir?: SortDir },
  accessors?: Record<string, (r: T) => string | number | null | undefined>): { sorted: T[] } & SortState {
  const [key, setKey] = React.useState<string | undefined>(initial?.key);
  const [dir, setDir] = React.useState<SortDir>(initial?.dir ?? 'asc');
  const toggle = (k: string) => {
    if (k === key) {
      setDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setKey(k);
      setDir('asc');
    }
  };
  const sorted = React.useMemo(() => {
    if (!key) return rows;
    const acc = accessors?.[key] ?? ((r: T) => (r as Record<string, unknown>)[key] as string | number);
    return [...rows].sort((a, b) => {
      const av = acc(a);
      const bv = acc(b);
      let c: number;
      if (av == null && bv == null) c = 0;
      else if (av == null) c = -1;
      else if (bv == null) c = 1;
      else if (typeof av === 'number' && typeof bv === 'number') c = av - bv;
      else c = String(av).localeCompare(String(bv));
      return dir === 'asc' ? c : -c;
    });
  }, [rows, key, dir, accessors]);
  return { sorted, key, dir, toggle };
}

/** Clickable, accessible sort header. */
export function SortableTh({ label, sortKey, sort, className }:
  { label: string; sortKey: string; sort: SortState; className?: string }) {
  const active = sort.key === sortKey;
  const Icon = !active ? ArrowUpDown : sort.dir === 'asc' ? ArrowUp : ArrowDown;
  return (
    <th className={cn('px-5 py-3 font-medium', className)}
      aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" onClick={() => sort.toggle(sortKey)}
        className="inline-flex items-center gap-1 hover:text-ink-900 focus-visible:underline focus-visible:outline-none">
        {label}<Icon className={cn('h-3 w-3', active ? 'text-ink-700' : 'text-muted/50')} />
      </button>
    </th>
  );
}

/* ── Page header ────────────────────────────────────────────────────────── */
export function PageHeader({ title, subtitle, actions }:
  { title: string; subtitle?: string; actions?: React.ReactNode }) {
  return (
    <div className="mb-6 flex items-end justify-between gap-4">
      <div>
        <h1 className="text-title font-semibold text-ink-900">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
