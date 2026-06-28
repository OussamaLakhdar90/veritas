import { HashRouter, Routes, Route, NavLink } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  LayoutDashboard, ShieldCheck, Bug, ClipboardList, ListChecks, Code2, GitPullRequestArrow,
  Coins, Settings, Moon, Sun, Target, ClipboardCheck, Rocket, BookOpen, Layers, Sparkles,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
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
import { setLanguage, type Lang } from './i18n';
import { CopilotAuthProvider } from './lib/copilotAuth';
import { BackgroundScansProvider } from './lib/backgroundScans';
import { cn } from './components/cn';

interface NavItem { to: string; key: string; icon: LucideIcon; end?: boolean; adv?: boolean }
interface NavGroup { section: string; items: NavItem[] }

/** Sidebar grouped into labeled sections so 15 destinations read as a hierarchy, not a flat wall of links. */
const NAV_GROUPS: NavGroup[] = [
  { section: 'secOverview', items: [
    { to: '/', key: 'overview', icon: LayoutDashboard, end: true },
    { to: '/onboarding', key: 'getStarted', icon: Rocket },
  ] },
  { section: 'secValidate', items: [
    { to: '/repos', key: 'validate', icon: ShieldCheck },
    { to: '/defects', key: 'defects', icon: Bug },
  ] },
  { section: 'secDesign', items: [
    { to: '/test-strategy', key: 'testStrategy', icon: Target },
    { to: '/multi-source-strategy', key: 'multiSource', icon: Layers },
    { to: '/test-plans', key: 'testPlans', icon: ClipboardList },
    { to: '/test-cases', key: 'testCases', icon: ListChecks },
    { to: '/review-test-cases', key: 'reviews', icon: ClipboardCheck },
  ] },
  { section: 'secAutomation', items: [
    { to: '/generate-api-tests', key: 'generateApiTests', icon: Sparkles },
    { to: '/generate-tests', key: 'localGeneration', icon: Code2, adv: true },
  ] },
  { section: 'secGovern', items: [
    { to: '/gates', key: 'gates', icon: GitPullRequestArrow },
    { to: '/costs', key: 'cost', icon: Coins },
  ] },
  { section: 'secAdmin', items: [
    { to: '/glossary', key: 'glossary', icon: BookOpen },
    { to: '/settings', key: 'settings', icon: Settings },
  ] },
];

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

export function App() {
  const [dark, toggleDark] = useDarkMode();
  const { t } = useTranslation();
  return (
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
                      'flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors',
                      isActive ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/5 hover:text-white')}>
                    {({ isActive }) => (<>
                      <span className={cn('h-4 w-0.5 rounded-full', isActive ? 'bg-brand' : 'bg-transparent')} />
                      <Icon className="h-[18px] w-[18px]" /> {t(`nav.${key}`)}
                      {adv && (
                        <span aria-hidden="true"
                          className="ml-auto rounded border border-white/20 px-1.5 text-[9px] text-white/45">
                          {t('nav.adv')}
                        </span>
                      )}
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
              <LanguageToggle />
              <button onClick={toggleDark} aria-label={t('header.toggleTheme')}
                className="grid h-9 w-9 place-items-center rounded-md text-ink-600 hover:bg-ink-50">
                {dark ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}
              </button>
            </span>
          </header>
          <main className="min-w-0 flex-1 overflow-auto p-6">
            <Routes>
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
          </main>
        </CopilotAuthProvider>
        </div>
      </div>
      </BackgroundScansProvider>
    </HashRouter>
  );
}
