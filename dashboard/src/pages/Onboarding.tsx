import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { CheckCircle2, Circle, AlertTriangle, Settings as SettingsIcon, ShieldCheck, Github } from 'lucide-react';
import { api } from '../api';
import { Button, Card, CardBody, CardHeader, PageHeader, Spinner } from '../components/ui';

/** First-run guided setup — a friendlier, step-by-step companion to the Settings page. */
export function Onboarding() {
  const { t } = useTranslation();
  const preflight = useQuery({ queryKey: ['preflight'], queryFn: api.preflight });
  const copilot = useQuery({ queryKey: ['copilot'], queryFn: api.copilotStatus });

  const checks = preflight.data ?? [];
  const ready = checks.length > 0 && checks.every((c) => c.status === 'OK') && copilot.data?.authenticated;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title={t('onboarding.pageTitle')}
        subtitle={t('onboarding.pageSubtitle')} />

      <Card className="mb-6">
        <CardHeader title={t('onboarding.getSetUpTitle')} subtitle={t('onboarding.getSetUpSubtitle')} />
        <CardBody className="space-y-4">
          <Step n={1} done={!!copilot.data?.authenticated} loading={copilot.isLoading}
            icon={Github} title={t('onboarding.step1Title')}
            body={t('onboarding.step1Body')}
            action={<Link to="/settings"><Button size="sm" variant="secondary">{t('onboarding.openSettings')}</Button></Link>} />

          <Step n={2} done={checks.some((c) => c.name.toLowerCase().includes('bitbucket') && c.status === 'OK')}
            loading={preflight.isLoading} icon={SettingsIcon} title={t('onboarding.step2Title')}
            body={t('onboarding.step2Body')}
            action={<Link to="/settings"><Button size="sm" variant="secondary">{t('onboarding.configure')}</Button></Link>} />

          <Step n={3} done={checks.filter((c) => c.status !== 'OK').length === 0 && checks.length > 0}
            loading={preflight.isLoading} icon={SettingsIcon} title={t('onboarding.step3Title')}
            body={t('onboarding.step3Body')}
            action={<Link to="/settings"><Button size="sm" variant="secondary">{t('onboarding.configure')}</Button></Link>} />
        </CardBody>
      </Card>

      <Card>
        <CardBody className="flex items-center justify-between gap-4">
          <div className="flex items-start gap-3">
            {ready ? <CheckCircle2 className="mt-0.5 h-5 w-5 text-success" /> : <AlertTriangle className="mt-0.5 h-5 w-5 text-warning" />}
            <div>
              <p className="text-sm font-semibold text-ink-900">{ready ? t('onboarding.readyTitle') : t('onboarding.notReadyTitle')}</p>
              <p className="mt-0.5 text-sm text-muted">
                {ready ? t('onboarding.readyBody')
                  : t('onboarding.notReadyBody')}
              </p>
            </div>
          </div>
          <Link to="/repos"><Button><ShieldCheck className="h-4 w-4" /> {t('onboarding.validateContract')}</Button></Link>
        </CardBody>
      </Card>

      {(preflight.isLoading || copilot.isLoading) && <div className="mt-4 flex justify-center"><Spinner /></div>}
    </div>
  );
}

function Step({ n, done, loading, icon: Icon, title, body, action }: {
  n: number; done: boolean; loading: boolean; icon: React.ComponentType<{ className?: string }>;
  title: string; body: string; action: React.ReactNode;
}) {
  return (
    <div className="flex items-start gap-4 rounded-lg border border-border p-4">
      <div className="mt-0.5">
        {loading ? <Circle className="h-5 w-5 text-muted/40" />
          : done ? <CheckCircle2 className="h-5 w-5 text-success" /> : <Circle className="h-5 w-5 text-muted/50" />}
      </div>
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <span className="text-muted">{n}.</span><Icon className="h-4 w-4 text-muted" /> {title}
        </p>
        <p className="mt-1 text-sm text-muted">{body}</p>
      </div>
      <div className="shrink-0">{action}</div>
    </div>
  );
}
