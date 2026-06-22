import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Search, ShieldCheck, GitBranch, FileCode, Star, Clock,
  Code2, GitCompare, Sparkles, FileText, Check, AlertTriangle, Loader2 } from 'lucide-react';
import { api, Repo } from '../api';
import { Button, Card, CardBody, EmptyState, Field, Input, PageHeader, Select, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { useToast } from '../components/Toast';
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
      .catch((e) => setErr(String(e)))
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
      <PageHeader title="Validate a contract"
        subtitle="Find a repository by its app-id, then check its OpenAPI spec against the code." />

      <Card className="mb-6">
        <CardBody>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <div className="flex-1">
              <Field label="App-id" hint="Bitbucket project key (Server/DC) or workspace (Cloud), e.g. APP7571.">
                <Input placeholder="APP7571" value={appId} autoFocus
                  onChange={(e) => setAppId(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && appId && search()} />
              </Field>
            </div>
            <Button onClick={() => search()} disabled={!appId || loading} className="sm:mb-0.5">
              {loading ? <Spinner className="text-white" /> : <Search className="h-4 w-4" />}
              {loading ? 'Searching…' : 'Find repos'}
            </Button>
          </div>
          {recents.length > 0 && (
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1 text-[12px] text-muted"><Clock className="h-3.5 w-3.5" /> Recent:</span>
              {recents.map((id) => (
                <button key={id} onClick={() => search(id)}
                  className="rounded-full px-2.5 py-1 text-[12px] font-medium text-ink-700 ring-1 ring-border hover:bg-ink-50">
                  {id}
                </button>
              ))}
            </div>
          )}
          {err && <p className="mt-3 text-[13px] text-danger">{err}</p>}
        </CardBody>
      </Card>

      {repos.length > 0 ? (
        <Card>
          <CardBody className="p-0">
            <div className="border-b border-border p-3">
              <div className="relative max-w-xs">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                <Input className="pl-8" placeholder={`Filter ${repos.length} repos…`} value={filter}
                  onChange={(e) => setFilter(e.target.value)} aria-label="Filter repositories" />
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-[12px] uppercase tracking-wide text-muted">
                    <th className="w-10 px-5 py-3" />
                    <th className="px-5 py-3 font-medium">Repo</th>
                    <th className="px-5 py-3 font-medium">Project</th>
                    <th className="px-5 py-3 font-medium">Default branch</th>
                    <th className="px-5 py-3 font-medium">Description</th>
                    <th className="px-5 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {shown.map((r) => {
                    const pinned = favs.includes(favKey(r));
                    return (
                      <tr key={r.slug} className="border-b border-border/60 last:border-0 hover:bg-ink-50/60">
                        <td className="px-5 py-3">
                          <button onClick={() => toggleFav(r)} aria-label={pinned ? 'Unpin' : 'Pin'} title={pinned ? 'Unpin' : 'Pin to top'}>
                            <Star className={cn('h-4 w-4', pinned ? 'fill-gold text-gold' : 'text-muted/50 hover:text-muted')} />
                          </button>
                        </td>
                        <td className="px-5 py-3 font-mono text-[13px] font-medium text-ink-900">{r.slug}</td>
                        <td className="px-5 py-3 text-muted">{r.projectKey}</td>
                        <td className="px-5 py-3 text-muted">{r.defaultBranch}</td>
                        <td className="px-5 py-3 text-muted">{r.description}</td>
                        <td className="px-5 py-3 text-right">
                          <Button size="sm" variant="secondary" onClick={() => setTarget(r)}>
                            <ShieldCheck className="h-4 w-4" /> Validate
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                  {shown.length === 0 && (
                    <tr><td colSpan={6} className="px-5 py-6 text-center text-[13px] text-muted">No repos match “{filter}”.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </CardBody>
        </Card>
      ) : (!err && (
        <EmptyState icon={Search}
          title={searched ? 'No accessible repos found' : 'Search by app-id'}
          body={searched
            ? 'No repositories matched that app-id, or your token cannot access them. Check the app-id and your Bitbucket token in Settings.'
            : 'Enter an app-id above to list the repositories your Bitbucket token can access.'} />
      ))}

      {target && (
        <ValidateModal repo={target} appId={appId} onClose={() => setTarget(null)} />
      )}
    </div>
  );
}

/* ── The live progress steps, in order. RECONCILING is shown but may be skipped
      (no findings, or AI disabled) — the order-based status handles that gracefully. ── */
const SCAN_STEPS: { key: string; label: string; detail: string; icon: typeof GitBranch }[] = [
  { key: 'CLONING', label: 'Cloning the repository', detail: 'Fetching the branch from Bitbucket', icon: GitBranch },
  { key: 'RESOLVING_SPEC', label: 'Locating the OpenAPI spec', detail: 'Reading the contract you selected', icon: FileCode },
  { key: 'EXTRACTING', label: 'Reading the API from the code', detail: 'Static analysis of the controllers (no app run)', icon: Code2 },
  { key: 'DIFFING', label: 'Comparing code against the spec', detail: 'Endpoints, params, schemas, status codes', icon: GitCompare },
  { key: 'RECONCILING', label: 'AI review & corrected spec', detail: 'Copilot explains findings and drafts a fix', icon: Sparkles },
  { key: 'REPORTING', label: 'Building the report', detail: 'Scoring fidelity and rendering the results', icon: FileText },
];
const STAGE_ORDER: Record<string, number> = {
  QUEUED: 0, CLONING: 1, RESOLVING_SPEC: 2, EXTRACTING: 3, DIFFING: 4, RECONCILING: 5, REPORTING: 6, DONE: 7, FAILED: 7,
};

/* ── Guided "Validate" form → live progress stepper (no window.prompt, full visibility) ── */
function ValidateModal({ repo, appId, onClose }: { repo: Repo; appId: string; onClose: () => void }) {
  const toast = useToast();
  const navigate = useNavigate();
  const [branch, setBranch] = useState(repo.defaultBranch || '');
  const [specKind, setSpecKind] = useState<'REPO_PATH' | 'LIVE_DOCS' | 'CONFLUENCE'>('REPO_PATH');
  const [specRef, setSpecRef] = useState('openapi.yaml');
  const [starting, setStarting] = useState(false);
  const [scanId, setScanId] = useState<string | null>(null);
  const [navigated, setNavigated] = useState(false);

  const SPEC = {
    REPO_PATH: { label: 'File in the repo', field: 'Spec path', placeholder: 'openapi.yaml',
      hint: 'Path to the OpenAPI/Swagger file, relative to the repo root.' },
    LIVE_DOCS: { label: 'Live /v3/api-docs URL', field: 'API docs URL', placeholder: 'https://service.bnc.ca/v3/api-docs',
      hint: 'A running endpoint that serves the OpenAPI JSON.' },
    CONFLUENCE: { label: 'Confluence page', field: 'Confluence page (URL or ID)',
      placeholder: 'https://wiki.bnc.ca/spaces/IAMAS/pages/1725186990/… or 1725186990',
      hint: 'Paste the page URL or just its numeric ID — the page must hold the OpenAPI/Swagger spec.' },
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
      toast.push('success', `Scan complete — ${scan.totalFindings} finding${scan.totalFindings === 1 ? '' : 's'}.`);
      onClose();
      navigate(`/findings/${scan.id}`);
    }
  }, [done, scan, navigated]); // eslint-disable-line react-hooks/exhaustive-deps

  const start = async () => {
    if (!specRef.trim()) { toast.push('error', `Enter the ${SPEC.field.toLowerCase()}.`); return; }
    setStarting(true);
    try {
      const common = { serviceName: repo.slug, appId, repoSlug: repo.slug, branch: branch.trim() || undefined };
      const res = await api.triggerScan(specKind === 'REPO_PATH'
        ? { ...common, specPaths: [specRef.trim()] }
        : { ...common, specSources: [{ kind: specKind, ref: specRef.trim() }] });
      setScanId(res.scanId);
    } catch (e) {
      toast.push('error', `Could not start the scan: ${(e as Error).message}`);
    } finally {
      setStarting(false);
    }
  };

  const retry = () => { setScanId(null); setNavigated(false); };

  const title = scanId ? `Validating ${repo.slug}` : `Validate ${repo.slug}`;

  // Footer changes with the phase: form → starting; running → background; failed → retry.
  const footer = !scanId ? (
    <>
      <Button variant="secondary" onClick={onClose}>Cancel</Button>
      <Button onClick={start} loading={starting}><ShieldCheck className="h-4 w-4" /> Run validation</Button>
    </>
  ) : failed ? (
    <>
      <Button variant="secondary" onClick={onClose}>Close</Button>
      <Button onClick={retry}><ShieldCheck className="h-4 w-4" /> Try again</Button>
    </>
  ) : (
    <Button variant="secondary" onClick={onClose}>Run in background</Button>
  );

  return (
    <Modal open title={title} onClose={onClose} footer={footer}>
      {!scanId ? (
        <>
          <p className="mb-4 text-[13px] text-muted">
            Veritas clones <span className="font-medium text-ink-900">{repo.slug}</span>, extracts the API from the code,
            and compares it to the spec below. No changes are written to the repo.
          </p>
          <div className="space-y-4">
            <Field label="Branch"
              hint={branchesQ.isLoading ? 'Loading branches…' : branches.length > 0 ? 'Default branch listed first.' : 'Type the branch (branch list unavailable).'}>
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
            <Field label="Spec source" hint="Where the OpenAPI/Swagger spec lives.">
              <Select value={specKind} onChange={(e) => {
                const k = e.target.value as 'REPO_PATH' | 'LIVE_DOCS' | 'CONFLUENCE';
                setSpecKind(k);
                setSpecRef(k === 'REPO_PATH' ? 'openapi.yaml' : '');   // reset the ref to a sensible default per kind
              }}>
                <option value="REPO_PATH">File in the repo</option>
                <option value="LIVE_DOCS">Live /v3/api-docs URL</option>
                <option value="CONFLUENCE">Confluence page</option>
              </Select>
            </Field>
            <Field label={SPEC.field} hint={SPEC.hint}>
              <div className="relative">
                <FileCode className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                <Input className="pl-8" placeholder={SPEC.placeholder} value={specRef}
                  onChange={(e) => setSpecRef(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && !starting && start()} />
              </div>
            </Field>
          </div>
        </>
      ) : (
        <ScanProgress stage={stage} failed={failed} errorMessage={scan?.errorMessage} onRetry={retry} />
      )}
    </Modal>
  );
}

/* ── Friendly step-by-step tracker the user can follow while a scan runs ── */
function ScanProgress({ stage, failed, errorMessage, onRetry }:
  { stage: string; failed: boolean; errorMessage?: string; onRetry: () => void }) {
  const current = STAGE_ORDER[stage] ?? 0;
  // Which step the failure happened on (the last step we'd entered), for a clear "failed at …" line.
  const failedStepIdx = failed ? Math.max(0, Math.min(SCAN_STEPS.length - 1, current - 1)) : -1;

  return (
    <div>
      <p className="mb-4 text-[13px] text-muted">
        {failed
          ? 'The scan stopped before it finished. Nothing was written to the repo.'
          : 'Veritas is working through the contract check. You can keep this open or run it in the background.'}
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
            <li key={step.key} className="flex items-start gap-3 rounded-lg px-2 py-2">
              <span className={cn('mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full ring-1',
                status === 'done' && 'bg-success/10 text-success ring-success/30',
                status === 'active' && 'bg-brand-50 text-brand-600 ring-brand-600/30',
                status === 'error' && 'bg-danger/10 text-danger ring-danger/30',
                status === 'pending' && 'bg-ink-50 text-muted/60 ring-border')}>
                {status === 'done' ? <Check className="h-4 w-4" />
                  : status === 'active' ? <Loader2 className="h-4 w-4 animate-spin" />
                  : status === 'error' ? <AlertTriangle className="h-4 w-4" />
                  : <Icon className="h-4 w-4" />}
              </span>
              <div className="min-w-0">
                <p className={cn('text-[13px] font-medium',
                  status === 'pending' ? 'text-muted' : 'text-ink-900')}>
                  {step.label}
                  {status === 'active' && <span className="ml-2 text-[12px] font-normal text-brand-600">working…</span>}
                </p>
                <p className="text-[12px] text-muted">{isFailedHere && errorMessage ? errorMessage : step.detail}</p>
              </div>
            </li>
          );
        })}
      </ol>
      {failed && (
        <div className="mt-4 rounded-lg border border-danger/30 bg-danger/5 p-3">
          <p className="flex items-center gap-1.5 text-[13px] font-semibold text-danger">
            <AlertTriangle className="h-4 w-4" /> Validation failed
          </p>
          {errorMessage && <p className="mt-1 break-words text-[12px] text-ink-700">{errorMessage}</p>}
          <button onClick={onRetry} className="mt-2 text-[12px] font-medium text-brand-600 hover:underline">
            Adjust the inputs and try again
          </button>
        </div>
      )}
    </div>
  );
}
