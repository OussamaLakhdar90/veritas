import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ShieldCheck, AlertTriangle, Activity, FileText, ArrowRight, Settings as SettingsIcon, Sparkles } from 'lucide-react';
import { api, Scan } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, KpiTile, PageHeader, Skeleton } from '../components/ui';
import type { KpiTrend } from '../components/ui';
import { TONE } from '../theme/tokens';

/** Map a validation status to a status-pill tone. */
function statusTone(status?: string): string {
  const s = (status || '').toUpperCase();
  if (['COMPLETED', 'DONE', 'SUCCESS', 'PASSED'].includes(s)) return TONE.ok;
  if (['FAILED', 'ERROR'].includes(s)) return TONE.danger;
  if (['RUNNING', 'PENDING', 'IN_PROGRESS', 'QUEUED'].includes(s)) return TONE.info;
  return TONE.muted;
}

export function Dashboard() {
  const { t } = useTranslation();

  /** Plain-language, localized status label — never show the raw enum. */
  const statusLabel = (status?: string): string => {
    const s = (status || '').toUpperCase();
    if (['COMPLETED', 'DONE', 'SUCCESS', 'PASSED'].includes(s)) return t('status.completed');
    if (['FAILED', 'ERROR'].includes(s)) return t('status.failed');
    if (['RUNNING', 'IN_PROGRESS'].includes(s)) return t('status.running');
    if (['PENDING', 'QUEUED'].includes(s)) return t('status.queued');
    return status ? status.charAt(0) + status.slice(1).toLowerCase() : '—';
  };

  const scansQ = useQuery({ queryKey: ['scans'], queryFn: () => api.scans() });
  const preflightQ = useQuery({ queryKey: ['preflight'], queryFn: api.preflight });
  const costQ = useQuery({ queryKey: ['costs'], queryFn: api.costSummary });
  const defectsQ = useQuery({ queryKey: ['defects'], queryFn: api.defects });
  const servicesQ = useQuery({ queryKey: ['services'], queryFn: api.services });
  const services = servicesQ.data ?? [];

  const scans = scansQ.data ?? [];
  const missing = (preflightQ.data ?? []).filter((c) => c.status === 'MISSING');

  const totals = useMemo(() => {
    const services = new Set(scans.map((s) => s.serviceName)).size;
    const findings = scans.reduce((n, s) => n + (s.totalFindings ?? 0), 0);
    const openDefects = (defectsQ.data ?? []).filter(
      (d) => (d.jiraStatusCategory ?? '').toLowerCase() !== 'done').length;
    const spend = costQ.data?.totalEstCostUsd ?? scans.reduce((n, s) => n + (s.totalEstCostUsd ?? 0), 0);
    return { services, findings, openDefects, spend };
  }, [scans, defectsQ.data, costQ.data]);

  // How many distinct services currently carry findings — drives the executive one-liner.
  const attention = useMemo(
    () => new Set(scans.filter((s) => (s.totalFindings ?? 0) > 0).map((s) => s.serviceName)).size,
    [scans],
  );

  // Honest findings trend: per service, compare the latest scan to the most recent STRICTLY-earlier scan; sum the
  // deltas. Only show a chip when there's a real baseline (a prior scan at a different time) and the net moved.
  const findingsTrend: KpiTrend | undefined = useMemo(() => {
    const byService = new Map<string, Scan[]>();
    for (const s of scans) {
      const arr = byService.get(s.serviceName) ?? [];
      arr.push(s);
      byService.set(s.serviceName, arr);
    }
    let delta = 0;
    let hasBaseline = false;
    byService.forEach((list) => {
      const sorted = [...list].sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''));
      const latest = sorted[0];
      const prev = sorted.find((x) => (x.startedAt ?? '') < (latest.startedAt ?? ''));
      if (latest && prev) {
        delta += (latest.totalFindings ?? 0) - (prev.totalFindings ?? 0);
        hasBaseline = true;
      }
    });
    if (!hasBaseline || delta === 0) return undefined;
    return delta < 0
      ? { dir: 'down', good: true, label: t('overview.trendFindingsDown', { count: -delta }) }
      : { dir: 'up', good: false, label: t('overview.trendFindingsUp', { count: delta }) };
  }, [scans, t]);

  const recent: Scan[] = [...scans]
    .sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''))
    .slice(0, 8);

  // Don't render misleading zeros when the core data couldn't load — show a real error instead.
  const loadError = (scansQ.isError && scansQ.error) || (costQ.isError && costQ.error) || (defectsQ.isError && defectsQ.error);
  const showBanner = !scansQ.isLoading && !loadError && scans.length > 0;

  return (
    <div>
      <PageHeader
        title={t('overview.title')}
        subtitle={t('overview.subtitle')}
        actions={
          <Link to="/repos">
            <Button><ShieldCheck className="h-4 w-4" /> {t('overview.validateBtn')}</Button>
          </Link>
        }
      />

      {loadError && (
        <div className="mb-6">
          <ErrorState message={t('overview.loadError', { message: (loadError as Error).message })} />
        </div>
      )}

      {/* Setup nudge — only when something is unconfigured */}
      {missing.length > 0 && (
        <Card className="mb-6 border-l-4 border-l-warning">
          <CardBody className="flex items-start justify-between gap-4">
            <div className="flex items-start gap-3">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warning" />
              <div>
                <p className="text-sm font-semibold text-ink-900">
                  {t('overview.finishSetup', { count: missing.length })}
                </p>
                <p className="mt-0.5 text-[13px] text-muted">
                  {missing.length > 3
                    ? t('overview.setupBodyMore', { names: missing.slice(0, 3).map((c) => c.name).join(', '), count: missing.length - 3 })
                    : t('overview.setupBody', { names: missing.map((c) => c.name).join(', ') })}
                </p>
              </div>
            </div>
            <Link to="/settings">
              <Button variant="secondary" size="sm"><SettingsIcon className="h-4 w-4" /> {t('overview.openSettings')}</Button>
            </Link>
          </CardBody>
        </Card>
      )}

      {/* Executive one-liner — a manager-readable health verdict above the metrics */}
      {showBanner && (
        <Card className="mb-6 border-l-4 border-l-brand">
          <CardBody className="flex items-center gap-3">
            <Sparkles className="h-5 w-5 shrink-0 text-brand" />
            <p className="text-[13.5px] text-ink-700">
              <span className="font-semibold text-ink-900">
                {attention > 0
                  ? t('overview.execAttention', { services: totals.services, attention })
                  : t('overview.execHealthy', { services: totals.services })}
              </span>{' '}
              {t('overview.execTail', { findings: totals.findings, defects: totals.openDefects, spend: totals.spend.toFixed(2) })}
            </p>
          </CardBody>
        </Card>
      )}

      {/* KPI row */}
      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        {scansQ.isLoading ? (
          Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-28" />)
        ) : (
          <>
            <KpiTile label={t('overview.kpiServices')} value={totals.services} tone="brand"
              sub={t('overview.kpiServicesSub', { count: scans.length })} />
            <KpiTile label={t('overview.kpiFindings')} value={totals.findings}
              tone={totals.findings > 0 ? 'warning' : 'success'} sub={t('overview.kpiFindingsSub')} trend={findingsTrend} />
            <KpiTile label={t('overview.kpiDefects')} value={totals.openDefects}
              tone={totals.openDefects > 0 ? 'danger' : 'success'} sub={t('overview.kpiDefectsSub')} />
            <KpiTile label={t('overview.kpiCost')} value={`$${totals.spend.toFixed(2)}`}
              sub={costQ.data ? t('overview.kpiCostCalls', { count: costQ.data.actions }) : t('overview.kpiCostEnv')} />
          </>
        )}
      </div>

      {/* Pipeline by service — everything the platform holds work for, browsable (find-your-work). */}
      {services.length > 0 && (
        <Card className="mb-6">
          <CardHeader title={t('overview.pipelineTitle')} subtitle={t('overview.pipelineSubtitle')} />
          <CardBody className="p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-[12px] uppercase tracking-wide text-muted">
                    <th className="px-5 py-3 font-medium">{t('overview.colService')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colStrategies')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colConditions')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colCases')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colPlans')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colCodegen')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colScans')}</th>
                    <th className="px-5 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {services.map((s) => (
                    <tr key={s.name} className="border-b border-border/60 last:border-0 hover:bg-ink-50/60">
                      <td className="px-5 py-3 font-medium text-ink-900">{s.name}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-muted">{s.strategies || '—'}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-muted">{s.conditions || '—'}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-muted">{s.cases || '—'}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-muted">{s.plans || '—'}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-muted">{s.codegenRuns || '—'}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-muted">{s.scans || '—'}</td>
                      <td className="px-5 py-3 text-right whitespace-nowrap">
                        <Link to="/test-strategy"
                          className="inline-flex items-center gap-1 text-[13px] font-medium text-gold hover:underline">
                          {t('overview.open')} <ArrowRight className="h-3.5 w-3.5" />
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </CardBody>
        </Card>
      )}

      {/* Recent activity */}
      <Card>
        <CardHeader title={t('overview.recentTitle')} subtitle={t('overview.recentSubtitle')}
          action={<Link to="/repos" className="text-[13px] font-medium text-gold hover:underline">{t('overview.newValidation')}</Link>} />
        <CardBody className="p-0">
          {scansQ.isLoading ? (
            <div className="space-y-2 p-5">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-10" />)}</div>
          ) : recent.length === 0 ? (
            <div className="p-5">
              <EmptyState icon={Activity} title={t('overview.noValidations')}
                body={t('overview.noValidationsBody')}
                action={<Link to="/repos"><Button><ShieldCheck className="h-4 w-4" /> {t('overview.validateFirst')}</Button></Link>} />
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-[12px] uppercase tracking-wide text-muted">
                    <th className="px-5 py-3 font-medium">{t('overview.colService')}</th>
                    <th className="px-5 py-3 font-medium">{t('overview.colStatus')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colFindings')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colCost')}</th>
                    <th className="px-5 py-3 font-medium">{t('overview.colStarted')}</th>
                    <th className="px-5 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {recent.map((s) => (
                    <tr key={s.id} className="border-b border-border/60 last:border-0 hover:bg-ink-50/60">
                      <td className="px-5 py-3 font-medium text-ink-900">{s.serviceName}</td>
                      <td className="px-5 py-3"><Badge className={statusTone(s.status)}>{statusLabel(s.status)}</Badge></td>
                      <td className="px-5 py-3 text-right tabular-nums text-ink-900">{s.totalFindings}</td>
                      <td className="px-5 py-3 text-right tabular-nums text-muted">${(s.totalEstCostUsd ?? 0).toFixed(4)}</td>
                      <td className="px-5 py-3 text-muted">{s.startedAt}</td>
                      <td className="px-5 py-3 text-right whitespace-nowrap">
                        <Link to={`/findings/${s.id}`} className="inline-flex items-center gap-1 text-[13px] font-medium text-gold hover:underline">
                          {t('overview.view')} <ArrowRight className="h-3.5 w-3.5" />
                        </Link>
                        <a href={api.reportUrl(s.id)} target="_blank" rel="noreferrer"
                          className="ml-3 inline-flex items-center gap-1 text-[13px] font-medium text-muted hover:text-ink-900">
                          <FileText className="h-3.5 w-3.5" /> {t('overview.report')}
                        </a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
