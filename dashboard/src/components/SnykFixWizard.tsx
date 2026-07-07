import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ExternalLink, CheckCircle2, AlertTriangle, GitBranch, Loader2, Check } from 'lucide-react';
import { api, type SnykIssueView, type SnykFixTrainView, type SnykFixStepView } from '../api';
import { Modal } from './Modal';
import { Badge, Button, ErrorState, Field, Input, Spinner } from './ui';
import { useToast } from './Toast';
import { TONE } from '../theme/tokens';
import { FIX_STATUS, IN_FLIGHT } from '../lib/snykStatus';
import { SuccessCheck } from './SuccessCheck';

const FRAMEWORK_LABELS = ['BOM', 'core', 'api', 'web'];

/** Only ever render an http(s) URL as a clickable link — never a javascript:/data: value (defence-in-depth). */
function isHttpUrl(url?: string | null): boolean {
  return !!url && /^https?:\/\//i.test(url);
}

function stepTone(status: string): string {
  if (status === 'PR_OPEN' || status === 'MERGED') return TONE.ok;
  if (status === 'FAILED') return TONE.danger;
  if (status === 'MANUAL') return TONE.warn;
  return TONE.muted;
}

type StepVisual = 'done' | 'active' | 'pending' | 'error' | 'manual' | 'pushed';
/** The contract-validation-style visual for one cascade step — drives its icon circle + row accent. */
function stepVisual(step: SnykFixStepView, failedHere: boolean): StepVisual {
  if (failedHere || step.status === 'FAILED') return 'error';
  if (step.manual || step.status === 'MANUAL') return 'manual';
  if (step.status === 'PR_OPEN' || step.status === 'MERGED') return 'done';
  if (step.status === 'RUNNING') return 'active';
  if (step.status === 'BRANCH_PUSHED') return 'pushed';
  return 'pending';   // PLANNED — not yet reached
}

