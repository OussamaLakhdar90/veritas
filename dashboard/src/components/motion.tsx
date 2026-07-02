import React from 'react';
import { motion } from 'framer-motion';
import { isTestEnv, pageTransition, riseItem, rowDelay, stagger } from '../lib/motion';

/**
 * Reusable entrance primitives — the ONLY way pages should animate lists and sections, so the whole app
 * shares one motion vocabulary. All of them render their finished state instantly under Vitest (jsdom rAF
 * never ticks) and inherit the app-wide `MotionConfig reducedMotion="user"` for accessibility.
 */

/** Fade + small upward settle on mount. */
export function FadeIn({ className, children }: { className?: string; children: React.ReactNode }) {
  if (isTestEnv) {
    return <div className={className}>{children}</div>;
  }
  return (
    <motion.div className={className} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}
      transition={pageTransition}>
      {children}
    </motion.div>
  );
}

/** Parent for a staggered reveal — children must be StaggerItem. Cap the child count at ~8 for lists. */
export function StaggerList({ className, children }: { className?: string; children: React.ReactNode }) {
  if (isTestEnv) {
    return <div className={className}>{children}</div>;
  }
  return (
    <motion.div className={className} variants={stagger} initial="hidden" animate="show">
      {children}
    </motion.div>
  );
}

export function StaggerItem({ className, children }: { className?: string; children: React.ReactNode }) {
  if (isTestEnv) {
    return <div className={className}>{children}</div>;
  }
  return (
    <motion.div className={className} variants={riseItem}>
      {children}
    </motion.div>
  );
}

/** Index-delayed table row — variants don't propagate through a plain <tbody>, so rows delay by index
 *  (40ms each, capped at 8: rows 9+ appear instantly — stagger is a reading-order aid, not a show). */
export function MotionRow({ index, className, children, ...rest }:
  React.HTMLAttributes<HTMLTableRowElement> & { index: number }) {
  if (isTestEnv) {
    return <tr className={className} {...rest}>{children}</tr>;
  }
  return (
    <motion.tr className={className} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
      transition={{ ...pageTransition, delay: rowDelay(index) }}
      {...(rest as React.ComponentProps<typeof motion.tr>)}>
      {children}
    </motion.tr>
  );
}
