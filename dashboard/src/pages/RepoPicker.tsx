import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { Search, ShieldCheck, GitBranch, FileCode, Star, Clock,
  Sparkles, Check, AlertTriangle, Loader2 } from 'lucide-react';
import { api, Repo } from '../api';
import { Button, Card, CardBody, EmptyState, ErrorState, Field, Input, PageHeader, Select, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { useToast } from '../components/Toast';
import { useCopilotAuth } from '../lib/copilotAuth';
import { SCAN_STEPS, STAGE_ORDER, stagePct, formatElapsed, useElapsed, useStageElapsed } from '../lib/scanStages';
import { cn } from '../components/cn';

const RECENTS_KEY = 'veritas-recent-appids';
const FAVS_KEY = 'veritas-fav-repos';
function readList(key: string): string[] {
  try { const v = JSON.parse(localStorage.getItem(key) || '[]'); return Array.isArray(v) ? v : []; } catch { return []; }
}
function writeList(key: string, list: string[]) {
  try { localStorage.setItem(key, JSON.stringify(list.slice(0, 12))); } catch { /* quota/private mode — non-fatal */ }
}

export function RepoPicker() {
  const { t } = useTranslation();
  const [appId, setAppId] = useState('');
  const [repos, setRepos] = useState<Repo[]>([]);
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [target, setTarget] = useState<Repo | null>(null);
  const [recents, setRecents] = useState<string[]>(() => readList(RECENTS_KEY));
  const [favs, setFavs] = useState<string[]>(() => readList(FAVS_KEY));
  const [filter, setFilter] = useState('');

  const search = (id: string = appId) => {
    if (!id) return;
    setAppId(id);
    setErr('');
    setLoading(true);
    setFilter('');
    api.repos(id)
      .then((r) => {
        setRepos(r);
        const next = [id, ...recents.filter((x) => x !== id)];   // most-recent-first, de-duped
        setRecents(next);
        writeList(RECENTS_KEY, next);
      })
      .catch((e) => setErr((e as Error)?.message || String(e)))
      .finally(() => { setLoading(false); setSearched(true); });
  };

  const favKey = (r: Repo) => `${appId}/${r.slug}`;
  const toggleFav = (r: Repo) => {
    const k = favKey(r);
    const next = favs.includes(k) ? favs.filter((x) => x !== k) : [k, ...favs];
    setFavs(next);
    writeList(FAVS_KEY, next);
  };

  const shown = useMemo(() => {
    const f = filter.trim().toLowerCase();
    const matched = f ? repos.filter((r) =>
      r.slug.toLowerCase().includes(f) || (r.description || '').toLowerCase().includes(f)) : repos;
    // pinned favourites first, then by slug
    return [...matched].sort((a, b) => {
      const fa = favs.includes(favKey(a)) ? 0 : 1;
      const fb = favs.includes(favKey(b)) ? 0 : 1;
      return fa !== fb ? fa - fb : a.slug.localeCompare(b.slug);
    });
  }, [repos, filter, favs, appId]);

  return (
    <div>
      <PageHeader title={t('repos.pageTitle')}
        subtitle={t('repos.pageSubtitle')} />

      <Card className="mb-6">
        <CardBody>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <div className="flex-1">
              <Field label={t('repos.appIdLabel')} hint={t('repos.appIdHint')}>
                <Input placeholder="APP7571" value={appId} autoFocus
                  onChange={(e) => setAppId(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && appId && search()} />
              </Field>
            </div>
            <Button onClick={() => search()} disabled={!appId || loading} className="sm:mb-0.5">
              {loading ? <Spinner className="text-white" /> : <Search className="h-4 w-4" />}
              {loading ? t('repos.searching') : t('repos.findRepos')}
            </Button>
          </div>
          {recents.length > 0 && (
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1 text-xs text-muted"><Clock className="h-3.5 w-3.5" /> {t('repos.recent')}</span>
              {recents.map((id) => (
                <button key={id} onClick={() => search(id)}
                  className="rounded-full px-2.5 py-1 text-xs font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
                  {id}
                </button>
              ))}
            </div>
          )}
          {err && <div className="mt-3"><ErrorState message={t('repos.loadError')} detail={err} /></div>}
        </CardBody>
      </Card>

      {repos.length > 0 ? (
        <Card>
          <CardBody className="p-0">
            <div className="border-b border-border p-3">
              <div className="relative max-w-xs">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                <Input className="pl-8" placeholder={t('repos.filterPlaceholder', { count: repos.length })} value={filter}
                  onChange={(e) => setFilter(e.target.value)} aria-label={t('repos.filterAriaLabel')} />
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted">
                    <th className="w-10 px-5 py-3" />
                    <th className="px-5 py-3 font-medium">{t('repos.colRepo')}</th>
                    <th className="px-5 py-3 font-medium">{t('repos.colProject')}</th>
                    <th className="px-5 py-3 font-medium">{t('repos.colDefaultBranch')}</th>
                    <th className="px-5 py-3 font-medium">{t('repos.colDescription')}</th>
                    <th className="px-5 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {shown.map((r) => {
                    const pinned = favs.includes(favKey(r));
                    return (
                      <tr key={r.slug} className="border-b border-border/60 last:border-0 hover:bg-ink-50/60">
                        <td className="px-5 py-3">
                          <button onClick={() => toggleFav(r)} aria-label={pinned ? t('repos.unpin') : t('repos.pin')} title={pinned ? t('repos.unpin') : t('repos.pinToTop')}>
                            <Star className={cn('h-4 w-4', pinned ? 'fill-gold text-gold' : 'text-muted/50 hover:text-muted')} />
                          </button>
                        </td>
                        <td className="px-5 py-3 font-mono text-sm font-medium text-ink-900">{r.slug}</td>
                        <td className="px-5 py-3 text-muted">{r.projectKey}</td>
                        <td className="px-5 py-3 text-muted">{r.defaultBranch}</td>
                        <td className="px-5 py-3 text-muted">{r.description}</td>
                        <td className="px-5 py-3 text-right">
                          <Button size="sm" variant="secondary" onClick={() => setTarget(r)}>
                            <ShieldCheck className="h-4 w-4" /> {t('repos.validate')}
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                  {shown.length === 0 && (
                    <tr><td colSpan={6} className="px-5 py-6 text-center text-sm text-muted">{t('repos.noReposMatch', { filter })}</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </CardBody>
        </Card>
      ) : (!err && (
        <EmptyState icon={Search}
          title={searched ? t('repos.emptyFoundTitle') : t('repos.emptySearchTitle')}
          body={searched
            ? t('repos.emptyFoundBody')
            : t('repos.emptySearchBody')} />
      ))}

      {target && (
        <ValidateModal repo={target} appId={appId} onClose={() => setTarget(null)} />
      )}
    </div>
  );
}

/* ── Guided "Validate" form → live progress stepper (no window.prompt, full visibility) ── */
function ValidateModal({ repo, appId, onClose }: { repo: Repo; appId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const navigate = useNavigate();
  const { needsCopilot, connected, signIn } = useCopilotAuth();
  const [branch, setBranch] = useState(repo.defaultBranch || '');
  const [specKind, setSpecKind] = useState<'REPO_PATH' | 'LIVE_DOCS' | 'CONFLUENCE'>('REPO_PATH');
  const [specRef, setSpecRef] = useState('openapi.yaml');
  const [triedStart, setTriedStart] = useState(false);   // after a start attempt, an empty spec ref shows inline
  const [thoroughness, setThoroughness] = useState<'ECONOMY' | 'STANDARD' | 'DEEP'>('STANDARD');
  const [starting, setStarting] = useState(false);
  const [scanId, setScanId] = useState<string | null>(null);
  const [startedAtMs, setStartedAtMs] = useState<number | null>(null);
  const [navigated, setNavigated] = useState(false);

  const SPEC = {
    REPO_PATH: { label: t('repos.specRepoPathLabel'), field: t('repos.specPathField'), placeholder: 'openapi.yaml',
      hint: t('repos.specRepoPathHint') },
    LIVE_DOCS: { label: t('repos.specLiveDocsLabel'), field: t('repos.specLiveDocsField'), placeholder: 'https://service.bnc.ca/v3/api-docs',
      hint: t('repos.specLiveDocsHint') },
    CONFLUENCE: { label: t('repos.specConfluenceLabel'), field: t('repos.specConfluenceField'),
      placeholder: 'https://wiki.bnc.ca/spaces/IAMAS/pages/1725186990/… or 1725186990',
      hint: t('repos.specConfluenceHint') },
  }[specKind];

  // Discover the repo's real branches so the user picks (e.g.) master, not a hardcoded "main".
  const branchesQ = useQuery({ queryKey: ['branches', appId, repo.slug], queryFn: () => api.branches(appId, repo.slug) });
  const branches = branchesQ.data ?? [];
  useEffect(() => {
    if (branches.length > 0 && !branches.includes(branch)) {
      setBranch(branches[0]);   // listBranches returns the default branch first
    }
  }, [branches]); // eslint-disable-line react-hooks/exhaustive-deps

  // Poll the scan once it's been kicked off; stop when it's no longer RUNNING.
  const scanQ = useQuery({
    queryKey: ['scan', scanId],
    queryFn: () => api.scan(scanId as string),
    enabled: !!scanId,
    refetchInterval: (q) => {
      const s = q.state.data?.status;
      return s && s !== 'RUNNING' ? false : 1100;
    },
  });
  const scan = scanQ.data;
  const stage = scan?.stage ?? 'QUEUED';
  const failed = scan?.status === 'FAILED';
  const done = scan?.status === 'COMPLETED';
  const running = !!scanId && !failed && !done;

  // On success, hand off to the findings view (once).
  useEffect(() => {
    if (done && scan && !navigated) {
      setNavigated(true);
      toast.push('success', t('repos.toastScanComplete', { count: scan.totalFindings }));
      onClose();
      navigate(`/findings/${scan.id}`);
    }
  }, [done, scan, navigated]); // eslint-disable-line react-hooks/exhaustive-deps

  const start = async () => {
    setTriedStart(true);
    if (!specRef.trim()) { toast.push('error', t('repos.toastEnterField', { field: SPEC.field.toLowerCase() })); return; }
    setStarting(true);
    try {
      const common = { serviceName: repo.slug, appId, repoSlug: repo.slug, branch: branch.trim() || undefined, thoroughness };
      const res = await api.triggerScan(specKind === 'REPO_PATH'
        ? { ...common, specPaths: [specRef.trim()] }
        : { ...common, specSources: [{ kind: specKind, ref: specRef.trim() }] });
      setScanId(res.scanId);
      setStartedAtMs(Date.now());
    } catch (e) {
      toast.push('error', t('repos.toastCouldNotStart', { message: (e as Error).message }));
    } finally {
      setStarting(false);
    }
  };

  const retry = () => { setScanId(null); setStartedAtMs(null); setNavigated(false); };

  // Just close — the server-truth Activity dock already shows every running scan automatically.
  const runInBackground = () => onClose();

  const title = scanId ? t('repos.modalTitleValidating', { slug: repo.slug }) : t('repos.modalTitleValidate', { slug: repo.slug });

  // Footer changes with the phase: form → starting; running → background; failed → retry.
  const footer = !scanId ? (
    <>
      <Button variant="secondary" onClick={onClose}>{t('repos.cancel')}</Button>
      <Button onClick={start} loading={starting}><ShieldCheck className="h-4 w-4" /> {t('repos.runValidation')}</Button>
    </>
  ) : failed ? (
    <>
      <Button variant="secondary" onClick={onClose}>{t('repos.close')}</Button>
      <Button onClick={retry}><ShieldCheck className="h-4 w-4" /> {t('repos.tryAgain')}</Button>
    </>
  ) : (
    <Button variant="secondary" onClick={runInBackground}>{t('repos.runInBackground')}</Button>
  );

  return (
    <Modal open title={title} onClose={onClose} footer={footer}>
      {!scanId ? (
        <>
          <p className="mb-4 text-sm text-muted">
            {t('repos.modalIntroPrefix')} <span className="font-medium text-ink-900">{repo.slug}</span>
            {' '}{t('repos.modalIntroSuffix')}
          </p>
          {needsCopilot && !connected && (
            <div className="mb-4 flex items-start gap-2 rounded-lg border border-warning/30 bg-warning/10 p-3 text-xs text-ink-700">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
              <span>{t('repos.copilotWarnPrefix')} <strong>{t('repos.copilotWarnEmphasis')}</strong>{' '}{t('repos.copilotWarnSuffix')}
                <button type="button" onClick={signIn} className="ml-1 font-medium text-gold hover:underline">{t('repos.connectNow')}</button>
              </span>
            </div>
          )}
          <div className="space-y-4">
            <Field label={t('repos.branchLabel')}
              hint={branchesQ.isLoading ? t('repos.branchHintLoading') : branches.length > 0 ? t('repos.branchHintDefault') : t('repos.branchHintManual')}>
              <div className="relative">
                <GitBranch className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted z-10" />
                {branches.length > 0 ? (
                  <Select className="pl-8" value={branch} onChange={(e) => setBranch(e.target.value)}>
                    {branches.map((b) => <option key={b} value={b}>{b}</option>)}
                  </Select>
                ) : (
                  <Input className="pl-8" placeholder="master" value={branch} onChange={(e) => setBranch(e.target.value)} />
                )}
              </div>
            </Field>
            <Field label={t('repos.specSourceLabel')} hint={t('repos.specSourceHint')}>
              <Select value={specKind} onChange={(e) => {
                const k = e.target.value as 'REPO_PATH' | 'LIVE_DOCS' | 'CONFLUENCE';
                setSpecKind(k);
                setSpecRef(k === 'REPO_PATH' ? 'openapi.yaml' : '');   // reset the ref to a sensible default per kind
              }}>
                <option value="REPO_PATH">{t('repos.specRepoPathLabel')}</option>
                <option value="LIVE_DOCS">{t('repos.specLiveDocsLabel')}</option>
                <option value="CONFLUENCE">{t('repos.specConfluenceLabel')}</option>
              </Select>
            </Field>
            <Field label={SPEC.field} hint={SPEC.hint}
              error={triedStart && !specRef.trim() ? t('repos.toastEnterField', { field: SPEC.field.toLowerCase() }) : undefined}>
              <div className="relative">
                <FileCode className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                <Input className="pl-8" placeholder={SPEC.placeholder} value={specRef}
                  onChange={(e) => setSpecRef(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && !starting && start()} />
              </div>
            </Field>
            <Field label={t('repos.thoroughnessLabel')} hint={t('repos.thoroughnessHint')}>
              <Select value={thoroughness} onChange={(e) => setThoroughness(e.target.value as 'ECONOMY' | 'STANDARD' | 'DEEP')}>
                <option value="ECONOMY">{t('repos.thoroughnessEconomy')}</option>
                <option value="STANDARD">{t('repos.thoroughnessStandard')}</option>
                <option value="DEEP">{t('repos.thoroughnessDeep')}</option>
              </Select>
            </Field>
          </div>
        </>
      ) : (
        <ScanProgress stage={stage} failed={failed} errorMessage={scan?.errorMessage} onRetry={retry}
          startMs={(scan?.startedAt && Date.parse(scan.startedAt)) || startedAtMs}
          stageDetail={scan?.stageDetail} model={scan?.model} failedStage={scan?.failedStage} />
      )}
    </Modal>
  );
}

/* ── Friendly step-by-step tracker the user can follow while a scan runs ──
      Premium header (live timer + progress bar + "step N of M"), a per-step timer on the
      active row, and a reassurance on the slow AI step so a long scan never reads as stuck. ── */
function ScanProgress({ stage, failed, errorMessage, onRetry, startMs, stageDetail, model, failedStage }:
  { stage: string; failed: boolean; errorMessage?: string; onRetry: () => void; startMs: number | null;
    stageDetail?: string; model?: string; failedStage?: string }) {
  const { t } = useTranslation();
  const total = SCAN_STEPS.length;
  const current = STAGE_ORDER[stage] ?? 0;
  // Which step actually failed comes from the backend's preserved `failedStage` (the live `stage` is overwritten
  // with FAILED). Without it we mark no specific step — never fabricate a "failed at the AI step" or fake green ticks.
  const failedStepIdx = failed && failedStage ? SCAN_STEPS.findIndex((s) => s.key === failedStage) : -1;
  const stepNo = Math.min(total, Math.max(0, current));
  const pct = failed ? (failedStage ? stagePct(failedStage) : 8) : stagePct(stage);
  const activeStep = SCAN_STEPS.find((s) => s.key === stage);
  const activeLabel = activeStep ? t(`scan.${activeStep.key}.label`) : undefined;
  const elapsed = useElapsed(startMs, !failed);          // whole-scan timer
  const stageElapsed = useStageElapsed(stage, !failed);  // current step's own timer
  // Time-aware reassurance for the slow AI step — escalates instead of insisting "a minute or two" at 8 minutes.
  const aiHint = stageElapsed < 90_000
    ? t('repos.aiHintShort')
    : stageElapsed < 240_000
      ? t('repos.aiHintMedium')
      : t('repos.aiHintLong', { elapsed: formatElapsed(stageElapsed) });

  return (
    <div>
      {/* Live header: where we are, overall progress, and a ticking clock. */}
      <div className={cn('mb-4 rounded-xl p-4 ring-1',
        failed ? 'bg-danger/5 ring-danger/20' : 'bg-gold/5 ring-gold/20')}>
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <p className={cn('text-2xs font-semibold uppercase tracking-wide',
              failed ? 'text-danger' : 'text-gold')}>
              {failed ? t('repos.statusStopped') : stepNo === 0 ? t('repos.statusStarting') : t('repos.statusStep', { stepNo, total })}
            </p>
            <p className="mt-0.5 truncate text-sm font-semibold text-ink-900">
              {failed ? t('repos.validationFailed') : activeLabel ?? t('repos.queued')}
            </p>
          </div>
          <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-surface/80 px-2.5 py-1 text-xs font-medium tabular-nums text-ink-700 ring-1 ring-border">
            <Clock className="h-3.5 w-3.5 text-muted" /> {formatElapsed(elapsed)}
          </span>
        </div>
        <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-white/60">
          <div className={cn('h-full rounded-full transition-all duration-progress',
            failed ? 'bg-danger' : 'bg-gold')}
            style={{ width: `${pct}%` }} />
        </div>
      </div>

      <p className="mb-3 text-sm text-muted">
        {failed
          ? t('repos.scanStoppedNote')
          : t('repos.scanStaticNote')}
      </p>
      <ol className="space-y-1">
        {SCAN_STEPS.map((step, i) => {
          const order = STAGE_ORDER[step.key];
          const isFailedHere = failed && i === failedStepIdx;
          const status: 'done' | 'active' | 'pending' | 'error' =
            isFailedHere ? 'error'
              : failed && i > failedStepIdx ? 'pending'
              : current > order ? 'done'
              : current === order ? 'active'
              : 'pending';
          const Icon = step.icon;
          return (
            <li key={step.key} className={cn('flex items-start gap-3 rounded-lg px-2 py-2',
              status === 'active' && 'bg-gold/5')}>
              <span className={cn('mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full ring-1',
                status === 'done' && 'bg-success/10 text-success ring-success/30',
                status === 'active' && 'bg-gold/10 text-gold ring-gold/30',
                status === 'error' && 'bg-danger/10 text-danger ring-danger/30',
                status === 'pending' && 'bg-ink-50 text-muted/60 ring-border')}>
                {status === 'done' ? <Check className="h-4 w-4" />
                  : status === 'active' ? <Loader2 className="h-4 w-4 animate-spin" />
                  : status === 'error' ? <AlertTriangle className="h-4 w-4" />
                  : <Icon className="h-4 w-4" />}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-2">
                  <p className={cn('truncate text-sm font-medium',
                    status === 'pending' ? 'text-muted' : 'text-ink-900')}>
                    {t(`scan.${step.key}.label`)}
                  </p>
                  {status === 'active' && (
                    <span className="shrink-0 text-2xs font-medium tabular-nums text-gold">{formatElapsed(stageElapsed)}</span>
                  )}
                  {status === 'done' && <span className="shrink-0 text-2xs font-medium text-success">{t('repos.done')}</span>}
                </div>
                <p className="text-xs text-muted">
                  {isFailedHere && errorMessage ? errorMessage
                    : status === 'active' && stageDetail ? stageDetail
                    : t(`scan.${step.key}.detail`)}
                </p>
                {status === 'active' && step.long && (
                  <div className="mt-1.5 space-y-1">
                    <p className="inline-flex items-center gap-1.5 rounded-md bg-gold/10 px-2 py-1 text-2xs text-gold ring-1 ring-gold/20">
                      <Sparkles className="h-3 w-3 shrink-0" /> {aiHint}
                    </p>
                    {model && (
                      <p className="text-2xs text-muted">{t('repos.modelLabel')} <span className="font-medium text-ink-700">{model}</span> {t('repos.modelSuffix')}</p>
                    )}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ol>
      {failed && (
        <div className="mt-4 rounded-lg border border-danger/30 bg-danger/5 p-3">
          <p className="flex items-center gap-1.5 text-sm font-semibold text-danger">
            <AlertTriangle className="h-4 w-4" /> {t('repos.validationFailed')}
          </p>
          {errorMessage && <p className="mt-1 break-words text-xs text-ink-700">{errorMessage}</p>}
          <button onClick={onRetry} className="mt-2 text-xs font-medium text-gold hover:underline">
            {t('repos.adjustAndRetry')}
          </button>
        </div>
      )}
    </div>
  );
}
