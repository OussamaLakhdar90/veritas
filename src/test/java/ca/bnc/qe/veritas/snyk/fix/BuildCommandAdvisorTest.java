package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The AI build-command advisor: derive a per-app reactor command, security-gate it, cache it, and degrade safely.
 * Real extractor + validator so the actual build-command-advisor.schema.json is exercised; the DB is a stateful fake.
 */
class BuildCommandAdvisorTest {

    private final LlmGateway llm = mock(LlmGateway.class);
    private final PromptComposer composer = mock(PromptComposer.class);
    private final ModelSelector modelSelector = mock(ModelSelector.class);
    private final CostRecorder costRecorder = mock(CostRecorder.class);
    private final AppBuildCommandRepository cache = mock(AppBuildCommandRepository.class);

    private final BuildCommandAdvisor advisor = new BuildCommandAdvisor(llm, composer, modelSelector, costRecorder,
            new JsonBlockExtractor(), new ResponseSchemaValidator(new DefaultResourceLoader()), new ObjectMapper(),
            cache);

    // A stateful in-memory stand-in for the JPA cache so hit/miss/stale paths are exercised end-to-end.
    private final AtomicReference<AppBuildCommand> stored = new AtomicReference<>();

    @TempDir
    Path repo;

    @BeforeEach
    void stub() throws Exception {
        when(llm.isAvailable()).thenReturn(true);
        when(composer.data(anyString(), anyString())).thenReturn("block");
        when(composer.compose(anyString(), anyString(), any(), anyString(), anyString(), anyInt())).thenReturn("prompt");
        when(modelSelector.resolveTier(any())).thenReturn("claude-opus");
        when(modelSelector.promptTokenCap(any())).thenReturn(60000);
        when(cache.findByAppIdAndRepoSlug(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(cache.save(any())).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        Files.writeString(repo.resolve("pom.xml"), "<project><artifactId>ciam-oauth</artifactId></project>");
    }

    @Test
    void derivesValidatesAndCachesTheCommand() {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn -q -B -Psystem-test verify\",\"rationale\":\"failsafe ITs under a profile\"}\n```");

        String cmd = advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command();

        assertThat(cmd).isEqualTo("mvn -q -B -Psystem-test verify");
        assertThat(stored.get().getCommand()).isEqualTo("mvn -q -B -Psystem-test verify");
        assertThat(stored.get().getPomHash()).isNotBlank();
    }

    @Test
    void degradesToTheDefaultWhenCopilotIsOffline() {
        when(llm.isAvailable()).thenReturn(false);
        assertThat(advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command())
                .isEqualTo(BuildCommandAdvisor.DEFAULT_COMMAND);
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void degradesToTheDefaultOnAMalformedReply() {
        when(llm.complete(anyString(), anyString())).thenReturn("not json at all");
        assertThat(advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command())
                .isEqualTo(BuildCommandAdvisor.DEFAULT_COMMAND);
    }

    @Test
    void fallsBackToTheDefaultWhenTheDerivedCommandFailsTheSecurityGuard() {
        // A syntactically valid but DANGEROUS command (plugin goal + exec property) must never reach the reactor.
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn exec:exec -Dexec.executable=/bin/sh verify\",\"rationale\":\"x\"}\n```");
        assertThat(advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command())
                .isEqualTo(BuildCommandAdvisor.DEFAULT_COMMAND);
    }

    @Test
    void reusesTheCachedCommandOnAFreshHitWithoutCallingTheModelAgain() {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn -q -B verify\",\"rationale\":\"unit\"}\n```");

        String first = advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command();
        String second = advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command();   // same pom → cache hit

        assertThat(first).isEqualTo("mvn -q -B verify");
        assertThat(second).isEqualTo("mvn -q -B verify");
        verify(llm, times(1)).complete(anyString(), anyString());   // the second resolve did NOT re-derive
    }

