package com.smartchain.platform.domain.ai.service;

import com.smartchain.platform.domain.ai.config.AiRunApiConfig;
import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;
import com.smartchain.platform.domain.diagnostic.repository.DiagnosticRepository;
import com.smartchain.platform.dto.ai.AiRunStatusResponse;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiRunStateService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final DiagnosticRepository diagnostics;
    private final AiRunApiConfig config;

    private Diagnostic lock(Long id) {
        Diagnostic d = diagnostics.findForUpdate(id).orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSTIC_NOT_FOUND));
        entityManager.refresh(d, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return d;
    }
    private void expire(Diagnostic d) {
        if (d.isAiRunExpired()) d.failAiRun(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode());
    }

    @Transactional
    public AiRunStatusResponse claim(Long id) {
        Diagnostic d = lock(id);
        expire(d);
        if (d.isAiRunActive()) return AiRunStatusResponse.from(d, true);
        // Covers all configured attempts/backoff; abandoned workers become retryable.
        long seconds = Math.max(30L, (config.getTimeoutSeconds() + 10L) * (config.getMaxRetry() + 1L) + 30L);
        d.beginAiRun(UUID.randomUUID().toString(), LocalDateTime.now().plusSeconds(seconds));
        return AiRunStatusResponse.from(d, false);
    }

    @Transactional
    public boolean start(Long id, String requestId) {
        Diagnostic d = lock(id);
        expire(d);
        if (!d.ownsAiRun(requestId) || !"PENDING".equals(d.getAiRunStatus())) return false;
        d.startAiRun();
        return true;
    }

    @Transactional
    public void fail(Long id, String requestId, String errorCode) {
        Diagnostic d = lock(id);
        if (requestId.equals(d.getAiRunRequestId()) && d.isAiRunActive()) d.failAiRun(errorCode);
    }

    @Transactional
    public AiRunStatusResponse status(Long id) {
        Diagnostic d = lock(id);
        expire(d);
        return AiRunStatusResponse.from(d, false);
    }
}
