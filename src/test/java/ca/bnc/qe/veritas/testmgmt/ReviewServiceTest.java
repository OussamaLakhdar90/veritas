package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayStep;
import ca.bnc.qe.veritas.integration.xray.XrayTest;
import ca.bnc.qe.veritas.persistence.ReviewResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class ReviewServiceTest {

    @Autowired
    private ReviewService reviewService;

    @MockBean
    private XrayClient xray;

    @Test
    void reviewsTestsFromXray() {
        when(xray.getTestsByJql(any())).thenReturn(List.of(
                new XrayTest("CIAM-1", "1001", "Validate create policy", "Manual",
                        List.of(new XrayStep("Call POST /policies", "valid", "201")))));

        List<ReviewResult> results = reviewService.reviewByJql("project = CIAM", "tester", false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTargetKey()).isEqualTo("CIAM-1");
        assertThat(results.get(0).getScore()).isEqualTo(78.0);
        assertThat(results.get(0).getVerdict()).isEqualTo("Solid");
        assertThat(results.get(0).isApplied()).isFalse();
        // structured deliverable: rubric + self-review confidence persisted
        assertThat(results.get(0).getConfidence()).isEqualTo(76.0);
        assertThat(results.get(0).getDeliverableJson()).contains("rubric").contains("selfReview");
    }
}
