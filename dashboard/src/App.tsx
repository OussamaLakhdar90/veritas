import { useEffect, useState } from 'react';
import { HashRouter, Routes, Route, NavLink, useLocation } from 'react-router-dom';
import { motion, MotionConfig } from 'framer-motion';
import { useTranslation } from 'react-i18next';
import { Moon, Sun, Search } from 'lucide-react';
import { Dashboard } from './pages/Dashboard';
import { Onboarding } from './pages/Onboarding';
import { RepoPicker } from './pages/RepoPicker';
import { Findings } from './pages/Findings';
import { TestStrategy } from './pages/TestStrategy';
import { StrategyDetail } from './pages/StrategyDetail';
import { TestConditions } from './pages/TestConditions';
import { MultiSourceStrategy } from './pages/MultiSourceStrategy';
import { TestPlans } from './pages/TestPlans';
import { TestPlanDetail } from './pages/TestPlanDetail';
import { TestCases } from './pages/TestCases';
import { Reviews } from './pages/Reviews';
import { Codegen } from './pages/Codegen';
import { GenerateApiTests } from './pages/GenerateApiTests';
import { EngineBadge } from './components/EngineBadge';
import { Costs } from './pages/Costs';
import { Defects } from './pages/Defects';
import { Gates } from './pages/Gates';
import { Glossary } from './pages/Glossary';
import { Settings as SettingsPage } from './pages/Settings';
import { useDarkMode } from './lib/theme';
import { pageTransition, navSpring, isTestEnv } from './lib/motion';
import { setLanguage, type Lang } from './i18n';
import { CopilotAuthProvider } from './lib/copilotAuth';
import { BackgroundScansProvider } from './lib/backgroundScans';
import { cn } from './components/cn';
import { NAV_GROUPS } from './lib/nav';
import { CommandPalette } from './components/CommandPalette';

/** EN/FR segmented toggle — French is the default (NBC/Québec); the choice persists to localStorage. */
function LanguageToggle() {
  const { i18n, t } = useTranslation();
  const current = (i18n.language?.startsWith('fr') ? 'fr' : 'en') as Lang;
  return (
    <div className="inline-flex rounded-full bg-ink-50 p-0.5 text-[11px] font-semibold ring-1 ring-border"
      role="group" aria-label={t('header.language')}>
      {(['en', 'fr'] as Lang[]).map((lng) => (
        <button key={lng} type="button" onClick={() => setLanguage(lng)} aria-pressed={current === lng}
          className={cn('rounded-full px-2.5 py-1 transition-colors',
            current === lng ? 'bg-brand text-white' : 'text-muted hover:text-ink-900')}>
          {lng.toUpperCase()}
        </button>
      ))}
    </div>
  );
}

/** The routed content area — each route enters with a short fade + settle (skipped in tests for determinism). */
function RoutedMain() {
  const location = useLocation();
  const routes = (
    <Routes location={location}>
      <Route path="/" element={<Dashboard />} />
      <Route path="/onboarding" element={<Onboarding />} />
      <Route path="/repos" element={<RepoPicker />} />
      <Route path="/findings/:scanId" element={<Findings />} />
      <Route path="/defects" element={<Defects />} />
      <Route path="/test-strategy" element={<TestStrategy />} />
      <Route path="/test-strategy/:id" element={<StrategyDetail />} />
      <Route path="/test-conditions/:id" element={<TestConditions />} />
      <Route path="/multi-source-strategy" element={<MultiSourceStrategy />} />
      <Route path="/test-plans" element={<TestPlans />} />
      <Route path="/test-plans/:id" element={<TestPlanDetail />} />
      <Route path="/test-cases" element={<TestCases />} />
      <Route path="/review-test-cases" element={<Reviews />} />
      <Route path="/generate-tests" element={<Codegen />} />
      <Route path="/generate-api-tests" element={<GenerateApiTests />} />
      <Route path="/gates" element={<Gates />} />
      <Route path="/costs" element={<Costs />} />
      <Route path="/glossary" element={<Glossary />} />
      <Route path="/settings" element={<SettingsPage />} />
    </Routes>
  );
  return (
    <main className="min-w-0 flex-1 overflow-auto p-6">
      {isTestEnv ? routes : (
        <motion.div key={location.pathname} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={pageTransition}>
          {routes}
        </motion.div>
      )}
    </main>
  );
}

