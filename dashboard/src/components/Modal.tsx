import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import { Button } from './ui';

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

  useEffect(() => {
    if (!open) return;
    const previouslyFocused = document.activeElement as HTMLElement | null;

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
    // Move focus into the dialog (first focusable, else the dialog itself).
    const node = ref.current;
    (node?.querySelector<HTMLElement>(FOCUSABLE) ?? node)?.focus();

    return () => {
      document.removeEventListener('keydown', onKey);
      previouslyFocused?.focus?.();   // restore focus to the trigger
    };
  }, [open, onClose]);

  if (!open) return null;
  const width = size === 'lg' ? 'max-w-2xl' : 'max-w-md';
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink-900/50 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
      <div ref={ref} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}
        className={`relative w-full ${width} rounded-xl bg-surface shadow-pop ring-1 ring-border focus:outline-none`}>
        <div className="flex items-center justify-between border-b border-border px-5 py-4">
          <h3 id={titleId} className="text-[15px] font-semibold text-ink-900">{title}</h3>
          <Button variant="ghost" size="sm" onClick={onClose} aria-label={t('common.close')}><X className="h-4 w-4" /></Button>
        </div>
        <div className="px-5 py-4">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-border px-5 py-3">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}
