import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { SnykBatchDetail } from '../pages/SnykBatchDetail'

/** One train under a bulk story, minimal shape the batch view needs. */
function train(over: Record<string, unknown> = {}) {
  return {
    id: 't1', coordinate: 'org.a:x', oldVersion: '1.0', fixedIn: '2.0', severity: 'high', appIds: 'APP7576',
    jiraKey: 'CIAM-2', storyKey: 'CIAM-100', status: 'DONE', breaking: false,
    verdict: { available: false, breaking: false, confidence: 0, reasons: [] }, steps: [],
    ...over,
  }
}

describe('Snyk batch (aggregate) view', () => {
  it('rolls up the batch by outcome and lists a drill-in row per app fix', async () => {
    const trains = [
      train({ id: 't1', appIds: 'APP7576', coordinate: 'org.a:x', status: 'DONE' }),
      train({ id: 't2', appIds: 'APP7576', coordinate: 'org.b:y', status: 'AWAITING_MANUAL_FIX' }),
      train({ id: 't3', appIds: 'APP7571', coordinate: 'org.c:z', status: 'ALREADY_FIXED' }),
    ]
    server.use(
      http.get('*/api/v1/snyk/fixes/batches/CIAM-100', () => HttpResponse.json(trains)),
      // Each collapsed <details> still mounts FixTrainProgress → it fetches its train.
      http.get('*/api/v1/snyk/fixes/:id', ({ params }) =>
        HttpResponse.json(trains.find((t) => t.id === params.id) ?? trains[0])),
    )
    renderPage(<SnykBatchDetail />, { path: '/snyk/batch/:storyKey', route: '/snyk/batch/CIAM-100' })

    // The roll-up buckets (plain language, colour-coded) — one fixed, one waiting, one already-safe. Each label
    // appears both in the roll-up chip and on its row badge, so match all.
    expect(await screen.findAllByText(/^fixed$/)).not.toHaveLength(0)
    expect(screen.getAllByText(/waiting on you/)).not.toHaveLength(0)
    expect(screen.getAllByText(/already safe/)).not.toHaveLength(0)
    // Both apps appear as groups, and every coordinate is a drill-in row.
    expect(screen.getByText('APP7576')).toBeInTheDocument()
    expect(screen.getByText('APP7571')).toBeInTheDocument()
    expect(screen.getByText('org.a:x')).toBeInTheDocument()
    expect(screen.getByText('org.c:z')).toBeInTheDocument()
  })
})
