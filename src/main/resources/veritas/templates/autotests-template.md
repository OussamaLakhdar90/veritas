---
framework:
  name: "TestNG + Rest-Assured (ca.bnc.lsist.api)"
  language: java
buildTool: maven
verifyCommand: "mvn -q -DskipTests test-compile"
packageRoot: "{serviceName}Api"
layout:
  models: "src/main/java/models"
  baseTests: "src/test/java/{serviceName}Api/test/base"
  happyPath: "src/test/java/{serviceName}Api/test/happyPath"
  errorCase: "src/test/java/{serviceName}Api/test/errorCase"
  data: "src/test/resources/data"
  suites: "suites"
---

# Code Templates — Unified Reference

<!-- Reconstructed from the BNC contract-validator reference (templates.md v1.5) | vendored into Veritas as the
     default `templateSource` for implement-tests. The LLM MUST mirror this template and introduce no pattern
     absent from it. Framework: TestNG + Rest-Assured over ca.bnc.lsist.api (the BNC autotests framework). -->

> **Single parameterized file** replacing `java-test-templates.md`, `data-file-templates.md`, and `suite-templates.md`.
> Loaded **once** when the agent reaches the implementation phase. Covers all steps: Java classes, JSON data, suite XML.

---

## Placeholder Legend

| Placeholder | Meaning | Example |
|-------------|---------|---------|
| `{serviceName}` | camelCase package | `petStoreMockApi` |
| `{ServiceName}` | PascalCase prefix | `PetStoreMock` |
| `{Action}` | PascalCase verb+entity | `GetPet`, `CreateOrder` |
| `{action_desc}` | snake_case method desc | `Get_Pet_By_Id` |
| `{entity}` | entity name (lowerCamel) | `pet`, `order` |
| `{baseUrlKey}` | serverConfig base URL key | `Pet Store Base Url` |
| `{endpointKey}` | serverConfig endpoint key | `get_pet` |
| `{tN}` / `{tN+1}` | method number prefix | sequential per `@DependentStep` ordering |

---

## 1. Response Model

`src/main/java/models/{Name}Response.java`

```java
package models;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class {Name}Response {
    // Match fields from the service response DTO.
    // Nested objects        -> public static inner class with @Getter @Setter
    // Dynamic fields        -> @HasToBeIgnoredForAssertion   (timestamps, generated IDs)
    // List endpoints        -> add: List<{Item}> content;  + pagination: totalElements, totalPages, size, number
}
```

---

## 2. Base Test — Unified Template

`src/test/java/{serviceName}Api/test/base/{Action}Test.java`       — happy path
`src/test/java/{serviceName}Api/test/base/{Action}ErrorTest.java`  — error case (same structure; see variant table)

> **⚠️ CRITICAL — Base tests vs Validation tests**
> - Base tests (`test/base/`) **MUST NOT** declare `@Factory`, `@DataProvider`, `TEST_ID`, or `@Xray`. They are
>   **setup-only** classes that execute the API call and push the response into the World.
> - Only **Validation** classes (`test/happyPath/` or `test/errorCase/`) get `@Factory(dataProvider)`, `TEST_ID`,
>   `@Xray`, **extend** the base test, and add the assertion methods.
> - Base test classes have **no constructor** (they inherit from `Base`/pretest). Validation classes have a
>   `@Factory` constructor.

```java
package {serviceName}Api.test.base;

// ... imports: ApiEnvironment, TestData, Validate, WorldKey, HttpStatus, io.restassured.response.Response ...

    String entityId = td.from("{entity}Data.json").forIndex(1).getForKey("{field}");
    // POST/PUT/PATCH: load body ->
    //   JSONObject body = td.from("{entity}Data.json").forIndex(1)
    //       .getFormattedForKey("as-json-request-body", JSONObject.class);

    Validate.Objects.isNotNull(entityId, "entityId is not null");
    String endpoint = rest().getApiUrl(urlTemplate, Map.of("{baseUrlKey}", baseUrl, "{path_var}", entityId));

    // --- Execute (see HTTP method variant table below) ---
    Response rawResponse = rest().get(endpoint);

    Validate.Objects.isNotNull(rawResponse, "The response is not null");
    rawResponse.then().assertThat().statusCode(HttpStatus.SC_OK);
    pushToTheWorld(WorldKey.RAW_RESPONSE, rawResponse);   // ⚠️ OMIT this push for error base tests
} catch (Exception ex) {
    fail(ex.getMessage());
}
```