/** The guided fix flow: pick app-ids + a Jira ticket → review the cascade (edit versions/reviewers) → watch the train. */
export function SnykFixWizard({ open, onClose, issue, watchId, apps, defaultApp }:
  { open: boolean; onClose: () => void; issue: SnykIssueView | null; watchId?: string;
    apps: { slug: string; name: string }[]; defaultApp?: string }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();

  const [selected, setSelected] = useState<Set<string>>(new Set(defaultApp ? [defaultApp] : []));
  const [jiraKey, setJiraKey] = useState('');
  const [jiraProject, setJiraProject] = useState('');
  const [reviewers, setReviewers] = useState('');
  const [trainId, setTrainId] = useState<string | null>(null);
  const [prUrls, setPrUrls] = useState<Record<number, string>>({});
  // Review-step edits: per-module new version (keyed by module label) + per-step reviewers (keyed by step order).
  const [versionEdits, setVersionEdits] = useState<Record<string, string>>({});
  const [reviewerEdits, setReviewerEdits] = useState<Record<number, string>>({});

  // The wizard stays mounted (Modal just hides), so the useState initializer ran once at page mount with no
  // defaultApp — re-seed the app pre-selection each time it opens so the current app is actually pre-checked.
  useEffect(() => {
    if (open) {
      setSelected(new Set(defaultApp ? [defaultApp] : []));
    }
  }, [open, defaultApp]);

  const trainQ = useQuery({
    queryKey: ['snyk-fix', trainId], queryFn: () => api.snykFix(trainId as string), enabled: !!trainId,
    refetchInterval: (q) => (q.state.data && IN_FLIGHT.includes(q.state.data.status) ? 2000 : false),
  });
  const train = trainQ.data;
  const reviewing = train?.status === FIX_STATUS.AWAITING_CONFIRM;

  const start = useMutation({
    mutationFn: () => api.startSnykFix({
      watchId, issueId: issue?.issueId ?? '', coordinate: issue?.pkgName ?? '', oldVersion: issue?.pkgVersion ?? '',
      fixedIn: issue?.fixedIn ?? '', severity: issue?.severity ?? '',
      appIds: [...selected].map((s) => s.toUpperCase()),   // Snyk org slug (app7576) → Bitbucket project (APP7576)
      jiraKey: jiraKey.trim() || undefined, jiraProject: jiraProject.trim() || undefined,
      reviewers: reviewers.split(',').map((r) => r.trim()).filter(Boolean),
      autoConfirm: false,   // pause at AWAITING_CONFIRM so the user reviews the cascade first
    }),
    onSuccess: (r) => setTrainId(r.trainId),
    onError: (e: Error) => toast.push('error', e.message),
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ['snyk-fix', trainId] });

  const confirm = useMutation({
    mutationFn: () => {
      const reviewerOverrides: Record<number, string[]> = {};
      for (const [order, raw] of Object.entries(reviewerEdits)) {
        const list = raw.split(',').map((r) => r.trim()).filter(Boolean);
        if (list.length) reviewerOverrides[Number(order)] = list;
      }
      const versionOverrides: Record<string, string> = {};
      for (const [label, v] of Object.entries(versionEdits)) {
        if (v.trim()) versionOverrides[label] = v.trim();
      }
      return api.confirmSnykFix(trainId as string, versionOverrides, reviewerOverrides);
    },
    onSuccess: invalidate,
    onError: (e: Error) => toast.push('error', e.message),
  });

  const openPrs = useMutation({ mutationFn: () => api.openSnykFixPrs(trainId as string),
    onSuccess: invalidate, onError: (e: Error) => toast.push('error', e.message) });
  const markMerged = useMutation({ mutationFn: () => api.markSnykFixMerged(trainId as string),
    onSuccess: invalidate, onError: (e: Error) => toast.push('error', e.message) });
  const recordPr = useMutation({
    mutationFn: (v: { order: number; url: string }) => api.recordSnykFixPr(trainId as string, v.order, v.url),
    onSuccess: invalidate, onError: (e: Error) => toast.push('error', e.message),
  });

  const canStart = selected.size > 0 && (jiraKey.trim() !== '' || jiraProject.trim() !== '') && !!issue;

  const close = () => {
    setTrainId(null);
    setSelected(new Set(defaultApp ? [defaultApp] : []));
    setVersionEdits({});
    setReviewerEdits({});
    onClose();
  };

  const footer = !trainId ? (
    <>
      <Button variant="secondary" onClick={close}>{t('common.close')}</Button>
      <Button disabled={!canStart} loading={start.isPending} onClick={() => start.mutate()}>
        {t('snyk.fix.start')}
      </Button>
    </>
  ) : reviewing ? (
    <>
      <Button variant="secondary" onClick={close}>{t('common.close')}</Button>
      <Button loading={confirm.isPending} onClick={() => confirm.mutate()}>{t('snyk.fix.confirm')}</Button>
    </>
  ) : <Button variant="secondary" onClick={close}>{t('common.close')}</Button>;

  return (
    <Modal open={open} onClose={close} size="lg"
      title={t('snyk.fix.title', { pkg: issue?.pkgName ?? '', version: issue?.fixedIn ?? '' })}
      footer={footer}>

      {/* ── Setup ── */}
      {!trainId && (
        <div className="space-y-4">
          <p className="text-sm text-muted">{t('snyk.fix.intro', {
            pkg: issue?.pkgName, from: issue?.pkgVersion, to: issue?.fixedIn })}</p>
          <p className="text-xs text-muted">{t('snyk.fix.introAside')}</p>

          <Field label={t('snyk.fix.appsLabel')} hint={t('snyk.fix.appsHint')}>
            <div className="grid gap-1 sm:grid-cols-2">
              {apps.map((a) => (
                <label key={a.slug} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-ink-50/60">
                  <input type="checkbox" className="accent-brand" checked={selected.has(a.slug)}
                    onChange={(e) => setSelected((p) => { const n = new Set(p); if (e.target.checked) n.add(a.slug); else n.delete(a.slug); return n; })} />
                  <span className="truncate text-ink-900">{a.name || a.slug}</span>
                  <span className="ml-auto text-2xs text-muted">{a.slug.toUpperCase()}</span>
                </label>
              ))}
            </div>
          </Field>

          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={t('snyk.fix.jiraKeyLabel')} hint={t('snyk.fix.jiraKeyHint')}>
              <Input value={jiraKey} onChange={(e) => setJiraKey(e.target.value)} placeholder="CIAM-1234" />
            </Field>
            <Field label={t('snyk.fix.jiraProjectLabel')} hint={t('snyk.fix.jiraProjectHint')}>
              <Input value={jiraProject} onChange={(e) => setJiraProject(e.target.value)} placeholder="CIAM"
                disabled={jiraKey.trim() !== ''} />
            </Field>
          </div>
          <Field label={t('snyk.fix.reviewersLabel')} hint={t('snyk.fix.reviewersHint')}>
            <Input value={reviewers} onChange={(e) => setReviewers(e.target.value)} placeholder="alice, bob" />
          </Field>
          <p className="rounded-lg bg-ink-50/60 px-3 py-2 text-xs text-muted">
            {t('snyk.fix.bitbucketNote')}
          </p>
        </div>
      )}

      {/* ── Review the cascade (edit versions + reviewers) before it runs ── */}
      {trainId && reviewing && train && (
        <div className="space-y-4">
          <p className="text-sm text-muted">{t('snyk.fix.reviewIntro')}</p>
          <ol className="space-y-2">
            {train.steps.map((s) => (
              <ReviewRow key={s.order} step={s}
                version={versionEdits[s.moduleLabel] ?? (s.newModuleVersion ?? '')}
                onVersion={(v) => setVersionEdits((p) => ({ ...p, [s.moduleLabel]: v }))}
                reviewers={reviewerEdits[s.order] ?? s.reviewers.join(', ')}
                onReviewers={(v) => setReviewerEdits((p) => ({ ...p, [s.order]: v }))} />
            ))}
          </ol>
        </div>
      )}

      {/* ── Release train ── */}
      {trainId && !reviewing && (
        <div className="space-y-4">
          {!train ? (
            trainQ.isError ? (
              <div className="space-y-2">
                <ErrorState message={t('snyk.fix.loadFailed')} />
                <Button variant="secondary" onClick={() => trainQ.refetch()}>{t('common.retry')}</Button>
              </div>
            ) : (
              <div className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('snyk.fix.starting')}</div>
            )
          ) : (
            <>
              <TrainHeader train={train} />
              <ol className="space-y-2">
                {train.steps.map((s) => (
                  <StepRow key={s.order} step={s} failedHere={train.failedStepOrder === s.order}
                    awaiting={train.status === FIX_STATUS.AWAITING_MANUAL_FIX}
                    url={prUrls[s.order] ?? ''} onUrl={(v) => setPrUrls((p) => ({ ...p, [s.order]: v }))}
                    onRecord={() => recordPr.mutate({ order: s.order, url: prUrls[s.order] ?? '' })} />
                ))}
              </ol>
              <div className="flex flex-wrap gap-2">
                {train.status === FIX_STATUS.AWAITING_MANUAL_FIX && (
                  <Button loading={openPrs.isPending} onClick={() => openPrs.mutate()}>
                    {t('snyk.fix.openPrs')}</Button>
                )}
                {train.status === FIX_STATUS.PR_OPEN && (
                  <Button variant="secondary" loading={markMerged.isPending} onClick={() => markMerged.mutate()}>
                    {t('snyk.fix.markMerged')}</Button>
                )}
              </div>
            </>
          )}
        </div>
      )}
    </Modal>
  );
}

