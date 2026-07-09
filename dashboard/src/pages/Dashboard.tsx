import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ShieldCheck, AlertTriangle, Activity, FileText, ArrowRight, Settings as SettingsIcon, Download } from 'lucide-react';
import { api, Scan } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, FreshnessStamp, KpiTile, PageHeader, Skeleton, Table, Th, Td, Row } from '../components/ui';
import type { KpiTrend } from '../components/ui';
import { Donut, Gauge, TrendChart, severitySlices, SEV_SWATCH } from '../components/charts';
import { SnykImpactCard } from '../components/SnykImpact';
import { SnykAlertBanner } from '../components/SnykAlertBanner';
import { FidelityScorecard } from '../components/FidelityScorecard';
import { ValueStrip } from '../components/ValueStrip';
import { LiveScanRow } from '../components/LiveScanRow';
import { PrecisionStrip } from '../components/PrecisionStrip';
import { DecisionQueue } from '../components/DecisionQueue';
import { StaggerItem, StaggerList } from '../components/motion';
import { Tooltip } from '../components/Tooltip';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { formatDuration, formatMoney, formatDateTime, formatRelative } from '../lib/format';
import { downloadScorecardCsv, downloadTrendCsv } from '../lib/exportCsv';

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

const VERDICT_TONE: Record<string, string> = { PASS: TONE.ok, WARN: TONE.warn, FAIL: TONE.danger };

/** Release-gate verdict badge for a scan/service row — PASS / WARN / FAIL. */
function VerdictBadge({ verdict }: { verdict?: string | null }) {
  const { t } = useTranslation();
  if (verdict == null) return <span className="text-muted">—</span>;
  return <Badge className={VERDICT_TONE[verdict] ?? TONE.muted}>{t(`overview.verdict.${verdict}`, verdict)}</Badge>;
}

/** The trend-card time ranges, in days. 30 is the default (matches the historical sparkline window). */
const TREND_RANGES = [7, 30, 90] as const;
type TrendRange = (typeof TREND_RANGES)[number];

