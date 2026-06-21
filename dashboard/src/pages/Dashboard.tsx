import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, PreflightCheck, Scan } from '../api'

export function Dashboard() {
  const [scans, setScans] = useState<Scan[]>([])
  const [checks, setChecks] = useState<PreflightCheck[]>([])
  const [err, setErr] = useState('')

  useEffect(() => {
    api.scans().then(setScans).catch((e) => setErr(String(e)))
    api.preflight().then(setChecks).catch(() => setChecks([]))
  }, [])

  const missing = checks.filter((c) => c.status === 'MISSING')

  return (
    <div>
      {missing.length > 0 && (
        <div style={{ background: '#FDECEF', border: '1px solid #E31837', borderRadius: 10, padding: '10px 14px', marginBottom: 16 }}>
          <strong style={{ color: '#9E0F25' }}>Configuration needed ({missing.length})</strong>
          <ul style={{ margin: '6px 0 0' }}>
            {missing.map((c) => (
              <li key={c.name}>{c.name}: <span className="muted">{c.remediation}</span></li>
            ))}
          </ul>
        </div>
      )}
      <h1>Scans</h1>
      {err && <p className="muted">No data yet ({err}). Run a validate-contract scan.</p>}
      <table>
        <thead>
          <tr><th>Service</th><th>Status</th><th>Findings</th><th>Est. cost</th><th>Started</th><th></th></tr>
        </thead>
        <tbody>
          {scans.map((s) => (
            <tr key={s.id}>
              <td>{s.serviceName}</td>
              <td>{s.status}</td>
              <td>{s.totalFindings}</td>
              <td>${(s.totalEstCostUsd ?? 0).toFixed(4)}</td>
              <td className="muted">{s.startedAt}</td>
              <td>
                <Link to={`/findings/${s.id}`}>View</Link>
                {' · '}
                <a href={api.reportUrl(s.id)} target="_blank" rel="noreferrer">Report</a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
