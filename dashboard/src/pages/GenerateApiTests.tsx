import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Search, ArrowRight, ArrowLeft, Plus, Check, AlertTriangle, Sparkles, GitPullRequest, FileCode, GitPullRequestArrow, ExternalLink } from 'lucide-react';
import { api, Repo, TestGenPlan, TestGenPlanItem, CodegenRun } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, Field, Input, PageHeader, Select, Spinner, Table, Td, Th, Row } from '../components/ui';
import { useToast } from '../components/Toast';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';

const STEPS = ['Service', 'Destination', 'Plan', 'Review'];

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
  const [plan, setPlan] = useState<TestGenPlan | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [run, setRun] = useState<CodegenRun | null>(null);
  const [prBranch, setPrBranch] = useState('main');

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
    }),
    onSuccess: (r) => { setRun(r); setStep(4); toast.push('success', 'Tests generated — review them, then open a PR.'); },
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
            <div className="flex justify-between">
              <Button variant="secondary" onClick={() => setStep(1)}><ArrowLeft className="h-4 w-4" /> Back</Button>
              <Button loading={planM.isPending} onClick={() => planM.mutate()}>See the plan <ArrowRight className="h-4 w-4" /></Button>
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
                  <span className="flex items-center gap-3">
                    {notice}
                    <Button disabled={selected.size === 0 || blocked} loading={generateM.isPending}
                      onClick={() => generateM.mutate()}>
                      <Sparkles className="h-4 w-4" /> Generate selected ({selected.size})
                    </Button>
                  </span>
                </div>
                <p className="flex items-center gap-1.5 text-[12px] text-muted">
                  <GitPullRequest className="h-3.5 w-3.5" /> Tests are written to <span className="font-mono">{outputRepo || '—'}</span> on a branch — nothing is pushed until you click “Open pull request”.
                </p>
              </>
            )}
          </CardBody>
        </Card>
      )}

      {step === 4 && run && (
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
              <p className="text-sm">Pull request opened: <a href={run.prUrl} target="_blank" rel="noreferrer"
                className="inline-flex items-center gap-1 font-medium text-gold hover:underline">{run.prUrl} <ExternalLink className="h-3.5 w-3.5" /></a></p>
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
              <Button variant="secondary" onClick={() => setStep(3)}><ArrowLeft className="h-4 w-4" /> Back to plan</Button>
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