### HTTP method variants

| Method | Call | With auth |
|--------|------|-----------|
| GET    | `rest().get(endpoint)` | `rest().get(endpoint, jwt)` |
| POST   | `rest().post(endpoint, body.toString())` | `rest().post(endpoint, jwt, body.toString())` |
| PUT    | `rest().put(endpoint, body.toString())` | `rest().put(endpoint, jwt, body.toString())` |
| PATCH  | `rest().patch(endpoint, body.toString())` | `rest().patch(endpoint, jwt, body.toString())` |
| DELETE | `rest().delete(endpoint)` | `rest().delete(endpoint, jwt)` |

Auth token: `String jwt = pullFromTheWorld(WorldKey.ROBOT_TOKEN, String.class);`

> **Multi-group services:** if `config.yml` → `service_auth` defines multiple groups (e.g. tpps, apps), use a
> group-specific WorldKey: `WorldKey.TPPS_TOKEN`, `WorldKey.APPS_TOKEN`, `WorldKey.SCOPES_TOKEN`.

---

## 3. Validation Test — Unified Template

Both happy-path and error validation classes share the Factory/DataProvider boilerplate. Use the variant table to
set the package, parent, class name, and validation methods.

```java
package {serviceName}Api.test.{layer};   // happyPath OR errorCase

import {serviceName}Api.test.base.{ParentTest};
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

@Xray(requirement = "{xray_id}")   // from config.yml -> service_auth.{group}.xray_requirement; if empty -> "TODO-FILL"
@Test(groups = {"integration", "nonregression", "{priority}"})   // {priority} = P0 | P1 | P2 | P3
public class Validate{Action}Test extends {Action}Test {

    private static final String TEST_ID = "{Test ID from data-manager}";

    @Factory(dataProvider = "testData")
    public Validate{Action}Test(Map<String, String> testData) { this.testData = testData; }

    // --- 3a. Happy-path validation methods ---
    @Test
    @DependentStep
    public void {tN+1}_Validate_Response_Body_Fields() {
        try {
            JSONObject actual = pullFromTheWorld(WorldKey.ACTUAL_RESPONSE, JSONObject.class);
            JSONObject expected = pullFromTheWorld(WorldKey.EXPECTED_RESPONSE, JSONObject.class);
            Validate.Objects.isNotNull(actual, "Actual response is not null");
            Validate.Objects.isNotNull(expected, "Expected response is not null");
            // Option A (POJO — stable schema):
            //   {Name}Response a = JSONUtils.objectMapper(actual, {Name}Response.class);
            //   {Name}Response e = JSONUtils.objectMapper(expected, {Name}Response.class);
            //   AssertionHelper.compareFieldByFieldRecursively(a, e);
            // Option B (JSON — dynamic/polymorphic):
            //   AssertionHelper.assertResponseStructure(actual, expected);
        } catch (Exception ex) { fail(ex.getMessage()); }
    }

    // --- 3b. Error-case validation methods ---
    @Test
    @DependentStep
    public void {tN}_Validate_Error_Status_Code() {
        try {
            Response rawResponse = pullFromTheWorld(WorldKey.RAW_RESPONSE, Response.class);
            Validate.Objects.isNotNull(rawResponse, "Response is not null");
            TestData tdExpected = new TestData(testData).from("{entity}ErrorResponse.json").forIndex(1);
            int expectedStatus = Integer.parseInt(tdExpected.getForKey("expected_status"));
            rawResponse.then().assertThat().statusCode(expectedStatus);
        } catch (Exception ex) { fail(ex.getMessage()); }
    }

    @Test
    @DependentStep
    public void {tN+1}_Validate_Error_Response_Body() {
        try {
            Response rawResponse = pullFromTheWorld(WorldKey.RAW_RESPONSE, Response.class);
            JSONObject actual = readJSONObjectFromString(rawResponse.then().extract().body().asString());
            Validate.Objects.isNotNull(actual, "Error response body is not null");
            TestData tdExpected = new TestData(testData).from("{entity}ErrorResponse.json").forIndex(1);
            JSONObject expected = tdExpected.getFormattedForKey("as-json-error-response", JSONObject.class);
            Validate.Objects.isNotNull(expected, "Expected error response is not null");
            AssertionHelper.assertResponseStructure(actual, expected);
        } catch (Exception ex) { fail(ex.getMessage()); }
    }
}
```

