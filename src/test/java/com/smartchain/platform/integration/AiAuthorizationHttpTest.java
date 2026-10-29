package com.smartchain.platform.integration;

import com.smartchain.platform.domain.ai.client.AiRunApiClient;
import com.smartchain.platform.domain.ai.entity.AiAnalysisResult;
import com.smartchain.platform.domain.ai.repository.AiAnalysisResultRepository;
import com.smartchain.platform.domain.ai.service.AiAnalysisAsyncService;
import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;
import com.smartchain.platform.domain.diagnostic.repository.DiagnosticRepository;
import com.smartchain.platform.domain.evidence.entity.EvidenceFile;
import com.smartchain.platform.domain.evidence.repository.EvidenceFileRepository;
import com.smartchain.platform.domain.user.entity.*;
import com.smartchain.platform.domain.user.repository.*;
import com.smartchain.platform.dto.ai.run.RunPreviewResponse;
import com.smartchain.platform.global.security.JwtTokenProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real TCP HTTP + JWT + persisted fixtures. Only the outbound AI boundary is mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.sql.init.mode=never", "app.data-initializer.enabled=false"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiAuthorizationHttpTest {
    private static final boolean BASELINE = Boolean.parseBoolean(System.getenv("AUTHORIZATION_EXPECT_VULNERABLE"));
    private static final List<String> ENDPOINTS = List.of("/preview", "/submit", "/result", "/result/detail", "/history");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("AUTHORIZATION_DATABASE_URL");
        boolean postgres = url != null && !url.isBlank();
        p.add("spring.datasource.url", () -> postgres ? url : "jdbc:h2:mem:ai_authorization;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        p.add("spring.datasource.username", () -> postgres ? "audit" : "sa");
        p.add("spring.datasource.password", () -> postgres ? "isolated-audit-only" : "");
        p.add("spring.datasource.driver-class-name", () -> postgres ? "org.postgresql.Driver" : "org.h2.Driver");
        p.add("spring.jpa.properties.hibernate.dialect", () -> postgres ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.H2Dialect");
    }

    @Autowired TestRestTemplate http;
    @Autowired JwtTokenProvider jwt;
    @Autowired TransactionTemplate transactions;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired CompanyRepository companies;
    @Autowired DomainRepository domains;
    @Autowired DiagnosticRepository diagnostics;
    @Autowired EvidenceFileRepository files;
    @Autowired AiAnalysisResultRepository results;
    @MockitoBean AiRunApiClient ai;
    @MockitoBean AiAnalysisAsyncService async;

    final Map<String, String> tokens = new HashMap<>();
    Long diagnosticId, ownFileId, foreignFileId;

    @BeforeAll
    void fixtures() {
        transactions.executeWithoutResult(status -> {
            Role drafter = roles.save(new Role("기안자", "DRAFTER"));
            Role approver = roles.save(new Role("결재자", "APPROVER"));
            Role reviewer = roles.save(new Role("수신자", "REVIEWER"));
            Role guest = roles.save(new Role("게스트", "GUEST"));
            Domain esg = domains.save(Domain.builder().code("ESG").name("ESG").build());
            Domain safety = domains.save(Domain.builder().code("SAFETY").name("안전").build());
            Company a = companies.save(Company.builder().name("격리 검증 A사").build());
            Company b = companies.save(Company.builder().name("격리 검증 B사").build());
            Company ownerCompany = companies.save(Company.builder().name("격리 검증 원청").build());
            User owner = user("owner", a, drafter, esg);
            User foreignOwner = user("foreign-owner", b, drafter, esg);
            user("other-drafter", a, drafter, esg);
            user("foreign-company", b, approver, esg);
            user("wrong-domain", a, approver, safety);
            user("wrong-domain-reviewer", ownerCompany, reviewer, safety);
            user("approver", a, approver, esg);
            user("reviewer", ownerCompany, reviewer, esg);
            user("guest", a, guest, null);
            Diagnostic target = diagnostics.save(diagnostic("AUTH-TARGET", a, esg, owner));
            Diagnostic foreign = diagnostics.save(diagnostic("AUTH-FOREIGN", b, esg, foreignOwner));
            diagnosticId = target.getDiagnosticId();
            ownFileId = file(target, "own.pdf").getResultFileId();
            foreignFileId = file(foreign, "foreign.pdf").getResultFileId();
            results.save(AiAnalysisResult.builder().diagnostic(target).domainCode("ESG")
                .packageId("AUDIT_PACKAGE").riskLevel("LOW").verdict("PASS")
                .whySummary("AUDIT_RESULT")
                .resultJson("{\"slot_results\":[],\"clarifications\":[],\"extras\":{}}").build());
        });
    }

    private User user(String name, Company company, Role role, Domain domain) {
        User u = User.builder().name(name).email(name + "@audit.example").company(company)
            .role(role).userPassword("not-used-for-jwt-fixture").emailVerified(true).build();
        if (domain != null) u.addDomainRole(UserDomainRole.builder().user(u).role(role).domain(domain).build());
        u = users.save(u);
        tokens.put(name, jwt.createAccessToken(u.getUserId(), u.getEmail(), role.getCode()));
        return u;
    }

    private Diagnostic diagnostic(String code, Company company, Domain domain, User drafter) {
        return Diagnostic.builder().diagnosticCode(code).title(code).company(company).domain(domain)
            .drafterId(drafter.getUserId()).periodStartDate(LocalDate.now().minusDays(30))
            .periodEndDate(LocalDate.now()).build();
    }

    private EvidenceFile file(Diagnostic diagnostic, String name) {
        EvidenceFile f = EvidenceFile.builder().diagnostic(diagnostic).originalFileName(name)
            .filePath("isolated-audit/" + name).mimeType("application/pdf").fileSize(1L).build();
        f.completeParsing(null, null, null);
        return files.save(f);
    }

    @BeforeEach
    void resetPackageAndMockResponse() {
        transactions.executeWithoutResult(status -> diagnostics.findById(diagnosticId).orElseThrow().updatePreviewPackageId(null));
        when(ai.previewSync(any())).thenReturn(new RunPreviewResponse("AUDIT_PACKAGE", List.of(), List.of(), List.of()));
    }

    static Stream<Arguments> deniedRequests() {
        return Stream.of("foreign-company", "wrong-domain", "other-drafter", "wrong-domain-reviewer")
            .flatMap(user -> ENDPOINTS.stream().map(path -> Arguments.of(user, path)));
    }

    static Stream<String> allEndpoints() { return ENDPOINTS.stream(); }
    static Stream<Arguments> authorizedReaders() {
        return Stream.of("approver", "reviewer")
            .flatMap(user -> Stream.of("/result", "/result/detail", "/history").map(path -> Arguments.of(user, path)));
    }

    private ResponseEntity<String> request(String user, String path, Long fileId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) h.setBearerAuth(tokens.get(user));
        boolean post = path.equals("/preview") || path.equals("/submit");
        Object body = path.equals("/preview") ? Map.of("fileIds", List.of(fileId)) : Map.of();
        return http.exchange("/api/v1/ai/run/diagnostics/" + diagnosticId + path,
            post ? HttpMethod.POST : HttpMethod.GET, new HttpEntity<>(post ? body : null, h), String.class);
    }

    @ParameterizedTest(name = "{0} {1}") @MethodSource("deniedRequests")
    void scopeIsEnforcedBeforeReadingOrDispatching(String user, String path) {
        ResponseEntity<String> r = request(user, path, ownFileId);
        int expected = BASELINE ? (path.equals("/submit") ? 202 : 200) : 403;
        System.out.printf("%s %s %s -> %d%n", BASELINE ? "BASELINE" : "PROTECTED", user, path, r.getStatusCode().value());
        assertThat(r.getStatusCode().value()).isEqualTo(expected);
        if (!BASELINE) {
            assertThat(r.getBody()).doesNotContain("AUDIT_RESULT");
            verifyNoInteractions(ai, async);
            assertThat(diagnostics.findById(diagnosticId).orElseThrow().getPreviewPackageId()).isNull();
        } else if (!path.equals("/preview") && !path.equals("/submit")) {
            assertThat(r.getBody()).contains("AUDIT_RESULT");
        }
    }

    @Test
    void ownDiagnosticCannotBorrowAnotherDiagnosticsFile() {
        ResponseEntity<String> r = request("owner", "/preview", foreignFileId);
        assertThat(r.getStatusCode().value()).isEqualTo(BASELINE ? 200 : 403);
        System.out.printf("%s mixed-file preview -> %d%n", BASELINE ? "BASELINE" : "PROTECTED", r.getStatusCode().value());
        if (!BASELINE) verifyNoInteractions(ai, async);
    }

    @ParameterizedTest @MethodSource("allEndpoints")
    void ownerCanStillUseAllEndpoints(String path) {
        ResponseEntity<String> r = request("owner", path, ownFileId);
        assertThat(r.getStatusCode().value()).isEqualTo(path.equals("/submit") ? 202 : 200);
        if (path.equals("/preview")) verify(ai).previewSync(any());
        if (path.equals("/submit")) verify(async).submitAsync(eq(diagnosticId), any());
    }

    @ParameterizedTest @MethodSource("authorizedReaders")
    void companyApproverAndDomainReviewerKeepReadAccess(String user, String path) {
        assertThat(request(user, path, ownFileId).getStatusCode().value()).isEqualTo(200);
    }

    @ParameterizedTest @MethodSource("allEndpoints")
    void anonymousIsRejected(String path) {
        assertThat(request(null, path, ownFileId).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(ai, async);
    }

    @ParameterizedTest @MethodSource("allEndpoints")
    void guestIsRejected(String path) {
        assertThat(request("guest", path, ownFileId).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(ai, async);
    }
}
