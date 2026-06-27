package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.catalog.ServiceCatalog;
import ca.bnc.qe.veritas.catalog.ServiceSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service catalog: every service the platform holds work for, with per-stage counts — backs the dashboard picker. */
@RestController
@RequestMapping("/api/v1")
public class ServicesController {

    private final ServiceCatalog catalog;

    public ServicesController(ServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/services")
    public List<ServiceSummary> services() {
        return catalog.catalog();
    }
}
