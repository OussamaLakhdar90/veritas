---
framework:
  name: "TestNG + REST Assured (ca.bnc.lsist)"
  language: java
buildTool: maven
verifyCommand: "mvn -q -DskipTests test-compile"
packageRoot: "{serviceName}Api"
layout:
  models: "src/main/java/models"
  base: "src/main/java/base"
  utils: "src/main/java/utils"
  pretest: "src/test/java/{serviceName}Api/pretest"
  baseTests: "src/test/java/{serviceName}Api/test/base"
  happyPath: "src/test/java/{serviceName}Api/test/happyPath"
  errorCase: "src/test/java/{serviceName}Api/test/errorCase"
  data: "src/test/resources/data/{env}"
  suites: "suites"
---

# Code Templates — BNC `lsist` API test framework

<!-- Verified against the real framework: project APP7488 / lsist-test-framework-api-template (the copy-and-rename
     scaffold) which consumes the ca.bnc.lsist:lsist-test-framework-api library (lsist-api 1.0.1 / lsist-bom 1.0.6 ->
     ca.bnc.lsist.core + ca.bnc.lsist.api), Java 21, TestNG + REST Assured. The LLM MUST mirror this template and
     introduce no pattern absent from it. -->

> Framework utilities are imported from `ca.bnc.lsist.core.*` / `ca.bnc.lsist.api.*` (the library). The **generated,
> service-specific** code (response models, base/token/scope/test classes) lives in **local packages** (`models`,
> `base`, `utils`, `listener`, `{serviceName}Api.*`). Never invent framework classes.

---

## 0. Framework building blocks (the real API)

### Project base — `base/Base.java` (rename to `{ServiceName}ApiBase`)
`extends ca.bnc.lsist.core.base.AbstractTestBase`. Holds one shared `RestClient` and exposes:
- `rest()` → the shared `ca.bnc.lsist.core.rest.RestClient` (auto pretty-print logging + sensitive-data redaction)
- `logStep(String)` · `logResponseStatusCode(int)` · `log` (protected SLF4J)
- inherited: `pushToTheWorld(WorldKey, Object)` / `pullFromTheWorld(WorldKey, Class<T>)` — share state across `@DependentStep`

### HTTP — `rest()` (every verb takes a positional `jwt` AND a trailing `context`)
- `rest().get(endpoint, jwt, context)` · `rest().post(endpoint, jwt, body, context)` ·
  `rest().put(endpoint, jwt, body, context)` · `rest().delete(endpoint, jwt, context)` → RestAssured `Response`
- URL building: `String endpoint = rest().getApiUrl(endpointTemplate, Map.of("{BaseUrlKey}", baseUrl, "{pathVar}", id));`
- `jwt = pullFromTheWorld(WorldKey.ROBOT_TOKEN, String.class)`; `context` is loaded from data (a correlation/context id).

