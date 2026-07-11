import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Brain, GitPullRequestArrow, RefreshCw, ExternalLink, Check, ShieldAlert, ChevronRight, ChevronDown, FlaskConical } from 'lucide-react';
import { api, ClassificationTrain, DisputedTypeGroup, DisputeExample } from '../api';
import { Badge, Button, Card, CardBody, EmptyState, ErrorState, Input, PageHeader, TableSkeleton } from '../components/ui';
import { useToast } from '../components/Toast';
import { severityBadge, TONE } from '../theme/tokens';
import { cn } from '../components/cn';
import { enumLabel } from '../lib/enumLabels';

/** The maintainer verdicts on a disputed finding, in triage order (worst-for-the-engine last). */
const VERDICTS: { key: string; tone: string }[] = [
  { key: 'CONFIRMED_FP', tone: TONE.danger },
  { key: 'VALID', tone: TONE.ok },
  { key: 'NEEDS_DETECTION_FIX', tone: TONE.warn },
];

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

      <DisputedSection />
    </div>
  );
}

/**
 * The precision half of the learning debt: the reconcile LLM's own likely-false-positive flags, rolled up by
 * finding type. A maintainer drills in and records a verdict per finding — was the dispute right (a real false
 * positive), wrong (a valid finding), or a systematic detection gap worth an engine fix? Read-only triage; the
 * per-type counts reconcile with the disputed KPI above, and the verdicts are the signal Channel 2 will consume.
 */
function DisputedSection() {
  const { t } = useTranslation();
  const disputed = useQuery({ queryKey: ['ai-disputed'], queryFn: () => api.aiDisputedFindings() });
  const groups = disputed.data ?? [];

  return (
    <div className="mt-8">
      <div className="mb-3 flex items-center gap-2">
        <ShieldAlert className="h-5 w-5 text-brand" />
        <div>
          <h2 className="text-lg font-semibold text-ink-900">{t('evolve.disputesTitle')}</h2>
          <p className="text-sm text-muted">{t('evolve.disputesSubtitle')}</p>
        </div>
      </div>

      {disputed.isLoading ? (
        <Card><CardBody className="p-0"><TableSkeleton label={t('evolve.disputesLoading')} /></CardBody></Card>
      ) : disputed.isError ? (
        <ErrorState message={t('evolve.disputesLoadError')} detail={(disputed.error as Error).message} />
      ) : groups.length === 0 ? (
        <EmptyState icon={ShieldAlert} title={t('evolve.disputesEmptyTitle')} body={t('evolve.disputesEmptyBody')} />
      ) : (
        <div className="flex flex-col gap-3">
          {groups.map((g) => <DisputedTypeCard key={g.findingType} group={g} />)}
        </div>
      )}
    </div>
  );
}

function DisputedTypeCard({ group }: { group: DisputedTypeGroup }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  return (
    <Card>
      <CardBody className="flex flex-col gap-3">
        <button type="button" aria-expanded={open} onClick={() => setOpen((o) => !o)}
          className="flex items-center justify-between gap-3 flex-wrap text-left">
          <div className="flex items-center gap-2">
            {open ? <ChevronDown className="h-4 w-4 text-muted" /> : <ChevronRight className="h-4 w-4 text-muted" />}
            <span className="font-mono text-sm font-semibold text-ink-900">{group.findingType}</span>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {VERDICTS.filter((v) => group.verdictBreakdown[v.key]).map((v) => (
              <Badge key={v.key} className={v.tone}>{t(`evolve.verdict_${v.key}`)}: {group.verdictBreakdown[v.key]}</Badge>
            ))}
            <span className="text-xs text-muted">
              {t('evolve.disputesEvidence', { count: group.count, services: group.distinctServices })}</span>
          </div>
        </button>

        {open && (
          <div className="flex flex-col divide-y divide-border overflow-hidden rounded-lg bg-ink-50">
            {group.examples.map((ex) => <DisputeExampleRow key={ex.id} ex={ex} />)}
          </div>
        )}
      </CardBody>
    </Card>
  );
}

function DisputeExampleRow({ ex }: { ex: DisputeExample }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const setVerdict = useMutation({
    mutationFn: (verdict: string) => api.setDisputeVerdict(ex.id, verdict),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ai-disputed'] });
      qc.invalidateQueries({ queryKey: ['classification-debt'] });
      toast.push('success', t('evolve.verdictSaved'));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  return (
    <div className="flex flex-col gap-2 p-3">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="min-w-0">
          <p className="text-sm text-ink-900">{ex.summary || ex.endpoint || t('evolve.disputeUnlabelled')}</p>
          <p className="mt-0.5 flex items-center gap-2 flex-wrap font-mono text-xs text-muted">
            {ex.service && <span>{ex.service}</span>}
            {ex.endpoint && <span>{ex.endpoint}</span>}
          </p>
        </div>
        {ex.scanId && (
          <Link to={`/findings/${ex.scanId}`}
            className="inline-flex shrink-0 items-center gap-1 text-sm text-brand hover:underline">
            <ExternalLink className="h-4 w-4" /> {t('evolve.viewFinding')}
          </Link>
        )}
      </div>
      {ex.reason && <p className="rounded-md bg-surface p-2 text-sm text-ink-700">{ex.reason}</p>}
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-xs text-muted">{t('evolve.verdictPrompt')}</span>
        {VERDICTS.map((v) => (
          <Button key={v.key} size="sm" variant={ex.verdict === v.key ? 'primary' : 'secondary'}
            loading={setVerdict.isPending && setVerdict.variables === v.key}
            onClick={() => setVerdict.mutate(v.key)}>
            {t(`evolve.verdict_${v.key}`)}
          </Button>
        ))}
      </div>
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
  // Developer dry-run: writes the generated DiffEngine.java edit to a local review folder — no clone/gate/PR.
  // On success the toast names the folder to open; on error (e.g. the flag is off) it surfaces the reason.
  const dryRun = useMutation({
    mutationFn: () => api.dryRunClassification(train.id),
    onSuccess: (p) => toast.push('success', t('evolve.dryRunDone', { path: p.editedFilePath })),
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
              <Button size="sm" variant="ghost" loading={dryRun.isPending} disabled={unsaved}
                title={t('evolve.dryRunHint')} onClick={() => dryRun.mutate()}>
                <FlaskConical className="h-4 w-4" /> {t('evolve.dryRun')}</Button>
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
