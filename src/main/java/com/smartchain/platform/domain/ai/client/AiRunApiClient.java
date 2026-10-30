package com.smartchain.platform.domain.ai.client;

import com.smartchain.platform.dto.ai.run.RunPreviewRequest;
import com.smartchain.platform.dto.ai.run.RunPreviewResponse;
import com.smartchain.platform.dto.ai.run.RunSubmitRequest;
import com.smartchain.platform.dto.ai.run.RunSubmitResponse;
import com.smartchain.platform.global.enums.AiVerdict;
import com.smartchain.platform.global.enums.RiskLevel;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.error.ErrorCode;
import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * AI Run API 클라이언트
 * 공통 /run/preview, /run/submit 엔드포인트 호출
 */
@Component
public class AiRunApiClient {

    private static final Logger log = LoggerFactory.getLogger(AiRunApiClient.class);

    private final WebClient webClient;
    private final int maxRetry;
    private final int submitTimeoutSeconds;
    private final int previewTimeoutSeconds;
    private final int previewMaxRetry;

    public AiRunApiClient(
        @Qualifier("aiRunApiWebClient") WebClient webClient,
        com.smartchain.platform.domain.ai.config.AiRunApiConfig config
    ) {
        this.webClient = webClient;
        this.maxRetry = config.getMaxRetry();
        this.submitTimeoutSeconds = config.getTimeoutSeconds();
        this.previewTimeoutSeconds = config.getPreviewTimeoutSeconds();
        this.previewMaxRetry = config.getPreviewMaxRetry();
    }

    /**
     * Preview API 호출
     * 파일 추가 시 슬롯 추정 및 필수 항목 현황 반환
     */
    public Mono<RunPreviewResponse> preview(RunPreviewRequest request) {
        log.info("AI Run API preview 호출 - domain: {}, packageId: {}, addedFiles: {}, removedFileIds: {}",
            request.domain(), request.packageId(),
            request.addedFiles() != null ? request.addedFiles().size() : 0,
            request.removedFileIds());

        return webClient.post()
            .uri("/run/preview")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(RunPreviewResponse.class)
            .timeout(Duration.ofSeconds(previewTimeoutSeconds))
            .retryWhen(Retry.backoff(previewMaxRetry, Duration.ofSeconds(1))
                .filter(this::isRetryableError)
                .onRetryExhaustedThrow((spec, signal) ->
                    new CustomException(ErrorCode.AI_SERVICE_UNAVAILABLE)))
            .onErrorMap(this::mapToCustomException)
            .doOnSuccess(response ->
                log.info("AI Run API preview 성공 - packageId: {}", response.packageId()))
            .doOnError(error -> {
                if (!(error instanceof CustomException)) {
                    log.error("AI Run API preview 실패", error);
                }
            });
    }

    /**
     * Submit API 호출
     * 전체 파일 검증 및 최종 판정 결과 반환
     */
    public Mono<RunSubmitResponse> submit(RunSubmitRequest request) {
        log.info("AI Run API submit 호출 - domain: {}, packageId: {}, files: {}",
            request.domain(), request.packageId(),
            request.files() != null ? request.files().size() : 0);

        return webClient.post()
            .uri("/run/submit")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(RunSubmitResponse.class)
            .timeout(Duration.ofSeconds(submitTimeoutSeconds))
            .map(this::validateSubmitResponse)
            .retryWhen(Retry.backoff(maxRetry, Duration.ofSeconds(2))
                .filter(this::isRetryableError)
                .onRetryExhaustedThrow((spec, signal) ->
                    new CustomException(ErrorCode.AI_SERVICE_UNAVAILABLE)))
            .onErrorMap(this::mapToCustomException)
            .doOnSuccess(response ->
                log.info("AI Run API submit 성공 - packageId: {}, verdict: {}",
                    response.packageId(), response.verdict()))
            .doOnError(error -> {
                if (!(error instanceof CustomException)) {
                    log.error("AI Run API submit 실패", error);
                }
            });
    }

    /**
     * Preview API 동기 호출
     */
    public RunPreviewResponse previewSync(RunPreviewRequest request) {
        return preview(request).block();
    }

    /**
     * Submit API 동기 호출
     */
    public RunSubmitResponse submitSync(RunSubmitRequest request) {
        return submit(request).block();
    }

