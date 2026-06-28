import { useQuery } from '@tanstack/react-query';
import { FlaskConical } from 'lucide-react';
import { api } from '../api';

/**
 * Header pill that warns when the active engine is the mock — so simulated fidelity scores and findings are never
 * mistaken for real analysis by a stakeholder glancing at a result page. Renders nothing in a real-engine session.
 */
export function EngineBadge() {
  const q = useQuery({ queryKey: ['llm-settings'], queryFn: api.llmSettings });
  const simulated = q.data?.simulated || q.data?.active === 'mock';
  if (!simulated) return null;
  return (
    <span
      title="The active engine is the mock — results are simulated, not real analysis. Switch engines in Settings."
      className="inline-flex items-center gap-1 rounded-full bg-warning/10 px-2.5 py-1 text-[11px] font-medium text-warning ring-1 ring-warning/30">
      <FlaskConical className="h-3 w-3" /> Simulated data
    </span>
  );
}
