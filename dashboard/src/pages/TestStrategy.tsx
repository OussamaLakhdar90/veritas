import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardList, Play, FileText, ExternalLink, ScrollText, ArrowRight } from 'lucide-react';
import { api } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Field, PageHeader, Select, TableSkeleton, Table, Td, Th, Row, Textarea } from '../components/ui';
import { useToast } from '../components/Toast';
import { ServiceField } from '../components/ServiceField';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';
import { enumLabel } from '../lib/enumLabels';
import { formatDateTime } from '../lib/format';

/** ISTQB Test-Manager strategy wizard: synthesize a structured strategy from a basis, then review versions. */
export function TestStrategy() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  // Deep link support: /test-strategy?service=X lands ON the service's list (seeding `service` alone
  // would show an empty form — the list renders off `loaded`).
  const params = new URLSearchParams(useLocation().search);
  const initialService = params.get('service') ?? '';
  const [service, setService] = useState(initialService);
  const [loaded, setLoaded] = useState(initialService);
  const [basis, setBasis] = useState('');
  const [source, setSource] = useState('CODE');

  const { blocked, notice } = useCopilotGate();
  const list = useQuery({ queryKey: ['strategies', loaded], queryFn: () => api.strategies(loaded), enabled: !!loaded });

  const generate = useMutation({
    mutationFn: () => api.generateStrategy(service, { basis, source }),
    onSuccess: () => {
      setLoaded(service);
      qc.invalidateQueries({ queryKey: ['strategies', service] });
      toast.push('success', t('testStrategy.strategyGenerated'));
    },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const rows = list.data ?? [];
  return (
    <div>
      <PageHeader title={t('testStrategy.pageTitle')} subtitle={t('testStrategy.pageSubtitle')} />

      <Card className="mb-6">
        <CardHeader title={t('testStrategy.newStrategy')} subtitle={t('testStrategy.newStrategySubtitle')} />
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Field label={t('testStrategy.serviceLabel')} hint={t('testStrategy.serviceHint')}><ServiceField value={service} onChange={(e) => setService(e.target.value)} /></Field>
            <Field label={t('testStrategy.basisSourceLabel')}>
              <Select aria-label={t('testStrategy.basisSourceLabel')} value={source} onChange={(e) => setSource(e.target.value)}>
                <option value="CODE">{t('testStrategy.sourceCodebase')}</option>
                <option value="JIRA">{t('testStrategy.sourceJiraStories')}</option>
                <option value="CONFLUENCE">{t('testStrategy.sourceConfluence')}</option>
              </Select>
            </Field>
          </div>
          <Field label={t('testStrategy.basisLabel')} hint={t('testStrategy.basisHint')}>
            <Textarea placeholder={t('testStrategy.basisPlaceholder')} value={basis} onChange={(e) => setBasis(e.target.value)} />
          </Field>
          <div className="flex items-center justify-end gap-3">
            {notice}
            <Button loading={generate.isPending} disabled={blocked}
              onClick={() => (service && basis.trim()) ? generate.mutate() : toast.push('error', t('testStrategy.serviceAndBasisRequired'))}>
              <Play className="h-4 w-4" /> {t('testStrategy.generateStrategy')}
            </Button>
          </div>
        </CardBody>
      </Card>

      {!loaded ? (
        <EmptyState icon={ClipboardList} title={t('testStrategy.noStrategyYet')} body={t('testStrategy.noStrategyYetBody')} />
      ) : list.isLoading ? (
        <Card><CardBody className="p-0"><TableSkeleton label={t('testStrategy.loading')} /></CardBody></Card>
      ) : rows.length === 0 ? (
        <EmptyState icon={ClipboardList} title={t('testStrategy.noStrategiesForService', { service: loaded })} />
      ) : (
        <Card>
          <CardHeader title={t('testStrategy.strategiesForService', { service: loaded })} />
          <CardBody className="p-0">
            <Table head={<><Th>{t('testStrategy.columnStatus')}</Th><Th className="text-right">{t('testStrategy.columnConfidence')}</Th><Th>{t('testStrategy.columnCreated')}</Th><Th /></>}>
              {rows.map((s) => (
                <Row key={s.id}>
                  <Td><Badge className={s.status === 'APPROVED' ? TONE.ok : TONE.info}>{enumLabel(t, 'strategyStatus', s.status ?? 'DRAFT')}</Badge></Td>
                  <Td className="text-right tabular-nums text-ink-900">{s.confidence != null ? `${Math.round(s.confidence)}%` : '—'}</Td>
                  <Td className="text-muted">{formatDateTime(s.createdAt)}</Td>
                  <Td className="text-right">
                    <div className="inline-flex items-center gap-4">
                      {s.source === 'multi-source' && (
                        <a href={api.strategyWhyDocUrl(s.id)} target="_blank" rel="noreferrer"
                          title={t('testStrategy.evidenceTooltip')}
                          className="inline-flex items-center gap-1 text-sm font-medium text-gold hover:underline">
                          <ScrollText className="h-3.5 w-3.5" /> {t('testStrategy.evidence')} <ExternalLink className="h-3 w-3" />
                        </a>
                      )}
                      <a href={api.strategyRationaleUrl(s.id)} target="_blank" rel="noreferrer"
                        className="inline-flex items-center gap-1 text-sm font-medium text-gold hover:underline">
                        <FileText className="h-3.5 w-3.5" /> {t('testStrategy.rationale')} <ExternalLink className="h-3 w-3" />
                      </a>
                      <Link to={`/test-strategy/${s.id}`}
                        className="inline-flex items-center gap-1 text-sm font-medium text-gold hover:underline">
                        {t('testStrategy.open')} <ArrowRight className="h-3.5 w-3.5" />
                      </Link>
                    </div>
                  </Td>
                </Row>
              ))}
            </Table>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
