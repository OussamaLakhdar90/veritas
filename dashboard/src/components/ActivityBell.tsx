import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { AnimatePresence, motion } from 'framer-motion';
import { Bell } from 'lucide-react';
import { api } from '../api';
import { useActivityCenter } from '../lib/activityCenter';
import { isTestEnv, overlaySpring, exitEase } from '../lib/motion';
import { cn } from './cn';

/**
 * The ONE TopBar bell. Badge count = unseen Snyk alerts (same query key as the Snyk page, so a dismissal
 * there updates the badge) + activity items that need attention. Red + pulsing when any unseen alert is
 * Critical. Clicking toggles a small popover (Escape / outside click closes) with an Activity section —
 * each row links to the task and acks it — and a Security row that jumps to the Snyk page.
 */
export function ActivityBell() {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  const q = useQuery({
    queryKey: ['snyk-alerts', 'unseen'],
    queryFn: () => api.snykAlerts(true),
    refetchInterval: 30000,
    refetchOnWindowFocus: true,
  });
  const alerts = q.data ?? [];
  const hasCritical = alerts.some((a) => a.severity?.toLowerCase() === 'critical');

  const { items, ack } = useActivityCenter();
  const attention = items.filter((i) => i.needsAttention && !i.acked);
  const count = alerts.length + attention.length;

  // Escape and outside-click both close the popover while it is open.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('mousedown', onDown);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('mousedown', onDown);
    };
  }, [open]);

  const popoverCls = 'absolute right-0 top-full z-50 mt-2 w-80 rounded-xl bg-surface p-3 shadow-pop ring-1 ring-border';
  const popoverBody = (
    <>
      {attention.length > 0 && (
        <section>
          <p className="px-2 pb-1 text-2xs font-semibold uppercase tracking-wider text-muted">{t('activity.title')}</p>
          <ul>
            {attention.map((item) => (
              <li key={item.id}>
                <Link to={item.link} onClick={() => { ack([item.id]); setOpen(false); }}
                  className="block truncate rounded-lg px-2 py-1.5 text-sm text-ink-900 hover:bg-ink-50">
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
      {alerts.length > 0 && (
        <section className={cn(attention.length > 0 && 'mt-2 border-t border-border pt-2')}>
          <p className="px-2 pb-1 text-2xs font-semibold uppercase tracking-wider text-muted">{t('activity.security')}</p>
          <Link to="/snyk" onClick={() => setOpen(false)}
            className="block rounded-lg px-2 py-1.5 text-sm text-ink-900 hover:bg-ink-50">
            {t('activity.alertsRow', { count: alerts.length })}
          </Link>
        </section>
      )}
      {attention.length === 0 && alerts.length === 0 && (
        <p className="px-2 py-1.5 text-sm text-muted">{t('activity.empty')}</p>
      )}
    </>
  );

  return (
    <div ref={rootRef} className="relative">
      <button type="button" onClick={() => setOpen((o) => !o)} aria-label={t('activity.bellAria')}
        aria-haspopup="true" aria-expanded={open}
        className={cn('relative grid h-9 w-9 shrink-0 place-items-center rounded-md hover:bg-ink-50',
          hasCritical ? 'text-danger' : 'text-ink-600')}>
        <Bell className="h-4.5 w-4.5" />
        {count > 0 && (
          <span className={cn(
            'absolute -right-0.5 -top-0.5 grid h-4 min-w-[16px] place-items-center rounded-full px-1 text-2xs font-bold text-white',
            hasCritical ? 'bg-danger' : 'bg-info')}>
            {count > 9 ? '9+' : count}
          </span>
        )}
        {/* A pulsing ring when a Critical alert is unseen — draws the eye without being obnoxious. */}
        {hasCritical && (
          <span className="absolute -right-0.5 -top-0.5 h-4 w-4 motion-safe:animate-ping rounded-full bg-danger/40" aria-hidden="true" />
        )}
      </button>

      {/* Test keeps the plain conditional (jsdom rAF doesn't tick + the Escape test asserts synchronous unmount);
          the app settles the popover in on the shared overlay spring and fades it out. */}
      {isTestEnv
        ? (open && <div className={popoverCls}>{popoverBody}</div>)
        : (
          <AnimatePresence>
            {open && (
              <motion.div className={popoverCls}
                initial={{ opacity: 0, y: -6, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, scale: 0.98, transition: { duration: 0.12, ease: exitEase } }}
                transition={overlaySpring}>
                {popoverBody}
              </motion.div>
            )}
          </AnimatePresence>
        )}
    </div>
  );
}
