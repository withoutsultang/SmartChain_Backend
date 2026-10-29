package com.smartchain.platform.domain.auth.service;

import com.smartchain.platform.domain.auth.repository.EmailVerificationCodeRepository;
import com.smartchain.platform.domain.role.repository.RoleRequestRepository;
import com.smartchain.platform.domain.user.entity.User;
import com.smartchain.platform.domain.user.repository.*;
import com.smartchain.platform.dto.auth.login.LoginRequest;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ActiveProfiles("test")
@Import(AuthService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoginFailurePersistenceTest {
    @Autowired AuthService authService;
    @Autowired UserRepository users;
    @MockitoBean RoleRepository roles;
    @MockitoBean UserDomainRoleRepository domainRoles;
    @MockitoBean RoleRequestRepository requests;
    @MockitoBean EmailVerificationCodeRepository codes;
    @MockitoBean PasswordEncoder encoder;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean EmailService email;
    @MockitoBean RecaptchaService recaptcha;

    @Test
    void failedRequestsPersistAttemptsAndLockAcrossTransactions() {
        User user = users.save(User.builder().name("검증 사용자")
                .email("lock-persistence@example.com").userPassword("encoded")
                .emailVerified(true).build());
        when(encoder.matches("wrong", "encoded")).thenReturn(false);
        LoginRequest request = LoginRequest.builder().email(user.getEmail()).password("wrong").build();
        try {
            for (int attempt = 1; attempt <= 5; attempt++) {
                assertThatThrownBy(() -> authService.login(request)).isInstanceOf(CustomException.class);
                // Repository read occurs after the service transaction has ended.
                User persisted = users.findById(user.getUserId()).orElseThrow();
                assertThat(persisted.getFailedLoginAttempts()).isEqualTo(attempt);
            }
            assertThat(users.findById(user.getUserId()).orElseThrow().getLockedUntil()).isNotNull();
        } finally {
            users.deleteById(user.getUserId());
        }
    }
}
