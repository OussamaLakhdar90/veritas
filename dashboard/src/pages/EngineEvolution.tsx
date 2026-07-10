import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Brain, GitPullRequestArrow, RefreshCw, ExternalLink, Check } from 'lucide-react';
import { api, ClassificationTrain } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, ErrorState, Input, PageHeader, TableSkeleton } from '../components/ui';
import { useToast } from '../components/Toast';
import { severityBadge, TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { enumLabel } from '../lib/enumLabels';

const SEV_CHOICES = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO'];
const statusTone = (s?: string) =>
  s === 'MERGED' ? TONE.ok : s === 'PR_OPEN' ? TONE.info : s === 'DISMISSED' ? TONE.danger : TONE.warn;

/**
 * Engine Evolution — the classification-learning loop. Each proposal is a field-learned severity for a
 * not-yet-classified finding type: the AI suggests via the engine's own rubric, a maintainer accepts or overrides
 * it with a reason, and a deterministic, human-reviewed PR promotes it into the engine. The open count is the
 * engine's learning debt the loop drives toward zero.
 */
export function EngineEvolution() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['classification-proposals'], queryFn: () => api.classificationProposals() });
  const debt = useQuery({ queryKey: ['classification-debt'], queryFn: () => api.classificationDebt() });

  const refresh = useMutation({
    mutationFn: () => api.refreshProposals(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['classification-proposals'] });
      qc.invalidateQueries({ queryKey: ['classification-debt'] });
      toast.push('success', t('evolve.refreshed'));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const rows = q.data ?? [];

  return (
    <div>
      <PageHeader title={t('evolve.title')} subtitle={t('evolve.subtitle')}
        actions={<Button size="sm" variant="secondary" loading={refresh.isPending} onClick={() => refresh.mutate()}>
          <RefreshCw className="h-4 w-4" /> {t('evolve.refresh')}</Button>} />

      {/* Learning debt: the engine's open classification + precision debt, driven toward zero. */}
      <Card className="mb-4"><CardBody className="flex items-center gap-8">
        <Brain className="h-5 w-5 text-brand" />
        <div>
          <div className="text-stat font-semibold tabular-nums text-ink-900">{debt.data?.unspecified ?? 0}</div>
          <div className="text-sm text-muted">{t('evolve.debtUnspecified')}</div>
        </div>
        <div>
          <div className="text-stat font-semibold tabular-nums text-ink-900">{debt.data?.disputed ?? 0}</div>
          <div className="text-sm text-muted">{t('evolve.debtDisputed')}</div>
        </div>
      </CardBody></Card>

      {q.isLoading ? (
        <Card><CardBody className="p-0"><TableSkeleton label={t('evolve.loading')} /></CardBody></Card>
      ) : q.isError ? (
        <ErrorState message={t('evolve.loadError')} detail={(q.error as Error).message} />
      ) : rows.length === 0 ? (
        <EmptyState icon={Brain} title={t('evolve.emptyTitle')} body={t('evolve.emptyBody')} />
      ) : (
        <div className="flex flex-col gap-3">
          {rows.map((r) => <ProposalCard key={`${r.id}:${r.status}:${r.aiSuggestedSeverity}:${r.finalSeverity}`} train={r} />)}
        </div>
      )}
    </div>
  );
}

