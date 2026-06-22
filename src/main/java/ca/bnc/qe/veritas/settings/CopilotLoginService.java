package ca.bnc.qe.veritas.settings;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.llm.copilot.CopilotTokens.DeviceCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Drives the GitHub→Copilot device flow from the UI without blocking the request: {@code start()} kicks the
 * (blocking) {@link CopilotAuthService#deviceFlow} onto a background thread and returns as soon as the user
 * code is available; the UI then polls {@code status(id)} until AUTHORIZED/EXPIRED/ERROR. Sessions are keyed by
 * principal (single-flight per user), reaped on expiry, and the executor is shut down on destroy.
 */
@Service
@Slf4j
public class CopilotLoginService {

    private static final long EXPIRY_SECONDS = 900;

    private final CopilotAuthService auth;
    private final CurrentUser currentUser;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "copilot-login");
        t.setDaemon(true);
        return t;
    });

    public CopilotLoginService(CopilotAuthService auth, CurrentUser currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    public CopilotLoginStart start() {
        String principal = currentUser.principalId();
        cancelInFlight(principal);   // single-flight per principal
        Session s = new Session(principal + ":" + UUID.randomUUID());
        sessions.put(s.id, s);
        s.task = executor.submit(() -> runFlow(s));
        try {
            s.ready.await(30, TimeUnit.SECONDS);   // wait until the device code is available (or the flow failed)
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (s.device == null) {
            sessions.remove(s.id);
            throw new IllegalStateException("Could not start the GitHub device flow — check connectivity.");
        }
        return new CopilotLoginStart(s.id, s.device.userCode(), s.device.verificationUri(), s.device.expiresIn());
    }

    private void runFlow(Session s) {
        try {
            auth.deviceFlow(device -> {
                s.device = device;
                s.ready.countDown();
            });
            s.state = "AUTHORIZED";
            s.message = "Signed in to GitHub Copilot.";
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.toLowerCase(java.util.Locale.ROOT).contains("expired")) {
                s.state = "EXPIRED";
                s.message = "The device code expired — start the sign-in again.";
            } else {
                s.state = "ERROR";
                s.message = "Sign-in did not complete. Please try again.";
            }
        } finally {
            s.ready.countDown();   // release start() even if the flow failed before emitting a device code
        }
    }

    public CopilotLoginStatus status(String id) {
        Session s = sessions.get(id);
        if (s == null) {
            throw new IllegalArgumentException("Unknown or expired sign-in session.");
        }
        return new CopilotLoginStatus(s.state, s.message);
    }

    public boolean isAuthenticated() {
        return auth.isAuthenticated();
    }

    public void signOut() {
        auth.signOut();
    }

    private void cancelInFlight(String principal) {
        sessions.forEach((id, s) -> {
            if (id.startsWith(principal + ":") && "PENDING".equals(s.state)) {
                if (s.task != null) {
                    s.task.cancel(true);
                }
                sessions.remove(id);
            }
        });
    }

    @Scheduled(fixedDelay = 60_000)
    void reapExpired() {
        Instant cutoff = Instant.now().minusSeconds(EXPIRY_SECONDS);
        sessions.forEach((id, s) -> {
            if (s.startedAt.isBefore(cutoff)) {
                if (s.task != null) {
                    s.task.cancel(true);
                }
                sessions.remove(id);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class Session {
        final String id;
        final CountDownLatch ready = new CountDownLatch(1);
        final Instant startedAt = Instant.now();
        volatile String state = "PENDING";
        volatile String message = "Waiting for you to authorize in GitHub…";
        volatile DeviceCode device;
        volatile Future<?> task;

        Session(String id) {
            this.id = id;
        }
    }
}