export function Dashboard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const rawRange = Number(params.get('range'));
  const range: TrendRange = (TREND_RANGES as readonly number[]).includes(rawRange) ? (rawRange as TrendRange) : 30;

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
  // Only the findings trend follows the 7/30/90 range picker; the cost WoW pill needs a fixed 14+-day window.
  const scansTrendQ = useQuery({ queryKey: ['scans-trend', range], queryFn: () => api.scansTrend(range) });
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
      if ((s.status || '').toUpperCase() !== 'COMPLETED' || s.releaseSafe == null) continue;
      const prev = map.get(s.serviceName);
      if (!prev || (s.startedAt ?? '') > (prev.startedAt ?? '')) map.set(s.serviceName, s);
    }
    return map;
  }, [scans]);
  const coverageGaps = useMemo(
    () => [...latestByService.values()].reduce((n, s) => n + (s.coverageGaps ?? 0), 0), [latestByService]);
  const perServiceSummary = useMemo(
    () => new Map((summary?.perService ?? []).map((s) => [s.service, s])), [summary]);

  // The service holding the most blocking findings — the DecisionQueue's blocking row links straight to it.
  const worstBlockingService = useMemo(() => {
    const worst = (summary?.perService ?? [])
      .filter((s) => s.blockingCount > 0)
      .sort((a, b) => b.blockingCount - a.blockingCount)[0];
    return worst ? { service: worst.service, scanId: worst.latestScanId } : undefined;
  }, [summary]);

  // Findings-per-day, keeping the date so the TrendChart can label its axis (Dashboard.tsx:99 used to discard it).
  const findingsTrend = useMemo(
    () => (scansTrendQ.data ?? []).map((p) => ({ date: p.date, value: p.findings })), [scansTrendQ.data]);
  const findingsSeries = useMemo(() => findingsTrend.map((p) => p.value), [findingsTrend]);
  const findingsWeekly = useMemo(() => weeklyDelta(findingsSeries), [findingsSeries]);
  const lastFindings = findingsTrend.length ? findingsTrend[findingsTrend.length - 1].value : null;

  const costTrend: KpiTrend | undefined = useMemo(() => {
    const d = weeklyDelta((costTrendQ.data ?? []).map((p) => p.totalUsd));
    if (d == null || Math.abs(d) < 0.005) return undefined;
    return { dir: d < 0 ? 'down' : 'up',
      label: t(d < 0 ? 'overview.trendCostDown' : 'overview.trendCostUp', { amount: formatMoney(Math.abs(d)) }) };
  }, [costTrendQ.data, t]);

  // Findings WoW pill on the Breaking tile. Label stays "findings" (not "breaking changes") — the /trend
  // series is total findings/day; calling it breaking would overclaim. Fewer is good.
  const findingsTrendPill: KpiTrend | undefined = useMemo(() => {
    if (findingsWeekly == null || findingsWeekly === 0) return undefined;
    return { dir: findingsWeekly < 0 ? 'down' : 'up', good: findingsWeekly < 0,
      label: t(findingsWeekly < 0 ? 'overview.trendWeekFewer' : 'overview.trendWeekMore', { count: Math.abs(findingsWeekly) }) };
  }, [findingsWeekly, t]);

  // Audits/week pill on the Services tile — scans run in the last 7 days from the /trend series.
  const auditsThisWeek = useMemo(
    () => (scansTrendQ.data ?? []).slice(-7).reduce((n, p) => n + p.scans, 0), [scansTrendQ.data]);
  const auditsPill: KpiTrend | undefined = auditsThisWeek > 0
    ? { dir: 'flat', label: t('overview.trendAuditsWeek', { count: auditsThisWeek }) } : undefined;

  const recent: Scan[] = [...scans]
    .sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''))
    .slice(0, 8);

  const sevSlices = useMemo(() => severitySlices(metricsQ.data?.bySeverity ?? {}), [metricsQ.data]);
  const sevTotal = sevSlices.reduce((n, s) => n + s.value, 0);

  const loadError = (scansQ.isError && scansQ.error) || (costQ.isError && costQ.error) || (defectsQ.isError && defectsQ.error);
  const hasData = !scansQ.isLoading && !loadError && scans.length > 0;
  const hasHero = hasData && !!summary && summary.perService.length > 0;

  // Manual refresh — the data does NOT poll while idle, so this is functional. Invalidate every dashboard query.
  const refreshing = scansQ.isFetching || summaryQ.isFetching || costQ.isFetching || defectsQ.isFetching;
  const refreshAll = () => qc.invalidateQueries();
  // Print / scorecard-CSV export (the frontend-buildable equivalent of scheduled report subscriptions).
  const exportCsv = () => downloadScorecardCsv(t, services, perServiceSummary, latestByService);

  const kpiTiles = (
    <>
      <StaggerItem><KpiTile label={t('overview.kpiServices')} value={totals.services} tone="brand"
        sub={t('overview.kpiServicesSub', { count: scans.length })} trend={auditsPill} /></StaggerItem>
      <StaggerItem><KpiTile label={t('overview.kpiBreaking')} value={summary?.totals.breakingFindingsCaught ?? 0}
        tone={(summary?.totals.breakingFindingsCaught ?? 0) > 0 ? 'danger' : 'success'}
        sub={t('overview.kpiBreakingSub')} trend={findingsTrendPill} /></StaggerItem>
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
          <div className="flex items-center gap-2 print:hidden">
            <FreshnessStamp updatedAt={scansQ.dataUpdatedAt} onRefresh={refreshAll} refreshing={refreshing} />
            <Button variant="secondary" size="sm" onClick={() => window.print()}>
              <FileText className="h-4 w-4" /> {t('overview.print')}
            </Button>
            <Button variant="secondary" size="sm" onClick={exportCsv} disabled={services.length === 0}>
              <Download className="h-4 w-4" /> {t('overview.exportCsv')}
            </Button>
            <Link to="/repos">
              <Button><ShieldCheck className="h-4 w-4" /> {t('overview.validateBtn')}</Button>
            </Link>
          </div>
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

      {/* Security banner — mirrors the TopBar bell: a prominent, dismissible red/amber strip when unseen Snyk
          alerts exist, with a one-click jump to /snyk. Renders nothing when there are none. */}
      <SnykAlertBanner />

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

      {/* Everything waiting on a named human — moved above the fold (the governance selling point should be
          the first thing after the hero + ROI, not buried below the charts). */}
      {hasData && <DecisionQueue blockingOpen={summary?.totals.blockingOpen ?? 0} worstService={worstBlockingService} />}


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
                  // Drill-down: a legend row opens the Defects list filtered to that severity.
                  <Link key={s.label} to={`/defects?severity=${s.label}`}
                    className="group -mx-1 flex items-center gap-2 rounded px-1 py-0.5 hover:bg-ink-50/60">
                    <span className={`h-2.5 w-2.5 shrink-0 rounded-sm ${SEV_SWATCH[s.label] ?? 'bg-sev-info'}`} />
                    <span className="text-ink-700 group-hover:text-ink-900">{t(`severity.${s.label}`)}</span>
                    <span className="ml-auto font-semibold tabular-nums text-ink-900">{s.value}</span>
                    <ArrowRight className="h-3 w-3 text-muted opacity-0 group-hover:opacity-100" />
                  </Link>
                ))}
              </div>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title={t('overview.chartResolution')} subtitle={t('overview.chartResolutionSub')} />
            <CardBody className="flex min-h-[152px] items-center justify-center">
              {/* Gate on a resolved, non-empty metrics query — a 0% success-green gauge would misread as "nothing
                  resolved" on a portfolio that simply has no defects yet. */}
              {metricsQ.data && metricsQ.data.total > 0 ? (
                <Gauge value={metricsQ.data.closed} max={metricsQ.data.total}
                  ariaLabel={t('overview.chartResolution')} centerLabel={t('overview.chartResolved')} />
              ) : (
                <p className="text-sm text-muted">{t('overview.chartResolutionEmpty')}</p>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader title={t('overview.chartTrend')}
              subtitle={t('overview.chartTrendSub', { count: range })}
              action={
                <div className="flex items-center gap-2">
                  {findingsWeekly != null && findingsWeekly !== 0 && (
                    <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-2xs font-semibold',
                      findingsWeekly < 0 ? TONE.ok : TONE.muted)}>
                      {t(findingsWeekly < 0 ? 'overview.trendWeekFewer' : 'overview.trendWeekMore', { count: Math.abs(findingsWeekly) })}
                    </span>
                  )}
                  <div className="inline-flex rounded-md bg-ink-50 p-0.5 text-2xs font-semibold ring-1 ring-border"
                    role="group" aria-label={t('overview.rangeLabel')}>
                    {TREND_RANGES.map((r) => (
                      <button key={r} type="button" aria-pressed={range === r}
                        onClick={() => setParams((p) => { const n = new URLSearchParams(p); n.set('range', String(r)); return n; }, { replace: true })}
                        className={cn('rounded px-1.5 py-0.5 transition-colors',
                          range === r ? 'bg-surface text-ink-900 shadow-card' : 'text-muted hover:text-ink-900')}>
                        {t('overview.rangeDays', { count: r })}
                      </button>
                    ))}
                  </div>
                  <button type="button" aria-label={t('overview.exportCsv')} title={t('overview.exportCsv')}
                    onClick={() => downloadTrendCsv('veritas-findings-trend', t('overview.csvDate'), t('overview.csvFindings'), findingsTrend)}
                    className="grid h-8 w-8 place-items-center rounded-md text-ink-600 hover:bg-ink-50"><Download className="h-4 w-4" /></button>
                </div>
              } />
            <CardBody>
              <div className="relative">
                {lastFindings != null && (
                  <span className="absolute right-0 top-0 z-10 text-2xs text-muted">
                    {t('overview.trendLast', { count: lastFindings })}
                  </span>
                )}
                <TrendChart points={findingsTrend} ariaLabel={t('overview.chartTrend')} tone="brand" />
              </div>
            </CardBody>
          </Card>
        </div>
      )}

      {/* Dependency-security posture (Snyk) — found vs fixed; renders only when app-ids are watched. */}
      <SnykImpactCard />

      {/* Scorecard by service — letter grades, deltas, coverage honesty, assets, deep links. */}
      {services.length > 0 && (
        <Card className="mb-6">
          <CardHeader title={t('overview.pipelineTitle')} subtitle={t('overview.pipelineSubtitle')} />
          <CardBody className="p-0">
            <Table head={<>
              <Th>{t('overview.colService')}</Th>
              <Th>{t('overview.colVerdict')}</Th>
              <Th className="text-right">{t('overview.colCoverage')}</Th>
              <Th>{t('overview.colAssets')}</Th>
              <Th className="text-right">{t('overview.colScans')}</Th>
              <Th />
            </>}>
              {services.map((s, i) => {
                const sum = perServiceSummary.get(s.name);
                const latest = latestByService.get(s.name);
                const assets: Array<[string, number]> = [
                  [t('overview.assetStrategies'), s.strategies], [t('overview.assetConditions'), s.conditions],
                  [t('overview.assetCases'), s.cases], [t('overview.assetPlans'), s.plans],
                  [t('overview.assetCodegen'), s.codegenRuns]];
                return (
                  <Row key={s.name} index={i}>
                    <Td className="font-medium text-ink-900">{s.name}</Td>
                    <Td>
                      <span className="inline-flex items-center gap-1.5">
                        <VerdictBadge verdict={sum?.releaseSafe} />
                        {sum != null && sum.breakingCount > 0 && (
                          <span className="text-2xs font-semibold tabular-nums text-danger">
                            {t('overview.nBreaking', { n: sum.breakingCount })}
                          </span>
                        )}
                      </span>
                    </Td>
                    <Td className="text-right text-muted">
                      {latest?.coverageGaps != null
                        ? (latest.coverageGaps === 0
                          ? t('overview.coverageFull')
                          : t('overview.coverageGaps', { count: latest.coverageGaps }))
                        : '—'}
                    </Td>
                    <Td>
                      <span className="flex flex-wrap gap-1">
                        {assets.filter(([, n]) => n > 0).map(([label, n]) => (
                          <span key={label} className="rounded-full bg-ink-50 px-2 py-0.5 text-2xs text-ink-700 ring-1 ring-border">
                            {n} {label}
                          </span>
                        ))}
                        {assets.every(([, n]) => !n) && <span className="text-muted">—</span>}
                      </span>
                    </Td>
                    <Td className="text-right tabular-nums text-muted">{s.scans || '—'}</Td>
                    <Td className="text-right whitespace-nowrap">
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
                    </Td>
                  </Row>
                );
              })}
            </Table>
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
            <Table head={<>
              <Th>{t('overview.colService')}</Th>
              <Th>{t('overview.colStatus')}</Th>
              <Th>{t('overview.colVerdict')}</Th>
              <Th className="text-right">{t('overview.colFindings')}</Th>
              <Th>{t('overview.colAudit')}</Th>
              <Th>{t('overview.colStarted')}</Th>
              <Th />
            </>}>
              {recent.map((s, i) => {
                const dur = formatDuration(s.startedAt, s.finishedAt);
                return (
                  <Row key={s.id} index={i}>
                    <Td className="font-medium text-ink-900">{s.serviceName}</Td>
                    <Td><Badge className={statusTone(s.status)}>{statusLabel(s.status)}</Badge></Td>
                    <Td><VerdictBadge verdict={s.releaseSafe} /></Td>
                    <Td className="text-right tabular-nums text-ink-900">{s.totalFindings}</Td>
                    <Td className="text-muted">
                      {dur ? t('overview.auditedIn', { dur, cost: formatMoney(s.totalEstCostUsd ?? 0) }) : '—'}
                    </Td>
                    <Td className="text-muted">
                      <Tooltip label={formatDateTime(s.startedAt)}>
                        <span>{formatRelative(s.startedAt)}</span>
                      </Tooltip>
                    </Td>
                    <Td className="text-right whitespace-nowrap">
                      <Link to={`/findings/${s.id}`} className="inline-flex items-center gap-1 text-sm font-medium text-gold hover:underline">
                        {t('overview.view')} <ArrowRight className="h-3.5 w-3.5" />
                      </Link>
                      <a href={api.reportUrl(s.id)} target="_blank" rel="noreferrer"
                        className="ml-3 inline-flex items-center gap-1 text-sm font-medium text-muted hover:text-ink-900">
                        <FileText className="h-3.5 w-3.5" /> {t('overview.report')}
                      </a>
                    </Td>
                  </Row>
                );
              })}
            </Table>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
