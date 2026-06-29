import { useState, useEffect } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Search, ArrowRight, ArrowLeft, Plus, Check, AlertTriangle, Sparkles, GitPullRequest, FileCode, GitPullRequestArrow, ExternalLink, Ticket, X, Lock, Trash2, KeyRound } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { api, Repo, TestGenPlan, TestGenPlanItem, CodegenRun, Scope, ServiceAuthGroup } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, Field, Input, PageHeader, Select, Spinner, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';

const STEP_KEYS = ['stepService', 'stepDestination', 'stepPlan', 'stepTokens', 'stepReview'] as const;

/** Default OAuth scopes for a freshly-authenticated group (the user fills the scope strings). */
const DEFAULT_SCOPES: Scope[] = [{ name: 'READ', value: '' }, { name: 'WRITE', value: '' }, { name: 'DELETE', value: '' }];
/** A fresh token group (one Okta token source). */
const newGroup = (name: string): ServiceAuthGroup => ({ name, tokenUrl: '', clientId: '', privateKeyField: '', scopes: DEFAULT_SCOPES, pathPrefixes: [] });

function parseList(json?: string): string[] {
  if (!json) return [];
  try { const v = JSON.parse(json); return Array.isArray(v) ? v : []; } catch { return []; }
}
const buildTone = (s?: string) => ({ PASS: TONE.ok, REPAIRED: TONE.warn, FAIL: TONE.danger, SKIPPED: TONE.muted }[s ?? ''] ?? TONE.muted);

/** Plain-language badge + tone for each reconciliation status. */
const STATUS: Record<string, { labelKey: string; tone: string; icon: typeof Plus }> = {
  GAP: { labelKey: 'statusAddTest', tone: TONE.warn, icon: Plus },
  CURRENT: { labelKey: 'statusCovered', tone: TONE.ok, icon: Check },
  ORPHAN: { labelKey: 'statusFlag', tone: TONE.muted, icon: AlertTriangle },
  STALE: { labelKey: 'statusUpdate', tone: TONE.danger, icon: ArrowRight },
};

function Stepper({ step }: { step: number }) {
  const { t } = useTranslation();
  return (
    <div className="mb-6 flex gap-2">
      {STEP_KEYS.map((key, i) => {
        const n = i + 1;
        const active = n === step;
        const done = n < step;
        return (
          <div key={key} className="flex-1">
            <div className={cn('h-1 rounded-full', done || active ? 'bg-brand' : 'bg-border')} />
            <p className={cn('mt-1.5 text-[11px]', active ? 'font-semibold text-ink-900' : done ? 'text-ink-700' : 'text-muted')}>
              {n}. {t(`wizard.${key}`)}
            </p>
          </div>
        );
      })}
    </div>
  );
}

