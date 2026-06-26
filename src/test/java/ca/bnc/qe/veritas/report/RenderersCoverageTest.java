package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.persistence.TestStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Branch/edge coverage for {@link StrategyRationaleRenderer} and {@link WhyDocRenderer}: exercises the
 * empty/null guards, the glossary-vs-fallback principle branches, the HIGH/no-HIGH risk paths, the blind-spot
 * (beyond-syllabus) marker, citation/rationale presence branches, HTML escaping and the scorecard/gap/evidence
 * projection branches. Complements the existing {@code StrategyRationaleRendererTest} / {@code WhyDocRendererTest}.
 */
class RenderersCoverageTest {

    private final ObjectMapper m = new ObjectMapper();
    private final StrategyRationaleRenderer rationale = new StrategyRationaleRenderer(m);
    private final WhyDocRenderer whyDoc = new WhyDocRenderer(m);

    private TestStrategy strategy(String deliverableJson, String scorecardJson) {
        TestStrategy s = new TestStrategy();
        s.setServiceName("ciam-policies");
        s.setDeliverableJson(deliverableJson);
        s.setScorecardJson(scorecardJson);
        return s;
    }

    // ----------------------------------------------------------------------------------------------------------
    // StrategyRationaleRenderer
    // ----------------------------------------------------------------------------------------------------------
    @Nested
    class StrategyRationale {

        @Test
        void nullDeliverableAndNullServiceNameProduceAShellWithNoSections() {
            TestStrategy s = new TestStrategy();   // serviceName null, version null, deliverableJson null
            String html = rationale.renderHtml(s);

            // The frame is always present.
            assertThat(html).startsWith("<!DOCTYPE html>").contains("Test Strategy Rationale");
            // version null -> no " . v" suffix; serviceName null -> nz() yields empty, no literal "null".
            assertThat(html).doesNotContain(" · v").doesNotContain(">null<").doesNotContain("null</title>");
            // No deliverable -> none of the optional sections render.
            assertThat(html).doesNotContain("Scope &amp; objectives")
                    .doesNotContain("Risk-based prioritization")
                    .doesNotContain("Technique —")
                    .doesNotContain("Test levels &amp; types")
                    .doesNotContain("Exit criteria");
            // The fixed footer/intro are still there.
            assertThat(html).contains("beyond syllabus").contains("each decision explained against its");
        }

        @Test
        void malformedDeliverableJsonFallsBackToEmptyAndDoesNotThrow() {
            String html = rationale.renderHtml(strategy("{not valid json", null));
            // Parse error swallowed -> empty object -> no sections, but the shell renders.
            assertThat(html).contains("Test Strategy Rationale").contains("ciam-policies");
            assertThat(html).doesNotContain("Risk-based prioritization");
        }

        @Test
        void versionIsRenderedWhenPresent() {
            TestStrategy s = strategy("{}", null);
            s.setVersion(7);
            assertThat(rationale.renderHtml(s)).contains(" · v7");
        }

        @Test
        void scopeRendersOnlyWhenObjectiveBearingObject() {
            // scope present but NOT an object (an array) -> guard skips it.
            String htmlArr = rationale.renderHtml(strategy("{\"scope\":[\"x\"]}", null));
            assertThat(htmlArr).doesNotContain("Scope &amp; objectives");

            // scope is an object but has no "objectives" key -> guard skips it.
            String htmlNoObj = rationale.renderHtml(strategy("{\"scope\":{\"notes\":\"hi\"}}", null));
            assertThat(htmlNoObj).doesNotContain("Scope &amp; objectives");

            // scope object with objectives -> section with the joined objectives + the glossary "Test Scope" principle.
            String html = rationale.renderHtml(
                    strategy("{\"scope\":{\"objectives\":[\"Validate authZ\",\"Validate authN\"]}}", null));
            assertThat(html).contains("Scope &amp; objectives")
                    .contains("Objectives: Validate authZ, Validate authN.")
                    .contains("isn't being tested");   // from the "test scope" glossary entry
        }

