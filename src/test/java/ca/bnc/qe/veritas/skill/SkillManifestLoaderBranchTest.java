package ca.bnc.qe.veritas.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Pure-Mockito branch coverage for {@link SkillManifestLoader}. The class loads every
 * {@code classpath*:skills/*.skill.yaml} at startup via {@link ApplicationContext#getResources(String)},
 * deserialises each to a {@link SkillManifest}, and validates fail-fast. We drive {@code @PostConstruct load()}
 * directly (it is package-private) with a mocked {@code ApplicationContext} returning in-memory YAML
 * {@link Resource}s, exercising every validation branch: name/pipeline presence, missing/duplicate step ids,
 * null kind, the three {@link StepKind} arms (deterministic handler resolution, LLM prompt/schema, gate),
 * {@code inputsFrom} reference resolution against earlier {@code out} values, and {@code out} storage. Plus the
 * public surface: {@code get(name)} hit/miss and {@code all()} immutability. No Spring context booted, no
 * production code touched.
 */
class SkillManifestLoaderBranchTest {

    /** A classpath resource backed by in-memory YAML with a controllable filename (used in error messages). */
    private static Resource yamlResource(String filename, String yaml) {
        return new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    /** Build a loader whose getResources(...) returns the given resources, with the supplied handler beans. */
    private static SkillManifestLoader loaderFor(Map<String, StepHandler> handlers, Resource... resources)
            throws IOException {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getResources(anyString())).thenReturn(resources);
        return new SkillManifestLoader(handlers, ctx);
    }

    private static Map<String, StepHandler> handlers(String... names) {
        Map<String, StepHandler> map = new HashMap<>();
        for (String n : names) {
            map.put(n, (ctx, step) -> "ok");
        }
        return map;
    }

    // ---- happy path: every step kind + inputsFrom resolution + multiple manifests --------------------------

    @Test
    void loadsValidManifestWithAllThreeStepKindsAndResolvesInputsFrom() throws Exception {
        String yaml = """
                name: full
                description: every kind
                pipeline:
                  - { id: a, kind: deterministic, handler: h1, out: outA }
                  - { id: b, kind: llm, promptSkill: p.md, expectsJson: s.json, inputsFrom: [outA], out: outB }
                  - { id: c, kind: gate, inputsFrom: [outA, outB] }
                """;
        SkillManifestLoader loader = loaderFor(handlers("h1"), yamlResource("full.skill.yaml", yaml));

        loader.load();

        SkillManifest m = loader.get("full");
        assertThat(m.name()).isEqualTo("full");
        assertThat(m.pipeline()).hasSize(3);
        assertThat(m.pipeline().get(0).kind()).isEqualTo(StepKind.DETERMINISTIC);
        assertThat(m.pipeline().get(1).kind()).isEqualTo(StepKind.LLM);
        assertThat(m.pipeline().get(2).kind()).isEqualTo(StepKind.GATE);
        assertThat(m.pipeline().get(0).handler()).isEqualTo("h1");
        assertThat(m.pipeline().get(1).expectsJson()).isEqualTo("s.json");
        assertThat(loader.all()).containsOnlyKeys("full");
    }

    @Test
    void loadsMultipleManifestsKeyedByName() throws Exception {
        String one = """
                name: one
                pipeline:
                  - { id: s1, kind: gate }
                """;
        String two = """
                name: two
                pipeline:
                  - { id: s1, kind: gate }
                """;
        SkillManifestLoader loader = loaderFor(handlers(),
                yamlResource("one.skill.yaml", one),
                yamlResource("two.skill.yaml", two));

        loader.load();

        assertThat(loader.all()).containsOnlyKeys("one", "two");
        assertThat(loader.get("one").name()).isEqualTo("one");
        assertThat(loader.get("two").name()).isEqualTo("two");
    }

    @Test
    void loadWithNoResourcesLeavesRegistryEmpty() throws Exception {
        SkillManifestLoader loader = loaderFor(handlers());

        loader.load();

        assertThat(loader.all()).isEmpty();
    }

    // ---- name branch --------------------------------------------------------------------------------------

    @Test
    void nullNameThrows() throws Exception {
        String yaml = """
                description: no name
                pipeline:
                  - { id: s1, kind: gate }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("noname.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("noname.skill.yaml")
                .hasMessageContaining("has no name");
    }

    @Test
    void blankNameThrows() throws Exception {
        String yaml = """
                name: "   "
                pipeline:
                  - { id: s1, kind: gate }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("blank.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank.skill.yaml")
                .hasMessageContaining("has no name");
    }

    // ---- pipeline branch ----------------------------------------------------------------------------------

    @Test
    void nullPipelineThrows() throws Exception {
        String yaml = """
                name: nopipe
                description: missing pipeline
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("nopipe.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Skill 'nopipe' has an empty pipeline");
    }

    @Test
    void emptyPipelineThrows() throws Exception {
        String yaml = """
                name: emptypipe
                pipeline: []
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("emptypipe.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Skill 'emptypipe' has an empty pipeline");
    }

    // ---- step id branch -----------------------------------------------------------------------------------

    @Test
    void nullStepIdThrows() throws Exception {
        String yaml = """
                name: noid
                pipeline:
                  - { kind: gate }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("noid.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Skill 'noid' has a missing/duplicate step id: null");
    }

    @Test
    void duplicateStepIdThrows() throws Exception {
        String yaml = """
                name: dup
                pipeline:
                  - { id: s1, kind: gate }
                  - { id: s1, kind: gate }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("dup.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Skill 'dup' has a missing/duplicate step id: s1");
    }

    // ---- step kind branch ---------------------------------------------------------------------------------

    @Test
    void nullKindThrows() throws Exception {
        String yaml = """
                name: nokind
                pipeline:
                  - { id: s1 }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("nokind.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Step 's1' in 'nokind' has no kind");
    }

    // ---- DETERMINISTIC arm --------------------------------------------------------------------------------

    @Test
    void deterministicWithNullHandlerThrows() throws Exception {
        String yaml = """
                name: dethandler
                pipeline:
                  - { id: s1, kind: deterministic, out: o }
                """;
        SkillManifestLoader loader = loaderFor(handlers("known"), yamlResource("dethandler.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Step 's1' in 'dethandler'")
                .hasMessageContaining("references unknown handler bean 'null'")
                .hasMessageContaining("Known handlers: [known]");
    }

    @Test
    void deterministicWithUnknownHandlerThrows() throws Exception {
        String yaml = """
                name: detunknown
                pipeline:
                  - { id: s1, kind: deterministic, handler: missingBean, out: o }
                """;
        SkillManifestLoader loader = loaderFor(handlers("known"), yamlResource("detunknown.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references unknown handler bean 'missingBean'");
    }

    @Test
    void deterministicWithKnownHandlerLoads() throws Exception {
        String yaml = """
                name: detok
                pipeline:
                  - { id: s1, kind: deterministic, handler: known, out: o }
                """;
        SkillManifestLoader loader = loaderFor(handlers("known"), yamlResource("detok.skill.yaml", yaml));

        loader.load();

        assertThat(loader.get("detok").pipeline().get(0).handler()).isEqualTo("known");
    }

    // ---- LLM arm ------------------------------------------------------------------------------------------

    @Test
    void llmWithNullPromptSkillThrows() throws Exception {
        String yaml = """
                name: llmnoprompt
                pipeline:
                  - { id: s1, kind: llm, expectsJson: s.json, out: o }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("llmnoprompt.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM step 's1' in 'llmnoprompt'")
                .hasMessageContaining("requires both promptSkill and expectsJson");
    }

    @Test
    void llmWithNullExpectsJsonThrows() throws Exception {
        String yaml = """
                name: llmnoschema
                pipeline:
                  - { id: s1, kind: llm, promptSkill: p.md, out: o }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("llmnoschema.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM step 's1' in 'llmnoschema'")
                .hasMessageContaining("requires both promptSkill and expectsJson");
    }

    @Test
    void llmWithBothPromptAndSchemaLoads() throws Exception {
        String yaml = """
                name: llmok
                pipeline:
                  - { id: s1, kind: llm, promptSkill: p.md, expectsJson: s.json, out: o }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("llmok.skill.yaml", yaml));

        loader.load();

        Step s = loader.get("llmok").pipeline().get(0);
        assertThat(s.promptSkill()).isEqualTo("p.md");
        assertThat(s.expectsJson()).isEqualTo("s.json");
    }

    // ---- inputsFrom branch --------------------------------------------------------------------------------

    @Test
    void inputsFromUnknownOutputThrows() throws Exception {
        String yaml = """
                name: badref
                pipeline:
                  - { id: s1, kind: gate, inputsFrom: [doesNotExist] }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("badref.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Step 's1' in 'badref'")
                .hasMessageContaining("reads unknown output 'doesNotExist'");
    }

    @Test
    void inputsFromForwardReferenceToLaterStepOutputThrows() throws Exception {
        String yaml = """
                name: forwardref
                pipeline:
                  - { id: s1, kind: gate, inputsFrom: [late] }
                  - { id: s2, kind: gate, out: late }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("forwardref.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reads unknown output 'late'");
    }

    @Test
    void inputsFromResolvesToEarlierStepOutput() throws Exception {
        String yaml = """
                name: goodref
                pipeline:
                  - { id: s1, kind: gate, out: early }
                  - { id: s2, kind: gate, inputsFrom: [early] }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("goodref.skill.yaml", yaml));

        loader.load();

        assertThat(loader.get("goodref").pipeline().get(1).inputsFrom()).containsExactly("early");
    }

    @Test
    void stepWithNullInputsFromIsAccepted() throws Exception {
        String yaml = """
                name: noinputs
                pipeline:
                  - { id: s1, kind: gate, out: o }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("noinputs.skill.yaml", yaml));

        loader.load();

        assertThat(loader.get("noinputs").pipeline().get(0).inputsFrom()).isNull();
    }

    @Test
    void stepWithNullOutDoesNotRegisterAsResolvableOutput() throws Exception {
        String yaml = """
                name: nullout
                pipeline:
                  - { id: s1, kind: gate }
                  - { id: s2, kind: gate, inputsFrom: [s1] }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("nullout.skill.yaml", yaml));

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reads unknown output 's1'");
    }

    // ---- get() / all() public surface ---------------------------------------------------------------------

    @Test
    void getUnknownSkillThrowsWithKnownSet() throws Exception {
        String yaml = """
                name: solo
                pipeline:
                  - { id: s1, kind: gate }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("solo.skill.yaml", yaml));
        loader.load();

        assertThatThrownBy(() -> loader.get("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown skill 'ghost'")
                .hasMessageContaining("solo");
    }

    @Test
    void getKnownSkillReturnsManifest() throws Exception {
        String yaml = """
                name: solo
                pipeline:
                  - { id: s1, kind: gate }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("solo.skill.yaml", yaml));
        loader.load();

        assertThat(loader.get("solo")).isNotNull();
        assertThat(loader.get("solo").name()).isEqualTo("solo");
    }

    @Test
    void allReturnsImmutableDefensiveCopy() throws Exception {
        String yaml = """
                name: solo
                pipeline:
                  - { id: s1, kind: gate }
                """;
        SkillManifestLoader loader = loaderFor(handlers(), yamlResource("solo.skill.yaml", yaml));
        loader.load();

        Map<String, SkillManifest> snapshot = loader.all();
        assertThat(snapshot).containsOnlyKeys("solo");
        assertThatThrownBy(() -> snapshot.put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getOnFreshLoaderBeforeLoadThrowsWithEmptyKnownSet() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        SkillManifestLoader loader = new SkillManifestLoader(handlers(), ctx);

        assertThatThrownBy(() -> loader.get("anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown skill 'anything'")
                .hasMessageContaining("Known: []");
    }
}