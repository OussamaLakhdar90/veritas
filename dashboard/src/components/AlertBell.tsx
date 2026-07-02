import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bell } from 'lucide-react';
import { api } from '../api';
import { cn } from './cn';

/**
 * App-wide Snyk alert bell in the TopBar: polls the unseen-alert feed (shared query key with the Snyk page, so a
 * dismissal there updates the bell), shows the count, and turns red when any unseen alert is Critical. Clicking
 * jumps to the Snyk page. Silent when there are no alerts.
 */
export function AlertBell() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const q = useQuery({
    queryKey: ['snyk-alerts', 'unseen'],
    queryFn: () => api.snykAlerts(true),
    refetchInterval: 30000,
    refetchOnWindowFocus: true,
  });
  const alerts = q.data ?? [];
  const count = alerts.length;
  const hasCritical = alerts.some((a) => a.severity?.toLowerCase() === 'critical');

  return (
    <button type="button" onClick={() => navigate('/snyk')} aria-label={t('header.alerts', { count })}
      className={cn('relative grid h-9 w-9 shrink-0 place-items-center rounded-md hover:bg-ink-50',
        hasCritical ? 'text-danger' : 'text-ink-600')}>
      <Bell className="h-[18px] w-[18px]" />
      {count > 0 && (
        <span className={cn(
          'absolute -right-0.5 -top-0.5 grid h-4 min-w-[16px] place-items-center rounded-full px-1 text-[10px] font-bold text-white',
          hasCritical ? 'bg-danger' : 'bg-brand')}>
          {count > 9 ? '9+' : count}
        </span>
      )}
      {/* A pulsing ring when a Critical alert is unseen — draws the eye without being obnoxious. */}
      {hasCritical && (
        <span className="absolute -right-0.5 -top-0.5 h-4 w-4 animate-ping rounded-full bg-danger/40" aria-hidden="true" />
      )}
    </button>
  );
}
