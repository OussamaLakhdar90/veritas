import { useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Coins, Download } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { Card, CardBody, CardHeader, EmptyState, ErrorState, FreshnessStamp, KpiTile, PageHeader, Skeleton, Table, Td, Th, Row } from '../components/ui';
import { TrendChart } from '../components/charts';
import { downloadTrendCsv } from '../lib/exportCsv';
import { enumLabel } from '../lib/enumLabels';
import { formatMoney } from '../lib/format';

export function Costs() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['costs'], queryFn: api.costSummary });
  const trendQ = useQuery({ queryKey: ['cost-trend'], queryFn: () => api.costTrend(30) });
  const s = q.data;
  const rows = Object.entries(s?.bySkill ?? {}).sort((a, b) => b[1] - a[1]);
  const max = rows.length ? rows[0][1] : 0;
  const trend = useMemo(() => (trendQ.data ?? []).map((p) => ({ date: p.date, value: p.totalUsd })), [trendQ.data]);

  const refreshing = q.isFetching || trendQ.isFetching;

  return (
    <div>
      <PageHeader title={t('costs.pageTitle')} subtitle={t('costs.pageSubtitle')}
        actions={<FreshnessStamp updatedAt={q.dataUpdatedAt} refreshing={refreshing}
          onRefresh={() => { qc.invalidateQueries({ queryKey: ['costs'] }); qc.invalidateQueries({ queryKey: ['cost-trend'] }); }} />} />

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

      {/* Daily spend trend — the "where is this heading" line a budget owner reads first. */}
      {trend.length >= 2 && (
        <Card className="mb-6">
          <CardHeader title={t('costs.trendTitle')} subtitle={t('costs.trendSubtitle')}
            action={<button type="button" aria-label={t('overview.exportCsv')} title={t('overview.exportCsv')}
              onClick={() => downloadTrendCsv('veritas-cost-trend', t('overview.csvDate'), t('costs.csvSpend'), trend)}
              className="grid h-8 w-8 place-items-center rounded-md text-ink-600 hover:bg-ink-50"><Download className="h-4 w-4" /></button>} />
          <CardBody>
            <TrendChart points={trend} ariaLabel={t('costs.trendTitle')} tone="brand"
              format={(v) => formatMoney(v, v < 1 ? 4 : 2)} />
          </CardBody>
        </Card>
      )}

      <Card>
        <CardHeader title={t('costs.bySkillTitle')} subtitle={t('costs.bySkillSubtitle')} />
        <CardBody className="p-0">
          {q.isLoading ? (
            <div className="space-y-2 p-5">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-8" />)}</div>
          ) : q.isError ? (
            <div className="p-5"><ErrorState detail={(q.error as Error).message} /></div>
          ) : rows.length === 0 ? (
            <div className="p-5"><EmptyState icon={Coins} title={t('costs.emptyTitle')} body={t('costs.emptyBody')} /></div>
          ) : (
            <Table head={<><Th>{t('costs.colSkill')}</Th><Th className="w-1/2">{t('costs.colShare')}</Th><Th className="text-right">{t('costs.colEstCost')}</Th></>}>
              {rows.map(([skill, cost], i) => (
                <Row key={skill} index={i}>
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
