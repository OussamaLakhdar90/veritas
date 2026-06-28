# Framework comparison — NEW `lsist` vs OLD `ca.bnc.ciam.autotests`

> Verified from: 55 phone screenshots of the NEW framework in Bitbucket
> (`APP7488 / lsist-test-framework-api-template`, author Mohamed Jlassi, branch `develop`) **and** the OLD framework's
> real source at `d:\Mohamed\ca-bnc-ciam-autotests`. Cross-checked by hand on the screenshots that matter most
> (`Base.java`, `SampleTokenHelper.java`, `SampleBaseTest.java`, `pom.xml`). OCR was accurate.
>
> **Caveat:** the NEW framework's *library* internals (`RobotToken`, `AbstractTestBase`, `RestClient` exact
> signatures) live in the `lsist-api` / `lsist-bom` artifacts, which were NOT in the screenshots — those are inferred
> from the template's call sites, not from their own source.

## TL;DR
The OLD monolithic `ca.bnc.ciam.autotests` has been **refactored into reusable library artifacts**
(`ca.bnc.lsist:lsist-test-framework-api` 1.0.1 via `lsist-test-framework-bom` 1.0.6 → `ca.bnc.lsist.core` /
`ca.bnc.lsist.api`) **plus a thin copy-and-rename template repo** (`lsist-test-framework-api-template`,
groupId `ca.bnc.api.tests`, Java 21). The core patterns carry over (World context, fluent `TestData`,
`@Factory`+`@DataProvider`+`TEST_ID`, `@Xray`/`@DependentStep`, AssertionHelper, TestNG + REST Assured,
`data-manager.json`), but auth, the REST call signature, packages, and the base layering all changed.

## Side-by-side

| Aspect | OLD — `ca.bnc.ciam.autotests` | NEW — `lsist` |
|---|---|---|
| Distribution | one framework repo | library (`lsist-api`/`lsist-bom`) + template repo `lsist-test-framework-api-template` |
| Packages | `ca.bnc.ciam.autotests.*` | `ca.bnc.lsist.core.*` + `ca.bnc.lsist.api.*`; project code in `base`, `utils`, `listener`, `SampleApi.*` |
| Base class | extend `AbstractDataDrivenTest` directly | framework `AbstractTestBase` → **project `base.Base`** with `rest()`, `logStep`, `logResponseStatusCode` |
| HTTP client | `RestClient` builder; `restClient.withAuth(token).get(path)` | shared `rest()`; **`rest().get(endpoint, jwt, context)`** — positional `jwt` + trailing **`context`** on every verb |
| Token WorldKey | `WorldKey.AUTH_TOKEN` | **`WorldKey.ROBOT_TOKEN`** (single) + `WorldKey.CONTEXT` |
| **Token generation** | none in lib (left to caller) | **Okta private-key JWT assertion**: `SampleTokenHelper.getToken(testData, Scope)` → `new RobotToken().getOktaTokenWithPrivateKey(privateKey, Set.of(scope), AUTH_SERVER_TOKEN_URL, CLIENT_ID)`; private key read from `oktaCredentials.json`; scopes from a **`SampleScope` enum** (READ/WRITE/DELETE → `sample:resource:*`) |
| Test structure | base test + validation test | **4-level inheritance chain**: `Base → {Svc}BaseTest` (t000 token, t001 create→201, t999 delete→204) `→ {Svc}GetTest` (t002 GET, store Response) `→ {Svc}ValidateTest` (t003 structure, t004 fields; `@Factory`/`@DataProvider`/`@Xray`) |
| Data loader | `TestData.from(f).forIndex(i).getForKey(k)` → `{f}:{k}:{i}`, `as-json-`, `$sensitive:` | same `TestData` fluent API (`ca.bnc.lsist.api.data`) |
| Env / data-manager | `BaseEnvironment.buildTestEnvironment(TEST_ID)`; data-manager via `bnc.data.manager`/`debug_config.json` | **`ApiEnvironment.buildTestEnvironment(TEST_ID)`**; **SPI-registered `TestngListener`** sets `bnc.data.manager = data/<testEnvironment>/data-manager.json` from `-DtestEnvironment` |
| Listener registration | TestNG listener | **SPI** file `META-INF/services/org.testng.ITestNGListener` (no `<listeners>`) |
| Assertions | `Validate.isNotNull(value, "ctx")` (flat) | **`Validate.Objects.isNotNull(...)`** (nested); `AssertionHelper.compareFieldByFieldRecursively` / `assertResponseStructure` |
| Traceability | `@Xray(requirement=, test=, requirements=, summary=, labels=)`; `@DependentStep` | same |
| serverConfig | base URL + endpoints in data | `serverConfig.json`: `forIndex(1).getForKey("{Svc}BaseUrl")` for base URL, `forIndex(2).getForKey("create_resource"/"get_resource"/...)` for endpoints; `getApiUrl(template, Map.of("{Svc}BaseUrl", baseUrl, "resource_id", id))` |
| Build / CI | — | Java 21, Log4j2; `Jenkinsfile` uses shared lib + Vault creds (`test-auto-secrets`); `settings.xml` → internal Artifactory |

