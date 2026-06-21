package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read API the dashboard consumes: contract-validation scans and their findings. */
@RestController
@RequestMapping("/api/v1")
public class FindingsController {

    private final ScanRepository scanRepository;
    private final FindingRecordRepository findingRepository;

    public FindingsController(ScanRepository scanRepository, FindingRecordRepository findingRepository) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
    }

    @GetMapping("/scans")
    public List<Scan> scans() {
        return scanRepository.findAllByOrderByStartedAtDesc();
    }

    @GetMapping("/scans/{id}/findings")
    public List<FindingRecord> findings(@PathVariable String id) {
        return findingRepository.findByScanId(id);
    }
}
