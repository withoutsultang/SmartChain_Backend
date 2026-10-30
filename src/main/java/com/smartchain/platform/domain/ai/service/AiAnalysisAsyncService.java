package com.smartchain.platform.domain.ai.service;

import com.smartchain.platform.dto.ai.run.SlotHint;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiAnalysisAsyncService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisAsyncService.class);
    private final AiAnalysisService aiAnalysisService;
    private final AiRunStateService state;

    @Async("aiAnalysisExecutor")
    public void submitAsync(Long diagnosticId, List<SlotHint> slotHints, String requestId) {
        if (!state.start(diagnosticId, requestId)) return;
        try {
            aiAnalysisService.submitForRun(diagnosticId, slotHints, requestId);
        } catch (Exception e) {
            String code = e instanceof CustomException custom ? custom.getErrorCode().getCode()
                    : ErrorCode.AI_SERVICE_UNAVAILABLE.getCode();
            state.fail(diagnosticId, requestId, code);
            // Do not persist provider URLs, payloads, or credential-bearing exception messages.
            log.warn("AI run failed: diagnosticId={}, requestId={}, code={}", diagnosticId, requestId, code);
        }
    }
}
