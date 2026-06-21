package ca.bnc.qe.veritas.testmgmt;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.ingest.IngestService;
import ca.bnc.qe.veritas.ingest.TestBasis;
import ca.bnc.qe.veritas.ingest.TestBasisItem;
import org.springframework.stereotype.Component;

/** Builds the test basis text fed to LLM test-management skills — from the codebase or from Jira/Confluence. */
@Component
public class BasisBuilder {

    private final JavaSpringExtractor javaSpringExtractor;
    private final IngestService ingestService;

    public BasisBuilder(JavaSpringExtractor javaSpringExtractor, IngestService ingestService) {
        this.javaSpringExtractor = javaSpringExtractor;
        this.ingestService = ingestService;
    }

    public String fromRepo(Path repoPath) {
        ApiModel model = javaSpringExtractor.extract(repoPath);
        StringBuilder sb = new StringBuilder("Endpoints (from code):\n");
        for (Endpoint e : model.endpoints()) {
            sb.append("- ").append(e.signature()).append("\n");
        }
        return sb.toString();
    }

    public String fromIngest(String jql, List<String> pageIds) {
        TestBasis basis = ingestService.assemble(jql, pageIds, 100);
        StringBuilder sb = new StringBuilder("Test basis (from Jira/Confluence):\n");
        for (TestBasisItem item : basis.items()) {
            sb.append("- [").append(item.id()).append("] ").append(item.kind()).append(": ").append(item.text()).append("\n");
        }
        return sb.toString();
    }
}
