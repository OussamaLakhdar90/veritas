import { useQuery } from '@tanstack/react-query';
import { Database, FlaskConical } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';

/**
 * Header pill that warns when the active engine is the mock — so simulated fidelity scores and findings are never
 * mistaken for real analysis by a stakeholder glancing at a result page. Renders nothing in a real-engine session.
 */
export function EngineBadge() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['llm-settings'], queryFn: api.llmSettings });
  const simulated = q.data?.simulated || q.data?.active === 'mock';
  const seeded = q.data?.seeded === true;
  if (!simulated && !seeded) return null;
  return (
    <span className="inline-flex items-center gap-1.5">
      {simulated && (
        <span
          title={t('engine.mockTooltip')}
          className="inline-flex items-center gap-1 rounded-full bg-warning/10 px-2.5 py-1 text-2xs font-medium text-warning ring-1 ring-warning/30">
          <FlaskConical className="h-3 w-3" /> {t('engine.simulatedData')}
        </span>
      )}
      {seeded && (
        <span
          title={t('engine.seededTooltip')}
          className="inline-flex items-center gap-1 rounded-full bg-gold/10 px-2.5 py-1 text-2xs font-medium text-gold ring-1 ring-gold/30">
          <Database className="h-3 w-3" /> {t('engine.seededData')}
        </span>
      )}
    </span>
  );
}
