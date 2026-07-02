import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ShieldCheck, AlertTriangle, Activity, FileText, ArrowRight, Settings as SettingsIcon } from 'lucide-react';
import { api, Scan } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, KpiTile, PageHeader, Skeleton } from '../components/ui';
import type { KpiTrend } from '../components/ui';
import { Donut, Gauge, Sparkline, severitySlices, SEV_SWATCH } from '../components/charts';
import { SnykImpactCard } from '../components/SnykImpact';
import { FidelityScorecard, letterGrade } from '../components/FidelityScorecard';
import { ValueStrip } from '../components/ValueStrip';
import { LiveScanRow } from '../components/LiveScanRow';
import { PrecisionStrip } from '../components/PrecisionStrip';
import { DecisionQueue } from '../components/DecisionQueue';
import { StaggerItem, StaggerList } from '../components/motion';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { formatDuration, formatMoney, formatDateTime, formatRelative } from '../lib/format';

/** Map a validation status to a status-pill tone. */
function statusTone(status?: string): string {
  const s = (status || '').toUpperCase();
  if (['COMPLETED', 'DONE', 'SUCCESS', 'PASSED'].includes(s)) return TONE.ok;
  if (['FAILED', 'ERROR'].includes(s)) return TONE.danger;
  if (['RUNNING', 'PENDING', 'IN_PROGRESS', 'QUEUED'].includes(s)) return TONE.info;
  return TONE.muted;
}

/** Sum of the last 7 points minus the 7 before — the week-over-week delta. null until two full weeks of data. */
function weeklyDelta(series: number[]): number | null {
  if (series.length < 14) return null;
  const last7 = series.slice(-7).reduce((a, b) => a + b, 0);
  const prev7 = series.slice(-14, -7).reduce((a, b) => a + b, 0);
  if (last7 === 0 && prev7 === 0) return null;
  return Math.round((last7 - prev7) * 100) / 100;
}

