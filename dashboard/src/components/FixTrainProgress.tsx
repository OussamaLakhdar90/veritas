import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { useToast } from './Toast';
import { IN_FLIGHT, FIX_STATUS } from '../lib/snykStatus';
import { TrainHeader, StepRow, ReviewRow } from './SnykFixWizard';
import { Button, ErrorState, Spinner } from './ui';

/**
 * The live + ACTIONABLE view for one Snyk fix train, driven purely by its id — reused by the bulk launch and the
 * Activity deep-link. It shows the same lifecycle stepper the wizard does AND, for every "waiting for you" state,
 * explains what's needed and lets the user do it right here (review & confirm the cascade / open the held PRs /
 * record a manual PR / mark merged) — so a click on an Activity fix row resolves the train instead of dead-ending.
 * Every action toasts on failure and re-fetches on success (no silent no-ops). Polls every 2s while in flight.
 */
export function FixTrainProgress({ trainId }: { trainId: string }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ['snyk-fix', trainId], queryFn: () => api.snykFix(trainId), enabled: !!trainId,
    refetchInterval: (query) => (query.state.data && IN_FLIGHT.includes(query.state.data.status) ? 2000 : false),
  });
  const train = q.data;
  const invalidate = () => qc.invalidateQueries({ queryKey: ['snyk-fix', trainId] });

  const [versionEdits, setVersionEdits] = useState<Record<string, string>>({});
  const [reviewerEdits, setReviewerEdits] = useState<Record<number, string>>({});
  const [prUrls, setPrUrls] = useState<Record<number, string>>({});

  const confirm = useMutation({
    mutationFn: () => {
      const reviewerOverrides: Record<number, string[]> = {};
      for (const [order, raw] of Object.entries(reviewerEdits)) {
        const list = raw.split(',').map((r) => r.trim()).filter(Boolean);
        if (list.length) reviewerOverrides[Number(order)] = list;
      }
      const versionOverrides: Record<string, string> = {};
      for (const [label, v] of Object.entries(versionEdits)) if (v.trim()) versionOverrides[label] = v.trim();
      return api.confirmSnykFix(trainId, versionOverrides, reviewerOverrides);
    },
    onSuccess: invalidate, onError: (e: Error) => toast.push('error', e.message),
  });
  const openPrs = useMutation({ mutationFn: () => api.openSnykFixPrs(trainId),
    onSuccess: invalidate, onError: (e: Error) => toast.push('error', e.message) });
  const markMerged = useMutation({ mutationFn: () => api.markSnykFixMerged(trainId),
    onSuccess: invalidate, onError: (e: Error) => toast.push('error', e.message) });
  const recordPr = useMutation({
    mutationFn: (v: { order: number; url: string }) => api.recordSnykFixPr(trainId, v.order, v.url),
    onSuccess: invalidate, onError: (e: Error) => toast.push('error', e.message),
  });

  if (q.isError) {
    return (
      <div className="space-y-2">
        <ErrorState message={t('snyk.fix.loadFailed')} detail={(q.error as Error).message} />
        <Button variant="secondary" onClick={() => q.refetch()}>{t('common.retry')}</Button>
      </div>
    );
  }
  if (!train) {
    return <div className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('snyk.fix.starting')}</div>;
  }

  const reviewing = train.status === FIX_STATUS.AWAITING_CONFIRM;
  const awaitingManual = train.status === FIX_STATUS.AWAITING_MANUAL_FIX;
  const prOpen = train.status === FIX_STATUS.PR_OPEN;

  return (
    <div className="space-y-4">
      <TrainHeader train={train} />

      {/* Per-state "what you need to do" — the explanation the waiting states were missing, on the deep-link too. */}
      {(reviewing || awaitingManual || prOpen) && (
        <p className="rounded-lg bg-brand/5 px-3 py-2 text-sm text-ink-700 ring-1 ring-brand/15">
          {t(`snyk.fix.whatToDo.${train.status}`)}
        </p>
      )}

      {reviewing ? (
        /* Review the planned cascade (editable versions/reviewers), then Confirm & run. */
        <ol className="space-y-2">
          {train.steps.map((s) => (
            <ReviewRow key={s.order} step={s}
              version={versionEdits[s.moduleLabel] ?? (s.newModuleVersion ?? '')}
              onVersion={(v) => setVersionEdits((p) => ({ ...p, [s.moduleLabel]: v }))}
              reviewers={reviewerEdits[s.order] ?? s.reviewers.join(', ')}
              onReviewers={(v) => setReviewerEdits((p) => ({ ...p, [s.order]: v }))} />
          ))}
        </ol>
      ) : (
        <ol className="space-y-2">
          {train.steps.map((s) => (
            <StepRow key={s.order} step={s} failedHere={train.failedStepOrder === s.order}
              awaiting={awaitingManual}
              url={prUrls[s.order] ?? ''} onUrl={(v) => setPrUrls((p) => ({ ...p, [s.order]: v }))}
              onRecord={() => recordPr.mutate({ order: s.order, url: prUrls[s.order] ?? '' })} />
          ))}
        </ol>
      )}

      {/* The real build output — the actual reason a reactor build failed, captured before but never shown. */}
      {train.reactorPassed === false && train.reactorOutputTail && (
        <details className="rounded-lg ring-1 ring-border">
          <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-muted">
            {t('snyk.fix.buildOutput')}
          </summary>
          <pre className="max-h-64 overflow-auto rounded-b-lg bg-ink-900 px-3 py-2 text-2xs leading-relaxed text-ink-50">
            {train.reactorOutputTail}
          </pre>
        </details>
      )}

      {/* The action, co-located with its explanation — every waiting state can be resolved here. */}
      {(reviewing || awaitingManual || prOpen) && (
        <div className="flex flex-wrap gap-2">
          {reviewing && (
            <Button loading={confirm.isPending} onClick={() => confirm.mutate()}>{t('snyk.fix.confirm')}</Button>
          )}
          {awaitingManual && (
            <Button loading={openPrs.isPending} onClick={() => openPrs.mutate()}>{t('snyk.fix.openPrs')}</Button>
          )}
          {prOpen && (
            <Button variant="secondary" loading={markMerged.isPending} onClick={() => markMerged.mutate()}>
              {t('snyk.fix.markMerged')}</Button>
          )}
        </div>
      )}
    </div>
  );
}
