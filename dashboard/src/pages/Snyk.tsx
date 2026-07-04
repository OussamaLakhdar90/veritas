import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { RefreshCw, Trash2, Eye, ExternalLink, ShieldCheck, PackageOpen, X, ShieldAlert, AlertTriangle,
  PlugZap, Settings as SettingsIcon } from 'lucide-react';
import { api, type ApiError, type SnykIssueView } from '../api';
import {
  Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, Input, Skeleton, Spinner, TableSkeleton,
  Table, Td, Row, SortableTh, useSort, PageHeader,
} from '../components/ui';
import { SnykLogo } from '../components/SnykLogo';
import { SnykImpactCard } from '../components/SnykImpact';
import { SnykFixWizard } from '../components/SnykFixWizard';
import { useToast } from '../components/Toast';
import { snykSeverityBadge, TONE } from '../theme/tokens';
import { formatDateTime } from '../lib/format';

/** Plain-language "time ago"-ish label using the locale date. */
function when(iso?: string): string | null {
  return iso ? formatDateTime(iso) : null;
}

/** True when a failed call is really "the Snyk token isn't configured" (server code: secret-required), so we can
 *  show an inline "connect your token" affordance instead of a generic red error. Mirrors the copilot-auth gate. */
function isSecretRequired(err: unknown): boolean {
  return (err as ApiError | null)?.code === 'secret-required';
}

/** Snyk sends raw lowercase severities ("critical"…); map to the localized Critical/High/Medium/Low label. */
const SEV_KEY: Record<string, string> = {
  critical: 'snyk.sevCritical', high: 'snyk.sevHigh', medium: 'snyk.sevMedium', low: 'snyk.sevLow',
};

/** Severity has no alphabetical order that reads right (critical < high < low ≠ meaningful), so sort by a
 *  rank instead — critical first when descending. Module-level for a stable useSort accessor reference. */
const SEV_RANK: Record<string, number> = { critical: 4, high: 3, medium: 2, low: 1 };
const ISSUE_ACCESSORS: Record<string, (i: SnykIssueView) => string | number> = {
  severity: (i) => SEV_RANK[(i.severity || '').toLowerCase()] ?? 0,
  package: (i) => i.pkgName ?? '',
  project: (i) => i.projectName ?? '',
  cve: (i) => i.cve ?? '',
  cvss: (i) => i.cvss ?? -1,
  fix: (i) => (i.fixable ? 1 : 0),
};

