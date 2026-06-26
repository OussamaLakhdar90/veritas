package ca.bnc.qe.veritas.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage oriented tests for {@link TestBasisExtractor}: exercises each conditional in
 * {@code extract} plus the private {@code slug} / {@code containsModal} helpers (via observable
 * effects on item ids / kinds). Assertions check real values so the suite survives mutation testing.
 */
class TestBasisExtractorBranchTest {

    private final TestBasisExtractor extractor = new TestBasisExtractor();

    private static NormalizedDoc doc(String md) {
        return new NormalizedDoc("jira", "JIRA-7", "T", md);
    }

    // ---- null / empty guards -------------------------------------------------

    @Test
    void nullDocReturnsEmpty() {
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void nullMarkdownReturnsEmpty() {
        assertThat(extractor.extract(new NormalizedDoc("jira", "JIRA-7", "T", null))).isEmpty();
    }

    @Test
    void blankAndEmptyLinesAreSkipped() {
        // only whitespace / empty lines → nothing extracted
        List<TestBasisItem> items = extractor.extract(doc("\n   \n\t\n"));
        assertThat(items).isEmpty();
    }

    // ---- bullets: AC vs requirement vs business-rule -------------------------

    @Test
    void bulletUnderAcceptanceSectionIsAcceptanceCriteria() {
        // heading contains "acceptance" (case-insensitive) → acSection true
        List<TestBasisItem> items = extractor.extract(doc("""
                ## Acceptance Criteria
                - the user is logged in
                """));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(TestBasisKind.ACCEPTANCE_CRITERIA);
            assertThat(i.text()).isEqualTo("the user is logged in");
            assertThat(i.id()).isEqualTo("JIRA-7#acceptance-criteria-1");
            assertThat(i.origin()).isEqualTo("JIRA-7");
        });
    }

    @Test
    void plainBulletUnderNonAcSectionIsRequirement() {
        List<TestBasisItem> items = extractor.extract(doc("""
                ## Notes
                - just a plain description
                """));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(TestBasisKind.REQUIREMENT);
            assertThat(i.text()).isEqualTo("just a plain description");
        });
    }

    @Test
    void modalBulletUnderNonAcSectionIsBusinessRule() {
        List<TestBasisItem> items = extractor.extract(doc("""
                ## Rules
                - the name must be unique
                """));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(TestBasisKind.BUSINESS_RULE);
            assertThat(i.text()).isEqualTo("the name must be unique");
        });
    }

    @Test
    void numberedListItemIsTreatedLikeBullet() {
        // matches "\d+\.\s.*" branch and strips the "1. " prefix
        List<TestBasisItem> items = extractor.extract(doc("1. first step is plain"));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(TestBasisKind.REQUIREMENT);
            assertThat(i.text()).isEqualTo("first step is plain");
            // default section before any heading is "general"
            assertThat(i.id()).isEqualTo("JIRA-7#general-1");
        });
    }

    // ---- gherkin lines -------------------------------------------------------

    @Test
    void gherkinLineIsAcceptanceCriteriaEvenWithoutBulletOrAcSection() {
        // "Given ..." matches the gherkin regex before reaching modal handling
        List<TestBasisItem> items = extractor.extract(doc("Given a valid token the call succeeds"));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(TestBasisKind.ACCEPTANCE_CRITERIA);
            assertThat(i.text()).isEqualTo("Given a valid token the call succeeds");
        });
    }

    @Test
    void gherkinKeywordsAreCaseInsensitiveAndWordBounded() {
        // "when"/"Then"/"AND"/"but" all hit the gherkin branch
        List<TestBasisItem> items = extractor.extract(doc("""
                when the request is sent
                Then a 200 is returned
                AND the body is json
                but errors are logged
                """));
        assertThat(items).hasSize(4);
        assertThat(items).allMatch(i -> i.kind() == TestBasisKind.ACCEPTANCE_CRITERIA);
    }

    @Test
    void wordThatMerelyStartsWithGherkinKeywordIsNotGherkin() {
        // "Andrew" must NOT match \bgiven|when|then|and|but\b (word boundary), and has no modal → dropped
        List<TestBasisItem> items = extractor.extract(doc("Andrew reviewed the document"));
        assertThat(items).isEmpty();
    }

    // ---- bare lines: modal vs dropped ---------------------------------------

    @Test
    void bareModalLineIsBusinessRule() {
        List<TestBasisItem> items = extractor.extract(doc("The system shall reject duplicates"));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(TestBasisKind.BUSINESS_RULE);
            assertThat(i.text()).isEqualTo("The system shall reject duplicates");
        });
    }

    @Test
    void bareNarrativeLineWithoutModalIsDropped() {
        // no bullet, no gherkin, no modal → falls through, nothing added
        List<TestBasisItem> items = extractor.extract(doc("This is plain narrative boilerplate text"));
        assertThat(items).isEmpty();
    }

    @Test
    void modalShouldIsRecognised() {
        List<TestBasisItem> items = extractor.extract(doc("Callers should retry on 503"));
        assertThat(items).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo(TestBasisKind.BUSINESS_RULE));
    }

    @Test
    void lineStartingWithMustHitsStartsWithModalBranch() {
        // "Must ..." → containsModal startsWith(" must ") branch (after the leading space prepend)
        List<TestBasisItem> items = extractor.extract(doc("Must not exceed the limit"));
        assertThat(items).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo(TestBasisKind.BUSINESS_RULE));
    }

    @Test
    void lineStartingWithShallHitsStartsWithModalBranch() {
        List<TestBasisItem> items = extractor.extract(doc("Shall validate the schema"));
        assertThat(items).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo(TestBasisKind.BUSINESS_RULE));
    }

    // ---- tables --------------------------------------------------------------

    @Test
    void tableSeparatorRowIsSkippedButDataRowBecomesBusinessRule() {
        List<TestBasisItem> items = extractor.extract(doc("""
                | Field | Rule |
                | --- | :---: |
                | name | required |
                """));
        // header row + data row produce items; the separator row is skipped
        assertThat(items).hasSize(2);
        assertThat(items).allMatch(i -> i.kind() == TestBasisKind.BUSINESS_RULE);
        // cells joined with em-dash separator; outer pipes stripped
        assertThat(items).anyMatch(i -> i.text().equals("Field — Rule"));
        assertThat(items).anyMatch(i -> i.text().equals("name — required"));
    }

    @Test
    void tableRowWithoutTrailingPipeStillStripsAndJoins() {
        // exercises replaceAll("\\|$","") when there is no trailing pipe (no-op) and the leading strip
        List<TestBasisItem> items = extractor.extract(doc("| a | b"));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(TestBasisKind.BUSINESS_RULE);
            assertThat(i.text()).isEqualTo("a — b");
        });
    }

    @Test
    void separatorRowVariantsAreAllSkipped() {
        // a row matching the separator regex with spaces, colons and dashes produces no item
        List<TestBasisItem> items = extractor.extract(doc("|:--:| :-: |"));
        assertThat(items).isEmpty();
    }

    // ---- headings, sections, slug -------------------------------------------

    @Test
    void headingResetsSectionAndAcFlagToggles() {
        // First an AC section, then a non-AC heading → acSection flips back to false
        List<TestBasisItem> items = extractor.extract(doc("""
                ## Acceptance Criteria
                - first criterion
                ## Other Rules
                - plain item
                """));
        assertThat(items).hasSize(2);
        assertThat(items).filteredOn(i -> i.text().equals("first criterion"))
                .singleElement().extracting(TestBasisItem::kind)
                .isEqualTo(TestBasisKind.ACCEPTANCE_CRITERIA);
        // second bullet is under non-AC heading → requirement, proving acSection was reset
        assertThat(items).filteredOn(i -> i.text().equals("plain item"))
                .singleElement().extracting(TestBasisItem::kind)
                .isEqualTo(TestBasisKind.REQUIREMENT);
    }

    @Test
    void slugIsLowercasedHyphenatedAndUsedInId() {
        List<TestBasisItem> items = extractor.extract(doc("""
                # Policy Creation Flow
                - a must rule
                """));
        // "Policy Creation Flow" → "policy-creation-flow"
        assertThat(items).singleElement()
                .extracting(TestBasisItem::id)
                .isEqualTo("JIRA-7#policy-creation-flow-1");
    }

    @Test
    void slugTruncatesLongHeadingsToTwentyFourChars() {
        // heading slug longer than 24 chars must be substring(0,24)
        List<TestBasisItem> items = extractor.extract(doc("""
                # This Heading Is Definitely Way Too Long To Keep
                - the value must hold
                """));
        assertThat(items).singleElement().satisfies(i -> {
            String section = i.id().substring("JIRA-7#".length(), i.id().lastIndexOf("-"));
            assertThat(section).hasSize(24);
            assertThat(i.id()).isEqualTo("JIRA-7#this-heading-is-definite-1");
        });
    }

    @Test
    void slugFallsBackToSecForNonAlphanumericHeading() {
        // heading "###" → after replaceFirst the heading text is "", slug empty → "sec"
        List<TestBasisItem> items = extractor.extract(doc("""
                ### @@@ ###
                - value must persist
                """));
        assertThat(items).singleElement()
                .extracting(TestBasisItem::id)
                .isEqualTo("JIRA-7#sec-1");
    }

    @Test
    void headingWithoutSpaceAfterHashesIsStillParsed() {
        // "#Heading" (no space) still strips the leading hashes via replaceFirst "^#+\\s*"
        List<TestBasisItem> items = extractor.extract(doc("""
                #Quick
                - it must work
                """));
        assertThat(items).singleElement()
                .extracting(TestBasisItem::id)
                .isEqualTo("JIRA-7#quick-1");
    }

    // ---- counter / ordering / mixed content ---------------------------------

    @Test
    void counterIncrementsAcrossAllItemKinds() {
        List<TestBasisItem> items = extractor.extract(doc("""
                # Spec
                - plain requirement
                - the rule must apply
                Given a precondition
                The system shall log
                | k | v |
                """));
        // 5 items in order, each with an increasing index suffix and stable section "spec"
        assertThat(items).hasSize(5);
        assertThat(items).extracting(TestBasisItem::id).containsExactly(
                "JIRA-7#spec-1",
                "JIRA-7#spec-2",
                "JIRA-7#spec-3",
                "JIRA-7#spec-4",
                "JIRA-7#spec-5");
        assertThat(items).extracting(TestBasisItem::kind).containsExactly(
                TestBasisKind.REQUIREMENT,
                TestBasisKind.BUSINESS_RULE,
                TestBasisKind.ACCEPTANCE_CRITERIA,
                TestBasisKind.BUSINESS_RULE,
                TestBasisKind.BUSINESS_RULE);
    }

    @Test
    void everyItemKeepsOriginAndIdRootedAtSource() {
        List<TestBasisItem> items = extractor.extract(doc("""
                # H
                - a must rule
                Given something
                """));
        assertThat(items).isNotEmpty();
        assertThat(items).allMatch(i -> i.origin().equals("JIRA-7"));
        assertThat(items).allMatch(i -> i.id().startsWith("JIRA-7#"));
    }
}
