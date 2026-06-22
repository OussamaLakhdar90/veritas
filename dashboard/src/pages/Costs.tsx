import { useQuery } from '@tanstack/react-query';
import { Coins } from 'lucide-react';
import { api } from '../api';
import { Card, CardBody, CardHeader, EmptyState, KpiTile, PageHeader, Skeleton, Table, Td, Th, Row } from '../components/ui';

export function Costs() {
  const q = useQuery({ queryKey: ['costs'], queryFn: api.costSummary });
  const s = q.data;
  const rows = Object.entries(s?.bySkill ?? {}).sort((a, b) => b[1] - a[1]);
  const max = rows.length ? rows[0][1] : 0;

  return (
    <div>
      <PageHeader title="LLM cost" subtitle="Estimated Copilot spend in this environment, broken down by skill." />

      <div className="mb-6 grid grid-cols-2 gap-4 sm:max-w-md">
        {q.isLoading ? (
          <><Skeleton className="h-28" /><Skeleton className="h-28" /></>
        ) : (
          <>
            <KpiTile label="Total est. cost" value={`$${(s?.totalEstCostUsd ?? 0).toFixed(4)}`} tone="brand" />
            <KpiTile label="LLM actions" value={s?.actions ?? 0} />
          </>
        )}
      </div>

      <Card>
        <CardHeader title="By skill" subtitle="Where the spend goes." />
        <CardBody className="p-0">
          {q.isLoading ? (
            <div className="space-y-2 p-5">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-8" />)}</div>
          ) : rows.length === 0 ? (
            <div className="p-5"><EmptyState icon={Coins} title="No spend yet" body="Run a skill that calls the LLM and its cost will be tracked here." /></div>
          ) : (
            <Table head={<><Th>Skill</Th><Th className="w-1/2">Share</Th><Th className="text-right">Est. cost (USD)</Th></>}>
              {rows.map(([skill, cost]) => (
                <Row key={skill}>
                  <Td className="font-medium text-ink-900">{skill}</Td>
                  <Td>
                    <div className="h-2 w-full rounded-full bg-ink-100">
                      <div className="h-2 rounded-full bg-brand" style={{ width: `${max ? Math.max(4, (cost / max) * 100) : 0}%` }} />
                    </div>
                  </Td>
                  <Td className="text-right tabular-nums text-ink-900">${cost.toFixed(4)}</Td>
                </Row>
              ))}
            </Table>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
