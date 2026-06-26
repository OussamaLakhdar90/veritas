package ca.bnc.qe.veritas.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfluenceStorageToMarkdownTest {

    private final ConfluenceStorageToMarkdown conf = new ConfluenceStorageToMarkdown();

    private String md(String xhtml) {
        return conf.normalize("CONF-1", "Title", xhtml).markdown();
    }

    @Test
    void convertsStorageXhtmlIncludingCodeMacroAndTable() {
        String xhtml = """
            <h1>Policy API</h1>
            <p>The service manages <strong>policies</strong>.</p>
            <h2>Acceptance Criteria</h2>
            <ul><li>User must provide a valid name</li><li>Code length must be 10 or less</li></ul>
            <table><tbody>
              <tr><th>Field</th><th>Rule</th></tr>
              <tr><td>name</td><td>required</td></tr>
            </tbody></table>
            <ac:structured-macro ac:name="code">
              <ac:parameter ac:name="language">json</ac:parameter>
              <ac:plain-text-body><![CDATA[{"x":1}]]></ac:plain-text-body>
            </ac:structured-macro>
            """;
        String md = md(xhtml);

        assertThat(md).contains("# Policy API");
        assertThat(md).contains("**policies**");
        assertThat(md).contains("## Acceptance Criteria");
        assertThat(md).contains("- User must provide a valid name");
        assertThat(md).contains("| Field | Rule |");
        assertThat(md).contains("| name | required |");
        assertThat(md).contains("```json");
        assertThat(md).contains("{\"x\":1}");
        // storage macro/markup scaffolding must be gone
        assertThat(md).doesNotContain("ac:structured-macro");
    }

    // ----- top-level normalize() guards -----

    @Test
    void nullStorageProducesEmptyMarkdownButKeepsMetadata() {
        NormalizedDoc doc = conf.normalize("CONF-9", "Empty Page", null);
        assertThat(doc.markdown()).isEmpty();
        assertThat(doc.sourceType()).isEqualTo("confluence");
        assertThat(doc.sourceId()).isEqualTo("CONF-9");
        assertThat(doc.title()).isEqualTo("Empty Page");
    }

    @Test
    void emptyStringStorageProducesEmptyMarkdown() {
        assertThat(md("")).isEmpty();
    }

    @Test
    void resultIsStrippedOfLeadingAndTrailingWhitespace() {
        // wrapping a heading in newlines; output must not start/end with blank lines
        String md = md("\n\n   <h3>Trimmed</h3>   \n\n");
        assertThat(md).isEqualTo("### Trimmed");
    }

    @Test
    void looseTopLevelTextNodeIsPreservedWhenNotBlank() {
        // a bare text node at the document root goes through render(TextNode)
        assertThat(md("Just loose text")).contains("Just loose text");
    }

    @Test
    void blankTopLevelTextNodesAreDropped() {
        // whitespace-only text nodes between block elements should not leak into output
        String md = md("<p>One</p>\n\n   \n\n<p>Two</p>");
        assertThat(md).isEqualTo("One\n\nTwo");
    }

    // ----- headings (Integer.parseInt of every level) -----

    @Test
    void rendersAllSixHeadingLevels() {
        String md = md("<h1>A</h1><h2>B</h2><h3>C</h3><h4>D</h4><h5>E</h5><h6>F</h6>");
        assertThat(md).contains("# A");
        assertThat(md).contains("## B");
        assertThat(md).contains("### C");
        assertThat(md).contains("#### D");
        assertThat(md).contains("##### E");
        assertThat(md).contains("###### F");
    }

    // ----- paragraph branches -----

    @Test
    void emptyParagraphProducesNothing() {
        // <p> whose inline content strips to empty must not emit blank "\n\n"
        String md = md("<p>  </p><p>Real</p>");
        assertThat(md).isEqualTo("Real");
    }

    // ----- lists -----

    @Test
    void orderedListNumbersSequentially() {
        String md = md("<ol><li>first</li><li>second</li><li>third</li></ol>");
        assertThat(md).contains("1. first");
        assertThat(md).contains("2. second");
        assertThat(md).contains("3. third");
    }

    @Test
    void unorderedListUsesDashBullets() {
        String md = md("<ul><li>alpha</li><li>beta</li></ul>");
        assertThat(md).contains("- alpha");
        assertThat(md).contains("- beta");
    }

    // ----- table branches: header-separator only once, empty rows skipped -----

    @Test
    void tableEmitsSeparatorOnceAndSkipsRowsWithoutCells() {
        String xhtml = """
            <table><tbody>
              <tr><th>H1</th><th>H2</th></tr>
              <tr></tr>
              <tr><td>a</td><td>b</td></tr>
              <tr><td>c</td><td>d</td></tr>
            </tbody></table>
            """;
        String md = md(xhtml);
        assertThat(md).contains("| H1 | H2 |");
        assertThat(md).contains("| a | b |");
        assertThat(md).contains("| c | d |");
        // separator row appears exactly once (after header), matching 2 columns
        assertThat(md).contains("| --- | --- |");
        int sepCount = md.split("\\| --- \\| --- \\|", -1).length - 1;
        assertThat(sepCount).isEqualTo(1);
    }

    @Test
    void tableCellNewlinesAreFlattenedToSpaces() {
        // a <br> inside a cell becomes "  \n" then is replaced by a single space (with extra spaces collapsed by us not)
        String xhtml = "<table><tbody><tr><td>line1<br/>line2</td><td>ok</td></tr></tbody></table>";
        String md = md(xhtml);
        assertThat(md).doesNotContain("line1\nline2");
        assertThat(md).contains("line1");
        assertThat(md).contains("line2");
        // both cells live on the same markdown row
        assertThat(md).contains("| ok |");
    }

    // ----- pre block -----

    @Test
    void preTagBecomesFencedCodeBlock() {
        String md = md("<pre>some code here</pre>");
        assertThat(md).contains("```\nsome code here\n```");
    }

    // ----- blockquote (multiline prefix replace) -----

    @Test
    void blockquoteGetsQuotePrefixOnEveryLine() {
        // a hard <br> inside the quote yields an inline newline that must be re-prefixed with "> "
        String md = md("<blockquote>first line<br/>second line</blockquote>");
        assertThat(md).startsWith("> ");
        assertThat(md).contains("first line");
        assertThat(md).contains("second line");
        // the internal break must carry the quote marker on the continuation line
        assertThat(md).contains("\n> ");
    }

    // ----- macro: code (language present + body via text) -----

    @Test
    void codeMacroWithoutLanguageStillFences() {
        String xhtml = """
            <ac:structured-macro ac:name="code">
              <ac:plain-text-body>plain body</ac:plain-text-body>
            </ac:structured-macro>
            """;
        String md = md(xhtml);
        // no language → bare fence
        assertThat(md).contains("```\nplain body\n```");
    }

    @Test
    void codeMacroWithCdataBodyUsesWholeTextWhenTextIsBlank() {
        // CDATA whose .text() is blank forces the body.wholeText() branch
        String xhtml = """
            <ac:structured-macro ac:name="code">
              <ac:parameter ac:name="language">java</ac:parameter>
              <ac:plain-text-body><![CDATA[int x = 1;]]></ac:plain-text-body>
            </ac:structured-macro>
            """;
        String md = md(xhtml);
        assertThat(md).contains("```java");
        assertThat(md).contains("int x = 1;");
    }

    @Test
    void codeMacroWithNoBodyEmitsEmptyFence() {
        // body == null → code is ""
        String xhtml = "<ac:structured-macro ac:name=\"code\"></ac:structured-macro>";
        String md = md(xhtml);
        assertThat(md).contains("```");
    }

    @Test
    void codeMacroNameIsCaseInsensitive() {
        String xhtml = """
            <ac:structured-macro ac:name="CODE">
              <ac:plain-text-body>UC</ac:plain-text-body>
            </ac:structured-macro>
            """;
        assertThat(md(xhtml)).contains("```\nUC\n```");
    }

    // ----- macro: jira (present key vs empty key) -----

    @Test
    void jiraMacroWithKeyEmitsIssueReference() {
        String xhtml = """
            <ac:structured-macro ac:name="jira">
              <ac:parameter ac:name="key">PROJ-42</ac:parameter>
            </ac:structured-macro>
            """;
        assertThat(md(xhtml)).contains("JIRA:PROJ-42");
    }

    @Test
    void jiraMacroWithoutKeyEmitsNothing() {
        // missing key → macroParam returns "" → empty output
        String xhtml = "<p>before <ac:structured-macro ac:name=\"jira\"></ac:structured-macro>after</p>";
        String md = md(xhtml);
        assertThat(md).doesNotContain("JIRA:");
        assertThat(md).contains("before");
        assertThat(md).contains("after");
    }

    // ----- macro: info/note/panel/expand → unwrap rich-text-body -----

    @Test
    void infoMacroUnwrapsRichTextBodyContent() {
        String xhtml = """
            <ac:structured-macro ac:name="info">
              <ac:rich-text-body><p>Heads up: <strong>important</strong></p></ac:rich-text-body>
            </ac:structured-macro>
            """;
        String md = md(xhtml);
        assertThat(md).contains("Heads up: **important**");
        assertThat(md).doesNotContain("rich-text-body");
        assertThat(md).doesNotContain("ac:structured-macro");
    }

    @Test
    void unknownMacroWithoutRichBodyFallsBackToRenderingChildren() {
        // a non-code/jira macro with NO rich-text-body → renderChildren(macro) path
        String xhtml = """
            <ac:structured-macro ac:name="weirdmacro">
              <p>bare child paragraph</p>
            </ac:structured-macro>
            """;
        String md = md(xhtml);
        assertThat(md).contains("bare child paragraph");
        assertThat(md).doesNotContain("ac:structured-macro");
    }

    // ----- layout containers + default unwrap -----

    @Test
    void layoutContainersAreUnwrappedToTheirContent() {
        String xhtml = """
            <ac:layout>
              <ac:layout-section>
                <ac:layout-cell>
                  <h2>Inside Layout</h2>
                  <p>cell text</p>
                </ac:layout-cell>
              </ac:layout-section>
            </ac:layout>
            """;
        String md = md(xhtml);
        assertThat(md).contains("## Inside Layout");
        assertThat(md).contains("cell text");
        assertThat(md).doesNotContain("layout-cell");
        assertThat(md).doesNotContain("ac:layout");
    }

    @Test
    void unknownBlockElementIsUnwrappedViaDefaultBranch() {
        // <div> is not handled explicitly → default → renderChildren keeps inner content
        String md = md("<div><p>wrapped</p></div>");
        assertThat(md).contains("wrapped");
        assertThat(md).doesNotContain("<div>");
    }

    // ----- inline formatting branches -----

    @Test
    void inlineBoldItalicCodeAndLinkAreConverted() {
        String xhtml =
            "<p>"
            + "<strong>s</strong> <b>b</b> "
            + "<em>e</em> <i>i</i> "
            + "<code>c</code> "
            + "<a href=\"https://x.test/page\">link</a>"
            + "</p>";
        String md = md(xhtml);
        assertThat(md).contains("**s**");
        assertThat(md).contains("**b**");
        assertThat(md).contains("*e*");
        assertThat(md).contains("*i*");
        assertThat(md).contains("`c`");
        assertThat(md).contains("[link](https://x.test/page)");
    }

    @Test
    void inlineBreakBecomesHardLineBreak() {
        String md = md("<p>top<br/>bottom</p>");
        // <br> → "  \n"
        assertThat(md).contains("top  \nbottom");
    }

    @Test
    void inlineUnknownSpanIsUnwrappedKeepingText() {
        // <span> is not an inline case → default → recurse and keep text
        String md = md("<p>hello <span>world</span></p>");
        assertThat(md).contains("hello world");
        assertThat(md).doesNotContain("<span>");
    }

    @Test
    void inlineJiraMacroInsideParagraphIsRendered() {
        // exercises the inline "ac:structured-macro" branch via renderMacro(ce).strip()
        String xhtml = """
            <p>See <ac:structured-macro ac:name="jira">
              <ac:parameter ac:name="key">ABC-7</ac:parameter>
            </ac:structured-macro> now</p>
            """;
        String md = md(xhtml);
        assertThat(md).contains("JIRA:ABC-7");
        assertThat(md).contains("See");
        assertThat(md).contains("now");
    }

    @Test
    void nestedInlineFormattingInsideHeading() {
        // heading inline path with a link nested inside emphasis
        String md = md("<h2>Read <em>the <a href=\"u\">docs</a></em></h2>");
        assertThat(md).contains("## Read *the [docs](u)*");
    }
}