### Auth — Okta **private-key JWT assertion** (NO client_secret, NO basic auth, single token)
`utils/{ServiceName}TokenHelper.java` (rename of `SampleTokenHelper`):
```java
package utils;

import ca.bnc.lsist.api.data.TestData;
import ca.bnc.lsist.core.token.RobotToken;
import ca.bnc.lsist.core.utils.Validate;
import java.util.Map;
import java.util.Set;

/** Retrieves an OAuth access token via private-key JWT assertion against Okta. */
public final class {ServiceName}TokenHelper {
    // TODO: replace with your API's Okta values (from SERVICE_AUTH_SPEC).
    private static final String CLIENT_ID            = "{oktaClientId}";          // e.g. 0oa...
    private static final String AUTH_SERVER_TOKEN_URL = "{oktaTokenUrl}";         // .../oauth2/<auth-server>/v1/token
    private static final String OKTA_CREDENTIALS_FILE = "oktaCredentials.json";
    private static final String PRIVATE_KEY_FIELD     = "{privateKeyField}";      // e.g. MY_API_PRIVATE_KEY

    private {ServiceName}TokenHelper() {}

    public static String getToken(Map<String, String> testData, {ServiceName}Scope scope) throws Exception {
        RobotToken token = new RobotToken();
        String privateKey = new TestData(testData).from(OKTA_CREDENTIALS_FILE).forIndex(1).getForKey(PRIVATE_KEY_FIELD);
        Validate.Objects.isNotNull(privateKey, "private key is not null");
        String accessToken = token.getOktaTokenWithPrivateKey(
                privateKey, Set.of(scope.getValue()), AUTH_SERVER_TOKEN_URL, CLIENT_ID);
        Validate.Objects.isNotNull(accessToken, "access token is not null");
        return accessToken;
    }
}
```
`utils/{ServiceName}Scope.java` — the API's OAuth scopes (rename of `SampleScope`):
```java
package utils;

public enum {ServiceName}Scope {
    READ("{api:resource:read}"), WRITE("{api:resource:write}"), DELETE("{api:resource:delete}");
    private final String value;
    {ServiceName}Scope(String value) { this.value = value; }
    public String getValue() { return value; }
}
```
> The private key is read from `oktaCredentials.json` (a `$sensitive:` field — never a literal). Request the WRITE
> scope for create/update, READ for get/list, DELETE for delete.
>
> **Multiple token groups (per `SERVICE_AUTH_SPEC`):** a project that calls more than one API group declares one group
> per token. A single token uses `WorldKey.ROBOT_TOKEN`; with multiple tokens each group gets its own
> `{Name}TokenHelper` + `{Name}Scope` enum + **`WorldKey.ROBOT_TOKEN_{NAME}`** (e.g.
> `TppsTokenHelper`/`WorldKey.ROBOT_TOKEN_TPPS`, `AppsTokenHelper`/`WorldKey.ROBOT_TOKEN_APPS`), each with its own Okta
> token URL / client id / private-key field / scopes, and use the group whose `pathPrefix` matches the endpoint.

### Test data — `TestData` + `ApiEnvironment` (data-driven)
- `@DataProvider` returns `ca.bnc.lsist.api.environment.ApiEnvironment.buildTestEnvironment(TEST_ID)` → `Iterator<Object[]>`.
- `data-manager.json` (keyed by `TEST_ID`) maps each iteration to data files: `{ "TEST_ID": [ { "serverConfig.json":
  ["base","endpoints"], "{entity}Data.json": ["rec1"], "descriptor": "...", "comment": "..." } ] }`. The
  **SPI `TestngListener`** selects it via `-DtestEnvironment=<env>` → `bnc.data.manager = data/<env>/data-manager.json`.
- read: `td.from("serverConfig.json").forIndex(1).getForKey("{BaseUrlKey}")` (base URL block) and
  `.forIndex(2).getForKey("{endpointKey}")` (endpoints block); `getForKey("as-json-...")` for JSON bodies.
- secrets: `"$sensitive:ENV_VAR_NAME"` — auto-resolved from the environment; never a literal credential.

### Assertions + traceability
- `ca.bnc.lsist.core.utils.Validate.Objects.isNotNull(value, "context")` (nested `Objects` form).
- `ca.bnc.lsist.api.assertion.AssertionHelper.compareFieldByFieldRecursively(actual, expected, "context")` /
  `assertResponseStructure(actual, expected)`; `@HasToBeIgnoredForAssertion` on dynamic fields.
- `@ca.bnc.lsist.core.annotation.Xray(requirement = "...", test = "...")` — class-level; `@DependentStep` per step
  (`t000_`, `t001_`, … `t999_`; lexicographic order = run order).

---

## 1. Response model — `src/main/java/models/{Name}Response.java`
```java
package models;

import ca.bnc.lsist.core.annotation.HasToBeIgnoredForAssertion;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class {Name}Response {
    // Fields mirror the service response DTO (names from DATA_MODELS — never invented).
    // Nested -> public static inner class with @Getter @Setter. Dynamic fields -> @HasToBeIgnoredForAssertion.
}
```

