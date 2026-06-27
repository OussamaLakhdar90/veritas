package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.secret.SecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/** Proves implement-tests is wired end-to-end: the gated publish step pushes + opens a PR via PrPublisher. */
@SpringBootTest
class CodegenPublishTest {

    @Autowired private CodegenService codegen;
    @Autowired private CodegenRunRepository runs;
    @MockBean private PrPublisher prPublisher;
    @MockBean private SecretProvider secrets;

    @BeforeEach
    void seedToken() {
        when(secrets.get("GIT_TOKEN")).thenReturn(Optional.of("git-pat"));   // satisfies the git write-scope preflight
    }

    @Test
    void publishOpensPrAndRecordsUrl() {
        when(prPublisher.publish(any())).thenReturn(
                new PrPublisher.PrResult("veritas/ciam-policies-tests", "https://bitbucket.bnc/ciam/pr/7"));

        CodegenRun run = new CodegenRun();
        run.setServiceName("ciam-policies");
        run.setOutputRepo(System.getProperty("java.io.tmpdir"));
        run.setBuildStatus("SKIPPED");
        run = runs.save(run);

        CodegenRun published = codegen.publish(run.getId(), "ciam-policies-tests", "main", "tester");

        assertThat(published.getPrUrl()).isEqualTo("https://bitbucket.bnc/ciam/pr/7");
        assertThat(published.getBranch()).isEqualTo("veritas/ciam-policies-tests");
        verify(prPublisher).publish(any());
    }

    @Test
    void skippedBuildPrDescriptionWarnsItWasNotCompiled() {
        when(prPublisher.publish(any())).thenReturn(new PrPublisher.PrResult("b", "https://pr/1"));
        CodegenRun run = new CodegenRun();
        run.setServiceName("ciam-policies");
        run.setOutputRepo(System.getProperty("java.io.tmpdir"));
        run.setBuildStatus("SKIPPED");
        run = runs.save(run);

        codegen.publish(run.getId(), "ciam-policies-tests", "main", "tester");

        org.mockito.ArgumentCaptor<PrPublisher.PrRequest> cap =
                org.mockito.ArgumentCaptor.forClass(PrPublisher.PrRequest.class);
        verify(prPublisher).publish(cap.capture());
        assertThat(cap.getValue().description()).contains("NOT compiled").contains("rely on CI");
    }

    @Test
    void refusesToPublishAFailingBuildUnlessOverridden() {
        CodegenRun run = new CodegenRun();
        run.setServiceName("ciam-policies");
        run.setOutputRepo(System.getProperty("java.io.tmpdir"));
        run.setBuildStatus("FAIL");
        run = runs.save(run);
        String id = run.getId();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> codegen.publish(id, "ciam-policies-tests", "main", "tester"))
                .isInstanceOf(ca.bnc.qe.veritas.preflight.PreconditionException.class)
                .hasMessageContaining("did not compile");
        verify(prPublisher, org.mockito.Mockito.never()).publish(any());

        // explicit override publishes anyway
        when(prPublisher.publish(any())).thenReturn(new PrPublisher.PrResult("b", "https://pr/override"));
        CodegenRun published = codegen.publish(id, "ciam-policies-tests", "main", "tester", true);
        assertThat(published.getPrUrl()).isEqualTo("https://pr/override");
    }
}
