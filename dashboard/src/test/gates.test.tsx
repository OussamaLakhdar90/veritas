import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Gates } from '../pages/Gates'

const pendingGate = {
  id: 'gate-1',
  runId: 'run-abc',
  action: 'CREATE_DEFECT',
  status: 'PENDING',
  approver: null as string | null,
  createdAt: '2026-06-01T10:00:00Z',
}

const approvedGate = {
  id: 'gate-2',
  runId: 'run-def',
  action: 'XRAY_CREATE_TEST',
  status: 'APPROVED',
  approver: 'alice',
  decidedAt: '2026-06-02T12:00:00Z',
  createdAt: '2026-06-01T11:00:00Z',
}

function renderGates() {
  return renderPage(<Gates />, { path: '/gates', route: '/gates' })
}

describe('Approval gates', () => {
  it('shows a loading state while the gates query is in flight', async () => {
    let release: (() => void) | undefined
    server.use(
      http.get('*/api/v1/gates', async () => {
        await new Promise<void>((resolve) => { release = resolve })
        return HttpResponse.json([])
      }),
    )
    renderGates()

    expect(await screen.findByText('Loading…')).toBeInTheDocument()
    release?.()
  })

  it('renders an empty state when there are no pending gates', async () => {
    server.use(http.get('*/api/v1/gates', () => HttpResponse.json([])))
    renderGates()

    expect(await screen.findByText('No pending approvals')).toBeInTheDocument()
    expect(
      screen.getByText(/When a skill needs approval before writing to Jira/),
    ).toBeInTheDocument()
  })

  it('surfaces an error state when the gates query fails', async () => {
    server.use(
      http.get('*/api/v1/gates', () =>
        HttpResponse.json({ detail: 'boom', status: 500 }, { status: 500 })),
    )
    renderGates()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/Couldn’t load this data/)).toBeInTheDocument()
  })

  it('lists pending gates with their action, run, status and approver', async () => {
    server.use(http.get('*/api/v1/gates', () => HttpResponse.json([pendingGate])))
    renderGates()

    expect(await screen.findByText('Create a Jira defect')).toBeInTheDocument()
    expect(screen.getByText('run-abc')).toBeInTheDocument()
    expect(screen.getAllByText('Pending').length).toBeGreaterThan(0)
    expect(screen.getByText('—')).toBeInTheDocument() // no approver yet
    expect(screen.getByRole('button', { name: /^Approve$/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Reject$/ })).toBeInTheDocument()
  })

  it('approves a pending gate (POST /gates/:id/approve) and shows the success toast', async () => {
    let approveHit = false
    server.use(
      http.get('*/api/v1/gates', () => HttpResponse.json([pendingGate])),
      http.post('*/api/v1/gates/:id/approve', ({ params }) => {
        approveHit = true
        return HttpResponse.json({ ...pendingGate, id: String(params.id), status: 'APPROVED', approver: 'dashboard' })
      }),
    )
    const user = userEvent.setup()
    renderGates()

    expect(await screen.findByText('Create a Jira defect')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^Approve$/ }))

    expect(await screen.findByText('Approved.')).toBeInTheDocument()
    expect(approveHit).toBe(true)
  })

  it('rejects a pending gate (POST /gates/:id/reject) and shows the success toast', async () => {
    let rejectHit = false
    server.use(
      http.get('*/api/v1/gates', () => HttpResponse.json([pendingGate])),
      http.post('*/api/v1/gates/:id/reject', ({ params }) => {
        rejectHit = true
        return HttpResponse.json({ ...pendingGate, id: String(params.id), status: 'REJECTED', approver: 'dashboard' })
      }),
    )
    const user = userEvent.setup()
    renderGates()

    expect(await screen.findByText('Create a Jira defect')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^Reject$/ }))

    expect(await screen.findByText('Rejected.')).toBeInTheDocument()
    expect(rejectHit).toBe(true)
  })

  it('surfaces an error toast when approving fails', async () => {
    server.use(
      http.get('*/api/v1/gates', () => HttpResponse.json([pendingGate])),
      http.post('*/api/v1/gates/:id/approve', () =>
        HttpResponse.json({ detail: 'Gate already decided', status: 409 }, { status: 409 })),
    )
    const user = userEvent.setup()
    renderGates()

    expect(await screen.findByText('Create a Jira defect')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^Approve$/ }))

    expect(await screen.findByText('Gate already decided')).toBeInTheDocument()
  })

  it('switches the status filter and re-queries (APPROVED rows expose no decide buttons)', async () => {
    server.use(
      http.get('*/api/v1/gates', ({ request }) => {
        const status = new URL(request.url).searchParams.get('status')
        if (status === 'APPROVED') return HttpResponse.json([approvedGate])
        return HttpResponse.json([pendingGate])
      }),
    )
    const user = userEvent.setup()
    renderGates()

    expect(await screen.findByText('Create a Jira defect')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Approved' }))

    expect(await screen.findByText('Create an Xray test')).toBeInTheDocument()
    expect(screen.getByText('alice')).toBeInTheDocument()
    expect(screen.getAllByText('Approved').length).toBeGreaterThan(0)
    // Decided gates are not actionable: only the filter toggles remain, no row Approve/Reject.
    expect(screen.queryByRole('button', { name: /^Approve$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Reject$/ })).not.toBeInTheDocument()
    // The pending row is gone after switching filters.
    expect(screen.queryByText('Create a Jira defect')).not.toBeInTheDocument()
  })

  it('renders the approver and decided time for a row inside its table row', async () => {
    server.use(http.get('*/api/v1/gates', () => HttpResponse.json([approvedGate])))
    renderGates()

    const row = (await screen.findByText('Create an Xray test')).closest('tr')!
    expect(within(row).getByText('alice')).toBeInTheDocument()
    expect(within(row).getByText('Approved')).toBeInTheDocument()
  })
})
