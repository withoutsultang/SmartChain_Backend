package com.smartchain.platform.dto.ai;

import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;

public record AiRunStatusResponse(Long diagnosticId, String requestId, String status,
                                  Long resultId, String errorCode, boolean retryable, boolean duplicate) {
    public static AiRunStatusResponse from(Diagnostic d, boolean duplicate) {
        String status = d.getAiRunStatus() == null ? "IDLE" : d.getAiRunStatus();
        return new AiRunStatusResponse(d.getDiagnosticId(), d.getAiRunRequestId(), status,
                d.getAiRunResultId(), d.getAiRunErrorCode(), "FAILED".equals(status), duplicate);
    }
}