/** The git-native, non-technical wizard: pick a service repo → say where the tests live → see the plan. */
export function GenerateApiTests() {
  const { t } = useTranslation();
  const toast = useToast();
  const { blocked, notice } = useCopilotGate();
  const [step, setStep] = useState(1);
  const [appId, setAppId] = useState('');
  const [repos, setRepos] = useState<Repo[]>([]);
  const [serviceRepo, setServiceRepo] = useState('');
  const [serviceBranch, setServiceBranch] = useState('');
  const [testRepo, setTestRepo] = useState('');   // '' = no existing tests (from scratch)
  const [testBranch, setTestBranch] = useState('');
  const [jiraKey, setJiraKey] = useState('');
  const [jiraSummary, setJiraSummary] = useState('');
  const [jiraQuery, setJiraQuery] = useState('');
  const [plan, setPlan] = useState<TestGenPlan | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  // Token groups (the BNC lsist framework). 0 groups = public; each group is one Okta token source for an API group.
  const [authGroups, setAuthGroups] = useState<ServiceAuthGroup[]>([]);
  const [run, setRun] = useState<CodegenRun | null>(null);
  const [prBranch, setPrBranch] = useState('main');

  // Pre-fill the Auth step from the service's saved profile (URLs / client ids / scope strings — never the private key).
  const authPrefill = useQuery({
    queryKey: ['auth-profile', appId, serviceRepo],
    queryFn: () => api.authProfile(serviceRepo, appId, serviceRepo),
    enabled: !!appId && !!serviceRepo,
  });
  useEffect(() => { if (authPrefill.data?.groups) setAuthGroups(authPrefill.data.groups); }, [authPrefill.data]);

  const setPreset = (n: number) =>
    setAuthGroups(n === 0 ? [] : n === 1 ? [newGroup('primary')] : [newGroup('tpps'), newGroup('apps')]);
  const patchGroup = (i: number, patch: Partial<ServiceAuthGroup>) =>
    setAuthGroups((gs) => gs.map((g, idx) => (idx === i ? { ...g, ...patch } : g)));
  const serviceAuth = { groups: authGroups };

  // Where the generated tests are written and the PR is later opened: the chosen test repo, or the service repo itself
  // when starting from scratch with no separate test repo.
  const outputRepo = testRepo || serviceRepo;

  const loadRepos = useMutation({
    mutationFn: () => api.repos(appId),
    onSuccess: (rs) => {
      setRepos(rs);
      if (rs.length === 0) toast.push('error', t('genApi.noReposFound'));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const svcBranches = useQuery({
    queryKey: ['branches', appId, serviceRepo],
    queryFn: () => api.branches(appId, serviceRepo),
    enabled: !!appId && !!serviceRepo,
  });
  const testBranches = useQuery({
    queryKey: ['branches', appId, testRepo],
    queryFn: () => api.branches(appId, testRepo),
    enabled: !!appId && !!testRepo,
  });
  // Jira ticket search — runs once a ticket isn't already chosen and the query is long enough.
  const jiraResults = useQuery({
    queryKey: ['jira-search', jiraQuery],
    queryFn: () => api.jiraSearch(jiraQuery),
    enabled: jiraQuery.trim().length >= 2 && !jiraKey,
  });

  const planM = useMutation({
    mutationFn: () => api.testGenPlan(serviceRepo, {
      appId,
      serviceRepoSlug: serviceRepo,
      serviceBranch: serviceBranch || undefined,
      testRepoSlug: testRepo || undefined,
      testBranch: testBranch || undefined,
    }),
    onSuccess: (p) => {
      setPlan(p);
      // Default-select the actionable gaps; covered/orphaned rows are informational.
      setSelected(new Set(p.items.filter((i) => i.status === 'GAP' && i.signature).map((i) => i.signature!)));
      setStep(3);
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  // Generate the selected tests into a clone of the output repo. Does NOT push — that's the explicit publish click.
  const generateM = useMutation({
    mutationFn: () => api.testGenGenerate(serviceRepo, {
      appId,
      serviceRepoSlug: serviceRepo,
      serviceBranch: serviceBranch || undefined,
      outputRepoSlug: outputRepo,
      outputBranch: testBranch || serviceBranch || undefined,
      endpoints: [...selected],
      jiraKey,
      serviceAuth,
    }),
    onSuccess: (r) => { setRun(r); setStep(5); toast.push('success', t('genApi.testsGenerated')); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  // Explicit, user-clicked push: opens a PR for review. Never automatic; never merges.
  const publishM = useMutation({
    mutationFn: ({ allowFailedBuild }: { allowFailedBuild: boolean }) =>
      api.publishCodegen(run!.id, outputRepo, prBranch || 'main', allowFailedBuild),
    onSuccess: (updated) => {
      setRun(updated);
      toast.push('success', updated.prUrl ? t('genApi.prOpenedToast') : t('genApi.submittedForApproval'));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const toggle = (sig: string) => setSelected((s) => {
    const n = new Set(s);
    if (n.has(sig)) { n.delete(sig); } else { n.add(sig); }
    return n;
  });

  const gaps = plan?.items.filter((i) => i.status === 'GAP').length ?? 0;
  const covered = plan?.items.filter((i) => i.status === 'CURRENT').length ?? 0;
  const orphans = plan?.items.filter((i) => i.status === 'ORPHAN').length ?? 0;

  return (
    <div>
      <PageHeader title={t('genApi.pageTitle')}
        subtitle={t('genApi.pageSubtitle')} />
      <Stepper step={step} />

      {step === 1 && (
        <Card>
          <CardHeader title={t('genApi.step1Title')} subtitle={t('genApi.step1Subtitle')} />
          <CardBody className="space-y-4">
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <Field label={t('genApi.gitAppLabel')} hint={t('genApi.gitAppHint')}>
                  <Input placeholder="APP7571" value={appId} autoFocus
                    onChange={(e) => { setAppId(e.target.value); setRepos([]); setServiceRepo(''); }}
                    onKeyDown={(e) => e.key === 'Enter' && appId.trim() && loadRepos.mutate()} />
                </Field>
              </div>
              <Button variant="secondary" loading={loadRepos.isPending}
                onClick={() => appId.trim() ? loadRepos.mutate() : toast.push('error', t('genApi.enterGitAppId'))}>
                <Search className="h-4 w-4" /> {t('genApi.findRepos')}
              </Button>
            </div>

            {repos.length > 0 && (
              <>
                <Field label={t('genApi.serviceRepoLabel')}>
                  <Select value={serviceRepo} onChange={(e) => setServiceRepo(e.target.value)}>
                    <option value="">{t('genApi.chooseRepo')}</option>
                    {repos.map((r) => <option key={r.slug} value={r.slug}>{r.name || r.slug}</option>)}
                  </Select>
                </Field>
                {serviceRepo && (
                  <Field label={t('genApi.branchLabel')} hint={svcBranches.isLoading ? t('genApi.loadingBranches') : t('genApi.branchHintDefault')}>
                    <Select value={serviceBranch} onChange={(e) => setServiceBranch(e.target.value)}>
                      <option value="">{t('genApi.defaultBranchOption')}</option>
                      {(svcBranches.data ?? []).map((b) => <option key={b} value={b}>{b}</option>)}
                    </Select>
                  </Field>
                )}
              </>
            )}

            <div className="flex justify-end">
              <Button disabled={!serviceRepo} onClick={() => setStep(2)}>{t('genApi.next')} <ArrowRight className="h-4 w-4" /></Button>
            </div>
          </CardBody>
        </Card>
      )}

      {step === 2 && (
        <Card>
          <CardHeader title={t('genApi.step2Title')}
            subtitle={t('genApi.step2Subtitle')} />
          <CardBody className="space-y-4">
            <Field label={t('genApi.testRepoLabel')} hint={t('genApi.testRepoHint')}>
              <Select value={testRepo} onChange={(e) => setTestRepo(e.target.value)}>
                <option value="">{t('genApi.noTestsYet')}</option>
                {repos.map((r) => <option key={r.slug} value={r.slug}>{r.name || r.slug}</option>)}
              </Select>
            </Field>
            {testRepo && (
              <Field label={t('genApi.branchLabel')} hint={testBranches.isLoading ? t('genApi.loadingBranches') : undefined}>
                <Select value={testBranch} onChange={(e) => setTestBranch(e.target.value)}>
                  <option value="">{t('genApi.defaultBranchOption')}</option>
                  {(testBranches.data ?? []).map((b) => <option key={b} value={b}>{b}</option>)}
                </Select>
              </Field>
            )}

            <Field label={t('genApi.jiraTicketLabel')}
              hint={t('genApi.jiraTicketHint')}>
              {jiraKey ? (
                <div className="flex items-center justify-between gap-2 rounded-md border border-border bg-ink-50 px-3 py-2">
                  <span className="min-w-0 text-[13px]">
                    <span className="font-mono font-medium text-ink-900">{jiraKey}</span>
                    {jiraSummary && <span className="text-muted"> — {jiraSummary}</span>}
                  </span>
                  <button type="button" aria-label={t('genApi.clearTicket')} className="shrink-0 text-muted hover:text-ink-900"
                    onClick={() => { setJiraKey(''); setJiraSummary(''); setJiraQuery(''); }}>
                    <X className="h-4 w-4" />
                  </button>
                </div>
              ) : (
                <div className="relative">
                  <Ticket className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                  <Input className="pl-8" placeholder={t('genApi.jiraSearchPlaceholder')}
                    value={jiraQuery} onChange={(e) => setJiraQuery(e.target.value)} />
                  {jiraQuery.trim().length >= 2 && (
                    <div className="absolute z-10 mt-1 w-full overflow-hidden rounded-md border border-border bg-surface shadow-card">
                      {jiraResults.isLoading ? (
                        <p className="px-3 py-2 text-[13px] text-muted">{t('genApi.searching')}</p>
                      ) : (jiraResults.data ?? []).length === 0 ? (
                        <p className="px-3 py-2 text-[13px] text-muted">{t('genApi.noMatchingTickets')}</p>
                      ) : (
                        (jiraResults.data ?? []).map((i) => (
                          <button key={i.key} type="button"
                            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[13px] hover:bg-ink-50"
                            onClick={() => { setJiraKey(i.key); setJiraSummary(i.summary ?? ''); }}>
                            <span className="font-mono font-medium text-ink-900">{i.key}</span>
                            <span className="truncate text-muted">{i.summary}</span>
                          </button>
                        ))
                      )}
                    </div>
                  )}
                </div>
              )}
            </Field>

            <div className="flex items-center justify-between">
              <Button variant="secondary" onClick={() => setStep(1)}><ArrowLeft className="h-4 w-4" /> {t('genApi.back')}</Button>
              <Button loading={planM.isPending} disabled={!jiraKey}
                onClick={() => jiraKey ? planM.mutate() : toast.push('error', t('genApi.pickJiraFirst'))}>
                {t('genApi.seeThePlan')} <ArrowRight className="h-4 w-4" />
              </Button>
            </div>
          </CardBody>
        </Card>
      )}

      {step === 3 && (
        <Card>
          <CardHeader title={t('genApi.step3Title')}
            subtitle={plan?.mode === 'REFACTOR'
              ? t('genApi.step3SubtitleRefactor', { count: plan?.filesScanned ?? 0 })
              : t('genApi.step3SubtitleScratch')} />
          <CardBody className="space-y-5">
            {planM.isPending || !plan ? (
              <p className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('genApi.reconciling')}</p>
            ) : (
              <>
                <div className="grid grid-cols-3 gap-3">
                  <Tile label={t('genApi.tileCovered')} value={covered} />
                  <Tile label={t('genApi.tileNewTests')} value={gaps} tone="text-gold" />
                  <Tile label={t('genApi.tileOrphaned')} value={orphans} />
                </div>

                <div>
                  <p className="mb-2 text-[13px] text-muted">
                    {gaps > 0 ? t('genApi.uncheckHint') : t('genApi.everythingCovered')}
                  </p>
                  <Table head={<><Th /><Th>{t('genApi.thEndpoint')}</Th><Th>{t('genApi.thWhat')}</Th></>}>
                    {plan.items.map((it) => <PlanRow key={(it.signature ?? it.path) + it.status} it={it} selected={selected} toggle={toggle} />)}
                  </Table>
                </div>

                <div className="flex items-center justify-between border-t border-border pt-4">
                  <Button variant="secondary" onClick={() => setStep(2)}><ArrowLeft className="h-4 w-4" /> {t('genApi.back')}</Button>
                  <Button disabled={selected.size === 0} onClick={() => setStep(4)}>
                    {t('genApi.continueCount', { count: selected.size })} <ArrowRight className="h-4 w-4" />
                  </Button>
                </div>
                <p className="flex items-center gap-1.5 text-[12px] text-muted">
                  <GitPullRequest className="h-3.5 w-3.5" /> {t('genApi.nextTokensPrefix')} <span className="font-mono">{outputRepo || '—'}</span> {t('genApi.nextTokensSuffix')}
                </p>
              </>
            )}
          </CardBody>
        </Card>
      )}

      {step === 4 && (
        <Card>
          <CardHeader title={t('genApi.step4Title')}
            subtitle={t('genApi.step4Subtitle')} />
          <CardBody className="space-y-5">
            <div className="flex gap-2">
              <PresetBtn active={authGroups.length === 0} onClick={() => setPreset(0)}>{t('genApi.presetNoToken')}</PresetBtn>
              <PresetBtn active={authGroups.length === 1} onClick={() => setPreset(1)}>{t('genApi.presetOneToken')}</PresetBtn>
              <PresetBtn active={authGroups.length === 2} onClick={() => setPreset(2)}>{t('genApi.presetTwoTokens')}</PresetBtn>
            </div>

            {authGroups.length === 0 ? (
              <p className="rounded-lg bg-ink-50 p-3 text-[13px] text-muted">
                {t('genApi.publicServiceNote')}
              </p>
            ) : (
              authGroups.map((g, i) => (
                <AuthGroupCard key={i} index={i} group={g} multi={authGroups.length > 1} onChange={patchGroup} />
              ))
            )}

            {authGroups.length > 0 && (
              <p className="flex items-center gap-1.5 rounded-lg bg-success/5 p-2.5 text-[12px] text-success">
                <Lock className="h-3.5 w-3.5 shrink-0" /> {t('genApi.privateKeyNote1')} <span className="font-mono">oktaCredentials.json</span> {t('genApi.privateKeyNote2')}
                <span className="font-mono">"$sensitive:…"</span> {t('genApi.privateKeyNote3')}
              </p>
            )}

            <div className="flex items-center justify-between border-t border-border pt-4">
              <Button variant="secondary" onClick={() => setStep(3)}><ArrowLeft className="h-4 w-4" /> {t('genApi.back')}</Button>
              <span className="flex items-center gap-3">
                {notice}
                <Button disabled={selected.size === 0 || blocked} loading={generateM.isPending}
                  onClick={() => generateM.mutate()}>
                  <Sparkles className="h-4 w-4" /> {t('genApi.generateSelected', { count: selected.size })}
                </Button>
              </span>
            </div>
          </CardBody>
        </Card>
      )}

      {step === 5 && run && (
        <Card>
          <CardHeader
            title={<span className="inline-flex items-center gap-2">{t('genApi.step5Title')}
              <Badge className={buildTone(run.buildStatus)}>{t('genApi.buildBadge', { status: run.buildStatus ?? '—' })}</Badge></span>}
            subtitle={t('genApi.step5Subtitle', { name: run.serviceName })} />
          <CardBody className="space-y-5">
            {run.buildStatus === 'SKIPPED' && (
              <div className="rounded-lg border-l-4 border-l-warning bg-warning/5 p-3 text-[13px] text-ink-700">
                {t('genApi.buildSkippedNote')}
              </div>
            )}

            <div>
              <p className="mb-1.5 text-[13px] font-semibold text-ink-900">{t('genApi.generatedFiles')}</p>
              <ul className="space-y-1">
                {parseList(run.filesWritten).map((f) => (
                  <li key={f} className="flex items-center gap-2 font-mono text-[12.5px] text-muted"><FileCode className="h-3.5 w-3.5 shrink-0" /> {f}</li>
                ))}
                {parseList(run.filesWritten).length === 0 && <li className="text-[13px] text-muted">—</li>}
              </ul>
            </div>

            {parseList(run.todos).length > 0 && (
              <div className="rounded-lg border-l-4 border-l-warning bg-warning/5 p-3">
                <p className="mb-1 text-[13px] font-semibold text-ink-900">{t('genApi.beforeTheseRun')}</p>
                <ul className="list-disc space-y-0.5 pl-5 text-[13px] text-ink-700">
                  {parseList(run.todos).map((t, i) => <li key={i}>{t}</li>)}
                </ul>
              </div>
            )}

            {run.prUrl ? (
              <div className="space-y-3">
                <p className="text-sm">{t('genApi.pullRequestOpenedLabel')} <a href={run.prUrl} target="_blank" rel="noreferrer"
                  className="inline-flex items-center gap-1 font-medium text-gold hover:underline">{run.prUrl} <ExternalLink className="h-3.5 w-3.5" /></a></p>
                <div className="rounded-lg bg-ink-50 p-3 text-[13px] text-ink-700">
                  <p className="mb-1 font-semibold text-ink-900">{t('genApi.testItLocally')}</p>
                  <p>{t('genApi.testItLocallyBody')}</p>
                  <pre className="mt-2 overflow-x-auto rounded bg-ink-900/90 px-3 py-2 font-mono text-[12px] text-white">git fetch origin{'\n'}git checkout {run.branch ?? t('genApi.thePrBranch')}</pre>
                </div>
              </div>
            ) : (
              <div className="flex flex-col gap-3 border-t border-border pt-4 sm:flex-row sm:items-end">
                <div className="flex-1">
                  <Field label={t('genApi.openPrAgainst')} hint={t('genApi.baseBranchHint', { repo: outputRepo })}>
                    <Input value={prBranch} onChange={(e) => setPrBranch(e.target.value)} placeholder="main" />
                  </Field>
                </div>
                <Button loading={publishM.isPending && !publishM.variables?.allowFailedBuild}
                  onClick={() => publishM.mutate({ allowFailedBuild: false })}>
                  <GitPullRequestArrow className="h-4 w-4" /> {t('genApi.openPullRequest')}
                </Button>
                {run.buildStatus === 'FAIL' && (
                  <Button variant="secondary" loading={publishM.isPending && publishM.variables?.allowFailedBuild}
                    title={t('genApi.openAnywayTitle')}
                    onClick={() => publishM.mutate({ allowFailedBuild: true })}>
                    {t('genApi.openAnyway')}
                  </Button>
                )}
              </div>
            )}

            <div className="flex items-center justify-between border-t border-border pt-4">
              <Button variant="secondary" onClick={() => setStep(4)}><ArrowLeft className="h-4 w-4" /> {t('genApi.back')}</Button>
              <p className="flex items-center gap-1.5 text-[12px] text-muted">
                <GitPullRequest className="h-3.5 w-3.5" /> {t('genApi.openingPrNote')}
              </p>
            </div>
          </CardBody>
        </Card>
      )}
    </div>
  );
}

function PresetBtn({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick}
      className={cn('flex-1 rounded-md border px-3 py-2 text-[13px] transition-colors',
        active ? 'border-brand bg-brand/10 font-semibold text-ink-900' : 'border-border text-muted hover:bg-ink-50')}>
      {children}
    </button>
  );
}

/** One token group = one Okta token source (token URL + client id + private-key field + scopes) for an API group. */
function AuthGroupCard({ index, group, multi, onChange }:
  { index: number; group: ServiceAuthGroup; multi: boolean; onChange: (i: number, patch: Partial<ServiceAuthGroup>) => void }) {
  const { t } = useTranslation();
  const scopes = group.scopes ?? [];
  const setScopes = (next: Scope[]) => onChange(index, { scopes: next });
  const patchScope = (si: number, patch: Partial<Scope>) => setScopes(scopes.map((s, idx) => (idx === si ? { ...s, ...patch } : s)));
  return (
    <div className="space-y-3 rounded-lg border border-border p-3">
      <div className="flex items-center gap-2">
        <span className="inline-flex items-center gap-1 rounded-full bg-brand/10 px-2 py-0.5 text-[12px] font-medium text-brand">
          <KeyRound className="h-3 w-3" /> {t('genApi.tokenLetter', { letter: String.fromCharCode(65 + index) })}
        </span>
        {multi && <span className="text-[12px] text-muted">{t('genApi.tokenForEndpoints')}</span>}
      </div>
      <div className="grid grid-cols-2 gap-3">
        {multi && (
          <Field label={t('genApi.groupNameLabel')} hint={t('genApi.groupNameHint')}>
            <Input aria-label={t('genApi.ariaGroupName', { index })} value={group.name} onChange={(e) => onChange(index, { name: e.target.value })} placeholder="tpps" />
          </Field>
        )}
        {multi && (
          <Field label={t('genApi.appliesToPathsLabel')} hint={t('genApi.appliesToPathsHint')}>
            <Input aria-label={t('genApi.ariaGroupPaths', { index })} value={(group.pathPrefixes ?? []).join(', ')}
              onChange={(e) => onChange(index, { pathPrefixes: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} />
          </Field>
        )}
        <Field label={t('genApi.oktaTokenUrlLabel')} hint={t('genApi.oktaTokenUrlHint')}>
          <Input aria-label={t('genApi.ariaGroupTokenUrl', { index })} value={group.tokenUrl ?? ''} onChange={(e) => onChange(index, { tokenUrl: e.target.value })}
            placeholder="https://your-okta/oauth2/<auth-server>/v1/token" />
        </Field>
        <Field label={t('genApi.oktaClientIdLabel')}>
          <Input aria-label={t('genApi.ariaGroupClientId', { index })} value={group.clientId ?? ''} onChange={(e) => onChange(index, { clientId: e.target.value })} placeholder="0oa…" />
        </Field>
        <div className="col-span-2">
          <Field label={t('genApi.privateKeyFieldLabel')} hint={t('genApi.privateKeyFieldHint')}>
            <Input aria-label={t('genApi.ariaGroupPrivateKeyField', { index })} value={group.privateKeyField ?? ''}
              onChange={(e) => onChange(index, { privateKeyField: e.target.value })} placeholder="MY_API_PRIVATE_KEY" />
          </Field>
        </div>
      </div>
      <div>
        <p className="mb-1.5 text-[12px] font-medium text-ink-900">{t('genApi.oauthScopes')}</p>
        <div className="space-y-2">
          {scopes.map((s, si) => (
            <div key={si} className="flex items-end gap-2">
              <div className="w-24">
                <Field label={si === 0 ? t('genApi.scopeNameLabel') : ''}>
                  <Input aria-label={t('genApi.ariaGroupScopeName', { index, si })} value={s.name}
                    onChange={(e) => patchScope(si, { name: e.target.value.toUpperCase() })} placeholder="READ" />
                </Field>
              </div>
              <div className="flex-1">
                <Field label={si === 0 ? t('genApi.oktaScopeStringLabel') : ''}>
                  <Input aria-label={t('genApi.ariaGroupScopeValue', { index, si })} value={s.value}
                    onChange={(e) => patchScope(si, { value: e.target.value })} placeholder="myapi:resource:read" />
                </Field>
              </div>
              <button type="button" aria-label={t('genApi.ariaGroupRemoveScope', { index, si })} className="mb-1.5 text-muted hover:text-danger"
                onClick={() => setScopes(scopes.filter((_, idx) => idx !== si))}>
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
        <Button variant="secondary" className="mt-2" onClick={() => setScopes([...scopes, { name: '', value: '' }])}>
          <Plus className="h-4 w-4" /> {t('genApi.addScope')}
        </Button>
      </div>
    </div>
  );
}

function Tile({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div className="rounded-lg bg-ink-50 p-3">
      <p className={cn('text-[12px]', tone ?? 'text-muted')}>{label}</p>
      <p className="mt-0.5 text-2xl font-semibold tabular-nums text-ink-900">{value}</p>
    </div>
  );
}

function PlanRow({ it, selected, toggle }: { it: TestGenPlanItem; selected: Set<string>; toggle: (sig: string) => void }) {
  const { t } = useTranslation();
  const meta = STATUS[it.status] ?? STATUS.GAP;
  const Icon = meta.icon;
  const sig = it.signature ?? it.path ?? '';
  const selectable = it.status === 'GAP';
  return (
    <Row>
      <Td>
        {selectable ? (
          <input type="checkbox" aria-label={t('genApi.selectEndpoint', { sig })} className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
            checked={selected.has(sig)} onChange={() => toggle(sig)} />
        ) : <span className="inline-block h-4 w-4" />}
      </Td>
      <Td>
        <div className="font-mono text-[12.5px] text-ink-900">{it.method} {it.path}</div>
        {it.reason && <div className="text-[11px] text-muted">{it.reason}</div>}
      </Td>
      <Td>
        <Badge className={meta.tone}><Icon className="h-3 w-3" /> {t(`genApi.${meta.labelKey}`)}</Badge>
      </Td>
    </Row>
  );
}
