import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { MultiSourceStrategy } from '../pages/MultiSourceStrategy'

// A two-feature preview index. Override any slice per test.
const preview = (over: Record<string, unknown> = {}) => ({
  snapshotId: 'snap-1',
  features: [
    { featureId: 'f1', displayName: 'Get policy', status: 'IMPLEMENTED', pinned: false,
      units: [{ id: 'CODE-1', source: 'CODE', type: 'ENDPOINT', title: 'GET /policies' }] },
    { featureId: 'f2', displayName: 'Create policy', status: 'PLANNED', pinned: false,
      units: [{ id: 'JIRA-1', source: 'JIRA', type: 'REQUIREMENT', title: 'Create' }] },
  ],
  gaps: [], mix: { code: true, jira: true, confluence: false },
  redactionCount: 0, fetchFailures: [], hardFail: false, estimatedCostUsd: 0.07, carryForwardNotes: [],
  ...over,
})

// Drive the wizard from the source-pick step to a rendered preview index using a Jira source.
async function previewFromJira(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('ciam-policies'), 'ciam-policies')
  await user.click(screen.getByText('Jira (issues by JQL or epic)'))
  await user.type(screen.getByPlaceholderText(/project = CIAM/), 'project = CIAM')
  await user.click(screen.getByRole('button', { name: /Preview the feature index/ }))
  expect(await screen.findByText('Get policy')).toBeInTheDocument()
}

// The base preview POST every test needs to reach the edit step. Tests that assert on the request
// override this with their own handler.
function stubInitialPreview(body = preview()) {
  server.use(http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => HttpResponse.json(body)))
}

