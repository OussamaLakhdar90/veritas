import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Bug, RefreshCw, ExternalLink } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, KpiTile, PageHeader, Spinner, Table, Td, Row, SortableTh, useSort } from '../components/ui';
import { Donut, Gauge, severitySlices } from '../components/charts';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { DefectLink } from '../api';
import { enumLabel } from '../lib/enumLabels';
import { formatDateTime } from '../lib/format';

const DEFECT_ACCESSORS: Record<string, (d: DefectLink) => string | number> = {
  jiraKey: (d) => d.jiraKey ?? '',
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

function severityTone(severity?: string): string {
  const s = (severity || '').toUpperCase();
  if (s === 'CRITICAL' || s === 'HIGH') return TONE.danger;
  if (s === 'MEDIUM') return TONE.warn;
  if (s === 'LOW' || s === 'INFO') return TONE.info;
  return TONE.muted;
}

export function Defects() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['defects'], queryFn: api.defects });
  const metricsQ = useQuery({ queryKey: ['defect-metrics'], queryFn: api.defectMetrics });
  const sync = useMutation({
    mutationFn: api.syncDefects,
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['defects'] }); qc.invalidateQueries({ queryKey: ['defect-metrics'] }); toast.push('success', t('defects.syncSuccess', { count: r.updated })); },
    onError: (e: Error) => toast.push('error', t('defects.syncFailed', { message: e.message })),
  });
  const m = metricsQ.data;

  const sort = useSort(q.data ?? [], { key: 'jiraKey' }, DEFECT_ACCESSORS);
  const rows = sort.sorted;
  return (
    <div>
      <PageHeader title={t('defects.title')} subtitle={t('defects.subtitle')}
        actions={<Button variant="secondary" loading={sync.isPending} onClick={() => sync.mutate()}>
          <RefreshCw className="h-4 w-4" /> {t('defects.refreshStatuses')}</Button>} />

      {m && m.total > 0 && (
        <div className="mb-6 space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            <KpiTile label={t('defects.totalDefects')} value={m.total} />
            <KpiTile label={t('defects.open')} value={m.open} tone={m.open > 0 ? 'warning' : 'success'} />
            <KpiTile label={t('defects.closed')} value={m.closed} tone="success" />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
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
            <Card>
              <CardHeader title={t('defects.resolution')} />
              <CardBody className="flex justify-center">
                <Gauge value={m.closed} max={m.total} ariaLabel={t('defects.resolutionAria')} centerLabel={t('defects.resolvedCenterLabel')} />
              </CardBody>
            </Card>
          </div>
        </div>
      )}

      {q.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('defects.loading')}</CardBody></Card>
      ) : q.isError ? (
        <Card><CardBody className="text-sm text-danger">{t('defects.loadError', { message: (q.error as Error).message })}</CardBody></Card>
      ) : rows.length === 0 ? (
        <EmptyState icon={Bug} title={t('defects.emptyTitle')}
          body={t('defects.emptyBody')} />
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table head={<>
              <SortableTh label={t('defects.colJira')} sortKey="jiraKey" sort={sort} />
              <SortableTh label={t('defects.colStatus')} sortKey="status" sort={sort} />
              <SortableTh label={t('defects.colCreatedBy')} sortKey="createdBy" sort={sort} />
              <SortableTh label={t('defects.colLastSynced')} sortKey="lastSyncedAt" sort={sort} />
            </>}>
              {rows.map((d) => (
                <Row key={d.id}>
                  <Td className="font-medium text-ink-900">
                    {d.jiraUrl
                      ? <a href={d.jiraUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-gold hover:underline">{d.jiraKey} <ExternalLink className="h-3.5 w-3.5" /></a>
                      : (d.jiraKey ?? '—')}
                  </Td>
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
