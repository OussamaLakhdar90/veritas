import { useState, useEffect } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Search, ArrowRight, ArrowLeft, Plus, Check, AlertTriangle, Sparkles, GitPullRequest, FileCode, GitPullRequestArrow, ExternalLink, Ticket, X, KeyRound, Lock, Terminal } from 'lucide-react';
import { api, Repo, TestGenPlan, TestGenPlanItem, CodegenRun, Mechanism, ServiceAuthGroup } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, Field, Input, PageHeader, Select, Spinner, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';

const STEPS = ['Service', 'Destination', 'Plan', 'Auth', 'Review'];

/** The token-generation mechanisms and the env-var roles each needs (the user types the env-var name for each role). */
const MECHANISMS: { value: Mechanism; label: string; roles: { key: string; label: string }[] }[] = [
  { value: 'PRIVATE_KEY', label: 'Private key', roles: [{ key: 'privateKey', label: 'private key' }] },
  { value: 'BASIC_AUTH', label: 'Basic auth (base64)', roles: [{ key: 'basicAuth', label: 'base64 Authorization' }] },
  { value: 'OAUTH2_CLIENT_CREDENTIALS', label: 'OAuth2 client credentials',
    roles: [{ key: 'clientId', label: 'client id' }, { key: 'clientSecret', label: 'client secret' }] },
];
const rolesFor = (m: Mechanism) => MECHANISMS.find((x) => x.value === m)?.roles ?? [];
const newGroup = (name: string): ServiceAuthGroup => ({ name, mechanism: 'PRIVATE_KEY', envVars: {}, pathPrefixes: [] });

function parseList(json?: string): string[] {
  if (!json) return [];
  try { const v = JSON.parse(json); return Array.isArray(v) ? v : []; } catch { return []; }
}
const buildTone = (s?: string) => ({ PASS: TONE.ok, REPAIRED: TONE.warn, FAIL: TONE.danger, SKIPPED: TONE.muted }[s ?? ''] ?? TONE.muted);

/** Plain-language badge + tone for each reconciliation status. */
const STATUS: Record<string, { label: string; tone: string; icon: typeof Plus }> = {
  GAP: { label: 'add test', tone: TONE.warn, icon: Plus },
  CURRENT: { label: 'covered', tone: TONE.ok, icon: Check },
  ORPHAN: { label: 'flag', tone: TONE.muted, icon: AlertTriangle },
  STALE: { label: 'update', tone: TONE.danger, icon: ArrowRight },
};

function Stepper({ step }: { step: number }) {
  return (
    <div className="mb-6 flex gap-2">
      {STEPS.map((label, i) => {
        const n = i + 1;
        const active = n === step;
        const done = n < step;
        return (
          <div key={label} className="flex-1">
            <div className={cn('h-1 rounded-full', done || active ? 'bg-brand' : 'bg-border')} />
            <p className={cn('mt-1.5 text-[11px]', active ? 'font-semibold text-ink-900' : done ? 'text-ink-700' : 'text-muted')}>
              {n}. {label}
            </p>
          </div>
        );
      })}
    </div>
  );
}

