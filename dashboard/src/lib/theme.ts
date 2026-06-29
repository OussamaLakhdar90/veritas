import { useEffect, useState } from 'react';

const KEY = 'veritas-theme';

/** Dark-mode toggle persisted to localStorage; flips the `dark` class on <html>. */
export function useDarkMode(): [boolean, () => void] {
  const [dark, setDark] = useState(() => localStorage.getItem(KEY) === 'dark');
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    localStorage.setItem(KEY, dark ? 'dark' : 'light');
  }, [dark]);
  return [dark, () => setDark((d) => !d)];
}

const SIDEBAR_KEY = 'veritas-sidebar-collapsed';

/** Sidebar collapse state (desktop), persisted to localStorage. */
export function useSidebarCollapsed(): [boolean, () => void] {
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(SIDEBAR_KEY) === '1');
  useEffect(() => {
    localStorage.setItem(SIDEBAR_KEY, collapsed ? '1' : '0');
  }, [collapsed]);
  return [collapsed, () => setCollapsed((c) => !c)];
}
