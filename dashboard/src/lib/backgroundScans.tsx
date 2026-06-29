import { createContext, useCallback, useContext, useEffect, useRef, useState, ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { Check, AlertTriangle, X, ArrowRight, Loader2 } from 'lucide-react';
import { api } from '../api';
import { useToast } from '../components/Toast';
import { cn } from '../components/cn';
import { stagePct, formatElapsed, useElapsed } from './scanStages';

/**
 * Keeps "Run in background" scans visible after the modal closes — the missing piece that made
 * a backgrounded scan feel stuck. A small dock of live cards (bottom-right) polls each scan,
 * shows its stage + elapsed time on a gradient progress bar, and hands off to the findings view
 * on completion. Tracked ids survive a reload via localStorage so a refresh doesn't lose them.
 */
const KEY = 'veritas-bg-scans';

type BgScan = { id: string; service: string; startedAt: number };

type BgCtxValue = { track: (scan: { id: string; service: string }) => void };
const BgCtx = createContext<BgCtxValue>({ track: () => {} });
export const useBackgroundScans = () => useContext(BgCtx);

function load(): BgScan[] {
  try { const v = JSON.parse(localStorage.getItem(KEY) || '[]'); return Array.isArray(v) ? v : []; } catch { return []; }
}
function persist(list: BgScan[]) {
  try { localStorage.setItem(KEY, JSON.stringify(list.slice(0, 6))); } catch { /* quota/private mode — non-fatal */ }
}

export function BackgroundScansProvider({ children }: { children: ReactNode }) {
  const [scans, setScans] = useState<BgScan[]>(load);

  const track = useCallback((scan: { id: string; service: string }) => {
    setScans((prev) => {
      if (prev.some((x) => x.id === scan.id)) return prev;
      const next = [...prev, { id: scan.id, service: scan.service, startedAt: Date.now() }];
      persist(next);
      return next;
    });
  }, []);

  const dismiss = useCallback((id: string) => {
    setScans((prev) => { const next = prev.filter((x) => x.id !== id); persist(next); return next; });
  }, []);

  return (
    <BgCtx.Provider value={{ track }}>
      {children}
      {scans.length > 0 && (
        // Sits in the same corner as toasts but a notch lower z — a passing toast briefly overlays it, by design.
        <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2">
          {scans.map((s) => <BgScanCard key={s.id} scan={s} onDismiss={() => dismiss(s.id)} />)}
        </div>
      )}
    </BgCtx.Provider>
  );
}

function BgScanCard({ scan, onDismiss }: { scan: BgScan; onDismiss: () => void }) {
  const navigate = useNavigate();
  const toast = useToast();
  const { t } = useTranslation();
  const notified = useRef(false);

  // Shares the ['scan', id] query key with the modal's poller, so there's no double polling.
  const q = useQuery({
    queryKey: ['scan', scan.id],
    queryFn: () => api.scan(scan.id),
    refetchInterval: (query) => {
      const s = query.state.data?.status;
      return s && s !== 'RUNNING' ? false : 1500;
    },
  });

  const data = q.data;
  const status = data?.status;
  const stage = data?.stage ?? 'QUEUED';
  const failed = status === 'FAILED';
  const done = status === 'COMPLETED';
  const running = !failed && !done;
  const startMs = (data?.startedAt && Date.parse(data.startedAt)) || scan.startedAt;
  const elapsed = useElapsed(startMs, running);
  const pct = failed || done ? 100 : stagePct(stage);
  const findings = data?.totalFindings ?? 0;

  // Announce the outcome once (the modal is gone, so the dock owns the notification now).
  useEffect(() => {
    if (notified.current || !data) return;
    if (done) { notified.current = true; toast.push('success', t('scan.toastComplete', { service: scan.service, count: findings })); }
    else if (failed) { notified.current = true; toast.push('error', t('scan.toastFailed', { service: scan.service })); }
  }, [done, failed, data]); // eslint-disable-line react-hooks/exhaustive-deps

  const open = () => { navigate(`/findings/${scan.id}`); onDismiss(); };

  return (
    <div className="pointer-events-auto overflow-hidden rounded-xl bg-surface shadow-pop ring-1 ring-border">
      <div className="h-1 w-full bg-ink-50">
        <div className={cn('h-full transition-all duration-700',
          failed ? 'bg-danger' : done ? 'bg-success' : 'bg-gold')}
          style={{ width: `${pct}%` }} />
      </div>
      <div className="flex items-start gap-3 p-3">
        <span className={cn('grid h-8 w-8 shrink-0 place-items-center rounded-lg ring-1',
          failed ? 'bg-danger/10 text-danger ring-danger/30'
            : done ? 'bg-success/10 text-success ring-success/30'
            : 'bg-gold/10 text-gold ring-gold/30')}>
          {failed ? <AlertTriangle className="h-4 w-4" />
            : done ? <Check className="h-4 w-4" />
            : <Loader2 className="h-4 w-4 animate-spin" />}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <p className="truncate text-[13px] font-semibold text-ink-900">{scan.service}</p>
            <button onClick={onDismiss} aria-label={t('scan.dockDismiss')} className="shrink-0 text-muted hover:text-ink-700">
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
          <p className="mt-0.5 truncate text-[12px] text-muted">
            {failed ? t('scan.dockFailed')
              : done ? t('scan.dockComplete', { count: findings })
              : `${t(`scan.${stage}.short`)} · ${formatElapsed(elapsed)}`}
          </p>
          {(done || failed) && (
            <button onClick={open} className="mt-1.5 inline-flex items-center gap-1 text-[12px] font-medium text-brand-600 hover:underline">
              {failed ? t('scan.dockOpenScan') : t('scan.dockViewFindings')} <ArrowRight className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
