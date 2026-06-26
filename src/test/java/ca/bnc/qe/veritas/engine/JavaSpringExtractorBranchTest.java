package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Branch-coverage companion for {@link JavaSpringExtractor} (does NOT edit the existing sibling tests).
 * Each test writes a tiny @TempDir Spring source fixture, runs the real extractor, and asserts concrete
 * values on the produced {@link ApiModel} — exercising the many uncovered edge branches: multi-path /
 * multi-method mappings, constant + placeholder path refs, every wrapper unwrap, all binding-param kinds
 * with required/defaultValue, composed/meta annotations, enums, nested DTOs, @ControllerAdvice statuses,
 * consumes/produces, inherited + interface mappings, and the SecurityFilterChain resolver.
 */
class JavaSpringExtractorBranchTest {

    private static final String HDR = "package demo;\nimport org.springframework.web.bind.annotation.*;\n";

    // ---------- small helpers ----------

    private static ApiModel extract(Path dir) {
        return new JavaSpringExtractor().extract(dir);
    }

    private static Endpoint ep(ApiModel m, String signature) {
        return m.endpoints().stream().filter(e -> e.signature().equals(signature)).findFirst().orElseThrow();
    }

    private static ParamModel param(Endpoint e, String name) {
        return e.params().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private static FieldModel field(SchemaModel s, String name) {
        return s.fields().stream().filter(f -> f.jsonName().equals(name)).findFirst().orElseThrow();
    }

    // ======================================================================================
    // Mapping shapes: multi-path, multi-method, @RequestMapping verbs, class × method product
    // ======================================================================================

    @Test
    void multiPathMappingProducesOneEndpointPerPath(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping({\"/a\",\"/b\"}) String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints()).hasSize(2);
        assertThat(m.endpoints()).extracting(Endpoint::pathTemplate).containsExactlyInAnyOrder("/a", "/b");
        assertThat(m.endpoints()).allMatch(e -> e.method() == HttpMethod.GET);
    }

    @Test
    void requestMappingWithMultipleMethodsProducesEndpointPerVerb(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @RequestMapping(value=\"/x\", method={RequestMethod.GET, RequestMethod.POST}) "
                + "String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints()).extracting(Endpoint::method)
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(m.endpoints()).allMatch(e -> e.pathTemplate().equals("/x"));
    }

    @Test
    void requestMappingWithoutMethodDefaultsToGet(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @RequestMapping(\"/only\") String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints()).singleElement().satisfies(e -> {
            assertThat(e.method()).isEqualTo(HttpMethod.GET);
            assertThat(e.pathTemplate()).isEqualTo("/only");
        });
    }

    @Test
    void classAndMethodPathsAreJoinedAndDuplicateSlashesCollapsed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController @RequestMapping(\"/api/\") class C { @GetMapping(\"/users/\") String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints().get(0).pathTemplate()).isEqualTo("/api/users");
    }

    @Test
    void classMultiPathTimesMethodMultiPathProducesCartesianProduct(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController @RequestMapping({\"/a\",\"/b\"}) class C { @GetMapping({\"/x\",\"/y\"}) String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints()).extracting(Endpoint::pathTemplate)
                .containsExactlyInAnyOrder("/a/x", "/a/y", "/b/x", "/b/y");
    }

    @Test
    void mappingWithNoPathYieldsRootFromClassBase(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints().get(0).pathTemplate()).isEqualTo("/");
    }

    @Test
    void allFiveShortcutVerbsAreRecognised(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/g\") String g(){return null;}"
                + " @PostMapping(\"/p\") String p(){return null;}"
                + " @PutMapping(\"/u\") String u(){return null;}"
                + " @PatchMapping(\"/pa\") String pa(){return null;}"
                + " @DeleteMapping(\"/d\") String d(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints()).extracting(Endpoint::method).containsExactlyInAnyOrder(
                HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    }

    @Test
    void regexPathVariableConstraintIsNormalisedToBareName(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/users/{id:\\\\d+}\") String g(@PathVariable String id){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints().get(0).pathTemplate()).isEqualTo("/users/{id}");
    }

    // ======================================================================================
    // Constant + placeholder path resolution
    // ======================================================================================

    @Test
    void constantPathRefIsResolvedToLiteralValue(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Paths.java"),
                "package demo; public class Paths { public static final String BASE = \"/const\"; }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(Paths.BASE) String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints().get(0).pathTemplate()).isEqualTo("/const");
        assertThat(m.blindSpots()).noneMatch(b -> b.contains("could not be resolved"));
    }

    @Test
    void concatenatedConstantPathIsResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Paths.java"),
                "package demo; public class Paths { public static final String ROOT = \"/root\"; }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(Paths.ROOT + \"/leaf\") String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints().get(0).pathTemplate()).isEqualTo("/root/leaf");
    }

    @Test
    void unresolvableConstantPathRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(External.UNKNOWN) String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.blindSpots()).anyMatch(b -> b.contains("could not be resolved to a literal"));
    }

    @Test
    void propertyPlaceholderPathRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"${app.base}/x\") String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints().get(0).pathTemplate()).contains("${app.base}");
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("property placeholder"));
    }

    @Test
    void ownerQualifiedConstantOnClassLevelIsResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Api.java"),
                "package demo; public interface Api { String V1 = \"/api/v1\"; }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController @RequestMapping(Api.V1) class C { @GetMapping(\"/things\") String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints().get(0).pathTemplate()).isEqualTo("/api/v1/things");
    }

    // ======================================================================================
    // Return-type wrapper unwrapping
    // ======================================================================================

    @Test
    void optionalWrapperUnwrapsToInnerType(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR + "import java.util.Optional;\n@RestController class C {"
                + " @GetMapping(\"/u\") Optional<User> g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("User");
        assertThat(m.schemas()).containsKey("User");
    }

    @Test
    void monoWrapperUnwrapsToInnerType(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR + "import reactor.core.publisher.Mono;\n@RestController class C {"
                + " @GetMapping(\"/u\") Mono<User> g(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("User");
    }

    @Test
    void completableFutureWrapperUnwraps(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR + "import java.util.concurrent.CompletableFuture;\n@RestController class C {"
                + " @GetMapping(\"/u\") CompletableFuture<User> g(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("User");
    }

    @Test
    void fluxWrapperBecomesArrayOfInner(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR + "import reactor.core.publisher.Flux;\n@RestController class C {"
                + " @GetMapping(\"/u\") Flux<User> g(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("User[]");
    }

    @Test
    void pageWrapperBecomesArrayOfInner(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR + "import org.springframework.data.domain.Page;\n@RestController class C {"
                + " @GetMapping(\"/u\") Page<User> g(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("User[]");
    }

    @Test
    void listWrapperBecomesArrayOfInner(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR + "import java.util.List;\n@RestController class C {"
                + " @GetMapping(\"/u\") List<User> g(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("User[]");
    }

    @Test
    void responseEntityOfListNestsUnwrapToArray(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.http.ResponseEntity;\nimport java.util.List;\n@RestController class C {"
                + " @GetMapping(\"/u\") ResponseEntity<List<User>> g(){return null;} }");

        var resp = extract(dir).endpoints().get(0).responses().get(0);
        assertThat(resp.schemaRef()).isEqualTo("User[]");
        assertThat(resp.origin()).isEqualTo("RESPONSE_ENTITY");
    }

    @Test
    void rawResponseEntityHasNoSchemaButIsStillABody(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "import org.springframework.http.ResponseEntity;\n@RestController class C {"
                + " @GetMapping(\"/u\") ResponseEntity g(){return null;} }");

        var resp = extract(dir).endpoints().get(0).responses().get(0);
        assertThat(resp.schemaRef()).isNull();
        assertThat(resp.origin()).isEqualTo("RESPONSE_ENTITY");
        assertThat(resp.statusCode()).isEqualTo(200);
    }

    @Test
    void voidReturnHasNoBodySchema(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @DeleteMapping(\"/u\") void g(){} }");

        var resp = extract(dir).endpoints().get(0).responses().get(0);
        assertThat(resp.schemaRef()).isNull();
        assertThat(resp.origin()).isEqualTo("RETURN");
    }

    @Test
    void bareEnvelopeWrapperWithoutGenericsIsLeftAsIs(@TempDir Path dir) throws Exception {
        // ENVELOPE_WRAPPERS only unwrap when parameterized — a raw ApiResponse stays its own schema name.
        Files.writeString(dir.resolve("ApiResponse.java"), "package demo; public class ApiResponse { public String ok; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") ApiResponse g(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("ApiResponse");
    }

    @Test
    void scalarReturnTypeMapsToOpenApiScalarSchemaRef(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "import java.util.UUID;\n@RestController class C {"
                + " @GetMapping(\"/u\") UUID g(){return null;} }");

        // a scalar return is still emitted as the body's schemaRef (the IR maps UUID to string/uuid elsewhere).
        assertThat(extract(dir).endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("UUID");
    }

    // ======================================================================================
    // Parameter binding: PATH / QUERY / HEADER / COOKIE incl. required + defaultValue
    // ======================================================================================

    @Test
    void requestParamRequiredFalseMakesParamOptional(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(@RequestParam(value=\"q\", required=false) String q){return null;} }");

        ParamModel q = param(extract(dir).endpoints().get(0), "q");
        assertThat(q.location()).isEqualTo(ParamLocation.QUERY);
        assertThat(q.required()).isFalse();
    }

    @Test
    void requestParamWithDefaultValueIsOptional(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(@RequestParam(defaultValue=\"10\") String size){return null;} }");

        ParamModel size = param(extract(dir).endpoints().get(0), "size");
        assertThat(size.required()).isFalse();
    }

    @Test
    void requestParamPlainIsRequiredByDefault(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(@RequestParam String q){return null;} }");

        assertThat(param(extract(dir).endpoints().get(0), "q").required()).isTrue();
    }

    @Test
    void requestParamNameMemberOverridesJavaName(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(@RequestParam(name=\"page_size\") int sz){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        ParamModel p = param(e, "page_size");
        assertThat(p.location()).isEqualTo(ParamLocation.QUERY);
        assertThat(p.type()).isEqualTo("integer");
        assertThat(p.format()).isEqualTo("int32");
    }

    @Test
    void pathVariableAlwaysRequired(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u/{id}\") String g(@PathVariable Long id){return null;} }");

        ParamModel id = param(extract(dir).endpoints().get(0), "id");
        assertThat(id.location()).isEqualTo(ParamLocation.PATH);
        assertThat(id.required()).isTrue();
        assertThat(id.type()).isEqualTo("integer");
        assertThat(id.format()).isEqualTo("int64");
    }

    @Test
    void requestHeaderWithRequiredFalseIsOptional(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(@RequestHeader(value=\"X-Trace\", required=false) String t){return null;} }");

        ParamModel t = param(extract(dir).endpoints().get(0), "X-Trace");
        assertThat(t.location()).isEqualTo(ParamLocation.HEADER);
        assertThat(t.required()).isFalse();
    }

    @Test
    void cookieValueWithDefaultIsOptionalAndNamed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(@CookieValue(value=\"sid\", defaultValue=\"none\") String sid){return null;} }");

        ParamModel c = param(extract(dir).endpoints().get(0), "sid");
        assertThat(c.location()).isEqualTo(ParamLocation.COOKIE);
        assertThat(c.required()).isFalse();
    }

    @Test
    void plainHeaderAndCookieDefaultRequired(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(@RequestHeader String auth, @CookieValue String tok){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(param(e, "auth").required()).isTrue();
        assertThat(param(e, "tok").required()).isTrue();
    }

    @Test
    void enumTypedQueryParamGetsEnumConstraint(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Sort.java"), "package demo; public enum Sort { ASC, DESC }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(@RequestParam Sort sort){return null;} }");

        ParamModel p = param(extract(dir).endpoints().get(0), "sort");
        assertThat(p.type()).isEqualTo("string");
        assertThat(p.constraints().enumValues()).containsExactly("ASC", "DESC");
    }

    @Test
    void unannotatedParameterIsNotBound(@TempDir Path dir) throws Exception {
        // A bare model-attribute / injected param (no binding annotation) is not surfaced as a param.
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/s\") String g(String notBound){return null;} }");

        assertThat(extract(dir).endpoints().get(0).params()).isEmpty();
    }

    @Test
    void sizeAndPatternConstraintsOnQueryParamAreCaptured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import jakarta.validation.constraints.*;\n@RestController class C {"
                + " @GetMapping(\"/s\") String g(@RequestParam @Size(min=2, max=8) @Pattern(regexp=\"[a-z]+\") String code){return null;} }");

        ParamModel code = param(extract(dir).endpoints().get(0), "code");
        assertThat(code.constraints().minLength()).isEqualTo(2);
        assertThat(code.constraints().maxLength()).isEqualTo(8);
        assertThat(code.constraints().pattern()).isEqualTo("[a-z]+");
    }

    // ======================================================================================
    // @RequestBody + @Valid
    // ======================================================================================

    @Test
    void requestBodyWithValidIsCapturedAndValidated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("CreateUser.java"), "package demo; public class CreateUser { public String name; }");
        Files.writeString(dir.resolve("C.java"), HDR + "import jakarta.validation.Valid;\n@RestController class C {"
                + " @PostMapping(\"/u\") String g(@Valid @RequestBody CreateUser body){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.requestBody()).isNotNull();
        assertThat(e.requestBody().schemaRef()).isEqualTo("CreateUser");
        assertThat(e.requestBody().validated()).isTrue();
        assertThat(extract(dir).schemas()).containsKey("CreateUser");
    }

    @Test
    void requestBodyWithoutValidIsNotValidated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dto.java"), "package demo; public class Dto { public String x; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @PostMapping(\"/u\") String g(@RequestBody Dto body){return null;} }");

        assertThat(extract(dir).endpoints().get(0).requestBody().validated()).isFalse();
    }

    // ======================================================================================
    // consumes / produces
    // ======================================================================================

    @Test
    void consumesAndProducesAreCaptured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @PostMapping(value=\"/u\", consumes=\"application/json\", produces={\"application/json\",\"application/xml\"})"
                + " String g(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.consumes()).containsExactly("application/json");
        assertThat(e.produces()).containsExactly("application/json", "application/xml");
    }

    @Test
    void singleMemberMappingHasNoProduces(@TempDir Path dir) throws Exception {
        // The single-member form is the path/value — it must NOT be read as produces.
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") String g(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.produces()).isEmpty();
        assertThat(e.consumes()).isEmpty();
    }

    // ======================================================================================
    // @ResponseStatus on the method
    // ======================================================================================

    @Test
    void responseStatusCreatedYields201(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.http.HttpStatus;\nimport org.springframework.web.bind.annotation.ResponseStatus;\n"
                + "@RestController class C { @ResponseStatus(HttpStatus.CREATED) @PostMapping(\"/u\") String g(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).statusCode()).isEqualTo(201);
    }

    @Test
    void responseStatusNoContentYields204(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.http.HttpStatus;\nimport org.springframework.web.bind.annotation.ResponseStatus;\n"
                + "@RestController class C { @ResponseStatus(HttpStatus.NO_CONTENT) @DeleteMapping(\"/u\") void g(){} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).statusCode()).isEqualTo(204);
    }

    @Test
    void responseStatusAcceptedYields202(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.http.HttpStatus;\nimport org.springframework.web.bind.annotation.ResponseStatus;\n"
                + "@RestController class C { @ResponseStatus(HttpStatus.ACCEPTED) @PostMapping(\"/u\") String g(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).responses().get(0).statusCode()).isEqualTo(202);
    }

    // ======================================================================================
    // DTO schemas: records, @JsonProperty rename, NotNull required, nested DTO transitive build
    // ======================================================================================

    @Test
    void recordDtoFieldsBecomeSchemaFields(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Money.java"),
                "package demo; public record Money(String currency, java.math.BigDecimal amount) {}");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/m\") Money g(){return null;} }");

        SchemaModel money = extract(dir).schemas().get("Money");
        assertThat(money.type()).isEqualTo("object");
        assertThat(money.fields()).extracting(FieldModel::jsonName).containsExactlyInAnyOrder("currency", "amount");
        assertThat(field(money, "amount").type()).isEqualTo("number");
    }

    @Test
    void jsonPropertyRenamesFieldInSchema(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dto.java"),
                "package demo; import com.fasterxml.jackson.annotation.JsonProperty;\n"
                        + "public class Dto { @JsonProperty(\"user_name\") public String userName; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/d\") Dto g(){return null;} }");

        SchemaModel dto = extract(dir).schemas().get("Dto");
        assertThat(dto.fields()).extracting(FieldModel::jsonName).containsExactly("user_name");
    }

    @Test
    void notNullAndNotBlankMakeFieldRequired(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dto.java"),
                "package demo; import jakarta.validation.constraints.*;\n"
                        + "public class Dto { @NotNull public String a; @NotBlank public String b; public String c; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/d\") Dto g(){return null;} }");

        SchemaModel dto = extract(dir).schemas().get("Dto");
        assertThat(field(dto, "a").required()).isTrue();
        assertThat(field(dto, "b").required()).isTrue();
        assertThat(field(dto, "c").required()).isFalse();
    }

    @Test
    void nestedDtoIsBuiltTransitively(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Address.java"), "package demo; public class Address { public String city; }");
        Files.writeString(dir.resolve("Person.java"),
                "package demo; public class Person { public String name; public Address address; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/p\") Person g(){return null;} }");

        ApiModel m = extract(dir);
        assertThat(m.schemas()).containsKeys("Person", "Address");
        assertThat(field(m.schemas().get("Person"), "address").refSchema()).isEqualTo("Address");
        assertThat(field(m.schemas().get("Address"), "city").type()).isEqualTo("string");
    }

    @Test
    void staticFieldsAreExcludedFromSchema(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dto.java"),
                "package demo; public class Dto { public static final String K = \"v\"; public String real; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/d\") Dto g(){return null;} }");

        SchemaModel dto = extract(dir).schemas().get("Dto");
        assertThat(dto.fields()).extracting(FieldModel::jsonName).containsExactly("real").doesNotContain("K");
    }

    @Test
    void recordComponentWithJsonIgnoreIsExcluded(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dto.java"),
                "package demo; import com.fasterxml.jackson.annotation.JsonIgnore;\n"
                        + "public record Dto(String shown, @JsonIgnore String hidden) {}");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/d\") Dto g(){return null;} }");

        SchemaModel dto = extract(dir).schemas().get("Dto");
        assertThat(dto.fields()).extracting(FieldModel::jsonName).containsExactly("shown");
    }

    @Test
    void emailConstraintBecomesFormatEmail(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dto.java"),
                "package demo; import jakarta.validation.constraints.Email;\n"
                        + "public class Dto { @Email public String addr; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/d\") Dto g(){return null;} }");

        SchemaModel dto = extract(dir).schemas().get("Dto");
        assertThat(field(dto, "addr").constraints().format()).isEqualTo("email");
    }

    @Test
    void minMaxNumericConstraintsAreCaptured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dto.java"),
                "package demo; import jakarta.validation.constraints.*;\n"
                        + "public class Dto { @Min(1) @Max(99) public int qty; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/d\") Dto g(){return null;} }");

        SchemaModel dto = extract(dir).schemas().get("Dto");
        assertThat(field(dto, "qty").constraints().minimum()).isEqualTo(1.0);
        assertThat(field(dto, "qty").constraints().maximum()).isEqualTo(99.0);
    }

    // ======================================================================================
    // @ControllerAdvice / @RestControllerAdvice error responses
    // ======================================================================================

    @Test
    void controllerAdviceResponseStatusIsAttachedToEndpoints(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.HttpStatus;\n"
                + "@RestControllerAdvice class Advice {"
                + " @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)"
                + " String bad(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 400 && "EXCEPTION_HANDLER".equals(r.origin()));
    }

    @Test
    void catchAllAdviceHandlerIsMarkedGlobal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.HttpStatus;\n"
                + "@ControllerAdvice class Advice {"
                + " @ExceptionHandler(Exception.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)"
                + " String boom(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 500 && "EXCEPTION_HANDLER_GLOBAL".equals(r.origin()));
    }

    @Test
    void adviceHandlerStatusFromHandledExceptionAnnotation(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("NotFoundEx.java"), HDR
                + "import org.springframework.http.HttpStatus;\n"
                + "@ResponseStatus(HttpStatus.NOT_FOUND) class NotFoundEx extends RuntimeException {}");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "@RestControllerAdvice class Advice {"
                + " @ExceptionHandler(NotFoundEx.class) String nf(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 404);
    }

    @Test
    void adviceHandlerStatusFromFrameworkExceptionMap(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "@RestControllerAdvice class Advice {"
                + " @ExceptionHandler(AccessDeniedException.class) String denied(){return null;}"
                + " @ExceptionHandler(MethodArgumentNotValidException.class) String invalid(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 403);   // AccessDeniedException
        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 400);   // MethodArgumentNotValidException
    }

    @Test
    void adviceHandlerStatusFromNewResponseEntityConstruction(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {"
                + " @ExceptionHandler(RuntimeException.class)"
                + " ResponseEntity<String> h(){ return new ResponseEntity<>(\"oops\", HttpStatus.CONFLICT); } }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 409);
    }

    @Test
    void adviceHandlerWithUnresolvableStatusRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "@RestControllerAdvice class Advice {"
                + " @ExceptionHandler(SomeUnknownThing.class) String h(){return null;} }");

        ApiModel m = extract(dir);
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("could not be resolved statically"));
    }

    @Test
    void adviceMediaTypeFromContentTypeIsCaptured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {"
                + " @ExceptionHandler(RuntimeException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)"
                + " ResponseEntity<String> h(){ return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(\"e\"); } }");

        Endpoint e = extract(dir).endpoints().get(0);
        var r400 = e.responses().stream().filter(r -> r.statusCode() == 400).findFirst().orElseThrow();
        assertThat(r400.mediaTypes()).contains("application/problem+json");
    }

    // ======================================================================================
    // One-hop service reachability: a status a called service throws is attached to the endpoint
    // ======================================================================================

    @Test
    void serviceThrownExceptionStatusIsReachableFromEndpoint(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("NotFoundEx.java"), HDR
                + "import org.springframework.http.HttpStatus;\n"
                + "@ResponseStatus(HttpStatus.NOT_FOUND) class NotFoundEx extends RuntimeException {}");
        Files.writeString(dir.resolve("Service.java"), "package demo;\n"
                + "class Service { String load(String id){ if (id == null) throw new NotFoundEx(); return id; } }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " private final Service service = new Service();"
                + " @GetMapping(\"/u/{id}\") String g(@PathVariable String id){ return service.load(id); } }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.responses())
                .anyMatch(r -> r.statusCode() == 404 && "EXCEPTION_HANDLER_REACHABLE".equals(r.origin()));
    }

    // ======================================================================================
    // Composed / meta annotations on the METHOD (verb from meta, path from usage)
    // ======================================================================================

    @Test
    void composedMethodMappingTakesVerbFromMetaAndPathFromUsage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("GetJson.java"), HDR
                + "@GetMapping(produces=\"application/json\") @interface GetJson { String[] value() default {}; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetJson(\"/things\") String g(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.method()).isEqualTo(HttpMethod.GET);
        assertThat(e.pathTemplate()).isEqualTo("/things");
    }

    @Test
    void composedMethodMappingUsesMetaPathWhenUsageHasNone(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Health.java"), HDR
                + "@GetMapping(\"/health\") @interface Health {}");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @Health String h(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.method()).isEqualTo(HttpMethod.GET);
        assertThat(e.pathTemplate()).isEqualTo("/health");
    }

    @Test
    void metaRequestMappingComposedAnnotationCarriesVerb(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("PostJson.java"), HDR
                + "@RequestMapping(method=RequestMethod.POST) @interface PostJson { String[] value() default {}; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @PostJson(\"/submit\") String s(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.method()).isEqualTo(HttpMethod.POST);
        assertThat(e.pathTemplate()).isEqualTo("/submit");
    }

    // ======================================================================================
    // Non-controller / no-mapping branches
    // ======================================================================================

    @Test
    void plainControllerWithoutResponseBodyIsNotARestController(@TempDir Path dir) throws Exception {
        // @Controller WITHOUT @ResponseBody is a view controller — not a REST endpoint source here.
        Files.writeString(dir.resolve("C.java"), HDR
                + "@Controller class C { @GetMapping(\"/page\") String page(){return \"view\";} }");

        assertThat(extract(dir).endpoints()).isEmpty();
    }

    @Test
    void controllerWithResponseBodyClassLevelIsRecognised(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@Controller @ResponseBody class C { @GetMapping(\"/api\") String a(){return null;} }");

        assertThat(extract(dir).endpoints()).extracting(Endpoint::pathTemplate).containsExactly("/api");
    }

    @Test
    void controllerMethodWithoutMappingProducesNoEndpoint(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/a\") String a(){return null;}"
                + " String helper(){return null;} }");   // no @*Mapping → skipped

        assertThat(extract(dir).endpoints()).hasSize(1);
    }

    @Test
    void customStereotypeMetaAnnotatedWithRestControllerIsRecognised(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ApiController.java"), HDR
                + "@RestController @interface ApiController {}");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@ApiController class C { @GetMapping(\"/x\") String x(){return null;} }");

        assertThat(extract(dir).endpoints()).extracting(Endpoint::pathTemplate).containsExactly("/x");
    }

    // ======================================================================================
    // Interface mapping blind spot
    // ======================================================================================

    @Test
    void controllerWithNoOwnMappingsButImplementsInterfaceRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Api.java"), HDR
                + "interface Api { @GetMapping(\"/spec\") String op(); }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C implements Api { public String op(){return null;} }");

        ApiModel m = extract(dir);
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("mappings declared on interfaces are not analysed"));
    }

    @Test
    void inheritedMappingFromGrandparentIsEmitted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Grand.java"), HDR
                + "abstract class Grand { @GetMapping(\"/grand\") String g(){return null;} }");
        Files.writeString(dir.resolve("Mid.java"), HDR
                + "abstract class Mid extends Grand { @GetMapping(\"/mid\") String mid(){return null;} }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C extends Mid { @GetMapping(\"/child\") String c(){return null;} }");

        assertThat(extract(dir).endpoints()).extracting(Endpoint::pathTemplate)
                .contains("/child", "/mid", "/grand");
    }

    // ======================================================================================
    // Annotation-level security (@Secured / @RolesAllowed) + class-level inheritance
    // ======================================================================================

    @Test
    void securedAnnotationIsCaptured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.security.access.annotation.Secured;\n"
                + "@RestController class C { @Secured(\"ROLE_ADMIN\") @GetMapping(\"/a\") String a(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.security().toString()).contains("ROLE_ADMIN");
    }

    @Test
    void classLevelSecurityAppliesToAllMethods(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.security.access.prepost.PreAuthorize;\n"
                + "@RestController @PreAuthorize(\"hasRole('USER')\") class C {"
                + " @GetMapping(\"/a\") String a(){return null;}"
                + " @GetMapping(\"/b\") String b(){return null;} }");

        ApiModel m = extract(dir);
        assertThat(m.endpoints()).allSatisfy(e -> assertThat(e.security().toString()).contains("USER"));
    }

    @Test
    void rolesAllowedAnnotationIsCaptured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import jakarta.annotation.security.RolesAllowed;\n"
                + "@RestController class C { @RolesAllowed(\"ADMIN\") @GetMapping(\"/a\") String a(){return null;} }");

        assertThat(extract(dir).endpoints().get(0).security().toString()).contains("ADMIN");
    }

    // ======================================================================================
    // SecurityFilterChain (centralized) resolution
    // ======================================================================================

    private static final String SEC_HDR = "package demo;\n"
            + "import org.springframework.context.annotation.Bean;\n"
            + "import org.springframework.security.config.annotation.web.builders.HttpSecurity;\n"
            + "import org.springframework.security.web.SecurityFilterChain;\n"
            + "import org.springframework.web.bind.annotation.*;\n";

    @Test
    void securityChainAuthenticatedRuleSecuresMatchingEndpoint(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/admin/users\") String u(){return null;} }");
        Files.writeString(dir.resolve("Sec.java"), SEC_HDR
                + "class Sec { @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {"
                + " http.authorizeHttpRequests(a -> a.requestMatchers(\"/admin/**\").authenticated().anyRequest().permitAll());"
                + " return null; } }");

        Endpoint e = ep(extract(dir), "GET /admin/users");
        assertThat(e.security()).contains("authenticated");
    }

    @Test
    void securityChainPermitAllLeavesEndpointUnsecured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/public/info\") String i(){return null;} }");
        Files.writeString(dir.resolve("Sec.java"), SEC_HDR
                + "class Sec { @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {"
                + " http.authorizeHttpRequests(a -> a.requestMatchers(\"/public/**\").permitAll().anyRequest().authenticated());"
                + " return null; } }");

        Endpoint e = ep(extract(dir), "GET /public/info");
        assertThat(e.security()).isEmpty();
        // a definitive resolution (no ambiguity) means no centralized blind spot is kept
        assertThat(extract(dir).blindSpots()).noneMatch(b -> b.contains("centralized"));
    }

    @Test
    void securityChainRoleRuleProducesHasRoleExpression(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/mgr/area\") String a(){return null;} }");
        Files.writeString(dir.resolve("Sec.java"), SEC_HDR
                + "class Sec { @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {"
                + " http.authorizeHttpRequests(a -> a.requestMatchers(\"/mgr/**\").hasRole(\"MANAGER\").anyRequest().permitAll());"
                + " return null; } }");

        Endpoint e = ep(extract(dir), "GET /mgr/area");
        assertThat(e.security()).containsExactly("hasRole('MANAGER')");
    }

    @Test
    void securityChainMethodScopedMatcherOnlyAppliesToThatVerb(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @PostMapping(\"/items\") String create(){return null;}"
                + " @GetMapping(\"/items\") String list(){return null;} }");
        Files.writeString(dir.resolve("Sec.java"), SEC_HDR
                + "import org.springframework.http.HttpMethod;\n"
                + "class Sec { @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {"
                + " http.authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, \"/items\").authenticated()"
                + ".anyRequest().permitAll());"
                + " return null; } }");

        ApiModel m = extract(dir);
        assertThat(ep(m, "POST /items").security()).contains("authenticated");
        assertThat(ep(m, "GET /items").security()).isEmpty();
    }

    @Test
    void multipleSecurityChainsKeepCentralizedBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/x\") String x(){return null;} }");
        Files.writeString(dir.resolve("Sec.java"), SEC_HDR
                + "class Sec {"
                + " @Bean SecurityFilterChain a(HttpSecurity http) throws Exception { return null; }"
                + " @Bean SecurityFilterChain b(HttpSecurity http) throws Exception { return null; } }");

        ApiModel m = extract(dir);
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("Authorization appears centralized"));
    }

    @Test
    void securityConfigWithoutParseableChainKeepsBlindSpot(@TempDir Path dir) throws Exception {
        // A WebSecurityConfigurerAdapter usage (no SecurityFilterChain bean) → centralized blind spot, no resolution.
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/x\") String x(){return null;} }");
        Files.writeString(dir.resolve("Sec.java"), "package demo;\n"
                + "abstract class Sec extends WebSecurityConfigurerAdapter {}");

        ApiModel m = extract(dir);
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("Authorization appears centralized"));
    }

    @Test
    void annotationSecurityWinsOverChainResolution(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.security.access.prepost.PreAuthorize;\n"
                + "@RestController class C { @PreAuthorize(\"hasRole('OWNER')\") @GetMapping(\"/admin/x\") String x(){return null;} }");
        Files.writeString(dir.resolve("Sec.java"), SEC_HDR
                + "class Sec { @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {"
                + " http.authorizeHttpRequests(a -> a.requestMatchers(\"/admin/**\").authenticated().anyRequest().permitAll());"
                + " return null; } }");

        Endpoint e = ep(extract(dir), "GET /admin/x");
        // already secured by annotation — chain is moot, the annotation's role is preserved
        assertThat(e.security().toString()).contains("OWNER");
        assertThat(e.security().toString()).doesNotContain("authenticated");
    }

    // ======================================================================================
    // Parse failure blind spot
    // ======================================================================================

    @Test
    void unparseableSourceFileRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Broken.java"), "package demo; this is not valid java @#$%");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/ok\") String ok(){return null;} }");

        ApiModel m = extract(dir);
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("Could not parse"));
        assertThat(m.endpoints()).extracting(Endpoint::pathTemplate).contains("/ok");   // valid file still analysed
    }

    @Test
    void testFolderSourcesAreSkipped(@TempDir Path dir) throws Exception {
        Path test = dir.resolve("test");
        Files.createDirectories(test);
        Files.writeString(test.resolve("TestCtrl.java"), HDR
                + "@RestController class TestCtrl { @GetMapping(\"/from-test\") String t(){return null;} }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/main\") String m(){return null;} }");

        ApiModel m = extract(dir);
        assertThat(m.endpoints()).extracting(Endpoint::pathTemplate)
                .contains("/main").doesNotContain("/from-test");
    }

    @Test
    void controllerClassNameIsRecordedOnEndpoint(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("WidgetController.java"), HDR
                + "@RestController class WidgetController { @GetMapping(\"/w\") String w(){return null;} }");

        Endpoint e = extract(dir).endpoints().get(0);
        assertThat(e.controllerClass()).isEqualTo("WidgetController");
        assertThat(e.operationId()).isEqualTo("w");
    }

    @Test
    void unresolvedRequestBodyTypeRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @PostMapping(\"/x\") String x(@RequestBody ExternalDto body){return null;} }");

        ApiModel m = extract(dir);
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("ExternalDto") && b.contains("could not be resolved"));
    }

    @Test
    void emptyDirectoryProducesEmptyModelWithoutCrashing(@TempDir Path dir) throws Exception {
        ApiModel m = extract(dir);
        assertThat(m.endpoints()).isEmpty();
        assertThat(m.schemas()).isEmpty();
        assertThat(m.source()).isEqualTo("code");
    }

    @Test
    void enumValuedConstraintIsNotTreatedAsRefSchema(@TempDir Path dir) throws Exception {
        // Sanity guard: an enum field never produces a phantom object ref and the values land on constraints.
        Files.writeString(dir.resolve("Color.java"), "package demo; public enum Color { RED, GREEN, BLUE }");
        Files.writeString(dir.resolve("Paint.java"),
                "package demo; public class Paint { public Color color; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/p\") Paint g(){return null;} }");

        SchemaModel paint = extract(dir).schemas().get("Paint");
        FieldModel color = field(paint, "color");
        assertThat(color.type()).isEqualTo("string");
        assertThat(color.refSchema()).isNull();
        assertThat(color.constraints().enumValues()).containsExactly("RED", "GREEN", "BLUE");
    }

    @Test
    void responseEntityMultipleStatusesPicksSuccessBodyForLowest2xx(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.http.ResponseEntity;\n@RestController class C {"
                + " @PostMapping(\"/u\") ResponseEntity<User> g(boolean exists){"
                + "   if (exists) return ResponseEntity.ok().body(null);"
                + "   return ResponseEntity.created(null).body(null); } }");

        Endpoint e = extract(dir).endpoints().get(0);
        // both statuses emitted; only the lowest 2xx success (200) carries the body schema
        List<Integer> codes = e.responses().stream().map(r -> r.statusCode()).toList();
        assertThat(codes).contains(200, 201);
        var ok = e.responses().stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
        var created = e.responses().stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow();
        assertThat(ok.schemaRef()).isEqualTo("User");
        assertThat(created.schemaRef()).isNull();
    }
}