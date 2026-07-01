package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ResponseEntity status resolution must analyse the ACTUALLY-RETURNED (tail) value through the fluent Optional/Stream
 * chain — not a blanket subtree scan. These four cases were confirmed as regressions by an adversarial refutation of an
 * earlier coarse fix: a ResponseEntity factory / local helper sitting in a NON-returned position (an {@code .orElse(...)}
 * fallback beside an opaque {@code .map}, a {@code log(errorResponse())} side-call argument, or a wrong-arity overload)
 * must neither be harvested as a phantom status nor mask a genuine opaque-delegation blind spot.
 */
class TailStatusResolutionTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static List<Integer> statusCodes(ApiModel m) {
        return m.endpoints().get(0).responses().stream().map(r -> r.statusCode()).toList();
    }

    // A ResponseEntity helper evaluated only to produce an argument to a void side-call (log(errorResponse())) is
    // discarded — it must NOT be harvested as a phantom status. Every real return path yields 201 via wrap(...).
    @Test
    void sideCallArgumentHelperIsNotHarvestedAsPhantomStatus(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            import java.util.Optional;
            @RestController class C {
                @PostMapping("/y") public ResponseEntity<String> handle(@RequestParam String id) {
                    return service(id)
                        .map(v -> wrap(v))
                        .orElseGet(() -> { log(errorResponse()); return wrap("default"); });
                }
                private Optional<String> service(String id) { return Optional.of(id); }
                private ResponseEntity<String> wrap(String v) { return ResponseEntity.status(201).body(v); }
                private ResponseEntity<String> errorResponse() { return ResponseEntity.status(400).build(); }
                private void log(ResponseEntity<String> r) { }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(201).doesNotContain(400);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // An opaque .map(factory.build) delegation with a resolvable .orElse(ResponseEntity.ok(x)) fallback: the fallback's
    // 200 IS a real (empty-branch) status, but the opaque map delegation MUST still surface a blind spot — the presence
    // of a resolvable factory in a sibling tail position must not suppress it.
    @Test
    void resolvableOrElseFallbackBesideOpaqueMapKeepsBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            import java.util.Optional;
            @RestController class C {
                private Object factory;
                @GetMapping("/u/{id}") public ResponseEntity<String> get(@PathVariable String id) {
                    return find(id)
                        .map(u -> factory.build(u))
                        .orElse(ResponseEntity.ok(id));
                }
                private Optional<String> find(String id) { return Optional.empty(); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200);
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }

    // A resolvable local helper appearing only as an ARGUMENT to an opaque delegation (factory.wrap(errorBody())) is not
    // the delegation target — it must not suppress the blind spot, and its 500 must not be harvested.
    @Test
    void helperInArgumentPositionDoesNotSuppressOpaqueDelegation(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                private Object factory;
                @GetMapping("/w/{id}") public ResponseEntity<String> get(@PathVariable String id) {
                    if (id.isEmpty()) { return factory.wrap(errorBody()); }
                    return ResponseEntity.ok(id);
                }
                private ResponseEntity<String> errorBody() { return ResponseEntity.internalServerError().body("boom"); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200).doesNotContain(500);
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }

    // A TYPE-qualified factory method reference (`.map(ResponseEntity::ok)`, not `this::`) resolves to its fixed status
    // (200) — the idiomatic Optional<T> -> Optional<ResponseEntity<T>> -> ResponseEntity<T> shape — with no blind spot.
    @Test
    void typeQualifiedFactoryMethodReferenceResolves(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            import java.util.Optional;
            @RestController class C {
                private Repo svc;
                @GetMapping("/u/{id}") public ResponseEntity<String> get(@PathVariable String id) {
                    return svc.find(id).map(ResponseEntity::ok).orElseThrow(() -> new RuntimeException());
                }
                interface Repo { Optional<String> find(String id); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // A ResponseEntity factory built inside an orElseThrow THROWING-SUPPLIER (the payload of a thrown exception) is never
    // returned — its status must NOT be harvested as a phantom. The endpoint returns only 200 (the .map hit).
    @Test
    void factoryInsideOrElseThrowSupplierIsNotHarvested(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            import java.util.Optional;
            @RestController class C {
                private Repo repo;
                @GetMapping("/u/{id}") public ResponseEntity<String> get(@PathVariable String id) {
                    return repo.find(id)
                        .map(u -> ResponseEntity.ok(u))
                        .orElseThrow(() -> new ApiException(ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
                }
                interface Repo { Optional<String> find(String id); }
                static class ApiException extends RuntimeException { ApiException(Object p) {} }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200).doesNotContain(404);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // A local response helper NAMED like a fluent operator (`get`, `filter`, …) must be classified as a helper, not
    // mistaken for Optional.get()/Stream.filter() — an unqualified call has no Optional/Stream receiver.
    @Test
    void localHelperNamedLikeFluentOperatorResolves(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                @GetMapping("/u/{id}") public ResponseEntity<String> getUser(@PathVariable String id) {
                    return get(id);
                }
                private ResponseEntity<String> get(String id) { return ResponseEntity.status(200).body(id); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // A cast/parenthesized OPAQUE delegation (`return (ResponseEntity<X>) factory.build(id);`) must behave exactly like
    // the un-cast form: fire the honest blind spot and NOT fabricate a phantom 200.
    @Test
    void castedOpaqueDelegationStillFiresBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                private Factory factory;
                @GetMapping("/u/{id}") public ResponseEntity<String> get(@PathVariable String id) {
                    return (ResponseEntity<String>) factory.build(id);
                }
                static class Factory { ResponseEntity build(String id) { return ResponseEntity.ok(id); } }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
        assertThat(statusCodes(m)).doesNotContain(200);
    }

    // A one-hop helper status (201 via .map(this::created)) and a direct status (404 via the .orElse literal) coexist —
    // both must be recorded. The helper harvest must be UNIONed with the direct harvest, not gated behind "direct empty".
    @Test
    void directStatusBesideHelperDelegatedStatusKeepsBoth(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            import java.util.Optional;
            @RestController class C {
                @GetMapping("/i/{id}") public ResponseEntity<String> ep(@PathVariable String id) {
                    return lookup(id).map(this::created).orElse(ResponseEntity.notFound().build());
                }
                private Optional<String> lookup(String id) { return Optional.ofNullable(id); }
                private ResponseEntity<String> created(String s) { return ResponseEntity.status(201).body(s); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(201).contains(404);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // A ResponseEntity held in an instance field, assigned in the handler and returned via `return this.field;`
    // (a FieldAccessExpr, not a NameExpr) — its status must be harvested from the this.field write.
    @Test
    void returnedThisFieldStatusIsHarvested(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                private ResponseEntity<String> cached;
                @GetMapping("/job") public ResponseEntity<String> submit() {
                    this.cached = ResponseEntity.status(HttpStatus.ACCEPTED).body("queued");
                    return this.cached;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(202);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // A dynamic ResponseEntity.status(var) that is NEVER returned (only passed to a side-call) must NOT fire the blind
    // spot — the check is return-scoped; the returned value here is a determinate 200.
    @Test
    void dynamicStatusInNonReturnedPositionIsNotABlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                private int fallbackCode = 503;
                @GetMapping("/u/{id}") public ResponseEntity<String> get(@PathVariable String id) {
                    log(ResponseEntity.status(fallbackCode).build());
                    return ResponseEntity.ok(id);
                }
                private void log(ResponseEntity<String> r) { }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // The ubiquitous imperative conditional-status handler: a mutable ResponseEntity local assigned by readable
    // factories on every branch, then returned. Its status set {200, 202} must resolve with NO blind spot — the returned
    // local is only a blind spot when a write is genuinely opaque.
    @Test
    void mutableLocalReassignedByFactoriesResolvesWithoutBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                @PutMapping("/d/{id}") public ResponseEntity<String> update(@PathVariable Long id, @RequestParam boolean async) {
                    ResponseEntity<String> response = ResponseEntity.ok("v");
                    if (async) { response = ResponseEntity.accepted().body("q"); }
                    return response;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200).contains(202);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // The if/else-assigned (declared-without-initializer) variant of the same idiom: {200, 201}, no blind spot.
    @Test
    void ifElseAssignedLocalResolvesWithoutBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                @GetMapping("/x") public ResponseEntity<String> g(@RequestParam boolean flag) {
                    ResponseEntity<String> r;
                    if (flag) { r = ResponseEntity.ok("x"); }
                    else { r = ResponseEntity.status(HttpStatus.CREATED).body("y"); }
                    return r;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200).contains(201);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // (guard) A returned local with a MIX of a readable write (200) and an OPAQUE delegation write (factory.build) must
    // still fire the blind spot for the unreadable branch — the readable status alone must not suppress it.
    @Test
    void localWrittenByOpaqueDelegationStillBlindSpots(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                private Object factory;
                @GetMapping("/x") public ResponseEntity<String> g(@RequestParam boolean cond) {
                    ResponseEntity<String> r = ResponseEntity.ok("x");
                    if (cond) { r = factory.build(); }
                    return r;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200);
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }

    // An opaque write to a same-named FIELD (this.response) must NOT be attributed to a returned LOCAL that shadows it
    // and is written solely by readable factories — the no-shadow guard applies to the blind-spot check too.
    @Test
    void opaqueThisFieldWriteShadowedByReadableLocalDoesNotBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                private ResponseEntity<String> response;
                private Svc service;
                @GetMapping("/i/{id}") public ResponseEntity<String> get(@PathVariable Long id) {
                    ResponseEntity<String> response = ResponseEntity.ok("x");
                    if (id < 0) { response = ResponseEntity.badRequest().build(); }
                    this.response = service.buildCached(id);
                    return response;
                }
                interface Svc { ResponseEntity<String> buildCached(Long id); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200).contains(400);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // A returned local bound to a helper-delegating chain (`resp = svc.find(id).map(this::toCreated).orElseThrow();
    // return resp;`) must resolve the helper's 201 through the local's write — not lose it to a phantom 200.
    @Test
    void returnedLocalWrittenByHelperChainResolvesStatus(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            import java.util.Optional;
            @RestController class C {
                private Svc service;
                @GetMapping("/w/{id}") public ResponseEntity<String> get(@PathVariable Long id) {
                    ResponseEntity<String> resp = service.find(id).map(this::toCreated).orElseThrow();
                    return resp;
                }
                private ResponseEntity<String> toCreated(String w) { return ResponseEntity.status(201).body(w); }
                interface Svc { Optional<String> find(Long id); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(201).doesNotContain(200);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // A ResponseEntity local name legally reused across DISJOINT block scopes — a throwaway 503 in its own block, then
    // the real returned 200 — must not fabricate a phantom 503: only a declarator whose scope reaches the return counts.
    @Test
    void disjointScopeSameNameLocalDoesNotFabricatePhantomStatus(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                @GetMapping("/r/{id}") public ResponseEntity<String> get(@PathVariable String id) {
                    {
                        ResponseEntity<String> probe = ResponseEntity.status(503).build();
                        healthLog(probe);
                    }
                    ResponseEntity<String> probe = ResponseEntity.ok(id);
                    return probe;
                }
                private void healthLog(ResponseEntity<String> r) { }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).contains(200).doesNotContain(503);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // A this::render method reference binds exactly the single-arg overload (Function<T,R>). The harvest must match by
    // arity: the never-bound render(String, boolean) overload's resolvable 418 must NOT be attributed to the endpoint,
    // and because the bound render(String) has a dynamic status, the honest blind spot must fire.
    @Test
    void methodReferenceHelperMatchesOnlyBoundArityOverload(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            import java.util.Optional;
            @RestController class C {
                private Repo svc;
                @GetMapping("/x") public ResponseEntity<String> get(@RequestParam String id) {
                    return svc.find(id).map(this::render).orElseThrow(() -> new RuntimeException());
                }
                private ResponseEntity<String> render(String u) {
                    HttpStatus st = pick(u);
                    return ResponseEntity.status(st).body(u);
                }
                private ResponseEntity<String> render(String u, boolean f) {
                    return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).body(u);
                }
                private HttpStatus pick(String u) { return HttpStatus.OK; }
                interface Repo { Optional<String> find(String id); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(statusCodes(m)).doesNotContain(418);
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }
}
