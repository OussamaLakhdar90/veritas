import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RefreshCw, Trash2, Eye, ExternalLink, ShieldCheck, PackageOpen, X } from 'lucide-react';
import { api, type SnykIssueView } from '../api';
import {
  Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, Field, Select, Spinner,
  Table, Th, Td, Row, PageHeader,
} from '../components/ui';
import { SnykLogo } from '../components/SnykLogo';
import { useToast } from '../components/Toast';
import { snykSeverityBadge, TONE } from '../theme/tokens';

/** Plain-language "time ago"-ish label using the locale date. */
function when(iso?: string): string | null {
  return iso ? new Date(iso).toLocaleString() : null;
}

export function Snyk() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();

  const [orgId, setOrgId] = useState('');
  const [targetId, setTargetId] = useState('');
  const [selectedWatch, setSelectedWatch] = useState<string | null>(null);

  const orgsQ = useQuery({ queryKey: ['snyk-orgs'], queryFn: api.snykOrgs });
  const reposQ = useQuery({
    queryKey: ['snyk-repos', orgId], queryFn: () => api.snykRepos(orgId), enabled: !!orgId,
  });
  const watchesQ = useQuery({ queryKey: ['snyk-watches'], queryFn: api.snykWatches });
  const alertsQ = useQuery({ queryKey: ['snyk-alerts', 'unseen'], queryFn: () => api.snykAlerts(true) });
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

  const addWatch = useMutation({
    mutationFn: () => {
      const org = orgsQ.data?.find((o) => o.id === orgId);
      const repo = reposQ.data?.find((rp) => rp.id === targetId);
      return api.addSnykWatch({
        orgId, orgSlug: org?.slug ?? '', orgName: org?.name ?? '',
        targetId, repoSlug: repo?.displayName ?? '',
      });
    },
    onSuccess: (w) => { setTargetId(''); qc.invalidateQueries({ queryKey: ['snyk-watches'] }); toast.push('success', t('snyk.watchAdded', { repo: w.repoSlug })); },
    onError: (e: Error) => toast.push('error', t('snyk.watchFailed', { message: e.message })),
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
        <span className="text-[20px] font-semibold tracking-tight text-ink-900">Snyk</span>
      </div>
      <PageHeader title={t('snyk.title')} subtitle={t('snyk.subtitle')}
        actions={<Button variant="secondary" loading={refresh.isPending} onClick={() => refresh.mutate()}>
          <RefreshCw className="h-4 w-4" /> {t('snyk.refresh')}</Button>} />

      {/* New-alert notifications (Critical in red) */}
      {alerts.length > 0 && (
        <div className="mb-6 space-y-2">
          {alerts.map((a) => (
            <div key={a.id} role="alert"
              className={`flex items-start justify-between gap-3 rounded-lg border-l-4 px-4 py-3 text-[13px] ${
                a.severity === 'critical' ? 'border-l-danger bg-danger/5' : 'border-l-warning bg-warning/5'}`}>
              <div className="flex items-start gap-2">
                <Badge className={snykSeverityBadge(a.severity)}>{a.severity}</Badge>
                <span className="text-ink-900">{a.message}</span>
              </div>
              <button type="button" onClick={() => dismissAlert.mutate(a.id)} aria-label={t('snyk.alertDismiss')}
                className="shrink-0 rounded p-0.5 text-muted hover:text-ink-900">
                <X className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Add a watch */}
      <Card className="mb-6">
        <CardHeader title={t('snyk.addTitle')} subtitle={t('snyk.addBody')} />
        <CardBody>
          {orgsQ.isError ? (
            <p className="text-[13px] text-muted">{t('snyk.connectHint')}</p>
          ) : (
            <div className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_auto]">
              <Field label={t('snyk.orgLabel')}>
                <Select value={orgId} onChange={(e) => { setOrgId(e.target.value); setTargetId(''); }}>
                  <option value="">{t('snyk.selectOrg')}</option>
                  {(orgsQ.data ?? []).map((o) => <option key={o.id} value={o.id}>{o.name || o.slug}</option>)}
                </Select>
              </Field>
              <Field label={t('snyk.repoLabel')}>
                <Select value={targetId} onChange={(e) => setTargetId(e.target.value)} disabled={!orgId || reposQ.isLoading}>
                  <option value="">{reposQ.isLoading ? t('snyk.loadingRepos') : t('snyk.selectRepo')}</option>
                  {(reposQ.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.displayName}</option>)}
                </Select>
              </Field>
              <Button disabled={!orgId || !targetId} loading={addWatch.isPending} onClick={() => addWatch.mutate()}>
                {t('snyk.watchBtn')}
              </Button>
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
                      <p className="text-[12px] text-muted">{w.orgSlug}</p>
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
                  <p className="mt-3 text-[12px] text-muted">
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
                    <Td><Badge className={snykSeverityBadge(i.severity)}>{i.severity}</Badge></Td>
                    <Td>
                      <span className="font-medium text-ink-900">{i.pkgName}</span>
                      <span className="text-muted">@{i.pkgVersion}</span>
                      <span className="block text-[12px] text-muted">{i.title}</span>
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
                      {i.fixable && i.fixedIn
                        ? <Badge className={TONE.ok}>{t('snyk.fixAvailable', { version: i.fixedIn })}</Badge>
                        : <span className="inline-flex items-center gap-1 text-[12px] text-muted">
                            <PackageOpen className="h-3.5 w-3.5" /> {t('snyk.noFix')}</span>}
                    </Td>
                  </Row>
                ))}
              </Table>
            )}
          </CardBody>
        </Card>
      )}
    </div>
  );
}