export function App() {
  const [dark, toggleDark] = useDarkMode();
  const { t } = useTranslation();
  const [paletteOpen, setPaletteOpen] = useState(false);
  // ⌘K / Ctrl-K toggles the command palette from anywhere.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPaletteOpen((o) => !o); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return (
    <MotionConfig reducedMotion="user">
    <HashRouter>
      <BackgroundScansProvider>
      <div className="flex min-h-screen">
        {/* Sidebar */}
        <aside className="flex w-60 shrink-0 flex-col bg-sidebar text-white/80">
          <div className="flex items-center gap-2 px-5 py-5">
            <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand font-bold text-white">V</span>
            <div>
              <div className="text-[15px] font-semibold text-white">{t('brand.name')}</div>
              <div className="text-[11px] text-white/50">{t('brand.tagline')}</div>
            </div>
          </div>
          <nav className="flex-1 space-y-0.5 px-3 pb-2">
            {NAV_GROUPS.map((group) => (
              <div key={group.section}>
                <p className="px-3 pb-1 pt-3 text-[9.5px] font-semibold uppercase tracking-wider text-white/35">
                  {t(`nav.${group.section}`)}
                </p>
                {group.items.map(({ to, key, icon: Icon, end, adv }) => (
                  <NavLink key={to} to={to} end={end}
                    className={({ isActive }) => cn(
                      'relative flex items-center rounded-lg px-3 py-2 text-sm transition-colors',
                      isActive ? 'text-white' : 'text-white/70 hover:bg-white/5 hover:text-white')}>
                    {({ isActive }) => (<>
                      {isActive && (
                        <motion.span layoutId="navActive" transition={navSpring} aria-hidden="true"
                          className="absolute inset-0 rounded-lg bg-white/10" />
                      )}
                      <span className="relative z-10 flex flex-1 items-center gap-3">
                        <span className={cn('h-4 w-0.5 rounded-full', isActive ? 'bg-brand' : 'bg-transparent')} />
                        <Icon className="h-[18px] w-[18px]" /> {t(`nav.${key}`)}
                        {adv && (
                          <span aria-hidden="true"
                            className="ml-auto rounded border border-white/20 px-1.5 text-[9px] text-white/45">
                            {t('nav.adv')}
                          </span>
                        )}
                      </span>
                    </>)}
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>
          <div className="px-5 py-4 text-[11px] text-white/40">{t('brand.org')}</div>
        </aside>

        {/* Main column */}
        <div className="flex min-w-0 flex-1 flex-col">
        <CopilotAuthProvider>
          <header className="flex h-14 items-center justify-between border-b border-border bg-surface px-6">
            <span className="flex items-center gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-full bg-ink-50 px-2.5 py-1 text-[11px] font-medium text-muted ring-1 ring-border">
                <span className="h-1.5 w-1.5 rounded-full bg-success" /> {t('header.connected')}
              </span>
              <EngineBadge />
            </span>
            <span className="flex items-center gap-2">
              <button type="button" onClick={() => setPaletteOpen(true)}
                className="inline-flex h-9 items-center gap-2 rounded-md border border-border bg-bg px-3 text-[12px] text-muted transition-colors hover:text-ink-900">
                <Search className="h-3.5 w-3.5" /> {t('palette.open')}
                <kbd className="rounded bg-surface px-1.5 py-0.5 text-[10px] font-medium ring-1 ring-border">⌘K</kbd>
              </button>
              <LanguageToggle />
              <button onClick={toggleDark} aria-label={t('header.toggleTheme')}
                className="grid h-9 w-9 place-items-center rounded-md text-ink-600 hover:bg-ink-50">
                {dark ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}
              </button>
            </span>
          </header>
          <RoutedMain />
        </CopilotAuthProvider>
        </div>
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      </BackgroundScansProvider>
    </HashRouter>
    </MotionConfig>
  );
}
