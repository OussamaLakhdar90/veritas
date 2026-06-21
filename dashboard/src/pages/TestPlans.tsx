import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, TestPlan } from '../api'

export function TestPlans() {
  const [plans, setPlans] = useState<TestPlan[]>([])
  const [err, setErr] = useState('')
  const [svc, setSvc] = useState('')
  const [fixVersion, setFixVersion] = useState('')
  const [projectKey, setProjectKey] = useState('')
  const [createGaps, setCreateGaps] = useState(false)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')

  function load() {
    api.testPlans().then(setPlans).catch((e) => setErr(String(e)))
  }
  useEffect(load, [])

  async function trigger() {
    if (!svc || !fixVersion) {
      setMsg('Service and fixVersion are required.')
      return
    }
    setBusy(true)
    setMsg('')
    try {
      const s = await api.triggerReleasePlan(svc, { fixVersion, projectKey: projectKey || undefined, createGaps })
      setMsg(`Plan ${s.planId}: ${s.matched} matched, ${s.gaps} gaps, ${s.created} created.`)
      load()
    } catch (e) {
      setMsg(String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <h1>Test Plans</h1>
      <div className="card" style={{ margin: '8px 0', display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <strong>New release plan:</strong>
        <input placeholder="service" value={svc} onChange={(e) => setSvc(e.target.value)} />
        <input placeholder="fixVersion (e.g. 8.2)" value={fixVersion} onChange={(e) => setFixVersion(e.target.value)} />
        <input placeholder="projectKey" value={projectKey} onChange={(e) => setProjectKey(e.target.value)} />
        <label><input type="checkbox" checked={createGaps} onChange={(e) => setCreateGaps(e.target.checked)} /> create gap tests (gated)</label>
        <button onClick={trigger} disabled={busy}>{busy ? 'Running…' : 'Generate'}</button>
        {msg && <span className="muted">{msg}</span>}
      </div>
      {err && <p className="muted">No data yet ({err}). Generate a release test plan.</p>}
      <table>
        <thead>
          <tr><th>Service</th><th>Kind</th><th>fixVersion</th><th>Status</th><th>Confidence</th><th>Risks</th><th>Est. cost</th><th></th></tr>
        </thead>
        <tbody>
          {plans.map((p) => (
            <tr key={p.id}>
              <td>{p.serviceName}</td>
              <td>{p.kind}</td>
              <td>{p.fixVersion ?? '—'}</td>
              <td>{p.status}</td>
              <td>{p.confidence != null ? `${Math.round(p.confidence)}%` : '—'}</td>
              <td>{p.riskCount ?? '—'}</td>
              <td>${(p.estCostUsd ?? 0).toFixed(4)}</td>
              <td><Link to={`/test-plans/${p.id}`}>Open deliverable →</Link></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
