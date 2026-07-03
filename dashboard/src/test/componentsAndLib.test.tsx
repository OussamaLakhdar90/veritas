import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  Button,
  Badge,
  Table,
  Th,
  Td,
  Row,
  SortableTh,
  useSort,
  FreshnessStamp,
  useCountUp,
  type SortState,
} from '../components/ui'
import { Modal } from '../components/Modal'
import { ToastProvider, useToast } from '../components/Toast'
import { cn } from '../components/cn'
import {
  SCAN_STEPS,
  STAGE_ORDER,
  stagePct,
  formatElapsed,
} from '../lib/scanStages'
import { useDarkMode } from '../lib/theme'
import {
  severityBadge,
  SEVERITY_BADGE,
  TONE,
} from '../theme/tokens'
import i18n from '../i18n'
import { enumLabel } from '../lib/enumLabels'

/* ── FreshnessStamp + useCountUp ───────────────────────────────────────────── */
describe('FreshnessStamp', () => {
  it('shows the relative update time and refetches on click', async () => {
    const user = userEvent.setup()
    const onRefresh = vi.fn()
    render(<FreshnessStamp updatedAt={Date.now() - 2 * 60_000} onRefresh={onRefresh} />)
    expect(screen.getByRole('button')).toBeInTheDocument()
    await user.click(screen.getByRole('button'))
    expect(onRefresh).toHaveBeenCalledOnce()
  })

  it('omits the timestamp when updatedAt is missing and disables while refreshing', () => {
    const { rerender } = render(<FreshnessStamp onRefresh={() => {}} refreshing />)
    expect(screen.getByRole('button')).toBeDisabled()
    rerender(<FreshnessStamp onRefresh={() => {}} />)
    expect(screen.getByRole('button')).not.toBeDisabled()
  })
})

describe('useCountUp', () => {
  it('resolves to the target immediately in tests (no rAF flake)', () => {
    let seen = -1
    function Probe() { seen = useCountUp(84); return <span>{seen}</span> }
    render(<Probe />)
    expect(seen).toBe(84)
  })
})

/* ── components/cn.ts ──────────────────────────────────────────────────────── */
describe('cn', () => {
  it('joins truthy class names and drops falsy ones', () => {
    expect(cn('a', 'b')).toBe('a b')
    expect(cn('a', false, null, undefined, 'b')).toBe('a b')
  })

  it('expands conditional object + array forms (clsx semantics)', () => {
    expect(cn({ on: true, off: false }, ['x', 'y'])).toBe('on x y')
  })

  it('returns an empty string when given nothing truthy', () => {
    expect(cn(false, null, undefined)).toBe('')
  })
})

/* ── components/ui.tsx :: Button ───────────────────────────────────────────── */
describe('Button', () => {
  it('renders children and fires onClick when enabled', async () => {
    const user = userEvent.setup()
    let clicks = 0
    render(<Button onClick={() => { clicks += 1 }}>Save</Button>)
    const btn = screen.getByRole('button', { name: 'Save' })
    await user.click(btn)
    expect(clicks).toBe(1)
    // primary variant by default
    expect(btn.className).toContain('bg-brand')
  })

  it('is disabled (and unclickable) while loading and shows the spinner', async () => {
    const user = userEvent.setup()
    let clicks = 0
    render(<Button loading onClick={() => { clicks += 1 }}>Submit</Button>)
    const btn = screen.getByRole('button', { name: 'Submit' })
    expect(btn).toBeDisabled()
    // the Loader2 icon renders as an svg with the spin animation
    expect(btn.querySelector('svg.animate-spin')).not.toBeNull()
    await user.click(btn)
    expect(clicks).toBe(0)
  })

  it('respects an explicit disabled prop independent of loading', async () => {
    const user = userEvent.setup()
    let clicks = 0
    render(<Button disabled onClick={() => { clicks += 1 }}>Nope</Button>)
    const btn = screen.getByRole('button', { name: 'Nope' })
    expect(btn).toBeDisabled()
    await user.click(btn)
    expect(clicks).toBe(0)
  })

  it('applies the chosen variant + size classes', () => {
    render(<Button variant="danger" size="sm">Delete</Button>)
    const btn = screen.getByRole('button', { name: 'Delete' })
    expect(btn.className).toContain('bg-danger')
    expect(btn.className).toContain('h-8') // sm height
  })
})

