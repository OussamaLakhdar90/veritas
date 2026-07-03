import React from 'react';
import { Loader2, ArrowUp, ArrowDown, ArrowUpDown, TrendingUp, TrendingDown, Minus, RefreshCw, ChevronDown } from 'lucide-react';
import { motion, useReducedMotion } from 'framer-motion';
import { useTranslation } from 'react-i18next';
import { cn } from './cn';
import { TONE } from '../theme/tokens';
import { isTestEnv, pageTransition, rowDelay } from '../lib/motion';
import { formatRelativeEpoch } from '../lib/format';

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
  // A press dips the button 2% (motion-safe only); transition covers colour AND transform so the dip eases back.
  const base = 'inline-flex items-center justify-center gap-2 rounded-md font-medium transition-[background-color,transform] motion-safe:active:scale-[0.98] '
    + 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/40 disabled:opacity-50 disabled:cursor-not-allowed';
  const sizes = { sm: 'h-8 px-3 text-sm', md: 'h-9 px-4 text-sm' };
  const variants = {
    primary: 'bg-brand text-white hover:bg-brand-700',
    secondary: 'bg-surface text-ink-900 ring-1 ring-border hover:bg-ink-50',
    ghost: 'text-ink-700 hover:bg-ink-50',
    // A dedicated darker-danger hover token (not opacity) so the destructive action stays fully opaque and legible.
    danger: 'bg-danger text-white hover:bg-danger-strong',
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
        {/* h2: a card title sits under the page <h1>, so it is a level-2 heading — the old h3 skipped a level. */}
        <h2 className="text-md font-semibold text-ink-900">{title}</h2>
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
/** The animate-pulse base (asserted by page tests) plus a token-var shimmer overlay; both stop under
 *  reduced-motion (the shimmer via a @media rule in index.css). */
export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('skeleton-shimmer animate-pulse rounded-md bg-ink-100', className)} />;
}

/** A loading placeholder for a data table: a header bar + N body rows. Announces itself politely
 *  (role="status" + an sr-only label) so screen readers — and page tests asserting the *.loading string
 *  — still see the loading state that the old lone spinner conveyed. */
export function TableSkeleton({ rows = 5, label }: { rows?: number; label: string }) {
  return (
    <div role="status" aria-live="polite" className="space-y-2 p-5">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-6 w-1/3" />
      {Array.from({ length: rows }).map((_, i) => <Skeleton key={i} className="h-10" />)}
    </div>
  );
}

/** A loading placeholder card for a non-tabular panel — mirrors TableSkeleton's a11y contract. */
export function CardSkeleton({ lines = 3, label }: { lines?: number; label: string }) {
  return (
    <Card>
      <CardBody>
        <div role="status" aria-live="polite" className="space-y-3">
          <span className="sr-only">{label}</span>
          <Skeleton className="h-6 w-1/4" />
          {Array.from({ length: lines }).map((_, i) => <Skeleton key={i} className={cn('h-4', i === lines - 1 && 'w-2/3')} />)}
        </div>
      </CardBody>
    </Card>
  );
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
/** `detail` (the raw server error) stays one click away behind a "Technical details" disclosure — the
 *  headline is always the plain-language message. */
export function ErrorState({ message, detail }: { message?: string; detail?: string }) {
  const { t } = useTranslation();
  return (
    <Card className="border-l-4 border-l-danger">
      <CardBody>
        <p className="text-sm text-danger" role="alert">
          {t('common.errorTitle')}{message ? `: ${message}` : '.'} {t('common.errorRetry')}
        </p>
        {detail && (
          <details className="mt-2">
            <summary className="cursor-pointer text-xs text-muted">{t('common.technicalDetails')}</summary>
            <pre className="mt-1 whitespace-pre-wrap break-all font-mono text-xs text-muted">{detail}</pre>
          </details>
        )}
      </CardBody>
    </Card>
  );
}

/* ── Form fields ────────────────────────────────────────────────────────── */
/** A Field with an `error` puts its wrapped control into the invalid state via CONTEXT (not cloneElement — the
 *  control is often wrapped, e.g. the Select's chevron span, so a clone wouldn't reach it). */
const FieldInvalidCtx = React.createContext(false);

export function Field({ label, hint, error, children }:
  { label: string; hint?: string; error?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-medium text-ink-700">{label}</span>
      <FieldInvalidCtx.Provider value={!!error}>{children}</FieldInvalidCtx.Provider>
      {error ? <span className="mt-1 block text-xs text-danger">{error}</span>
        : hint ? <span className="mt-1 block text-xs text-muted">{hint}</span> : null}
    </label>
  );
}
const fieldCls = 'h-9 w-full rounded-md bg-surface px-3 text-sm text-ink-900 ring-1 ring-border '
  + 'placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-brand/40';
/** Invalid ring — a red ring at rest and on focus, so an error reads without waiting for a toast. */
const invalidCls = 'ring-danger focus:ring-danger/40';
/** Read the Field's invalid state; components can also force it with an explicit `invalid` prop. */
function useInvalid(explicit?: boolean): boolean {
  return React.useContext(FieldInvalidCtx) || !!explicit;
}
export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }>(
  function Input({ className, invalid, ...rest }, ref) {
    const bad = useInvalid(invalid);
    return <input ref={ref} aria-invalid={bad || undefined} className={cn(fieldCls, bad && invalidCls, className)} {...rest} />;
  });
