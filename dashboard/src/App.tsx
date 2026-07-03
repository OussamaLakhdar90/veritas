import { useEffect, useState } from 'react';
import { HashRouter, Routes, Route, NavLink, useLocation } from 'react-router-dom';
import { motion, MotionConfig } from 'framer-motion';
import { useTranslation } from 'react-i18next';
import { Moon, Sun, Search, Menu, ChevronLeft, ChevronRight } from 'lucide-react';
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
import { Snyk } from './pages/Snyk';
import { EngineBadge } from './components/EngineBadge';
import { Costs } from './pages/Costs';
import { Defects } from './pages/Defects';
import { Gates } from './pages/Gates';
import { Glossary } from './pages/Glossary';
import { Settings as SettingsPage } from './pages/Settings';
import { useDarkMode, useSidebarCollapsed } from './lib/theme';
import { pageTransition, navSpring, isTestEnv } from './lib/motion';
import { setLanguage, type Lang } from './i18n';
import { CopilotAuthProvider } from './lib/copilotAuth';
import { ActivityCenterProvider } from './lib/activityCenter';
import { cn } from './components/cn';
import { NAV_GROUPS, NAV_ITEMS } from './lib/nav';
import { CommandPalette } from './components/CommandPalette';
import { ActivityBell } from './components/ActivityBell';
import { VeritasLogo } from './components/VeritasLogo';

