package secured;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/secured")
public class SecuredController {

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SecuredResponse get(@PathVariable String id) {
        return null;
    }
}