export function Select({ className, invalid, children, ...rest }:
  React.SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean }) {
  const bad = useInvalid(invalid);
  // appearance-none drops the OS chevron; a lucide chevron is drawn in an overlay span so it matches the app.
  // The className stays on the <select> (callers style width/height there); the wrapper only positions the icon.
  return (
    <span className="relative block">
      <select aria-invalid={bad || undefined} className={cn(fieldCls, 'appearance-none pr-9', bad && invalidCls, className)} {...rest}>
        {children}
      </select>
      <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" aria-hidden="true" />
    </span>
  );
}
export const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean }>(
  function Textarea({ className, invalid, ...rest }, ref) {
    const bad = useInvalid(invalid);
    return <textarea ref={ref} aria-invalid={bad || undefined}
      className={cn(fieldCls, 'h-auto min-h-[110px] py-2 font-mono text-sm', bad && invalidCls, className)} {...rest} />;
  });

/* ── KPI tile ───────────────────────────────────────────────────────────── */
export interface KpiTrend { dir: 'up' | 'down' | 'flat'; label: string; good?: boolean }
export function KpiTile({ label, value, sub, tone = 'ink', trend }:
  { label: string; value: React.ReactNode; sub?: React.ReactNode;
    tone?: 'ink' | 'brand' | 'success' | 'warning' | 'danger'; trend?: KpiTrend }) {
  const toneCls = { ink: 'text-ink-900', brand: 'text-brand', success: 'text-success', warning: 'text-warning', danger: 'text-danger' }[tone];
  // A coloured cap only when the tone is a true STATUS — red on a brand tile would read as an alarm.
  const capCls = { success: 'bg-success', warning: 'bg-warning', danger: 'bg-danger' }[tone as string];
  const trendTone = trend?.good === undefined ? TONE.muted : trend.good ? TONE.ok : TONE.danger;
  const TrendIcon = trend?.dir === 'up' ? TrendingUp : trend?.dir === 'down' ? TrendingDown : Minus;
  const numeric = typeof value === 'number';
  const counted = useCountUp(numeric ? (value as number) : 0);
  const shown = numeric ? counted : value;
  return (
    <Card className="relative flex-1 overflow-hidden transition-transform duration-base ease-calm motion-safe:hover:-translate-y-0.5">
      {capCls && <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-[3px]', capCls)} />}
      <CardBody>
        <p className="text-xs font-medium uppercase tracking-wide text-muted">{label}</p>
        <p className={cn('mt-1 text-stat font-semibold tracking-tight tabular-nums', toneCls)}>{shown}</p>
        {sub && <p className="mt-1 text-sm text-muted">{sub}</p>}
        {trend && (
          <span className={cn('mt-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-2xs font-semibold', trendTone)}>
            <TrendIcon className="h-3 w-3" aria-hidden="true" /> {trend.label}
          </span>
        )}
      </CardBody>
    </Card>
  );
}

/* ── Table primitives ───────────────────────────────────────────────────── */
/** `stickyHead` opts a long list into a scroll region whose header stays pinned — column labels never
 *  scroll out of view (a table-stakes affordance on Findings/Defects/Snyk/TestCases). Short tables omit it. */
export function Table({ head, children, stickyHead }:
  { head: React.ReactNode; children: React.ReactNode; stickyHead?: boolean }) {
  return (
    <div className={cn('overflow-x-auto', stickyHead && 'max-h-[70vh] overflow-y-auto')}>
      <table className="w-full text-sm">
        <thead className={cn(stickyHead && 'sticky top-0 z-10 bg-surface')}>
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
/** A table body row. Pass `index` to give it an index-delayed entrance (40ms/row, capped at 8 — a reading-order
 *  aid, not a show); omit it for a static row. Motion resolves to its final state instantly under Vitest. */
export function Row({ className, index, children, ...rest }:
  React.HTMLAttributes<HTMLTableRowElement> & { index?: number }) {
  const cls = cn('border-b border-border/60 align-top last:border-0 hover:bg-ink-50/60', className);
  if (index == null || isTestEnv) {
    return <tr className={cls} {...rest}>{children}</tr>;
  }
  return (
    <motion.tr className={cls} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
      transition={{ ...pageTransition, delay: rowDelay(index) }}
      {...(rest as React.ComponentProps<typeof motion.tr>)}>
      {children}
    </motion.tr>
  );
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

/* ── Page container ─────────────────────────────────────────────────────── */
/** Centres a page's content and caps its width. `narrow` (reading/checklist pages) and `wide` (form
 *  wizards) both mx-auto so they never left-hug on a wide monitor; `full` spans the whole content column
 *  (dense tables). Use this instead of a hand-rolled `max-w-*` root (a Vitest guard enforces it). */
export function PageContainer({ variant = 'full', className, children }:
  { variant?: 'narrow' | 'wide' | 'full'; className?: string; children: React.ReactNode }) {
  const width = { narrow: 'mx-auto max-w-3xl', wide: 'mx-auto max-w-4xl', full: '' }[variant];
  return <div className={cn(width, className)}>{children}</div>;
}

/* ── Freshness + manual refresh ─────────────────────────────────────────── */
/** Re-render every `intervalMs` so a relative timestamp ("2 min ago") keeps ticking. Off in tests (no timers). */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = React.useState(() => Date.now());
  React.useEffect(() => {
    if (isTestEnv) return;
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);
  return now;
}

/**
 * "Updated 2 min ago" + a refresh button. `updatedAt` is react-query's `dataUpdatedAt` (epoch ms). The data
 * does NOT poll while idle, so this is a real freshness signal, not decoration — the button refetches on demand.
 */
export function FreshnessStamp({ updatedAt, onRefresh, refreshing }:
  { updatedAt?: number; onRefresh: () => void; refreshing?: boolean }) {
  const { t } = useTranslation();
  useNow(30_000); // tick the relative label
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted">
      {updatedAt ? <span aria-live="polite">{t('common.updatedAgo', { time: formatRelativeEpoch(updatedAt) })}</span> : null}
      <button type="button" onClick={onRefresh} disabled={refreshing} aria-label={t('common.refresh')}
        className="grid h-8 w-8 place-items-center rounded-md text-ink-600 hover:bg-ink-50 disabled:opacity-50">
        <RefreshCw className={cn('h-4 w-4', refreshing && 'animate-spin')} />
      </button>
    </span>
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
