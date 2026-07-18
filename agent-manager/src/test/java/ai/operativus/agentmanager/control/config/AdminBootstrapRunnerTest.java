package ai.operativus.agentmanager.control.config;

import ai.operativus.agentmanager.control.repository.UserRepository;
import ai.operativus.agentmanager.core.entity.User;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.core.model.enums.RoleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private AdminBootstrapRunner runner(String username, String email, String password, String orgId, String role) {
        return new AdminBootstrapRunner(userRepository, passwordEncoder, username, email, password, orgId, role);
    }

    @Test
    void createsAdmin_whenAbsent_encodesPasswordStampsRoleAndOrg() {
        when(userRepository.existsByUsername("ops")).thenReturn(false);
        when(userRepository.existsByEmail("ops@example.com")).thenReturn(false);
        when(passwordEncoder.encode("supersecret")).thenReturn("ENC");

        runner("ops", "ops@example.com", "supersecret", "ACME_ORG", "ROLE_SUPER_ADMIN").run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("ops", saved.getUsername());
        assertEquals("ops@example.com", saved.getEmail());
        assertEquals("ENC", saved.getPassword(), "password must be BCrypt-encoded, never stored raw");
        assertEquals("ACME_ORG", saved.getOrgId());
        assertTrue(saved.getRoles().contains(RoleType.ROLE_SUPER_ADMIN));
    }

    @Test
    void blankOrgId_defaultsToSystemOrg() {
        when(userRepository.existsByUsername("ops")).thenReturn(false);
        when(userRepository.existsByEmail("ops@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("ENC");

        runner("ops", "ops@example.com", "supersecret", "  ", "ROLE_ADMIN").run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, captor.getValue().getOrgId());
    }

    @Test
    void skips_whenUsernameAlreadyExists() {
        when(userRepository.existsByUsername("ops")).thenReturn(true);

        runner("ops", "ops@example.com", "supersecret", null, "ROLE_SUPER_ADMIN").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void skips_whenEmailAlreadyExists() {
        when(userRepository.existsByUsername("ops")).thenReturn(false);
        when(userRepository.existsByEmail("ops@example.com")).thenReturn(true);

        runner("ops", "ops@example.com", "supersecret", null, "ROLE_SUPER_ADMIN").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void failsFast_whenRequiredFieldBlank() {
        assertThrows(IllegalStateException.class,
                () -> runner("ops", "ops@example.com", "  ", null, "ROLE_SUPER_ADMIN").run(null));
        verify(userRepository, never()).save(any());
    }

    @Test
    void failsFast_whenPasswordTooShort() {
        assertThrows(IllegalStateException.class,
                () -> runner("ops", "ops@example.com", "short", null, "ROLE_SUPER_ADMIN").run(null));
        verify(userRepository, never()).save(any());
    }

    @Test
    void failsFast_whenRoleInvalid() {
        assertThrows(IllegalStateException.class,
                () -> runner("ops", "ops@example.com", "supersecret", null, "ROLE_WIZARD").run(null));
        verify(userRepository, never()).save(any());
    }
}
