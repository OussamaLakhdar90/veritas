import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { AnimatePresence, motion } from 'framer-motion';
import { CheckCircle2, Radio, X } from 'lucide-react';
import type { Scan } from '../api';
import { Card, CardBody, useCountUp } from './ui';
import { isTestEnv, overlaySpring, exitEase } from '../lib/motion';
import { STAGE_ORDER, SCAN_STEPS, formatElapsed, stagePct, useElapsed } from '../lib/scanStages';

/**
 * The live-machine hero: pinned while any validation is RUNNING, streaming the pipeline's own progress
 * (stage + the AI's live detail + model + elapsed) so the slow AI step never reads as dead air. When a scan
 * completes on screen, the row morphs into a result strip and the fidelity score counts up live — proof this
 * is real-time analysis, not a report generated last night. Completion also refreshes the executive summary
 * so the hero ring and the release-safe banner move together.
 */
export function LiveScanRow({ scans }: { scans: Scan[] }) {
  const qc = useQueryClient();
  const running = scans.filter((s) => (s.status || '').toUpperCase() === 'RUNNING');
  const [flash, setFlash] = useState<Scan | null>(null);
  const prevStatus = useRef<Map<string, string>>(new Map());

  useEffect(() => {
    for (const s of scans) {
      const prev = prevStatus.current.get(s.id);
      const now = (s.status || '').toUpperCase();
      if (prev === 'RUNNING' && now === 'COMPLETED') {
        setFlash(s);
        // The hero ring, release-safe banner and breaking KPI must move with the new score — together.
        qc.invalidateQueries({ queryKey: ['executive-summary'] });
        qc.invalidateQueries({ queryKey: ['services'] });
      }
      prevStatus.current.set(s.id, now);
    }
  }, [scans, qc]);

  // Tests keep the plain early return (jsdom rAF doesn't tick, and the "renders nothing" test asserts no gold
  // strip when idle). In the app the container stays mounted past this point so AnimatePresence can play the
  // LAST strip's exit before the row empties — otherwise the final dismissal blinks.
  if (isTestEnv) {
    if (running.length === 0 && !flash) return null;
    return (
      <div className="mb-6 space-y-3">
        {flash && <CompletedStrip scan={flash} onDismiss={() => setFlash(null)} />}
        {running.map((s) => <RunningStrip key={s.id} scan={s} />)}
      </div>
    );
  }
  const empty = running.length === 0 && !flash;
  return (
    <div className={empty ? '' : 'mb-6 space-y-3'}>
      <AnimatePresence initial={false}>
        {flash && (
          // Shared layoutId with the running strip of the same scan — framer morphs the gold RunningStrip into
          // this green CompletedStrip (position + size) instead of a hard swap. Exit collapses its height so the
          // row closes smoothly when dismissed.
          <motion.div key={`live-${flash.id}`} layoutId={`live-${flash.id}`}
            exit={{ opacity: 0, height: 0, marginBottom: 0, transition: { duration: 0.16, ease: exitEase } }}
            transition={overlaySpring}>
            <CompletedStrip scan={flash} onDismiss={() => setFlash(null)} />
          </motion.div>
        )}
        {running.map((s) => (
          // The just-completed scan is rendered as the CompletedStrip above (same layoutId) — skip it here so
          // the two don't fight over the shared id.
          !(flash && flash.id === s.id) && (
            <motion.div key={`live-${s.id}`} layoutId={`live-${s.id}`}
              exit={{ opacity: 0, transition: { duration: 0.14, ease: exitEase } }}
              transition={overlaySpring}>
              <RunningStrip scan={s} />
            </motion.div>
          )
        ))}
      </AnimatePresence>
    </div>
  );
}

function RunningStrip({ scan }: { scan: Scan }) {
  const { t } = useTranslation();
  const stage = scan.stage ?? 'QUEUED';
  const startedMs = scan.startedAt ? new Date(scan.startedAt).getTime() : null;
  const elapsed = useElapsed(startedMs, true);
  const stepNo = Math.min(STAGE_ORDER[stage] ?? 0, SCAN_STEPS.length);
  return (
    <Card className="border-l-4 border-l-gold">
      <CardBody className="py-4">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <Radio className="h-4 w-4 motion-safe:animate-pulse text-gold" aria-hidden="true" />
          <span className="font-semibold text-ink-900">{scan.serviceName}</span>
          <span className="text-sm text-muted">
            {t('live.step', { n: Math.max(1, stepNo), m: SCAN_STEPS.length, label: t(`scan.${stage}.short`, { defaultValue: stage }) })}
          </span>
          {scan.model && <span className="rounded-full bg-ink-50 px-2 py-0.5 text-2xs text-ink-700 ring-1 ring-border">{scan.model}</span>}
          <span className="ml-auto text-sm tabular-nums text-muted">{formatElapsed(elapsed)}</span>
        </div>
        {scan.stageDetail && <p className="mt-1.5 truncate text-sm text-muted">{scan.stageDetail}</p>}
        <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-ink-100">
          <div className="h-full rounded-full bg-gradient-to-r from-gold to-brand-700 transition-[width] duration-progress ease-calm"
            style={{ width: `${stagePct(stage)}%` }} />
        </div>
      </CardBody>
    </Card>
  );
}

function CompletedStrip({ scan, onDismiss }: { scan: Scan; onDismiss: () => void }) {
  const { t } = useTranslation();
  const score = useCountUp(scan.fidelityScore ?? 0);
  return (
    <Card className="border-l-4 border-l-success">
      <CardBody className="flex flex-wrap items-center gap-3 py-4">
        <CheckCircle2 className="h-5 w-5 shrink-0 text-success" aria-hidden="true" />
        <span className="font-semibold text-ink-900">
          {scan.fidelityScore != null
            ? t('live.done', { service: scan.serviceName, score })
            : t('live.doneNoScore', { service: scan.serviceName })}
        </span>
        <Link to={`/findings/${scan.id}`} className="text-sm font-medium text-gold hover:underline">
          {t('live.view')}
        </Link>
        <button type="button" onClick={onDismiss} aria-label={t('live.dismiss')}
          className="ml-auto rounded-md p-1 text-muted hover:bg-ink-50 hover:text-ink-900">
          <X className="h-4 w-4" />
        </button>
      </CardBody>
    </Card>
  );
}