        @Test
        void emptyObjectivesArrayJoinsToEmDash() {
            String html = rationale.renderHtml(strategy("{\"scope\":{\"objectives\":[]}}", null));
            assertThat(html).contains("Scope &amp; objectives").contains("Objectives: —.");
        }

        @Test
        void riskRegisterWithHighRisksConcentratesCoverage() {
            String json = "{\"riskRegister\":["
                    + "{\"id\":\"R1\",\"description\":\"Unauthorized read\",\"level\":\"HIGH\",\"mitigation\":\"AuthZ matrix\"},"
                    + "{\"id\":\"R2\",\"description\":\"DoS\",\"level\":\"VERY HIGH\"},"
                    + "{\"id\":\"R3\",\"description\":\"Theming\",\"level\":\"VH\"},"
                    + "{\"id\":\"R4\",\"description\":\"Typo\",\"level\":\"LOW\"}]}";
            String html = rationale.renderHtml(strategy(json, null));

            assertThat(html).contains("Risk-based prioritization")
                    .contains("identifies 4 risk(s)")
                    .contains("3 HIGH/VERY-HIGH risk(s)")      // HIGH + VERY HIGH + VH all counted, LOW excluded
                    .contains("Coverage is concentrated on:")
                    .contains("R1").contains("Unauthorized read").contains("AuthZ matrix")
                    .contains("R2").contains("DoS")
                    .contains("(mitigation: —)");         // R2 has no mitigation -> default em dash
            assertThat(html).doesNotContain("R4");             // LOW risk is not in the concentration list
        }

        @Test
        void riskRegisterWithNoHighRisksSpreadsCoverageEvenly() {
            String json = "{\"riskRegister\":["
                    + "{\"id\":\"R1\",\"description\":\"Minor\",\"level\":\"LOW\"},"
                    + "{\"id\":\"R2\",\"description\":\"Medium\",\"level\":\"MEDIUM\"}]}";
            String html = rationale.renderHtml(strategy(json, null));

            assertThat(html).contains("Risk-based prioritization")
                    .contains("identifies 2 risk(s)")
                    .contains("0 HIGH/VERY-HIGH risk(s)")
                    .contains("No HIGH risks — coverage is spread evenly.");
            assertThat(html).doesNotContain("Coverage is concentrated on:");
        }

        @Test
        void emptyRiskRegisterArrayRendersNoRiskSection() {
            assertThat(rationale.renderHtml(strategy("{\"riskRegister\":[]}", null)))
                    .doesNotContain("Risk-based prioritization");
        }

        @Test
        void techniqueWithKnownCitationUsesGlossaryPrincipleAndRationaleAndRiskId() {
            String json = "{\"testApproach\":{\"techniques\":["
                    + "{\"name\":\"Decision Table\",\"rationale\":\"role x state rules\",\"riskId\":\"R1\","
                    + "\"citation\":\"CTAL-TA — Decision Table Testing\"}]}}";
            String html = rationale.renderHtml(strategy(json, null));

            assertThat(html).contains("Technique — Decision Table")
                    .contains("Combinations of conditions")    // glossary principle for decision table
                    .contains("Why here.").contains("role x state rules")   // rationale present -> "Why here"
                    .contains("Addresses risk R1.");           // riskId present -> "How it serves"
        }

        @Test
        void techniqueWithoutCitationRationaleOrRiskIdFallsBackPerBranch() {
            // No citation -> cite defaults to name "Mystery" (not in glossary) -> "Team convention - beyond syllabus."
            // Blank rationale -> why is null -> no "Why here." block. No riskId -> no "How it serves" block.
            String json = "{\"testApproach\":{\"techniques\":["
                    + "{\"name\":\"Mystery\",\"rationale\":\"\"}]}}";
            String html = rationale.renderHtml(strategy(json, null));

            assertThat(html).contains("Technique — Mystery")
                    .contains("Team convention — beyond syllabus.");
            // For this technique's section, neither optional block appears.
            String section = html.substring(html.indexOf("Technique — Mystery"));
            assertThat(section).doesNotContain("Why here.").doesNotContain("Addresses risk");
        }