    @Test
    void retriesAtALowerTierWhenTheDeepModelIsRejected() {
        // The exact bug: DEEP resolves to a model this Copilot account rejects (model_not_supported 4xx). Instead of
        // silently degrading, the advisor must RETRY at STANDARD (which the account supports) and succeed.
        when(modelSelector.resolveTier(ModelTier.DEEP)).thenReturn("gemini-3.1-pro");
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-sonnet-4.6");
        when(llm.complete(anyString(), eq("gemini-3.1-pro")))
                .thenThrow(new IllegalStateException("HTTP 400: {\"error\":{\"code\":\"model_not_supported\"}}"));
        when(llm.complete(anyString(), eq("claude-sonnet-4.6"))).thenReturn(
                "```json\n{\"command\":\"mvn -q -B verify\",\"rationale\":\"ok on standard\"}\n```");

        BuildCommandAdvisor.BuildCommand cmd = advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");

        assertThat(cmd.aiDerived()).isTrue();
        assertThat(cmd.command()).isEqualTo("mvn -q -B verify");
        verify(llm).complete(anyString(), eq("gemini-3.1-pro"));      // DEEP was tried…
        verify(llm).complete(anyString(), eq("claude-sonnet-4.6"));   // …then it retried STANDARD and won
        assertThat(stored.get().getCommand()).isEqualTo("mvn -q -B verify");   // a real AI command IS cached
    }

    @Test
    void degradesVisiblyWhenEveryTierIsRejected() {
        when(modelSelector.resolveTier(ModelTier.DEEP)).thenReturn("gemini-3.1-pro");
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-sonnet-4.6");
        when(llm.complete(anyString(), anyString()))
                .thenThrow(new IllegalStateException("The requested model is not supported."));

        BuildCommandAdvisor.BuildCommand cmd = advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");

        assertThat(cmd.aiDerived()).isFalse();
        assertThat(cmd.command()).isEqualTo(BuildCommandAdvisor.DEFAULT_COMMAND);
        assertThat(cmd.note()).containsIgnoringCase("not supported");   // the reason is surfaced, not swallowed
        assertThat(stored.get()).isNull();                              // a degraded default is NEVER cached
    }

    @Test
    void doesNotRetryALowerTierOnAContentOrGuardFailure() {
        when(modelSelector.resolveTier(ModelTier.DEEP)).thenReturn("gemini-3.1-pro");
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-sonnet-4.6");
        // DEEP replies with a dangerous plugin-goal command → the guard rejects it. A cheaper model won't fix a bad
        // command, so we degrade IMMEDIATELY and never call STANDARD.
        when(llm.complete(anyString(), eq("gemini-3.1-pro"))).thenReturn(
                "```json\n{\"command\":\"mvn exec:exec -Dexec.executable=/bin/sh verify\",\"rationale\":\"x\"}\n```");

        BuildCommandAdvisor.BuildCommand cmd = advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");

        assertThat(cmd.aiDerived()).isFalse();
        assertThat(cmd.command()).isEqualTo(BuildCommandAdvisor.DEFAULT_COMMAND);
        verify(llm, never()).complete(anyString(), eq("claude-sonnet-4.6"));   // no wasted retry on a content failure
    }

