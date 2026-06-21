package ca.bnc.qe.veritas.engine.model;

import java.util.List;

public record RequestBodyModel(
        String schemaRef,
        boolean required,
        boolean validated,
        List<String> mediaTypes,
        SourceRef source
) {}
