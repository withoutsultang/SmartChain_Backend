package com.smartchain.platform.domain.file.service;

import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;
import com.smartchain.platform.domain.diagnostic.repository.DiagnosticRepository;
import com.smartchain.platform.domain.evidence.entity.EvidenceFile;
import com.smartchain.platform.domain.evidence.repository.EvidenceFileRepository;
import com.smartchain.platform.domain.file.storage.FileStorageService;
import com.smartchain.platform.domain.job.entity.AsyncJob;
import com.smartchain.platform.domain.job.repository.AsyncJobRepository;
import com.smartchain.platform.domain.job.service.FileParsingJobService;
import com.smartchain.platform.domain.user.entity.Company;
import com.smartchain.platform.domain.user.entity.Role;
import com.smartchain.platform.domain.user.entity.User;
import com.smartchain.platform.domain.user.repository.UserRepository;
import com.smartchain.platform.dto.common.file.*;
import com.smartchain.platform.global.enums.DiagnosticStatus;
import com.smartchain.platform.global.enums.ParsingStatus;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {
    @Mock private jakarta.persistence.EntityManager entityManager;

    @InjectMocks
    private FileService fileService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DiagnosticRepository diagnosticRepository;

    @Mock
    private EvidenceFileRepository evidenceFileRepository;

    @Mock
    private AsyncJobRepository asyncJobRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private FileParsingJobService fileParsingJobService;

    @Mock
    private User drafterUser;

    @Mock
    private User approverUser;

    @Mock
    private User reviewerUser;

    @Mock
    private User guestUser;

    @Mock
    private Role drafterRole;

    @Mock
    private Role approverRole;

    @Mock
    private Role reviewerRole;

    @Mock
    private Role guestRole;

    @Mock
    private Company testCompany;

    @Mock
    private Diagnostic testDiagnostic;

    @Mock
    private EvidenceFile testEvidenceFile;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(fileService, "entityManager", entityManager);
        lenient().when(diagnosticRepository.findForUpdate(any())).thenReturn(java.util.Optional.of(testDiagnostic));
        // Role setup
        lenient().when(drafterRole.getCode()).thenReturn("DRAFTER");
        lenient().when(approverRole.getCode()).thenReturn("APPROVER");
        lenient().when(reviewerRole.getCode()).thenReturn("REVIEWER");
        lenient().when(guestRole.getCode()).thenReturn("GUEST");

        // User setup
        lenient().when(drafterUser.getUserId()).thenReturn(1L);
        lenient().when(drafterUser.getRole()).thenReturn(drafterRole);
        lenient().when(drafterUser.getCompany()).thenReturn(testCompany);

        lenient().when(approverUser.getUserId()).thenReturn(2L);
        lenient().when(approverUser.getRole()).thenReturn(approverRole);
        lenient().when(approverUser.getCompany()).thenReturn(testCompany);

        lenient().when(reviewerUser.getUserId()).thenReturn(3L);
        lenient().when(reviewerUser.getRole()).thenReturn(reviewerRole);

        lenient().when(guestUser.getUserId()).thenReturn(4L);
        lenient().when(guestUser.getRole()).thenReturn(guestRole);

        // Company setup
        lenient().when(testCompany.getCompanyId()).thenReturn(100L);

        // Diagnostic setup
        lenient().when(testDiagnostic.getDiagnosticId()).thenReturn(10L);
        lenient().when(testDiagnostic.getDrafterId()).thenReturn(1L);
        lenient().when(testDiagnostic.getCompany()).thenReturn(testCompany);
        lenient().when(testDiagnostic.getStatus()).thenReturn(DiagnosticStatus.WRITING);

        // EvidenceFile setup
        lenient().when(testEvidenceFile.getResultFileId()).thenReturn(200L);
        lenient().when(testEvidenceFile.getOriginalFileName()).thenReturn("test.pdf");
        lenient().when(testEvidenceFile.getFileSize()).thenReturn(1024L);
        lenient().when(testEvidenceFile.getFilePath()).thenReturn("diagnostics/10/abc_test.pdf");
        lenient().when(testEvidenceFile.getParsingStatus()).thenReturn(ParsingStatus.SUCCESS);
        lenient().when(testEvidenceFile.getValNum()).thenReturn(new BigDecimal("123.45"));
        lenient().when(testEvidenceFile.getValText()).thenReturn("Parsed text");
        lenient().when(testEvidenceFile.getDiagnostic()).thenReturn(testDiagnostic);
    }

    @Nested
    @DisplayName("파일 목록 조회 테스트")
    class GetFileListTest {

        @Test
        @DisplayName("DRAFTER가 파일 목록 조회 성공")
        void getFileList_Success() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));
            given(evidenceFileRepository.findByDiagnosticId(10L))
                    .willReturn(List.of(testEvidenceFile));

            // when
            List<EvidenceFileDto> result = fileService.getFileList(1L, 10L);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getFileId()).isEqualTo(200L);
            assertThat(result.get(0).getFileName()).isEqualTo("test.pdf");
            assertThat(result.get(0).getParsingStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("파일이 없는 진단의 목록 조회 시 빈 리스트 반환")
        void getFileList_Empty() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));
            given(evidenceFileRepository.findByDiagnosticId(10L)).willReturn(List.of());

            // when
            List<EvidenceFileDto> result = fileService.getFileList(1L, 10L);

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("파일 업로드 테스트")
    class UploadFileTest {

        @Test
        @DisplayName("DRAFTER가 파일 업로드 성공")
        void uploadFile_Success() {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "test content".getBytes());

            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));
            // Return the argument directly since EvidenceFile generates its own ID via JPA
            given(evidenceFileRepository.save(any(EvidenceFile.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(asyncJobRepository.save(any(AsyncJob.class))).willAnswer(invocation -> invocation.getArgument(0));

            // when
            FileUploadResponse response = fileService.uploadFile(1L, 10L, file);

            // then
            assertThat(response).isNotNull();
            assertThat(response.getOriginalFileName()).isEqualTo("test.pdf");
            assertThat(response.getMimeType()).isEqualTo("application/pdf");
            assertThat(response.getJobId()).isNotNull();
            assertThat(response.getJobId()).startsWith("job_parse_");
            verify(fileStorageService).upload(eq(file), any(String.class));
            verify(evidenceFileRepository).save(any(EvidenceFile.class));
            verify(asyncJobRepository).save(any(AsyncJob.class));
        }

        @Test
        @DisplayName("파일 크기 초과 시 실패")
        void uploadFile_FileSizeExceeded_ThrowsException() {
            // given
            byte[] largeContent = new byte[51 * 1024 * 1024]; // 51MB
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", largeContent);

            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));

            // when & then
            assertThatThrownBy(() -> fileService.uploadFile(1L, 10L, file))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException ce = (CustomException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.FILE_SIZE_EXCEEDED);
                    });
        }

        @Test
        @DisplayName("제출된 진단에 파일 업로드 시 실패")
        void uploadFile_SubmittedDiagnostic_ThrowsException() {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "test content".getBytes());

            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));
            given(testDiagnostic.getStatus()).willReturn(DiagnosticStatus.SUBMITTED);

            // when & then
            assertThatThrownBy(() -> fileService.uploadFile(1L, 10L, file))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException ce = (CustomException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.DIAGNOSTIC_INVALID_STATE_TRANSITION);
                    });
        }

        @Test
        @DisplayName("지원하지 않는 파일 형식 시 실패")
        void uploadFile_InvalidFileType_ThrowsException() {
            // given
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.exe", "application/octet-stream", "test content".getBytes());

            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));

            // when & then
            assertThatThrownBy(() -> fileService.uploadFile(1L, 10L, file))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException ce = (CustomException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.INVALID_FILE_TYPE);
                    });
        }
    }

    @Nested
    @DisplayName("파싱 결과 조회 테스트")
    class GetParsingResultTest {

        @Test
        @DisplayName("파싱 결과 조회 성공")
        void getParsingResult_Success() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));
            given(evidenceFileRepository.findByIdAndDiagnosticId(200L, 10L))
                    .willReturn(Optional.of(testEvidenceFile));

            // when
            FileParsingResultResponse response = fileService.getParsingResult(1L, 10L, 200L);

            // then
            assertThat(response).isNotNull();
            assertThat(response.getFileId()).isEqualTo(200L);
            assertThat(response.getParsingStatus()).isEqualTo("SUCCESS");
            assertThat(response.getParsedValue()).isEqualTo(new BigDecimal("123.45"));
        }

        @Test
        @DisplayName("파일이 없을 때 실패")
        void getParsingResult_FileNotFound_ThrowsException() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));
            given(evidenceFileRepository.findByIdAndDiagnosticId(999L, 10L))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> fileService.getParsingResult(1L, 10L, 999L))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException ce = (CustomException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND);
                    });
        }
    }

    @Nested
    @DisplayName("파일 다운로드 URL 발급 테스트")
    class GetDownloadUrlTest {

        @Test
        @DisplayName("다운로드 URL 발급 시 FileStorageService의 presignedUrl을 사용한다")
        void getDownloadUrl_Success() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(evidenceFileRepository.findById(200L)).willReturn(Optional.of(testEvidenceFile));
            given(fileStorageService.getPresignedUrl(eq("diagnostics/10/abc_test.pdf"), any()))
                    .willReturn("https://storage.blob.core.windows.net/files/diagnostics/10/abc_test.pdf?sig=real");

            // when
            FileDownloadUrlResponse response = fileService.getDownloadUrl(1L, 200L);

            // then
            assertThat(response).isNotNull();
            assertThat(response.getDownloadUrl()).contains("sig=real");
            assertThat(response.getFileName()).isEqualTo("test.pdf");
            assertThat(response.getExpiresAt()).isNotNull();
            verify(fileStorageService).getPresignedUrl(eq("diagnostics/10/abc_test.pdf"), any());
        }
    }

    @Nested
    @DisplayName("파일 삭제 테스트")
    class DeleteFileTest {

        @Test
        @DisplayName("파일 삭제 성공")
        void deleteFile_Success() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(evidenceFileRepository.findById(200L)).willReturn(Optional.of(testEvidenceFile));

            // when
            FileDeleteResponse response = fileService.deleteFile(1L, 200L);

            // then
            assertThat(response).isNotNull();
            assertThat(response.getFileId()).isEqualTo(200L);
            assertThat(response.getMessage()).isEqualTo("파일이 삭제되었습니다");
            verify(fileStorageService).delete("diagnostics/10/abc_test.pdf");
            verify(evidenceFileRepository).delete(testEvidenceFile);
        }

        @Test
        @DisplayName("제출된 진단의 파일 삭제 시 실패")
        void deleteFile_SubmittedDiagnostic_ThrowsException() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));
            given(evidenceFileRepository.findById(200L)).willReturn(Optional.of(testEvidenceFile));
            given(testDiagnostic.getStatus()).willReturn(DiagnosticStatus.SUBMITTED);

            // when & then
            assertThatThrownBy(() -> fileService.deleteFile(1L, 200L))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException ce = (CustomException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.DIAGNOSTIC_INVALID_STATE_TRANSITION);
                    });
        }
    }

    @Nested
    @DisplayName("데이터 패키지 URL 발급 테스트")
    class GetPackageUrlTest {

        @Test
        @DisplayName("APPROVER가 패키지 URL 발급 성공")
        void getPackageUrl_Success() {
            // given
            given(userRepository.findById(2L)).willReturn(Optional.of(approverUser));
            given(diagnosticRepository.findById(10L)).willReturn(Optional.of(testDiagnostic));
            given(evidenceFileRepository.findByDiagnosticId(10L))
                    .willReturn(List.of(testEvidenceFile));
            given(fileStorageService.getPresignedUrl(any(), any()))
                    .willReturn("https://storage.blob.core.windows.net/packages/diagnostic_10.zip?sig=test");

            // when
            DataPackageUrlResponse response = fileService.getPackageUrl(2L, 10L);

            // then
            assertThat(response).isNotNull();
            assertThat(response.getDownloadUrl()).isNotNull();
            assertThat(response.getManifest()).isNotNull();
            assertThat(response.getManifest().getTotalFiles()).isEqualTo(1);
        }

        @Test
        @DisplayName("DRAFTER가 패키지 URL 요청 시 실패")
        void getPackageUrl_DrafterNotAllowed_ThrowsException() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(drafterUser));

            // when & then
            assertThatThrownBy(() -> fileService.getPackageUrl(1L, 10L))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> {
                        CustomException ce = (CustomException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED_ACTION);
                    });
        }
    }
}
