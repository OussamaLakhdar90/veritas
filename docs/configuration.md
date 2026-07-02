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
    snyk:
      base-url: https://api.snyk.io               # Snyk SaaS REST/v1; auth is a personal API token ("token <key>")
      auth-type: BEARER
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

## Snyk dependency-security module

The Automation → Snyk feature watches app-ids for new vulnerabilities and opens a cascade of dependency-bump
PRs across the lsist test framework. Connect it in **Settings → Snyk** (base URL + a personal API token) — the
token is stored as the secret **`SNYK_API_TOKEN`** (owner-only, never in config). The poller is **off by
default** — enable it in the Settings/Snyk UI or with `veritas.snyk.poll-enabled=true`.

```yaml
veritas:
  connections:
    snyk:
      base-url: https://api.snyk.io      # Snyk SaaS; auth = personal API token ("token <key>")
  snyk:
    api-version: "2024-10-15"            # Snyk REST API version date (?version=)
    poll-enabled: false                  # background vulnerability polling — OFF by default
    poll-interval-ms: 86400000           # once a day between polls
    poll-initial-delay-ms: 60000         # first poll ~1 min after startup
    framework:                           # where the lsist test framework lives (the fix-cascade targets)
      project: APP7488                   # Bitbucket project holding bom/core/api/web (MUST match your setup)
      branch: develop                    # integration branch the cascade branches off + opens PRs against
      consumer-repo: application-tests   # repo (under each watched app-id) holding the consumer poms Veritas bumps
      default-reviewers: []              # Bitbucket usernames added when git history yields no reviewer
```

**Required secret:** `SNYK_API_TOKEN` (from *app.snyk.io → Account settings*). **Per-deployment overrides you
should review:** `framework.project` (the Bitbucket project key holding the framework repos), `framework.branch`
(if your framework repos integrate on `main`/`master`), `framework.consumer-repo` (if your test repos are not
named `application-tests`), and `framework.default-reviewers`. The four `framework.*-repo` slugs and the
`framework.*-version-property` names are also overridable if your naming differs — the local reactor build is the
safety net if any name is wrong (a bad name blocks the PRs rather than shipping a broken bump).

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

## Self-serve configuration (in the app — no terminal, no env vars)

A non-technical QA configures everything from the dashboard **Settings** page; nothing above needs to be
hand-edited. The page is backed by the `settings/` REST API (`/api/v1/settings/**`) and reuses the existing
secret store and connection beans:

| What | UI | Endpoint | Stored |
|---|---|---|---|
| Connection URLs / edition / workspace / auth-type | per-service cards | `GET`/`PUT /settings/connections` | `~/.veritas/connections.json` (non-secret) |
| Tokens / passwords | write-only fields ("● configured" vs "not set") | `POST /settings/secrets` (204, never echoed); `GET` returns booleans only | encrypted `secrets.enc` (AES-GCM) |
| Live "Test connection" | button → status pill | `POST /settings/connections/{service}/test` | nothing (read-only `whoAmI`) |
| GitHub → Copilot sign-in | device-flow modal (user code + link) | `POST /settings/copilot/login/start` → poll `…/login/status`; `…/status`; `…/signout` | `copilot-auth.json` (owner-only) |
| Setup checklist | readiness list on Settings + a banner on the dashboard | `GET /preflight` | — |

Notes:
- **Passphrase bootstrap (local).** On first start, a `SettingsEnvironmentPostProcessor` auto-generates a
  machine-bound, owner-only `~/.veritas/secret.key` and injects it as `veritas.secret.passphrase` before any
  binding, so the AES-GCM store works with **zero setup**. Gated by `veritas.secret.auto-passphrase: true`
  (default) — set **false** on the `server` profile (see below) to require Vault/KMS instead.
- **`connections.json` overlay.** The same post-processor overlays the persisted file pre-binding; most fields
  apply immediately because clients read `ConnectionsProperties` per request. **`edition` is the exception** —
  its client wiring is fixed at startup, so `PUT /settings/connections` returns `restartRequiredFields` and
  the UI shows "restart to apply" when only the edition changed.
- **Secrets never leave the box** in DB rows, DTOs, logs, YAML, or generated files; the GET status endpoint
  returns booleans, never values.

## Building the dashboard

The compiled dashboard is **committed** under `src/main/resources/static`, so the default build is
**node-free** (CI- and offline-friendly):

```
mvn package                 # uses the committed UI build
```

To rebuild the UI from source, use the opt-in profile (needs Node; downloads a pinned v22.11.0 and runs
`npm ci` + `npm run build`, which vite writes back into `src/main/resources/static`):

```
mvn -Pdashboard package
```

## EC2 / multi-user (server profile)

Locally the server binds to **127.0.0.1** and trusts the single OS user. For off-box (EC2, shared host)
deployment, activate the **`server`** profile (`application-server.yml`), e.g.
`--spring.profiles.active=server,postgres`. It turns on:

- **Authentication** — `ServerApiAuthFilter` guards `/api/v1/**` with a bearer token
  (`veritas.server.api-token`, from a secret manager). It **fails closed**: if the token is unset on the
  server profile, every API request is rejected (503). This is the working seam; swap it for an **OIDC/SAML**
  resource-server filter once the bank's IdP is chosen — the controllers and `CurrentUser` contract don't change.
- **Per-principal secrets** — `veritas.secret.per-principal: true` stores each user's tokens under
  `~/.veritas/secrets/<principal>.enc`, and `auto-passphrase: false` disables the home-dir machine key (provide
  `veritas.secret.passphrase` from **Vault/KMS**). The principal is `ServerCurrentUser` (request-scoped, reads
  the `X-Veritas-User` / `X-Forwarded-User` header the auth proxy/OIDC filter sets).
- **TLS** — `server.ssl.*` is wired in `application-server.yml` (env-driven keystore); terminate TLS here or at
  a fronting proxy so the device-flow user code and settings traffic never cross the network in clear.
- **Bind address** — `server.address` defaults to `0.0.0.0` on this profile, safe **only** because the auth
  filter + TLS above are active.

Still external (not code): provisioning the actual Vault/KMS passphrase and the OIDC IdP. See
[security-auth-and-credentials.md](security-auth-and-credentials.md).