export function Snyk() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();

  const [selectedOrgs, setSelectedOrgs] = useState<Set<string>>(new Set());
  const [selectedWatch, setSelectedWatch] = useState<string | null>(null);
  const [fixIssue, setFixIssue] = useState<SnykIssueView | null>(null);

  const orgsQ = useQuery({ queryKey: ['snyk-orgs'], queryFn: api.snykOrgs });
  const watchesQ = useQuery({ queryKey: ['snyk-watches'], queryFn: api.snykWatches });
  // Poll so a new Critical raised by the daily/startup backend poll surfaces without a manual refresh.
  const alertsQ = useQuery({ queryKey: ['snyk-alerts', 'unseen'], queryFn: () => api.snykAlerts(true),
    refetchInterval: 30000, refetchOnWindowFocus: true });
  const issuesQ = useQuery({
    queryKey: ['snyk-issues', selectedWatch], queryFn: () => api.snykIssues(selectedWatch as string),
    enabled: !!selectedWatch,
  });
  // Severity-ranked by default (Critical first) — the order a security reviewer works top-down.
  const { sorted: sortedIssues, ...issueSort } = useSort(issuesQ.data ?? [], { key: 'severity', dir: 'desc' }, ISSUE_ACCESSORS);

  // Any change to what's watched moves every derived read at once: the watched list, the managerial summary/impact
  // card, the unseen-alert banner + bell, and (if open) the selected watch's issues. Used by refresh AND by the
  // watch/unwatch mutations so the whole page settles without a manual "Refresh now".
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['snyk-watches'] });
    qc.invalidateQueries({ queryKey: ['snyk-summary'] });
    qc.invalidateQueries({ queryKey: ['snyk-alerts', 'unseen'] });
    if (selectedWatch) qc.invalidateQueries({ queryKey: ['snyk-issues', selectedWatch] });
  };

  // "Refresh now" kicks off a BACKGROUND poll on the backend (202 returns fast) — it must NOT wait on the long
  // 30–60s Snyk poll to resolve, or the button would appear dead. On a successful 202 we start polling
  // GET /snyk/refresh/status and only settle (invalidate + toast) once it reports the background poll finished.
  const [polling, setPolling] = useState(false);

  const refresh = useMutation({
    mutationFn: api.snykRefresh,
    // 202 accepted → the poll now runs in the background; begin watching its status (the POST already returned fast).
    // Drop any cached status from a prior cycle first, so we never settle on a stale "not running" read before the
    // new background poll has been observed as running.
    onSuccess: () => { qc.removeQueries({ queryKey: ['snyk-refresh-status'] }); setPolling(true); },
    onError: (e: Error) => toast.push('error', t('snyk.refreshFailed', { message: e.message })),
  });

  // The running indicator covers BOTH phases: the (fast) POST in flight and the background poll we're tracking.
  const refreshing = refresh.isPending || polling;

  // While the background refresh runs, poll its status. When it flips to not-running, refresh the derived reads and
  // drop the indicator — the "Refresh now" button is never left hanging on the slow poll.
  const refreshStatusQ = useQuery({
    queryKey: ['snyk-refresh-status'], queryFn: api.snykRefreshStatus,
    enabled: polling, refetchInterval: 1500,
  });
  useEffect(() => {
    // Settle only on a fresh (not stale/in-flight) read that reports the poll done. The POST increments the backend's
    // in-flight count before it returns 202, so the first status read after onSuccess reliably sees running:true.
    if (polling && refreshStatusQ.data && !refreshStatusQ.data.running && !refreshStatusQ.isFetching) {
      setPolling(false);
      invalidate();
      toast.push('success', t('snyk.refreshDone'));
    }
  }, [polling, refreshStatusQ.data, refreshStatusQ.isFetching]); // eslint-disable-line react-hooks/exhaustive-deps

  const watchSelected = useMutation({
    mutationFn: async () => {
      const orgs = (orgsQ.data ?? []).filter((o) => selectedOrgs.has(o.id));
      return Promise.allSettled(orgs.map((o) =>
        api.addSnykWatchByApp({ orgId: o.id, orgSlug: o.slug, orgName: o.name })));
    },
    onSuccess: (results) => {
      const ok = results.filter((r) => r.status === 'fulfilled').length;
      const failed = results.length - ok;
      setSelectedOrgs(new Set());
      // Refetch the summary/impact card + alerts too, not just the watched list — the just-watched app's
      // vulnerabilities should surface without the user clicking "Refresh now".
      invalidate();
      if (ok > 0) toast.push('success', t('snyk.watchedApps', { count: ok }));
      if (failed > 0) toast.push('error', t('snyk.watchSomeFailed', { count: failed }));
    },
  });

  const removeWatch = useMutation({
    mutationFn: (id: string) => api.removeSnykWatch(id),
    // Unwatching also changes the summary/impact totals + alert set — invalidate the whole family, same as watch.
    onSuccess: (_r, id) => { if (selectedWatch === id) setSelectedWatch(null); invalidate(); toast.push('success', t('snyk.removed')); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const dismissAlert = useMutation({
    mutationFn: (id: string) => api.markSnykAlertSeen(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['snyk-alerts', 'unseen'] }),
  });

  const watches = watchesQ.data ?? [];
  const alerts = alertsQ.data ?? [];

  // "Connect your Snyk token" takes over the moment any Snyk read fails because the token isn't set — a config
  // gap, not a broken page. Shown once at the top; the per-section error states below defer to it.
  const tokenRequired = isSecretRequired(orgsQ.error) || isSecretRequired(watchesQ.error)
    || isSecretRequired(issuesQ.error) || isSecretRequired(refresh.error);

  return (
    <div>
      {/* Snyk-branded header */}
      <div className="mb-1 flex items-center gap-2">
        <SnykLogo className="h-7 w-7" />
        <span className="text-xl font-semibold tracking-tight text-ink-900">Snyk</span>
      </div>
      <PageHeader title={t('snyk.title')} subtitle={t('snyk.subtitle')}
        actions={<Button variant="secondary" loading={refreshing} disabled={refreshing} onClick={() => refresh.mutate()}>
          <RefreshCw className="h-4 w-4" /> {t('snyk.refresh')}</Button>} />

      {/* Managerial impact strip — found vs fixed (renders once at least one app-id is watched). */}
      <SnykImpactCard showLink={false} />

      {/* Token-missing takes precedence over every other error: a config gap, surfaced as an actionable panel. */}
      {tokenRequired && <SnykTokenRequiredBanner />}

      {/* New-alert notifications. Critical is deliberately alarming (strong red + a pulsing shield); lower severities
          stay calm. Each row is role="alert" (an implicit assertive live region) so screen readers announce arrivals
          — no wrapping aria-live, which would double-announce. */}
      <div className={alerts.length > 0 ? 'mb-6 space-y-2' : ''}>
        {alerts.map((a) => {
          const critical = a.severity === 'critical';
          return (
            <div key={a.id} role="alert"
              className={`flex items-start justify-between gap-3 rounded-lg border-l-4 px-4 py-3 text-sm ${
                critical ? 'border-l-danger bg-danger/10 ring-1 ring-danger/25 shadow-sm' : 'border-l-warning bg-warning/5'}`}>
              <div className="flex items-start gap-2.5">
                {critical
                  ? <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 motion-safe:animate-pulse text-danger" aria-hidden="true" />
                  : <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />}
                <Badge className={snykSeverityBadge(a.severity)}>{t(SEV_KEY[a.severity] ?? a.severity)}</Badge>
                <span className={critical ? 'font-semibold text-danger' : 'text-ink-900'}>{a.message}</span>
              </div>
              <button type="button" onClick={() => dismissAlert.mutate(a.id)} aria-label={t('snyk.alertDismiss')}
                className="shrink-0 rounded p-0.5 text-muted hover:text-ink-900">
                <X className="h-4 w-4" />
              </button>
            </div>
          );
        })}
      </div>

      {/* Watch applications (app-id-centric — each watch targets that app's application-tests repo) */}
      <Card className="mb-6">
        <CardHeader title={t('snyk.addTitle')} subtitle={t('snyk.addBody')} />
        <CardBody>
          {tokenRequired ? (
            // The top banner already owns the "connect your token" call-to-action — don't repeat it here.
            <p className="text-sm text-muted">{t('snyk.tokenRequiredHint')}</p>
          ) : orgsQ.isError ? (
            <ConnectSnykPanel />
          ) : orgsQ.isLoading ? (
            <div role="status" aria-live="polite" className="grid gap-2 sm:grid-cols-2">
              <span className="sr-only">{t('snyk.loadingApps')}</span>
              {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-8" />)}
            </div>
          ) : (orgsQ.data ?? []).length === 0 ? (
            <div className="text-sm text-muted">
              <p>{t('snyk.noApps')}</p>
              <Link to="/settings" className="mt-1 inline-flex items-center gap-1 font-medium text-gold hover:underline">
                <SettingsIcon className="h-3.5 w-3.5" /> {t('snyk.openSettings')}
              </Link>
            </div>
          ) : (
            <div>
              <div className="grid gap-1 sm:grid-cols-2">
                {(orgsQ.data ?? []).map((o) => (
                  <label key={o.id}
                    className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-ink-50/60">
                    <input type="checkbox" className="accent-brand" checked={selectedOrgs.has(o.id)}
                      onChange={(e) => setSelectedOrgs((prev) => {
                        const next = new Set(prev);
                        if (e.target.checked) next.add(o.id); else next.delete(o.id);
                        return next;
                      })} />
                    <span className="min-w-0 truncate text-ink-900">{o.name || o.slug}</span>
                    <span className="ml-auto shrink-0 text-2xs text-muted">{o.slug}</span>
                  </label>
                ))}
              </div>
              <div className="mt-3 flex flex-wrap items-center gap-3">
                <Button disabled={selectedOrgs.size === 0} loading={watchSelected.isPending}
                  onClick={() => watchSelected.mutate()}>{t('snyk.watchSelected')}</Button>
                <span className="text-xs text-muted">{t('snyk.watchHint')}</span>
              </div>
            </div>
          )}
        </CardBody>
      </Card>

      {/* A quiet "we're re-polling Snyk" line while a refresh runs — the button already spins; this tells the
          reader the data regions below are being refreshed too (not stale-but-idle). */}
      {refreshing && (
        <p role="status" aria-live="polite" className="mb-3 inline-flex items-center gap-1.5 text-xs text-muted">
          <Spinner className="h-3.5 w-3.5" /> {t('snyk.refreshing')}
        </p>
      )}

      {/* Watched repos */}
      {watchesQ.isLoading ? (
        <Card><CardBody className="p-0"><TableSkeleton label={t('snyk.loading')} /></CardBody></Card>
      ) : watchesQ.isError ? (
        <ErrorState message={t('snyk.watchesLoadError')} detail={(watchesQ.error as Error).message} />
      ) : watches.length === 0 ? (
        <EmptyState icon={ShieldCheck} title={t('snyk.noWatchesTitle')} body={t('snyk.noWatchesBody')} />
      ) : (
        <div aria-busy={refreshing}
          className={`grid gap-4 md:grid-cols-2 transition-opacity ${refreshing ? 'opacity-60' : ''}`}>
          {watches.map((w) => {
            const checked = when(w.lastPolled);
            return (
              <Card key={w.id} className={selectedWatch === w.id ? 'ring-2 ring-brand/40' : ''}>
                <CardBody>
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate font-semibold text-ink-900">{w.repoSlug}</p>
                      <p className="text-xs text-muted">{w.orgSlug}</p>
                    </div>
                    <div className="flex shrink-0 gap-1">
                      <button type="button" onClick={() => setSelectedWatch(w.id)} title={t('snyk.viewIssues')} aria-label={t('snyk.viewIssues')}
                        className="rounded p-1.5 text-muted hover:bg-ink-50 hover:text-ink-900"><Eye className="h-4 w-4" /></button>
                      <button type="button" onClick={() => removeWatch.mutate(w.id)} title={t('snyk.remove')} aria-label={t('snyk.remove')}
                        className="rounded p-1.5 text-muted hover:bg-ink-50 hover:text-danger"><Trash2 className="h-4 w-4" /></button>
                    </div>
                  </div>
                  <div className="mt-3 flex flex-wrap gap-1.5">
                    <Badge className={snykSeverityBadge('critical')}>{w.critical} {t('snyk.sevCritical')}</Badge>
                    <Badge className={snykSeverityBadge('high')}>{w.high} {t('snyk.sevHigh')}</Badge>
                    <Badge className={snykSeverityBadge('medium')}>{w.medium} {t('snyk.sevMedium')}</Badge>
                    <Badge className={snykSeverityBadge('low')}>{w.low} {t('snyk.sevLow')}</Badge>
                  </div>
                  <p className="mt-3 text-xs text-muted">
                    {t('snyk.projects', { count: w.projectCount })} · {t('snyk.fixable', { count: w.fixable })}
                    {' · '}{checked ? t('snyk.lastPolled', { when: checked }) : t('snyk.neverPolled')}
                  </p>
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}

      {/* Issues for the selected watch */}
      {selectedWatch && (
        <div aria-busy={refreshing} className={`transition-opacity ${refreshing ? 'opacity-60' : ''}`}>
        <Card className="mt-6">
          <CardHeader title={t('snyk.issuesTitle', { repo: watches.find((w) => w.id === selectedWatch)?.repoSlug ?? '' })}
            action={<Button variant="ghost" size="sm" onClick={() => setSelectedWatch(null)}>{t('common.close')}</Button>} />
          <CardBody className="p-0">
            {issuesQ.isLoading ? (
              <TableSkeleton rows={4} label={t('snyk.issuesLoading')} />
            ) : issuesQ.isError ? (
              isSecretRequired(issuesQ.error)
                ? <div className="p-5 text-sm text-muted">{t('snyk.tokenRequiredHint')}</div>
                : <div className="p-5"><ErrorState message={t('snyk.issuesLoadError')} detail={(issuesQ.error as Error).message} /></div>
            ) : (issuesQ.data ?? []).length === 0 ? (
              <div className="p-5"><EmptyState icon={ShieldCheck} title={t('snyk.issuesEmpty')} /></div>
            ) : (
              <Table stickyHead head={<>
                <SortableTh label={t('snyk.colSeverity')} sortKey="severity" sort={issueSort} />
                <SortableTh label={t('snyk.colPackage')} sortKey="package" sort={issueSort} />
                <SortableTh label={t('snyk.colProject')} sortKey="project" sort={issueSort} />
                <SortableTh label={t('snyk.colCve')} sortKey="cve" sort={issueSort} />
                <SortableTh label={t('snyk.colCvss')} sortKey="cvss" sort={issueSort} />
                <SortableTh label={t('snyk.colFix')} sortKey="fix" sort={issueSort} />
              </>}>
                {sortedIssues.map((i: SnykIssueView, idx: number) => (
                  <Row key={i.issueId + i.projectName} index={idx}>
                    <Td><Badge className={snykSeverityBadge(i.severity)}>{t(SEV_KEY[i.severity] ?? i.severity)}</Badge></Td>
                    <Td>
                      <span className="font-medium text-ink-900">{i.pkgName}</span>
                      <span className="text-muted">@{i.pkgVersion}</span>
                      <span className="block text-xs text-muted">{i.title}</span>
                    </Td>
                    <Td className="text-muted">{i.projectName}</Td>
                    <Td>
                      {i.cve
                        ? <a href={`https://nvd.nist.gov/vuln/detail/${encodeURIComponent(i.cve)}`} target="_blank"
                            rel="noreferrer" className="inline-flex items-center gap-1 text-gold hover:underline">
                            {i.cve} <ExternalLink className="h-3 w-3" /></a>
                        : <span className="text-muted">—</span>}
                    </Td>
                    <Td className="tabular-nums text-muted">{i.cvss > 0 ? i.cvss.toFixed(1) : '—'}</Td>
                    <Td>
                      {i.fixable && i.fixedIn ? (
                        <div className="flex items-center gap-2">
                          <Badge className={TONE.ok}>{t('snyk.fixAvailable', { version: i.fixedIn })}</Badge>
                          <Button size="sm" variant="ghost" onClick={() => setFixIssue(i)}>{t('snyk.fix.button')}</Button>
                        </div>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-xs text-muted">
                          <PackageOpen className="h-3.5 w-3.5" /> {t('snyk.noFix')}</span>
                      )}
                    </Td>
                  </Row>
                ))}
              </Table>
            )}
          </CardBody>
        </Card>
        </div>
      )}

      <SnykFixWizard open={!!fixIssue} onClose={() => setFixIssue(null)} issue={fixIssue} watchId={selectedWatch ?? undefined}
        apps={Array.from(new Map(watches.map((w) => [w.orgSlug, { slug: w.orgSlug, name: w.orgName }])).values())}
        defaultApp={watches.find((w) => w.id === selectedWatch)?.orgSlug} />
    </div>
  );
}

/** Inline "your Snyk token isn't set" panel — shown when a call fails with code: secret-required. Non-technical,
 *  with a one-click deep-link to Settings → Snyk (mirrors the copilot-auth gate's "connect" affordance). */
function SnykTokenRequiredBanner() {
  const { t } = useTranslation();
  return (
    <div role="alert"
      className="mb-6 flex items-start justify-between gap-3 rounded-lg border-l-4 border-l-warning bg-warning/10 px-4 py-3 text-sm">
      <span className="flex items-start gap-2.5">
        <PlugZap className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
        <span className="text-ink-900"><strong>{t('snyk.tokenRequiredTitle')}</strong> {t('snyk.tokenRequiredBody')}</span>
      </span>
      <Link to="/settings"
        className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-brand px-3 py-1 font-medium text-white hover:bg-brand-700">
        <SettingsIcon className="h-3.5 w-3.5" /> {t('snyk.tokenRequiredCta')}
      </Link>
    </div>
  );
}

/** Guided "Snyk isn't connected yet" state — paste your personal API token right here and connect in one click. */
function ConnectSnykPanel() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const [token, setToken] = useState('');
  const [err, setErr] = useState<string | null>(null);

  // Save the token (encrypted, server-side), then validate it via Test connection; on success the orgs query
  // refetches and this whole panel is replaced by the app list — no trip to Settings needed.
  const connect = useMutation({
    mutationFn: async () => {
      await api.setSecret('SNYK_API_TOKEN', token.trim());
      const res = await api.testConnection('snyk');
      if (!res.authenticated) {
        throw new Error(res.message || t('snyk.connectFailed'));
      }
      return res;
    },
    onSuccess: () => {
      setErr(null);
      setToken('');
      toast.push('success', t('snyk.connectedToast'));
      qc.invalidateQueries({ queryKey: ['snyk-orgs'] });
    },
    onError: (e: Error) => setErr(e.message || t('snyk.connectFailed')),
  });

  const badge = (n: number) => (
    <span className="mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand text-2xs font-semibold text-white">{n}</span>
  );

  return (
    <div className="rounded-xl border border-dashed border-border bg-ink-50/40 px-5 py-5">
      <div className="flex items-start gap-3">
        <div className="shrink-0 rounded-lg bg-brand-50 p-2"><PlugZap className="h-5 w-5 text-brand" /></div>
        <div className="min-w-0 flex-1">
          <p className="text-md font-semibold text-ink-900">{t('snyk.connectTitle')}</p>
          <p className="mt-0.5 text-sm text-muted">{t('snyk.connectBody')}</p>

          {/* Step 1 — get the token */}
          <p className="mt-3 flex items-start gap-2.5 text-sm text-ink-700">
            {badge(1)}
            <span className="min-w-0">{t('snyk.connectStep1')}{' '}
              <a href="https://app.snyk.io/account" target="_blank" rel="noreferrer"
                className="font-medium text-gold hover:underline">app.snyk.io/account</a></span>
          </p>

          {/* Step 2 — paste it right here + connect */}
          <form className="mt-2 flex items-start gap-2.5"
            onSubmit={(e) => { e.preventDefault(); if (token.trim()) connect.mutate(); }}>
            {badge(2)}
            <div className="flex flex-1 flex-wrap items-end gap-2">
              <div className="min-w-[240px] flex-1">
                <label htmlFor="snyk-token" className="mb-1 block text-xs font-medium text-ink-700">
                  {t('snyk.tokenLabel')}
                </label>
                <Input id="snyk-token" type="password" autoComplete="off" placeholder={t('snyk.tokenPlaceholder')}
                  value={token} onChange={(e) => setToken(e.target.value)} />
              </div>
              <Button type="submit" disabled={!token.trim()} loading={connect.isPending}>
                <PlugZap className="h-4 w-4" /> {t('snyk.connectCta2')}
              </Button>
            </div>
          </form>
          {err && <p className="mt-2 pl-[30px] text-sm text-danger" role="alert">{err}</p>}

          <p className="mt-3 text-xs text-muted">
            {t('snyk.connectAdvanced')}{' '}
            <Link to="/settings" className="inline-flex items-center gap-1 font-medium text-gold hover:underline">
              <SettingsIcon className="h-3.5 w-3.5" /> {t('snyk.openFullSettings')}
            </Link>
          </p>
        </div>
      </div>
    </div>
  );
}
