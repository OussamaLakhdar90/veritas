import { HashRouter, Routes, Route, NavLink } from 'react-router-dom';
import {
  LayoutDashboard, ShieldCheck, Bug, ClipboardList, ListChecks, Code2, GitPullRequestArrow,
  Coins, Settings, Moon, Sun,
} from 'lucide-react';
import { Dashboard } from './pages/Dashboard';
import { RepoPicker } from './pages/RepoPicker';
import { Findings } from './pages/Findings';
import { TestPlans } from './pages/TestPlans';
import { TestPlanDetail } from './pages/TestPlanDetail';
import { TestCases } from './pages/TestCases';
import { Codegen } from './pages/Codegen';
import { Costs } from './pages/Costs';
import { Defects } from './pages/Defects';
import { Gates } from './pages/Gates';
import { useDarkMode } from './lib/theme';
import { cn } from './components/cn';

const NAV = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/repos', label: 'Validate', icon: ShieldCheck },
  { to: '/defects', label: 'Defects', icon: Bug },
  { to: '/test-plans', label: 'Test Plans', icon: ClipboardList },
  { to: '/test-cases', label: 'Test Cases', icon: ListChecks },
  { to: '/generate-tests', label: 'Generate Tests', icon: Code2 },
  { to: '/gates', label: 'Gates', icon: GitPullRequestArrow },
  { to: '/costs', label: 'Cost', icon: Coins },
  { to: '/settings', label: 'Settings', icon: Settings },
];

export function App() {
  const [dark, toggleDark] = useDarkMode();
  return (
    <HashRouter>
      <div className="flex min-h-screen">
        {/* Sidebar */}
        <aside className="flex w-60 shrink-0 flex-col bg-sidebar text-white/80">
          <div className="flex items-center gap-2 px-5 py-5">
            <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand font-bold text-white">V</span>
            <div>
              <div className="text-[15px] font-semibold text-white">Veritas</div>
              <div className="text-[11px] text-white/50">API Quality Platform</div>
            </div>
          </div>
          <nav className="flex-1 space-y-1 px-3">
            {NAV.map(({ to, label, icon: Icon, end }) => (
              <NavLink key={to} to={to} end={end}
                className={({ isActive }) => cn(
                  'flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors',
                  isActive ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/5 hover:text-white')}>
                {({ isActive }) => (<>
                  <span className={cn('h-4 w-0.5 rounded-full', isActive ? 'bg-brand' : 'bg-transparent')} />
                  <Icon className="h-[18px] w-[18px]" /> {label}
                </>)}
              </NavLink>
            ))}
          </nav>
          <div className="px-5 py-4 text-[11px] text-white/40">QE · National Bank of Canada</div>
        </aside>

        {/* Main column */}
        <div className="flex min-w-0 flex-1 flex-col">
          <header className="flex h-14 items-center justify-between border-b border-border bg-surface px-6">
            <span className="rounded-full bg-ink-50 px-2.5 py-1 text-[11px] font-medium text-muted ring-1 ring-border">
              Local · 127.0.0.1
            </span>
            <button onClick={toggleDark} aria-label="Toggle theme"
              className="grid h-9 w-9 place-items-center rounded-md text-ink-600 hover:bg-ink-50">
              {dark ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}
            </button>
          </header>
          <main className="min-w-0 flex-1 overflow-auto p-6">
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/repos" element={<RepoPicker />} />
              <Route path="/findings/:scanId" element={<Findings />} />
              <Route path="/defects" element={<Defects />} />
              <Route path="/test-plans" element={<TestPlans />} />
              <Route path="/test-plans/:id" element={<TestPlanDetail />} />
              <Route path="/test-cases" element={<TestCases />} />
              <Route path="/generate-tests" element={<Codegen />} />
              <Route path="/gates" element={<Gates />} />
              <Route path="/costs" element={<Costs />} />
              <Route path="/settings" element={<SettingsPlaceholder />} />
            </Routes>
          </main>
        </div>
      </div>
    </HashRouter>
  );
}

/** Placeholder until P3 ships the real Settings page (keeps the route + nav live now). */
function SettingsPlaceholder() {
  return <div className="text-sm text-muted">Settings — connections, tokens & Copilot sign-in (coming in the next slice).</div>;
}