/* ── components/ui.tsx :: Badge / Table primitives ─────────────────────────── */
describe('Badge & Table primitives', () => {
  it('Badge renders its content with the merged className', () => {
    render(<Badge className="custom-badge">Critical</Badge>)
    const badge = screen.getByText('Critical')
    expect(badge.tagName).toBe('SPAN')
    expect(badge.className).toContain('custom-badge')
    expect(badge.className).toContain('rounded-full')
  })

  it('Table renders a head row plus body rows/cells', () => {
    render(
      <Table head={<Th>Name</Th>}>
        <Row>
          <Td>Alice</Td>
        </Row>
      </Table>,
    )
    const table = screen.getByRole('table')
    expect(within(table).getByText('Name')).toBeInTheDocument()
    expect(within(table).getByText('Alice')).toBeInTheDocument()
    // one header cell + one body cell
    expect(within(table).getAllByRole('columnheader')).toHaveLength(1)
    expect(within(table).getAllByRole('cell')).toHaveLength(1)
  })

  it('Td forwards arbitrary cell props (e.g. colSpan)', () => {
    render(
      <Table head={<Th>H</Th>}>
        <Row>
          <Td colSpan={3}>spanned</Td>
        </Row>
      </Table>,
    )
    expect(screen.getByText('spanned')).toHaveAttribute('colspan', '3')
  })
})

/* ── components/ui.tsx :: useSort + SortableTh ─────────────────────────────── */
type Person = { name: string; age: number }
const PEOPLE: Person[] = [
  { name: 'Charlie', age: 30 },
  { name: 'Alice', age: 25 },
  { name: 'Bob', age: 35 },
]

function SortHarness({ initial }: { initial?: { key: string; dir?: 'asc' | 'desc' } }) {
  const { sorted, toggle, key, dir } = useSort<Person>(PEOPLE, initial)
  return (
    <div>
      <span data-testid="state">{key ?? 'none'}:{dir}</span>
      <ul>
        {sorted.map((p) => (
          <li key={p.name}>{p.name}</li>
        ))}
      </ul>
      <button onClick={() => toggle('name')}>sort-name</button>
      <button onClick={() => toggle('age')}>sort-age</button>
    </div>
  )
}

function rowNames() {
  return screen.getAllByRole('listitem').map((li) => li.textContent)
}

describe('useSort', () => {
  it('returns rows in original order when no key is set', () => {
    render(<SortHarness />)
    expect(rowNames()).toEqual(['Charlie', 'Alice', 'Bob'])
    expect(screen.getByTestId('state')).toHaveTextContent('none:asc')
  })

  it('sorts ascending by a string key, then toggles to descending on re-click', async () => {
    const user = userEvent.setup()
    render(<SortHarness />)
    await user.click(screen.getByText('sort-name'))
    expect(rowNames()).toEqual(['Alice', 'Bob', 'Charlie'])
    expect(screen.getByTestId('state')).toHaveTextContent('name:asc')

    await user.click(screen.getByText('sort-name'))
    expect(rowNames()).toEqual(['Charlie', 'Bob', 'Alice'])
    expect(screen.getByTestId('state')).toHaveTextContent('name:desc')
  })

  it('switching to a different key resets the direction to ascending', async () => {
    const user = userEvent.setup()
    render(<SortHarness initial={{ key: 'name', dir: 'desc' }} />)
    // numeric compare path
    await user.click(screen.getByText('sort-age'))
    expect(rowNames()).toEqual(['Alice', 'Charlie', 'Bob'])
    expect(screen.getByTestId('state')).toHaveTextContent('age:asc')
  })

  it('honours an initial sort key/direction', () => {
    render(<SortHarness initial={{ key: 'age', dir: 'desc' }} />)
    expect(rowNames()).toEqual(['Bob', 'Charlie', 'Alice'])
  })
})

