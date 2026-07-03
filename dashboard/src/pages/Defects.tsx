import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bug, RefreshCw, ExternalLink, X } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, KpiTile, PageHeader, TableSkeleton, Table, Td, Row, SortableTh, useSort } from '../components/ui';
import { Donut, Gauge, severitySlices } from '../components/charts';
import { useToast } from '../components/Toast';
import { Tooltip } from '../components/Tooltip';
import { severityBadge, TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { DefectLink } from '../api';
import { enumLabel } from '../lib/enumLabels';
import { formatDateTime } from '../lib/format';

// Severity rank so the column and the sort agree with the finding severities (blocker → info).
const SEV_ORDER = ['BLOCKER', 'CRITICAL', 'HIGH', 'MAJOR', 'MEDIUM', 'MINOR', 'LOW', 'INFO'];
const sevRank = (s?: string) => { const i = SEV_ORDER.indexOf((s || '').toUpperCase()); return i < 0 ? SEV_ORDER.length : i; };

const DEFECT_ACCESSORS: Record<string, (d: DefectLink) => string | number> = {
  jiraKey: (d) => d.jiraKey ?? '',
  severity: (d) => sevRank(d.severity),
  service: (d) => d.serviceName ?? '',
  status: (d) => d.jiraStatus ?? '',
  createdBy: (d) => d.createdBy ?? '',
  lastSyncedAt: (d) => d.lastSyncedAt ?? '',
};

function statusTone(category?: string): string {
  const c = (category || '').toLowerCase();
  if (c === 'done') return TONE.ok;
  if (c === 'indeterminate') return TONE.warn;
  if (c === 'new' || c === 'to do' || c === 'todo') return TONE.info;
  return TONE.muted;
}

/**
 * Severity → pill tone. Extended so the finding-grade codes (BLOCKER/MAJOR/MINOR) map alongside the Jira
 * ones (HIGH/MEDIUM/LOW): BLOCKER+CRITICAL/HIGH → danger, MAJOR/MEDIUM → warn, MINOR/LOW/INFO → info.
 */
function severityTone(severity?: string): string {
  const s = (severity || '').toUpperCase();
  if (s === 'BLOCKER' || s === 'CRITICAL' || s === 'HIGH') return TONE.danger;
  if (s === 'MAJOR' || s === 'MEDIUM') return TONE.warn;
  if (s === 'MINOR' || s === 'LOW' || s === 'INFO') return TONE.info;
  return TONE.muted;
}

export function Defects() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const severityFilter = (params.get('severity') || '').toUpperCase();
  const q = useQuery({ queryKey: ['defects'], queryFn: api.defects });
  const metricsQ = useQuery({ queryKey: ['defect-metrics'], queryFn: api.defectMetrics });
  const sync = useMutation({
    mutationFn: api.syncDefects,
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['defects'] }); qc.invalidateQueries({ queryKey: ['defect-metrics'] }); toast.push('success', t('defects.syncSuccess', { count: r.updated })); },
    onError: (e: Error) => toast.push('error', t('defects.syncFailed', { message: e.message })),
  });
  const m = metricsQ.data;
  const all = q.data ?? [];

  // The severities present in the data, blocker→info, drive the filter chips.
  const severities = useMemo(
    () => [...new Set(all.map((d) => (d.severity || '').toUpperCase()).filter(Boolean))].sort((a, b) => sevRank(a) - sevRank(b)),
    [all]);
  const setSeverity = (sev?: string) => setParams((p) => {
    const n = new URLSearchParams(p);
    if (sev) n.set('severity', sev); else n.delete('severity');
    return n;
  }, { replace: true });

  const filtered = useMemo(
    () => (severityFilter ? all.filter((d) => (d.severity || '').toUpperCase() === severityFilter) : all),
    [all, severityFilter]);

  // Aging signal: open defects (status not "done") whose createdAt is older than 7 days. Computed honestly —
  // a defect with no createdAt can't be aged, so it's excluded from the count (never inflates the number).
  const openOver7Days = useMemo(() => {
    const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
    return all.filter((d) => {
      if ((d.jiraStatusCategory || '').toLowerCase() === 'done') return false;
      if (!d.createdAt) return false;
      const created = Date.parse(d.createdAt);
      return !Number.isNaN(created) && created < cutoff;
    }).length;
  }, [all]);

  // Defects grouped by service (from metrics) → a simple horizontal bar card.
  const byService = useMemo(
    () => Object.entries(m?.byService ?? {}).sort((a, b) => b[1] - a[1]), [m]);
  const byServiceMax = byService.length ? byService[0][1] : 0;

  const sort = useSort(filtered, { key: 'jiraKey' }, DEFECT_ACCESSORS);
  const rows = sort.sorted;
  return (
    <div>
      <PageHeader title={t('defects.title')} subtitle={t('defects.subtitle')}
        actions={<Button variant="secondary" loading={sync.isPending} onClick={() => sync.mutate()}>
          <RefreshCw className="h-4 w-4" /> {t('defects.refreshStatuses')}</Button>} />

      {m && m.total > 0 && (
        <div className="mb-6 space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <KpiTile label={t('defects.totalDefects')} value={m.total} />
            <KpiTile label={t('defects.open')} value={m.open} tone={m.open > 0 ? 'warning' : 'success'} />
            <KpiTile label={t('defects.closed')} value={m.closed} tone="success" />
            <KpiTile label={t('defects.openOver7Days')} value={openOver7Days} sub={t('defects.openOver7DaysHint')}
              tone={openOver7Days > 0 ? 'warning' : 'success'} />
          </div>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <Card>
              <CardHeader title={t('defects.bySeverity')} />
              <CardBody className="flex items-center gap-5">
                <Donut slices={severitySlices(m.bySeverity)} ariaLabel={t('defects.bySeverityAria')}
                  centerValue={m.total} centerLabel={t('defects.defectsCenterLabel')} />
                <div className="min-w-0 flex-1 space-y-1.5 text-sm">
                  {Object.entries(m.bySeverity).map(([sev, n]) => (
                    <div key={sev} className="flex items-center gap-2">
                      <Badge className={severityTone(sev)}>{enumLabel(t, 'severity', sev)}</Badge>
                      <span className="ml-auto font-semibold tabular-nums text-ink-900">{n}</span>
                    </div>
                  ))}
                </div>
              </CardBody>
            </Card>
            {byService.length > 0 && (
              <Card>
                <CardHeader title={t('defects.byService')} />
                <CardBody className="space-y-2 text-sm">
                  {byService.map(([svc, n]) => (
                    <div key={svc} className="flex items-center gap-3">
                      <Tooltip label={svc}><span className="block w-28 shrink-0 truncate text-ink-700">{svc}</span></Tooltip>
                      <div className="h-2 flex-1 rounded-full bg-ink-100">
                        <div className="h-2 rounded-full bg-brand" style={{ width: `${byServiceMax ? Math.max(4, (n / byServiceMax) * 100) : 0}%` }} />
                      </div>
                      <span className="w-6 text-right font-semibold tabular-nums text-ink-900">{n}</span>
                    </div>
                  ))}
                </CardBody>
              </Card>
            )}
            <Card>
              <CardHeader title={t('defects.resolution')} />
              <CardBody className="flex justify-center">
                <Gauge value={m.closed} max={m.total} ariaLabel={t('defects.resolutionAria')} centerLabel={t('defects.resolvedCenterLabel')} />
              </CardBody>
            </Card>
          </div>
        </div>
      )}

      {/* Severity filter chips — URL-addressable (?severity=…) so the Dashboard donut can deep-link in. */}
      {all.length > 0 && severities.length > 0 && (
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <button onClick={() => setSeverity(undefined)}
            className={cn('rounded-full px-2.5 py-1 text-2xs font-semibold uppercase tracking-wide transition',
              !severityFilter ? 'bg-brand text-white' : 'text-muted ring-1 ring-border hover:bg-ink-50')}>
            {t('defects.filterAll', { count: all.length })}
          </button>
          {severities.map((sev) => (
            <button key={sev} onClick={() => setSeverity(sev)}
              className={cn('rounded-full px-2.5 py-1 text-2xs font-semibold uppercase tracking-wide transition',
                severityFilter === sev ? severityBadge(sev) : 'text-muted ring-1 ring-border hover:bg-ink-50')}>
              {enumLabel(t, 'severity', sev)}
            </button>
          ))}
        </div>
      )}

      {q.isLoading ? (
        <Card><CardBody className="p-0"><TableSkeleton label={t('defects.loading')} /></CardBody></Card>
      ) : q.isError ? (
        <ErrorState message={t('defects.loadError')} detail={(q.error as Error).message} />
      ) : rows.length === 0 ? (
        severityFilter && all.length > 0 ? (
          <EmptyState icon={Bug} title={t('defects.emptyFilterTitle', { severity: enumLabel(t, 'severity', severityFilter) })}
            body={t('defects.emptyFilterBody')}
            action={<Button variant="secondary" onClick={() => setSeverity(undefined)}><X className="h-4 w-4" /> {t('defects.clearFilter')}</Button>} />
        ) : (
          <EmptyState icon={Bug} title={t('defects.emptyTitle')} body={t('defects.emptyBody')} />
        )
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table stickyHead head={<>
              <SortableTh label={t('defects.colJira')} sortKey="jiraKey" sort={sort} />
              <SortableTh label={t('defects.colSeverity')} sortKey="severity" sort={sort} />
              <SortableTh label={t('defects.colService')} sortKey="service" sort={sort} />
              <SortableTh label={t('defects.colStatus')} sortKey="status" sort={sort} />
              <SortableTh label={t('defects.colCreatedBy')} sortKey="createdBy" sort={sort} />
              <SortableTh label={t('defects.colLastSynced')} sortKey="lastSyncedAt" sort={sort} />
            </>}>
              {rows.map((d, i) => (
                <Row key={d.id} index={i}>
                  <Td className="font-medium text-ink-900">
                    {d.jiraUrl
                      ? <a href={d.jiraUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-gold hover:underline">{d.jiraKey} <ExternalLink className="h-3.5 w-3.5" /></a>
                      : (d.jiraKey ?? '—')}
                  </Td>
                  <Td>{d.severity ? <Badge className={severityTone(d.severity)}>{enumLabel(t, 'severity', d.severity)}</Badge> : <span className="text-muted">—</span>}</Td>
                  <Td className="text-muted">{d.serviceName ?? '—'}</Td>
                  <Td><Badge className={statusTone(d.jiraStatusCategory)}>{d.jiraStatus ?? (d.createdInJira ? t('defects.statusOpen') : t('defects.statusNotCreated'))}</Badge></Td>
                  <Td className="text-muted">{d.createdBy ?? '—'}</Td>
                  <Td className="text-muted">{formatDateTime(d.lastSyncedAt)}</Td>
                </Row>
              ))}
            </Table>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
