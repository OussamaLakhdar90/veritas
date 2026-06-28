import { BookOpen } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { Badge, Card, CardBody, CardHeader, PageHeader } from '../components/ui';
import { SEVERITY_BADGE, LAYER_LABEL, CONFIDENCE_LABEL, TONE } from '../theme/tokens';

/** A single term row: the labelled chip on the left, a plain-language meaning on the right. */
function Term({ chip, children }: { chip: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5 py-3 sm:flex-row sm:items-start sm:gap-4">
      <div className="w-44 shrink-0">{chip}</div>
      <p className="text-[13.5px] leading-relaxed text-ink-900">{children}</p>
    </div>
  );
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <Card className="mb-5">
      <CardHeader title={title} subtitle={subtitle} />
      <CardBody className="divide-y divide-border/60 py-1">{children}</CardBody>
    </Card>
  );
}

/**
 * In-app glossary — the plain-language vocabulary Veritas uses on screen and in the management report.
 * It pulls its labels from the same token maps the rest of the UI uses, so it can never drift from them.
 */
export function Glossary() {
  const { t } = useTranslation();
  return (
    <div className="max-w-3xl">
      <PageHeader
        title={t('glossary.pageTitle')}
        subtitle={t('glossary.pageSubtitle')}
      />

      <Card className="mb-5 border-l-4 border-l-brand">
        <CardBody className="flex items-start gap-3">
          <BookOpen className="mt-0.5 h-5 w-5 shrink-0 text-brand" />
          <p className="text-[13.5px] leading-relaxed text-ink-900">
            <Trans i18nKey="glossary.intro">
              Veritas compares what a service's <strong>code actually does</strong> with what its <strong>API
              contract promises</strong>, and lists every difference as a <em>finding</em>. The terms below
              explain how those findings are labelled so you can judge them without reading the code.
            </Trans>
          </p>
        </CardBody>
      </Card>

      <Section title={t('glossary.bottomLineTitle')}
        subtitle={t('glossary.bottomLineSubtitle')}>
        <Term chip={<Badge className={TONE.ok}>{t('glossary.proceedChip')}</Badge>}>
          {t('glossary.proceedBody')}
        </Term>
        <Term chip={<Badge className={TONE.warn}>{t('glossary.holdChip')}</Badge>}>
          {t('glossary.holdBody')}
        </Term>
        <Term chip={<Badge className={TONE.danger}>{t('glossary.doNotReleaseChip')}</Badge>}>
          {t('glossary.doNotReleaseBody')}
        </Term>
      </Section>

      <Section title={t('glossary.severityTitle')}
        subtitle={t('glossary.severitySubtitle')}>
        <Term chip={<Badge className={SEVERITY_BADGE.BLOCKER}>{t('glossary.blockerChip')}</Badge>}>
          {t('glossary.blockerBody')}
        </Term>
        <Term chip={<Badge className={SEVERITY_BADGE.CRITICAL}>{t('glossary.criticalChip')}</Badge>}>
          {t('glossary.criticalBody')}
        </Term>
        <Term chip={<Badge className={SEVERITY_BADGE.MAJOR}>{t('glossary.majorChip')}</Badge>}>
          {t('glossary.majorBody')}
        </Term>
        <Term chip={<Badge className={SEVERITY_BADGE.MINOR}>{t('glossary.minorChip')}</Badge>}>
          {t('glossary.minorBody')}
        </Term>
        <Term chip={<Badge className={SEVERITY_BADGE.INFO}>{t('glossary.infoChip')}</Badge>}>
          {t('glossary.infoBody')}
        </Term>
      </Section>

      <Section title={t('glossary.confidenceTitle')}
        subtitle={t('glossary.confidenceSubtitle')}>
        <Term chip={<span className="text-[13px] font-medium text-ink-900">{CONFIDENCE_LABEL.HIGH}</span>}>
          {t('glossary.confidenceHighBody')}
        </Term>
        <Term chip={<span className="text-[13px] font-medium text-ink-900">{CONFIDENCE_LABEL.MEDIUM}</span>}>
          {t('glossary.confidenceMediumBody')}
        </Term>
        <Term chip={<span className="text-[13px] font-medium text-warning">{CONFIDENCE_LABEL.LOW}</span>}>
          <Trans i18nKey="glossary.confidenceLowBody">
            A reasonable suspicion that needs a human to confirm. A <strong>high-severity finding marked low
            confidence</strong> is flagged with a warning icon — verify it before you treat it as a blocker.
          </Trans>
        </Term>
      </Section>

      <Section title={t('glossary.analysisAreaTitle')}
        subtitle={t('glossary.analysisAreaSubtitle')}>
        {Object.entries(LAYER_LABEL).map(([code, label]) => (
          <Term key={code} chip={<span className="text-[13px] font-medium text-ink-900">{label}</span>}>
            {t(`glossary.layerBlurb${code}`)}
          </Term>
        ))}
      </Section>

      <Section title={t('glossary.reviewStatusTitle')}
        subtitle={t('glossary.reviewStatusSubtitle')}>
        <Term chip={<Badge className={TONE.ok}>{t('glossary.acceptedChip')}</Badge>}>
          {t('glossary.acceptedBody')}
        </Term>
        <Term chip={<Badge className={TONE.muted}>{t('glossary.rejectedChip')}</Badge>}>
          {t('glossary.rejectedBody')}
        </Term>
        <Term chip={<Badge className={TONE.ok}>{t('glossary.defectRaisedChip')}</Badge>}>
          {t('glossary.defectRaisedBody')}
        </Term>
      </Section>

      <Section title={t('glossary.reportMetricsTitle')}
        subtitle={t('glossary.reportMetricsSubtitle')}>
        <Term chip={<span className="text-[13px] font-medium text-ink-900">{t('glossary.contractFidelityChip')}</span>}>
          {t('glossary.contractFidelityBody')}
        </Term>
        <Term chip={<span className="text-[13px] font-medium text-ink-900">{t('glossary.analysisCoverageChip')}</span>}>
          <Trans i18nKey="glossary.analysisCoverageBody">
            How much of the service Veritas was able to examine. <strong>Full</strong> means every source it needed
            was available; <strong>Partial</strong> means something (a security source, an exception handler, a DTO)
            wasn't supplied, so some checks were skipped.
          </Trans>
        </Term>
        <Term chip={<span className="text-[13px] font-medium text-ink-900">{t('glossary.estAnalysisCostChip')}</span>}>
          {t('glossary.estAnalysisCostBody')}
        </Term>
      </Section>
    </div>
  );
}