/** One row in the review step: a framework module (editable version) or a consumer/manual step. */
function ReviewRow({ step, version, onVersion, reviewers, onReviewers }:
  { step: SnykFixStepView; version: string; onVersion: (v: string) => void;
    reviewers: string; onReviewers: (v: string) => void }) {
  const { t } = useTranslation();
  const isFramework = FRAMEWORK_LABELS.includes(step.moduleLabel);
  return (
    <li className={`rounded-lg ring-1 ring-border px-3 py-2 ${step.manual ? 'opacity-60' : ''}`}>
      <div className="flex items-center gap-2">
        <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-ink-100 text-2xs font-semibold text-ink-700">{step.order}</span>
        <GitBranch className="h-3.5 w-3.5 text-muted" />
        <span className="font-medium text-ink-900">{step.moduleLabel}</span>
        <span className="truncate text-xs text-muted">{step.bitbucketProject}/{step.repoSlug}</span>
        {step.manual && <Badge className={`ml-auto ${TONE.warn}`}>{t('snyk.fix.step.MANUAL')}</Badge>}
      </div>
      <p className="mt-1 pl-7 text-xs text-muted">{step.manual ? step.reason : step.diffPreview}</p>
      {!step.manual && (
        <div className="ml-7 mt-2 grid gap-2 sm:grid-cols-2">
          {isFramework && (
            <Field label={t('snyk.fix.newVersionLabel')}>
              <Input value={version} onChange={(e) => onVersion(e.target.value)} className="h-8 text-xs" />
            </Field>
          )}
          <Field label={t('snyk.fix.reviewersLabel')} hint={t('snyk.fix.reviewersEditHint')}>
            <Input value={reviewers} onChange={(e) => onReviewers(e.target.value)}
              placeholder="alice, bob" className="h-8 text-xs" />
          </Field>
        </div>
      )}
    </li>
  );
}

