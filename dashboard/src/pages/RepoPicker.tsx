import { useState } from 'react'
import { api, Repo } from '../api'

export function RepoPicker() {
  const [appId, setAppId] = useState('')
  const [repos, setRepos] = useState<Repo[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)

  const search = () => {
    setErr('')
    setLoading(true)
    api.repos(appId)
      .then(setRepos)
      .catch((e) => setErr(String(e)))
      .finally(() => { setLoading(false); setSearched(true) })
  }

  const validate = (r: Repo) => {
    const spec = window.prompt('Spec path (relative to the repo, e.g. openapi.yaml):')
    if (!spec) return
    api.triggerScan({ appId, repoSlug: r.slug, specPaths: [spec] })
      .then((res) => window.alert(`Scan ${res.scanId}: ${res.totalFindings} findings`))
      .catch((e) => window.alert('Failed: ' + e))
  }

  return (
    <div>
      <h1>Validate by app-id</h1>
      <div className="row">
        <input
          placeholder="App-id (e.g. APP7571)"
          value={appId}
          onChange={(e) => setAppId(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && appId && search()}
        />
        <button onClick={search} disabled={!appId || loading}>
          {loading ? 'Searching…' : 'Find repos'}
        </button>
      </div>
      {err && <p className="muted">{err}</p>}
      {repos.length > 0 && (
        <table>
          <thead>
            <tr><th>Repo</th><th>Project</th><th>Default branch</th><th>Description</th><th></th></tr>
          </thead>
          <tbody>
            {repos.map((r) => (
              <tr key={r.slug}>
                <td><code>{r.slug}</code></td>
                <td>{r.projectKey}</td>
                <td>{r.defaultBranch}</td>
                <td className="muted">{r.description}</td>
                <td><button onClick={() => validate(r)}>Validate</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {!repos.length && !err && (
        <p className="muted">
          {searched ? 'No accessible repos found for that app-id.' : 'Enter an app-id to list the repos you can access.'}
        </p>
      )}
    </div>
  )
}