### Variant table

| Variant | Package | Extends | Class name | Default priority |
|---------|---------|---------|------------|------------------|
| Happy path | `test.happyPath` | `{Action}Test` | `Validate{Action}Test` | P0 |
| Error case | `test.errorCase` | `{Action}ErrorTest` | `Validate{Action}ErrorTest` | P1 |

### Error categories to test per endpoint

| Status | Scenario | Technique |
|--------|----------|-----------|
| 400 | Missing required fields, invalid format, constraints | EP / BVA |
| 401 | No token, expired token, invalid token | EP |
| 403 | Valid token, insufficient scopes/roles | EP |
| 404 | Non-existent resource ID | EP |
| 409 | Duplicate creation, concurrent modification | State Transition |

---

## 4. Data Files

`{entity}Data.json` — happy-path request/expected data (one object per `@Factory` index).

`{entity}ErrorResponse.json`
```json
{
  "Not Found Error":   { "expected_status": "404", "as-json-error-response": { "status": 404, "error": "Not Found",   "message": "Entity not found" } },
  "Bad Request Error": { "expected_status": "400", "as-json-error-response": { "status": 400, "error": "Bad Request", "message": "Validation failed" } }
}
```

### Auth data
```json
{ "service_auth": { "client_id": "$sensitive:SERVICE_CLIENT_ID", "client_secret": "$sensitive:SERVICE_CLIENT_SECRET", "scope": "read write" } }
```

Use `$sensitive:ENV_VAR_NAME` for **all** credentials — never a literal secret. (Veritas secret-scans generated
files before write and will reject a literal credential.)

---

## 5. Suite XML

Generate **three suites** in `suites/` from this single template — only the group filter changes:

```xml
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="{Service Name} {SuiteLabel}" verbose="1">
  <test name="{Service Name} {TestLabel}">
    {GROUP_FILTER}
    <classes>
      <class name="{serviceName}Api.test.happyPath.Validate{Action}Test"/>
      <class name="{serviceName}Api.test.errorCase.Validate{Action}ErrorTest"/>
      <!-- one <class> per Validate* class -->
    </classes>
  </test>
</suite>
```

| File | `{SuiteLabel}` | `{GROUP_FILTER}` |
|------|----------------|------------------|
| `{svc}-smoke.xml` | `Smoke (P0)` | `<groups><run><include name="P0"/></run></groups>` |
| `{svc}-regression.xml` | `Regression (P0+P1)` | `<groups><run><include name="P0"/><include name="P1"/></run></groups>` |
| `{svc}.xml` | `API Tests — Full` | *(omit — runs all groups)* |

Priority annotation: `@Test(groups = {"integration", "nonregression", "P0"})` — P0/P1/P2/P3 per test plan.

**Execution:**
```bash
mvn test -Dsuite=suites/{svc}-smoke.xml      -Denv=staging-ta   # P0     (~1 min)
mvn test -Dsuite=suites/{svc}-regression.xml -Denv=staging-ta   # P0+P1  (~5 min)
mvn test -Dsuite=suites/{svc}.xml            -Denv=staging-ta   # All    (~15 min)
mvn test -Dsuite=suites/{svc}.xml            -Denv=dev          # Different env
```
