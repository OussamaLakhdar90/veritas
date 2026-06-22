# Continuous Integration (CI)

How the automated build/test pipeline works, technically. Source of truth:
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

## What it is

A single **GitHub Actions** workflow named `CI`. GitHub auto-discovers any `*.yml` under
`.github/workflows/`, reads the `on:` block to decide when to run it, and executes the `jobs:` on
GitHub-hosted runners. No external CI service (Jenkins/CircleCI) is involved.

## Triggers (`on:`)

```yaml
on:
  push:
    branches: [ main, master ]
  pull_request:
```

- **`push`** to `main` or `master` → runs after commits land (e.g. a `git push origin main`).
- **`pull_request`** → runs against the **merge commit** (a simulated merge of the PR branch into its
  base), on open / synchronize / reopen. This is the green check that gates a PR.
- A push to a feature branch with **no** open PR runs nothing (the `push` trigger is filtered to
  `main`/`master`; the PR trigger covers everything else). Typical flow: branch → open PR (CI runs) →
  merge to `main` (CI runs again).

## Execution model

Two **jobs**: `build` and `docker`. Each job:

- gets a **fresh, ephemeral `ubuntu-latest` VM** — nothing persists between jobs or runs except what is
  explicitly cached or uploaded;
- runs its `steps:` sequentially; **any step exiting non-zero fails the job** and stops it;
- `docker` declares `needs: build`, so the jobs form a DAG — `build` runs first, and `docker` starts
  only if `build` succeeded. (Without `needs`, jobs run in parallel.)

## Job `build` — "Build & test"

```yaml
runs-on: ubuntu-latest
steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
    with: { distribution: temurin, java-version: '21', cache: maven }
  - run: mvn -B -ntp verify
  - uses: actions/upload-artifact@v4
    if: always()
    with: { name: surefire-reports, path: target/surefire-reports/, if-no-files-found: ignore }
```

1. **`actions/checkout@v4`** — clones the repo at the triggering commit into the runner workspace
   (shallow, depth 1). Without it the VM is empty.

2. **`actions/setup-java@v4`** — installs **Temurin JDK 21**, puts it on `PATH`, sets `JAVA_HOME`.
   `cache: maven` hashes `pom.xml`/lockfiles into a cache key and restores `~/.m2/repository` at the
   start, saving it at the end. The first run downloads all dependencies; later runs with an unchanged
   `pom.xml` restore the cache and skip the re-download.

3. **`mvn -B -ntp verify`** — the actual gate.
   - `-B` (batch mode): non-interactive, no ANSI/progress spinners — clean CI logs.
   - `-ntp` (`--no-transfer-progress`): suppresses per-artifact download lines.
   - **`verify`** is a Maven lifecycle *phase*, and Maven runs **every phase up to and including it**:
     `validate → compile → test-compile → test → package → integration-test → verify`. So this one
     command compiles main + test sources, runs **all unit tests via Surefire** (`test`), builds the
     **fat jar** via `spring-boot-maven-plugin` (`package`), and runs any Failsafe integration tests
     (`verify`). A failed test or a jar that won't assemble exits non-zero → the job goes red. This is a
     *stronger* gate than a local `mvn test`, which stops at `test` and never packages.

4. **`actions/upload-artifact@v4`** with **`if: always()`** — `always()` forces this step to run **even
   if `mvn verify` failed** (a failed step normally aborts the rest). It zips `target/surefire-reports/`
   (per-test XML/txt results) and attaches them to the run so you can download and see exactly which
   test failed. `if-no-files-found: ignore` keeps the upload from failing if the dir is missing (e.g.
   compilation failed before any test ran).

## Job `docker` — "Build Docker image"

```yaml
needs: build
if: github.event_name == 'push'
steps:
  - uses: actions/checkout@v4
  - run: docker build -t veritas:${{ github.sha }} .
```

- **`needs: build`** — runs only if `build` passed.
- **`if: github.event_name == 'push'`** — a **job-level condition**: build the image only on real pushes
  (merges to `main`/`master`), **not** on pull requests. Keeps PR feedback fast and avoids building
  images for code that may never merge.
- **`docker build -t veritas:${{ github.sha }} .`** — `ubuntu-latest` ships Docker preinstalled. It
  builds the repo `Dockerfile`, tagging the image with `${{ github.sha }}` (the exact commit SHA, a
  built-in context variable). This proves the image still assembles. It only **builds** — there is no
  `docker login`/`docker push` and no registry secret, so nothing is published; it is purely a "does it
  still containerize" check.

## Security / secrets

There are **no `secrets.*` references** anywhere, so the workflow needs no configured credentials and
can't leak any. The build is hermetic: tests use WireMock / SQLite / mocks, never live BNC endpoints.
To publish the Docker image later, add a `secrets.REGISTRY_TOKEN` + a `docker/login-action` step before
a `docker push`.

## What it deliberately does *not* cover

- **The React dashboard.** CI runs the **default Maven profile**, which is node-free and ships the
  *committed* `src/main/resources/static` build. The `-Pdashboard` profile (which downloads Node and
  runs `npm ci && npm run build`) is **not** invoked, so a frontend-only break — or a stale committed
  `static/` vs. the current `dashboard/src` — won't be caught here.
- **No coverage threshold, linting, or release signing** — just compile + test + package + image build.

## Local equivalent

| CI does | Locally |
|---|---|
| `mvn -B -ntp verify` (compile + test + package) | `mvn -o test` (add `package` to match fully) |
| Temurin JDK 21 | local JDK 21 + Maven (`& "$env:M2_HOME\bin\mvn.cmd"`) |
| `docker build .` | only relevant when containerizing |

When local `mvn test` is green on a commit, CI's `build` job is green too (it additionally packages the
jar, which is reliable).

## Viewing results

- Web: `https://github.com/OussamaLakhdar90/veritas/actions`
- CLI: `gh auth login` once, then `gh run list --repo OussamaLakhdar90/veritas` and
  `gh run view <id> --log`.

## Possible hardening (not yet done)

- Add a **`-Pdashboard` lane** that runs `npm ci && npm run build` so frontend breakage is caught.
- Add a **drift check** that fails if `git diff` shows the committed `static/` is stale vs. a fresh
  `npm run build`.
- Add **JaCoCo coverage** with a minimum threshold, and publish the Surefire results as a PR check
  summary (e.g. `dorny/test-reporter`).
