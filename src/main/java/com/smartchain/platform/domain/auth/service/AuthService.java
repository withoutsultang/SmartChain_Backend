package com.smartchain.platform.domain.auth.service;

import com.smartchain.platform.domain.auth.entity.EmailVerificationCode;
import com.smartchain.platform.domain.auth.repository.EmailVerificationCodeRepository;
import com.smartchain.platform.domain.role.repository.RoleRequestRepository;
import com.smartchain.platform.domain.user.entity.Role;
import com.smartchain.platform.domain.user.entity.User;
import com.smartchain.platform.domain.user.entity.UserDomainRole;
import com.smartchain.platform.domain.user.repository.RoleRepository;
import com.smartchain.platform.domain.user.repository.UserDomainRoleRepository;
import com.smartchain.platform.domain.user.repository.UserRepository;
import com.smartchain.platform.dto.auth.common.CompanyInfoDto;
import com.smartchain.platform.dto.auth.common.DomainRoleDto;
import com.smartchain.platform.dto.auth.common.RoleInfoDto;
import com.smartchain.platform.dto.auth.common.UserInfoDto;
import com.smartchain.platform.dto.auth.email.*;
import com.smartchain.platform.dto.auth.login.LoginRequest;
import com.smartchain.platform.dto.auth.login.LoginResponse;
import com.smartchain.platform.dto.auth.logout.LogoutRequest;
import com.smartchain.platform.dto.auth.myinfo.MyDomainResponse;
import com.smartchain.platform.dto.auth.myinfo.MyInfoResponse;
import com.smartchain.platform.dto.auth.register.RegisterRequest;
import com.smartchain.platform.dto.auth.register.RegisterResponse;
import com.smartchain.platform.dto.auth.token.TokenRefreshRequest;
import com.smartchain.platform.dto.auth.token.TokenRefreshResponse;
import com.smartchain.platform.global.util.NameMaskingUtil;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.enums.RequestStatus;
import com.smartchain.platform.global.enums.UserStatus;
import com.smartchain.platform.global.error.ErrorCode;
import com.smartchain.platform.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartchain.platform.dto.auth.login.AccountLockInfo;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserDomainRoleRepository userDomainRoleRepository;
    private final RoleRequestRepository roleRequestRepository;
    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final EmailService emailService;
    private final RecaptchaService recaptchaService;

    private static final String GUEST_ROLE_CODE = "GUEST";
    private static final int VERIFICATION_CODE_LENGTH = 6;
    private static final int VERIFICATION_CODE_EXPIRY_MINUTES = 5;
    private static final int RESEND_LIMIT_SECONDS = 60;

    // 계정 잠금 정책
    private static final int TEMP_LOCK_THRESHOLD = 5;      // 임시 잠금 기준 (5회 실패)
    private static final int PERM_LOCK_THRESHOLD = 10;     // 영구 잠금 기준 (10회 실패)
    private static final int TEMP_LOCK_MINUTES = 15;       // 임시 잠금 시간 (15분)

    // 비밀번호 패턴: 8자 이상, 영문+숫자+특수문자
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*#?&])[A-Za-z\\d@$!%*#?&]{8,}$"
    );

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        // 1. 비밀번호 확인
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw new CustomException(ErrorCode.PASSWORD_MISMATCH);
        }

        // 2. 비밀번호 형식 검증
        if (!PASSWORD_PATTERN.matcher(request.getPassword()).matches()) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD_FORMAT);
        }

        // 3. 필수 약관 동의 확인
        if (request.getTerms() == null ||
                !Boolean.TRUE.equals(request.getTerms().getPrivacyPolicyAgreed()) ||
                !Boolean.TRUE.equals(request.getTerms().getServiceTermsAgreed())) {
            throw new CustomException(ErrorCode.TERMS_NOT_AGREED);
        }

        // 4. 이메일 중복 확인
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 5. 이메일 인증 여부 확인 (회원가입 전 인증 완료 필수)
        boolean isEmailVerified = verificationCodeRepository
                .findTopByEmailAndVerifiedTrueOrderByCreatedAtDesc(request.getEmail())
                .isPresent();

        if (!isEmailVerified) {
            throw new CustomException(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        // 6. GUEST 역할 조회
        Role guestRole = roleRepository.findByCode(GUEST_ROLE_CODE)
                .orElseThrow(() -> new CustomException(ErrorCode.ROLE_NOT_FOUND));

        // 7. 사용자 생성 (이메일 인증 완료 상태로)
        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .userPassword(passwordEncoder.encode(request.getPassword()))
                .role(guestRole)
                .emailVerified(true)
                .build();

        User savedUser = userRepository.save(user);

        log.info("User registered: userId={}, email={}, emailVerified=true", savedUser.getUserId(), savedUser.getEmail());

        return RegisterResponse.builder()
                .userId(savedUser.getUserId())
                .email(savedUser.getEmail())
                .name(savedUser.getName())
                .role(GUEST_ROLE_CODE)
                .message("회원가입이 완료되었습니다")
                .nextStep("ROLE_REQUEST")
                .build();
    }

    // Authentication failures must commit the failed-attempt counter and account lock.
    @Transactional(noRollbackFor = CustomException.class)
    public LoginResponse login(LoginRequest request) {
        // 0. reCAPTCHA 검증
        recaptchaService.verify(request.getRecaptchaToken(), "login");

        // 1. 사용자 조회
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));

        // 2. 잠금 상태 확인
        checkAccountLockStatus(user);

        // 3. 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), user.getUserPassword())) {
            handleLoginFailure(user);
        }

        // 4. 계정 상태 검증
        if (user.getStatus() == UserStatus.INACTIVE) {
            throw new CustomException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!user.isEmailVerified()) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_VERIFIED);
        }

        // 5. 로그인 성공 - 실패 횟수 초기화 및 경고 메시지 생성
        int previousFailedAttempts = user.getFailedLoginAttempts();
        user.resetFailedAttempts();
        user.updateLastLoginAt();

        // 6. 토큰 생성
        String roleCode = user.getRole() != null ? user.getRole().getCode() : GUEST_ROLE_CODE;
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getUserId(), user.getEmail(), roleCode);
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserId());

        log.info("User logged in: userId={}, email={}", user.getUserId(), user.getEmail());

        // 7. 응답 생성 (이전 실패 횟수가 3회 이상이면 경고 포함)
        LoginResponse.LoginResponseBuilder responseBuilder = LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenValidityInSeconds())
                .user(buildUserInfoDto(user));

        if (previousFailedAttempts >= 3) {
            responseBuilder
                    .remainingAttempts(TEMP_LOCK_THRESHOLD - previousFailedAttempts)
                    .warningMessage(String.format("로그인 실패 %d회. %d회 추가 실패 시 계정이 잠깁니다.",
                            previousFailedAttempts, TEMP_LOCK_THRESHOLD - previousFailedAttempts));
        }

        return responseBuilder.build();
    }

    /**
     * 계정 잠금 상태 확인
     */
    private void checkAccountLockStatus(User user) {
        // 영구 잠금 확인
        if (user.isPermanentlyLocked()) {
            throw new CustomException(ErrorCode.ACCOUNT_PERMANENTLY_LOCKED);
        }

        // 임시 잠금 확인
        if (user.isTemporarilyLocked()) {
            AccountLockInfo lockInfo = AccountLockInfo.builder()
                    .lockedUntil(user.getLockedUntil())
                    .remainingMinutes(user.getRemainingLockMinutes())
                    .remainingAttempts(0)
                    .build();
            throw new CustomException(ErrorCode.ACCOUNT_TEMPORARILY_LOCKED, lockInfo);
        }
    }

    /**
     * 로그인 실패 처리
     */
    private void handleLoginFailure(User user) {
        int failedAttempts = user.incrementFailedAttempts();
        log.warn("Login failed for user: email={}, attempts={}", user.getEmail(), failedAttempts);

        // 10회 실패 - 영구 잠금
        if (failedAttempts >= PERM_LOCK_THRESHOLD) {
            user.lockPermanently();
            log.warn("Account permanently locked: email={}", user.getEmail());
            throw new CustomException(ErrorCode.ACCOUNT_PERMANENTLY_LOCKED);
        }

        // 5회 실패 - 임시 잠금
        if (failedAttempts >= TEMP_LOCK_THRESHOLD) {
            user.lockTemporarily(TEMP_LOCK_MINUTES);
            log.warn("Account temporarily locked: email={}, until={}", user.getEmail(), user.getLockedUntil());

            AccountLockInfo lockInfo = AccountLockInfo.builder()
                    .lockedUntil(user.getLockedUntil())
                    .remainingMinutes((long) TEMP_LOCK_MINUTES)
                    .remainingAttempts(0)
                    .build();
            throw new CustomException(ErrorCode.ACCOUNT_TEMPORARILY_LOCKED, lockInfo);
        }

        // 일반 실패 - 남은 시도 횟수 포함
        AccountLockInfo lockInfo = AccountLockInfo.builder()
                .remainingAttempts(TEMP_LOCK_THRESHOLD - failedAttempts)
                .build();
        throw new CustomException(ErrorCode.INVALID_CREDENTIALS, lockInfo);
    }

    public EmailCheckResponse checkEmail(EmailCheckRequest request) {
        boolean exists = userRepository.existsByEmail(request.getEmail());

        return EmailCheckResponse.builder()
                .email(request.getEmail())
                .available(!exists)
                .message(exists ? "이미 사용중인 이메일입니다" : "사용 가능한 이메일입니다")
                .build();
    }

    @Transactional
    public SendVerificationResponse sendVerificationCode(SendVerificationRequest request) {
        String email = request.getEmail();

        // 1. 재발송 제한 확인 (1분 이내 재발송 방지)
        Optional<EmailVerificationCode> lastCode = verificationCodeRepository
                .findTopByEmailOrderByCreatedAtDesc(email);

        if (lastCode.isPresent()) {
            LocalDateTime lastSentAt = lastCode.get().getCreatedAt();
            long secondsSinceLastSend = ChronoUnit.SECONDS.between(lastSentAt, LocalDateTime.now());
            if (secondsSinceLastSend < RESEND_LIMIT_SECONDS) {
                throw new CustomException(ErrorCode.VERIFICATION_RATE_LIMIT);
            }
        }

        // 2. 인증 코드 생성
        String code = generateVerificationCode();

        // 3. 인증 코드 저장
        EmailVerificationCode verificationCode = EmailVerificationCode.builder()
                .email(email)
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(VERIFICATION_CODE_EXPIRY_MINUTES))
                .build();

        verificationCodeRepository.save(verificationCode);

        // 이메일 발송 (프로파일에 따라 실제 발송 또는 로깅)
        emailService.sendVerificationCode(email, code, VERIFICATION_CODE_EXPIRY_MINUTES);

        return SendVerificationResponse.builder()
                .email(email)
                .message("인증 코드가 발송되었습니다")
                .expiresInSeconds(VERIFICATION_CODE_EXPIRY_MINUTES * 60)
                .build();
    }

    @Transactional
    public EmailVerificationResponse verifyEmail(EmailVerificationRequest request) {
        // 1. 가장 최근 인증 코드 조회
        EmailVerificationCode verificationCode = verificationCodeRepository
                .findTopByEmailAndVerifiedFalseOrderByCreatedAtDesc(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_VERIFICATION_CODE));

        // 2. 만료 확인
        if (verificationCode.isExpired()) {
            throw new CustomException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }

        // 3. 코드 검증
        if (!verificationCode.isValid(request.getVerificationCode())) {
            throw new CustomException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        // 4. 인증 완료 처리
        verificationCode.markAsVerified();

        // 5. 사용자 이메일 인증 상태 업데이트
        userRepository.findByEmail(request.getEmail())
                .ifPresent(User::verifyEmail);

        log.info("Email verified: email={}", request.getEmail());

        return EmailVerificationResponse.builder()
                .verified(true)
                .message("이메일 인증이 완료되었습니다")
                .build();
    }

    public TokenRefreshResponse refreshToken(TokenRefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        // 1. 리프레시 토큰 검증
        JwtTokenProvider.TokenValidationResult validationResult =
                jwtTokenProvider.validateTokenWithResult(refreshToken);

        if (validationResult == JwtTokenProvider.TokenValidationResult.EXPIRED) {
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        }

        if (validationResult == JwtTokenProvider.TokenValidationResult.INVALID) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        // 2. 리프레시 토큰에서 사용자 ID 추출
        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);

        // 3. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 4. 새로운 토큰 발급
        String roleCode = user.getRole() != null ? user.getRole().getCode() : GUEST_ROLE_CODE;
        String newAccessToken = jwtTokenProvider.createAccessToken(
                user.getUserId(), user.getEmail(), roleCode);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(user.getUserId());

        log.info("Token refreshed: userId={}", userId);

        return TokenRefreshResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenValidityInSeconds())
                .build();
    }

    public void logout(LogoutRequest request) {
        // 현재 구현에서는 클라이언트에서 토큰을 삭제하는 방식으로 로그아웃 처리
        // 추후 Redis 등을 이용한 토큰 블랙리스트 방식으로 확장 가능
        log.info("User logged out");
    }

    public MyInfoResponse getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        MyInfoResponse.MyInfoResponseBuilder builder = MyInfoResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .name(user.getName())
                .maskedName(NameMaskingUtil.mask(user.getName()))
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt());

        if (user.getRole() != null) {
            builder.role(RoleInfoDto.builder()
                    .code(user.getRole().getCode())
                    .name(user.getRole().getName())
                    .build());
        }

        if (user.getCompany() != null) {
            builder.company(CompanyInfoDto.builder()
                    .companyId(user.getCompany().getCompanyId())
                    .companyName(user.getCompany().getName())
                    .build());
        }

        builder.domainRoles(buildDomainRoleDtos(user.getUserId()));

        return builder.build();
    }

    public MyDomainResponse getMyDomains(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String globalRole = user.getRole() != null ? user.getRole().getCode() : GUEST_ROLE_CODE;
        List<DomainRoleDto> domainRoles = buildDomainRoleDtos(userId);

        String roleRequestStatus = null;
        if (GUEST_ROLE_CODE.equals(globalRole)) {
            boolean hasPending = roleRequestRepository.existsByUserAndStatus(user, RequestStatus.PENDING);
            roleRequestStatus = hasPending ? "PENDING" : "NONE";
        }

        return MyDomainResponse.builder()
                .globalRole(globalRole)
                .domainRoles(domainRoles)
                .roleRequestStatus(roleRequestStatus)
                .build();
    }

    private String generateVerificationCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < VERIFICATION_CODE_LENGTH; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    private UserInfoDto buildUserInfoDto(User user) {
        UserInfoDto.UserInfoDtoBuilder builder = UserInfoDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .name(user.getName())
                .maskedName(NameMaskingUtil.mask(user.getName()));

        if (user.getRole() != null) {
            builder.role(RoleInfoDto.builder()
                    .code(user.getRole().getCode())
                    .name(user.getRole().getName())
                    .build());
        }

        if (user.getCompany() != null) {
            builder.company(CompanyInfoDto.builder()
                    .companyId(user.getCompany().getCompanyId())
                    .companyName(user.getCompany().getName())
                    .build());
        }

        builder.domainRoles(buildDomainRoleDtos(user.getUserId()));

        return builder.build();
    }

    private List<DomainRoleDto> buildDomainRoleDtos(Long userId) {
        List<UserDomainRole> domainRoles = userDomainRoleRepository.findByUserIdWithDomainAndRole(userId);
        return domainRoles.stream()
                .map(udr -> DomainRoleDto.builder()
                        .domainCode(udr.getDomain().getCode())
                        .domainName(udr.getDomain().getName())
                        .roleCode(udr.getRole().getCode())
                        .roleName(udr.getRole().getName())
                        .build())
                .toList();
    }
}

