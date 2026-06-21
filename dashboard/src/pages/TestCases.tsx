import { useState } from 'react'
import { api, TestCase } from '../api'

export function TestCases() {
  const [svc, setSvc] = useState('')
  const [rows, setRows] = useState<TestCase[] | null>(null)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState('')
  const [projectKey, setProjectKey] = useState('')

  function load() {
    if (!svc) return
    api.testCases(svc).then(setRows).catch((e) => setErr(String(e)))
  }

  async function act(tc: TestCase, fn: () => Promise<unknown>) {
    setBusy(tc.id)
    setErr('')
    try {
      await fn()
      load()
    } catch (e) {
      setErr(String(e))
    } finally {
      setBusy('')
    }
  }

  return (
    <div>
      <h1>Test Cases</h1>
      <div className="card" style={{ margin: '8px 0', display: 'flex', gap: 8, alignItems: 'center' }}>
        <input placeholder="service" value={svc} onChange={(e) => setSvc(e.target.value)} />
        <input placeholder="projectKey (for push)" value={projectKey} onChange={(e) => setProjectKey(e.target.value)} />
        <button onClick={load}>Load</button>
      </div>
      {err && <p className="sev-major">{err}</p>}
      {rows && (
        <table>
          <thead><tr><th>Title</th><th>Technique</th><th>Status</th><th>Xray</th><th>Actions</th></tr></thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={5} className="muted">No cases for “{svc}”.</td></tr>}
            {rows.map((tc) => (
              <tr key={tc.id}>
                <td>{tc.title}</td>
                <td>{tc.technique ?? '—'}</td>
                <td>{tc.status}</td>
                <td>{tc.xrayKey ?? '—'}</td>
                <td style={{ display: 'flex', gap: 6 }}>
                  <button disabled={busy === tc.id} onClick={() => act(tc, () => api.patchTestCase(tc.id, { status: 'APPROVED', actor: 'dashboard' }))}>Approve</button>
                  <button disabled={busy === tc.id} onClick={() => act(tc, () => api.patchTestCase(tc.id, { status: 'REJECTED' }))}>Reject</button>
                  <button disabled={busy === tc.id || !projectKey} title={projectKey ? '' : 'enter a projectKey'} onClick={() => act(tc, () => api.pushTestCase(tc.id, projectKey))}>Push→Xray</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