## NEW framework — file map (template repo)
| File | Role |
|---|---|
| `src/main/java/base/Base.java` | project base: `extends AbstractTestBase`; `rest()`, `logStep`, `logResponseStatusCode`; rename to `MyApiBase` |
| `src/main/java/utils/SampleScope.java` | OAuth-scope enum (READ/WRITE/DELETE → `sample:resource:*`); rename per API |
| `src/main/java/utils/SampleTokenHelper.java` | `static String getToken(Map testData, SampleScope)` → Okta private-key-JWT via `RobotToken` |
| `src/main/java/listener/TestngListener.java` | `extends ApiTestListener`; SPI-registered; selects per-env `data-manager.json` |
| `src/main/resources/META-INF/services/org.testng.ITestNGListener` | SPI registration (`listener.TestngListener`) |
| `src/test/java/SampleApi/pretest/SampleBaseTest.java` | `extends Base`; t000 token (WRITE), t001 create (201), t999 delete (204) |
| `src/test/java/SampleApi/test/base/SampleGetTest.java` | `extends SampleBaseTest`; t002 GET, store Response |
| `src/test/java/SampleApi/test/happyPath/SampleValidateTest.java` | `extends SampleGetTest`; `@Xray`/`@Factory`; t003 structure, t004 fields |
| `suites/all.xml` | TestNG suite; listener auto-registered (no `<listeners>`) |
| `pom.xml` | `ca.bnc.api.tests:lsist-test-framework-api-template:1.0.0`; imports `lsist-bom`; Java 21 |
| `Jenkinsfile` / `settings.xml` | CI: shared pipeline + Vault creds; Artifactory mirror |

## Impact on Veritas (the actionable part)
Veritas's current `autotests-template.md` (and the auth-token feature shipped in PRs #109–111) modelled
**`config.yml → service_auth` with `client_id`/`client_secret`/basic-auth and multi-group `TPPS/APPS` tokens** — which
**does not exist** in this framework. Corrections required:

1. **Auth = Okta private-key JWT** (biggest): drop `service_auth`/`client_secret`/multi-group. Real model = an
   `oktaCredentials.json` private key + a token helper (`getToken(testData, Scope)`) + a **`Scope` enum** +
   `RobotToken.getOktaTokenWithPrivateKey(privateKey, scopes, AUTH_SERVER_TOKEN_URL, CLIENT_ID)`. One `ROBOT_TOKEN`.
2. **REST signature**: every call takes a trailing **`context`** arg — `rest().get(endpoint, jwt, context)`.
3. **WorldKey**: only `ROBOT_TOKEN` (remove `TPPS/APPS/SCOPES`).
4. **Env class**: `ApiEnvironment.buildTestEnvironment(TEST_ID)` (not `Environment`).
5. **Data-manager selection**: SPI listener sets `bnc.data.manager` from `-DtestEnvironment`.
6. **Keep** (already correct): inheritance chain, `@Factory`/`@DataProvider`/`TEST_ID` on the validation class,
   `@Xray`/`@DependentStep`, `AssertionHelper`, `Validate.Objects.*`, `as-json-` keys, SPI listener, suites.
