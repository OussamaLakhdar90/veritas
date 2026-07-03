import { createContext, useCallback, useContext, useEffect, useRef, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, BellRing, Loader2, X } from 'lucide-react';
import { api, ActivityItem } from '../api';
import { useToast } from '../components/Toast';
import { cn } from '../components/cn';
import { SuccessCheck } from '../components/SuccessCheck';

/**
 * Server-truth Activity Center. ONE adaptive poll of GET /activity feeds three surfaces: (a) the floating
 * bottom-right dock of live/attention cards, (b) the TopBar ActivityBell badge + popover (via
 * useActivityCenter), and (c) outcome toasts fired on status transitions. Replaces the old
 * localStorage-tracked background-scans dock — the server now remembers what is running, so a reload or a
 * second browser sees the same feed and nothing gets "lost" when a modal closes.
 */

/** localStorage key holding the `${id}:${status}` pairs already toasted — a reload never re-announces. */
const NOTIFIED_KEY = 'veritas.activity.notified';
const NOTIFIED_CAP = 200;

function loadNotified(): string[] {
  try { const v = JSON.parse(localStorage.getItem(NOTIFIED_KEY) || '[]'); return Array.isArray(v) ? v : []; } catch { return []; }
}
function persistNotified(list: string[]) {
  try { localStorage.setItem(NOTIFIED_KEY, JSON.stringify(list.slice(-NOTIFIED_CAP))); } catch { /* quota/private mode — non-fatal */ }
}

const isTerminal = (s: ActivityItem['status']) => s === 'COMPLETED' || s === 'FAILED';
const isLive = (s: ActivityItem['status']) => s === 'QUEUED' || s === 'RUNNING';

type ActivityCtxValue = { items: ActivityItem[]; ack: (ids: string[]) => void };
const ActivityCtx = createContext<ActivityCtxValue>({ items: [], ack: () => {} });
export const useActivityCenter = () => useContext(ActivityCtx);

export function ActivityCenterProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();

  // The one adaptive poll: brisk while something is queued/running, relaxed otherwise.
  const q = useQuery({
    queryKey: ['activity'],
    queryFn: api.activity,
    refetchInterval: (query) => (query.state.data?.some((i) => isLive(i.status)) ? 5000 : 30000),
  });
  const items = q.data ?? [];

  // Optimistic dismiss: flip acked in the cache immediately; the next poll is the source of truth.
  const ack = useCallback((ids: string[]) => {
    qc.setQueryData<ActivityItem[]>(['activity'], (old) =>
      old?.map((i) => (ids.includes(i.id) ? { ...i, acked: true } : i)));
    api.ackActivity(ids).catch(() => { /* the poll restores the item if the server didn't record the ack */ });
  }, [qc]);

  // Toast only on transitions we OBSERVE (never on first load), deduped across reloads via localStorage.
  const prevStatuses = useRef<Map<string, string> | null>(null);
  useEffect(() => {
    if (!q.data) return;
    const before = prevStatuses.current;
    prevStatuses.current = new Map(q.data.map((i) => [i.id, i.status]));
    if (!before) return;
    const notified = loadNotified();
    let changed = false;
    for (const item of q.data) {
      const prev = before.get(item.id);
      if (!prev || prev === item.status) continue;
      const key = `${item.id}:${item.status}`;
      if (notified.includes(key)) continue;
      if (item.status === 'COMPLETED') toast.push('success', t('activity.toastDone', { label: item.label }));
      else if (item.status === 'FAILED') toast.push('error', t('activity.toastFailed', { label: item.label }));
      else if (item.status === 'WAITING_FOR_YOU') toast.push('info', t('activity.toastWaiting', { label: item.label }));
      else continue;
      notified.push(key);
      changed = true;
    }
    if (changed) persistNotified(notified);
  }, [q.data]); // eslint-disable-line react-hooks/exhaustive-deps

  // The dock lists what is in flight, plus finished work that still wants a decision.
  const dockItems = items.filter((i) => !isTerminal(i.status) || (i.needsAttention && !i.acked));

  return (
    <ActivityCtx.Provider value={{ items, ack }}>
      {children}
      {dockItems.length > 0 && (
        // Same corner as toasts but one notch lower z — a passing toast briefly overlays it, by design.
        <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2 print:hidden">
          {dockItems.map((item) => (
            <ActivityCard key={item.id} item={item} onDismiss={() => ack([item.id])} />
          ))}
        </div>
      )}
    </ActivityCtx.Provider>
  );
}

/** Icon-chip tone per status (kept as whole class strings so Tailwind's content scan never purges them). */
const CARD_TONE: Record<ActivityItem['status'], string> = {
  QUEUED: 'bg-gold/10 text-gold ring-gold/30',
  RUNNING: 'bg-gold/10 text-gold ring-gold/30',
  WAITING_FOR_YOU: 'bg-warning/10 text-warning ring-warning/30',
  COMPLETED: 'bg-success/10 text-success ring-success/30',
  FAILED: 'bg-danger/10 text-danger ring-danger/30',
};

function StatusIcon({ status }: { status: ActivityItem['status'] }) {
  if (status === 'COMPLETED') return <SuccessCheck className="h-5 w-5" />;
  if (status === 'FAILED') return <AlertTriangle className="h-4 w-4 text-danger" />;
  if (status === 'WAITING_FOR_YOU') return <BellRing className="h-4 w-4 text-warning" />;
  return <Loader2 className="h-4 w-4 animate-spin" />;
}

/** One dock card: status chip, label linking to the task, plain-language status line, dismiss X. */
function ActivityCard({ item, onDismiss }: { item: ActivityItem; onDismiss: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="pointer-events-auto overflow-hidden rounded-xl bg-surface p-3 shadow-pop ring-1 ring-border">
      <div className="flex items-start gap-3">
        <span className={cn('grid h-8 w-8 shrink-0 place-items-center rounded-lg ring-1', CARD_TONE[item.status])}>
          <StatusIcon status={item.status} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <Link to={item.link} className="truncate text-sm font-semibold text-ink-900 hover:underline">
              {item.label}
            </Link>
            <button type="button" onClick={onDismiss} aria-label={t('activity.dismiss')}
              className="shrink-0 text-muted hover:text-ink-700">
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
          <p className="mt-0.5 truncate text-xs text-muted">
            {t(`activity.status.${item.status}`)}{item.detail ? ` · ${item.detail}` : ''}
          </p>
        </div>
      </div>
    </div>
  );
}
