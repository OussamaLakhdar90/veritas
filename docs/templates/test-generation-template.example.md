---
# Fill this in later. This skeleton is the contract Veritas reads (see ../test-generation-template.md).
framework:
  name: TODO            # e.g. ca.bnc.ciam:autotests
  language: TODO        # java | kotlin | typescript | ...
  version: TODO         # optional
buildTool: TODO         # maven | gradle | npm | none
verifyCommand: "TODO"   # e.g. mvn -q compile test-compile -DskipTests   (empty string = skip verify)
packageRoot: "TODO"     # e.g. {serviceName}Api
layout:
  baseTests:   "TODO"   # e.g. src/test/java/{packageRoot}/test/base
  validations: "TODO"   # e.g. src/test/java/{packageRoot}/test/happyPath
  models:      "TODO"   # e.g. src/main/java/models
  data:        "TODO"   # e.g. src/test/resources/data/{env}
  suite:       "TODO"   # e.g. suites
dataFormat: TODO        # e.g. data-manager-json | fixtures-json | csv
secretRef: "TODO"       # e.g. $sensitive:ENV_NAME   (never put literal secrets in generated data)
referenceFiles:         # the few real files the generator must mirror exactly
  - "TODO/path/to/ExampleBaseTest"
  - "TODO/path/to/ExampleValidationTest"
mergeFiles:             # files to APPEND to, never overwrite
  - "TODO"              # e.g. data-manager.json
---

# <Framework name> test-generation template

## Conventions
TODO — package structure, base classes, test naming (e.g. t001_/two-tier base + happy-path), annotations
(e.g. @DependentStep, @Factory(dataProvider), @Xray(requirement=...)). Note the import rule
(e.g. import from local base/utils/models packages, NOT directly from the framework jar).

## Assertions
TODO — assertion library/style and how expected vs actual is compared.

## Data
TODO — the data-file format and an example; how IDs that must pre-exist are handled (Veritas surfaces them
as PR TODOs, never invents them); the secret-reference convention.

## Suite
TODO — the suite/config file format and an example.

## Do / Don't
TODO — explicit rules the generator must follow and patterns it must never introduce.
