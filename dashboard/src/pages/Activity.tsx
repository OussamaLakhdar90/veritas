import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Activity as ActivityIcon, AlertTriangle, BellRing, ChevronRight, Loader2 } from 'lucide-react';
import { api, ActivityItem } from '../api';
import { Badge, Card, CardBody, CardSkeleton, EmptyState, ErrorState, FreshnessStamp, KpiTile, PageHeader } from '../components/ui';
import { StaggerItem, StaggerList } from '../components/motion';
import { SuccessCheck } from '../components/SuccessCheck';
import { Tooltip } from '../components/Tooltip';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { formatDateTime, formatDayLong, formatDuration, formatRelative } from '../lib/format';

/**
 * The Activity page — the always-discoverable home for "what is the platform doing right now, what is
 * waiting on me, and what recently finished". It reads the SAME server-truth feed as the TopBar bell and
 * the floating dock (GET /activity), and needs no backend of its own. Three calm sections — Waiting on you,
 * In progress, Recent history (7 days) — each derivable from ActivityItem[]; nothing technical ever renders
 * (no raw stage codes, ids or routes). Non-technical, bilingual, VP-legible.
 */

type Status = ActivityItem['status'];

/** True when an item is actively asking for a human decision (and hasn't been dismissed from the dock). */
const isWaiting = (i: ActivityItem) => i.needsAttention && !i.acked;
const isLive = (i: ActivityItem) => i.status === 'QUEUED' || i.status === 'RUNNING';
const isTerminal = (s: Status) => s === 'COMPLETED' || s === 'FAILED';

/** ms since epoch, or NaN for a missing/invalid instant. */
function ms(iso?: string): number {
  return iso ? Date.parse(iso) : NaN;
}
/** Compare two instants with nulls/invalids always sorted last; `dir` +1 ascending, -1 descending. */
function cmpTime(a?: string, b?: string, dir: 1 | -1 = 1): number {
  const ta = ms(a);
  const tb = ms(b);
  const na = Number.isNaN(ta);
  const nb = Number.isNaN(tb);
  if (na && nb) return 0;
  if (na) return 1;
  if (nb) return -1;
  return dir * (ta - tb);
}

export interface ActivityGroups { waiting: ActivityItem[]; inProgress: ActivityItem[]; history: ActivityItem[] }

/**
 * Split the feed into the three page sections, mutually exclusive by priority: an item that needs you wins
 * over "in progress", which wins over "history". So a dismissed failure falls to history, and a still-live
 * task never hides in history.
 */
export function partitionActivity(items: ActivityItem[]): ActivityGroups {
  const waiting = items.filter(isWaiting).sort((a, b) => cmpTime(a.startedAt, b.startedAt, 1));
  const inProgress = items
    .filter((i) => isLive(i) && !isWaiting(i))
    .sort((a, b) => (rank(a.status) - rank(b.status)) || cmpTime(a.startedAt, b.startedAt, -1));
  const history = items
    .filter((i) => !isWaiting(i) && !(isLive(i) && !isWaiting(i)))
    .sort((a, b) => cmpTime(a.finishedAt, b.finishedAt, -1));
  return { waiting, inProgress, history };
}
/** RUNNING sorts above QUEUED within "in progress". */
function rank(s: Status): number {
  return s === 'RUNNING' ? 0 : 1;
}

export interface ActivityKpis { inProgress: number; waitingOnYou: number; finished7d: number }

/** The three header counts, each an honest predicate over the feed. */
export function activityKpis(items: ActivityItem[]): ActivityKpis {
  return {
    inProgress: items.filter(isLive).length,
    waitingOnYou: items.filter(isWaiting).length,
    finished7d: items.filter((i) => isTerminal(i.status)).length,
  };
}

