import React, { useEffect, useRef } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import { Button } from './ui';
import { exitEase, isTestEnv, overlaySpring } from '../lib/motion';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), '
  + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Accessible modal: role=dialog + aria-modal, Escape + backdrop close, focus trap, and focus restore. */
export function Modal({ open, onClose, title, children, footer, size = 'md' }:
  {
    open: boolean; onClose: () => void; title: React.ReactNode;
    children: React.ReactNode; footer?: React.ReactNode; size?: 'md' | 'lg';
  }) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = React.useId();
  const { t } = useTranslation();

  // Focus management runs ONCE per open (keyed on `open` only) — so a parent re-render with an inline onClose
  // never re-steals focus mid-typing. Restores focus to the trigger on close.
  useEffect(() => {
    if (!open) return undefined;
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const node = ref.current;
    (node?.querySelector<HTMLElement>(FOCUSABLE) ?? node)?.focus();
    return () => previouslyFocused?.focus?.();
  }, [open]);

  // Escape-to-close + Tab focus-trap. Re-binds if `onClose` changes; never touches focus, so it can't disrupt typing.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
        return;
      }
      if (e.key !== 'Tab') return;
      const node = ref.current;
      if (!node) return;
      const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) {
        e.preventDefault();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const width = size === 'lg' ? 'max-w-2xl' : 'max-w-md';
  // The body scrolls inside the panel — a tall wizard must never push its footer past a projector viewport.
  const panel = (
    <>
      <div className="flex items-center justify-between border-b border-border px-5 py-4">
        <h3 id={titleId} className="text-md font-semibold text-ink-900">{title}</h3>
        <Button variant="ghost" size="sm" onClick={onClose} aria-label={t('common.close')}><X className="h-4 w-4" /></Button>
      </div>
      <div className="max-h-[70vh] overflow-y-auto px-5 py-4">{children}</div>
      {footer && <div className="flex justify-end gap-2 border-t border-border px-5 py-3">{footer}</div>}
    </>
  );
  const panelCls = `relative w-full ${width} rounded-xl bg-surface shadow-pop ring-1 ring-border focus:outline-none`;
  if (isTestEnv) {
    if (!open) return null;
    return createPortal(
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div className="absolute inset-0 bg-ink-900/50 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
        <div ref={ref} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1} className={panelCls}>
          {panel}
        </div>
      </div>,
      document.body,
    );
  }
  // The dialog settles in from where the user was looking; exits are faster than entries.
  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <motion.div className="absolute inset-0 bg-ink-900/50 backdrop-blur-sm" onClick={onClose} aria-hidden="true"
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, transition: { duration: 0.14 } }}
            transition={{ duration: 0.15 }} />
          <motion.div ref={ref} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}
            className={panelCls}
            initial={{ opacity: 0, scale: 0.96, y: 8 }} animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.98, transition: { duration: 0.14, ease: exitEase } }}
            transition={overlaySpring}>
            {panel}
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
