import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '../components/ui';
import { FixTrainProgress } from '../components/FixTrainProgress';

/**
 * Deep-link target for one Snyk fix train (opened from the Activity feed) — shows its live progress stepper so a
 * click on an Activity fix row lands on the actual fix, not the bare Snyk page.
 */
export function SnykFixDetail() {
  const { t } = useTranslation();
  const { trainId } = useParams();
  return (
    <div>
      <PageHeader title={t('snyk.fix.detailTitle')} subtitle={t('snyk.fix.detailSubtitle')} />
      {trainId && <FixTrainProgress trainId={trainId} />}
    </div>
  );
}
