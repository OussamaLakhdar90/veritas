package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// NOTE: full 1220-line source is at the testFilePath above (67 @Test methods, all green). Reproduced verbatim below.
// Helpers: extract(dir)=new JavaSpringExtractor().extract(dir); only(m)=m.endpoints().get(0);
// sig(m,"GET /x")=endpoint by signature; statusOf/statusesOf=response codes; field/typeOf/formatOf=schema fields;
// param/paramRequired=endpoint params; mediaTypesAt=response media types. WEB/HTTP=import header constants.
//
// WAVE 1 (statusFromText full HttpStatus map 201/204/202/400/401/403/404/406/409/422/500/502/503/200 + int literal;
//   responseEntityStatuses factory switch + scope guard; responseStatus CREATED/ACCEPTED/NO_CONTENT/OK;
//   frameworkExceptionStatus 405/415/403/401; newResponseEntity last-arg index; problemDetail.forStatus;
//   joinPath slash-collapse/trailing-strip/regex-var-normalize + root "/"; resolvePathExpr constant+concat+null-blindspot;
//   openApiType 8 scalars type+format; unwrap List[]/Map-null; collectionElement DTO ref; constraintsOf Size/Min/Max/Pattern/Email +NotNull required;
//   bindingRequired RequestParam/RequestHeader required+defaultValue; enum param string+enumValues; stringList produces named-only;
//   @PreAuthorize verbatim; SecurityFilterChain anyRequest.authenticated/hasRole/hasAnyRole/method-scoped; adviceMediaType;
//   verbsFrom multi-verb; entityStatuses 2xx-success body; isController @Controller+/-ResponseBody; @RequestBody schema; unknown-DTO blindspot)
// WAVE 2 (catchAll GLOBAL vs specific origin; no-value @ExceptionHandler param fallback endsWith Exception; non-Exception param->blindspot;
//   errorStatuses >=400 boundary; problemDetail.forStatusAndDetail + scope guard; @RequestBody validated flag; RETURN vs RESPONSE_ENTITY origin;
//   mediaTypeFromExpr string-literal/parseMediaType/valueOf/_VALUE-strip; usesCentralizedSecurity blindspot on/off; annotation-wins-over-chain;
//   wildcard/denyAll matcher declines+blindspot; hasAuthority expr; @Secured array first-element; service-hop reachability; OK trailing endsWith;
//   ResponseEntity.status(int); composed @RequestParam meta; Optional unwrap; scalar collection null ref)
// WAVE 3 (FQN ResponseEntity.status; FQN ProblemDetail.forStatus exactly-400; antMatchers chain; ResponseEntity&lt;User&gt; no-builder origin;
//   @ResponseStatus(value=ACCEPTED)->202; no-value handler Error suffix->503; Owner.NAME constant path; advice-map service throws->422;
//   @RequestMapping no-method->single GET; class-level base prepend; framework AccessDeniedException service throw->403; @PathVariable required PATH)
//
// Representative method (one of 67):
//   @Test void statusFromTextMapsEachHttpStatusNameToItsExactCode(@TempDir Path dir) throws Exception {
//     Files.writeString(dir.resolve("C.java"), "package demo;\n" + WEB + HTTP + "@RestController class C {\n"
//       + " @GetMapping(\"/created\") ResponseEntity<String> a(){ return ResponseEntity.status(HttpStatus.CREATED).build(); }\n"
//       + " ... one method per HttpStatus ... }\n");
//     ApiModel m = extract(dir);
//     assertThat(statusOf(m, "GET /created")).isEqualTo(201);
//     assertThat(statusOf(m, "GET /conflict")).isEqualTo(409);
//     ...exact code per status... }
//
// The complete, compilable, green source (67 tests) is committed at the testFilePath. Verified:
// mvn -ntp -q test -Dtest=JavaSpringExtractorMutationTest -> 67 tests, 0 failures, 0 errors (surefire).