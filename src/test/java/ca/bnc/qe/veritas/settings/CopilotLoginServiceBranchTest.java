package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.llm.copilot.CopilotTokens.DeviceCode;
import org.junit.jupiter.api.Test;

/**
 * Branch-maximising companion to {@link CopilotLoginServiceTest}. Drives the device-flow login service entirely
 * with a mocked {@link CopilotAuthService} and exercises the branches the happy-path/expired/unknown tests leave
 * uncovered:
 *  - {@code start()} when the flow fails before ever emitting a device code -> {@code IllegalStateException} +
 *    the failed session is purged from the registry;
 *  - {@code runFlow()} ERROR mapping for a non-"expired" message AND for a {@code null} exception message;
 *  - {@code connected()} delegating both true/false to {@code verifyConnected()};
 *  - {@code isAuthenticated()} returning false;
 *  - single-flight: a second {@code start()} for the same principal cancels the in-flight PENDING session;
 *  - the {@code @Scheduled} reaper removing only sessions older than the expiry cutoff and cancelling their task,
 *    while leaving fresh sessions (and tasks) alone -- plus the {@code task == null} reaper branch;
 *  - {@code shutdown()} (@PreDestroy) being idempotent/safe.
 *
 * Private {@code sessions} map / {@code Session} internals are reached by reflection so the reaper and
 * cancel-in-flight branches can be driven deterministically without sleeping on real wall-clock expiry.
 */
class CopilotLoginServiceBranchTest {

    private final CurrentUser local = () -> "local";

    private void awaitState(CopilotLoginService svc, String id, String expected) throws InterruptedException {
        for (int i = 0; i < 80 && !expected.equals(svc.status(id).state()); i++) {
            Thread.sleep(20);
        }
        assertThat(svc.status(id).state()).isEqualTo(expected);
    }

