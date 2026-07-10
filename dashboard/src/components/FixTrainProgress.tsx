import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { IN_FLIGHT } from '../lib/snykStatus';
import { TrainHeader, StepRow } from './SnykFixWizard';
import { Button, ErrorState, Spinner } from './ui';

/**
 * The live progress view for ONE Snyk fix train, driven purely by its id — the SAME lifecycle stepper the single-fix
 * wizard shows (clone → breaking-change check → build & test → open PRs), reused by the bulk launch and the Activity
 * deep-link. Read-only: it shows WHICH phase is running and, on a failure, exactly which step stopped it (mvn / push
 * / a breaking change). Polls every 2s while the train is in flight, then stops on a terminal state.
 */
export function FixTrainProgress({ trainId }: { trainId: string }) {
  const { t } = useTranslation();
  const q = useQuery({
    queryKey: ['snyk-fix', trainId], queryFn: () => api.snykFix(trainId), enabled: !!trainId,
    refetchInterval: (query) => (query.state.data && IN_FLIGHT.includes(query.state.data.status) ? 2000 : false),
  });
  const train = q.data;

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
  return (
    <div className="space-y-4">
      <TrainHeader train={train} />
      <ol className="space-y-2">
        {train.steps.map((s) => (
          <StepRow key={s.order} step={s} failedHere={train.failedStepOrder === s.order}
            awaiting={false} url="" onUrl={() => {}} onRecord={() => {}} />
        ))}
      </ol>
    </div>
  );
}
