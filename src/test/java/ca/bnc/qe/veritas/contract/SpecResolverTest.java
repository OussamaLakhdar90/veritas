package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecResolverTest {

    private SpecResolver resolver(ConfluenceClient confluence) {
        return new SpecResolver(confluence);
    }

    @Test
    void readsSpecFromRepoPath(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("openapi.yaml"), "openapi: 3.0.0\npaths: {}\n");
        SpecInput spec = resolver(id -> null)
                .resolve(new SpecSource(SpecSourceKind.REPO_PATH, "openapi.yaml"), repo);
        assertThat(spec.id()).isEqualTo("openapi");
        assertThat(spec.content()).contains("openapi: 3.0.0");
    }

    @Test
    void extractsYamlFromConfluenceCodeMacroPreservingNewlines() {
        String storage = "<p>Intro text</p>"
                + "<ac:structured-macro ac:name=\"code\">"
                + "<ac:parameter ac:name=\"language\">yaml</ac:parameter>"
                + "<ac:plain-text-body><![CDATA[openapi: 3.0.0\n"
                + "paths:\n"
                + "  /policies:\n"
                + "    get:\n"
                + "      responses:\n"
                + "        '200':\n"
                + "          description: ok\n]]></ac:plain-text-body>"
                + "</ac:structured-macro>";
        ConfluenceClient confluence = id -> new ConfluencePage(id, "Policies API", storage);

        SpecInput spec = resolver(confluence)
                .resolve(new SpecSource(SpecSourceKind.CONFLUENCE, "12345"), null);

        assertThat(spec.id()).isEqualTo("confluence-12345");
        assertThat(spec.content()).startsWith("openapi: 3.0.0");
        assertThat(spec.content()).contains("\n  /policies:\n");   // YAML indentation/newlines preserved
        assertThat(spec.content()).doesNotContain("Intro text");
    }

    @Test
    void picksTheMostSpecLikeBlockWhenSeveralExist() {
        String storage = "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body>"
                + "<![CDATA[echo \"just a shell snippet\"]]></ac:plain-text-body></ac:structured-macro>"
                + "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body>"
                + "<![CDATA[swagger: \"2.0\"\npaths:\n  /x: {}\n]]></ac:plain-text-body></ac:structured-macro>";
        SpecInput spec = resolver(id -> new ConfluencePage(id, "t", storage))
                .resolve(new SpecSource(SpecSourceKind.CONFLUENCE, "9"), null);
        assertThat(spec.content()).startsWith("swagger:");
        assertThat(spec.content()).doesNotContain("shell snippet");
    }
}
