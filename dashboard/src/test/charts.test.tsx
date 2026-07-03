import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Donut, Gauge, Sparkline, TrendChart, severitySlices } from '../components/charts'

describe('charts', () => {
  it('severitySlices keeps non-zero buckets in severity order', () => {
    const s = severitySlices({ INFO: 2, CRITICAL: 1, MAJOR: 0 })
    expect(s.map((x) => x.label)).toEqual(['CRITICAL', 'INFO'])
    expect(s.find((x) => x.label === 'CRITICAL')!.value).toBe(1)
  })

  it('renders a Donut as an accessible image with its center value + label', () => {
    render(<Donut slices={severitySlices({ CRITICAL: 3, MINOR: 1 })} ariaLabel="Sev" centerValue={4} centerLabel="findings" />)
    const img = screen.getByRole('img', { name: 'Sev' })
    expect(img).toHaveTextContent('4')
    expect(img).toHaveTextContent('findings')
  })

  it('renders a Gauge with the computed percentage', () => {
    render(<Gauge value={3} max={4} ariaLabel="Resolution" centerLabel="resolved" />)
    expect(screen.getByRole('img', { name: 'Resolution' })).toHaveTextContent('75%')
  })

  it('Gauge handles a zero max without dividing by zero', () => {
    render(<Gauge value={0} max={0} ariaLabel="Empty" />)
    expect(screen.getByRole('img', { name: 'Empty' })).toHaveTextContent('0%')
  })

  it('renders a Sparkline polyline for >= 2 points (and a placeholder for fewer)', () => {
    const { container, rerender } = render(<Sparkline values={[1, 4, 2, 5]} ariaLabel="Trend" />)
    expect(screen.getByRole('img', { name: 'Trend' })).toBeInTheDocument()
    expect(container.querySelector('polyline')).toBeInTheDocument()
    rerender(<Sparkline values={[3]} ariaLabel="Trend" />)
    expect(container.querySelector('polyline')).not.toBeInTheDocument()
  })

  describe('TrendChart', () => {
    const points = [
      { date: '2026-06-01', value: 60 },
      { date: '2026-06-02', value: 72 },
      { date: '2026-06-03', value: 88 },
    ]

    it('renders an accessible line + area path with first/last date labels', () => {
      const { container } = render(<TrendChart points={points} ariaLabel="Score history" />)
      expect(screen.getByRole('img', { name: 'Score history' })).toBeInTheDocument()
      // Two <path>: the gradient area fill and the line itself.
      expect(container.querySelectorAll('path').length).toBeGreaterThanOrEqual(2)
      // First + last date x-labels are locale-true short dates (en-CA in tests): "Jun 1" / "Jun 3".
      expect(screen.getByText('Jun 1')).toBeInTheDocument()
      expect(screen.getByText('Jun 3')).toBeInTheDocument()
    })

    it('renders a placeholder (no path) for fewer than two points', () => {
      const { container } = render(<TrendChart points={[{ date: '2026-06-01', value: 5 }]} ariaLabel="Sparse" />)
      expect(screen.getByRole('img', { name: 'Sparse' })).toBeInTheDocument()
      expect(container.querySelector('path')).not.toBeInTheDocument()
    })

    it('draws the target reference line when the target is inside the domain', () => {
      const { container } = render(
        <TrendChart points={points} ariaLabel="Fidelity" domain={[50, 100]} target={90} targetLabel="Release gate 90" />)
      // The dashed target line carries a <title> with the label.
      expect(container.querySelector('line[stroke-dasharray]')).toBeInTheDocument()
      expect(screen.getByText('Release gate 90')).toBeInTheDocument()
    })

    it('uses the value formatter for the y-axis min/max labels', () => {
      render(<TrendChart points={points} ariaLabel="Spend" domain={[0, 10]} format={(v) => `$${v.toFixed(0)}`} />)
      expect(screen.getByText('$10')).toBeInTheDocument()
      expect(screen.getByText('$0')).toBeInTheDocument()
    })

    it('shows an HTML tooltip with the value + date on hover', () => {
      const { container } = render(<TrendChart points={points} ariaLabel="Trend" />)
      const hit = container.querySelectorAll('rect')[1] // the 2nd point's hit column (value 72)
      fireEvent.mouseEnter(hit)
      // Tooltip text is HTML (a status region), not inside the <svg role=img>.
      const tip = screen.getByRole('status')
      expect(tip).toHaveTextContent('72')
      expect(tip).toHaveTextContent('Jun 2')
    })
  })
})