function SortableHeaderHarness() {
  const { sorted, ...sort } = useSort<Person>(PEOPLE)
  const sortState = sort as SortState
  return (
    <Table head={<SortableTh label="Name" sortKey="name" sort={sortState} />}>
      {sorted.map((p) => (
        <Row key={p.name}>
          <Td>{p.name}</Td>
        </Row>
      ))}
    </Table>
  )
}

describe('SortableTh', () => {
  it('exposes aria-sort that reflects the active column + direction', async () => {
    const user = userEvent.setup()
    render(<SortableHeaderHarness />)
    const header = screen.getByRole('columnheader')
    expect(header).toHaveAttribute('aria-sort', 'none')

    await user.click(screen.getByRole('button', { name: /Name/ }))
    expect(header).toHaveAttribute('aria-sort', 'ascending')

    await user.click(screen.getByRole('button', { name: /Name/ }))
    expect(header).toHaveAttribute('aria-sort', 'descending')
  })
})

/* ── components/Modal.tsx ──────────────────────────────────────────────────── */
describe('Modal', () => {
  it('renders nothing when closed', () => {
    render(<Modal open={false} onClose={() => {}} title="Hidden">body</Modal>)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByText('Hidden')).toBeNull()
  })

  it('renders an accessible dialog (role + aria-modal + labelled title) when open', () => {
    render(
      <Modal open onClose={() => {}} title="Confirm" footer={<span>foot</span>}>
        <p>Are you sure?</p>
      </Modal>,
    )
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // title is wired via aria-labelledby
    const labelledby = dialog.getAttribute('aria-labelledby')
    expect(labelledby).toBeTruthy()
    expect(document.getElementById(labelledby!)).toHaveTextContent('Confirm')
    expect(screen.getByText('Are you sure?')).toBeInTheDocument()
    expect(screen.getByText('foot')).toBeInTheDocument()
  })

  it('moves focus into the dialog when it opens (focus trap entry)', () => {
    render(
      <Modal open onClose={() => {}} title="Focusable">
        <button>inside</button>
      </Modal>,
    )
    // first focusable inside is the Close button (rendered before children)
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }))
  })

  it('closes on the Escape key', async () => {
    const user = userEvent.setup()
    let closed = 0
    render(<Modal open onClose={() => { closed += 1 }} title="Esc">body</Modal>)
    await user.keyboard('{Escape}')
    expect(closed).toBe(1)
  })

  it('closes when the Close button is clicked', async () => {
    const user = userEvent.setup()
    let closed = 0
    render(<Modal open onClose={() => { closed += 1 }} title="X">body</Modal>)
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(closed).toBe(1)
  })

  it('wraps Tab focus from the last focusable back to the first', async () => {
    const user = userEvent.setup()
    render(
      <Modal open onClose={() => {}} title="Trap">
        <button>only</button>
      </Modal>,
    )
    const close = screen.getByRole('button', { name: 'Close' })
    const only = screen.getByRole('button', { name: 'only' })
    only.focus()
    expect(document.activeElement).toBe(only)
    await user.keyboard('{Tab}')
    // last focusable -> wraps back to the first (Close)
    expect(document.activeElement).toBe(close)
  })

  it('wraps Shift+Tab focus from the first focusable to the last', async () => {
    const user = userEvent.setup()
    render(
      <Modal open onClose={() => {}} title="Trap">
        <button>only</button>
      </Modal>,
    )
    const close = screen.getByRole('button', { name: 'Close' })
    const only = screen.getByRole('button', { name: 'only' })
    close.focus()
    await user.keyboard('{Shift>}{Tab}{/Shift}')
    expect(document.activeElement).toBe(only)
  })
})

