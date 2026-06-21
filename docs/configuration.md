# Configuration — endpoints, editions, environments

Nothing about *where* systems live is hardcoded. Each integration has a configurable **base URL**,
**edition** (Cloud vs Server/DC), and **auth type**. Per-user **tokens/secrets are NOT here** — they live
in the per-user secret store ([security-auth-and-credentials.md](security-auth-and-credentials.md)).

## Shape

`veritas.connections` (in `application.yml`, overridable by env vars, and — on the server — editable per
environment via the dashboard settings UI):

```yaml
veritas:
  llm:
    mode: http                                   # mock | copilot (CLI) | http (Copilot HTTP API, device flow)
    model: claude-sonnet-4.6                      # default model id; cost-aware per-tier selection in model-policy.yaml
    binary: copilot                               # only when mode=copilot (the CLI)
    context-budget-chars: 16000                   # per untrusted input block (context trimming)
  copilot:                                        # used when mode=http (the device-flow HTTP gateway)
    client-id: Iv1.b507a08c87ecfe98
    github-base: https://github.com
    api-base: https://api.github.com
    copilot-base: https://api.githubcopilot.com
    token-file: ${user.home}/.veritas/copilot-auth.json   # written by `veritas copilot-login`, owner-only perms
  http:
    powershell-fallback: false                    # true if the JVM is firewall-blocked but powershell.exe is allowed
  connections:
    bitbucket:
      base-url: https://api.bitbucket.org         # or the internal vanity host fronting Bitbucket Cloud
      edition: CLOUD                              # Bitbucket: CLOUD client only (Server/DC not yet implemented)
      workspace: <bnc-workspace>                  # app-id = project key within the workspace
      auth-type: APP_PASSWORD                     # APP_PASSWORD | OAUTH
    jira:
      base-url: https://jira.bnc
      edition: SERVER_DC                          # SERVER_DC (default: wiki + REST v2 + Bearer PAT) | CLOUD (ADF + REST v3 + Basic)
      auth-type: BEARER                           # BEARER (PAT) | BASIC (user + api token)
    confluence:
      base-url: https://confluence.bnc
      edition: CLOUD                              # Confluence: CLOUD client only
      auth-type: BEARER
    xray:
      edition: SERVER_DC                          # SERVER_DC (default: Raven REST on the Jira host) | CLOUD (GraphQL)
      base-url: https://xray.cloud.getxray.app    # Cloud only; SERVER_DC reuses the Jira base-url
      auth-type: BEARER                           # SERVER_DC: Bearer (Xray/Jira PAT) | CLOUD: CLIENT_CREDENTIALS
```

## Rules

- **Jira and Xray default to `SERVER_DC`** (REST v2 + wiki markup + Bearer PAT; Xray = "Raven" REST on the
  Jira host) — matching the BNC contract-validator app this was modelled on. Set `edition: CLOUD` to switch
  the active client to Jira REST v3/ADF + Xray GraphQL. **Bitbucket and Confluence** ship Cloud-only clients
  today (their `edition` flag is reserved). Bitbucket app-id = a **project key** within the **workspace** →
  `GET /2.0/repositories/{workspace}?q=project.key="{appId}"`.
- **Edition drives behavior** (not the hostname): the `@ConditionalOnProperty` on each client selects the
  REST/auth variant from the configured `edition`. An internal vanity host (e.g. `jira.bnc`) doesn't change
  this — set `edition` explicitly; don't infer it from the URL.
- **Auto-probe as a safety net:** on first connect we can probe (`/rest/api/3` vs `/rest/api/2`,
  `serverInfo` deploymentType) and warn if the probe disagrees with the configured `edition` — but the
  config value wins.
- **Tokens never here.** Config holds only non-secret base URLs/editions/auth-type. Secrets resolve
  per-user from keychain/enc-file/env/Vault.
- **Multiple environments** (optional): a `profiles`/`environments` map keyed by name (dev/staging/prod),
  selectable per run, each with its own base URLs.
- **Corporate proxy / CA:** standard JVM `https.proxyHost`/`proxyPort` + a configurable truststore for the
  internal CA; documented as required endpoints in [open-questions-and-risks.md](open-questions-and-risks.md).
- **Validation at startup:** malformed/missing base URLs fail fast with a clear message; a `connections`
  health check is exposed for the dashboard.

## Where it's consumed

The `integration/{bitbucket,jira,xray,confluence}` clients are constructed from this config (base URL +
edition picks the REST/GraphQL variant); the secret provider supplies the matching per-user token. Built as
part of task #9 (Bitbucket) and #14 (Jira/Confluence ingestion).

## Datasource — local SQLite → server Postgres

Default (no profile) uses an embedded **SQLite** file (`veritas.db`) — zero-setup for local runs. For a
server deployment, activate the **`postgres`** profile (`application-postgres.yml`):

```
java -jar veritas.jar serve --spring.profiles.active=postgres
# or
SPRING_PROFILES_ACTIVE=postgres java -jar veritas.jar serve
```

Connection details come from the environment so no secrets sit in the image or repo:

| Env var | Default | Purpose |
|---|---|---|
| `VERITAS_DB_URL` | `jdbc:postgresql://localhost:5432/veritas` | JDBC URL |
| `VERITAS_DB_USER` | `veritas` | DB user |
| `VERITAS_DB_PASSWORD` | _(empty)_ | DB password |
| `VERITAS_DB_POOL_MAX` | `10` | Hikari max pool size |

The profile switches the Hibernate dialect to `PostgreSQLDialect` and the driver to `org.postgresql.Driver`;
`ddl-auto=update` keeps the schema in sync until Flyway migrations land (later phase).
