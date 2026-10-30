package com.smartchain.platform.domain.file.service;

import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;
import com.smartchain.platform.domain.diagnostic.repository.DiagnosticRepository;
import com.smartchain.platform.domain.evidence.entity.EvidenceFile;
import com.smartchain.platform.domain.evidence.repository.EvidenceFileRepository;
import com.smartchain.platform.domain.file.storage.FileStorageService;
import com.smartchain.platform.domain.job.entity.AsyncJob;
import com.smartchain.platform.domain.job.repository.AsyncJobRepository;
import com.smartchain.platform.domain.job.service.FileParsingJobService;
import com.smartchain.platform.domain.user.entity.User;
import com.smartchain.platform.domain.user.repository.UserRepository;
import com.smartchain.platform.dto.common.file.*;
import com.smartchain.platform.global.enums.DiagnosticStatus;
import com.smartchain.platform.global.enums.JobType;
import com.smartchain.platform.global.enums.ParsingStatus;
import com.smartchain.platform.global.error.CustomException;
import com.smartchain.platform.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FileService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private final UserRepository userRepository;
    private final DiagnosticRepository diagnosticRepository;
    private final EvidenceFileRepository evidenceFileRepository;
    private final AsyncJobRepository asyncJobRepository;
    private final FileStorageService fileStorageService;
    private final FileParsingJobService fileParsingJobService;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "image/png",
            "image/jpeg",
            "image/jpg"
    );

    /**
     * 파일 업로드 (비동기 파싱)
     */
    @Transactional
    public FileUploadResponse uploadFile(Long userId, Long diagnosticId, MultipartFile file) {
        User user = validateUser(userId);
        validateDrafterRole(user);

        Diagnostic diagnostic = diagnosticRepository.findById(diagnosticId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSTIC_NOT_FOUND));

        validateDiagnosticAccess(user, diagnostic);

        ensureNoActiveAnalysis(diagnosticId);

        // 제출 이후 상태에서는 파일 업로드 불가
        if (isSubmittedOrLater(diagnostic.getStatus())) {
            throw new CustomException(ErrorCode.DIAGNOSTIC_INVALID_STATE_TRANSITION);
        }

        validateFileForUpload(file);

        // 파일 저장소에 업로드
        String filePath = generateFilePath(diagnosticId, file.getOriginalFilename());
        fileStorageService.upload(file, filePath);

        EvidenceFile evidenceFile = EvidenceFile.builder()
                .diagnostic(diagnostic)
                .filePath(filePath)
                .originalFileName(file.getOriginalFilename())
                .fileSize(file.getSize())
                .mimeType(file.getContentType())
                .build();

        EvidenceFile savedFile = evidenceFileRepository.save(evidenceFile);

        // 비동기 파싱 작업 생성
        AsyncJob parsingJob = AsyncJob.builder()
                .jobType(JobType.FILE_PARSING)
                .requesterId(userId)
                .targetId(savedFile.getResultFileId())
                .estimatedSeconds(30)
                .build();

        AsyncJob savedJob = asyncJobRepository.save(parsingJob);

        // 트랜잭션 커밋 후 비동기 파싱 실행 (커밋 전에 실행하면 DB에서 job을 찾을 수 없음)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileParsingJobService.executeParsingAsync(savedJob.getJobId());
                }
            });
        } else {
            fileParsingJobService.executeParsingAsync(savedJob.getJobId());
        }

        log.info("File uploaded: fileId={}, diagnosticId={}, uploadedBy={}",
                savedFile.getResultFileId(), diagnosticId, userId);

        return FileUploadResponse.builder()
                .fileId(savedFile.getResultFileId())
                .fileName(filePath)
                .originalFileName(file.getOriginalFilename())
                .fileUrl("/api/v1/files/" + savedFile.getResultFileId() + "/download-url")
                .fileType(getFileType(file.getOriginalFilename()))
                .mimeType(file.getContentType())
                .fileSize(file.getSize())
                .fileSizeLabel(formatFileSize(file.getSize()))
                .uploadStatus("UPLOADING")
                .uploadedAt(LocalDateTime.now())
                .jobId(savedJob.getJobId())
                .statusCheckUrl(savedJob.getStatusCheckUrl())
                .build();
    }

    /**
     * 진단별 파일 목록 조회
     */
    public List<EvidenceFileDto> getFileList(Long userId, Long diagnosticId) {
        User user = validateUser(userId);

        Diagnostic diagnostic = diagnosticRepository.findById(diagnosticId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSTIC_NOT_FOUND));

        validateDiagnosticAccess(user, diagnostic);

        List<EvidenceFile> files = evidenceFileRepository.findByDiagnosticId(diagnosticId);

        return files.stream()
                .map(this::toEvidenceFileDto)
                .collect(Collectors.toList());
    }

    private EvidenceFileDto toEvidenceFileDto(EvidenceFile file) {
        return EvidenceFileDto.builder()
                .fileId(file.getResultFileId())
                .fileName(file.getOriginalFileName())
                .fileUrl("/api/v1/files/" + file.getResultFileId() + "/download-url")
                .fileType(getFileType(file.getOriginalFileName()))
                .fileSize(file.getFileSize())
                .parsingStatus(file.getParsingStatus() != null ? file.getParsingStatus().name() : null)
                .parsedDataUrl("/api/v1/diagnostics/" + file.getDiagnostic().getDiagnosticId()
                        + "/files/" + file.getResultFileId() + "/parsing-result")
                .uploadedAt(file.getCreatedAt())
                .build();
    }

    /**
     * 파싱 결과 조회
     */
    public FileParsingResultResponse getParsingResult(Long userId, Long diagnosticId, Long fileId) {
        User user = validateUser(userId);
        validateDrafterOrApproverRole(user);

        Diagnostic diagnostic = diagnosticRepository.findById(diagnosticId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSTIC_NOT_FOUND));

        validateDiagnosticAccess(user, diagnostic);

        EvidenceFile evidenceFile = evidenceFileRepository.findByIdAndDiagnosticId(fileId, diagnosticId)
                .orElseThrow(() -> new CustomException(ErrorCode.FILE_NOT_FOUND));

        return FileParsingResultResponse.builder()
                .fileId(evidenceFile.getResultFileId())
                .fileName(evidenceFile.getOriginalFileName())
                .parsingStatus(evidenceFile.getParsingStatus().name())
                .parsedValue(evidenceFile.getValNum())
                .parsedText(evidenceFile.getValText())
                .metaInfo(evidenceFile.getParsingMetaInfo())
                .errorMessage(evidenceFile.getParsingStatus() == ParsingStatus.FAILED
                        ? evidenceFile.getParsingMetaInfo() : null)
                .completedAt(evidenceFile.getParsingStatus() == ParsingStatus.SUCCESS
                        || evidenceFile.getParsingStatus() == ParsingStatus.FAILED
                        ? evidenceFile.getUpdatedAt() : null)
                .build();
    }

    /**
     * 파일 다운로드 URL 발급 (Signed URL, 30분 유효)
     */
    public FileDownloadUrlResponse getDownloadUrl(Long userId, Long fileId) {
        validateUser(userId);

        EvidenceFile evidenceFile = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new CustomException(ErrorCode.FILE_NOT_FOUND));

        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        String signedUrl = fileStorageService.getPresignedUrl(evidenceFile.getFilePath(), Duration.ofMinutes(30));

        // local 환경: 상대 경로 반환 시 직접 다운로드 엔드포인트로 교체
        if (signedUrl.startsWith("/")) {
            signedUrl = "/api/v1/files/" + fileId + "/download";
        }

        log.info("Download URL generated: fileId={}, requestedBy={}, expiresAt={}",
                fileId, userId, expiresAt);

        return FileDownloadUrlResponse.builder()
                .downloadUrl(signedUrl)
                .fileName(evidenceFile.getOriginalFileName())
                .fileSize(evidenceFile.getFileSize())
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * 파일 직접 다운로드 (local 환경용)
     */
    public FileDownloadData downloadFile(Long userId, Long fileId) {
        validateUser(userId);

        EvidenceFile evidenceFile = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new CustomException(ErrorCode.FILE_NOT_FOUND));

        InputStream inputStream = fileStorageService.download(evidenceFile.getFilePath());

        log.info("File download: fileId={}, requestedBy={}", fileId, userId);

        return new FileDownloadData(
                inputStream,
                evidenceFile.getOriginalFileName(),
                evidenceFile.getMimeType(),
                evidenceFile.getFileSize()
        );
    }

    public record FileDownloadData(
            InputStream inputStream,
            String fileName,
            String mimeType,
            Long fileSize
    ) {}

    /**
     * 파일 삭제
     */
    @Transactional
    public FileDeleteResponse deleteFile(Long userId, Long fileId) {
        User user = validateUser(userId);

        EvidenceFile evidenceFile = evidenceFileRepository.findById(fileId)
                .orElseThrow(() -> new CustomException(ErrorCode.FILE_NOT_FOUND));

        Diagnostic diagnostic = evidenceFile.getDiagnostic();
        if (diagnostic != null) {
            validateDiagnosticAccess(user, diagnostic);

            // 제출 후 삭제 방지
            if (isSubmittedOrLater(diagnostic.getStatus())) {
                throw new CustomException(ErrorCode.DIAGNOSTIC_INVALID_STATE_TRANSITION);
            }
        }

        if (evidenceFile.getDiagnostic() != null) ensureNoActiveAnalysis(evidenceFile.getDiagnostic().getDiagnosticId());
        fileStorageService.delete(evidenceFile.getFilePath());
        evidenceFileRepository.delete(evidenceFile);

        log.info("File deleted: fileId={}, deletedBy={}", fileId, userId);

        return FileDeleteResponse.builder()
                .fileId(fileId)
                .message("파일이 삭제되었습니다")
                .deletedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 데이터 패키지 다운로드 URL 발급
     */
    public DataPackageUrlResponse getPackageUrl(Long userId, Long diagnosticId) {
        User user = validateUser(userId);
        validateApproverOrReviewerRole(user);

        Diagnostic diagnostic = diagnosticRepository.findById(diagnosticId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSTIC_NOT_FOUND));

        List<EvidenceFile> files = evidenceFileRepository.findByDiagnosticId(diagnosticId);

        // 패키지 매니페스트 생성
        List<String> contents = files.stream()
                .map(EvidenceFile::getOriginalFileName)
                .collect(Collectors.toList());

        long totalSize = files.stream()
                .mapToLong(EvidenceFile::getFileSize)
                .sum();

        PackageManifestDto manifest = PackageManifestDto.builder()
                .totalFiles(files.size())
                .contents(contents)
                .build();

        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        String packagePath = "packages/diagnostic_" + diagnosticId + ".zip";
        String packageUrl = fileStorageService.getPresignedUrl(packagePath, Duration.ofMinutes(30));

        log.info("Package URL generated: diagnosticId={}, fileCount={}, requestedBy={}",
                diagnosticId, files.size(), userId);

        return DataPackageUrlResponse.builder()
                .downloadUrl(packageUrl)
                .fileName("diagnostic_" + diagnosticId + "_package.zip")
                .fileSize(totalSize)
                .expiresAt(expiresAt)
                .manifest(manifest)
                .build();
    }

    private void ensureNoActiveAnalysis(Long diagnosticId) {
        Diagnostic locked = diagnosticRepository.findForUpdate(diagnosticId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSTIC_NOT_FOUND));
        entityManager.refresh(locked, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (locked.isAiRunExpired()) locked.failAiRun(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode());
        if (locked.isAiRunActive()) throw new CustomException(ErrorCode.AI_ANALYSIS_IN_PROGRESS);
    }

    // ========== Private Helper Methods ==========

    private User validateUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    private void validateDrafterRole(User user) {
        String roleCode = user.getRole() != null ? user.getRole().getCode() : "GUEST";
        if (!"DRAFTER".equals(roleCode) && !"APPROVER".equals(roleCode) && !"REVIEWER".equals(roleCode)) {
            throw new CustomException(ErrorCode.PERMISSION_DENIED_ACTION);
        }
    }

    private void validateDrafterOrApproverRole(User user) {
        String roleCode = user.getRole() != null ? user.getRole().getCode() : "GUEST";
        if (!"DRAFTER".equals(roleCode) && !"APPROVER".equals(roleCode) && !"REVIEWER".equals(roleCode)) {
            throw new CustomException(ErrorCode.PERMISSION_DENIED_ACTION);
        }
    }

    private void validateApproverOrReviewerRole(User user) {
        String roleCode = user.getRole() != null ? user.getRole().getCode() : "GUEST";
        if (!"APPROVER".equals(roleCode) && !"REVIEWER".equals(roleCode)) {
            throw new CustomException(ErrorCode.PERMISSION_DENIED_ACTION);
        }
    }

    private void validateDiagnosticAccess(User user, Diagnostic diagnostic) {
        // DRAFTER는 본인 기안만 접근 가능
        String roleCode = user.getRole() != null ? user.getRole().getCode() : "GUEST";
        if ("DRAFTER".equals(roleCode)) {
            if (!diagnostic.getDrafterId().equals(user.getUserId())) {
                throw new CustomException(ErrorCode.PERMISSION_DENIED_RESOURCE);
            }
        }
        // APPROVER, REVIEWER는 소속 회사 기안 접근 가능
        else if ("APPROVER".equals(roleCode)) {
            if (user.getCompany() == null || !user.getCompany().equals(diagnostic.getCompany())) {
                throw new CustomException(ErrorCode.PERMISSION_DENIED_RESOURCE);
            }
        }
    }

    private void validateFileForUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new CustomException(ErrorCode.FILE_SIZE_EXCEEDED);
        }

        if (!ALLOWED_MIME_TYPES.contains(file.getContentType())) {
            throw new CustomException(ErrorCode.INVALID_FILE_TYPE);
        }
    }

    private boolean isSubmittedOrLater(DiagnosticStatus status) {
        return status == DiagnosticStatus.SUBMITTED
                || status == DiagnosticStatus.APPROVED
                || status == DiagnosticStatus.REVIEWING
                || status == DiagnosticStatus.COMPLETED;
    }

    private String generateFilePath(Long diagnosticId, String originalFileName) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return "diagnostics/" + diagnosticId + "/" + uuid + "_" + originalFileName;
    }

    private String getFileType(String fileName) {
        if (fileName == null) return "UNKNOWN";
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase();
        return switch (extension) {
            case "PDF" -> "PDF";
            case "XLS", "XLSX" -> "XLSX";
            case "CSV" -> "CSV";
            case "DOC", "DOCX" -> "DOCX";
            case "PNG" -> "PNG";
            case "JPG", "JPEG" -> "JPG";
            default -> "UNKNOWN";
        };
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }
}
