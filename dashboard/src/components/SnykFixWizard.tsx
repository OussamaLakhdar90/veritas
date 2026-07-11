import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ExternalLink, CheckCircle2, AlertTriangle, GitBranch, Loader2, Check, Clock, Ban, ShieldCheck } from 'lucide-react';
import { api, type SnykIssueView, type SnykFixTrainView, type SnykFixStepView } from '../api';
import { Modal } from './Modal';
import { Badge, Button, ErrorState, Field, Input, Spinner } from './ui';
import { useToast } from './Toast';
import { TONE } from '../theme/tokens';
import { FIX_STATUS, IN_FLIGHT } from '../lib/snykStatus';
import { FIX_PHASES, FIX_PHASE_ORDER, fixPhasePct, phaseVisual } from '../lib/fixPhases';
import { formatElapsed, useElapsed, useStageElapsed } from '../lib/scanStages';
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

/**
 * A muted "Cancel fix" button with a TWO-CLICK inline confirm — abandoning pushed work shouldn't be a one-tap
 * accident. First click arms it ("Click again to abandon"), which disarms after ~3s; the second click cancels.
 * Ghost/muted styling, never brand-red — cancelling is not an error ([[veritas-color-convention]]).
 */
export function CancelFixButton({ onCancel, pending }: { onCancel: () => void; pending: boolean }) {
  const { t } = useTranslation();
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    if (!armed) return;
    const id = setTimeout(() => setArmed(false), 3000);
    return () => clearTimeout(id);
  }, [armed]);
  return (
    <Button variant="ghost" loading={pending} className={armed ? 'text-warning' : 'text-muted'}
      onClick={() => { if (armed) { setArmed(false); onCancel(); } else { setArmed(true); } }}>
      <Ban className="h-3.5 w-3.5" />
      {armed ? t('snyk.fix.cancelConfirm') : t('snyk.fix.cancel')}
    </Button>
  );
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
  const cancel = useMutation({ mutationFn: () => api.cancelSnykFix(trainId as string),
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
                {/* Third choice besides open/record — abandon a waiting train so it isn't a PR-or-DB-delete dead-end. */}
                {(train.status === FIX_STATUS.AWAITING_MANUAL_FIX || train.status === FIX_STATUS.PR_OPEN) && (
                  <CancelFixButton onCancel={() => cancel.mutate()} pending={cancel.isPending} />
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
export function ReviewRow({ step, version, onVersion, reviewers, onReviewers }:
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

/**
 * The live lifecycle stepper — the same shape as contract validation's ScanProgress. It shows the fix advancing
 * through its four machine-driven phases (Getting the code → Checking for breaking changes → Building & testing →
 * Opening the pull requests) so the user always sees *which operation* is running, and — on a failure — exactly
 * which étape stopped it. The per-module cascade (BOM → core → api/web → app) renders below as the detail.
 */
// Jira status → a calm chip tone. Done/closed → green; everything else (in progress / in review) → gold.
// Colour convention: never brand-red for a normal in-flight state — red reads as "error".
function jiraChipTone(status?: string) {
  const v = (status ?? '').toLowerCase();
  if (/done|closed|resolved|complete|fixed/.test(v)) return 'bg-success/5 text-success ring-success/30';
  return 'bg-gold/5 text-gold ring-gold/30';
}

export function TrainHeader({ train }: { train: SnykFixTrainView }) {
  const { t } = useTranslation();
  const inFlight = IN_FLIGHT.includes(train.status);
  const failed = train.status === FIX_STATUS.FAILED;
  const cancelled = train.status === FIX_STATUS.CANCELLED;
  const alreadyFixed = train.status === FIX_STATUS.ALREADY_FIXED;
  const actionNeeded = train.status === FIX_STATUS.AWAITING_MANUAL_FIX;
  const succeeded = train.status === FIX_STATUS.DONE || train.status === FIX_STATUS.PR_OPEN;

  const current = FIX_PHASE_ORDER[train.status] ?? 0;
  const stepNo = Math.min(FIX_PHASES.length, current);
  const activePhase = FIX_PHASES.find((p) => p.key === train.status);
  const pct = fixPhasePct(train.status, train.failedStage);
  // Overall clock off createdAt — the honest MTTR clock (startedAt is a machine-phase clock the reconciler resets
  // mid-flight, which would make the timer jump backwards at the VERIFYING reset).
  const createdMs = train.createdAt ? Date.parse(train.createdAt) : null;
  const elapsed = useElapsed(createdMs, inFlight);
  const stageElapsed = useStageElapsed(train.status, inFlight);   // the current phase's own timer

  const headline = cancelled ? t(`snyk.fix.status.${FIX_STATUS.CANCELLED}`, 'Cancelled')
    : alreadyFixed ? t(`snyk.fix.status.${FIX_STATUS.ALREADY_FIXED}`, 'Already on the safe version')
    : failed ? t(`snyk.fix.status.${FIX_STATUS.FAILED}`, 'Stopped')
    : activePhase ? t(`snyk.fix.phase.${activePhase.key}.label`)
    : t(`snyk.fix.status.${train.status}`, train.status);
  const eyebrow = failed ? t('repos.statusStopped')
    : stepNo === 0 ? t('repos.statusStarting')
    : t('repos.statusStep', { stepNo, total: FIX_PHASES.length });
  // Literal class strings (Tailwind can't see dynamically-built names) keyed off the header tone.
  // Colour convention: GOLD = in-progress (calm), amber = action-needed, green = done, RED = failure ONLY, and a
  // neutral/muted grey for CANCELLED and ALREADY_FIXED — neither is an error, so neither is ever red.
  const boxCls = (cancelled || alreadyFixed) ? 'bg-ink-50 ring-border' : failed ? 'bg-danger/5 ring-danger/20'
    : actionNeeded ? 'bg-warning/5 ring-warning/20' : succeeded ? 'bg-success/5 ring-success/20' : 'bg-gold/5 ring-gold/20';
  const eyebrowCls = failed ? 'text-danger' : actionNeeded ? 'text-warning' : succeeded ? 'text-success' : 'text-gold';
  const barCls = (cancelled || alreadyFixed) ? 'bg-ink-300' : failed ? 'bg-danger' : actionNeeded ? 'bg-warning'
    : succeeded ? 'bg-success' : 'bg-gold';

  return (
    <div>
      {/* Live header: where we are, overall progress, and a ticking clock (mirrors ScanProgress). */}
      <div className={`mb-3 rounded-xl p-4 ring-1 ${boxCls}`}>
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            {(inFlight || failed) && (
              <p className={`text-2xs font-semibold uppercase tracking-wide ${eyebrowCls}`}>{eyebrow}</p>
            )}
            <p className="mt-0.5 flex items-center gap-1.5 truncate text-sm font-semibold text-ink-900">
              {inFlight ? <Loader2 className="h-4 w-4 animate-spin text-gold" />
                : actionNeeded ? <AlertTriangle className="h-4 w-4 text-warning" />
                : train.status === FIX_STATUS.DONE ? <SuccessCheck className="h-5 w-5" />
                : failed ? <AlertTriangle className="h-4 w-4 text-danger" />
                : cancelled ? <Ban className="h-4 w-4 text-muted" />
                : alreadyFixed ? <ShieldCheck className="h-4 w-4 text-muted" />
                : <CheckCircle2 className="h-4 w-4 text-success" />}
              {headline}
            </p>
          </div>
          {inFlight && (
            <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-surface/80 px-2.5 py-1 text-xs font-medium tabular-nums text-ink-700 ring-1 ring-border">
              <Clock className="h-3.5 w-3.5 text-muted" /> {formatElapsed(elapsed)}
            </span>
          )}
        </div>
        <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-white/60">
          <div className={`h-full rounded-full ${barCls} transition-all`} style={{ width: `${pct}%` }} />
        </div>
      </div>

      {/* The four lifecycle phases as a stepper — done / active / pending / error / manual. */}
      <ol className="space-y-1">
        {FIX_PHASES.map((phase) => {
          const visual = phaseVisual(phase.key, train.status, train.failedStage, train.reactorPassed);
          const Icon = phase.icon;
          const staticDetail = t(`snyk.fix.phase.${phase.key}.detail`);
          // On a held (breaking) train errorMessage is null, so a failed VERIFYING phase names the module that broke.
          const errorDetail = train.errorMessage
            || (train.reactorFailingLabel ? t('snyk.fix.reactorFail', { where: train.reactorFailingLabel }) : staticDetail);
          const detail = visual === 'active' ? (train.stageDetail || staticDetail)
            : visual === 'error' ? errorDetail
            : staticDetail;
          return (
            <li key={phase.key} className={`flex items-start gap-3 rounded-lg px-2 py-2 ${visual === 'active'
              ? 'bg-gold/5' : visual === 'error' ? 'bg-danger/5' : ''}`}>
              <span className={`mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full ring-1 ${
                visual === 'done' ? 'bg-success/10 text-success ring-success/30'
                : visual === 'active' ? 'bg-gold/10 text-gold ring-gold/30'
                : visual === 'error' ? 'bg-danger/10 text-danger ring-danger/30'
                : visual === 'manual' ? 'bg-warning/10 text-warning ring-warning/30'
                : 'bg-ink-50 text-muted/60 ring-border'}`}>
                {visual === 'done' ? <Check className="h-4 w-4" />
                  : visual === 'active' ? <Loader2 className="h-4 w-4 animate-spin" />
                  : visual === 'error' || visual === 'manual' ? <AlertTriangle className="h-4 w-4" />
                  : <Icon className="h-4 w-4" />}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-2">
                  <p className={`truncate text-sm font-medium ${visual === 'pending' ? 'text-muted' : 'text-ink-900'}`}>
                    {t(`snyk.fix.phase.${phase.key}.label`)}
                  </p>
                  {visual === 'active' && (
                    <span className="shrink-0 text-2xs font-medium tabular-nums text-gold">{formatElapsed(stageElapsed)}</span>
                  )}
                  {visual === 'done' && <span className="shrink-0 text-2xs font-medium text-success">{t('repos.done')}</span>}
                </div>
                <p className={`text-xs ${visual === 'error' ? 'text-danger' : 'text-muted'}`}>{detail}</p>
                {/* The two slow phases (AI check, local build) reassure the user they aren't stuck. */}
                {visual === 'active' && phase.long && (
                  <p className="mt-1.5 inline-flex items-center gap-1.5 rounded-md bg-gold/10 px-2 py-1 text-2xs text-gold ring-1 ring-gold/20">
                    <Clock className="h-3 w-3 shrink-0" />
                    {stageElapsed < 90_000 ? t('snyk.fix.slowHint')
                      : t('snyk.fix.slowHintLong', { elapsed: formatElapsed(stageElapsed) })}
                  </p>
                )}
              </div>
            </li>
          );
        })}
      </ol>

      {/* Advisory LLM verdict + the real reactor gate. */}
      <p className="mt-3 text-xs text-muted">
        {train.verdict?.available
          ? (train.breaking ? t('snyk.fix.verdictBreaking', { c: train.verdict.confidence })
              : t('snyk.fix.verdictClean', { c: train.verdict.confidence }))
          : t('snyk.fix.verdictNone')}
        {train.reactorPassed === true && ' · ' + t('snyk.fix.reactorPass')}
        {train.reactorPassed === false && ' · ' + t('snyk.fix.reactorFail', { where: train.reactorFailingLabel ?? '' })}
      </p>
      {/* The AI's REASONING, not just the confidence % — so the verdict is explainable. Calm styling (never brand-red). */}
      {train.verdict?.available && (train.verdict.reasons?.length || train.verdict.migrationNotes) && (
        <div className="mt-2 rounded-lg bg-ink-50 px-3 py-2 ring-1 ring-border">
          <p className="text-2xs font-semibold uppercase tracking-wide text-muted">{t('snyk.fix.aiReasons')}</p>
          {train.verdict.reasons?.length ? (
            <ul className="mt-1 space-y-0.5">
              {train.verdict.reasons.map((r, i) => (
                <li key={i} className="flex items-start gap-1.5 text-xs text-ink-700">
                  <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-muted/50" />
                  <span>{r}</span>
                </li>
              ))}
            </ul>
          ) : null}
          {train.verdict.migrationNotes && (
            <p className="mt-1.5 text-xs text-muted">
              <span className="font-medium text-ink-700">{t('snyk.fix.migrationNotes')}:</span> {train.verdict.migrationNotes}
            </p>
          )}
        </div>
      )}
      {/* Live Jira status chip — In Progress → In Review → Done, updated on the 2s poll. */}
      {train.jiraKey && (
        <span className={`mt-2 inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-2xs font-medium ring-1 ${jiraChipTone(train.jiraStatus)}`}>
          <span className="h-1.5 w-1.5 rounded-full bg-current" />
          {t('snyk.fix.jiraChip', { key: train.jiraKey }) + (train.jiraStatus ? ` · ${train.jiraStatus}` : '')}
        </span>
      )}
      {/* Prominent "action needed" banner — carries the backend's "the local build failed at <module>…" detail. */}
      {actionNeeded && (
        <p className="mt-2 flex items-start gap-1.5 rounded-lg bg-warning/10 px-3 py-2 text-xs font-medium text-warning ring-1 ring-warning/20">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>{train.stageDetail || t('snyk.fix.breakingHelp')}</span>
        </p>
      )}
      {failed && train.errorMessage && (
        <p className="mt-2 whitespace-pre-wrap break-words rounded-lg bg-danger/10 px-3 py-2 text-xs text-danger ring-1 ring-danger/20">
          {train.failedStage
            ? `${t('snyk.fix.failedAt', { stage: t(`snyk.fix.phase.${train.failedStage}.label`, train.failedStage) })} `
            : ''}{train.errorMessage}
        </p>
      )}
      {/* Cancelled: a calm, muted note (never red) — the Jira ticket + branches were left as-is for a relaunch. */}
      {cancelled && (
        <p className="mt-2 flex items-start gap-1.5 rounded-lg bg-ink-50 px-3 py-2 text-xs text-muted ring-1 ring-border">
          <Ban className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>{train.stageDetail || t('snyk.fix.status.CANCELLED')}</span>
        </p>
      )}
      {/* Already fixed: a muted, honest "nothing to release" note — NOT a false success (no PR), NOT an error (no red).
          Directly answers the "it fixed nothing" complaint by making the no-op case visible instead of a phantom PR. */}
      {alreadyFixed && (
        <p className="mt-2 flex items-start gap-1.5 rounded-lg bg-ink-50 px-3 py-2 text-xs text-muted ring-1 ring-border">
          <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>{train.stageDetail || t('snyk.fix.status.ALREADY_FIXED')}</span>
        </p>
      )}
    </div>
  );
}

export function StepRow({ step, failedHere, awaiting, url, onUrl, onRecord }:
  { step: SnykFixStepView; failedHere: boolean; awaiting: boolean; url: string;
    onUrl: (v: string) => void; onRecord: () => void }) {
  const { t } = useTranslation();
  const visual = stepVisual(step, failedHere);
  // The live line while active, the reason on failure, else the planned diff.
  const detail = visual === 'active' && step.stageDetail ? step.stageDetail
    : (visual === 'error' && step.reason) ? step.reason
    : step.manual ? step.reason : step.diffPreview;
  const circle = visual === 'done' ? 'bg-success/10 text-success ring-success/30'
    : visual === 'active' ? 'bg-gold/10 text-gold ring-gold/30'
    : visual === 'error' ? 'bg-danger/10 text-danger ring-danger/30'
    : visual === 'manual' ? 'bg-warning/10 text-warning ring-warning/30'
    : visual === 'pushed' ? 'bg-ink-100 text-ink-700 ring-border'
    : 'bg-ink-50 text-muted/60 ring-border';
  return (
    <li className={`flex items-start gap-3 rounded-lg px-2.5 py-2 ${visual === 'active' ? 'bg-gold/5'
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
        {/* The concrete git actions — which branch, and the commit sha once pushed — so the stepper isn't "blind"
            about what was actually done (the exact gap the user hit). Branch always shown; sha appears after the push. */}
        {!step.manual && step.branch && (
          <div className="mt-1 flex flex-wrap items-center gap-1.5">
            <span title={step.branch}
              className="inline-flex items-center gap-1 rounded bg-ink-100 px-1.5 py-0.5 font-mono text-2xs text-ink-700 ring-1 ring-border">
              <GitBranch className="h-3 w-3 shrink-0" />
              <span className="max-w-[16rem] truncate">{step.branch}</span>
            </span>
            {step.commitSha && (
              <span title={step.commitSha}
                className="inline-flex items-center gap-1 rounded bg-ink-100 px-1.5 py-0.5 font-mono text-2xs text-ink-700 ring-1 ring-border">
                {t('snyk.fix.commit')} {step.commitSha.slice(0, 7)}
              </span>
            )}
          </div>
        )}
        {step.prUrl && isHttpUrl(step.prUrl) && (
          <a href={step.prUrl} target="_blank" rel="noreferrer"
            className="mt-1 inline-flex items-center gap-1 text-xs text-gold hover:underline">
            {t('snyk.fix.viewPr')} {step.prOpenedBy ? `(${step.prOpenedBy.toLowerCase()})` : ''} <ExternalLink className="h-3 w-3" />
          </a>
        )}
        {/* No PR yet but the branch is pushed → let the user verify it in Bitbucket (fixes the "we said pushed but
            I can't find it" trust gap). */}
        {!step.prUrl && step.branchUrl && isHttpUrl(step.branchUrl) && (
          <a href={step.branchUrl} target="_blank" rel="noreferrer"
            className="mt-1 inline-flex items-center gap-1 text-xs text-gold hover:underline">
            {t('snyk.fix.viewBranch')} <ExternalLink className="h-3 w-3" />
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
