/** Shared motion presets so transitions feel consistent + premium across the app (framer-motion). */

/** Under Vitest the jsdom rAF loop doesn't tick reliably, so motion components resolve to their final state. */
export const isTestEnv = import.meta.env.MODE === 'test';

/** A calm ease-out used for page enters + most reveals. */
export const easeOut = [0.2, 0.7, 0.3, 1] as const;

/** Page (route) enter — a short fade + small upward settle. */
export const pageTransition = { duration: 0.24, ease: easeOut };

/** The sliding active-nav highlight — a snappy, slightly-overdamped spring. */
export const navSpring = { type: 'spring', stiffness: 520, damping: 42 } as const;

/** Staggered list/grid reveal (KPI tiles, cards). */
export const stagger = {
  hidden: {},
  show: { transition: { staggerChildren: 0.06 } },
};
export const riseItem = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: pageTransition },
};
