import React from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { Info } from 'lucide-react';
import { cn } from './cn';
import { isTestEnv, overlaySpring } from '../lib/motion';

/**
 * The one hover/focus tooltip primitive — replaces load-bearing native `title=` (which is mouse-only, never
 * appears on keyboard focus, and can't be styled). Portalled so it escapes `overflow:hidden` cells, positioned
 * above its trigger, `role="tooltip"` + `aria-describedby` so screen readers announce it. Colour is
 * `bg-ink-900 text-bg` (NOT text-white — text-bg tracks the theme so the label stays readable in dark mode).
 *
 * Opens on pointer-enter AND focus-within, closes on leave/blur/Escape. Motion is the shared overlay spring and
 * collapses to an instant show under reduced-motion or in tests (jsdom rAF doesn't tick reliably).
 */
export function Tooltip({ label, children, className }:
  { label: React.ReactNode; children: React.ReactElement; className?: string }) {
  const reduce = useReducedMotion();
  const id = React.useId();
  const triggerRef = React.useRef<HTMLSpanElement>(null);
  const [open, setOpen] = React.useState(false);
  const [coords, setCoords] = React.useState<{ top: number; left: number } | null>(null);

  const show = React.useCallback(() => {
    const el = triggerRef.current;
    if (el) {
      const r = el.getBoundingClientRect();
      setCoords({ top: r.top, left: r.left + r.width / 2 });
    }
    setOpen(true);
  }, []);
  const hide = React.useCallback(() => setOpen(false), []);

  React.useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  // The trigger wrapper carries the hover/focus handlers and the aria link — it is inline so it doesn't disturb
  // layout. `tabIndex` is not forced here: the child (a button/link) is usually already focusable; when it is
  // plain text (InfoTip's icon button) the caller makes it a <button>.
  const bubble = coords && (
    <div className="pointer-events-none fixed z-[80] -translate-x-1/2 -translate-y-full pb-1.5"
      style={{ top: coords.top, left: coords.left }}>
      <motion.div id={id} role="tooltip"
        className={cn('max-w-xs rounded-md bg-ink-900 px-2.5 py-1.5 text-2xs font-medium text-bg shadow-pop', className)}
        initial={isTestEnv || reduce ? false : { opacity: 0, y: 4, scale: 0.96 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={isTestEnv || reduce ? { opacity: 0 } : { opacity: 0, y: 2, transition: { duration: 0.1 } }}
        transition={overlaySpring}>
        {label}
      </motion.div>
    </div>
  );

  return (
    // pointerover bubbles (so hovering a child of the trigger still opens it); pointerleave fires once the
    // pointer exits the trigger AND all its descendants. focus/blur cover keyboard users.
    <span ref={triggerRef} className="inline-flex" aria-describedby={open ? id : undefined}
      onPointerOver={show} onPointerLeave={hide} onFocus={show} onBlur={hide}>
      {children}
      {isTestEnv
        ? (open && coords ? createPortal(bubble, document.body) : null)
        : createPortal(<AnimatePresence>{open && bubble}</AnimatePresence>, document.body)}
    </span>
  );
}

/**
 * A small "ⓘ" affordance that reveals explanatory copy on hover/focus — for the honest sub-notes a bank VP
 * reads once (ROI assumptions, a chip's meaning). Keyboard-reachable (it's a real <button>) and screen-reader
 * announced via the Tooltip it wraps.
 */
export function InfoTip({ label, className }: { label: React.ReactNode; className?: string }) {
  return (
    <Tooltip label={label}>
      <button type="button" aria-label={typeof label === 'string' ? label : undefined}
        className={cn('inline-grid place-items-center rounded-full text-muted hover:text-ink-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/40', className)}>
        <Info className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </Tooltip>
  );
}
