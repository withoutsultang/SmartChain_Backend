package com.smartchain.platform.domain.diagnostic.repository;

import com.smartchain.platform.domain.diagnostic.entity.Campaign;
import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;
import com.smartchain.platform.domain.user.entity.Company;
import com.smartchain.platform.domain.user.entity.Domain;
import com.smartchain.platform.global.enums.DiagnosticStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DiagnosticRepository extends JpaRepository<Diagnostic, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Diagnostic d where d.diagnosticId = :id")
    Optional<Diagnostic> findForUpdate(@Param("id") Long id);

    Optional<Diagnostic> findByDiagnosticCode(String diagnosticCode);

    Page<Diagnostic> findByCompanyOrderByCreatedAtDesc(Company company, Pageable pageable);

    Page<Diagnostic> findByDrafterIdOrderByCreatedAtDesc(Long drafterId, Pageable pageable);

    @Query("SELECT d FROM Diagnostic d WHERE d.company = :company AND d.status IN :statuses ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByCompanyAndStatusInOrderByCreatedAtDesc(
            @Param("company") Company company,
            @Param("statuses") List<DiagnosticStatus> statuses,
            Pageable pageable);

    @Query("SELECT d FROM Diagnostic d WHERE d.drafterId = :drafterId AND d.status IN :statuses ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByDrafterIdAndStatusInOrderByCreatedAtDesc(
            @Param("drafterId") Long drafterId,
            @Param("statuses") List<DiagnosticStatus> statuses,
            Pageable pageable);

    @Query("SELECT d FROM Diagnostic d WHERE d.company = :company AND d.deadline BETWEEN :fromDate AND :toDate ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByCompanyAndDeadlineBetweenOrderByCreatedAtDesc(
            @Param("company") Company company,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable);

    @Query("SELECT COUNT(d) FROM Diagnostic d WHERE d.company = :company")
    long countByCompany(@Param("company") Company company);

    @Query(value = "SELECT nextval('diagnostic_code_seq')", nativeQuery = true)
    Long getNextDiagnosticCodeSequence();

    Page<Diagnostic> findByDomainOrderByCreatedAtDesc(Domain domain, Pageable pageable);

    List<Diagnostic> findByDomain(Domain domain);

    List<Diagnostic> findByCampaign(Campaign campaign);

    // 도메인 기반 권한 조회 (DRAFTER: 본인 기안만)
    @Query("SELECT d FROM Diagnostic d WHERE d.domain IN :domains AND d.drafterId = :drafterId ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByDomainInAndDrafterIdOrderByCreatedAtDesc(
            @Param("domains") List<Domain> domains,
            @Param("drafterId") Long drafterId,
            Pageable pageable);

    // 도메인 기반 권한 조회 (APPROVER: 같은 회사 기안)
    @Query("SELECT d FROM Diagnostic d WHERE d.domain IN :domains AND d.company = :company ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByDomainInAndCompanyOrderByCreatedAtDesc(
            @Param("domains") List<Domain> domains,
            @Param("company") Company company,
            Pageable pageable);

    // 도메인 기반 권한 조회 (REVIEWER: 해당 도메인 모든 기안)
    @Query("SELECT d FROM Diagnostic d WHERE d.domain IN :domains ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByDomainInOrderByCreatedAtDesc(
            @Param("domains") List<Domain> domains,
            Pageable pageable);

    // 도메인 기반 복합 조회 (여러 역할의 도메인을 한번에 처리) — 레거시 호환용
    @Query("SELECT DISTINCT d FROM Diagnostic d WHERE " +
           "(:hasReviewer = true AND d.domain IN :reviewerDomains) OR " +
           "(:hasApprover = true AND d.domain IN :approverDomains AND d.company = :company) OR " +
           "(:hasDrafter = true AND d.domain IN :drafterDomains AND d.drafterId = :drafterId) " +
           "ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByUserDomainRoles(
            @Param("reviewerDomains") List<Domain> reviewerDomains,
            @Param("approverDomains") List<Domain> approverDomains,
            @Param("drafterDomains") List<Domain> drafterDomains,
            @Param("hasReviewer") boolean hasReviewer,
            @Param("hasApprover") boolean hasApprover,
            @Param("hasDrafter") boolean hasDrafter,
            @Param("company") Company company,
            @Param("drafterId") Long drafterId,
            Pageable pageable);

    // 통합 필터링 쿼리 (status + keyword + domain 권한 + deadline)
    @Query("SELECT DISTINCT d FROM Diagnostic d LEFT JOIN d.company c WHERE " +
           "(" +
           "  (:hasReviewer = true AND d.domain IN :reviewerDomains) OR " +
           "  (:hasApprover = true AND d.domain IN :approverDomains AND d.company = :company) OR " +
           "  (:hasDrafter = true AND d.domain IN :drafterDomains AND d.drafterId = :drafterId)" +
           ") " +
           "AND (:hasStatuses = false OR d.status IN :statuses) " +
           "AND (:keyword IS NULL OR d.title LIKE %:keyword% OR d.diagnosticCode LIKE %:keyword% OR c.name LIKE %:keyword%) " +
           "AND (:deadlineFrom IS NULL OR d.deadline >= :deadlineFrom) " +
           "AND (:deadlineTo IS NULL OR d.deadline <= :deadlineTo) " +
           "ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByFilters(
            @Param("reviewerDomains") List<Domain> reviewerDomains,
            @Param("approverDomains") List<Domain> approverDomains,
            @Param("drafterDomains") List<Domain> drafterDomains,
            @Param("hasReviewer") boolean hasReviewer,
            @Param("hasApprover") boolean hasApprover,
            @Param("hasDrafter") boolean hasDrafter,
            @Param("company") Company company,
            @Param("drafterId") Long drafterId,
            @Param("hasStatuses") boolean hasStatuses,
            @Param("statuses") List<DiagnosticStatus> statuses,
            @Param("keyword") String keyword,
            @Param("deadlineFrom") LocalDate deadlineFrom,
            @Param("deadlineTo") LocalDate deadlineTo,
            Pageable pageable);

    // 레거시 DRAFTER용 통합 필터링 (본인 기안만 조회)
    @Query("SELECT d FROM Diagnostic d LEFT JOIN d.company c WHERE " +
           "d.drafterId = :drafterId " +
           "AND (:hasDomain = false OR d.domain = :domain) " +
           "AND (:hasStatuses = false OR d.status IN :statuses) " +
           "AND (:keyword IS NULL OR d.title LIKE %:keyword% OR d.diagnosticCode LIKE %:keyword% OR c.name LIKE %:keyword%) " +
           "AND (:deadlineFrom IS NULL OR d.deadline >= :deadlineFrom) " +
           "AND (:deadlineTo IS NULL OR d.deadline <= :deadlineTo) " +
           "ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByDrafterIdAndFilters(
            @Param("drafterId") Long drafterId,
            @Param("hasDomain") boolean hasDomain,
            @Param("domain") Domain domain,
            @Param("hasStatuses") boolean hasStatuses,
            @Param("statuses") List<DiagnosticStatus> statuses,
            @Param("keyword") String keyword,
            @Param("deadlineFrom") LocalDate deadlineFrom,
            @Param("deadlineTo") LocalDate deadlineTo,
            Pageable pageable);

    // 레거시 통합 필터링 (도메인 역할 없는 APPROVER용 — 회사 전체)
    @Query("SELECT d FROM Diagnostic d LEFT JOIN d.company c WHERE " +
           "d.company = :company " +
           "AND (:hasDomain = false OR d.domain = :domain) " +
           "AND (:hasStatuses = false OR d.status IN :statuses) " +
           "AND (:keyword IS NULL OR d.title LIKE %:keyword% OR d.diagnosticCode LIKE %:keyword% OR c.name LIKE %:keyword%) " +
           "AND (:deadlineFrom IS NULL OR d.deadline >= :deadlineFrom) " +
           "AND (:deadlineTo IS NULL OR d.deadline <= :deadlineTo) " +
           "ORDER BY d.createdAt DESC")
    Page<Diagnostic> findByCompanyAndFilters(
            @Param("company") Company company,
            @Param("hasDomain") boolean hasDomain,
            @Param("domain") Domain domain,
            @Param("hasStatuses") boolean hasStatuses,
            @Param("statuses") List<DiagnosticStatus> statuses,
            @Param("keyword") String keyword,
            @Param("deadlineFrom") LocalDate deadlineFrom,
            @Param("deadlineTo") LocalDate deadlineTo,
            Pageable pageable);
}
