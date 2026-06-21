package ca.bnc.qe.veritas.engine.model;

import java.util.List;

public record Endpoint(
        HttpMethod method,
        String pathTemplate,
        String operationId,
        List<ParamModel> params,
        RequestBodyModel requestBody,
        List<ResponseModel> responses,
        List<String> consumes,
        List<String> produces,
        List<String> security,   // required roles/scopes/expressions (code) or scheme names (spec); empty = unsecured
        SourceRef source
) {
    public String signature() {
        return method + " " + pathTemplate;
    }
}
