import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import fr from './locales/fr.json';

/** localStorage key holding the chosen language; absent ⇒ French default (NBC / Québec, Bill 96). */
export const LANG_KEY = 'veritas-lang';
export type Lang = 'en' | 'fr';

function initialLang(): Lang {
  try {
    const stored = localStorage.getItem(LANG_KEY);
    if (stored === 'en' || stored === 'fr') return stored;
  } catch {
    /* localStorage unavailable (SSR/tests) — fall through to default */
  }
  return 'fr';
}

i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, fr: { translation: fr } },
  lng: initialLang(),
  fallbackLng: 'en', // English is the source language — fills any key not yet translated
  interpolation: { escapeValue: false },
  returnNull: false,
});

if (typeof document !== 'undefined') document.documentElement.lang = i18n.language;

/** Switch language, persist the choice, and reflect it on <html lang> (for a11y + correct hyphenation). */
export function setLanguage(lng: Lang) {
  i18n.changeLanguage(lng);
  try {
    localStorage.setItem(LANG_KEY, lng);
  } catch {
    /* ignore persistence failures */
  }
  if (typeof document !== 'undefined') document.documentElement.lang = lng;
}

export default i18n;