/** EN/FR segmented toggle — French is the default (NBC/Québec); the choice persists to localStorage. */
function LanguageToggle() {
  const { i18n, t } = useTranslation();
  const current = (i18n.language?.startsWith('fr') ? 'fr' : 'en') as Lang;
  return (
    <div className="inline-flex rounded-full bg-ink-50 p-0.5 text-2xs font-semibold ring-1 ring-border"
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

/** Collapsible (desktop) + off-canvas (mobile) sidebar. */
function Sidebar({ collapsed, onToggleCollapse, mobileOpen, onCloseMobile }:
  { collapsed: boolean; onToggleCollapse: () => void; mobileOpen: boolean; onCloseMobile: () => void }) {
  const { t } = useTranslation();
  return (
    <aside className={cn(
      'z-50 flex flex-col bg-sidebar text-white/80 print:hidden',
      'fixed inset-y-0 left-0 w-60 transition-transform duration-base',
      mobileOpen ? 'translate-x-0' : '-translate-x-full',
      'lg:static lg:translate-x-0 lg:transition-[width] lg:duration-base',
      collapsed ? 'lg:w-16' : 'lg:w-60',
    )}>
      <div className={cn('flex items-center gap-2 px-5 py-5', collapsed && 'lg:justify-center lg:px-0')}>
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-brand text-white">
          <VeritasLogo className="h-5 w-5" />
        </span>
        <div className={cn(collapsed && 'lg:hidden')}>
          <div className="text-md font-semibold text-white">{t('brand.name')}</div>
          <div className="text-2xs text-white/50">{t('brand.tagline')}</div>
        </div>
      </div>
      <nav className="flex-1 space-y-0.5 overflow-y-auto px-3 pb-2">
        {NAV_GROUPS.map((group) => (
          <div key={group.section}>
            <p className={cn('px-3 pb-1 pt-3 text-2xs font-semibold uppercase tracking-wider text-white/35',
              collapsed && 'lg:hidden')}>
              {t(`nav.${group.section}`)}
            </p>
            {group.items.map(({ to, key, icon: Icon, end, adv }) => (
              <NavLink key={to} to={to} end={end} onClick={onCloseMobile} title={t(`nav.${key}`)}
                aria-label={collapsed ? t(`nav.${key}`) : undefined}
                className={({ isActive }) => cn(
                  'relative flex items-center rounded-lg px-3 py-2 text-sm transition-colors',
                  collapsed && 'lg:justify-center lg:px-0',
                  isActive ? 'text-white' : 'text-white/70 hover:bg-white/5 hover:text-white')}>
                {({ isActive }) => (<>
                  {isActive && (
                    <motion.span layoutId="navActive" transition={navSpring} aria-hidden="true"
                      className="absolute inset-0 rounded-lg bg-white/10" />
                  )}
                  <span className="relative z-10 flex flex-1 items-center gap-3">
                    <span className={cn('h-4 w-0.5 rounded-full', isActive ? 'bg-brand' : 'bg-transparent',
                      collapsed && 'lg:hidden')} />
                    <Icon className="h-4.5 w-4.5 shrink-0" />
                    <span className={cn('flex-1', collapsed && 'lg:hidden')}>{t(`nav.${key}`)}</span>
                    {adv && (
                      <span aria-hidden="true"
                        className={cn('ml-auto rounded border border-white/20 px-1.5 text-2xs text-white/45',
                          collapsed && 'lg:hidden')}>
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
      <button type="button" onClick={onToggleCollapse}
        aria-label={collapsed ? t('header.expand') : t('header.collapse')}
        className="mx-3 mb-2 hidden items-center gap-2 rounded-lg px-3 py-2 text-xs text-white/55 hover:bg-white/5 hover:text-white lg:flex">
        {collapsed ? <ChevronRight className="h-4 w-4" />
          : (<><ChevronLeft className="h-4 w-4" /> {t('header.collapse')}</>)}
      </button>
      <div className={cn('px-5 py-4 text-2xs text-white/40', collapsed && 'lg:hidden')}>{t('brand.org')}</div>
    </aside>
  );
}

/** Resolve the current route to a plain-language page label for the header breadcrumb. */
function useCrumb(): string {
  const { pathname } = useLocation();
  const { t } = useTranslation();
  if (pathname === '/') return t('nav.overview');
  const match = NAV_ITEMS
    .filter((n) => n.to !== '/' && (pathname === n.to || pathname.startsWith(`${n.to}/`)))
    .sort((a, b) => b.to.length - a.to.length)[0];
  if (match) return t(`nav.${match.key}`);
  if (pathname.startsWith('/findings')) return t('findings.title');
  return '';
}

function TopBar({ onOpenMobile, onOpenPalette, dark, onToggleDark }:
  { onOpenMobile: () => void; onOpenPalette: () => void; dark: boolean; onToggleDark: () => void }) {
  const { t } = useTranslation();
  const crumb = useCrumb();
  return (
    <header className="flex h-14 items-center justify-between gap-3 border-b border-border bg-surface px-4 sm:px-6 print:hidden">
      <div className="flex min-w-0 items-center gap-3">
        <button type="button" onClick={onOpenMobile} aria-label={t('header.menu')}
          className="grid h-9 w-9 shrink-0 place-items-center rounded-md text-ink-600 hover:bg-ink-50 lg:hidden">
          <Menu className="h-5 w-5" />
        </button>
        {crumb && <span className="truncate text-sm font-semibold text-ink-900">{crumb}</span>}
      </div>
      <div className="flex items-center gap-2">
        <span className="hidden items-center gap-1.5 rounded-full bg-ink-50 px-2.5 py-1 text-2xs font-medium text-muted ring-1 ring-border sm:inline-flex">
          <span className="h-1.5 w-1.5 rounded-full bg-success" /> {t('header.connected')}
        </span>
        <EngineBadge />
        <button type="button" onClick={onOpenPalette}
          className="inline-flex h-9 items-center gap-2 rounded-md border border-border bg-bg px-3 text-xs text-muted transition-colors hover:text-ink-900">
          <Search className="h-3.5 w-3.5" /> <span className="hidden sm:inline">{t('palette.open')}</span>
          <kbd className="hidden rounded bg-surface px-1.5 py-0.5 text-2xs font-medium ring-1 ring-border sm:inline">⌘K</kbd>
        </button>
        <ActivityBell />
        <LanguageToggle />
        <button onClick={onToggleDark} aria-label={t('header.toggleTheme')}
          className="grid h-9 w-9 shrink-0 place-items-center rounded-md text-ink-600 hover:bg-ink-50">
          {dark ? <Sun className="h-4.5 w-4.5" /> : <Moon className="h-4.5 w-4.5" />}
        </button>
      </div>
    </header>
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
      <Route path="/snyk" element={<Snyk />} />
      <Route path="/gates" element={<Gates />} />
      <Route path="/costs" element={<Costs />} />
      <Route path="/glossary" element={<Glossary />} />
      <Route path="/settings" element={<SettingsPage />} />
    </Routes>
  );
  return (
    <main className="min-w-0 flex-1 overflow-auto">
      {/* Cap the content column so the flagship + dense tables don't render full-bleed / edge-to-edge on a
          wide external monitor. Narrow/wide PageContainer variants still nest correctly inside this. */}
      <div className="mx-auto max-w-[1600px] p-6">
        {isTestEnv ? routes : (
          <motion.div key={location.pathname} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={pageTransition}>
            {routes}
          </motion.div>
        )}
      </div>
    </main>
  );
}

export function App() {
  const [dark, toggleDark] = useDarkMode();
  const [collapsed, toggleCollapsed] = useSidebarCollapsed();
  const [mobileOpen, setMobileOpen] = useState(false);
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
      <ActivityCenterProvider>
      <div className="flex min-h-screen">
        {mobileOpen && (
          <div className="fixed inset-0 z-40 bg-ink-900/50 lg:hidden" onClick={() => setMobileOpen(false)} aria-hidden="true" />
        )}
        <Sidebar collapsed={collapsed} onToggleCollapse={toggleCollapsed}
          mobileOpen={mobileOpen} onCloseMobile={() => setMobileOpen(false)} />

        {/* Main column */}
        <div className="flex min-w-0 flex-1 flex-col">
        <CopilotAuthProvider>
          <TopBar onOpenMobile={() => setMobileOpen(true)} onOpenPalette={() => setPaletteOpen(true)}
            dark={dark} onToggleDark={toggleDark} />
          <RoutedMain />
        </CopilotAuthProvider>
        </div>
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      </ActivityCenterProvider>
    </HashRouter>
    </MotionConfig>
  );
}
