package ca.bnc.qe.veritas.defect;

import java.time.Instant;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.DefectLinkRepository;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.skill.GateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Creates a Jira defect from a finding (outward action). Idempotent: a finding already linked returns its
 * existing defect. Uses the invoking user's Jira token via the client; records a {@link DefectLink} and
 * flips the finding to JIRA_CREATED.
 */
@Service
@Slf4j
public class DefectService {

    private final FindingRecordRepository findingRepository;
    private final ScanRepository scanRepository;
    private final DefectLinkRepository defectRepository;
    private final DefectComposer composer;
    private final JiraClient jira;
    private final ConnectionsProperties connections;
    private final GateService gateService;
    private final Preflight preflight;

    public DefectService(FindingRecordRepository findingRepository, ScanRepository scanRepository,
                         DefectLinkRepository defectRepository, DefectComposer composer,
                         JiraClient jira, ConnectionsProperties connections, GateService gateService,
                         Preflight preflight) {
        this.findingRepository = findingRepository;
        this.scanRepository = scanRepository;
        this.defectRepository = defectRepository;
        this.composer = composer;
        this.jira = jira;
        this.connections = connections;
        this.gateService = gateService;
        this.preflight = preflight;
    }

    public DefectLink createDefect(String findingId, String projectKey, String issueType, String owner) {
        preflight.createDefect(findingId, projectKey);
        var existing = defectRepository.findByFindingId(findingId);
        if (existing.isPresent()) {
            return existing.get();   // idempotent — no duplicate ticket
        }
        GateService.Decision gate = gateService.await(findingId, "CREATE_DEFECT", owner);
        if (!gate.approved()) {
            throw new IllegalStateException("Defect creation for " + findingId
                    + " awaiting approval (gate " + gate.gateId() + ")");
        }
        preflight.requireJiraWriteScope(projectKey);   // fail fast on a missing/insufficient token, not mid-write
        FindingRecord finding = findingRepository.findById(findingId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown finding " + findingId));
        String serviceName = scanRepository.findById(finding.getScanId())
                .map(Scan::getServiceName).orElse(null);

        JiraCreateRequest request = composer.compose(finding, serviceName, projectKey, issueType);
        String key = jira.createIssue(request);

        DefectLink link = new DefectLink();
        link.setFindingId(findingId);
        link.setScanId(finding.getScanId());
        link.setJiraKey(key);
        link.setJiraUrl(browseUrl(key));
        link.setCreatedInJira(true);
        link.setCreatedBy(owner);
        link.setLastSyncedAt(Instant.now());
        link = defectRepository.save(link);

        // Attach the corrected OpenAPI YAML to the ticket (matches the reference app); non-fatal on failure.
        if (finding.getProposedFix() != null && !finding.getProposedFix().isBlank()) {
            try {
                jira.attachFile(key, "corrected-openapi.yaml", finding.getProposedFix());
            } catch (Exception e) {
                log.warn("Could not attach corrected YAML to {}: {}", key, e.getMessage());
            }
        }

        finding.setStatus("JIRA_CREATED");
        findingRepository.save(finding);
        log.info("Created defect {} for finding {}", key, findingId);
        return link;
    }

    private String browseUrl(String key) {
        String base = connections.getJira().getBaseUrl();
        if (base == null || base.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/browse/" + key;
    }
}