/** Fidelity pill for a scan row — letter grade + score in the threshold tone. */
function ScorePill({ score }: { score?: number | null }) {
  if (score == null) return <span className="text-muted">—</span>;
  const tone = score >= 90 ? TONE.ok : score >= 70 ? TONE.warn : TONE.danger;
  return <Badge className={tone}>{letterGrade(score)} · {score}</Badge>;
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

  // 2s live poll while any scan runs (drives the LiveScanRow theatre), calm otherwise.
  const scansQ = useQuery({ queryKey: ['scans'], queryFn: () => api.scans(),
    refetchInterval: (q) => ((q.state.data ?? []) as Scan[]).some((s) => (s.status || '').toUpperCase() === 'RUNNING') ? 2000 : false });
  const preflightQ = useQuery({ queryKey: ['preflight'], queryFn: api.preflight });
  const costQ = useQuery({ queryKey: ['costs'], queryFn: api.costSummary });
  const defectsQ = useQuery({ queryKey: ['defects'], queryFn: api.defects });
  const servicesQ = useQuery({ queryKey: ['services'], queryFn: api.services });
  const services = servicesQ.data ?? [];
  const metricsQ = useQuery({ queryKey: ['defect-metrics'], queryFn: api.defectMetrics });
  const scansTrendQ = useQuery({ queryKey: ['scans-trend'], queryFn: () => api.scansTrend(30) });
  const costTrendQ = useQuery({ queryKey: ['cost-trend'], queryFn: () => api.costTrend(30) });
  const summaryQ = useQuery({ queryKey: ['executive-summary'], queryFn: api.executiveSummary });

  const scans = scansQ.data ?? [];
  const summary = summaryQ.data;
  const missing = (preflightQ.data ?? []).filter((c) => c.status === 'MISSING');

  const totals = useMemo(() => {
    const serviceCount = new Set(scans.map((s) => s.serviceName)).size;
    const openDefects = (defectsQ.data ?? []).filter(
      (d) => (d.jiraStatusCategory ?? '').toLowerCase() !== 'done').length;
    const spend = costQ.data?.totalEstCostUsd ?? scans.reduce((n, s) => n + (s.totalEstCostUsd ?? 0), 0);
    return { services: serviceCount, openDefects, spend };
  }, [scans, defectsQ.data, costQ.data]);

  /** Latest completed, scored scan per service — feeds the coverage-honesty line + per-row coverage cells. */
  const latestByService = useMemo(() => {
    const map = new Map<string, Scan>();
    for (const s of scans) {
      if ((s.status || '').toUpperCase() !== 'COMPLETED' || s.fidelityScore == null) continue;
      const prev = map.get(s.serviceName);
      if (!prev || (s.startedAt ?? '') > (prev.startedAt ?? '')) map.set(s.serviceName, s);
    }
    return map;
  }, [scans]);
  const coverageGaps = useMemo(
    () => [...latestByService.values()].reduce((n, s) => n + (s.coverageGaps ?? 0), 0), [latestByService]);
  const perServiceSummary = useMemo(
    () => new Map((summary?.perService ?? []).map((s) => [s.service, s])), [summary]);

  const findingsSeries = useMemo(() => (scansTrendQ.data ?? []).map((p) => p.findings), [scansTrendQ.data]);
  const costTrend: KpiTrend | undefined = useMemo(() => {
    const d = weeklyDelta((costTrendQ.data ?? []).map((p) => p.totalUsd));
    if (d == null || Math.abs(d) < 0.005) return undefined;
    return { dir: d < 0 ? 'down' : 'up',
      label: t(d < 0 ? 'overview.trendCostDown' : 'overview.trendCostUp', { amount: formatMoney(Math.abs(d)) }) };
  }, [costTrendQ.data, t]);

  const recent: Scan[] = [...scans]
    .sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''))
    .slice(0, 8);

  const sevSlices = useMemo(() => severitySlices(metricsQ.data?.bySeverity ?? {}), [metricsQ.data]);
  const sevTotal = sevSlices.reduce((n, s) => n + s.value, 0);

  const loadError = (scansQ.isError && scansQ.error) || (costQ.isError && costQ.error) || (defectsQ.isError && defectsQ.error);
  const hasData = !scansQ.isLoading && !loadError && scans.length > 0;
  const hasHero = hasData && !!summary && summary.perService.length > 0;

  const kpiTiles = (
    <>
      <StaggerItem><KpiTile label={t('overview.kpiServices')} value={totals.services} tone="brand"
        sub={t('overview.kpiServicesSub', { count: scans.length })} /></StaggerItem>
      <StaggerItem><KpiTile label={t('overview.kpiBreaking')} value={summary?.totals.breakingFindingsCaught ?? 0}
        tone={(summary?.totals.breakingFindingsCaught ?? 0) > 0 ? 'danger' : 'success'}
        sub={t('overview.kpiBreakingSub')} /></StaggerItem>
      <StaggerItem><KpiTile label={t('overview.kpiDefects')} value={totals.openDefects}
        tone={totals.openDefects > 0 ? 'danger' : 'success'} sub={t('overview.kpiDefectsSub')} /></StaggerItem>
      <StaggerItem><KpiTile label={t('overview.kpiCost')} value={formatMoney(totals.spend)}
        sub={costQ.data ? t('overview.kpiCostCalls', { count: costQ.data.actions }) : t('overview.kpiCostEnv')}
        trend={costTrend} /></StaggerItem>
    </>
  );

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
          <ErrorState message={t('overview.loadError')} detail={(loadError as Error).message} />
        </div>
      )}

      {/* Live validations — pinned theatre while the machine works; morphs into the score reveal. */}
      {!loadError && <LiveScanRow scans={scans} />}

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
                <p className="mt-0.5 text-sm text-muted">
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

      {/* Hero row — the portfolio fidelity score + the value KPIs. The screen the VP reads in 10 seconds. */}
      {hasHero && (
        <div className="mb-6 grid gap-4 xl:grid-cols-[minmax(340px,5fr),6fr]">
          <FidelityScorecard summary={summary} coverageGaps={coverageGaps} />
          <StaggerList className="grid grid-cols-2 gap-4">{kpiTiles}</StaggerList>
        </div>
      )}

      {/* KPI-only fallback while nothing is scored yet (zeroed tiles are honest on an empty portfolio) */}
      {!scansQ.isLoading && !hasHero && (
        <StaggerList className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">{kpiTiles}</StaggerList>
      )}

      {scansQ.isLoading && (
        <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-28" />)}
        </div>
      )}

      {/* ROI strip — duration, cost per audit, review hours avoided (estimated — assumption in the tooltip) */}
      {hasData && <ValueStrip scans={scans} />}

      {/* Human-in-the-loop proof: acceptance rate + the AI's own disputes. */}
      {hasData && summary && <PrecisionStrip summary={summary} />}

      {/* Charts row — severity mix, resolution, findings trend */}
      {hasData && (
        <div className="mb-6 grid gap-4 lg:grid-cols-3">
          <Card>
            <CardHeader title={t('overview.chartDefectsSeverity')} subtitle={t('overview.chartDefectsSeveritySub')} />
            <CardBody className="flex items-center gap-5">
              <Donut slices={sevSlices} ariaLabel={t('overview.chartDefectsSeverity')}
                centerValue={sevTotal} centerLabel={t('overview.chartDefectsCenter')} />
              <div className="min-w-0 flex-1 space-y-1.5 text-sm">
                {sevSlices.length === 0 ? (
                  <span className="text-muted">—</span>
                ) : sevSlices.map((s) => (
                  <div key={s.label} className="flex items-center gap-2">
                    <span className={`h-2.5 w-2.5 shrink-0 rounded-sm ${SEV_SWATCH[s.label] ?? 'bg-sev-info'}`} />
                    <span className="text-ink-700">{t(`severity.${s.label}`)}</span>
                    <span className="ml-auto font-semibold tabular-nums text-ink-900">{s.value}</span>
                  </div>
                ))}
              </div>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title={t('overview.chartResolution')} subtitle={t('overview.chartResolutionSub')} />
            <CardBody className="flex justify-center">
              <Gauge value={metricsQ.data?.closed ?? 0} max={metricsQ.data?.total ?? 0}
                ariaLabel={t('overview.chartResolution')} centerLabel={t('overview.chartResolved')} />
            </CardBody>
          </Card>

          <Card>
            <CardHeader title={t('overview.chartTrend')} subtitle={t('overview.chartTrendSub')} />
            <CardBody>
              <Sparkline values={findingsSeries} ariaLabel={t('overview.chartTrend')} />
            </CardBody>
          </Card>
        </div>
      )}

      {/* Dependency-security posture (Snyk) — found vs fixed; renders only when app-ids are watched. */}
      <SnykImpactCard />

      {/* Everything waiting on a named human — the governance selling point. */}
      {hasData && <DecisionQueue />}

      {/* Scorecard by service — letter grades, deltas, coverage honesty, assets, deep links. */}
      {services.length > 0 && (
        <Card className="mb-6">
          <CardHeader title={t('overview.pipelineTitle')} subtitle={t('overview.pipelineSubtitle')} />
          <CardBody className="p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted">
                    <th className="px-5 py-3 font-medium">{t('overview.colService')}</th>
                    <th className="px-5 py-3 font-medium">{t('overview.colGrade')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colCoverage')}</th>
                    <th className="px-5 py-3 font-medium">{t('overview.colAssets')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colScans')}</th>
                    <th className="px-5 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {services.map((s) => {
                    const sum = perServiceSummary.get(s.name);
                    const latest = latestByService.get(s.name);
                    const assets: Array<[string, number]> = [
                      [t('overview.assetStrategies'), s.strategies], [t('overview.assetConditions'), s.conditions],
                      [t('overview.assetCases'), s.cases], [t('overview.assetPlans'), s.plans],
                      [t('overview.assetCodegen'), s.codegenRuns]];
                    return (
                      <tr key={s.name} className="border-b border-border/60 last:border-0 hover:bg-ink-50/60">
                        <td className="px-5 py-3 font-medium text-ink-900">{s.name}</td>
                        <td className="px-5 py-3">
                          <span className="inline-flex items-center gap-1.5">
                            <ScorePill score={sum?.fidelity} />
                            {sum?.delta != null && sum.delta !== 0 && (
                              <span className={cn('text-2xs font-semibold tabular-nums',
                                sum.delta > 0 ? 'text-success' : 'text-danger')}>
                                {sum.delta > 0 ? '+' : ''}{sum.delta}
                              </span>
                            )}
                          </span>
                        </td>
                        <td className="px-5 py-3 text-right text-muted">
                          {latest?.coverageGaps != null
                            ? (latest.coverageGaps === 0
                              ? t('overview.coverageFull')
                              : t('overview.coverageGaps', { count: latest.coverageGaps }))
                            : '—'}
                        </td>
                        <td className="px-5 py-3">
                          <span className="flex flex-wrap gap-1">
                            {assets.filter(([, n]) => n > 0).map(([label, n]) => (
                              <span key={label} className="rounded-full bg-ink-50 px-2 py-0.5 text-2xs text-ink-700 ring-1 ring-border">
                                {n} {label}
                              </span>
                            ))}
                            {assets.every(([, n]) => !n) && <span className="text-muted">—</span>}
                          </span>
                        </td>
                        <td className="px-5 py-3 text-right tabular-nums text-muted">{s.scans || '—'}</td>
                        <td className="px-5 py-3 text-right whitespace-nowrap">
                          {sum && (
                            <Link to={`/findings/${sum.latestScanId}`}
                              className="inline-flex items-center gap-1 text-sm font-medium text-gold hover:underline">
                              {t('overview.latestFindings')} <ArrowRight className="h-3.5 w-3.5" />
                            </Link>
                          )}
                          <Link to={`/test-strategy?service=${encodeURIComponent(s.name)}`}
                            className="ml-3 inline-flex items-center gap-1 text-sm font-medium text-muted hover:text-ink-900">
                            {t('overview.openStrategy')} <ArrowRight className="h-3.5 w-3.5" />
                          </Link>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </CardBody>
        </Card>
      )}

      {/* Recent activity */}
      <Card>
        <CardHeader title={t('overview.recentTitle')} subtitle={t('overview.recentSubtitle')}
          action={<Link to="/repos" className="text-sm font-medium text-gold hover:underline">{t('overview.newValidation')}</Link>} />
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
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted">
                    <th className="px-5 py-3 font-medium">{t('overview.colService')}</th>
                    <th className="px-5 py-3 font-medium">{t('overview.colStatus')}</th>
                    <th className="px-5 py-3 font-medium">{t('overview.colScore')}</th>
                    <th className="px-5 py-3 font-medium text-right">{t('overview.colFindings')}</th>
                    <th className="px-5 py-3 font-medium">{t('overview.colAudit')}</th>
                    <th className="px-5 py-3 font-medium">{t('overview.colStarted')}</th>
                    <th className="px-5 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {recent.map((s) => {
                    const dur = formatDuration(s.startedAt, s.finishedAt);
                    return (
                      <tr key={s.id} className="border-b border-border/60 last:border-0 hover:bg-ink-50/60">
                        <td className="px-5 py-3 font-medium text-ink-900">{s.serviceName}</td>
                        <td className="px-5 py-3"><Badge className={statusTone(s.status)}>{statusLabel(s.status)}</Badge></td>
                        <td className="px-5 py-3"><ScorePill score={s.fidelityScore} /></td>
                        <td className="px-5 py-3 text-right tabular-nums text-ink-900">{s.totalFindings}</td>
                        <td className="px-5 py-3 text-muted">
                          {dur ? t('overview.auditedIn', { dur, cost: formatMoney(s.totalEstCostUsd ?? 0) }) : '—'}
                        </td>
                        <td className="px-5 py-3 text-muted" title={formatDateTime(s.startedAt)}>
                          {formatRelative(s.startedAt)}
                        </td>
                        <td className="px-5 py-3 text-right whitespace-nowrap">
                          <Link to={`/findings/${s.id}`} className="inline-flex items-center gap-1 text-sm font-medium text-gold hover:underline">
                            {t('overview.view')} <ArrowRight className="h-3.5 w-3.5" />
                          </Link>
                          <a href={api.reportUrl(s.id)} target="_blank" rel="noreferrer"
                            className="ml-3 inline-flex items-center gap-1 text-sm font-medium text-muted hover:text-ink-900">
                            <FileText className="h-3.5 w-3.5" /> {t('overview.report')}
                          </a>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