function TrainHeader({ train }: { train: SnykFixTrainView }) {
  const { t } = useTranslation();
  const inFlight = IN_FLIGHT.includes(train.status);
  const failed = train.status === FIX_STATUS.FAILED;
  const actionNeeded = train.status === FIX_STATUS.AWAITING_MANUAL_FIX;
  // Progress = the share of actionable modules whose branch is up or PR is open (BOM → core → api/web → app).
  const actionable = train.steps.filter((s) => !s.manual);
  const done = actionable.filter((s) => ['PR_OPEN', 'MERGED', 'BRANCH_PUSHED'].includes(s.status)).length;
  const pct = actionable.length ? Math.round((done / actionable.length) * 100) : 0;
  const tone = train.status === FIX_STATUS.DONE || train.status === FIX_STATUS.PR_OPEN ? TONE.ok
    : failed ? TONE.danger : actionNeeded ? TONE.warn : TONE.info;
  return (
    <div className={`rounded-xl px-4 py-3 ring-1 ${failed ? 'bg-danger/5 ring-danger/20'
      : actionNeeded ? 'bg-warning/5 ring-warning/20' : 'bg-ink-50/60 ring-border'}`}>
      <div className="flex items-center gap-2">
        {inFlight ? <Loader2 className="h-4 w-4 animate-spin text-brand" />
          : actionNeeded ? <AlertTriangle className="h-4 w-4 text-warning" />
          : train.status === FIX_STATUS.DONE ? <SuccessCheck className="h-5 w-5" />
          : failed ? <AlertTriangle className="h-4 w-4 text-danger" />
          : <CheckCircle2 className="h-4 w-4 text-success" />}
        <Badge className={tone}>{t(`snyk.fix.status.${train.status}`, train.status)}</Badge>
        {train.stageDetail && !actionNeeded && !failed &&
          <span className="truncate text-sm text-muted">{train.stageDetail}</span>}
      </div>
      {/* Progress bar — advances BOM → core → api/web → app as each module completes. */}
      {!failed && (
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-white/60">
          <div className={`h-full rounded-full transition-all ${actionNeeded ? 'bg-warning' : 'bg-brand'}`}
            style={{ width: `${pct}%` }} />
        </div>
      )}
      {/* Advisory LLM verdict + the real reactor gate */}
      <p className="mt-2 text-xs text-muted">
        {train.verdict?.available
          ? (train.breaking ? t('snyk.fix.verdictBreaking', { c: train.verdict.confidence })
              : t('snyk.fix.verdictClean', { c: train.verdict.confidence }))
          : t('snyk.fix.verdictNone')}
        {train.reactorPassed === true && ' · ' + t('snyk.fix.reactorPass')}
        {train.reactorPassed === false && ' · ' + t('snyk.fix.reactorFail', { where: train.reactorFailingLabel ?? '' })}
      </p>
      {/* Prominent "action needed" banner — carries the backend's "the local build failed at <module>…" detail. */}
      {actionNeeded && (
        <p className="mt-2 flex items-start gap-1.5 rounded-lg bg-warning/10 px-3 py-2 text-xs font-medium text-warning ring-1 ring-warning/20">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>{train.stageDetail || t('snyk.fix.breakingHelp')}</span>
        </p>
      )}
      {failed && train.errorMessage && (
        <p className="mt-2 whitespace-pre-wrap break-words rounded-lg bg-danger/10 px-3 py-2 text-xs text-danger ring-1 ring-danger/20">
          {train.failedStage ? `${t('snyk.fix.failedAt', { stage: train.failedStage })} ` : ''}{train.errorMessage}
        </p>
      )}
    </div>
  );
}