/* ── components/Toast.tsx ──────────────────────────────────────────────────── */
function ToastTrigger() {
  const { push } = useToast()
  return (
    <div>
      <button onClick={() => push('success', 'Saved!')}>ok</button>
      <button onClick={() => push('error', 'Boom!')}>fail</button>
    </div>
  )
}

describe('Toast', () => {
  it('pushes a success toast as a polite status region', async () => {
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <ToastTrigger />
      </ToastProvider>,
    )
    await user.click(screen.getByText('ok'))
    const toast = await screen.findByText('Saved!')
    expect(toast).toBeInTheDocument()
    // success toasts use role=status, not alert
    expect(screen.getByRole('status')).toHaveTextContent('Saved!')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('pushes an error toast as an assertive alert', async () => {
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <ToastTrigger />
      </ToastProvider>,
    )
    await user.click(screen.getByText('fail'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Boom!')
  })

  it('dismisses a toast when its dismiss button is clicked', async () => {
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <ToastTrigger />
      </ToastProvider>,
    )
    await user.click(screen.getByText('ok'))
    expect(await screen.findByText('Saved!')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Dismiss toast' }))
    expect(screen.queryByText('Saved!')).toBeNull()
  })

  it('a bare useToast() consumer (no provider) is a safe no-op', async () => {
    const user = userEvent.setup()
    render(<ToastTrigger />)
    // the default context push is a no-op — clicking must not throw or render a toast
    await user.click(screen.getByText('ok'))
    expect(screen.queryByText('Saved!')).toBeNull()
  })
})

/* ── lib/scanStages.ts ─────────────────────────────────────────────────────── */
describe('scanStages', () => {
  it('has strictly increasing pct across the ordered steps, all within 0–100', () => {
    const pcts = SCAN_STEPS.map((s) => s.pct)
    for (let i = 1; i < pcts.length; i += 1) {
      expect(pcts[i]).toBeGreaterThan(pcts[i - 1])
    }
    pcts.forEach((p) => {
      expect(p).toBeGreaterThan(0)
      expect(p).toBeLessThanOrEqual(100)
    })
  })

  it('marks exactly the AI review step as long-running', () => {
    expect(SCAN_STEPS.filter((s) => s.long).map((s) => s.key)).toEqual(['RECONCILING'])
  })

  it('STAGE_ORDER is monotonic for the live pipeline and parks DONE/FAILED past the last step', () => {
    expect(STAGE_ORDER.QUEUED).toBe(0)
    expect(STAGE_ORDER.CLONING).toBeLessThan(STAGE_ORDER.REPORTING)
    expect(STAGE_ORDER.DONE).toBe(STAGE_ORDER.FAILED)
    expect(STAGE_ORDER.DONE).toBeGreaterThan(STAGE_ORDER.REPORTING)
  })

  it('stagePct: terminal/queued shortcuts plus per-step lookup, with a fallback', () => {
    expect(stagePct('DONE')).toBe(100)
    expect(stagePct('QUEUED')).toBe(4)
    expect(stagePct('RECONCILING')).toBe(82)
    expect(stagePct('NOT_A_STAGE')).toBe(4) // fallback
  })

  it('formatElapsed: m:ss with zero-padded seconds and a 0 floor for negatives', () => {
    expect(formatElapsed(0)).toBe('0:00')
    expect(formatElapsed(5000)).toBe('0:05')
    expect(formatElapsed(65000)).toBe('1:05')
    expect(formatElapsed(600000)).toBe('10:00')
    expect(formatElapsed(-5000)).toBe('0:00')
  })
})

/* ── lib/theme.ts :: useDarkMode ───────────────────────────────────────────── */
function DarkModeHarness() {
  const [dark, toggle] = useDarkMode()
  return (
    <div>
      <span data-testid="mode">{dark ? 'dark' : 'light'}</span>
      <button onClick={toggle}>toggle</button>
    </div>
  )
}

describe('useDarkMode', () => {
  it('defaults to light when nothing is persisted and writes "light" to localStorage', () => {
    render(<DarkModeHarness />)
    expect(screen.getByTestId('mode')).toHaveTextContent('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(localStorage.getItem('veritas-theme')).toBe('light')
  })

  it('initialises from a persisted "dark" preference and adds the dark class to <html>', () => {
    localStorage.setItem('veritas-theme', 'dark')
    render(<DarkModeHarness />)
    expect(screen.getByTestId('mode')).toHaveTextContent('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('toggles the mode, the <html> class, and the persisted value', async () => {
    const user = userEvent.setup()
    render(<DarkModeHarness />)
    expect(screen.getByTestId('mode')).toHaveTextContent('light')

    await user.click(screen.getByText('toggle'))
    expect(screen.getByTestId('mode')).toHaveTextContent('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(localStorage.getItem('veritas-theme')).toBe('dark')

    await user.click(screen.getByText('toggle'))
    expect(screen.getByTestId('mode')).toHaveTextContent('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(localStorage.getItem('veritas-theme')).toBe('light')
  })
})

/* ── theme/tokens.ts ───────────────────────────────────────────────────────── */
describe('theme tokens', () => {
  it('severityBadge maps known severities (case-insensitive) and falls back to INFO', () => {
    expect(severityBadge('CRITICAL')).toBe(SEVERITY_BADGE.CRITICAL)
    expect(severityBadge('critical')).toBe(SEVERITY_BADGE.CRITICAL)
    expect(severityBadge('BLOCKER')).toBe(SEVERITY_BADGE.BLOCKER)
    // unknown + undefined both resolve to the INFO style
    expect(severityBadge('WAT')).toBe(SEVERITY_BADGE.INFO)
    expect(severityBadge(undefined)).toBe(SEVERITY_BADGE.INFO)
  })

  it('TONE exposes the expected status keys', () => {
    expect(Object.keys(TONE).sort()).toEqual(['danger', 'info', 'muted', 'ok', 'warn'])
    expect(TONE.ok).toContain('text-success')
    expect(TONE.danger).toContain('text-danger')
  })
})

/* ── lib/enumLabels.ts (the layer/confidence labels moved here from theme/tokens) ── */
describe('enumLabel', () => {
  const t = i18n.t.bind(i18n)

  it('turns layer codes into plain language (case-insensitive) and echoes unknown codes', () => {
    expect(enumLabel(t, 'layer', 'L2')).toBe('API completeness')
    expect(enumLabel(t, 'layer', 'l2')).toBe('API completeness')
    expect(enumLabel(t, 'layer', 'L9')).toBe('L9') // unknown echoes the raw code
    expect(enumLabel(t, 'layer', undefined)).toBe('—') // no layer → em dash
  })

  it('maps confidence HIGH/MEDIUM/LOW to plain language', () => {
    expect(enumLabel(t, 'confidence', 'HIGH')).toBe('High confidence')
    expect(enumLabel(t, 'confidence', 'low')).toBe('Low confidence')
    expect(enumLabel(t, 'confidence', 'UNSURE')).toBe('UNSURE')
  })

  it('humanizes the real gate-action codes and prettifies unknown machine ids', () => {
    expect(enumLabel(t, 'gateAction', 'CREATE_DEFECT')).toBe('Create a Jira defect')
    expect(enumLabel(t, 'gateAction', 'XRAY_UPDATE_STEPS')).toBe('Update test steps in Xray')
    expect(enumLabel(t, 'gateAction', 'SOME_NEW-ACTION')).toBe('Some new action') // _ and - both split
    expect(enumLabel(t, 'gateAction', 'Already a sentence')).toBe('Already a sentence') // free-form stays raw
  })

  it('humanizes skill ids including the unknown bucket', () => {
    expect(enumLabel(t, 'skill', 'validate-contract')).toBe('Contract validation')
    expect(enumLabel(t, 'skill', 'unknown')).toBe('Other')
    expect(enumLabel(t, 'skill', 'brand-new-skill')).toBe('Brand new skill') // prettified fallback
  })
})