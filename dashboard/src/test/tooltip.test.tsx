import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Tooltip, InfoTip } from '../components/Tooltip'

describe('Tooltip', () => {
  it('is hidden at rest and reveals a role=tooltip on hover, then hides on unhover', async () => {
    const user = userEvent.setup()
    render(<Tooltip label="Half a senior day of manual review"><button type="button">4h</button></Tooltip>)

    // Nothing announced until the user points at the trigger.
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()

    await user.hover(screen.getByRole('button', { name: '4h' }))
    const tip = await screen.findByRole('tooltip')
    expect(tip).toHaveTextContent('Half a senior day of manual review')
    // The trigger points AT the tooltip for screen readers.
    expect(screen.getByRole('button', { name: '4h' }).parentElement)
      .toHaveAttribute('aria-describedby', tip.getAttribute('id'))

    await user.unhover(screen.getByRole('button', { name: '4h' }))
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('also opens on keyboard focus and closes on Escape', async () => {
    const user = userEvent.setup()
    render(<Tooltip label="Keyboard reachable"><button type="button">focus me</button></Tooltip>)

    await user.tab()
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Keyboard reachable')

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('uses the theme-safe ink-900 / text-bg colours (not text-white)', async () => {
    const user = userEvent.setup()
    render(<Tooltip label="hi"><span>trigger</span></Tooltip>)
    await user.hover(screen.getByText('trigger'))
    const tip = await screen.findByRole('tooltip')
    expect(tip.className).toContain('bg-ink-900')
    expect(tip.className).toContain('text-bg')
    expect(tip.className).not.toContain('text-white')
  })

  it('InfoTip renders an accessible info button that reveals its help on hover', async () => {
    const user = userEvent.setup()
    render(<InfoTip label="Hours avoided is an estimate" />)

    const btn = screen.getByRole('button', { name: 'Hours avoided is an estimate' })
    await user.hover(btn)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Hours avoided is an estimate')
  })
})