## 2. Pretest (setup/teardown) — `src/test/java/{serviceName}Api/pretest/{ServiceName}BaseTest.java`
> **⚠️ Base tests vs Validation tests:** pretest/base classes are SETUP-ONLY — **no** `@Factory`, `@DataProvider`,
> `TEST_ID`, or `@Xray`. They obtain the token, call the API, and `pushToTheWorld`. The chain is
> `Base → {ServiceName}BaseTest → {ServiceName}GetTest → {ServiceName}ValidateTest`.
```java
package {serviceName}Api.pretest;

import base.Base;
import utils.{ServiceName}Scope;
import ca.bnc.lsist.api.data.TestData;
import ca.bnc.lsist.core.annotation.DependentStep;
import ca.bnc.lsist.core.utils.Validate;
import io.restassured.response.Response;
import java.util.Map;
import org.testng.annotations.Test;
import static utils.{ServiceName}TokenHelper.getToken;
import static org.testng.Assert.fail;

public class {ServiceName}BaseTest extends Base {

    @Test
    @DependentStep
    public void t000_Setup_Token() {
        try {
            logStep("Retrieving write token");
            String robotToken = getToken(testData, {ServiceName}Scope.WRITE);
            Validate.Objects.isNotNull(robotToken, "robotToken is not null");
            pushToTheWorld(WorldKey.ROBOT_TOKEN, robotToken);
        } catch (Exception ex) { fail(ex.getMessage()); }
    }

    @Test
    @DependentStep
    public void t001_Create_Resource() {
        try {
            String jwt = pullFromTheWorld(WorldKey.ROBOT_TOKEN, String.class);
            Validate.Objects.isNotNull(jwt, "Token is not null");

            TestData td = new TestData(testData);
            String baseUrl = td.from("serverConfig.json").forIndex(1).getForKey("{BaseUrlKey}");
            String createEndpoint = td.from("serverConfig.json").forIndex(2).getForKey("{create_endpoint_key}");
            String endpoint = rest().getApiUrl(createEndpoint, Map.of("{BaseUrlKey}", baseUrl));

            String requestBody = td.from("{entity}Data.json").forIndex(1).getForKey("as-json-create-request-body");
            String context = td.from("{entity}Data.json").forIndex(2).getForKey("context");

            logStep("POST create resource");
            Response response = rest().post(endpoint, jwt, requestBody, context);
            response.then().assertThat().statusCode(201);
            pushToTheWorld(WorldKey.CONTEXT, context);
            // store the created id from the response for t002/t999 (e.g. response.jsonPath().getString("id"))
        } catch (Exception ex) { fail(ex.getMessage()); }
    }

    @Test
    @DependentStep
    public void t999_Delete_Resource() {
        try {
            String jwt = getToken(testData, {ServiceName}Scope.DELETE);
            String context = pullFromTheWorld(WorldKey.CONTEXT, String.class);
            // build the delete endpoint with the stored id, then:
            Response response = rest().delete(/* deleteEndpoint */ "", jwt, context);
            response.then().assertThat().statusCode(204);
        } catch (Exception ex) { fail(ex.getMessage()); }
    }
}
```

## 3. GET base — `src/test/java/{serviceName}Api/test/base/{ServiceName}GetTest.java`
```java
package {serviceName}Api.test.base;

import {serviceName}Api.pretest.{ServiceName}BaseTest;
import utils.{ServiceName}Scope;
import ca.bnc.lsist.core.annotation.DependentStep;
import io.restassured.response.Response;
import org.testng.annotations.Test;
import static utils.{ServiceName}TokenHelper.getToken;
import static org.testng.Assert.fail;

public class {ServiceName}GetTest extends {ServiceName}BaseTest {

    @Test
    @DependentStep
    public void t002_Get_Resource() {
        try {
            String jwt = getToken(testData, {ServiceName}Scope.READ);
            String context = pullFromTheWorld(WorldKey.CONTEXT, String.class);
            // build the get endpoint with the stored id:
            Response response = rest().get(/* getEndpoint */ "", jwt, context);
            response.then().assertThat().statusCode(200);
            pushToTheWorld(WorldKey.RAW_RESPONSE, response);
        } catch (Exception ex) { fail(ex.getMessage()); }
    }
}
```

