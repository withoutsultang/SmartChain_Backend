package com.smartchain.platform.domain.diagnostic.entity;

import com.smartchain.platform.domain.user.entity.Company;
import com.smartchain.platform.domain.user.entity.Domain;
import com.smartchain.platform.global.entity.BaseTimeEntity;
import com.smartchain.platform.global.enums.DiagnosticStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Diagnostic extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long diagnosticId;

    @Column(unique = true)
    private String diagnosticCode;

    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_id")
    private Domain domain;

    private Long drafterId;
    private Long approverId;
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiagnosticStatus status;

    private int qualitativeProgress;
    private int quantitativeProgress;
    private Integer overallScore;

    private LocalDate periodStartDate;
    private LocalDate periodEndDate;
    private LocalDate deadline;

    private LocalDateTime submittedAt;

    private String previewPackageId;

    // Durable analysis attempt, distinct from append-only analysis result history.
    private String aiRunRequestId;
    private String aiRunStatus = "IDLE";
    private String aiRunErrorCode;
    private Long aiRunResultId;
    private LocalDateTime aiRunExpiresAt;

    public boolean isAiRunActive() {
        return "PENDING".equals(aiRunStatus) || "RUNNING".equals(aiRunStatus);
    }

    public boolean isAiRunExpired() {
        return isAiRunActive() && aiRunExpiresAt != null && !aiRunExpiresAt.isAfter(LocalDateTime.now());
    }

    public boolean ownsAiRun(String requestId) {
        return requestId != null && requestId.equals(aiRunRequestId) && isAiRunActive() && !isAiRunExpired();
    }

    public void beginAiRun(String requestId, LocalDateTime expiresAt) {
        aiRunRequestId = requestId;
        aiRunStatus = "PENDING";
        aiRunErrorCode = null;
        aiRunResultId = null;
        aiRunExpiresAt = expiresAt;
    }

    public void startAiRun() { aiRunStatus = "RUNNING"; }
    public void failAiRun(String code) { aiRunStatus = "FAILED"; aiRunErrorCode = code; }
    public void completeAiRun(Long resultId) {
        aiRunStatus = "SUCCEEDED";
        aiRunErrorCode = null;
        aiRunResultId = resultId;
    }


    @Builder
    public Diagnostic(String diagnosticCode, String title, Campaign campaign, Company company,
                      Domain domain, Long drafterId, LocalDate periodStartDate, LocalDate periodEndDate, LocalDate deadline) {
        this.diagnosticCode = diagnosticCode;
        this.title = title;
        this.campaign = campaign;
        this.company = company;
        this.domain = domain;
        this.drafterId = drafterId;
        this.periodStartDate = periodStartDate;
        this.periodEndDate = periodEndDate;
        this.deadline = deadline;
        this.status = DiagnosticStatus.WRITING;
        this.qualitativeProgress = 0;
        this.quantitativeProgress = 0;
    }

    public void updateStatus(DiagnosticStatus newStatus) {
        this.status = newStatus;
    }

    public void submit() {
        this.status = DiagnosticStatus.SUBMITTED;
        this.submittedAt = LocalDateTime.now();
    }

    public void approve() {
        this.status = DiagnosticStatus.APPROVED;
    }

    public void returnForRevision() {
        this.status = DiagnosticStatus.RETURNED;
    }

    public void startReview() {
        this.status = DiagnosticStatus.REVIEWING;
    }

    public void complete() {
        this.status = DiagnosticStatus.COMPLETED;
    }

    public boolean canSubmit() {
        return this.status == DiagnosticStatus.WRITING || this.status == DiagnosticStatus.RETURNED;
    }

    public int getOverallProgress() {
        return (qualitativeProgress + quantitativeProgress) / 2;
    }

    public void markAsApproved() {
        this.status = DiagnosticStatus.APPROVED;
    }

    public void markAsReturned() {
        this.status = DiagnosticStatus.RETURNED;
    }

    public void markAsReviewing() {
        this.status = DiagnosticStatus.REVIEWING;
    }

    public boolean isApproved() {
        return this.status == DiagnosticStatus.APPROVED;
    }

    public void updatePreviewPackageId(String previewPackageId) {
        this.previewPackageId = previewPackageId;
    }
}