        @Test
        void emptyTechniquesArrayRendersNoTechniqueSections() {
            assertThat(rationale.renderHtml(strategy("{\"testApproach\":{\"techniques\":[]}}", null)))
                    .doesNotContain("Technique —");
        }

        @Test
        void levelsAndTypesRenderWhenEitherPresentAndJoinEmptyToEmDash() {
            // Only "levels" present; "types" absent -> joinArray("types") -> "-".
            String html = rationale.renderHtml(
                    strategy("{\"testApproach\":{\"levels\":[\"System\",\"Integration\"]}}", null));
            assertThat(html).contains("Test levels &amp; types")
                    .contains("Levels: System, Integration. Types: —.")
                    .contains("cheapest appropriate stage");
        }

        @Test
        void exitCriteriaRenderWithAndWithoutMetric() {
            String json = "{\"exitCriteria\":["
                    + "{\"criterion\":\"No open critical defects\",\"metric\":\"open critical = 0\"},"
                    + "{\"criterion\":\"Smoke passes\"}]}";
            String html = rationale.renderHtml(strategy(json, null));

            assertThat(html).contains("Exit criteria")
                    .contains("No open critical defects")
                    .contains("<em>(open critical = 0)</em>")   // metric present -> emphasised
                    .contains("Smoke passes")
                    .contains("Measurable conditions");          // exit-criteria glossary principle
            // The metric-less criterion must not emit an empty "<em>()</em>".
            assertThat(html).doesNotContain("<em>()</em>");
        }

        @Test
        void emptyExitCriteriaArrayRendersNoExitSection() {
            assertThat(rationale.renderHtml(strategy("{\"exitCriteria\":[]}", null)))
                    .doesNotContain("Exit criteria");
        }

