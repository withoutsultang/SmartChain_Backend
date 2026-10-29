package com.smartchain.platform.domain.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartchain.platform.domain.ai.client.AiRunApiClient;
import com.smartchain.platform.domain.ai.config.SlotConfigProperties;
import com.smartchain.platform.domain.ai.entity.AiAnalysisResult;
import com.smartchain.platform.domain.ai.repository.AiAnalysisResultRepository;
import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;
import com.smartchain.platform.domain.diagnostic.repository.DiagnosticRepository;
import com.smartchain.platform.domain.evidence.entity.EvidenceFile;
import com.smartchain.platform.domain.evidence.repository.EvidenceFileRepository;
import com.smartchain.platform.domain.review.repository.ReviewRepository;
import com.smartchain.platform.domain.user.entity.Company;
import com.smartchain.platform.domain.user.entity.Domain;
import com.smartchain.platform.dto.ai.run.*;
import com.smartchain.platform.global.enums.ParsingStatus;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiAnalysisServiceTest {

    @Mock
    private AiRunApiClient aiRunApiClient;
    @Mock
    private AiAnalysisResultRepository resultRepository;
    @Mock
    private DiagnosticRepository diagnosticRepository;
    @Mock
    private EvidenceFileRepository evidenceFileRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private SlotConfigProperties slotConfigProperties;

    private ObjectMapper objectMapper;
    private AiAnalysisService aiAnalysisService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiAnalysisService = new AiAnalysisService(
            aiRunApiClient,
            resultRepository,
            diagnosticRepository,
            evidenceFileRepository,
            reviewRepository,
            objectMapper,
            slotConfigProperties
        );
        // @Value로 주입되는 fileBasePath 설정
        ReflectionTestUtils.setField(aiAnalysisService, "fileBasePath", "./uploads");
    }

    @Nested
    @DisplayName("submit")
    class SubmitTest {

        @Test
        @DisplayName("진단이 존재하지 않으면 DIAGNOSTIC_NOT_FOUND 에러를 발생시킨다")
        void submit_diagnosticNotFound_throwsException() {
            // given
            Long diagnosticId = 999L;
            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> aiAnalysisService.submit(diagnosticId, null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(ErrorCode.DIAGNOSTIC_NOT_FOUND);
                });

            verify(aiRunApiClient, never()).submitSync(any());
        }

        @Test
        @DisplayName("증빙 파일이 없으면 DIAGNOSTIC_MISSING_EVIDENCE 에러를 발생시킨다")
        void submit_noEvidenceFiles_throwsException() {
            // given
            Long diagnosticId = 1L;
            Diagnostic diagnostic = createTestDiagnostic(diagnosticId);

            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.of(diagnostic));
            when(evidenceFileRepository.findByDiagnosticId(diagnosticId)).thenReturn(List.of());

            // when & then
            assertThatThrownBy(() -> aiAnalysisService.submit(diagnosticId, null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(ErrorCode.DIAGNOSTIC_MISSING_EVIDENCE);
                });

            verify(aiRunApiClient, never()).submitSync(any());
        }

        @Test
        @org.junit.jupiter.api.Disabled("필수 슬롯 검증이 현재 비활성화 상태 (AiAnalysisService:123-128)")
        @DisplayName("필수 슬롯이 누락되면 AI_MISSING_REQUIRED_SLOTS 에러를 발생시킨다")
        void submit_missingRequiredSlots_throwsException() {
            // given
            Long diagnosticId = 1L;
            Diagnostic diagnostic = createTestDiagnostic(diagnosticId);
            EvidenceFile evidenceFile = createTestEvidenceFile(1L, "선택슬롯파일.pdf");

            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.of(diagnostic));
            when(evidenceFileRepository.findByDiagnosticId(diagnosticId)).thenReturn(List.of(evidenceFile));
            when(slotConfigProperties.matchSlotName(anyString(), anyString())).thenReturn("esg.other");
            when(slotConfigProperties.getRequiredSlots("ESG")).thenReturn(List.of(
                createSlotDefinition("esg.energy.electricity.usage"),
                createSlotDefinition("esg.hazmat.msds")
            ));

            // when & then
            assertThatThrownBy(() -> aiAnalysisService.submit(diagnosticId, null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(ErrorCode.AI_MISSING_REQUIRED_SLOTS);
                });

            verify(aiRunApiClient, never()).submitSync(any());
        }

        @Test
        @DisplayName("정상 흐름: 파일 조회 → AI 호출 → 결과 저장")
        void submit_success_savesResult() {
            // given
            Long diagnosticId = 1L;
            Diagnostic diagnostic = createTestDiagnostic(diagnosticId);
            EvidenceFile evidenceFile1 = createTestEvidenceFile(1L, "전기사용량.xlsx");
            EvidenceFile evidenceFile2 = createTestEvidenceFile(2L, "msds문서.pdf");

            RunSubmitResponse aiResponse = new RunSubmitResponse(
                "PKG_001", "PASS", "LOW", "모든 검증 완료",
                List.of(), List.of(), null
            );

            AiAnalysisResult savedResult = AiAnalysisResult.builder()
                .diagnostic(diagnostic)
                .domainCode("ESG")
                .packageId("PKG_001")
                .verdict("PASS")
                .riskLevel("LOW")
                .build();
            ReflectionTestUtils.setField(savedResult, "id", 100L);

            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.of(diagnostic));
            when(evidenceFileRepository.findByDiagnosticId(diagnosticId))
                .thenReturn(List.of(evidenceFile1, evidenceFile2));
            when(slotConfigProperties.matchSlotName("전기사용량.xlsx", "ESG"))
                .thenReturn("esg.energy.electricity.usage");
            when(slotConfigProperties.matchSlotName("msds문서.pdf", "ESG"))
                .thenReturn("esg.hazmat.msds");
            when(slotConfigProperties.getRequiredSlots("ESG")).thenReturn(List.of(
                createSlotDefinition("esg.energy.electricity.usage"),
                createSlotDefinition("esg.hazmat.msds")
            ));
            when(resultRepository.findTopByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId))
                .thenReturn(Optional.empty());
            when(aiRunApiClient.submitSync(any(RunSubmitRequest.class))).thenReturn(aiResponse);
            when(resultRepository.save(any(AiAnalysisResult.class))).thenReturn(savedResult);

            // when
            AiAnalysisResult result = aiAnalysisService.submit(diagnosticId, null);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getPackageId()).isEqualTo("PKG_001");
            assertThat(result.getVerdict()).isEqualTo("PASS");

            // AI API 호출 검증
            ArgumentCaptor<RunSubmitRequest> requestCaptor = ArgumentCaptor.forClass(RunSubmitRequest.class);
            verify(aiRunApiClient).submitSync(requestCaptor.capture());
            RunSubmitRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.domain()).isEqualTo("esg");
            assertThat(capturedRequest.files()).hasSize(2);

            // 결과 저장 검증
            verify(resultRepository).save(any(AiAnalysisResult.class));
        }

        @Test
        @DisplayName("AI 서비스 에러 시 예외가 전파된다")
        void submit_aiServiceError_propagatesException() {
            // given
            Long diagnosticId = 1L;
            Diagnostic diagnostic = createTestDiagnostic(diagnosticId);
            EvidenceFile evidenceFile = createTestEvidenceFile(1L, "전기사용량.xlsx");

            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.of(diagnostic));
            when(evidenceFileRepository.findByDiagnosticId(diagnosticId)).thenReturn(List.of(evidenceFile));
            when(slotConfigProperties.matchSlotName(anyString(), anyString()))
                .thenReturn("esg.energy.electricity.usage");
            when(slotConfigProperties.getRequiredSlots("ESG")).thenReturn(List.of(
                createSlotDefinition("esg.energy.electricity.usage")
            ));
            when(resultRepository.findTopByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId))
                .thenReturn(Optional.empty());
            when(aiRunApiClient.submitSync(any(RunSubmitRequest.class)))
                .thenThrow(new CustomException(ErrorCode.AI_SERVICE_ERROR));

            // when & then
            assertThatThrownBy(() -> aiAnalysisService.submit(diagnosticId, null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR);
                });

            verify(resultRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("preview")
    class PreviewTest {

        @Test
        @DisplayName("진단이 존재하지 않으면 DIAGNOSTIC_NOT_FOUND 에러를 발생시킨다")
        void preview_diagnosticNotFound_throwsException() {
            // given
            Long diagnosticId = 999L;
            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> aiAnalysisService.preview(diagnosticId, List.of(1L), null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(ErrorCode.DIAGNOSTIC_NOT_FOUND);
                });
        }

        @Test
        @DisplayName("파일이 존재하지 않으면 FILE_NOT_FOUND 에러를 발생시킨다")
        void preview_fileNotFound_throwsException() {
            // given
            Long diagnosticId = 1L;
            Diagnostic diagnostic = createTestDiagnostic(diagnosticId);

            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.of(diagnostic));
            when(evidenceFileRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> aiAnalysisService.preview(diagnosticId, List.of(999L), null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND);
                });
        }

        @Test
        @DisplayName("파일 파싱이 완료되지 않으면 AI_FILE_NOT_READY 에러를 발생시킨다")
        void preview_fileNotReady_throwsException() {
            // given
            Long diagnosticId = 1L;
            Diagnostic diagnostic = createTestDiagnostic(diagnosticId);
            EvidenceFile evidenceFile = createTestEvidenceFileWithStatus(1L, "전기사용량.xlsx", ParsingStatus.PROCESSING);

            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.of(diagnostic));
            when(evidenceFileRepository.findById(1L)).thenReturn(Optional.of(evidenceFile));

            // when & then
            assertThatThrownBy(() -> aiAnalysisService.preview(diagnosticId, List.of(1L), null))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(ErrorCode.AI_FILE_NOT_READY);
                });

            verify(aiRunApiClient, never()).previewSync(any());
        }

        @Test
        @DisplayName("정상적인 preview 호출 시 AI 응답을 반환한다")
        void preview_success_returnsResponse() {
            // given
            Long diagnosticId = 1L;
            Diagnostic diagnostic = createTestDiagnostic(diagnosticId);
            EvidenceFile evidenceFile = createTestEvidenceFile(1L, "전기사용량.xlsx");

            RunPreviewResponse aiResponse = new RunPreviewResponse(
                "PKG_001",
                List.of(new SlotHint("1", "esg.energy.electricity.usage")),
                List.of(new SlotStatus("esg.energy.electricity.usage", "SUBMITTED")),
                List.of()
            );

            when(diagnosticRepository.findById(diagnosticId)).thenReturn(Optional.of(diagnostic));
            when(evidenceFileRepository.findById(1L)).thenReturn(Optional.of(evidenceFile));
            when(resultRepository.findTopByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId))
                .thenReturn(Optional.empty());
            when(aiRunApiClient.previewSync(any(RunPreviewRequest.class))).thenReturn(aiResponse);

            // when
            RunPreviewResponse result = aiAnalysisService.preview(diagnosticId, List.of(1L), null);

            // then
            assertThat(result).isNotNull();
            assertThat(result.packageId()).isEqualTo("PKG_001");
            assertThat(result.missingRequiredSlots()).isEmpty();

            // AI API 호출 검증
            ArgumentCaptor<RunPreviewRequest> requestCaptor = ArgumentCaptor.forClass(RunPreviewRequest.class);
            verify(aiRunApiClient).previewSync(requestCaptor.capture());
            RunPreviewRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.domain()).isEqualTo("esg");
        }
    }

    @Nested
    @DisplayName("getLatestResult")
    class GetLatestResultTest {

        @Test
        @DisplayName("분석 결과가 없으면 AI_ANALYSIS_NOT_FOUND 에러를 발생시킨다")
        void getLatestResult_notFound_throwsException() {
            // given
            Long diagnosticId = 1L;
            when(resultRepository.findTopByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId))
                .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> aiAnalysisService.getLatestResult(diagnosticId))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(ErrorCode.AI_ANALYSIS_NOT_FOUND);
                });
        }

        @Test
        @DisplayName("분석 결과가 있으면 최신 결과를 반환한다")
        void getLatestResult_found_returnsResult() {
            // given
            Long diagnosticId = 1L;
            AiAnalysisResult expectedResult = AiAnalysisResult.builder()
                .packageId("PKG_001")
                .verdict("PASS")
                .domainCode("ESG")
                .build();
            ReflectionTestUtils.setField(expectedResult, "id", 1L);

            when(resultRepository.findTopByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(diagnosticId))
                .thenReturn(Optional.of(expectedResult));

            // when
            AiAnalysisResult result = aiAnalysisService.getLatestResult(diagnosticId);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getPackageId()).isEqualTo("PKG_001");
        }
    }

    @Nested
    @DisplayName("validateRequiredSlots")
    class ValidateRequiredSlotsTest {

        @Test
        @DisplayName("모든 필수 슬롯이 제출되면 빈 목록을 반환한다")
        void validateRequiredSlots_allSubmitted_returnsEmpty() {
            // given
            String domainCode = "ESG";
            List<SlotHint> slotHints = List.of(
                new SlotHint("1", "esg.energy.electricity.usage"),
                new SlotHint("2", "esg.hazmat.msds")
            );

            when(slotConfigProperties.getRequiredSlots(domainCode)).thenReturn(List.of(
                createSlotDefinition("esg.energy.electricity.usage"),
                createSlotDefinition("esg.hazmat.msds")
            ));

            // when
            List<String> missingSlots = aiAnalysisService.validateRequiredSlots(slotHints, domainCode);

            // then
            assertThat(missingSlots).isEmpty();
        }

        @Test
        @DisplayName("일부 필수 슬롯이 누락되면 누락된 슬롯 목록을 반환한다")
        void validateRequiredSlots_someMissing_returnsMissingList() {
            // given
            String domainCode = "ESG";
            List<SlotHint> slotHints = List.of(
                new SlotHint("1", "esg.energy.electricity.usage")
            );

            when(slotConfigProperties.getRequiredSlots(domainCode)).thenReturn(List.of(
                createSlotDefinition("esg.energy.electricity.usage"),
                createSlotDefinition("esg.hazmat.msds")
            ));

            // when
            List<String> missingSlots = aiAnalysisService.validateRequiredSlots(slotHints, domainCode);

            // then
            assertThat(missingSlots).containsExactly("esg.hazmat.msds");
        }
    }

    // Helper methods
    private Diagnostic createTestDiagnostic(Long id) {
        Domain domain = Domain.builder()
            .code("ESG")
            .name("ESG 실사")
            .build();

        Company company = Company.builder()
            .name("테스트 회사")
            .build();
        ReflectionTestUtils.setField(company, "companyId", 1L);

        Diagnostic diagnostic = Diagnostic.builder()
            .domain(domain)
            .company(company)
            .periodStartDate(LocalDate.of(2024, 1, 1))
            .periodEndDate(LocalDate.of(2024, 12, 31))
            .build();
        ReflectionTestUtils.setField(diagnostic, "diagnosticId", id);
        return diagnostic;
    }

    private EvidenceFile createTestEvidenceFile(Long id, String fileName) {
        EvidenceFile evidenceFile = EvidenceFile.builder()
            .diagnostic(createTestDiagnostic(1L))
            .filePath("/path/to/" + fileName)
            .originalFileName(fileName)
            .build();
        ReflectionTestUtils.setField(evidenceFile, "resultFileId", id);
        // 파싱 완료 상태로 설정 (preview/submit 시 파싱 상태 검증 통과)
        evidenceFile.completeParsing(null, null, null);
        return evidenceFile;
    }

    private EvidenceFile createTestEvidenceFileWithStatus(Long id, String fileName, ParsingStatus status) {
        EvidenceFile evidenceFile = EvidenceFile.builder()
            .diagnostic(createTestDiagnostic(1L))
            .filePath("/path/to/" + fileName)
            .originalFileName(fileName)
            .build();
        ReflectionTestUtils.setField(evidenceFile, "resultFileId", id);
        ReflectionTestUtils.setField(evidenceFile, "parsingStatus", status);
        return evidenceFile;
    }

    private SlotConfigProperties.SlotDefinition createSlotDefinition(String name) {
        SlotConfigProperties.SlotDefinition slot = new SlotConfigProperties.SlotDefinition();
        slot.setName(name);
        slot.setRequired(true);
        return slot;
    }
}