    /**
     * 재시도 가능한 에러인지 판단
     * - 5xx 서버 에러: 재시도
     * - 네트워크/타임아웃 에러: 재시도
     * - 4xx 클라이언트 에러: 재시도 안함 (요청 파라미터 문제)
     */
    private boolean isRetryableError(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            // 5xx 에러만 재시도 (4xx는 요청 문제이므로 재시도 무의미)
            return ex.getStatusCode().is5xxServerError();
        }
        // 네트워크/타임아웃 에러도 재시도
        return isNetworkOrTimeoutError(throwable);
    }

    /**
     * 네트워크 또는 타임아웃 에러인지 확인
     */
    private boolean isNetworkOrTimeoutError(Throwable throwable) {
        if (throwable instanceof ConnectException ||
            throwable instanceof ConnectTimeoutException ||
            throwable instanceof SocketTimeoutException ||
            throwable instanceof io.netty.handler.timeout.ReadTimeoutException ||
            throwable instanceof TimeoutException) {
            return true;
        }
        if (throwable instanceof WebClientRequestException requestEx) {
            Throwable cause = requestEx.getCause();
            return cause instanceof ConnectException ||
                   cause instanceof ConnectTimeoutException ||
                   cause instanceof SocketTimeoutException ||
                   cause instanceof io.netty.handler.timeout.ReadTimeoutException ||
                   cause instanceof TimeoutException;
        }
        return false;
    }

    /**
     * 에러를 CustomException으로 매핑
     * - 4xx: AI_BAD_REQUEST (요청 파라미터 문제)
     * - 5xx: AI_SERVICE_ERROR (서버 장애)
     * - 타임아웃/네트워크: AI_SERVICE_UNAVAILABLE
     * - 이미 CustomException인 경우: 그대로 반환
     */
    private Throwable mapToCustomException(Throwable throwable) {
        // 이미 CustomException이면 그대로 반환
        if (throwable instanceof CustomException) {
            return throwable;
        }

        // HTTP 에러 응답 처리
        if (throwable instanceof WebClientResponseException ex) {
            logErrorResponseBody(ex);

            if (ex.getStatusCode().is4xxClientError()) {
                log.warn("AI 서비스 4xx 에러 - status: {}, message: {}",
                    ex.getStatusCode(), ex.getMessage());
                return new CustomException(ErrorCode.AI_BAD_REQUEST);
            }
            if (ex.getStatusCode().is5xxServerError()) {
                log.error("AI 서비스 5xx 에러 - status: {}, message: {}",
                    ex.getStatusCode(), ex.getMessage());
                return new CustomException(ErrorCode.AI_SERVICE_ERROR);
            }
        }

        // 타임아웃/네트워크 에러 처리
        if (isNetworkOrTimeoutError(throwable)) {
            log.error("AI 서비스 연결 실패 (타임아웃/네트워크) - {}: {}",
                throwable.getClass().getSimpleName(), throwable.getMessage());
            return new CustomException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        // 그 외 에러는 일반 AI 서비스 에러로 처리
        log.error("AI 서비스 알 수 없는 에러 - {}: {}",
            throwable.getClass().getSimpleName(), throwable.getMessage());
        return new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }

    /**
     * AI 서비스 에러 응답 body 로깅 (디버깅용)
     */
    private void logErrorResponseBody(WebClientResponseException ex) {
        try {
            String responseBody = ex.getResponseBodyAsString();
            if (responseBody != null && !responseBody.isBlank()) {
                log.error("AI 서비스 에러 응답 body: {}", responseBody);
            }
        } catch (Exception e) {
            log.debug("AI 서비스 에러 응답 body 파싱 실패", e);
        }
    }

    /**
     * Submit 응답 필수 필드 검증
     * - packageId: null 체크
     * - verdict: null 체크 및 유효값 검증 (PASS, WARN, NEED_CLARIFY, NEED_FIX)
     * - riskLevel: null 체크 및 유효값 검증 (LOW, MEDIUM, HIGH)
     */
    private RunSubmitResponse validateSubmitResponse(RunSubmitResponse response) {
        // 필수 필드 null 체크
        if (response.packageId() == null || response.packageId().isBlank()) {
            log.error("AI 응답 검증 실패: packageId가 null 또는 빈 값입니다");
            throw new CustomException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (response.verdict() == null || response.verdict().isBlank()) {
            log.error("AI 응답 검증 실패: verdict가 null 또는 빈 값입니다");
            throw new CustomException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (response.riskLevel() == null || response.riskLevel().isBlank()) {
            log.error("AI 응답 검증 실패: riskLevel이 null 또는 빈 값입니다");
            throw new CustomException(ErrorCode.AI_INVALID_RESPONSE);
        }

        // verdict 값 유효성 검증
        if (!AiVerdict.isValid(response.verdict())) {
            log.warn("AI 응답 검증 경고: 유효하지 않은 verdict 값 '{}'. 유효한 값: {}",
                response.verdict(), AiVerdict.validValuesString());
            throw new CustomException(ErrorCode.AI_INVALID_VERDICT);
        }

        // riskLevel 값 유효성 검증
        if (!RiskLevel.isValid(response.riskLevel())) {
            log.warn("AI 응답 검증 경고: 유효하지 않은 riskLevel 값 '{}'. 유효한 값: {}",
                response.riskLevel(), RiskLevel.validValuesString());
            throw new CustomException(ErrorCode.AI_INVALID_RISK_LEVEL);
        }

        log.debug("AI 응답 검증 성공 - verdict: {}, riskLevel: {}",
            response.verdict(), response.riskLevel());

        return response;
    }
}
