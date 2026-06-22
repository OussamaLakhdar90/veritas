import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search, ShieldCheck, GitBranch, FileCode, Star, Clock } from 'lucide-react';
import { api, Repo } from '../api';
import { Button, Card, CardBody, EmptyState, Field, Input, PageHeader, Spinner } from '../components/ui';
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

/* ── Guided "Validate" form (replaces window.prompt) ─────────────────────────── */
function ValidateModal({ repo, appId, onClose }: { repo: Repo; appId: string; onClose: () => void }) {
  const toast = useToast();
  const navigate = useNavigate();
  const [branch, setBranch] = useState(repo.defaultBranch || 'main');
  const [specPath, setSpecPath] = useState('openapi.yaml');
  const [busy, setBusy] = useState(false);

  const run = async () => {
    if (!specPath.trim()) { toast.push('error', 'Enter the spec path.'); return; }
    setBusy(true);
    try {
      const res = await api.triggerScan({
        appId, repoSlug: repo.slug, branch: branch.trim() || undefined, specPaths: [specPath.trim()],
      });
      toast.push('success', `Scan complete — ${res.totalFindings} finding${res.totalFindings === 1 ? '' : 's'}.`);
      onClose();
      navigate(`/findings/${res.scanId}`);
    } catch (e) {
      toast.push('error', `Validation failed: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open title={`Validate ${repo.slug}`} onClose={busy ? () => { /* lock while running */ } : onClose}
      footer={<>
        <Button variant="secondary" onClick={onClose} disabled={busy}>Cancel</Button>
        <Button onClick={run} loading={busy}><ShieldCheck className="h-4 w-4" /> Run validation</Button>
      </>}>
      <p className="mb-4 text-[13px] text-muted">
        Veritas clones <span className="font-medium text-ink-900">{repo.slug}</span>, extracts the API from the code,
        and compares it to the spec below. No changes are written to the repo.
      </p>
      <div className="space-y-4">
        <Field label="Branch" hint="Defaults to the repository's default branch.">
          <div className="relative">
            <GitBranch className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input className="pl-8" value={branch} onChange={(e) => setBranch(e.target.value)} />
          </div>
        </Field>
        <Field label="Spec path" hint="Path to the OpenAPI/Swagger file, relative to the repo root.">
          <div className="relative">
            <FileCode className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input className="pl-8" placeholder="openapi.yaml" value={specPath}
              onChange={(e) => setSpecPath(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && !busy && run()} />
          </div>
        </Field>
      </div>
    </Modal>
  );
}
