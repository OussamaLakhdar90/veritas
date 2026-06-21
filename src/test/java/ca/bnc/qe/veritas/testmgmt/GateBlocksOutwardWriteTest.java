package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.persistence.TestCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/** With the gate in server mode (no auto-approve), an outward Xray write must be blocked until approved. */
@SpringBootTest
@TestPropertySource(properties = "veritas.gate.auto-approve=false")
class GateBlocksOutwardWriteTest {

    @Autowired private CreateTestCasesService service;
    @MockBean private XrayClient xray;

    @Test
    void pushToXrayIsBlockedWhenNotApproved() {
        TestCase tc = new TestCase();
        tc.setServiceName("ciam-policies");
        tc.setTitle("Validate create policy");
        tc.setStepsJson("[]");

        assertThatThrownBy(() -> service.pushToXray(tc, "CIAM", "tester"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting approval");

        verify(xray, never()).createTest(any());
    }
}
