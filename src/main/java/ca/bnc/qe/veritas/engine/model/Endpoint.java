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
        SourceRef source,
        String controllerClass   // declaring controller class simple name (code side); null for spec endpoints
) {
    /** Back-compat constructor for callers without a declaring class (spec endpoints, fixtures): {@code controllerClass=null}. */
    public Endpoint(HttpMethod method, String pathTemplate, String operationId, List<ParamModel> params,
                    RequestBodyModel requestBody, List<ResponseModel> responses, List<String> consumes,
                    List<String> produces, List<String> security, SourceRef source) {
        this(method, pathTemplate, operationId, params, requestBody, responses, consumes, produces, security, source, null);
    }

    public String signature() {
        return method + " " + pathTemplate;
    }
}