function StepRow({ step, failedHere, awaiting, url, onUrl, onRecord }:
  { step: SnykFixStepView; failedHere: boolean; awaiting: boolean; url: string;
    onUrl: (v: string) => void; onRecord: () => void }) {
  const { t } = useTranslation();
  const visual = stepVisual(step, failedHere);
  // The live line while active, the reason on failure, else the planned diff.
  const detail = visual === 'active' && step.stageDetail ? step.stageDetail
    : (visual === 'error' && step.reason) ? step.reason
    : step.manual ? step.reason : step.diffPreview;
  const circle = visual === 'done' ? 'bg-success/10 text-success ring-success/30'
    : visual === 'active' ? 'bg-brand/10 text-brand ring-brand/30'
    : visual === 'error' ? 'bg-danger/10 text-danger ring-danger/30'
    : visual === 'manual' ? 'bg-warning/10 text-warning ring-warning/30'
    : visual === 'pushed' ? 'bg-ink-100 text-ink-700 ring-border'
    : 'bg-ink-50 text-muted/60 ring-border';
  return (
    <li className={`flex items-start gap-3 rounded-lg px-2.5 py-2 ${visual === 'active' ? 'bg-brand/5'
      : visual === 'error' ? 'bg-danger/5' : ''}`}>
      <span className={`mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full ring-1 ${circle}`}>
        {visual === 'done' ? <Check className="h-4 w-4" />
          : visual === 'active' ? <Loader2 className="h-4 w-4 animate-spin" />
          : visual === 'error' || visual === 'manual' ? <AlertTriangle className="h-4 w-4" />
          : <GitBranch className="h-4 w-4" />}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-ink-900">{step.moduleLabel}</span>
          <span className="truncate text-xs text-muted">{step.bitbucketProject}/{step.repoSlug}</span>
          <Badge className={`ml-auto ${stepTone(step.status)}`}>
            {step.manual ? t('snyk.fix.step.MANUAL') : t(`snyk.fix.step.${step.status}`, step.status)}
          </Badge>
        </div>
        {detail && <p className={`mt-0.5 text-xs ${visual === 'error' ? 'text-danger' : 'text-muted'}`}>{detail}</p>}
        {step.prUrl && isHttpUrl(step.prUrl) && (
          <a href={step.prUrl} target="_blank" rel="noreferrer"
            className="mt-1 inline-flex items-center gap-1 text-xs text-gold hover:underline">
            {t('snyk.fix.viewPr')} {step.prOpenedBy ? `(${step.prOpenedBy.toLowerCase()})` : ''} <ExternalLink className="h-3 w-3" />
          </a>
        )}
        {awaiting && !step.manual && !step.prUrl && (
          <div className="mt-1.5 flex items-center gap-2">
            <Input value={url} onChange={(e) => onUrl(e.target.value)} placeholder={t('snyk.fix.prUrlPlaceholder')}
              className="h-8 max-w-xs text-xs" />
            <Button size="sm" variant="ghost" disabled={!url.trim()} onClick={onRecord}>{t('snyk.fix.recordPr')}</Button>
          </div>
        )}
      </div>
    </li>
  );
}
