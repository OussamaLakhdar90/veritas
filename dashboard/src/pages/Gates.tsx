import { useEffect, useState } from 'react'
import { api, GateDecision } from '../api'

const FILTERS = ['PENDING', 'APPROVED', 'REJECTED']

export function Gates() {
  const [status, setStatus] = useState('PENDING')
  const [rows, setRows] = useState<GateDecision[] | null>(null)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState('')

  function load(s: string) {
    setRows(null)
    api.gates(s).then(setRows).catch((e) => setErr(String(e)))
  }
  useEffect(() => load(status), [status])

  async function decide(id: string, approve: boolean) {
    setBusy(id)
    try {
      if (approve) await api.approveGate(id)
      else await api.rejectGate(id, 'dashboard', 'Rejected from dashboard')
      load(status)
    } catch (e) {
      setErr(String(e))
    } finally {
      setBusy('')
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <h1>Approval Gates</h1>
        <div style={{ display: 'flex', gap: 6 }}>
          {FILTERS.map((f) => (
            <button key={f} onClick={() => setStatus(f)} className={f === status ? 'active' : ''}>{f}</button>
          ))}
        </div>
      </div>
      {err && <p className="muted">{err}</p>}
      {!rows ? <p className="muted">Loading…</p> : rows.length === 0 ? (
        <p className="muted">No {status.toLowerCase()} gates.</p>
      ) : (
        <table>
          <thead><tr><th>Action</th><th>Run</th><th>Status</th><th>Approver</th><th>When</th><th></th></tr></thead>
          <tbody>
            {rows.map((g) => (
              <tr key={g.id}>
                <td>{g.action}</td>
                <td className="muted">{g.runId}</td>
                <td>{g.status}</td>
                <td>{g.approver ?? '—'}</td>
                <td className="muted">{g.decidedAt ? new Date(g.decidedAt).toLocaleString() : (g.createdAt ? new Date(g.createdAt).toLocaleString() : '—')}</td>
                <td>
                  {g.status === 'PENDING' && (
                    <span style={{ display: 'flex', gap: 6 }}>
                      <button onClick={() => decide(g.id, true)} disabled={busy === g.id}>Approve</button>
                      <button onClick={() => decide(g.id, false)} disabled={busy === g.id}>Reject</button>
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
