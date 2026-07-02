import { motion, useReducedMotion } from 'framer-motion';
import { isTestEnv, overlaySpring } from '../lib/motion';

/**
 * The app's ONLY celebration: a check that draws itself once, on true completion moments (a finished scan,
 * a merged fix train, generated tests that built green). No confetti — this is a bank. Static under tests
 * and reduced motion (MotionConfig does not neutralize pathLength, so the fallback is explicit).
 */
export function SuccessCheck({ className = 'h-5 w-5' }: { className?: string }) {
  const reduce = useReducedMotion();
  if (isTestEnv || reduce) {
    return (
      <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="10" className="stroke-success/40" strokeWidth="2" />
        <path d="M7 12.5l3.2 3.2L17 9" className="stroke-success" strokeWidth="2.5"
          strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }
  return (
    <motion.svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true"
      initial={{ scale: 0.6, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={overlaySpring}>
      <circle cx="12" cy="12" r="10" className="stroke-success/40" strokeWidth="2" />
      <motion.path d="M7 12.5l3.2 3.2L17 9" className="stroke-success" strokeWidth="2.5"
        strokeLinecap="round" strokeLinejoin="round"
        initial={{ pathLength: 0 }} animate={{ pathLength: 1 }} transition={{ duration: 0.4, delay: 0.1 }} />
    </motion.svg>
  );
}