        @Test
        void escapesHtmlInServiceNameObjectivesAndRiskFields() {
            TestStrategy s = strategy(
                    "{\"scope\":{\"objectives\":[\"<b>x</b> & \\\"y\\\"\"]},"
                            + "\"riskRegister\":[{\"id\":\"<R>\",\"description\":\"a<b\",\"level\":\"HIGH\","
                            + "\"mitigation\":\"m&m\"}]}",
                    null);
            s.setServiceName("<svc>&\"q\"");
            String html = rationale.renderHtml(s);

            assertThat(html).contains("&lt;svc&gt;&amp;&quot;q&quot;").doesNotContain("<svc>");
            assertThat(html).contains("&lt;b&gt;x&lt;/b&gt; &amp; &quot;y&quot;");
            assertThat(html).contains("&lt;R&gt;").contains("a&lt;b").contains("m&amp;m");
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // WhyDocRenderer
    // ----------------------------------------------------------------------------------------------------------
    @Nested
    class WhyDoc {

        @Test
        void scorecardWithOkVerdictUsesOkPillAndConfidence() {
            String sc = "{\"verdict\":\"OK\",\"confidence\":95,\"checks\":["
                    + "{\"name\":\"Grounded\",\"passed\":true,\"detail\":\"all good\"}]}";
            String html = whyDoc.renderHtml(strategy("{}", sc));

            assertThat(html).contains("Quality scorecard")
                    .contains("pill ok").contains("OK · 95%")
                    .contains("li class=\"pass\"").contains("✓").contains("Grounded").contains("all good");
        }

        @Test
        void scorecardWithFailingCheckUsesBadPillAndFailMark() {
            String sc = "{\"verdict\":\"DEGRADED\",\"confidence\":40,\"checks\":["
                    + "{\"name\":\"Grounded\",\"passed\":false,\"detail\":\"1 dropped\"}]}";
            String html = whyDoc.renderHtml(strategy("{}", sc));

            assertThat(html).contains("pill bad").contains("DEGRADED · 40%")
                    .contains("li class=\"fail\"").contains("✗").contains("1 dropped");
        }

        @Test
        void scorecardAbsentOrWithoutVerdictRendersNoCard() {
            // null scorecard -> parse -> empty object -> no "verdict" -> skipped.
            assertThat(whyDoc.renderHtml(strategy("{}", null))).doesNotContain("Quality scorecard");
            // present but missing the verdict key -> still skipped.
            assertThat(whyDoc.renderHtml(strategy("{}", "{\"confidence\":50}")))
                    .doesNotContain("Quality scorecard");
            // a non-object scorecard (array) -> !isObject() -> skipped.
            assertThat(whyDoc.renderHtml(strategy("{}", "[1,2]"))).doesNotContain("Quality scorecard");
        }

        @Test
        void summaryRendersWhenPresentAndIsOmittedWhenBlank() {
            assertThat(whyDoc.renderHtml(strategy("{\"summary\":\"Strategy for X.\"}", null)))
                    .contains("class=\"summary\"").contains("Strategy for X.");
            assertThat(whyDoc.renderHtml(strategy("{\"summary\":\"\"}", null)))
                    .doesNotContain("class=\"summary\"");
        }

        @Test
        void featureIdFallsBackToFeatureThenStatusAndNameDefaults() {
            // No featureId -> falls back to "feature"; unknown status -> STATUS_LABEL/CLASS default (info).
            ObjectNode d = m.createObjectNode();
            ObjectNode risk = d.putArray("riskRegister").addObject();
            risk.put("feature", "Get policy").put("featureStatus", "MYSTERY_STATUS");
            risk.put("content", "validate 200");
            String html = whyDoc.renderHtml(strategy(d.toString(), null));

            assertThat(html).contains("Features").contains("Get policy")
                    .contains("pill info")              // unknown status -> default class
                    .contains(">MYSTERY_STATUS<");      // unknown status -> label is the raw status
        }

        @Test
        void featureWithMissingNameUsesEmptyAndKnownGapKindIsLabelled() {
            ObjectNode d = m.createObjectNode();
            // featureId present but no "feature" name and no status -> name defaults to featureId, status "".
            ObjectNode approach = d.putArray("testApproach").addObject();
            approach.put("featureId", "f9");
            approach.put("content", "stuff");
            d.putArray("gaps").addObject().put("kind", "PLANNED_NOT_IMPLEMENTED")
                    .put("message", "spec X not built");
            String html = whyDoc.renderHtml(strategy(d.toString(), null));

            assertThat(html).contains("Coverage gaps")
                    .contains("Specified, not built")   // PLANNED_NOT_IMPLEMENTED -> mapped label
                    .contains("spec X not built");
            // featureId becomes the heading when "feature" is absent.
            assertThat(html).contains(">f9<");
        }

        @Test
        void unknownGapKindFallsBackToRawKind() {
            ObjectNode d = m.createObjectNode();
            d.putArray("gaps").addObject().put("kind", "WEIRD_KIND").put("message", "huh");
            String html = whyDoc.renderHtml(strategy(d.toString(), null));
            assertThat(html).contains("Coverage gaps").contains("WEIRD_KIND").contains("huh");
        }

        @Test
        void emptyOrAbsentGapsAndFeaturesRenderNeitherSection() {
            // Empty gaps array and no features -> neither "Features" nor "Coverage gaps".
            assertThat(whyDoc.renderHtml(strategy("{\"gaps\":[]}", null)))
                    .doesNotContain("Coverage gaps")
                    .doesNotContain("class=\"sec\">Features");
        }

        @Test
        void evidenceWithGlossRendersAndBlankGlossIsOmitted() {
            ObjectNode d = m.createObjectNode();
            ObjectNode risk = d.putArray("riskRegister").addObject();
            risk.put("featureId", "f1").put("feature", "F1").put("featureStatus", "IMPLEMENTED");
            // First evidence has a gloss, second has a blank gloss -> no "- gloss" span for it.
            com.fasterxml.jackson.databind.node.ArrayNode evidence = risk.putArray("evidence");
            evidence.addObject().put("unitId", "JIRA-1").put("quote", "q1").put("gloss", "the why");
            evidence.addObject().put("unitId", "JIRA-2").put("quote", "q2").put("gloss", "");
            risk.put("content", "c");
            String html = whyDoc.renderHtml(strategy(d.toString(), null));

            assertThat(html).contains("Cited evidence")
                    .contains("JIRA-1").contains("q1").contains("the why")
                    .contains("JIRA-2").contains("q2");
            // The blank gloss must not produce a dangling "- </span>" empty gloss span text.
            assertThat(html).doesNotContain("gloss\">— </span>");
        }

        @Test
        void emptyEvidenceArrayRendersNoEvidenceBlock() {
            ObjectNode d = m.createObjectNode();
            ObjectNode risk = d.putArray("riskRegister").addObject();
            risk.put("featureId", "f1").put("feature", "F1").put("featureStatus", "IMPLEMENTED");
            risk.putArray("evidence");   // empty
            risk.put("content", "c");
            assertThat(whyDoc.renderHtml(strategy(d.toString(), null))).doesNotContain("Cited evidence");
        }

        @Test
        void renderContentHandlesNestedObjectsArraysValuesAndStopsAtDepthLimit() {
            // Build content nested deeper than the depth==6 cap to exercise the recursion-stop branch,
            // plus an object (-> dl/dt/dd), an array (-> ul/li), and value nodes.
            ObjectNode d = m.createObjectNode();
            ObjectNode risk = d.putArray("riskRegister").addObject();
            risk.put("featureId", "f1").put("feature", "F1").put("featureStatus", "IMPLEMENTED");
            ObjectNode content = m.createObjectNode();
            content.putArray("steps").add("step-one").add("step-two");   // array of values
            // 8 levels of nesting -> exceeds the depth>6 guard at the deepest level.
            ObjectNode cur = content.putObject("l1");
            for (int i = 2; i <= 8; i++) {
                cur = cur.putObject("l" + i);
            }
            cur.put("deepValue", "should-be-cut-off");
            risk.set("content", content);
            String html = whyDoc.renderHtml(strategy(d.toString(), null));

            assertThat(html).contains("<dl class=\"content\">")   // object -> definition list
                    .contains("<dt>steps</dt>").contains("step-one").contains("step-two")
                    .contains("<ul class=\"content\">");          // array -> bullet list
            // The value beyond the depth cap is not rendered.
            assertThat(html).doesNotContain("should-be-cut-off");
        }

        @Test
        void renderContentValueNodeAtTopAndEscapesHtmlInContent() {
            ObjectNode d = m.createObjectNode();
            ObjectNode risk = d.putArray("riskRegister").addObject();
            risk.put("featureId", "f1").put("feature", "<f>").put("featureStatus", "IMPLEMENTED");
            risk.put("content", "a & b < c > d \"q\"");   // a plain string content -> value-node branch
            String html = whyDoc.renderHtml(strategy(d.toString(), null));

            assertThat(html).contains("<span>a &amp; b &lt; c &gt; d &quot;q&quot;</span>");
            assertThat(html).contains("&lt;f&gt;").doesNotContain("<f>");   // feature name escaped
        }

        @Test
        void malformedDeliverableAndScorecardJsonAreToleratedAndStatusKnownLabelMaps() {
            // Malformed deliverable + malformed scorecard -> both parse to empty object, shell still renders.
            String html = whyDoc.renderHtml(strategy("{broken", "also broken"));
            assertThat(html).contains("Strategy Evidence").contains("ciam-policies")
                    .doesNotContain("Quality scorecard").doesNotContain("Features");
        }

        @Test
        void knownStatusMapsToFriendlyLabelAndClass() {
            ObjectNode d = m.createObjectNode();
            ObjectNode risk = d.putArray("riskRegister").addObject();
            risk.put("featureId", "f1").put("feature", "F1").put("featureStatus", "COVERAGE_GAP");
            risk.put("content", "c");
            String html = whyDoc.renderHtml(strategy(d.toString(), null));
            assertThat(html).contains("pill bad").contains("Done in Jira, not built");
        }
    }
}