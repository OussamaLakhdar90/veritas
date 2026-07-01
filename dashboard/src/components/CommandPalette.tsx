import { useEffect, useMemo, useRef, useState, type ComponentType } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Search, CornerDownLeft } from 'lucide-react';
import { api } from '../api';
import { NAV_ITEMS } from '../lib/nav';
import { cn } from './cn';

interface Cmd { id: string; label: string; group: string; to: string; Icon?: ComponentType<{ className?: string }> }

/** ⌘K palette — fuzzy-jump to any page or service. Keyboard-driven (↑↓ / Enter / Esc), localized. */
export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  // Only fetch the service list while the palette is open (cheap, cached, shared key).
  const servicesQ = useQuery({ queryKey: ['services'], queryFn: api.services, enabled: open });

  const commands = useMemo<Cmd[]>(() => {
    const pages: Cmd[] = NAV_ITEMS.map((n) => ({ id: `page:${n.to}`, label: t(`nav.${n.key}`), group: t('palette.pages'), to: n.to, Icon: n.icon }));
    const services: Cmd[] = (servicesQ.data ?? []).map((s) => ({ id: `svc:${s.name}`, label: s.name, group: t('palette.services'), to: '/test-strategy' }));
    return [...pages, ...services];
  }, [servicesQ.data, t]);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return needle ? commands.filter((c) => c.label.toLowerCase().includes(needle)) : commands;
  }, [commands, q]);

  useEffect(() => { setActive(0); }, [q, open]);
  useEffect(() => { if (open) inputRef.current?.focus(); }, [open]);

  if (!open) return null;

  const go = (c?: Cmd) => { if (!c) return; setQ(''); onClose(); navigate(c.to); };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, filtered.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); go(filtered[active]); }
    else if (e.key === 'Escape') { e.preventDefault(); onClose(); }
  };

  return createPortal(
    <div className="fixed inset-0 z-[70] flex items-start justify-center p-4 pt-[12vh]"
      role="dialog" aria-modal="true" aria-label={t('palette.title')}>
      <div className="absolute inset-0 bg-ink-900/50 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
      <div className="relative w-full max-w-lg overflow-hidden rounded-xl bg-surface shadow-pop ring-1 ring-border">
        <div className="flex items-center gap-2 border-b border-border px-4">
          <Search className="h-4 w-4 shrink-0 text-muted" />
          <input ref={inputRef} value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={onKey}
            placeholder={t('palette.placeholder')} aria-label={t('palette.placeholder')}
            className="h-12 w-full bg-transparent text-sm text-ink-900 placeholder:text-muted focus:outline-none" />
        </div>
        <ul className="max-h-80 overflow-auto p-2" role="listbox">
          {filtered.length === 0 ? (
            <li className="px-3 py-6 text-center text-[13px] text-muted">{t('palette.noResults')}</li>
          ) : filtered.map((c, i) => (
            <li key={c.id} role="option" aria-selected={i === active}>
              <button type="button" onMouseEnter={() => setActive(i)} onClick={() => go(c)}
                className={cn('flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm',
                  i === active ? 'bg-brand/10 text-ink-900' : 'text-ink-700 hover:bg-ink-50')}>
                {c.Icon ? <c.Icon className="h-4 w-4 shrink-0 text-muted" />
                  : <span className="grid h-4 w-4 shrink-0 place-items-center text-[11px] text-muted">›</span>}
                <span className="flex-1 truncate">{c.label}</span>
                <span className="text-[10px] uppercase tracking-wide text-muted">{c.group}</span>
              </button>
            </li>
          ))}
        </ul>
        <div className="flex items-center gap-3 border-t border-border px-4 py-2 text-[11px] text-muted">
          <span className="inline-flex items-center gap-1"><CornerDownLeft className="h-3 w-3" /> {t('palette.toSelect')}</span>
          <span>↑↓ {t('palette.toNavigate')}</span>
          <span className="ml-auto">esc</span>
        </div>
      </div>
    </div>,
    document.body,
  );
}
