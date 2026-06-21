import { useEffect, useState } from 'react'
import { api, DefectLink } from '../api'

function statusClass(category?: string): string {
  if (category === 'done') return 'sev-info'
  if (category === 'indeterminate') return 'sev-major'
  return 'sev-minor'
}

export function Defects() {
  const [rows, setRows] = useState<DefectLink[] | null>(null)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  function load() {
    api.defects().then(setRows).catch((e) => setErr(String(e)))
  }
  useEffect(load, [])

  async function sync() {
    setBusy(true)
    try {
      await api.syncDefects()
      load()
    } catch (e) {
      setErr(String(e))
    } finally {
      setBusy(false)
    }
  }

  if (err) return <p className="muted">No defects yet ({err}).</p>
  if (!rows) return <p className="muted">Loading…</p>

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <h1>Defects</h1>
        <button onClick={sync} disabled={busy}>{busy ? 'Syncing…' : 'Refresh statuses'}</button>
      </div>
      {rows.length === 0 ? (
        <p className="muted">No Jira defects have been created from findings yet.</p>
      ) : (
        <table>
          <thead><tr><th>Jira</th><th>Status</th><th>Created by</th><th>Last synced</th></tr></thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.id}>
                <td>{d.jiraUrl ? <a href={d.jiraUrl} target="_blank" rel="noreferrer">{d.jiraKey}</a> : (d.jiraKey ?? '—')}</td>
                <td><span className={statusClass(d.jiraStatusCategory)}>{d.jiraStatus ?? (d.createdInJira ? 'Open' : 'Not created')}</span></td>
                <td>{d.createdBy ?? '—'}</td>
                <td className="muted">{d.lastSyncedAt ? new Date(d.lastSyncedAt).toLocaleString() : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
