import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, TestPlan } from '../api'

export function TestPlans() {
  const [plans, setPlans] = useState<TestPlan[]>([])
  const [err, setErr] = useState('')

  useEffect(() => {
    api.testPlans().then(setPlans).catch((e) => setErr(String(e)))
  }, [])

  return (
    <div>
      <h1>Test Plans</h1>
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
