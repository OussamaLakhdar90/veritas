import React from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from './cn';

/* ── Button ─────────────────────────────────────────────────────────────── */
type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md';
  loading?: boolean;
};
export function Button({ variant = 'primary', size = 'md', loading, className, children, disabled, ...rest }: ButtonProps) {
  const base = 'inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors '
    + 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/40 disabled:opacity-50 disabled:cursor-not-allowed';
  const sizes = { sm: 'h-8 px-3 text-[13px]', md: 'h-9 px-4 text-sm' };
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
export function Card({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn('rounded-xl bg-surface ring-1 ring-border shadow-card', className)}>{children}</div>;
}
export function CardBody({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn('p-5', className)}>{children}</div>;
}
export function CardHeader({ title, subtitle, action }: { title: React.ReactNode; subtitle?: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
      <div>
        <h3 className="text-[15px] font-semibold text-ink-900">{title}</h3>
        {subtitle && <p className="mt-0.5 text-[13px] text-muted">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

/* ── Badge / Pill ───────────────────────────────────────────────────────── */
export function Badge({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide', className)}>
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
      {body && <p className="mt-1 max-w-md text-[13px] text-muted">{body}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

/* ── Form fields ────────────────────────────────────────────────────────── */
export function Field({ label, hint, error, children }:
  { label: string; hint?: string; error?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[13px] font-medium text-ink-700">{label}</span>
      {children}
      {error ? <span className="mt-1 block text-[12px] text-danger">{error}</span>
        : hint ? <span className="mt-1 block text-[12px] text-muted">{hint}</span> : null}
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

/* ── KPI tile ───────────────────────────────────────────────────────────── */
export function KpiTile({ label, value, sub, tone = 'ink' }:
  { label: string; value: React.ReactNode; sub?: React.ReactNode; tone?: 'ink' | 'brand' | 'success' | 'warning' | 'danger' }) {
  const toneCls = { ink: 'text-ink-900', brand: 'text-brand', success: 'text-success', warning: 'text-warning', danger: 'text-danger' }[tone];
  return (
    <Card className="flex-1">
      <CardBody>
        <p className="text-[12px] font-medium uppercase tracking-wide text-muted">{label}</p>
        <p className={cn('mt-1 text-3xl font-semibold tabular-nums', toneCls)}>{value}</p>
        {sub && <p className="mt-1 text-[13px] text-muted">{sub}</p>}
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
          <tr className="border-b border-border text-left text-[12px] uppercase tracking-wide text-muted">{head}</tr>
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

/* ── Page header ────────────────────────────────────────────────────────── */
export function PageHeader({ title, subtitle, actions }:
  { title: string; subtitle?: string; actions?: React.ReactNode }) {
  return (
    <div className="mb-6 flex items-end justify-between gap-4">
      <div>
        <h1 className="text-[22px] font-semibold text-ink-900">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
