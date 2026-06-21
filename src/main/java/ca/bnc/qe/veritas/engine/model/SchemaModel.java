package ca.bnc.qe.veritas.engine.model;

import java.util.List;

public record SchemaModel(
        String name,
        String type,
        List<FieldModel> fields,
        List<String> enumValues,
        SourceRef source
) {}
