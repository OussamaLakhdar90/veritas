import { useQuery } from '@tanstack/react-query';
import { Coins } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { Card, CardBody, CardHeader, EmptyState, ErrorState, KpiTile, PageHeader, Skeleton, Table, Td, Th, Row } from '../components/ui';
import { enumLabel } from '../lib/enumLabels';
import { formatMoney } from '../lib/format';

export function Costs() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['costs'], queryFn: api.costSummary });
  const s = q.data;
  const rows = Object.entries(s?.bySkill ?? {}).sort((a, b) => b[1] - a[1]);
  const max = rows.length ? rows[0][1] : 0;

  return (
    <div>
      <PageHeader title={t('costs.pageTitle')} subtitle={t('costs.pageSubtitle')} />

      <div className="mb-6 grid grid-cols-2 gap-4 sm:max-w-md">
        {q.isLoading ? (
          <><Skeleton className="h-28" /><Skeleton className="h-28" /></>
        ) : (
          <>
            <KpiTile label={t('costs.totalEstCost')} value={formatMoney(s?.totalEstCostUsd ?? 0, 4)} tone="brand" />
            <KpiTile label={t('costs.llmActions')} value={s?.actions ?? 0} />
          </>
        )}
      </div>

      <Card>
        <CardHeader title={t('costs.bySkillTitle')} subtitle={t('costs.bySkillSubtitle')} />
        <CardBody className="p-0">
          {q.isLoading ? (
            <div className="space-y-2 p-5">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-8" />)}</div>
          ) : q.isError ? (
            <div className="p-5"><ErrorState message={(q.error as Error).message} /></div>
          ) : rows.length === 0 ? (
            <div className="p-5"><EmptyState icon={Coins} title={t('costs.emptyTitle')} body={t('costs.emptyBody')} /></div>
          ) : (
            <Table head={<><Th>{t('costs.colSkill')}</Th><Th className="w-1/2">{t('costs.colShare')}</Th><Th className="text-right">{t('costs.colEstCost')}</Th></>}>
              {rows.map(([skill, cost]) => (
                <Row key={skill}>
                  <Td className="font-medium text-ink-900">{enumLabel(t, 'skill', skill)}</Td>
                  <Td>
                    <div className="h-2 w-full rounded-full bg-ink-100">
                      <div className="h-2 rounded-full bg-brand" style={{ width: `${max ? Math.max(4, (cost / max) * 100) : 0}%` }} />
                    </div>
                  </Td>
                  <Td className="text-right tabular-nums text-ink-900">{formatMoney(cost, 4)}</Td>
                </Row>
              ))}
            </Table>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
