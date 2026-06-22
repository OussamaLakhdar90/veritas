package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.llm.copilot.CopilotTokens.DeviceCode;
import org.junit.jupiter.api.Test;

class CopilotLoginServiceTest {

    private final CurrentUser local = () -> "local";

    private void awaitState(CopilotLoginService svc, String id, String expected) throws InterruptedException {
        for (int i = 0; i < 40 && !expected.equals(svc.status(id).state()); i++) {
            Thread.sleep(25);
        }
        assertThat(svc.status(id).state()).isEqualTo(expected);
    }

    @SuppressWarnings("unchecked")
    @Test
    void startShowsUserCodeThenReachesAuthorized() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        when(auth.deviceFlow(any())).thenAnswer(inv -> {
            ((Consumer<DeviceCode>) inv.getArgument(0))
                    .accept(new DeviceCode("dc", "ABCD-1234", "https://github.com/login/device", 5, 900));
            return null;   // success → AUTHORIZED
        });
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        CopilotLoginStart start = svc.start();
        assertThat(start.userCode()).isEqualTo("ABCD-1234");
        assertThat(start.verificationUri()).contains("github.com");
        awaitState(svc, start.id(), "AUTHORIZED");
    }

    @SuppressWarnings("unchecked")
    @Test
    void expiredDeviceCodeSurfacesAsExpired() throws Exception {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        when(auth.deviceFlow(any())).thenAnswer(inv -> {
            ((Consumer<DeviceCode>) inv.getArgument(0))
                    .accept(new DeviceCode("dc", "X-Y", "https://github.com/login/device", 5, 900));
            throw new IllegalStateException("Device code expired. Please run copilot-login again.");
        });
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        CopilotLoginStart start = svc.start();
        awaitState(svc, start.id(), "EXPIRED");
    }

    @Test
    void unknownSessionRejectedAndSignOutDelegates() {
        CopilotAuthService auth = mock(CopilotAuthService.class);
        when(auth.isAuthenticated()).thenReturn(true);
        CopilotLoginService svc = new CopilotLoginService(auth, local);

        assertThatThrownBy(() -> svc.status("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThat(svc.isAuthenticated()).isTrue();
        svc.signOut();
        verify(auth).signOut();
    }
}
