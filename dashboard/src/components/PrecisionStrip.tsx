import { useTranslation } from 'react-i18next';
import { UserCheck } from 'lucide-react';
import type { ExecutiveSummary } from '../api';
import { Card, CardBody } from './ui';

/**
 * The trust line: engineers reviewed the AI's findings and accepted most of them — and the AI disputed some
 * of its own before any human did. Scoped to each service's latest scan (the executive summary already
 * de-duplicates carry-forward), rendered only once someone has actually reviewed something.
 */
export function PrecisionStrip({ summary }: { summary: ExecutiveSummary }) {
  const { t } = useTranslation();
  const d = summary.dispositions;
  if (d.reviewed === 0) {
    return null;
  }
  // "Decided" is every finding a human ruled on — accepted, rejected, OR raised straight to Jira (an implicit
  // acceptance). The kept rate is (accepted + jiraCreated) / decided, and the copy states that base explicitly.
  const decided = d.accepted + d.rejected + d.jiraCreated;
  const acceptedPct = decided === 0 ? null : Math.round(((d.accepted + d.jiraCreated) / decided) * 100);
  return (
    <Card className="mb-6 border-l-4 border-l-gold">
      <CardBody className="flex flex-wrap items-center gap-x-2 gap-y-1 py-3.5 text-sm text-ink-700">
        <UserCheck className="h-4 w-4 shrink-0 text-gold" aria-hidden="true" />
        <span>
          {acceptedPct != null
            ? t('precision.summary', { reviewed: d.reviewed, decided, pct: acceptedPct, jira: d.jiraCreated })
            : t('precision.summaryNoRate', { reviewed: d.reviewed, jira: d.jiraCreated })}
        </span>
        {d.aiDisputed > 0 && (
          <span className="text-muted">{t('precision.disputed', { count: d.aiDisputed })}</span>
        )}
      </CardBody>
    </Card>
  );
}