function ProposalCard({ train }: { train: ClassificationTrain }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const [severity, setSeverity] = useState((train.finalSeverity || train.aiSuggestedSeverity || 'INFO').toUpperCase());
  const [comment, setComment] = useState(train.maintainerComment || '');
  const unsaved = severity !== (train.finalSeverity || '').toUpperCase();
  const editable = train.status !== 'PR_OPEN' && train.status !== 'MERGED' && train.status !== 'DISMISSED';
  const invalidate = () => qc.invalidateQueries({ queryKey: ['classification-proposals'] });

  const challenge = useMutation({
    mutationFn: () => api.challengeProposal(train.id, severity, comment),
    onSuccess: () => { invalidate(); toast.push('success', t('evolve.saved')); },
    onError: (e: Error) => toast.push('error', e.message),
  });
  const openPr = useMutation({
    mutationFn: () => api.openClassificationPr(train.id),
    onSuccess: () => { invalidate(); toast.push('success', t('evolve.prOpened')); },
    onError: (e: Error) => toast.push('error', e.message),
  });
  const markMerged = useMutation({
    mutationFn: () => api.markClassificationMerged(train.id),
    onSuccess: () => { invalidate(); toast.push('success', t('evolve.merged')); },
    onError: (e: Error) => toast.push('error', e.message),
  });
  const dismiss = useMutation({
    mutationFn: () => api.dismissProposal(train.id),
    onSuccess: () => { invalidate(); toast.push('success', t('evolve.dismissed')); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  return (
    <Card>
      <CardBody className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div className="flex items-center gap-2">
            <span className="font-mono text-sm font-semibold text-ink-900">{train.findingType}</span>
            <Badge className={statusTone(train.status)}>{t(`evolve.status_${train.status ?? 'PROPOSED'}`)}</Badge>
          </div>
          <div className="text-xs text-muted">{t('evolve.evidence', { votes: train.voteCount ?? 0, services: train.distinctServices ?? 0 })}</div>
        </div>

        {/* AI suggestion + rationale */}
        <div className="rounded-lg bg-ink-50 p-3">
          <div className="flex items-center gap-2 text-sm flex-wrap">
            <span className="text-muted">{t('evolve.aiSuggests')}</span>
            <span className={cn('inline-block rounded-md px-1.5 py-0.5 text-2xs font-semibold uppercase tracking-wide', severityBadge(train.aiSuggestedSeverity))}>
              {enumLabel(t, 'severity', train.aiSuggestedSeverity)}</span>
            <span className="text-2xs text-muted">{train.aiSuggested ? t('evolve.viaRubric') : t('evolve.viaConsensus')}</span>
          </div>
          {train.aiRationale && <p className="mt-1.5 text-sm text-ink-700">{train.aiRationale}</p>}
          {train.voteBreakdown && <p className="mt-1 text-2xs text-muted">{t('evolve.breakdown', { breakdown: train.voteBreakdown })}</p>}
        </div>

        {/* Maintainer decision */}
        {editable ? (
          <div className="flex flex-col gap-2">
            <div className="flex items-center gap-2 flex-wrap">
              <label className="text-sm text-muted" htmlFor={`sev-${train.id}`}>{t('evolve.finalSeverity')}</label>
              <select id={`sev-${train.id}`} value={severity} onChange={(e) => setSeverity(e.target.value)}
                className={cn('rounded-md px-1.5 py-0.5 text-2xs font-semibold uppercase tracking-wide outline-none', severityBadge(severity))}>
                {SEV_CHOICES.map((s) => <option key={s} value={s}>{enumLabel(t, 'severity', s)}</option>)}
              </select>
              {unsaved && <span className="text-2xs text-warning">{t('evolve.overrideHint')}</span>}
            </div>
            {unsaved && (
              <div className="flex flex-col gap-1">
                <label className="text-sm text-muted" htmlFor={`c-${train.id}`}>{t('evolve.comment')}</label>
                <Input id={`c-${train.id}`} value={comment} onChange={(e) => setComment(e.target.value)}
                  placeholder={t('evolve.commentPlaceholder')} />
              </div>
            )}
            <div className="flex items-center gap-2 flex-wrap">
              {unsaved && (
                <Button size="sm" variant="secondary" loading={challenge.isPending} disabled={!comment.trim()}
                  onClick={() => challenge.mutate()}>{t('evolve.saveOverride')}</Button>
              )}
              <Button size="sm" loading={openPr.isPending} disabled={unsaved}
                title={unsaved ? t('evolve.saveFirst') : undefined} onClick={() => openPr.mutate()}>
                <GitPullRequestArrow className="h-4 w-4" /> {t('evolve.openPr')}</Button>
              <Button size="sm" variant="secondary" loading={dismiss.isPending} onClick={() => dismiss.mutate()}>
                {t('evolve.dismiss')}</Button>
            </div>
          </div>
        ) : (
          <div className="flex items-center gap-3 flex-wrap">
            {train.prUrl && <a href={train.prUrl} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 text-sm text-brand hover:underline">
              <ExternalLink className="h-4 w-4" /> {t('evolve.viewPr')}</a>}
            {train.status === 'PR_OPEN' && (
              <Button size="sm" variant="secondary" loading={markMerged.isPending} onClick={() => markMerged.mutate()}>
                <Check className="h-4 w-4" /> {t('evolve.markMerged')}</Button>
            )}
          </div>
        )}
      </CardBody>
    </Card>
  );
}
