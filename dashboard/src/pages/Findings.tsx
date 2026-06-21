import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, Finding } from '../api'

const SEVERITY: Record<string, string> = {
  BLOCKER: '#6B21A8', CRITICAL: '#C2122D', MAJOR: '#C2410C', MINOR: '#CA8A04', INFO: '#3A4658',
}

export function Findings() {
  const { scanId } = useParams()
  const [findings, setFindings] = useState<Finding[]>([])
  const [err, setErr] = useState('')

  useEffect(() => {
    if (scanId) {
      api.findings(scanId).then(setFindings).catch((e) => setErr(String(e)))
    }
  }, [scanId])

  const raiseDefect = (f: Finding) => {
    const project = window.prompt('Jira project key for the defect?')
    if (!project) return
    api.createDefect(f.id, project)
      .then((r) => window.alert('Created defect ' + r.jiraKey))
      .catch((e) => window.alert('Failed: ' + e))
  }

  return (
    <div>
      <h1>Findings</h1>
      {err && <p className="muted">{err}</p>}
      <table>
        <thead>
          <tr><th>Severity</th><th>Layer</th><th>Endpoint</th><th>Spec</th><th>Summary</th><th>Evidence</th><th></th></tr>
        </thead>
        <tbody>
          {findings.map((f) => (
            <tr key={f.id}>
              <td><span className="badge" style={{ background: SEVERITY[f.severity] ?? '#6B7280' }}>{f.severity}</span></td>
              <td>{f.layer}</td>
              <td><code>{f.endpoint}</code></td>
              <td>{f.specSource}</td>
              <td>{f.summary}{f.explanation && <div className="muted">{f.explanation}</div>}</td>
              <td className="muted">
                {f.codeFile ? `${f.codeFile.split(/[\\/]/).pop()}${f.codeStartLine ? ':' + f.codeStartLine : ''}` : ''}
              </td>
              <td>
                {f.status === 'JIRA_CREATED'
                  ? <span className="muted">Jira created</span>
                  : <button onClick={() => raiseDefect(f)}>Defect</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
