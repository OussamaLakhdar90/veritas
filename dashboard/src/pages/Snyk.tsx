import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { RefreshCw, Trash2, Eye, ExternalLink, ShieldCheck, PackageOpen, X, ShieldAlert, AlertTriangle,
  PlugZap, Settings as SettingsIcon } from 'lucide-react';
import { api, type SnykIssueView } from '../api';
import {
  Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, Input, Spinner,
  Table, Th, Td, Row, PageHeader,
} from '../components/ui';
import { SnykLogo } from '../components/SnykLogo';
import { SnykImpactCard } from '../components/SnykImpact';
import { SnykFixWizard } from '../components/SnykFixWizard';
import { useToast } from '../components/Toast';
import { snykSeverityBadge, TONE } from '../theme/tokens';

/** Plain-language "time ago"-ish label using the locale date. */
function when(iso?: string): string | null {
  return iso ? new Date(iso).toLocaleString() : null;
}

/** Snyk sends raw lowercase severities ("critical"…); map to the localized Critical/High/Medium/Low label. */
const SEV_KEY: Record<string, string> = {
  critical: 'snyk.sevCritical', high: 'snyk.sevHigh', medium: 'snyk.sevMedium', low: 'snyk.sevLow',
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

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['snyk-watches'] });
    qc.invalidateQueries({ queryKey: ['snyk-alerts', 'unseen'] });
    if (selectedWatch) qc.invalidateQueries({ queryKey: ['snyk-issues', selectedWatch] });
  };

  const refresh = useMutation({
    mutationFn: api.snykRefresh,
    onSuccess: (r) => { invalidate(); toast.push('success', t('snyk.refreshed', { count: r.polled })); },
    onError: (e: Error) => toast.push('error', t('snyk.refreshFailed', { message: e.message })),
  });

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
      qc.invalidateQueries({ queryKey: ['snyk-watches'] });
      if (ok > 0) toast.push('success', t('snyk.watchedApps', { count: ok }));
      if (failed > 0) toast.push('error', t('snyk.watchSomeFailed', { count: failed }));
    },
  });

  const removeWatch = useMutation({
    mutationFn: (id: string) => api.removeSnykWatch(id),
    onSuccess: (_r, id) => { if (selectedWatch === id) setSelectedWatch(null); qc.invalidateQueries({ queryKey: ['snyk-watches'] }); toast.push('success', t('snyk.removed')); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const dismissAlert = useMutation({
    mutationFn: (id: string) => api.markSnykAlertSeen(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['snyk-alerts', 'unseen'] }),
  });

  const watches = watchesQ.data ?? [];
  const alerts = alertsQ.data ?? [];

  return (
    <div>
      {/* Snyk-branded header */}
      <div className="mb-1 flex items-center gap-2">
        <SnykLogo className="h-7 w-7" />
        <span className="text-xl font-semibold tracking-tight text-ink-900">Snyk</span>
      </div>
      <PageHeader title={t('snyk.title')} subtitle={t('snyk.subtitle')}
        actions={<Button variant="secondary" loading={refresh.isPending} onClick={() => refresh.mutate()}>
          <RefreshCw className="h-4 w-4" /> {t('snyk.refresh')}</Button>} />

      {/* Managerial impact strip — found vs fixed (renders once at least one app-id is watched). */}
      <SnykImpactCard showLink={false} />

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
                  ? <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 animate-pulse text-danger" aria-hidden="true" />
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
          {orgsQ.isError ? (
            <ConnectSnykPanel />
          ) : orgsQ.isLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('snyk.loadingApps')}</div>
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

      {/* Watched repos */}
      {watchesQ.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('snyk.loading')}</CardBody></Card>
      ) : watchesQ.isError ? (
        <ErrorState message={(watchesQ.error as Error).message} />
      ) : watches.length === 0 ? (
        <EmptyState icon={ShieldCheck} title={t('snyk.noWatchesTitle')} body={t('snyk.noWatchesBody')} />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
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
                      <button type="button" onClick={() => setSelectedWatch(w.id)} title={t('snyk.viewIssues')}
                        className="rounded p-1.5 text-muted hover:bg-ink-50 hover:text-ink-900"><Eye className="h-4 w-4" /></button>
                      <button type="button" onClick={() => removeWatch.mutate(w.id)} title={t('snyk.remove')}
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
        <Card className="mt-6">
          <CardHeader title={t('snyk.issuesTitle', { repo: watches.find((w) => w.id === selectedWatch)?.repoSlug ?? '' })}
            action={<Button variant="ghost" size="sm" onClick={() => setSelectedWatch(null)}>{t('common.close')}</Button>} />
          <CardBody className="p-0">
            {issuesQ.isLoading ? (
              <div className="flex items-center gap-2 p-5 text-sm text-muted"><Spinner /> {t('snyk.issuesLoading')}</div>
            ) : issuesQ.isError ? (
              <div className="p-5 text-sm text-danger">{(issuesQ.error as Error).message}</div>
            ) : (issuesQ.data ?? []).length === 0 ? (
              <div className="p-5"><EmptyState icon={ShieldCheck} title={t('snyk.issuesEmpty')} /></div>
            ) : (
              <Table head={<>
                <Th>{t('snyk.colSeverity')}</Th>
                <Th>{t('snyk.colPackage')}</Th>
                <Th>{t('snyk.colProject')}</Th>
                <Th><span title={t('snyk.tipCve')}>{t('snyk.colCve')}</span></Th>
                <Th><span title={t('snyk.tipCvss')}>{t('snyk.colCvss')}</span></Th>
                <Th><span title={t('snyk.tipFixable')}>{t('snyk.colFix')}</span></Th>
              </>}>
                {(issuesQ.data ?? []).map((i: SnykIssueView) => (
                  <Row key={i.issueId + i.projectName}>
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
      )}

      <SnykFixWizard open={!!fixIssue} onClose={() => setFixIssue(null)} issue={fixIssue} watchId={selectedWatch ?? undefined}
        apps={Array.from(new Map(watches.map((w) => [w.orgSlug, { slug: w.orgSlug, name: w.orgName }])).values())}
        defaultApp={watches.find((w) => w.id === selectedWatch)?.orgSlug} />
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
