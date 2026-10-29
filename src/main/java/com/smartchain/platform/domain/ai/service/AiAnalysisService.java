package com.smartchain.platform.domain.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartchain.platform.domain.ai.client.AiRunApiClient;
import com.smartchain.platform.domain.ai.config.SlotConfigProperties;
import com.smartchain.platform.domain.ai.entity.AiAnalysisResult;
import com.smartchain.platform.domain.ai.repository.AiAnalysisResultRepository;
import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;
import com.smartchain.platform.domain.diagnostic.repository.DiagnosticRepository;
import com.smartchain.platform.domain.evidence.entity.EvidenceFile;
import com.smartchain.platform.domain.evidence.repository.EvidenceFileRepository;
import com.smartchain.platform.domain.review.entity.Review;
import com.smartchain.platform.domain.review.repository.ReviewRepository;
import com.smartchain.platform.dto.ai.run.*;
import com.smartchain.platform.global.enums.ParsingStatus;
import com.smartchain.platform.global.enums.RiskLevel;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 분석 서비스
 * Diagnostic과 AI Run API를 연동
 */
@Service
@Transactional(readOnly = true)
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AiRunApiClient aiRunApiClient;
    private final AiAnalysisResultRepository resultRepository;
    private final DiagnosticRepository diagnosticRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final ReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;
    private final SlotConfigProperties slotConfigProperties;

    @Value("${file.storage.local.base-path:./uploads}")
    private String fileBasePath;

    public AiAnalysisService(
        AiRunApiClient aiRunApiClient,
        AiAnalysisResultRepository resultRepository,
        DiagnosticRepository diagnosticRepository,
        EvidenceFileRepository evidenceFileRepository,
        ReviewRepository reviewRepository,
        ObjectMapper objectMapper,
        SlotConfigProperties slotConfigProperties
    ) {
        this.aiRunApiClient = aiRunApiClient;
        this.resultRepository = resultRepository;
        this.diagnosticRepository = diagnosticRepository;
        this.evidenceFileRepository = evidenceFileRepository;
        this.reviewRepository = reviewRepository;
        this.objectMapper = objectMapper;
        this.slotConfigProperties = slotConfigProperties;
    }

    /**
     * Preview 호출 - 파일 추가/제거 시 슬롯 추정
     * preview 응답의 package_id를 Diagnostic에 저장하여 다음 호출에서 재사용
     */
    @Transactional
    public RunPreviewResponse preview(Long diagnosticId, List<Long> fileIds, List<String> removedFileIds) {
        Diagnostic diagnostic = getDiagnostic(diagnosticId);
        String domainCode = getDomainCode(diagnostic);

        List<FileInfo> addedFiles = fileIds.stream()
            .map(fileId -> toFileInfo(fileId, diagnosticId))
            .toList();

        // package_id 조회: preview 저장분 → submit 저장분 → null (신규)
        String existingPackageId = diagnostic.getPreviewPackageId();
        if (existingPackageId == null) {
            existingPackageId = resultRepository
                .findTopByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId)
                .map(AiAnalysisResult::getPackageId)
                .orElse(null);
        }

        if (diagnostic.getPeriodStartDate() == null || diagnostic.getPeriodEndDate() == null) {
            throw new CustomException(ErrorCode.AI_MISSING_PERIOD_DATES);
        }

        RunPreviewRequest request = new RunPreviewRequest(
            domainCode.toLowerCase(),
            diagnostic.getPeriodStartDate().format(DATE_FORMATTER),
            diagnostic.getPeriodEndDate().format(DATE_FORMATTER),
            existingPackageId,
            addedFiles,
            removedFileIds
        );

        log.info("Preview 요청 - diagnosticId: {}, packageId: {}, addedFiles: {}, removedFileIds: {}",
            diagnosticId, existingPackageId,
            addedFiles.stream().map(FileInfo::fileId).toList(),
            removedFileIds);

        RunPreviewResponse response = aiRunApiClient.previewSync(request);

        // 응답의 package_id를 Diagnostic에 저장 (다음 preview에서 재사용)
        if (response.packageId() != null) {
            diagnostic.updatePreviewPackageId(response.packageId());
        }

        return response;
    }

    /**
     * Submit 호출 - 전체 검증 및 판정 (동기)
     */
    @Transactional
    public AiAnalysisResult submit(Long diagnosticId, List<SlotHint> frontendSlotHints) {
        Diagnostic diagnostic = getDiagnostic(diagnosticId);
        String domainCode = getDomainCode(diagnostic);

        if (diagnostic.getPeriodStartDate() == null || diagnostic.getPeriodEndDate() == null) {
            throw new CustomException(ErrorCode.AI_MISSING_PERIOD_DATES);
        }

        // 증빙 파일 목록 조회
        List<EvidenceFile> evidenceFiles = evidenceFileRepository.findByDiagnosticId(diagnosticId);
        if (evidenceFiles.isEmpty()) {
            throw new CustomException(ErrorCode.DIAGNOSTIC_MISSING_EVIDENCE);
        }

        // 파싱 미완료 파일 검증
        List<Long> notReadyFileIds = evidenceFiles.stream()
            .filter(ef -> ef.getParsingStatus() != ParsingStatus.SUCCESS)
            .map(EvidenceFile::getResultFileId)
            .toList();
        if (!notReadyFileIds.isEmpty()) {
            log.warn("파일 파싱 미완료 - diagnosticId: {}, fileIds: {}", diagnosticId, notReadyFileIds);
            throw new CustomException(ErrorCode.AI_FILE_NOT_READY);
        }

        // FileInfo 변환 (절대 경로로 변환)
        List<FileInfo> files = evidenceFiles.stream()
            .map(ef -> new FileInfo(
                ef.getResultFileId().toString(),
                Paths.get(fileBasePath, ef.getFilePath()).toAbsolutePath().toString(),
                ef.getOriginalFileName()
            ))
            .toList();

        // SlotHint: 프론트에서 보낸 매핑 우선, 없는 파일만 파일명 기반 추측
        List<SlotHint> slotHints = buildSlotHints(evidenceFiles, frontendSlotHints, domainCode);

        // TODO: 필수 슬롯 검증 임시 비활성화 (테스트용)
        List<String> missingRequiredSlots = validateRequiredSlots(slotHints, domainCode);
        if (!missingRequiredSlots.isEmpty()) {
            log.warn("필수 슬롯 미제출 (검증 비활성화 상태) - diagnosticId: {}, missingSlots: {}",
                diagnosticId, missingRequiredSlots);
        }

        // package_id 조회: preview 저장분 → submit 저장분 → 신규 생성
        String packageId = diagnostic.getPreviewPackageId();
        if (packageId == null) {
            packageId = resultRepository
                .findTopByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId)
                .map(AiAnalysisResult::getPackageId)
                .orElse(generatePackageId(diagnostic));
        }

        RunSubmitRequest request = new RunSubmitRequest(
            packageId,
            domainCode.toLowerCase(),
            diagnostic.getPeriodStartDate().format(DATE_FORMATTER),
            diagnostic.getPeriodEndDate().format(DATE_FORMATTER),
            files,
            slotHints
        );

        // AI API 호출
        RunSubmitResponse response = aiRunApiClient.submitSync(request);

        // slotResults에 displayName 매핑
        RunSubmitResponse enrichedResponse = enrichWithDisplayNames(response, slotHints, domainCode);

        // 결과 저장
        return saveAnalysisResult(diagnostic, domainCode, enrichedResponse);
    }

    /**
     * AI 응답의 slotResults에 displayName 추가
     * 프론트에서 보낸 slotHints의 displayName 우선, 없으면 설정에서 조회
     * __x__ 교차 검증 슬롯은 구성 슬롯들의 displayName을 "A ↔ B" 형태로 조합
     */
    private RunSubmitResponse enrichWithDisplayNames(RunSubmitResponse response,
                                                      List<SlotHint> slotHints,
                                                      String domainCode) {
        if (response.slotResults() == null || response.slotResults().isEmpty()) {
            return response;
        }

        // 프론트에서 보낸 displayName 매핑 (slotName -> displayName)
        Map<String, String> frontendDisplayNames = slotHints.stream()
            .filter(h -> h.displayName() != null && !h.displayName().isBlank())
            .collect(Collectors.toMap(
                SlotHint::slotName,
                SlotHint::displayName,
                (a, b) -> a  // 중복 시 첫 번째 우선
            ));

        // slotResults에 displayName 추가
        List<SlotResult> enrichedResults = response.slotResults().stream()
            .map(result -> {
                String displayName;
                if (result.slotName() != null && result.slotName().contains("__x__")) {
                    // 교차 검증 슬롯: 구성 슬롯들의 displayName을 "A ↔ B" 형태로 조합
                    displayName = buildCrossValidationDisplayName(result.slotName(), frontendDisplayNames, domainCode);
                } else {
                    displayName = frontendDisplayNames.getOrDefault(
                        result.slotName(),
                        slotConfigProperties.getDisplayName(result.slotName(), domainCode)
                    );
                }
                return result.withDisplayName(displayName);
            })
            .toList();

        return new RunSubmitResponse(
            response.packageId(),
            response.riskLevel(),
            response.verdict(),
            response.why(),
            enrichedResults,
            response.clarifications(),
            response.extras()
        );
    }

    /**
     * __x__ 교차 검증 슬롯의 displayName 생성
     * 구성 슬롯들의 displayName을 " ↔ " 구분자로 조합
     */
    private String buildCrossValidationDisplayName(String crossSlotName,
                                                     Map<String, String> frontendDisplayNames,
                                                     String domainCode) {
        String[] parts = crossSlotName.split("__x__");
        return java.util.Arrays.stream(parts)
            .map(part -> frontendDisplayNames.getOrDefault(
                part,
                slotConfigProperties.getDisplayName(part, domainCode)
            ))
            .collect(Collectors.joining(" ↔ "));
    }

    /**
     * 필수 슬롯 검증 - 제출된 파일의 슬롯과 필수 슬롯을 비교
     * @return 누락된 필수 슬롯 목록 (비어있으면 모든 필수 슬롯 제출됨)
     */
    public List<String> validateRequiredSlots(List<SlotHint> slotHints, String domainCode) {
        // 필수 슬롯 목록 조회
        List<String> requiredSlotNames = slotConfigProperties.getRequiredSlots(domainCode)
            .stream()
            .map(SlotConfigProperties.SlotDefinition::getName)
            .toList();

        // 제출된 슬롯 목록
        Set<String> submittedSlots = new HashSet<>();
        for (SlotHint hint : slotHints) {
            submittedSlots.add(hint.slotName());
        }

        // 누락된 필수 슬롯 계산
        return requiredSlotNames.stream()
            .filter(slot -> !submittedSlots.contains(slot))
            .toList();
    }

    /**
     * 진단의 최신 분석 결과 조회
     */
    public AiAnalysisResult getLatestResult(Long diagnosticId) {
        return resultRepository.findTopByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId)
            .orElseThrow(() -> new CustomException(ErrorCode.AI_ANALYSIS_NOT_FOUND));
    }

    /**
     * 특정 분석 결과 조회 (ID로)
     */
    public AiAnalysisResult getResultById(Long resultId) {
        return resultRepository.findById(resultId)
            .orElseThrow(() -> new CustomException(ErrorCode.AI_ANALYSIS_NOT_FOUND));
    }

    /**
     * 진단의 분석 이력 조회
     */
    public List<AiAnalysisResult> getAnalysisHistory(Long diagnosticId) {
        return resultRepository.findByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId);
    }

    private Diagnostic getDiagnostic(Long diagnosticId) {
        return diagnosticRepository.findById(diagnosticId)
            .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSTIC_NOT_FOUND));
    }

    private String getDomainCode(Diagnostic diagnostic) {
        if (diagnostic.getDomain() != null) {
            return diagnostic.getDomain().getCode();
        }
        // 레거시 데이터는 ESG로 기본 설정
        return "ESG";
    }

    private FileInfo toFileInfo(Long fileId, Long diagnosticId) {
        EvidenceFile ef = evidenceFileRepository.findById(fileId)
            .orElseThrow(() -> new CustomException(ErrorCode.FILE_NOT_FOUND));

        // A permitted diagnostic ID must not be used to submit another diagnostic's files.
        if (ef.getDiagnostic() == null || !diagnosticId.equals(ef.getDiagnostic().getDiagnosticId())) {
            throw new CustomException(ErrorCode.PERMISSION_DENIED_RESOURCE);
        }

        if (ef.getParsingStatus() != ParsingStatus.SUCCESS) {
            log.warn("파일 파싱 미완료 - fileId: {}, status: {}", fileId, ef.getParsingStatus());
            throw new CustomException(ErrorCode.AI_FILE_NOT_READY);
        }

        return new FileInfo(
            ef.getResultFileId().toString(),
            ef.getFilePath(),
            ef.getOriginalFileName()
        );
    }

    /**
     * 프론트에서 보낸 slotHints를 우선 사용하고, 매핑이 없는 파일만 파일명 기반 추측
     */
    private List<SlotHint> buildSlotHints(List<EvidenceFile> evidenceFiles,
                                          List<SlotHint> frontendSlotHints,
                                          String domainCode) {
        // 프론트 매핑이 없으면 전부 파일명 기반 추측
        if (frontendSlotHints == null || frontendSlotHints.isEmpty()) {
            return evidenceFiles.stream()
                .map(ef -> new SlotHint(
                    ef.getResultFileId().toString(),
                    guessSlotName(ef.getOriginalFileName(), domainCode)
                ))
                .toList();
        }

        // 프론트 매핑을 fileId 기준으로 인덱싱
        Map<String, String> frontendMapping = frontendSlotHints.stream()
            .collect(Collectors.toMap(
                SlotHint::fileId,
                SlotHint::slotName,
                (a, b) -> b  // 중복 시 후자 우선
            ));

        // 프론트 매핑 우선, 없는 파일만 fallback
        return evidenceFiles.stream()
            .map(ef -> {
                String fileId = ef.getResultFileId().toString();
                String slotName = frontendMapping.getOrDefault(fileId,
                    guessSlotName(ef.getOriginalFileName(), domainCode));
                return new SlotHint(fileId, slotName);
            })
            .toList();
    }

    private String guessSlotName(String fileName, String domainCode) {
        return slotConfigProperties.matchSlotName(fileName, domainCode);
    }

    private String generatePackageId(Diagnostic diagnostic) {
        String companyCode = diagnostic.getCompany() != null
            ? diagnostic.getCompany().getCompanyId().toString()
            : "UNKNOWN";
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        return String.format("PKG_%s_%s_%d", companyCode, date, diagnostic.getDiagnosticId());
    }

    @Transactional
    protected AiAnalysisResult saveAnalysisResult(
        Diagnostic diagnostic,
        String domainCode,
        RunSubmitResponse response
    ) {
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.warn("AI 응답 JSON 변환 실패", e);
            resultJson = "{}";
        }

        AiAnalysisResult result = AiAnalysisResult.builder()
            .diagnostic(diagnostic)
            .domainCode(domainCode)
            .packageId(response.packageId())
            .riskLevel(response.riskLevel())
            .verdict(response.verdict())
            .whySummary(response.why())
            .resultJson(resultJson)
            .analyzedAt(LocalDateTime.now())
            .build();

        AiAnalysisResult saved = resultRepository.save(result);

        // Review가 존재하면 riskLevel 업데이트
        RiskLevel riskLevel = RiskLevel.fromString(response.riskLevel());
        reviewRepository.findByDiagnostic(diagnostic).ifPresentOrElse(
            review -> {
                review.updateRiskLevel(riskLevel);
                log.info("Review riskLevel 업데이트 - diagnosticId: {}, riskLevel: {}",
                    diagnostic.getDiagnosticId(), riskLevel);
            },
            () -> log.info("Review 미존재 (결재 전) - diagnosticId: {}, riskLevel은 심사 생성 시 반영 예정",
                diagnostic.getDiagnosticId())
        );

        return saved;
    }
}
