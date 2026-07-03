import {
  LayoutDashboard, ShieldCheck, Bug, ClipboardList, ListChecks, Code2, GitPullRequestArrow,
  Coins, Settings, Target, ClipboardCheck, Rocket, BookOpen, Layers, Sparkles, Activity,
} from 'lucide-react';
import type { ComponentType } from 'react';
import { SnykNavIcon } from '../components/SnykLogo';

export interface NavItem { to: string; key: string; icon: ComponentType<{ className?: string }>; end?: boolean; adv?: boolean }
export interface NavGroup { section: string; items: NavItem[] }

/** Sidebar grouped into labeled sections so 15 destinations read as a hierarchy, not a flat wall of links. */
export const NAV_GROUPS: NavGroup[] = [
  { section: 'secOverview', items: [
    { to: '/', key: 'overview', icon: LayoutDashboard, end: true },
    { to: '/onboarding', key: 'getStarted', icon: Rocket },
    { to: '/activity', key: 'activity', icon: Activity },
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
    { to: '/snyk', key: 'snyk', icon: SnykNavIcon },
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

/** Flat list of every destination — for the command palette and any route→label lookups. */
export const NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((g) => g.items);
