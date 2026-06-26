package ca.bnc.qe.veritas.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage focused test for {@link AdfToMarkdown}. Exercises every node-type branch in
 * {@code renderBlock} / {@code renderInlineNode}, all text marks, list/table rendering, the unknown-node
 * fallback paths, and the {@code normalize} error path. Pure-function assertions on exact markdown.
 */
class AdfToMarkdownBranchCoverageTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AdfToMarkdown adf = new AdfToMarkdown(mapper);

    private String toMd(String json) {
        try {
            JsonNode doc = mapper.readTree(json);
            return adf.toMarkdown(doc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- toMarkdown null / no-content guards ----------

    @Test
    void toMarkdownReturnsEmptyForNullDoc() {
        assertThat(adf.toMarkdown(null)).isEmpty();
    }

    @Test
    void toMarkdownReturnsEmptyWhenContentMissing() {
        // doc node has no "content" -> renderBlocks(null) -> ""
        assertThat(toMd("{\"type\":\"doc\"}")).isEmpty();
    }

    @Test
    void toMarkdownReturnsEmptyWhenContentNotArray() {
        // content present but not an array -> renderBlocks early return ""
        assertThat(toMd("{\"type\":\"doc\",\"content\":{\"type\":\"paragraph\"}}")).isEmpty();
    }

    // ---------- heading branch (level clamp 1..6) ----------

    @Test
    void headingUsesAttrLevel() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"H3"}]}]}
            """);
        assertThat(md).isEqualTo("### H3");
    }

    @Test
    void headingDefaultsToLevelOneWhenAttrMissing() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"heading","content":[{"type":"text","text":"Top"}]}]}
            """);
        assertThat(md).isEqualTo("# Top");
    }

    @Test
    void headingClampsLevelAboveSixDownToSix() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":9},"content":[{"type":"text","text":"Deep"}]}]}
            """);
        assertThat(md).isEqualTo("###### Deep");
    }

    @Test
    void headingClampsLevelBelowOneUpToOne() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":0},"content":[{"type":"text","text":"Zero"}]}]}
            """);
        assertThat(md).isEqualTo("# Zero");
    }

    // ---------- paragraph branch (blank vs non-blank) ----------

    @Test
    void paragraphRendersInlineText() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"Hello world"}]}]}
            """);
        assertThat(md).isEqualTo("Hello world");
    }

    @Test
    void blankParagraphIsDropped() {
        // first paragraph is blank (empty text) -> "" ; second renders -> only second survives
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"   "}]},
              {"type":"paragraph","content":[{"type":"text","text":"Real"}]}]}
            """);
        assertThat(md).isEqualTo("Real");
    }

    @Test
    void paragraphWithNoContentIsDropped() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph"},
              {"type":"paragraph","content":[{"type":"text","text":"After"}]}]}
            """);
        assertThat(md).isEqualTo("After");
    }

    // ---------- bulletList / orderedList branches + renderList ----------

    @Test
    void bulletListUsesDashMarker() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"bulletList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"one"}]}]},
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"two"}]}]}]}]}
            """);
        assertThat(md).isEqualTo("- one\n- two");
    }

    @Test
    void orderedListUsesIncrementingNumbers() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"orderedList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"alpha"}]}]},
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"beta"}]}]},
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"gamma"}]}]}]}]}
            """);
        assertThat(md).isEqualTo("1. alpha\n2. beta\n3. gamma");
    }

    @Test
    void listWithNullItemsRendersNothing() {
        // bulletList without content -> renderList(null,...) returns "" then "\n" appended, then strip()
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"bulletList"}]}
            """);
        assertThat(md).isEmpty();
    }

    @Test
    void listItemCollapsesDoubleNewlinesToSingle() {
        // list item with two paragraphs -> "\n\n" between collapsed to "\n", joined inline after marker
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"bulletList","content":[
                {"type":"listItem","content":[
                  {"type":"paragraph","content":[{"type":"text","text":"line1"}]},
                  {"type":"paragraph","content":[{"type":"text","text":"line2"}]}]}]}]}
            """);
        assertThat(md).isEqualTo("- line1\nline2");
    }

    // ---------- codeBlock branch (with + without language) ----------

    @Test
    void codeBlockIncludesLanguageFence() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"codeBlock","attrs":{"language":"java"},"content":[{"type":"text","text":"int x = 1;"}]}]}
            """);
        assertThat(md).isEqualTo("```java\nint x = 1;\n```");
    }

    @Test
    void codeBlockWithoutLanguageHasBareFence() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"codeBlock","content":[{"type":"text","text":"plain code"}]}]}
            """);
        assertThat(md).isEqualTo("```\nplain code\n```");
    }

    // ---------- blockquote branch (prefixes each line) ----------

    @Test
    void blockquotePrefixesEachLine() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"blockquote","content":[
                {"type":"paragraph","content":[{"type":"text","text":"q1"}]},
                {"type":"paragraph","content":[{"type":"text","text":"q2"}]}]}]}
            """);
        assertThat(md).isEqualTo("> q1\n> \n> q2");
    }

    // ---------- panel branch (unwraps children) ----------

    @Test
    void panelUnwrapsChildrenWithoutDecoration() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"panel","attrs":{"panelType":"info"},"content":[
                {"type":"paragraph","content":[{"type":"text","text":"noted"}]}]}]}
            """);
        assertThat(md).isEqualTo("noted");
    }

    // ---------- rule branch ----------

    @Test
    void ruleRendersHorizontalRule() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"a"}]},
              {"type":"rule"},
              {"type":"paragraph","content":[{"type":"text","text":"b"}]}]}
            """);
        assertThat(md).isEqualTo("a\n\n---\n\nb");
    }

    // ---------- table branch + renderTable ----------

    @Test
    void tableRendersHeaderSeparatorAndRows() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"table","content":[
                {"type":"tableRow","content":[
                  {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"A"}]}]},
                  {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"B"}]}]}]},
                {"type":"tableRow","content":[
                  {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"1"}]}]},
                  {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"2"}]}]}]}]}]}
            """);
        assertThat(md).isEqualTo("| A | B |\n| --- | --- |\n| 1 | 2 |");
    }

    @Test
    void tableCellNewlinesCollapsedToSpaces() {
        // a cell containing two paragraphs -> "\n\n" between -> each \n replaced with a space (so two spaces)
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"table","content":[
                {"type":"tableRow","content":[
                  {"type":"tableCell","content":[
                    {"type":"paragraph","content":[{"type":"text","text":"x"}]},
                    {"type":"paragraph","content":[{"type":"text","text":"y"}]}]}]}]}]}
            """);
        assertThat(md).isEqualTo("| x  y |\n| --- |");
    }

    @Test
    void tableWithoutContentRendersEmpty() {
        // table node with no "content" -> renderTable returns "" then "\n" appended then strip()
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"table"}]}
            """);
        assertThat(md).isEmpty();
    }

    // ---------- default block branch: unknown-with-content recurses ----------

    @Test
    void unknownNodeWithContentRecursesIntoChildren() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"weirdWrapper","content":[
                {"type":"paragraph","content":[{"type":"text","text":"inside"}]}]}]}
            """);
        assertThat(md).isEqualTo("inside");
    }

    @Test
    void unknownNodeWithTextRendersAsInline() {
        // unknown block, no "content" but has "text" -> renderInline(n)+"\n\n"
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"oddText","text":"loose"}]}
            """);
        assertThat(md).isEqualTo("loose");
    }

    @Test
    void unknownNodeWithoutContentOrTextRendersNothing() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"mediaSingle","attrs":{"layout":"center"}},
              {"type":"paragraph","content":[{"type":"text","text":"kept"}]}]}
            """);
        assertThat(md).isEqualTo("kept");
    }

    // ---------- inline marks: strong / em / code / link / unknown ----------

    @Test
    void textMarkStrong() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"bold","marks":[{"type":"strong"}]}]}]}
            """);
        assertThat(md).isEqualTo("**bold**");
    }

    @Test
    void textMarkEm() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"it","marks":[{"type":"em"}]}]}]}
            """);
        assertThat(md).isEqualTo("*it*");
    }

    @Test
    void textMarkCode() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"x()","marks":[{"type":"code"}]}]}]}
            """);
        assertThat(md).isEqualTo("`x()`");
    }

    @Test
    void textMarkLinkUsesHref() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"text","text":"BNC","marks":[{"type":"link","attrs":{"href":"https://bnc.ca"}}]}]}]}
            """);
        assertThat(md).isEqualTo("[BNC](https://bnc.ca)");
    }

    @Test
    void textMarkLinkWithoutHrefDefaultsEmpty() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"text","text":"x","marks":[{"type":"link"}]}]}]}
            """);
        assertThat(md).isEqualTo("[x]()");
    }

    @Test
    void unknownMarkLeavesTextUnchanged() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"text","text":"plain","marks":[{"type":"underline"}]}]}]}
            """);
        assertThat(md).isEqualTo("plain");
    }

    @Test
    void multipleMarksNestInOrder() {
        // strong applied first, then em wraps the result
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"text","text":"hot","marks":[{"type":"strong"},{"type":"em"}]}]}]}
            """);
        assertThat(md).isEqualTo("***hot***");
    }

    @Test
    void textWithoutTextFieldRendersEmptyMark() {
        // text node with no "text" attribute -> "" base, mark wraps empty
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"text","marks":[{"type":"strong"}]},
                {"type":"text","text":"tail"}]}]}
            """);
        assertThat(md).isEqualTo("****tail");
    }

    // ---------- inline node types: hardBreak / mention / emoji ----------

    @Test
    void hardBreakRendersTwoSpaceNewline() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"text","text":"a"},
                {"type":"hardBreak"},
                {"type":"text","text":"b"}]}]}
            """);
        assertThat(md).isEqualTo("a  \nb");
    }

    @Test
    void mentionUsesTextAttr() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"mention","attrs":{"id":"557","text":"Alice"}}]}]}
            """);
        assertThat(md).isEqualTo("@Alice");
    }

    @Test
    void mentionFallsBackToIdWhenTextMissing() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"mention","attrs":{"id":"557058:abc"}}]}]}
            """);
        assertThat(md).isEqualTo("@557058:abc");
    }

    @Test
    void emojiUsesTextAttr() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"text","text":"ship it "},
                {"type":"emoji","attrs":{"shortName":":rocket:","text":"🚀"}}]}]}
            """);
        assertThat(md).isEqualTo("ship it 🚀");
    }

    // ---------- inline default branch: unknown inline node ----------

    @Test
    void unknownInlineWithContentRecurses() {
        // an inline-wrapping unknown node carrying nested content
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"inlineCard","content":[{"type":"text","text":"nested"}]}]}]}
            """);
        assertThat(md).isEqualTo("nested");
    }

    @Test
    void unknownInlineWithTextRendersText() {
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"status","text":"DONE","attrs":{"color":"green"}}]}]}
            """);
        assertThat(md).isEqualTo("DONE");
    }

    @Test
    void unknownInlineWithoutContentOrTextRendersEmpty() {
        // paragraph holding only an unrenderable inline -> blank paragraph -> dropped
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[
                {"type":"date","attrs":{"timestamp":"123"}}]}]}
            """);
        assertThat(md).isEmpty();
    }

    // ---------- renderInline object vs array branch ----------

    @Test
    void inlineContentObjectHandledLikeSingleNode() {
        // heading whose content is a single OBJECT (not array) -> renderInline isObject branch
        String md = toMd("""
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":{"type":"text","text":"Solo"}}]}
            """);
        assertThat(md).isEqualTo("## Solo");
    }

    // ---------- normalize: happy path + error path ----------

    @Test
    void normalizeProducesJiraNormalizedDoc() {
        String json = """
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Title"}]},
              {"type":"paragraph","content":[{"type":"text","text":"body"}]}]}
            """;
        NormalizedDoc doc = adf.normalize("JIRA-99", "My Title", json);

        assertThat(doc.sourceType()).isEqualTo("jira");
        assertThat(doc.sourceId()).isEqualTo("JIRA-99");
        assertThat(doc.title()).isEqualTo("My Title");
        assertThat(doc.markdown()).isEqualTo("# Title\n\nbody");
    }

    @Test
    void normalizeReturnsEmptyMarkdownOnParseFailure() {
        NormalizedDoc doc = adf.normalize("JIRA-BAD", "Broken", "{not valid json");

        assertThat(doc.sourceType()).isEqualTo("jira");
        assertThat(doc.sourceId()).isEqualTo("JIRA-BAD");
        assertThat(doc.title()).isEqualTo("Broken");
        assertThat(doc.markdown()).isEmpty();
    }

    @Test
    void normalizeReturnsEmptyMarkdownWhenJsonIsNullLiteral() {
        // mapper.readTree("null") yields a NullNode -> toMarkdown sees non-null node but no content
        NormalizedDoc doc = adf.normalize("JIRA-NULL", "N", "null");
        assertThat(doc.markdown()).isEmpty();
    }

    // ---------- end-to-end composition sanity ----------

    @Test
    void mixedDocumentComposesAllBlockTypesWithBlankLineSeparators() {
        String json = """
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Spec"}]},
              {"type":"paragraph","content":[
                {"type":"text","text":"see "},
                {"type":"text","text":"docs","marks":[{"type":"link","attrs":{"href":"http://x"}}]}]},
              {"type":"orderedList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"first"}]}]}]}]}
            """;
        String md = toMd(json);

        assertThat(md)
                .startsWith("## Spec")
                .contains("see [docs](http://x)")
                .contains("1. first")
                .doesNotContain("\"type\"");
    }
}