    @Test
    void doesNotCacheADegradedDefaultSoItRetriesTheAiNextRun() {
        when(modelSelector.resolveTier(ModelTier.DEEP)).thenReturn("gemini-3.1-pro");
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-sonnet-4.6");
        // First run: every tier is rejected → degraded default, NOT cached (the latent cache-poisoning bug).
        when(llm.complete(anyString(), anyString())).thenThrow(new IllegalStateException("model_not_supported"));
        assertThat(advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").aiDerived()).isFalse();
        assertThat(stored.get()).isNull();

        // Second run (outage cleared on STANDARD): the AI is re-tried — no poisoned cache — and now succeeds.
        when(llm.complete(anyString(), eq("claude-sonnet-4.6"))).thenReturn(
                "```json\n{\"command\":\"mvn -q -B verify\",\"rationale\":\"ok now\"}\n```");
        BuildCommandAdvisor.BuildCommand second = advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");

        assertThat(second.aiDerived()).isTrue();
        assertThat(second.command()).isEqualTo("mvn -q -B verify");
    }

    @Test
    void discoversSuiteFilesAndFoldsThemIntoTheCacheFingerprint() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn -q -B verify\",\"rationale\":\"suite\"}\n```");
        Files.createDirectories(repo.resolve("src/test/resources"));
        Files.writeString(repo.resolve("src/test/resources/testng.xml"),
                "<suite name=\"ciam\"><test><classes/></test></suite>");
        advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");

        // Changing ONLY the suite file (the pom is untouched) must re-derive — proving the suite XML is discovered
        // AND folded into the config hash, not just the pom.
        Files.writeString(repo.resolve("src/test/resources/testng.xml"),
                "<suite name=\"ciam2\"><test><classes/></test></suite>");
        advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");

        verify(llm, times(2)).complete(anyString(), anyString());
    }

    @Test
    void fallsBackToTheDefaultWhenACachedCommandNoLongerPassesTheGuard() {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn -q -B verify\",\"rationale\":\"ok\"}\n```");
        advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");   // stores a valid command at the current hash
        stored.get().setCommand("mvn exec:exec verify");                // simulate a stored command the guard now rejects

        String cmd = advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command();   // cache hit → re-guard fails

        assertThat(cmd).isEqualTo(BuildCommandAdvisor.DEFAULT_COMMAND);
        verify(llm, times(1)).complete(anyString(), anyString());   // it was a cache hit, not a re-derivation
    }

    @Test
    void cachingIsBestEffortSoASaveFailureDoesNotFailTheFix() {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn -q -B test\",\"rationale\":\"x\"}\n```");
        when(cache.save(any())).thenThrow(new RuntimeException("db down"));   // persist is best-effort
        assertThat(advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command()).isEqualTo("mvn -q -B test");
    }

    @Test
    void handlesAnAppWithNoPomAndNoSuitesWithoutFailing() throws Exception {
        Files.delete(repo.resolve("pom.xml"));   // exercise the no-pom + empty-suites + no-subdirs input paths
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn -q -B test\",\"rationale\":\"no pom\"}\n```");
        assertThat(advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command()).isEqualTo("mvn -q -B test");
    }

    @Test
    void discoversAResourcesSuiteByContentAndIgnoresBuildDirsAndTruncatesLargeFiles() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn -q -B verify\",\"rationale\":\"suite\"}\n```");
        // A resources XML whose NAME doesn't match but whose CONTENT is a suite (content-match branch).
        Files.createDirectories(repo.resolve("src/test/resources"));
        Files.writeString(repo.resolve("src/test/resources/it-config.xml"),
                "<!-- big -->" + "x".repeat(9000) + "\n<suite name=\"it\"><test><classes/></test></suite>");
        // A build dir that must be ignored by the directory listing.
        Files.createDirectories(repo.resolve("target/classes"));

        assertThat(advisor.resolve("APP7576", "app-tests", repo, "alice", "t1").command()).isEqualTo("mvn -q -B verify");
        assertThat(stored.get().getCommand()).isEqualTo("mvn -q -B verify");
    }

    @Test
    void reDerivesWhenTheBuildConfigChanged() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"command\":\"mvn -q -B verify\",\"rationale\":\"unit\"}\n```");
        advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");

        Files.writeString(repo.resolve("pom.xml"), "<project><artifactId>ciam-oauth</artifactId><!--changed--></project>");
        advisor.resolve("APP7576", "app-tests", repo, "alice", "t1");   // pom hash changed → re-derive

        verify(llm, times(2)).complete(anyString(), anyString());
    }
}
