import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Donut, Gauge, Sparkline, severitySlices } from '../components/charts'

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

})
