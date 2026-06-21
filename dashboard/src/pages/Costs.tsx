import { useEffect, useState } from 'react'
import { api, CostSummary } from '../api'

export function Costs() {
  const [s, setS] = useState<CostSummary | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    api.costSummary().then(setS).catch((e) => setErr(String(e)))
  }, [])

  if (err) return <p className="muted">No cost data yet ({err}).</p>
  if (!s) return <p className="muted">Loading…</p>

  const rows = Object.entries(s.bySkill || {}).sort((a, b) => b[1] - a[1])
  return (
    <div>
      <h1>LLM Cost</h1>
      <div className="cards" style={{ display: 'flex', gap: 12, margin: '12px 0' }}>
        <div className="card"><div style={{ fontSize: 24, fontWeight: 700 }}>${(s.totalEstCostUsd ?? 0).toFixed(4)}</div><div className="muted">total est. cost</div></div>
        <div className="card"><div style={{ fontSize: 24, fontWeight: 700 }}>{s.actions ?? 0}</div><div className="muted">LLM actions</div></div>
      </div>
      <h2 style={{ fontSize: 16 }}>By skill</h2>
      <table>
        <thead><tr><th>Skill</th><th>Est. cost (USD)</th></tr></thead>
        <tbody>
          {rows.map(([skill, cost]) => (
            <tr key={skill}><td>{skill}</td><td>${cost.toFixed(4)}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
