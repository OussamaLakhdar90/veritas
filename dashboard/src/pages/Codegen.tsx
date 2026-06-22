import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Code2, FileCode, ExternalLink, GitPullRequestArrow } from 'lucide-react';
import { api, CodegenRun } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Field, Input, PageHeader, Spinner } from '../components/ui';
import { useToast } from '../components/Toast';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';

function buildTone(status?: string): string {
  const map: Record<string, string> = { PASS: TONE.ok, REPAIRED: TONE.warn, FAIL: TONE.danger, SKIPPED: TONE.muted };
  return map[status ?? ''] ?? TONE.muted;
}
function parseList(json?: string): string[] {
  if (!json) return [];
  try { const v = JSON.parse(json); return Array.isArray(v) ? v : []; } catch { return []; }
}

export function Codegen() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['codegen-runs'], queryFn: api.codegenRuns });
  const [selId, setSelId] = useState<string | null>(null);
  const [repoSlug, setRepoSlug] = useState('');

  const runs = q.data ?? [];
  const sel = runs.find((r) => r.id === selId) ?? null;

  const publish = useMutation({
    mutationFn: ({ run, allowFailedBuild }: { run: CodegenRun; allowFailedBuild: boolean }) =>
      api.publishCodegen(run.id, repoSlug, 'main', allowFailedBuild),
    onSuccess: (updated) => { qc.invalidateQueries({ queryKey: ['codegen-runs'] }); setSelId(updated.id); toast.push('success', 'PR opened.'); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  return (
    <div>
      <PageHeader title="Generate tests"
        subtitle="Template-driven test generation — inspect the files, build status and TODOs, then approve & open a PR." />

      {q.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> Loading…</CardBody></Card>
      ) : runs.length === 0 ? (
        <EmptyState icon={Code2} title="No generation runs yet"
          body="Trigger implement-tests for a service (via its workspace or the CLI) to review generated tests here." />
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[320px_1fr]">
          {/* Run list */}
          <Card className="h-fit">
            <CardHeader title="Runs" />
            <CardBody className="p-2">
              {runs.map((r) => (
                <button key={r.id} onClick={() => setSelId(r.id)}
                  className={cn('flex w-full items-center justify-between gap-2 rounded-lg px-3 py-2 text-left text-sm transition',
                    sel?.id === r.id ? 'bg-ink-50 ring-1 ring-border' : 'hover:bg-ink-50/60')}>
                  <span className="truncate font-medium text-ink-900">{r.serviceName}</span>
                  <Badge className={buildTone(r.buildStatus)}>{r.buildStatus ?? '—'}</Badge>
                </button>
              ))}
            </CardBody>
          </Card>

          {/* Detail */}
          {!sel ? (
            <Card><CardBody><EmptyState icon={FileCode} title="Select a run" body="Pick a generation run to see its files and open a PR." /></CardBody></Card>
          ) : (
            <Card>
              <CardHeader title={<span className="inline-flex items-center gap-2">{sel.serviceName}<Badge className={buildTone(sel.buildStatus)}>build {sel.buildStatus ?? '—'}</Badge></span>}
                subtitle={sel.templateSource ? `Template: ${sel.templateSource}` : undefined} />
              <CardBody className="space-y-5">
                <div>
                  <p className="mb-1.5 text-[13px] font-semibold text-ink-900">Generated files</p>
                  <ul className="space-y-1">
                    {parseList(sel.filesWritten).map((f) => (
                      <li key={f} className="flex items-center gap-2 font-mono text-[12.5px] text-muted"><FileCode className="h-3.5 w-3.5 shrink-0" /> {f}</li>
                    ))}
                    {parseList(sel.filesWritten).length === 0 && <li className="text-[13px] text-muted">—</li>}
                  </ul>
                </div>

                {parseList(sel.todos).length > 0 && (
                  <div className="rounded-lg border-l-4 border-l-warning bg-warning/5 p-3">
                    <p className="mb-1 text-[13px] font-semibold text-ink-900">TODOs (data / IDs that must pre-exist)</p>
                    <ul className="list-disc space-y-0.5 pl-5 text-[13px] text-ink-700">
                      {parseList(sel.todos).map((t, i) => <li key={i}>{t}</li>)}
                    </ul>
                  </div>
                )}

                {sel.prUrl ? (
                  <p className="text-sm">PR opened: <a href={sel.prUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-medium text-gold hover:underline">{sel.prUrl} <ExternalLink className="h-3.5 w-3.5" /></a></p>
                ) : (
                  <div className="flex flex-col gap-3 border-t border-border pt-4 sm:flex-row sm:items-end">
                    <div className="flex-1"><Field label="Output repo slug" hint="The repo where the PR will be opened.">
                      <Input placeholder="ciam-autotests" value={repoSlug} onChange={(e) => setRepoSlug(e.target.value)} /></Field></div>
                    <Button loading={publish.isPending && !publish.variables?.allowFailedBuild}
                      onClick={() => repoSlug ? publish.mutate({ run: sel, allowFailedBuild: false }) : toast.push('error', 'Enter the output repo slug.')}>
                      <GitPullRequestArrow className="h-4 w-4" /> Approve & open PR
                    </Button>
                    {sel.buildStatus === 'FAIL' && (
                      <Button variant="secondary" loading={publish.isPending && publish.variables?.allowFailedBuild}
                        title="Build failed — override the no-PR-on-FAIL guard"
                        onClick={() => repoSlug ? publish.mutate({ run: sel, allowFailedBuild: true }) : toast.push('error', 'Enter the output repo slug.')}>
                        Override (build failed)
                      </Button>
                    )}
                  </div>
                )}
              </CardBody>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}
