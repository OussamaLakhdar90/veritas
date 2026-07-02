/** Shared motion presets — ONE vocabulary for the whole app (framer-motion). Motion directs attention, never
 *  decorates: entries are calm ease-outs, exits are always FASTER than entries, stagger is a reading-order aid
 *  capped at 8 children, and reduced-motion users get the finished state instantly (never a slower animation). */

/** Under Vitest the jsdom rAF loop doesn't tick reliably, so motion components resolve to their final state. */
export const isTestEnv = import.meta.env.MODE === 'test';

/** Named durations (seconds). fast = hover/press, base = reveals, slow = overlay emphasis, progress = bars,
 *  chart sweeps and count-ups. Mirrored in tailwind.config (duration-fast / duration-base). */
export const dur = { fast: 0.15, base: 0.24, slow: 0.4, progress: 0.7 } as const;

/** A calm ease-out used for page enters + most reveals. */
export const easeOut = [0.2, 0.7, 0.3, 1] as const;

/** All exits — steeper AND faster (0.12–0.16s) than entries, so dismissals feel immediate. */
export const exitEase = [0.4, 0, 1, 1] as const;

/** Page (route) enter — a short fade + small upward settle. */
export const pageTransition = { duration: dur.base, ease: easeOut };

/** The sliding active-nav highlight — a snappy, slightly-overdamped spring. */
export const navSpring = { type: 'spring', stiffness: 520, damping: 42 } as const;

/** Modal / command-palette panels settle in on this spring (one overlay feel everywhere). */
export const overlaySpring = { type: 'spring', stiffness: 380, damping: 30, mass: 0.9 } as const;

/** Toast entry — slightly snappier than the overlay spring (a toast interrupts, it doesn't hold court). */
export const toastSpring = { type: 'spring', stiffness: 480, damping: 34 } as const;

/** Staggered list/grid reveal (KPI tiles, cards) — 60ms between children. */
export const stagger = {
  hidden: {},
  show: { transition: { staggerChildren: 0.06 } },
};
export const riseItem = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: pageTransition },
};

/** Table-row entrance delay: 40ms per row, hard-capped at 8 — row 9+ appears instantly. Variants don't
 *  propagate through a plain <tbody>, so rows use an index delay instead of staggerChildren. */
export const rowDelay = (index: number): number => Math.min(index, 8) * 0.04;