/** 'today' | 'yesterday' | a local 'YYYY-MM-DD' | 'earlier' — the day bucket a history item belongs to. */
export function dayBucket(iso: string | undefined, nowMs: number): string {
  const t = ms(iso);
  if (Number.isNaN(t)) return 'earlier';
  const startOfDay = (v: number) => { const d = new Date(v); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const day = startOfDay(t);
  const today = startOfDay(nowMs);
  const oneDay = 86_400_000;
  if (day === today) return 'today';
  if (day === today - oneDay) return 'yesterday';
  const d = new Date(t);
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export interface DayGroup { key: string; items: ActivityItem[] }

/** Group already-sorted (finishedAt desc) history rows under day separators, preserving order. */
export function groupHistoryByDay(items: ActivityItem[], nowMs: number): DayGroup[] {
  const out: DayGroup[] = [];
  const at = new Map<string, number>();
  for (const item of items) {
    const key = dayBucket(item.finishedAt ?? item.startedAt, nowMs);
    if (!at.has(key)) { at.set(key, out.length); out.push({ key, items: [] }); }
    out[at.get(key)!].items.push(item);
  }
  return out;
}

function dayHeading(key: string, t: TFunction): string {
  if (key === 'today') return t('activity.day.today');
  if (key === 'yesterday') return t('activity.day.yesterday');
  if (key === 'earlier') return t('activity.day.earlier');
  return formatDayLong(key);
}

// ── Per-status presentation ────────────────────────────────────────────────────
const STATUS_BADGE: Record<Status, string> = {
  QUEUED: TONE.info, RUNNING: TONE.info, WAITING_FOR_YOU: TONE.warn, COMPLETED: TONE.ok, FAILED: TONE.danger,
};
const CHIP_TONE: Record<Status, string> = {
  QUEUED: 'bg-info/10 text-info ring-info/30',
  RUNNING: 'bg-info/10 text-info ring-info/30',
  WAITING_FOR_YOU: 'bg-warning/10 text-warning ring-warning/30',
  COMPLETED: 'bg-success/10 text-success ring-success/30',
  FAILED: 'bg-danger/10 text-danger ring-danger/30',
};

function StatusIcon({ status }: { status: Status }) {
  if (status === 'COMPLETED') return <SuccessCheck className="h-5 w-5" />;
  if (status === 'FAILED') return <AlertTriangle className="h-4 w-4 text-danger" />;
  if (status === 'WAITING_FOR_YOU') return <BellRing className="h-4 w-4 text-warning" />;
  return <Loader2 className="h-4 w-4 animate-spin text-info" />;
}

/** The human detail under the label: item.detail if the server sent one, else a plain-language stage label,
 *  else nothing — a raw stage code, id or route must never reach the screen. */
function detailLine(item: ActivityItem, t: TFunction): string {
  if (item.detail) return item.detail;
  if (item.stage) return t(`activity.stage.${item.stage}`, { defaultValue: '' });
  return '';
}

/** The right-aligned time text, phrased for the status (never an invented "0 s" for a task that hasn't run). */
function timeText(item: ActivityItem, t: TFunction): string {
  if (item.status === 'RUNNING' && item.startedAt) return t('activity.time.running', { time: formatRelative(item.startedAt) });
  if (item.status === 'WAITING_FOR_YOU' && item.startedAt) return t('activity.time.waiting', { time: formatRelative(item.startedAt) });
  if (isTerminal(item.status)) {
    const dur = formatDuration(item.startedAt, item.finishedAt);
    const rel = item.finishedAt ? formatRelative(item.finishedAt) : '';
    return dur && rel ? `${dur} · ${rel}` : (dur || rel);
  }
  return '';
}

function ActivityRow({ item }: { item: ActivityItem }) {
  const { t } = useTranslation();
  const detail = detailLine(item, t);
  const time = timeText(item, t);
  const waiting = isWaiting(item);
  const cta = item.status === 'WAITING_FOR_YOU' ? t('activity.review')
    : item.status === 'FAILED' ? t('activity.viewDetails') : '';
  return (
    <Link to={item.link} className="block focus:outline-none focus-visible:ring-2 focus-visible:ring-brand/40 rounded-xl">
      <Card interactive>
        <CardBody className="flex items-center gap-3 p-3.5">
          <span className={cn('grid h-9 w-9 shrink-0 place-items-center rounded-lg ring-1', CHIP_TONE[item.status])}>
            <StatusIcon status={item.status} />
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <Tooltip label={item.label}>
                <span className="truncate text-md font-semibold text-ink-900">{item.label}</span>
              </Tooltip>
              <Badge className={cn(TONE.muted, 'hidden sm:inline-flex')}>{t(`activity.type.${item.type}`)}</Badge>
            </div>
            {detail && <p className="mt-0.5 truncate text-sm text-muted">{detail}</p>}
          </div>
          <Badge className={STATUS_BADGE[item.status]}>{t(`activity.status.${item.status}`)}</Badge>
          {time && (
            <Tooltip label={item.finishedAt ? formatDateTime(item.finishedAt) : formatDateTime(item.startedAt)}>
              <span className="hidden shrink-0 text-xs tabular-nums text-muted sm:inline">{time}</span>
            </Tooltip>
          )}
          {waiting
            ? <span className={cn('hidden shrink-0 text-xs font-semibold sm:inline',
                item.status === 'FAILED' ? 'text-danger' : 'text-warning')}>{cta}</span>
            : null}
          <ChevronRight className="h-4 w-4 shrink-0 text-muted" aria-hidden="true" />
        </CardBody>
      </Card>
    </Link>
  );
}

/** One labelled section (a11y region) holding its rows, staggered in. Rendered only when it has rows. */
function Section({ id, heading, items }: { id: string; heading: string; items: ActivityItem[] }) {
  return (
    <section aria-labelledby={id} className="space-y-2">
      <h2 id={id} className="text-xs font-semibold uppercase tracking-wider text-muted">{heading}</h2>
      <StaggerList className="space-y-2">
        {items.map((item) => <StaggerItem key={item.id}><ActivityRow item={item} /></StaggerItem>)}
      </StaggerList>
    </section>
  );
}

export function Activity() {
  const { t } = useTranslation();
  const q = useQuery({
    queryKey: ['activity'],
    queryFn: api.activity,
    refetchInterval: (query) => (query.state.data?.some(isLive) ? 5000 : 30000),
  });
  const items = q.data ?? [];
  const groups = useMemo(() => partitionActivity(items), [items]);
  const kpis = useMemo(() => activityKpis(items), [items]);
  // A single "now" per render keeps every day-bucket comparison consistent.
  const history = useMemo(() => groupHistoryByDay(groups.history, Date.now()), [groups.history]);

  const header = (
    <PageHeader title={t('activity.title')} subtitle={t('activity.pageSubtitle')}
      actions={<FreshnessStamp updatedAt={q.dataUpdatedAt} onRefresh={() => q.refetch()} refreshing={q.isFetching} />} />
  );

  // First load (no data yet) — placeholder tiles + skeletons, never a blank screen.
  if (q.isLoading) {
    return (
      <div>
        {header}
        <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <KpiTile label={t('activity.kpi.inProgress')} value="—" />
          <KpiTile label={t('activity.kpi.waitingOnYou')} value="—" />
          <KpiTile label={t('activity.kpi.finished7d')} value="—" />
        </div>
        <CardSkeleton label={t('activity.loading')} />
      </div>
    );
  }

  // A failure on the very first load (no feed yet) is an error, not "all quiet" — say so plainly.
  if (q.isError && items.length === 0) {
    return (
      <div>
        {header}
        <ErrorState detail={(q.error as Error)?.message} />
      </div>
    );
  }

  const isEmpty = groups.waiting.length === 0 && groups.inProgress.length === 0 && groups.history.length === 0;

  // Truly quiet — a single reassuring panel, no zeroed KPIs or empty sections cluttering the screen.
  if (isEmpty) {
    return (
      <div>
        {header}
        <EmptyState icon={ActivityIcon} title={t('activity.emptyTitle')} body={t('activity.emptyBody')}
          action={<Link to="/repos"
            className="inline-flex items-center gap-2 rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-700">
            {t('activity.emptyCta')}</Link>} />
      </div>
    );
  }

  return (
    <div>
      {header}

      <section aria-label={t('activity.kpisAria')} className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <KpiTile label={t('activity.kpi.inProgress')} value={kpis.inProgress} />
        <KpiTile label={t('activity.kpi.waitingOnYou')} value={kpis.waitingOnYou}
          tone={kpis.waitingOnYou > 0 ? 'warning' : 'ink'} />
        <KpiTile label={t('activity.kpi.finished7d')} value={kpis.finished7d} />
      </section>

      {/* A poll that fails after we already have data shows a quiet notice — never blanks the last-known feed. */}
      {q.isError && <div className="mb-4"><ErrorState detail={(q.error as Error)?.message} /></div>}

      <div className="space-y-8">
        {groups.waiting.length > 0 && (
          <Section id="activity-waiting" heading={t('activity.sections.waiting')} items={groups.waiting} />
        )}
        {groups.inProgress.length > 0 && (
          <Section id="activity-in-progress" heading={t('activity.sections.inProgress')} items={groups.inProgress} />
        )}
        {groups.history.length > 0 && (
          <section aria-labelledby="activity-history" className="space-y-3">
            <h2 id="activity-history" className="text-xs font-semibold uppercase tracking-wider text-muted">
              {t('activity.sections.history')}
            </h2>
            {history.map((day) => (
              <div key={day.key} className="space-y-2">
                <p className="text-2xs font-semibold uppercase tracking-wide text-muted/80">{dayHeading(day.key, t)}</p>
                <StaggerList className="space-y-2">
                  {day.items.map((item) => <StaggerItem key={item.id}><ActivityRow item={item} /></StaggerItem>)}
                </StaggerList>
              </div>
            ))}
            <p className="pt-1 text-xs text-muted">{t('activity.historyFooter')}</p>
          </section>
        )}
      </div>
    </div>
  );
}