/** The git-native, non-technical wizard: pick a service repo → say where the tests live → see the plan. */
export function GenerateApiTests() {
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
  const [authGroups, setAuthGroups] = useState<ServiceAuthGroup[]>([]);
  const [run, setRun] = useState<CodegenRun | null>(null);
  const [prBranch, setPrBranch] = useState('main');

  // Pre-fill the Auth step from the service's saved profile (declared token groups — names only, never secrets).
  const authPrefill = useQuery({
    queryKey: ['auth-profile', appId, serviceRepo],
    queryFn: () => api.authProfile(serviceRepo, appId, serviceRepo),
    enabled: !!appId && !!serviceRepo,
  });
  useEffect(() => { if (authPrefill.data?.groups) setAuthGroups(authPrefill.data.groups); }, [authPrefill.data]);

  const setPreset = (n: number) =>
    setAuthGroups(n === 0 ? [] : n === 1 ? [newGroup('primary')] : [newGroup('primary'), newGroup('secondary')]);
  const patchGroup = (i: number, patch: Partial<ServiceAuthGroup>) =>
    setAuthGroups((gs) => gs.map((g, idx) => (idx === i ? { ...g, ...patch } : g)));
  // Every env-var name the user typed, as a copy-paste setx checklist (values stay in the user's environment).
  const setxLines = authGroups.flatMap((g) => Object.values(g.envVars).filter(Boolean)
    .map((name) => `setx ${name} "<value>"`));

  // Where the generated tests are written and the PR is later opened: the chosen test repo, or the service repo itself
  // when starting from scratch with no separate test repo.
  const outputRepo = testRepo || serviceRepo;

  const loadRepos = useMutation({
    mutationFn: () => api.repos(appId),
    onSuccess: (rs) => {
      setRepos(rs);
      if (rs.length === 0) toast.push('error', 'No repos found for that app.');
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
      serviceAuth: { groups: authGroups },
    }),
    onSuccess: (r) => { setRun(r); setStep(5); toast.push('success', 'Tests generated — review them, then open a PR.'); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  // Explicit, user-clicked push: opens a PR for review. Never automatic; never merges.
  const publishM = useMutation({
    mutationFn: ({ allowFailedBuild }: { allowFailedBuild: boolean }) =>
      api.publishCodegen(run!.id, outputRepo, prBranch || 'main', allowFailedBuild),
    onSuccess: (updated) => {
      setRun(updated);
      toast.push('success', updated.prUrl ? 'Pull request opened.' : 'Submitted for approval — approve it on the Gates page to open the PR.');
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
      <PageHeader title="Generate API tests"
        subtitle="Pick a service from git, see what's covered, and choose what to generate. Nothing is written without your approval." />
      <Stepper step={step} />

      {step === 1 && (
        <Card>
          <CardHeader title="Which service do you want tests for?" subtitle="Pick the service repo from git — we only read it." />
          <CardBody className="space-y-4">
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <Field label="Git app" hint="Your Bitbucket app id, e.g. APP7571.">
                  <Input placeholder="APP7571" value={appId} autoFocus
                    onChange={(e) => { setAppId(e.target.value); setRepos([]); setServiceRepo(''); }}
                    onKeyDown={(e) => e.key === 'Enter' && appId.trim() && loadRepos.mutate()} />
                </Field>
              </div>
              <Button variant="secondary" loading={loadRepos.isPending}
                onClick={() => appId.trim() ? loadRepos.mutate() : toast.push('error', 'Enter your git app id.')}>
                <Search className="h-4 w-4" /> Find repos
              </Button>
            </div>

            {repos.length > 0 && (
              <>
                <Field label="Service repo">
                  <Select value={serviceRepo} onChange={(e) => setServiceRepo(e.target.value)}>
                    <option value="">Choose a repo…</option>
                    {repos.map((r) => <option key={r.slug} value={r.slug}>{r.name || r.slug}</option>)}
                  </Select>
                </Field>
                {serviceRepo && (
                  <Field label="Branch" hint={svcBranches.isLoading ? 'Loading branches…' : 'Leave on the default branch unless you need another.'}>
                    <Select value={serviceBranch} onChange={(e) => setServiceBranch(e.target.value)}>
                      <option value="">(default branch)</option>
                      {(svcBranches.data ?? []).map((b) => <option key={b} value={b}>{b}</option>)}
                    </Select>
                  </Field>
                )}
              </>
            )}

            <div className="flex justify-end">
              <Button disabled={!serviceRepo} onClick={() => setStep(2)}>Next <ArrowRight className="h-4 w-4" /></Button>
            </div>
          </CardBody>
        </Card>
      )}

      {step === 2 && (
        <Card>
          <CardHeader title="Where are the tests?"
            subtitle="If you already have a test repo we'll update it; otherwise we start fresh." />
          <CardBody className="space-y-4">
            <Field label="Test repo" hint="Leave on “create them” to generate from scratch.">
              <Select value={testRepo} onChange={(e) => setTestRepo(e.target.value)}>
                <option value="">No tests yet — create them</option>
                {repos.map((r) => <option key={r.slug} value={r.slug}>{r.name || r.slug}</option>)}
              </Select>
            </Field>
            {testRepo && (
              <Field label="Branch" hint={testBranches.isLoading ? 'Loading branches…' : undefined}>
                <Select value={testBranch} onChange={(e) => setTestBranch(e.target.value)}>
                  <option value="">(default branch)</option>
                  {(testBranches.data ?? []).map((b) => <option key={b} value={b}>{b}</option>)}
                </Select>
              </Field>
            )}

            <Field label="Jira ticket *"
              hint="The work item these tests commit under — its key goes in the branch, commit and PR so Jira links them.">
              {jiraKey ? (
                <div className="flex items-center justify-between gap-2 rounded-md border border-border bg-ink-50 px-3 py-2">
                  <span className="min-w-0 text-[13px]">
                    <span className="font-mono font-medium text-ink-900">{jiraKey}</span>
                    {jiraSummary && <span className="text-muted"> — {jiraSummary}</span>}
                  </span>
                  <button type="button" aria-label="Clear ticket" className="shrink-0 text-muted hover:text-ink-900"
                    onClick={() => { setJiraKey(''); setJiraSummary(''); setJiraQuery(''); }}>
                    <X className="h-4 w-4" />
                  </button>
                </div>
              ) : (
                <div className="relative">
                  <Ticket className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                  <Input className="pl-8" placeholder="Search Jira, or paste a key / URL (e.g. CIAM-1842)"
                    value={jiraQuery} onChange={(e) => setJiraQuery(e.target.value)} />
                  {jiraQuery.trim().length >= 2 && (
                    <div className="absolute z-10 mt-1 w-full overflow-hidden rounded-md border border-border bg-surface shadow-card">
                      {jiraResults.isLoading ? (
                        <p className="px-3 py-2 text-[13px] text-muted">Searching…</p>
                      ) : (jiraResults.data ?? []).length === 0 ? (
                        <p className="px-3 py-2 text-[13px] text-muted">No matching tickets.</p>
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
              <Button variant="secondary" onClick={() => setStep(1)}><ArrowLeft className="h-4 w-4" /> Back</Button>
              <Button loading={planM.isPending} disabled={!jiraKey}
                onClick={() => jiraKey ? planM.mutate() : toast.push('error', 'Pick a Jira ticket first.')}>
                See the plan <ArrowRight className="h-4 w-4" />
              </Button>
            </div>
          </CardBody>
        </Card>
      )}

      {step === 3 && (
        <Card>
          <CardHeader title="Here's what we'd do"
            subtitle={plan?.mode === 'REFACTOR'
              ? `Scanned ${plan?.filesScanned ?? 0} existing test files — we'll update, never replace.`
              : 'No tests yet — we\'ll create a fresh set.'} />
          <CardBody className="space-y-5">
            {planM.isPending || !plan ? (
              <p className="flex items-center gap-2 text-sm text-muted"><Spinner /> Reconciling the API against your tests…</p>
            ) : (
              <>
                <div className="grid grid-cols-3 gap-3">
                  <Tile label="Already covered" value={covered} />
                  <Tile label="New tests" value={gaps} tone="text-gold" />
                  <Tile label="Orphaned" value={orphans} />
                </div>

                <div>
                  <p className="mb-2 text-[13px] text-muted">
                    {gaps > 0 ? 'Uncheck anything you don\'t want. By default we generate the gaps.' : 'Everything is covered — nothing to generate.'}
                  </p>
                  <Table head={<><Th /><Th>Endpoint</Th><Th>What</Th></>}>
                    {plan.items.map((it) => <PlanRow key={(it.signature ?? it.path) + it.status} it={it} selected={selected} toggle={toggle} />)}
                  </Table>
                </div>

                <div className="flex items-center justify-between border-t border-border pt-4">
                  <Button variant="secondary" onClick={() => setStep(2)}><ArrowLeft className="h-4 w-4" /> Back</Button>
                  <Button disabled={selected.size === 0} onClick={() => setStep(4)}>
                    Continue ({selected.size}) <ArrowRight className="h-4 w-4" />
                  </Button>
                </div>
                <p className="flex items-center gap-1.5 text-[12px] text-muted">
                  <GitPullRequest className="h-3.5 w-3.5" /> Next we'll set up tokens, then write the tests to <span className="font-mono">{outputRepo || '—'}</span> on a branch — nothing is pushed until you click “Open pull request”.
                </p>
              </>
            )}
          </CardBody>
        </Card>
      )}

      {step === 4 && (
        <Card>
          <CardHeader title="How does this service authenticate?"
            subtitle="Tell us which tokens it needs. We store only the variable names — your secrets stay in your environment." />
          <CardBody className="space-y-5">
            <div className="flex gap-2">
              <PresetBtn active={authGroups.length === 0} onClick={() => setPreset(0)}>No token</PresetBtn>
              <PresetBtn active={authGroups.length === 1} onClick={() => setPreset(1)}>One token</PresetBtn>
              <PresetBtn active={authGroups.length === 2} onClick={() => setPreset(2)}>Two tokens · different APIs</PresetBtn>
            </div>

            {authGroups.length === 0 ? (
              <p className="rounded-lg bg-ink-50 p-3 text-[13px] text-muted">
                Public service — every endpoint is called without a token.
              </p>
            ) : (
              authGroups.map((g, i) => (
                <AuthGroupCard key={i} index={i} group={g} multi={authGroups.length > 1} onChange={patchGroup} />
              ))
            )}

            {setxLines.length > 0 && (
              <div className="rounded-lg border-l-4 border-l-success bg-success/5 p-3">
                <p className="mb-1.5 flex items-center gap-1.5 text-[13px] font-semibold text-success">
                  <Terminal className="h-3.5 w-3.5" /> Set these in your environment, then you're done
                </p>
                <pre className="overflow-x-auto rounded bg-ink-900/90 px-3 py-2 font-mono text-[12px] text-white">{setxLines.join('\n')}</pre>
              </div>
            )}

            <div className="flex items-center justify-between border-t border-border pt-4">
              <Button variant="secondary" onClick={() => setStep(3)}><ArrowLeft className="h-4 w-4" /> Back</Button>
              <span className="flex items-center gap-3">
                {notice}
                <Button disabled={selected.size === 0 || blocked} loading={generateM.isPending}
                  onClick={() => generateM.mutate()}>
                  <Sparkles className="h-4 w-4" /> Generate selected ({selected.size})
                </Button>
              </span>
            </div>
          </CardBody>
        </Card>
      )}

      {step === 5 && run && (
        <Card>
          <CardHeader
            title={<span className="inline-flex items-center gap-2">Review &amp; open a pull request
              <Badge className={buildTone(run.buildStatus)}>build {run.buildStatus ?? '—'}</Badge></span>}
            subtitle={`Generated tests for ${run.serviceName} — review, then open a PR when you're ready.`} />
          <CardBody className="space-y-5">
            {run.buildStatus === 'SKIPPED' && (
              <div className="rounded-lg border-l-4 border-l-warning bg-warning/5 p-3 text-[13px] text-ink-700">
                These tests weren't compiled here (build skipped) — don't assume they compile; CI will verify before merge.
              </div>
            )}

            <div>
              <p className="mb-1.5 text-[13px] font-semibold text-ink-900">Generated files</p>
              <ul className="space-y-1">
                {parseList(run.filesWritten).map((f) => (
                  <li key={f} className="flex items-center gap-2 font-mono text-[12.5px] text-muted"><FileCode className="h-3.5 w-3.5 shrink-0" /> {f}</li>
                ))}
                {parseList(run.filesWritten).length === 0 && <li className="text-[13px] text-muted">—</li>}
              </ul>
            </div>

            {parseList(run.todos).length > 0 && (
              <div className="rounded-lg border-l-4 border-l-warning bg-warning/5 p-3">
                <p className="mb-1 text-[13px] font-semibold text-ink-900">Before these run, you'll need</p>
                <ul className="list-disc space-y-0.5 pl-5 text-[13px] text-ink-700">
                  {parseList(run.todos).map((t, i) => <li key={i}>{t}</li>)}
                </ul>
              </div>
            )}

            {run.prUrl ? (
              <div className="space-y-3">
                <p className="text-sm">Pull request opened: <a href={run.prUrl} target="_blank" rel="noreferrer"
                  className="inline-flex items-center gap-1 font-medium text-gold hover:underline">{run.prUrl} <ExternalLink className="h-3.5 w-3.5" /></a></p>
                <div className="rounded-lg bg-ink-50 p-3 text-[13px] text-ink-700">
                  <p className="mb-1 font-semibold text-ink-900">Next: test it locally</p>
                  <p>Veritas generated the tests and opened the PR. Running them against the live API is done from your machine (or CI) — pull the branch and run your suite:</p>
                  <pre className="mt-2 overflow-x-auto rounded bg-ink-900/90 px-3 py-2 font-mono text-[12px] text-white">git fetch origin{'\n'}git checkout {run.branch ?? 'the PR branch'}</pre>
                </div>
              </div>
            ) : (
              <div className="flex flex-col gap-3 border-t border-border pt-4 sm:flex-row sm:items-end">
                <div className="flex-1">
                  <Field label="Open the PR against" hint={`Base branch in ${outputRepo}.`}>
                    <Input value={prBranch} onChange={(e) => setPrBranch(e.target.value)} placeholder="main" />
                  </Field>
                </div>
                <Button loading={publishM.isPending && !publishM.variables?.allowFailedBuild}
                  onClick={() => publishM.mutate({ allowFailedBuild: false })}>
                  <GitPullRequestArrow className="h-4 w-4" /> Open pull request
                </Button>
                {run.buildStatus === 'FAIL' && (
                  <Button variant="secondary" loading={publishM.isPending && publishM.variables?.allowFailedBuild}
                    title="Build failed — open the PR anyway (override)"
                    onClick={() => publishM.mutate({ allowFailedBuild: true })}>
                    Open anyway (build failed)
                  </Button>
                )}
              </div>
            )}

            <div className="flex items-center justify-between border-t border-border pt-4">
              <Button variant="secondary" onClick={() => setStep(4)}><ArrowLeft className="h-4 w-4" /> Back</Button>
              <p className="flex items-center gap-1.5 text-[12px] text-muted">
                <GitPullRequest className="h-3.5 w-3.5" /> Opening a PR pushes a branch for review — it never merges on its own.
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

/** One declared token group: mechanism + the env-var NAMES it reads + (when multi) the paths it covers. */
function AuthGroupCard({ index, group, multi, onChange }:
  { index: number; group: ServiceAuthGroup; multi: boolean; onChange: (i: number, patch: Partial<ServiceAuthGroup>) => void }) {
  const setEnv = (role: string, name: string) => onChange(index, { envVars: { ...group.envVars, [role]: name } });
  return (
    <div className="rounded-lg border border-border p-3">
      <div className="mb-2.5 flex items-center gap-2">
        <span className="inline-flex items-center gap-1 rounded-full bg-brand/10 px-2 py-0.5 text-[12px] font-medium text-brand">
          <KeyRound className="h-3 w-3" /> Token {String.fromCharCode(65 + index)}
        </span>
        {multi && <span className="text-[12px] text-muted">used by endpoints under its path prefix</span>}
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Field label="How is it generated?">
          <Select value={group.mechanism} onChange={(e) => onChange(index, { mechanism: e.target.value as Mechanism, envVars: {} })}>
            {MECHANISMS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
          </Select>
        </Field>
        {multi && (
          <Field label="Applies to paths" hint="Comma-separated prefixes, e.g. /tpps">
            <Input aria-label={`Token ${String.fromCharCode(65 + index)} paths`} value={group.pathPrefixes.join(', ')}
              onChange={(e) => onChange(index, { pathPrefixes: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} />
          </Field>
        )}
        {rolesFor(group.mechanism).map((r) => (
          <div key={r.key} className="col-span-2">
            <Field label={`Windows env var for the ${r.label}`} hint="Veritas never sees the value — only the name.">
              <Input aria-label={`Token ${String.fromCharCode(65 + index)} ${r.key} env var`}
                placeholder={`E.g. CIAM_${(group.name || 'token').toUpperCase()}_${r.key.toUpperCase()}`}
                value={group.envVars[r.key] ?? ''} onChange={(e) => setEnv(r.key, e.target.value)} />
            </Field>
          </div>
        ))}
      </div>
      {group.mechanism === 'BASIC_AUTH' && <Base64Helper />}
    </div>
  );
}

/** Browser-only base64 helper: turns client id + password into base64(id:password) locally — never sent to the server. */
function Base64Helper() {
  const [cid, setCid] = useState('');
  const [pw, setPw] = useState('');
  const [b64, setB64] = useState('');
  const encode = () => {
    if (!cid && !pw) return;
    try { setB64(btoa(`${cid}:${pw}`)); } catch { setB64(btoa(unescape(encodeURIComponent(`${cid}:${pw}`)))); }
  };
  return (
    <div className="mt-3 rounded-md bg-ink-50 p-3">
      <p className="mb-0.5 flex items-center gap-1.5 text-[12px] font-medium text-ink-900"><Lock className="h-3.5 w-3.5" /> Encode the base64 for me</p>
      <p className="mb-2 text-[11px] text-muted">Computed in your browser — client id and password are never sent to Veritas.</p>
      <div className="grid grid-cols-[1fr_1fr_auto] items-end gap-2">
        <Field label="Client id"><Input value={cid} onChange={(e) => setCid(e.target.value)} placeholder="svc-account" /></Field>
        <Field label="Password"><Input type="password" value={pw} onChange={(e) => setPw(e.target.value)} placeholder="••••••••" /></Field>
        <Button type="button" variant="secondary" onClick={encode}>Encode</Button>
      </div>
      {b64 && (
        <div className="mt-2">
          <p className="mb-0.5 text-[11px] text-muted">base64(client id:password) — use it in your setx command:</p>
          <code className="block break-all rounded border border-border bg-surface px-2 py-1.5 font-mono text-[12px]">{b64}</code>
        </div>
      )}
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
  const meta = STATUS[it.status] ?? STATUS.GAP;
  const Icon = meta.icon;
  const sig = it.signature ?? it.path ?? '';
  const selectable = it.status === 'GAP';
  return (
    <Row>
      <Td>
        {selectable ? (
          <input type="checkbox" aria-label={`Select ${sig}`} className="h-4 w-4 rounded border-border text-brand focus:ring-brand/40"
            checked={selected.has(sig)} onChange={() => toggle(sig)} />
        ) : <span className="inline-block h-4 w-4" />}
      </Td>
      <Td>
        <div className="font-mono text-[12.5px] text-ink-900">{it.method} {it.path}</div>
        {it.reason && <div className="text-[11px] text-muted">{it.reason}</div>}
      </Td>
      <Td>
        <Badge className={meta.tone}><Icon className="h-3 w-3" /> {meta.label}</Badge>
      </Td>
    </Row>
  );
}
