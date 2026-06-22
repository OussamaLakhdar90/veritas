import React, { createContext, useCallback, useContext, useState } from 'react';
import { CheckCircle2, AlertTriangle, Info, X } from 'lucide-react';
import { cn } from './cn';

type ToastKind = 'success' | 'error' | 'info';
type Toast = { id: number; kind: ToastKind; message: string };
type ToastCtx = { push: (kind: ToastKind, message: string) => void };

const Ctx = createContext<ToastCtx>({ push: () => {} });
export const useToast = () => useContext(Ctx);

let seq = 1;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const remove = useCallback((id: number) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const push = useCallback((kind: ToastKind, message: string) => {
    const id = seq++;
    setToasts((t) => [...t, { id, kind, message }]);
    setTimeout(() => remove(id), 5000);
  }, [remove]);

  const icon = { success: CheckCircle2, error: AlertTriangle, info: Info };
  const tone = {
    success: 'ring-success/30 text-success',
    error: 'ring-danger/30 text-danger',
    info: 'ring-info/30 text-info',
  };
  return (
    <Ctx.Provider value={{ push }}>
      {children}
      {/* Live region so screen readers announce toasts; errors assert, others are polite. */}
      <div className="fixed bottom-4 right-4 z-[60] flex w-80 flex-col gap-2" aria-live="polite" aria-atomic="false">
        {toasts.map((t) => {
          const Icon = icon[t.kind];
          return (
            <div key={t.id} role={t.kind === 'error' ? 'alert' : 'status'}
              className={cn('flex items-start gap-3 rounded-lg bg-surface p-3 shadow-pop ring-1', tone[t.kind])}>
              <Icon className="mt-0.5 h-4 w-4 shrink-0" />
              <p className="flex-1 text-[13px] text-ink-900">{t.message}</p>
              <button onClick={() => remove(t.id)} aria-label="Dismiss toast"><X className="h-3.5 w-3.5 text-muted" /></button>
            </div>
          );
        })}
      </div>
    </Ctx.Provider>
  );
}
