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
