import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Code2, FileCode, ExternalLink, GitPullRequestArrow, Play } from 'lucide-react';
import { api, CodegenRun } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, ErrorState, Field, Input, PageHeader, CardSkeleton } from '../components/ui';
import { useToast } from '../components/Toast';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { enumLabel } from '../lib/enumLabels';

function buildTone(status?: string): string {
  const map: Record<string, string> = { PASS: TONE.ok, REPAIRED: TONE.warn, FAIL: TONE.danger, SKIPPED: TONE.muted };
  return map[status ?? ''] ?? TONE.muted;
}
function parseList(json?: string): string[] {
  if (!json) return [];
  try { const v = JSON.parse(json); return Array.isArray(v) ? v : []; } catch { return []; }
}

export function Codegen() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['codegen-runs'], queryFn: api.codegenRuns });
  const [selId, setSelId] = useState<string | null>(null);
  const [repoSlug, setRepoSlug] = useState('');
  const { blocked, notice } = useCopilotGate();
  const [gen, setGen] = useState({ service: '', serviceRepo: '', outputDir: '', templatePath: '' });

  const runs = q.data ?? [];
  const sel = runs.find((r) => r.id === selId) ?? null;

  const publish = useMutation({
    mutationFn: ({ run, allowFailedBuild }: { run: CodegenRun; allowFailedBuild: boolean }) =>
      api.publishCodegen(run.id, repoSlug, 'main', allowFailedBuild),
    onSuccess: (updated) => { qc.invalidateQueries({ queryKey: ['codegen-runs'] }); setSelId(updated.id); toast.push('success', t('codegen.prOpenedToast')); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const generate = useMutation({
    mutationFn: () => api.implementTests(gen.service, {
      serviceRepo: gen.serviceRepo, outputDir: gen.outputDir,
      templatePath: gen.templatePath.trim() || undefined,
    }),
    onSuccess: (run) => { qc.invalidateQueries({ queryKey: ['codegen-runs'] }); setSelId(run.id); toast.push('success', t('codegen.generationCompleteToast')); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  return (
    <div>
      <PageHeader title={t('codegen.pageTitle')}
        subtitle={t('codegen.pageSubtitle')} />

      <Card className="mb-6">
        <CardHeader title={t('codegen.newGenerationTitle')} subtitle={t('codegen.newGenerationSubtitle')} />
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label={t('codegen.serviceLabel')}><Input placeholder="ciam-policies" value={gen.service} onChange={(e) => setGen((g) => ({ ...g, service: e.target.value }))} /></Field>
            <Field label={t('codegen.outputDirLabel')} hint={t('codegen.outputDirHint')}><Input placeholder="/work/ciam-autotests" value={gen.outputDir} onChange={(e) => setGen((g) => ({ ...g, outputDir: e.target.value }))} /></Field>
            <Field label={t('codegen.serviceRepoLabel')} hint={t('codegen.serviceRepoHint')}><Input placeholder="/work/ciam-policies" value={gen.serviceRepo} onChange={(e) => setGen((g) => ({ ...g, serviceRepo: e.target.value }))} /></Field>
            <Field label={t('codegen.templatePathLabel')} hint={t('codegen.templatePathHint')}><Input placeholder="(bundled default)" value={gen.templatePath} onChange={(e) => setGen((g) => ({ ...g, templatePath: e.target.value }))} /></Field>
          </div>
          <div className="flex items-center justify-end gap-3">
            {notice}
            <Button loading={generate.isPending} disabled={blocked}
              onClick={() => (gen.service && gen.serviceRepo.trim() && gen.outputDir.trim())
                ? generate.mutate()
                : toast.push('error', t('codegen.requiredFieldsToast'))}>
              <Play className="h-4 w-4" /> {t('codegen.generateButton')}
            </Button>
          </div>
        </CardBody>
      </Card>

      {q.isLoading ? (
        <CardSkeleton label={t('codegen.loading')} />
      ) : q.isError ? (
        <ErrorState detail={(q.error as Error).message} />
      ) : runs.length === 0 ? (
        <EmptyState icon={Code2} title={t('codegen.noRunsTitle')}
          body={t('codegen.noRunsBody')} />
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[320px_1fr]">
          {/* Run list */}
          <Card className="h-fit">
            <CardHeader title={t('codegen.runsTitle')} />
            <CardBody className="p-2">
              {runs.map((r) => (
                <button key={r.id} onClick={() => setSelId(r.id)}
                  className={cn('flex w-full items-center justify-between gap-2 rounded-lg px-3 py-2 text-left text-sm transition',
                    sel?.id === r.id ? 'bg-ink-50 ring-1 ring-border' : 'hover:bg-ink-50/60')}>
                  <span className="truncate font-medium text-ink-900">{r.serviceName}</span>
                  <Badge className={buildTone(r.buildStatus)}>{enumLabel(t, 'buildStatus', r.buildStatus)}</Badge>
                </button>
              ))}
            </CardBody>
          </Card>

          {/* Detail */}
          {!sel ? (
            <Card><CardBody><EmptyState icon={FileCode} title={t('codegen.selectRunTitle')} body={t('codegen.selectRunBody')} /></CardBody></Card>
          ) : (
            <Card>
              <CardHeader title={<span className="inline-flex items-center gap-2">{sel.serviceName}<Badge className={buildTone(sel.buildStatus)}>{t('codegen.buildBadge', { status: enumLabel(t, 'buildStatus', sel.buildStatus) })}</Badge></span>}
                subtitle={sel.templateSource ? t('codegen.templateSubtitle', { source: sel.templateSource }) : undefined} />
              <CardBody className="space-y-5">
                <div>
                  <p className="mb-1.5 text-sm font-semibold text-ink-900">{t('codegen.generatedFiles')}</p>
                  <ul className="space-y-1">
                    {parseList(sel.filesWritten).map((f) => (
                      <li key={f} className="flex items-center gap-2 font-mono text-xs text-muted"><FileCode className="h-3.5 w-3.5 shrink-0" /> {f}</li>
                    ))}
                    {parseList(sel.filesWritten).length === 0 && <li className="text-sm text-muted">—</li>}
                  </ul>
                </div>

                {parseList(sel.todos).length > 0 && (
                  <div className="rounded-lg border-l-4 border-l-warning bg-warning/5 p-3">
                    <p className="mb-1 text-sm font-semibold text-ink-900">{t('codegen.todosTitle')}</p>
                    <ul className="list-disc space-y-0.5 pl-5 text-sm text-ink-700">
                      {parseList(sel.todos).map((t, i) => <li key={i}>{t}</li>)}
                    </ul>
                  </div>
                )}

                {sel.prUrl ? (
                  <p className="text-sm">{t('codegen.prOpenedLabel')} <a href={sel.prUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-medium text-gold hover:underline">{sel.prUrl} <ExternalLink className="h-3.5 w-3.5" /></a></p>
                ) : (
                  <div className="flex flex-col gap-3 border-t border-border pt-4 sm:flex-row sm:items-end">
                    <div className="flex-1"><Field label={t('codegen.repoSlugLabel')} hint={t('codegen.repoSlugHint')}>
                      <Input placeholder="ciam-autotests" value={repoSlug} onChange={(e) => setRepoSlug(e.target.value)} /></Field></div>
                    <Button loading={publish.isPending && !publish.variables?.allowFailedBuild}
                      onClick={() => repoSlug ? publish.mutate({ run: sel, allowFailedBuild: false }) : toast.push('error', t('codegen.enterSlugToast'))}>
                      <GitPullRequestArrow className="h-4 w-4" /> {t('codegen.approveButton')}
                    </Button>
                    {sel.buildStatus === 'FAIL' && (
                      <Button variant="secondary" loading={publish.isPending && publish.variables?.allowFailedBuild}
                        title={t('codegen.overrideTitle')}
                        onClick={() => repoSlug ? publish.mutate({ run: sel, allowFailedBuild: true }) : toast.push('error', t('codegen.enterSlugToast'))}>
                        {t('codegen.overrideButton')}
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
