package ca.bnc.qe.veritas.engine.model;

import java.util.List;

public record ResponseModel(
        int statusCode,
        String schemaRef,
        List<String> mediaTypes,
        String origin,        // RETURN | RESPONSE_ENTITY | RESPONSE_STATUS | EXCEPTION_HANDLER | SPEC
        SourceRef source
) {}
