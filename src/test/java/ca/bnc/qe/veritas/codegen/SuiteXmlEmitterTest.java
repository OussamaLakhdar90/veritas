package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The suite emitter builds the three TestNG suites deterministically from the generated test-class paths. */
class SuiteXmlEmitterTest {

    private final SuiteXmlEmitter emitter = new SuiteXmlEmitter();

    @Test
    void emitsSmokeRegressionFullFromTestClasses() {
        List<String> written = List.of(
                "src/test/java/ciamPoliciesApi/test/happyPath/ValidateGetPolicyTest.java",
                "src/test/java/ciamPoliciesApi/test/errorCase/ValidateCreatePolicyErrorTest.java",
                "src/test/java/ciamPoliciesApi/base/BaseApiTest.java",   // not a *Test.java leaf → excluded
                "ciam-policies.http");                                    // not a test class → excluded

        Map<String, String> suites = emitter.emit("ciam-policies", written);

        assertThat(suites).containsOnlyKeys("ciam-policies-smoke.xml", "ciam-policies-regression.xml", "ciam-policies.xml");

        String smoke = suites.get("ciam-policies-smoke.xml");
        assertThat(smoke).contains("<include name=\"P0\"/>");
        assertThat(smoke).doesNotContain("<include name=\"P1\"/>");
        assertThat(smoke).contains("ciamPoliciesApi.test.happyPath.ValidateGetPolicyTest");
        assertThat(smoke).contains("ciamPoliciesApi.test.errorCase.ValidateCreatePolicyErrorTest");
        assertThat(smoke).doesNotContain("BaseApiTest");   // base class excluded

        String regression = suites.get("ciam-policies-regression.xml");
        assertThat(regression).contains("<include name=\"P0\"/>").contains("<include name=\"P1\"/>");

        String full = suites.get("ciam-policies.xml");
        assertThat(full).doesNotContain("<include name=");   // full suite runs every group
        assertThat(full).contains("<!DOCTYPE suite SYSTEM \"https://testng.org/testng-1.0.dtd\">");
    }

    @Test
    void emitsNothingWhenNoTestClasses() {
        assertThat(emitter.emit("ciam-policies", List.of("ciam-policies.http", "data/serverConfig.json"))).isEmpty();
    }
}