## 4. Validation — `src/test/java/{serviceName}Api/test/happyPath/{ServiceName}ValidateTest.java`
> Only the Validation class carries `@Factory`/`@DataProvider`/`TEST_ID`/`@Xray`, extends the GET base, and asserts.
```java
package {serviceName}Api.test.happyPath;

import {serviceName}Api.test.base.{ServiceName}GetTest;
import ca.bnc.lsist.api.data.TestData;
import ca.bnc.lsist.api.assertion.AssertionHelper;
import ca.bnc.lsist.api.environment.ApiEnvironment;
import ca.bnc.lsist.core.annotation.DependentStep;
import ca.bnc.lsist.core.annotation.Xray;
import ca.bnc.lsist.core.utils.Validate;
import io.restassured.response.Response;
import org.json.JSONObject;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;
import java.util.Iterator;
import java.util.Map;
import static ca.bnc.lsist.api.utils.JSONUtils.readJSONObjectFromString;
import static org.testng.Assert.fail;

@Xray(requirement = "{requirement}", test = "{xrayTest}")   // unknown -> "TODO-FILL"
@Test(groups = {"integration", "nonregression", "{priority}"})   // P0 | P1 | P2 | P3
public class {ServiceName}ValidateTest extends {ServiceName}GetTest {

    private static final String TEST_ID = "{Test ID from data-manager}";

    @Factory(dataProvider = "testData")
    public {ServiceName}ValidateTest(Map<String, String> testData) { setTestData(testData); }

    @DataProvider(name = "testData")
    public static Iterator<Object[]> testData() { return ApiEnvironment.buildTestEnvironment(TEST_ID); }

    @Test
    @DependentStep
    public void t003_Validate_Response_Structure() {
        try {
            Response response = pullFromTheWorld(WorldKey.RAW_RESPONSE, Response.class);
            JSONObject actual = readJSONObjectFromString(response.then().extract().body().asString());
            JSONObject expected = new TestData(testData).from("{entity}Response.json").forIndex(1)
                    .getJSONObject("as-json-expected-response");
            AssertionHelper.assertResponseStructure(actual, expected);
        } catch (Exception ex) { fail(ex.getMessage()); }
    }

    @Test
    @DependentStep
    public void t004_Validate_Field_Values() {
        try {
            Response response = pullFromTheWorld(WorldKey.RAW_RESPONSE, Response.class);
            {Name}Response actual = response.getBody().as({Name}Response.class);
            {Name}Response expected = /* map the expected from {entity}Response.json */ null;
            AssertionHelper.compareFieldByFieldRecursively(actual, expected, "{ServiceName} response");
        } catch (Exception ex) { fail(ex.getMessage()); }
    }
}
```
**Error case** (`test/errorCase/{ServiceName}ValidateErrorTest`): same shape, asserts the error status + body —
400 (missing/invalid fields, BVA/EP), 401 (no/invalid token), 403 (insufficient scope), 404 (unknown id), 409 (conflict).

## 5. Listener registration (once per project)
`src/main/java/listener/TestngListener.java` `extends ca.bnc.lsist.api.listener.ApiTestListener`; register via SPI —
`src/main/resources/META-INF/services/org.testng.ITestNGListener` containing the single line `listener.TestngListener`
(no `<listeners>` block in the suite).

## 6. Data files — `src/test/resources/data/{env}/`
- **`data-manager.json`** — `TEST_ID` → iterations → data-file → record-ids (see §0).
- **`serverConfig.json`** — `forIndex(1)` = base-URL block (`{BaseUrlKey}`), `forIndex(2)` = endpoints block
  (`{create_endpoint_key}`, `{get_endpoint_key}`, …).
- **`{entity}Data.json`** — request bodies (`as-json-create-request-body`) + `context`.
- **`{entity}Response.json`** — expected responses (`as-json-expected-response`).
- **`oktaCredentials.json`** — `{ "creds": { "{privateKeyField}": "$sensitive:MY_API_PRIVATE_KEY" } }` (never a literal).

## 7. Suite XML — `suites/all.xml`
A TestNG suite that includes the `{ServiceName}ValidateTest` classes; the listener is auto-registered via SPI (no
`<listeners>`). Group filters select smoke (P0) / regression (P0+P1) / full.
```xml
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="{Service Name} API Tests" group-by-instances="true">
  <test name="{Service Name}">
    <groups><run><include name="integration"/><include name="nonregression"/></run></groups>
    <classes>
      <class name="{serviceName}Api.test.happyPath.{ServiceName}ValidateTest"/>
    </classes>
  </test>
</suite>
```

**Execution:** `mvn test -DtestEnvironment=staging-ta` (the listener loads `data/staging-ta/data-manager.json`).
