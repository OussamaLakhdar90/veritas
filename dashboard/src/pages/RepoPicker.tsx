import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search, ShieldCheck, GitBranch, FileCode } from 'lucide-react';
import { api, Repo } from '../api';
import { Button, Card, CardBody, EmptyState, Field, Input, PageHeader, Spinner } from '../components/ui';
import { Modal } from '../components/Modal';
import { useToast } from '../components/Toast';

export function RepoPicker() {
  const [appId, setAppId] = useState('');
  const [repos, setRepos] = useState<Repo[]>([]);
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [target, setTarget] = useState<Repo | null>(null);

  const search = () => {
    setErr('');
    setLoading(true);
    api.repos(appId)
      .then(setRepos)
      .catch((e) => setErr(String(e)))
      .finally(() => { setLoading(false); setSearched(true); });
  };

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
            <Button onClick={search} disabled={!appId || loading} className="sm:mb-0.5">
              {loading ? <Spinner className="text-white" /> : <Search className="h-4 w-4" />}
              {loading ? 'Searching…' : 'Find repos'}
            </Button>
          </div>
          {err && <p className="mt-3 text-[13px] text-danger">{err}</p>}
        </CardBody>
      </Card>

      {repos.length > 0 ? (
        <Card>
          <CardBody className="p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-[12px] uppercase tracking-wide text-muted">
                    <th className="px-5 py-3 font-medium">Repo</th>
                    <th className="px-5 py-3 font-medium">Project</th>
                    <th className="px-5 py-3 font-medium">Default branch</th>
                    <th className="px-5 py-3 font-medium">Description</th>
                    <th className="px-5 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {repos.map((r) => (
                    <tr key={r.slug} className="border-b border-border/60 last:border-0 hover:bg-ink-50/60">
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
                  ))}
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