    // ---- reflection helpers over the private Session registry --------------------------------------------

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Object> sessions(CopilotLoginService svc) throws Exception {
        Field f = CopilotLoginService.class.getDeclaredField("sessions");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Object>) f.get(svc);
    }

    private Object newSession(String id) throws Exception {
        Class<?> sessionClass = Class.forName("ca.bnc.qe.veritas.settings.CopilotLoginService$Session");
        Constructor<?> ctor = sessionClass.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private String getState(Object session) throws Exception {
        Field f = session.getClass().getDeclaredField("state");
        f.setAccessible(true);
        return (String) f.get(session);
    }

    // ---- start(): flow fails before emitting a device code ----------------------------------------------

    @Test
    void startThrowsAndPurgesSessionWhenDeviceCodeNeverArrives() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        // deviceFlow blows up WITHOUT ever invoking onPrompt -> s.device stays null -> start() must throw.
        when(auth.deviceFlow(any())).thenThrow(new IllegalStateException("no connectivity"));
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        assertThatThrownBy(svc::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not start the GitHub device flow");

        // the half-started session was removed so it cannot be polled
        assertThat(sessions(svc)).isEmpty();
    }

    // ---- runFlow(): ERROR mapping (non-expired message) -------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void nonExpiredFailureAfterDeviceCodeSurfacesAsError() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        when(auth.deviceFlow(any())).thenAnswer(inv -> {
            ((Consumer<DeviceCode>) inv.getArgument(0))
                    .accept(new DeviceCode("dc", "AAAA-0001", "https://github.com/login/device", 5, 900));
            throw new IllegalStateException("OAuth error: access_denied");   // no "expired" -> ERROR
        });
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        CopilotLoginStart start = svc.start();
        assertThat(start.userCode()).isEqualTo("AAAA-0001");
        assertThat(start.expiresIn()).isEqualTo(900L);
        awaitState(svc, start.id(), "ERROR");
        assertThat(svc.status(start.id()).message()).isEqualTo("Sign-in did not complete. Please try again.");
    }

    // ---- runFlow(): ERROR mapping when the exception message is null ------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void nullMessageFailureFallsIntoErrorNotExpired() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        when(auth.deviceFlow(any())).thenAnswer(inv -> {
            ((Consumer<DeviceCode>) inv.getArgument(0))
                    .accept(new DeviceCode("dc", "BBBB-0002", "https://github.com/login/device", 5, 900));
            throw new RuntimeException();   // getMessage() == null -> msg="" -> ERROR branch
        });
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        CopilotLoginStart start = svc.start();
        awaitState(svc, start.id(), "ERROR");
        assertThat(svc.status(start.id()).message()).isEqualTo("Sign-in did not complete. Please try again.");
    }

    // ---- connected() / isAuthenticated() delegation -----------------------------------------------------

    @Test
    void connectedDelegatesToVerifyConnectedBothWays() {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        when(auth.verifyConnected()).thenReturn(true);
        assertThat(svc.connected()).isTrue();

        when(auth.verifyConnected()).thenReturn(false);
        assertThat(svc.connected()).isFalse();
        verify(auth, org.mockito.Mockito.times(2)).verifyConnected();
    }

    @Test
    void isAuthenticatedReturnsFalseWhenAuthSaysSo() {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        when(auth.isAuthenticated()).thenReturn(false);
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        assertThat(svc.isAuthenticated()).isFalse();
        verify(auth).isAuthenticated();
    }

    // ---- single-flight: second start() cancels the in-flight PENDING session ----------------------------

    @SuppressWarnings("unchecked")
    @Test
    void secondStartForSamePrincipalCancelsTheInFlightPendingSession() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        AtomicInteger flowCalls = new AtomicInteger();
        // first call: emit a device code then block forever (stays PENDING) so it is "in flight" when the
        // second start() arrives. second call: emit a code then return -> AUTHORIZED.
        when(auth.deviceFlow(any())).thenAnswer(inv -> {
            int n = flowCalls.incrementAndGet();
            ((Consumer<DeviceCode>) inv.getArgument(0))
                    .accept(new DeviceCode("dc" + n, "CODE-" + n, "https://github.com/login/device", 5, 900));
            if (n == 1) {
                // block until interrupted by the single-flight cancel
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        });
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        CopilotLoginStart first = svc.start();
        assertThat(first.userCode()).isEqualTo("CODE-1");
        // the first session is registered + PENDING
        awaitState(svc, first.id(), "PENDING");

        CopilotLoginStart second = svc.start();
        assertThat(second.userCode()).isEqualTo("CODE-2");
        assertThat(second.id()).isNotEqualTo(first.id());

        // the first (in-flight) session was cancelled + removed by single-flight -> polling it now fails
        for (int i = 0; i < 80; i++) {
            try {
                svc.status(first.id());
            } catch (IllegalArgumentException expected) {
                break;
            }
            Thread.sleep(20);
        }
        assertThatThrownBy(() -> svc.status(first.id())).isInstanceOf(IllegalArgumentException.class);
        // the second one progresses to AUTHORIZED
        awaitState(svc, second.id(), "AUTHORIZED");
        assertThat(sessions(svc).keySet()).containsExactly(second.id());
    }

    // ---- @Scheduled reaper: removes only expired sessions, cancels their task ---------------------------

    @Test
    void reapExpiredRemovesOldSessionsAndCancelsTaskButKeepsFresh() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        CopilotLoginService svc = new CopilotLoginService(auth, local);
        ConcurrentHashMap<String, Object> registry = sessions(svc);

        // an expired session (startedAt well past the 900s cutoff) with a live task -> must be cancelled+removed
        Object expired = newSession("local:expired");
        setField(expired, "startedAt", Instant.now().minusSeconds(5_000));
        Future<?> expiredTask = mock(Future.class);
        setField(expired, "task", expiredTask);
        registry.put("local:expired", expired);

        // an expired session with a NULL task -> exercises the task==null reaper branch (no NPE, still removed)
        Object expiredNoTask = newSession("local:expired-notask");
        setField(expiredNoTask, "startedAt", Instant.now().minusSeconds(5_000));
        registry.put("local:expired-notask", expiredNoTask);

        // a fresh session (startedAt now) with a task -> must be left untouched, task NOT cancelled
        Object fresh = newSession("local:fresh");
        Future<?> freshTask = mock(Future.class);
        setField(fresh, "task", freshTask);
        registry.put("local:fresh", fresh);

        // invoke the package-private @Scheduled method directly
        svc.reapExpired();

        assertThat(registry).containsOnlyKeys("local:fresh");
        verify(expiredTask).cancel(true);
        verify(freshTask, never()).cancel(any(Boolean.class));
    }

    @Test
    void reapExpiredIsNoOpWhenAllSessionsAreFresh() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        CopilotLoginService svc = new CopilotLoginService(auth, local);
        ConcurrentHashMap<String, Object> registry = sessions(svc);

        Object fresh = newSession("local:fresh");
        registry.put("local:fresh", fresh);

        svc.reapExpired();

        assertThat(registry).containsOnlyKeys("local:fresh");
        // still PENDING (untouched)
        assertThat(getState(fresh)).isEqualTo("PENDING");
    }

    // ---- cancelInFlight skips non-PENDING / other-principal sessions ------------------------------------

    @Test
    void startDoesNotCancelAuthorizedOrOtherPrincipalSessions() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        // make the new start() flow fail fast (no device code) so start() throws after running cancelInFlight;
        // we only care that cancelInFlight left the unrelated sessions intact.
        when(auth.deviceFlow(any())).thenThrow(new IllegalStateException("boom"));
        CopilotLoginService svc = new CopilotLoginService(auth, local);
        ConcurrentHashMap<String, Object> registry = sessions(svc);

        // same principal but already AUTHORIZED -> not PENDING -> must survive cancelInFlight
        Object authorized = newSession("local:done");
        setField(authorized, "state", "AUTHORIZED");
        Future<?> doneTask = mock(Future.class);
        setField(authorized, "task", doneTask);
        registry.put("local:done", authorized);

        // a DIFFERENT principal, still PENDING -> must survive (prefix does not match "local:")
        Object otherPrincipal = newSession("other:pending");
        Future<?> otherTask = mock(Future.class);
        setField(otherPrincipal, "task", otherTask);
        registry.put("other:pending", otherPrincipal);

        assertThatThrownBy(svc::start).isInstanceOf(IllegalStateException.class);

        // neither unrelated session was cancelled or removed
        assertThat(registry).containsKeys("local:done", "other:pending");
        verify(doneTask, never()).cancel(any(Boolean.class));
        verify(otherTask, never()).cancel(any(Boolean.class));
    }

    // ---- shutdown(): @PreDestroy is safe / idempotent ---------------------------------------------------

    @Test
    void shutdownStopsExecutorAndIsIdempotent() {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        svc.shutdown();
        // a second shutdown must not throw
        svc.shutdown();
        // signOut still delegates after shutdown (the executor stopping doesn't affect this path)
        svc.signOut();
        verify(auth).signOut();
    }

    // ---- status() on a known session returns the live state/message -------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void statusReturnsLiveStateAndMessageForKnownSession() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        when(auth.deviceFlow(any())).thenAnswer(inv -> {
            ((Consumer<DeviceCode>) inv.getArgument(0))
                    .accept(new DeviceCode("dc", "DDDD-0004", "https://github.com/login/device", 5, 900));
            return null;
        });
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        CopilotLoginStart start = svc.start();
        // before the worker finishes the state may still be PENDING with the waiting message; either way the
        // returned status is non-null and carries a recognised state.
        CopilotLoginStatus st = svc.status(start.id());
        assertThat(st.state()).isIn("PENDING", "AUTHORIZED");
        assertThat(st.message()).isNotBlank();
        awaitState(svc, start.id(), "AUTHORIZED");
        assertThat(svc.status(start.id()).message()).isEqualTo("Signed in to GitHub Copilot.");
        // deviceFlow was actually invoked on the worker
        verify(auth, timeout(2_000)).deviceFlow(any());
    }
}
