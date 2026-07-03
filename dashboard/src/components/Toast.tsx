import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { useTranslation } from 'react-i18next';
import { CheckCircle2, AlertTriangle, Info, X } from 'lucide-react';
import { cn } from './cn';
import { exitEase, isTestEnv, toastSpring } from '../lib/motion';

type ToastKind = 'success' | 'error' | 'info';
type Toast = { id: number; kind: ToastKind; message: string };
type ToastCtx = { push: (kind: ToastKind, message: string) => void };

const Ctx = createContext<ToastCtx>({ push: () => {} });
export const useToast = () => useContext(Ctx);

let seq = 1;

const ICON = { success: CheckCircle2, error: AlertTriangle, info: Info };
const TONE = {
  success: 'ring-success/30 text-success',
  error: 'ring-danger/30 text-danger',
  info: 'ring-info/30 text-info',
};
const BAR = { success: 'bg-success/60', error: 'bg-danger/60', info: 'bg-info/60' };

/** Errors linger longer — a slow reader must be able to finish them. */
const lifespanMs = (kind: ToastKind) => (kind === 'error' ? 8000 : 5000);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const remove = useCallback((id: number) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const push = useCallback((kind: ToastKind, message: string) => {
    const id = seq++;
    setToasts((t) => [...t, { id, kind, message }].slice(-4));   // cap the stack — old ones yield
  }, []);

  return (
    <Ctx.Provider value={{ push }}>
      {children}
      {/* Live region so screen readers announce toasts; errors assert, others are polite. */}
      <div className="fixed bottom-4 right-4 z-[60] flex w-80 flex-col gap-2 print:hidden" aria-live="polite" aria-atomic="false">
        <AnimatePresence mode="sync" initial={false}>
          {toasts.map((t) => (
            <ToastCard key={t.id} toast={t} onDismiss={() => remove(t.id)} />
          ))}
        </AnimatePresence>
      </div>
    </Ctx.Provider>
  );
}

/**
 * One toast: slides in on a snappy spring, shows its remaining lifespan as a shrinking bar, PAUSES (both the
 * bar and the removal timer) while hovered or focused so a slow reader can finish, and glides up (height
 * collapse) on exit. This provider mounts OUTSIDE the app's MotionConfig, so reduced-motion is handled
 * explicitly; under Vitest everything is static and dismissal stays synchronous.
 */
function ToastCard({ toast, onDismiss }: { toast: Toast; onDismiss: () => void }) {
  const { t: tr } = useTranslation();
  const reduce = useReducedMotion();
  const [paused, setPaused] = useState(false);
  const remaining = useRef(lifespanMs(toast.kind));
  const startedAt = useRef(Date.now());

  useEffect(() => {
    if (paused) {
      remaining.current -= Date.now() - startedAt.current;
      return undefined;
    }
    startedAt.current = Date.now();
    const timer = setTimeout(onDismiss, Math.max(0, remaining.current));
    return () => clearTimeout(timer);
  }, [paused, onDismiss]);

  const Icon = ICON[toast.kind];
  const body = (
    <div role={toast.kind === 'error' ? 'alert' : 'status'}
      onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)} onBlur={() => setPaused(false)}
      className={cn('relative flex items-start gap-3 overflow-hidden rounded-lg bg-surface p-3 shadow-pop ring-1', TONE[toast.kind])}>
      <Icon className="mt-0.5 h-4 w-4 shrink-0" />
      <p className="flex-1 text-sm text-ink-900">{toast.message}</p>
      <button onClick={onDismiss} aria-label={tr('common.dismissToast')}
        className="rounded-md p-1 hover:bg-ink-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/40">
        <X className="h-3.5 w-3.5 text-muted" />
      </button>
      {!isTestEnv && !reduce && (
        <span aria-hidden="true"
          className={cn('absolute inset-x-0 bottom-0 h-0.5 origin-left', BAR[toast.kind])}
          style={{ animation: `toastLife ${lifespanMs(toast.kind)}ms linear both`,
            animationPlayState: paused ? 'paused' : 'running' }} />
      )}
    </div>
  );

  if (isTestEnv) {
    return body;
  }
  return (
    <motion.div layout={!reduce} initial={reduce ? false : { opacity: 0, x: 24 }} animate={{ opacity: 1, x: 0 }}
      exit={reduce ? { opacity: 0, transition: { duration: 0 } }
        : { opacity: 0, height: 0, marginTop: -8, transition: { duration: 0.15, ease: exitEase } }}
      transition={toastSpring}>
      {body}
    </motion.div>
  );
}
