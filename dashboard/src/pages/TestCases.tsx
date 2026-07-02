import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ListChecks, Search, Check, X, Upload, Sparkles } from 'lucide-react';
import { api, TestCase } from '../api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Field, Input, PageHeader, Spinner, Table, Td, Th, Row, Textarea } from '../components/ui';
import { useToast } from '../components/Toast';
import { ServiceField } from '../components/ServiceField';
import { useCopilotGate } from '../lib/copilotAuth';
import { TONE } from '../theme/tokens';
import { enumLabel } from '../lib/enumLabels';

const tone = (s?: string) => {
  const v = (s || '').toUpperCase();
  if (v === 'APPROVED' || v.startsWith('CREATED') || v === 'ATTACHED' || v === 'IMPLEMENTED') return TONE.ok;
  if (v === 'REJECTED') return TONE.danger;
  return TONE.info;
};

export function TestCases() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const { blocked, notice } = useCopilotGate();
  const [svc, setSvc] = useState('');
  const [query, setQuery] = useState('');
  const [projectKey, setProjectKey] = useState('');
  const [basis, setBasis] = useState('');

  const q = useQuery({ queryKey: ['test-cases', query], queryFn: () => api.testCases(query), enabled: !!query });

  const generate = useMutation({
    mutationFn: () => api.generateTestCases(svc, { basis }),
    onSuccess: () => { setQuery(svc); qc.invalidateQueries({ queryKey: ['test-cases', svc] }); toast.push('success', t('testCases.toastGenerated')); },
    onError: (e: Error) => toast.push('error', e.message),
  });

  const act = useMutation({
    mutationFn: ({ tc, fn }: { tc: TestCase; fn: () => Promise<unknown> }) => fn(),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['test-cases'] }); toast.push('success', t('testCases.toastUpdated')); },
    onError: (e: Error) => toast.push('error', e.message),
  });
  const busyId = act.isPending ? act.variables?.tc.id : undefined;
  const rows = q.data ?? [];

  return (
    <div>
      <PageHeader title={t('testCases.pageTitle')} subtitle={t('testCases.pageSubtitle')} />

      <Card className="mb-6">
        <CardBody>
          <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
            <div className="flex-1"><Field label={t('testCases.serviceLabel')}><ServiceField value={svc}
              onChange={(e) => setSvc(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && svc && setQuery(svc)} /></Field></div>
            <div className="flex-1"><Field label={t('testCases.projectKeyLabel')} hint={t('testCases.projectKeyHint')}><Input placeholder="CIAM" value={projectKey}
              onChange={(e) => setProjectKey(e.target.value.toUpperCase())} /></Field></div>
            <Button onClick={() => setQuery(svc)} disabled={!svc} className="sm:mb-0.5"><Search className="h-4 w-4" /> {t('testCases.loadButton')}</Button>
          </div>
        </CardBody>
      </Card>

      <Card className="mb-6">
        <CardHeader title={t('testCases.generateTitle')} subtitle={t('testCases.generateSubtitle')} />
        <CardBody className="space-y-4">
          <Field label={t('testCases.basisLabel')} hint={t('testCases.basisHint')}>
            <Textarea placeholder={t('testCases.basisPlaceholder')} value={basis}
              onChange={(e) => setBasis(e.target.value)} />
          </Field>
          <div className="flex items-center justify-end gap-3">
            {notice}
            <Button loading={generate.isPending} disabled={blocked}
              onClick={() => (svc && basis.trim()) ? generate.mutate() : toast.push('error', t('testCases.validationRequired'))}>
              <Sparkles className="h-4 w-4" /> {t('testCases.generateButton')}
            </Button>
          </div>
        </CardBody>
      </Card>

      {!query ? (
        <EmptyState icon={ListChecks} title={t('testCases.emptyLoadTitle')} body={t('testCases.emptyLoadBody')} />
      ) : q.isLoading ? (
        <Card><CardBody className="flex items-center gap-2 text-sm text-muted"><Spinner /> {t('testCases.loading')}</CardBody></Card>
      ) : rows.length === 0 ? (
        <EmptyState icon={ListChecks} title={t('testCases.emptyNoCases', { query })} body={t('testCases.emptyNoCasesBody')} />
      ) : (
        <Card>
          <CardBody className="p-0">
            <Table head={<><Th>{t('testCases.colTitle')}</Th><Th>{t('testCases.colTechnique')}</Th><Th>{t('testCases.colStatus')}</Th><Th>{t('testCases.colXray')}</Th><Th className="text-right">{t('testCases.colActions')}</Th></>}>
              {rows.map((tc) => (
                <Row key={tc.id}>
                  <Td className="max-w-md font-medium text-ink-900">{tc.title}</Td>
                  <Td className="text-muted">{tc.technique ?? '—'}</Td>
                  <Td><Badge className={tone(tc.status)}>{enumLabel(t, 'caseStatus', tc.status)}</Badge></Td>
                  <Td className="font-mono text-xs text-muted">{tc.xrayKey ?? '—'}</Td>
                  <Td className="text-right whitespace-nowrap">
                    <span className="inline-flex gap-2">
                      <Button size="sm" variant="secondary" disabled={busyId === tc.id}
                        onClick={() => act.mutate({ tc, fn: () => api.patchTestCase(tc.id, { status: 'APPROVED', actor: 'dashboard' }) })}>
                        <Check className="h-4 w-4" /> {t('testCases.approveButton')}</Button>
                      <Button size="sm" variant="ghost" disabled={busyId === tc.id}
                        onClick={() => act.mutate({ tc, fn: () => api.patchTestCase(tc.id, { status: 'REJECTED' }) })}>
                        <X className="h-4 w-4" /> {t('testCases.rejectButton')}</Button>
                      <Button size="sm" variant="secondary" disabled={busyId === tc.id || !projectKey}
                        title={projectKey ? '' : t('testCases.pushTitleHint')}
                        onClick={() => act.mutate({ tc, fn: () => api.pushTestCase(tc.id, projectKey) })}>
                        <Upload className="h-4 w-4" /> {t('testCases.pushButton')}</Button>
                    </span>
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