describe('Multi-source strategy — edit-step extras', () => {
  it('renames a feature (PATCH .../rename) and shows the new name', async () => {
    let renameUrl = ''
    let renameBody: { featureId?: string; name?: string } = {}
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => HttpResponse.json(preview())),
      http.patch('*/api/v1/multi-source-strategy/snapshots/:id/rename', async ({ request }) => {
        renameUrl = request.url
        renameBody = (await request.json()) as { featureId?: string; name?: string }
        return HttpResponse.json(preview({
          features: [
            { featureId: 'f1', displayName: 'Fetch a policy', status: 'IMPLEMENTED', pinned: false,
              units: [{ id: 'CODE-1', source: 'CODE', type: 'ENDPOINT', title: 'GET /policies' }] },
            { featureId: 'f2', displayName: 'Create policy', status: 'PLANNED', pinned: false,
              units: [{ id: 'JIRA-1', source: 'JIRA', type: 'REQUIREMENT', title: 'Create' }] },
          ],
        }))
      }),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getAllByRole('button', { name: 'Rename feature' })[0])
    const input = screen.getByDisplayValue('Get policy')
    await user.clear(input)
    await user.type(input, 'Fetch a policy')
    await user.click(screen.getByRole('button', { name: 'Save name' }))

    expect(await screen.findByText('Fetch a policy')).toBeInTheDocument()
    expect(renameUrl).toContain('/multi-source-strategy/snapshots/snap-1/rename')
    expect(renameBody).toEqual({ featureId: 'f1', name: 'Fetch a policy' })
  })

  it('cancelling a rename closes the form without a PATCH', async () => {
    let renameHits = 0
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => HttpResponse.json(preview())),
      http.patch('*/api/v1/multi-source-strategy/snapshots/:id/rename', () => {
        renameHits += 1
        return HttpResponse.json(preview())
      }),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getAllByRole('button', { name: 'Rename feature' })[0])
    expect(screen.getByDisplayValue('Get policy')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByDisplayValue('Get policy')).not.toBeInTheDocument()
    expect(screen.getByText('Get policy')).toBeInTheDocument()
    expect(renameHits).toBe(0)
  })

  it('selecting one feature prompts to pick another before merging', async () => {
    stubInitialPreview()
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getByRole('checkbox', { name: 'Select Get policy to merge' }))

    expect(screen.getByText('1 selected')).toBeInTheDocument()
    expect(screen.getByText('Select another feature to merge.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Merge/ })).not.toBeInTheDocument()
  })

  it('merges two selected features (PATCH .../merge) with the typed name', async () => {
    let mergeUrl = ''
    let mergeBody: { featureIds?: string[]; name?: string } = {}
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => HttpResponse.json(preview())),
      http.patch('*/api/v1/multi-source-strategy/snapshots/:id/merge', async ({ request }) => {
        mergeUrl = request.url
        mergeBody = (await request.json()) as { featureIds?: string[]; name?: string }
        return HttpResponse.json(preview({
          features: [
            { featureId: 'fm', displayName: 'Policy CRUD', status: 'IMPLEMENTED', pinned: false,
              units: [
                { id: 'CODE-1', source: 'CODE', type: 'ENDPOINT', title: 'GET /policies' },
                { id: 'JIRA-1', source: 'JIRA', type: 'REQUIREMENT', title: 'Create' },
              ] },
          ],
        }))
      }),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getByRole('checkbox', { name: 'Select Get policy to merge' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Create policy to merge' }))
    expect(screen.getByText('2 selected')).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('Merged name (optional)'), 'Policy CRUD')
    await user.click(screen.getByRole('button', { name: /Merge 2/ }))

    expect(await screen.findByText('Policy CRUD')).toBeInTheDocument()
    expect(screen.queryByText('Get policy')).not.toBeInTheDocument()
    expect(mergeUrl).toContain('/multi-source-strategy/snapshots/snap-1/merge')
    expect(mergeBody).toEqual({ featureIds: ['f1', 'f2'], name: 'Policy CRUD' })
  })

  it('clears the selection from the merge action bar', async () => {
    stubInitialPreview()
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getByRole('checkbox', { name: 'Select Get policy to merge' }))
    expect(screen.getByText('1 selected')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear' }))

    expect(screen.queryByText('1 selected')).not.toBeInTheDocument()
  })

  it('pins a feature (PATCH .../pin) and the button flips to Unpin', async () => {
    let pinUrl = ''
    let pinBody: { featureId?: string; pinned?: boolean } = {}
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => HttpResponse.json(preview())),
      http.patch('*/api/v1/multi-source-strategy/snapshots/:id/pin', async ({ request }) => {
        pinUrl = request.url
        pinBody = (await request.json()) as { featureId?: string; pinned?: boolean }
        return HttpResponse.json(preview({
          features: [
            { featureId: 'f1', displayName: 'Get policy', status: 'IMPLEMENTED', pinned: true,
              units: [{ id: 'CODE-1', source: 'CODE', type: 'ENDPOINT', title: 'GET /policies' }] },
            { featureId: 'f2', displayName: 'Create policy', status: 'PLANNED', pinned: false,
              units: [{ id: 'JIRA-1', source: 'JIRA', type: 'REQUIREMENT', title: 'Create' }] },
          ],
        }))
      }),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    // Both features start unpinned, so scope the pin button to the first feature's row.
    const row = screen.getByText('Get policy').closest('div.rounded-lg') as HTMLElement
    await user.click(within(row).getByRole('button', { name: 'Pin feature' }))

    expect(await screen.findByRole('button', { name: 'Unpin feature' })).toBeInTheDocument()
    expect(pinUrl).toContain('/multi-source-strategy/snapshots/snap-1/pin')
    expect(pinBody).toEqual({ featureId: 'f1', pinned: true })
  })

  it('unpins an already-pinned feature (PATCH .../pin with pinned:false)', async () => {
    let pinBody: { featureId?: string; pinned?: boolean } = {}
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => HttpResponse.json(preview({
        features: [
          { featureId: 'f1', displayName: 'Get policy', status: 'IMPLEMENTED', pinned: true,
            units: [{ id: 'CODE-1', source: 'CODE', type: 'ENDPOINT', title: 'GET /policies' }] },
          { featureId: 'f2', displayName: 'Create policy', status: 'PLANNED', pinned: false,
            units: [{ id: 'JIRA-1', source: 'JIRA', type: 'REQUIREMENT', title: 'Create' }] },
        ],
      }))),
      http.patch('*/api/v1/multi-source-strategy/snapshots/:id/pin', async ({ request }) => {
        pinBody = (await request.json()) as { featureId?: string; pinned?: boolean }
        return HttpResponse.json(preview({
          features: [
            { featureId: 'f1', displayName: 'Get policy', status: 'IMPLEMENTED', pinned: false,
              units: [{ id: 'CODE-1', source: 'CODE', type: 'ENDPOINT', title: 'GET /policies' }] },
            { featureId: 'f2', displayName: 'Create policy', status: 'PLANNED', pinned: false,
              units: [{ id: 'JIRA-1', source: 'JIRA', type: 'REQUIREMENT', title: 'Create' }] },
          ],
        }))
      }),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getByRole('button', { name: 'Unpin feature' }))

    // After unpinning, both rows are unpinned → two "Pin feature" buttons. Assert the unpin button is gone.
    expect(await screen.findAllByRole('button', { name: 'Pin feature' })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Unpin feature' })).not.toBeInTheDocument()
    expect(pinBody).toEqual({ featureId: 'f1', pinned: false })
  })

  it('re-extracts keeping edits — POSTs preview?carryForwardFrom and shows the carried-forward toast', async () => {
    let recheckUrl = ''
    let postCount = 0
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', ({ request }) => {
        postCount += 1
        if (postCount > 1) recheckUrl = request.url
        return HttpResponse.json(preview())
      }),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getByRole('button', { name: /Re-extract \(keep edits\)/ }))

    expect(await screen.findByText('Re-extracted — your edits were carried forward.')).toBeInTheDocument()
    expect(recheckUrl).toContain('carryForwardFrom=snap-1')
  })

  it('re-extract surfaces carry-forward notes when some edits could not be re-applied', async () => {
    let postCount = 0
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => {
        postCount += 1
        return HttpResponse.json(postCount > 1
          ? preview({ carryForwardNotes: ['Rename of "Get policy" dropped — feature no longer present'] })
          : preview())
      }),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getByRole('button', { name: /Re-extract \(keep edits\)/ }))

    expect(await screen.findByText(/1 edit\(s\) couldn't be carried forward/)).toBeInTheDocument()
    expect(screen.getByText("Some of your edits couldn't be carried forward")).toBeInTheDocument()
    expect(screen.getByText('Rename of "Get policy" dropped — feature no longer present')).toBeInTheDocument()
  })

  it('renders the coverage-gaps list with friendly labels', async () => {
    stubInitialPreview(preview({
      gaps: [
        { kind: 'PLANNED_NOT_IMPLEMENTED', feature: 'f2', message: 'Create policy is specified but not built yet' },
        { kind: 'IMPLEMENTED_UNDOCUMENTED', feature: 'f1', message: 'Get policy is built but has no spec' },
      ],
    }))
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    expect(await screen.findByText('Coverage gaps')).toBeInTheDocument()
    expect(screen.getByText('Specified, not built')).toBeInTheDocument()
    expect(screen.getByText('Create policy is specified but not built yet')).toBeInTheDocument()
    expect(screen.getByText('Built, unspecified')).toBeInTheDocument()
    expect(screen.getByText('Get policy is built but has no spec')).toBeInTheDocument()
  })

  it('shows the soft fetch-failure banner without blocking generation', async () => {
    stubInitialPreview(preview({ fetchFailures: ['Confluence page 123 not found'], hardFail: false }))
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    expect(await screen.findByText(/Some items couldn't be fetched:/)).toBeInTheDocument()
    expect(screen.getByText(/Confluence page 123 not found/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Generate strategy/ })).toBeEnabled()
  })

  it('shows the feature/gap/redaction summary counts', async () => {
    stubInitialPreview(preview({
      redactionCount: 3,
      gaps: [{ kind: 'COVERAGE_GAP', feature: 'f2', message: 'Done in Jira, not built' }],
    }))
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    expect(await screen.findByText(/2 feature\(s\) · 1 gap\(s\) · 3 redaction\(s\)/)).toBeInTheDocument()
  })

  it('surfaces an error toast when a rename PATCH fails', async () => {
    server.use(
      http.post('*/api/v1/services/:s/multi-source-strategy/preview', () => HttpResponse.json(preview())),
      http.patch('*/api/v1/multi-source-strategy/snapshots/:id/rename', () =>
        HttpResponse.json({ detail: 'Snapshot is locked', status: 409 }, { status: 409 })),
    )
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getAllByRole('button', { name: 'Rename feature' })[0])
    const input = screen.getByDisplayValue('Get policy')
    await user.clear(input)
    await user.type(input, 'Fetch a policy')
    await user.click(screen.getByRole('button', { name: 'Save name' }))

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Snapshot is locked')).toBeInTheDocument()
  })

  it('Back returns to the source-pick step', async () => {
    stubInitialPreview()
    const user = userEvent.setup()
    renderPage(<MultiSourceStrategy />)
    await previewFromJira(user)

    await user.click(screen.getByRole('button', { name: /^Back/ }))

    expect(await screen.findByText(/Preview the feature index/)).toBeInTheDocument()
    expect(screen.queryByText('Get policy')).not.toBeInTheDocument()
  })
})