package com.smartchain.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartchain.platform.domain.ai.repository.AiAnalysisResultRepository;
import com.smartchain.platform.domain.ai.service.AiAnalysisService;
import com.smartchain.platform.domain.ai.service.AiRunStateService;
import com.smartchain.platform.domain.diagnostic.entity.*;
import com.smartchain.platform.domain.diagnostic.repository.*;
import com.smartchain.platform.domain.evidence.entity.EvidenceFile;
import com.smartchain.platform.domain.evidence.repository.EvidenceFileRepository;
import com.smartchain.platform.domain.user.entity.*;
import com.smartchain.platform.domain.user.repository.*;
import com.smartchain.platform.dto.ai.AiRunStatusResponse;
import com.smartchain.platform.global.security.JwtTokenProvider;
import com.smartchain.platform.global.error.CustomException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

/** Actual backend HTTP/JWT/JPA and outbound HTTP transport; faults are isolated to the provider stub. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.sql.init.mode=never", "app.data-initializer.enabled=false"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiRecoveryHttpTest {
    static final MockWebServer provider = new MockWebServer();
    static final Path storage;
    static {
        try { provider.start(); storage = Files.createTempDirectory("smartchain-recovery-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource
    static void config(DynamicPropertyRegistry p) {
        String url=System.getenv("AUTHORIZATION_DATABASE_URL");
        boolean pg=url!=null&&!url.isBlank();
        p.add("spring.datasource.url",()->pg?url:"jdbc:h2:mem:ai_recovery;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        p.add("spring.datasource.username",()->pg?"audit":"sa");
        p.add("spring.datasource.password",()->pg?"isolated-audit-only":"");
        p.add("spring.datasource.driver-class-name",()->pg?"org.postgresql.Driver":"org.h2.Driver");
        p.add("spring.jpa.properties.hibernate.dialect",()->pg?"org.hibernate.dialect.PostgreSQLDialect":"org.hibernate.dialect.H2Dialect");
        p.add("ai.run-api.url",()->provider.url("/").toString());
        p.add("ai.run-api.timeout-seconds",()->2);
        p.add("ai.run-api.max-retry",()->0);
        p.add("ai.run-api.preview-timeout-seconds",()->1);
        p.add("ai.run-api.preview-max-retry",()->0);
        p.add("file.storage.local.base-path",()->storage.toString());
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DiagnosticRepository diagnostics;
    @Autowired CampaignRepository campaigns;
    @Autowired EvidenceFileRepository files;
    @Autowired AiAnalysisResultRepository results;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired CompanyRepository companies;
    @Autowired DomainRepository domains;
    @Autowired JwtTokenProvider jwt;
    @Autowired AiRunStateService state;
    @Autowired AiAnalysisService analysis;
    User owner; Company company; Domain domain; Campaign campaign;
    String drafterToken, approverToken, reviewerToken, foreignToken;
    Long id, fileId;
    int counter;

    @BeforeAll
    void identities() {
        jdbc.execute("CREATE SEQUENCE IF NOT EXISTS diagnostic_code_seq START WITH 1 INCREMENT BY 1");
        tx.executeWithoutResult(s->{
            Role drafter=roles.save(new Role("기안자","DRAFTER"));
            Role approver=roles.save(new Role("결재자","APPROVER"));
            Role reviewer=roles.save(new Role("수신자","REVIEWER"));
            company=companies.save(Company.builder().name("재제출 검증 협력사").build());
            Company other=companies.save(Company.builder().name("별도 검증 회사").build());
            Company root=companies.save(Company.builder().name("원청 검증").build());
            domain=domains.save(Domain.builder().code("ESG").name("ESG").build());
            owner=user("drafter", company,drafter);
            drafterToken=token(owner);
            approverToken=token(user("approver",company,approver));
            reviewerToken=token(user("reviewer",root,reviewer));
            foreignToken=token(user("foreign",other,approver));
            campaign=campaigns.save(Campaign.builder().campaignCode("RECOVERY-CAMP").domain(domain)
                .ownerCompanyId(root.getCompanyId()).title("재제출 검증 캠페인")
                .periodStartDate(LocalDate.now().minusDays(30)).periodEndDate(LocalDate.now().plusDays(30))
                .deadline(LocalDate.now().plusDays(10)).build());
        });
    }
    User user(String name,Company c,Role role) {
        User u=User.builder().name(name).email(name+"@recovery.example").company(c).role(role)
            .userPassword("jwt-fixture-only").emailVerified(true).build();
        u.addDomainRole(UserDomainRole.builder().user(u).domain(domain).role(role).build());
        return users.save(u);
    }
    String token(User u) { return jwt.createAccessToken(u.getUserId(),u.getEmail(),u.getRole().getCode()); }
    @BeforeEach
    void draft() {
        tx.executeWithoutResult(s->{
            Diagnostic d=diagnostics.save(Diagnostic.builder().diagnosticCode("RECOVERY-"+(++counter))
                .title("격리 재시도 검증").campaign(campaign).company(company).domain(domain).drafterId(owner.getUserId())
                .periodStartDate(LocalDate.now().minusDays(10)).periodEndDate(LocalDate.now()).build());
            id=d.getDiagnosticId();
            EvidenceFile f=EvidenceFile.builder().diagnostic(d).filePath("fixture.pdf")
                .originalFileName("ethics_code.pdf").mimeType("application/pdf").fileSize(10L).build();
            f.completeParsing(null,null,null);fileId=files.save(f).getResultFileId();
        });
    }
    String path(String suffix){return "/ai/run/diagnostics/"+id+suffix;}
    ResponseEntity<String> raw(HttpMethod method,String path,String token,Object body) {
        HttpHeaders h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/v1"+path,method,new HttpEntity<>(body,h),String.class);
    }
    JsonNode request(HttpMethod method,String path,String token,Object body) {
        ResponseEntity<String> r=raw(method,path,token,body);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(method+" "+path+" -> "+r.getStatusCode()+" "+r.getBody()).isTrue();
        try{return json.readTree(r.getBody()).get("data");}catch(Exception e){throw new IllegalStateException(e);}
    }
    JsonNode status(){return request(HttpMethod.GET,path("/status"),drafterToken,null);}
    JsonNode submit(){return request(HttpMethod.POST,path("/submit"),drafterToken,Map.of());}
    void until(BooleanSupplier done) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){if(done.getAsBoolean())return;Thread.sleep(40);}
        fail("Condition not reached before deadline");
    }
    JsonNode terminal(String expected) throws Exception {
        until(()->expected.equals(status().path("status").asText()));return status();
    }
    MockResponse valid(){return new MockResponse().setHeader("Content-Type","application/json").setBody(
        "{\"package_id\":\"recovery-package\",\"risk_level\":\"LOW\",\"verdict\":\"PASS\",\"why\":\"controlled valid response\",\"slot_results\":[],\"clarifications\":[],\"extras\":{}}");}
    long analyzeSuccessfully() throws Exception {
        provider.enqueue(valid());JsonNode accepted=submit();JsonNode done=terminal("SUCCEEDED");
        assertThat(done.path("requestId").asText()).isEqualTo(accepted.path("requestId").asText());
        return done.path("resultId").asLong();
    }

    @Test void timeoutPreservesPreviousResultAndRetryCreatesNewAttempt() throws Exception {
        long previous=analyzeSuccessfully();
        provider.enqueue(valid().setHeadersDelay(4,TimeUnit.SECONDS));
        JsonNode failedAttempt=submit();JsonNode failed=terminal("FAILED");
        assertThat(failed.path("retryable").asBoolean()).isTrue();
        assertThat(failed.path("errorCode").asText()).isEqualTo("AI001");
        assertThat(failed.path("resultId").isNull()).isTrue();
        assertThat(results.findByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(id)).hasSize(1);
        assertThat(request(HttpMethod.GET,path("/result"),drafterToken,null).path("id").asLong()).isEqualTo(previous);
        provider.enqueue(valid());JsonNode retried=submit();JsonNode done=terminal("SUCCEEDED");
        assertThat(retried.path("requestId").asText()).isNotEqualTo(failedAttempt.path("requestId").asText());
        assertThat(done.path("resultId").asLong()).isNotEqualTo(previous);
        assertThat(results.findByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(id)).hasSize(2);
    }
    @Test void providerErrorIsTerminalAndCanBeRetried() throws Exception {
        provider.enqueue(new MockResponse().setResponseCode(503).setBody("controlled provider unavailable"));
        submit();assertThat(terminal("FAILED").path("retryable").asBoolean()).isTrue();
        assertThat(results.findByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(id)).isEmpty();
        analyzeSuccessfully();
    }
    @Test void concurrentDuplicatesShareOneAttemptAndOneProviderCall() throws Exception {
        int before=provider.getRequestCount();
        provider.enqueue(valid().setHeadersDelay(1,TimeUnit.SECONDS));
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go=new CountDownLatch(1);List<Future<JsonNode>> calls=new ArrayList<>();
            for(int i=0;i<8;i++)calls.add(pool.submit(()->{go.await();return submit();}));
            go.countDown();Set<String> ids=new HashSet<>();int originals=0;
            for(Future<JsonNode> f:calls){JsonNode n=f.get(5,TimeUnit.SECONDS);ids.add(n.path("requestId").asText());if(!n.path("duplicate").asBoolean())originals++;}
            assertThat(ids).hasSize(1);assertThat(originals).isEqualTo(1);
            terminal("SUCCEEDED");assertThat(provider.getRequestCount()-before).isEqualTo(1);
            assertThat(results.findByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(id)).hasSize(1);
        } finally {pool.shutdownNow();}
    }
    @Test void previewTimeoutDoesNotStorePackageAndRetrySucceeds() throws Exception {
        provider.enqueue(new MockResponse().setHeadersDelay(3,TimeUnit.SECONDS).setHeader("Content-Type","application/json").setBody("{}"));
        assertThat(raw(HttpMethod.POST,path("/preview"),drafterToken,Map.of("fileIds",List.of(fileId))).getStatusCode().value()).isEqualTo(503);
        assertThat(diagnostics.findById(id).orElseThrow().getPreviewPackageId()).isNull();
        provider.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"package_id\":\"retry-preview\",\"slot_hint\":[],\"required_slot_status\":[],\"missing_required_slots\":[]}"));
        request(HttpMethod.POST,path("/preview"),drafterToken,Map.of("fileIds",List.of(fileId)));
        assertThat(diagnostics.findById(id).orElseThrow().getPreviewPackageId()).isEqualTo("retry-preview");
    }
    @Test void expiredAttemptIsRecoverableAndLateWorkerCannotPublish() {
        AiRunStatusResponse old=state.claim(id);state.start(id,old.requestId());
        tx.executeWithoutResult(s->ReflectionTestUtils.setField(diagnostics.findById(id).orElseThrow(),"aiRunExpiresAt",LocalDateTime.now().minusSeconds(1)));
        assertThat(state.status(id).status()).isEqualTo("FAILED");
        AiRunStatusResponse fresh=state.claim(id);assertThat(fresh.requestId()).isNotEqualTo(old.requestId());
        provider.enqueue(valid());
        assertThatThrownBy(()->analysis.submitForRun(id,null,old.requestId())).isInstanceOf(CustomException.class);
        assertThat(results.findByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(id)).isEmpty();
        state.fail(id,old.requestId(),"old-failure");
        assertThat(state.status(id).requestId()).isEqualTo(fresh.requestId());
        assertThat(state.status(id).status()).isEqualTo("PENDING");
        state.fail(id,fresh.requestId(),"test-cleanup");
    }
    @Test void evidenceCannotChangeDuringAnActiveAttempt() {
        AiRunStatusResponse run=state.claim(id);
        assertThat(raw(HttpMethod.DELETE,"/files/"+fileId,drafterToken,null).getStatusCode().value()).isEqualTo(409);
        assertThat(files.findById(fileId)).isPresent();
        state.fail(id,run.requestId(),"test-cleanup");
    }
    @Test void statusEndpointUsesTheSameCompanyScope() {
        assertThat(raw(HttpMethod.GET,path("/status"),foreignToken,null).getStatusCode().value()).isEqualTo(403);
        assertThat(status().path("status").asText()).isEqualTo("IDLE");
        assertThat(raw(HttpMethod.GET,path("/status"),null,null).getStatusCode().value()).isEqualTo(401);
    }
    @Test void ocrFindingIsAnExplicitResultRatherThanExecutionFailure() throws Exception {
        provider.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(
            "{\"package_id\":\"ocr-failure\",\"risk_level\":\"HIGH\",\"verdict\":\"NEED_FIX\",\"why\":\"OCR failed; replace the scan\",\"slot_results\":[{\"slot_name\":\"esg.ethics.code\",\"verdict\":\"NEED_FIX\",\"reasons\":[\"OCR_FAILED\"],\"file_ids\":[],\"file_names\":[],\"extras\":{}}],\"clarifications\":[],\"extras\":{}}"));
        submit();terminal("SUCCEEDED");
        assertThat(request(HttpMethod.GET,path("/result"),drafterToken,null).path("verdict").asText()).isEqualTo("NEED_FIX");
        assertThat(results.findByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(id)).hasSize(1);
    }
    Long upload(String filename) throws Exception {
        ByteArrayResource resource=new ByteArrayResource("%PDF-1.4\nsynthetic HTTP fixture".getBytes()) {
            @Override public String getFilename(){return filename;}
        };
        HttpHeaders partHeaders=new HttpHeaders();partHeaders.setContentType(MediaType.APPLICATION_PDF);
        LinkedMultiValueMap<String,Object> body=new LinkedMultiValueMap<>();body.add("file",new HttpEntity<>(resource,partHeaders));
        HttpHeaders h=new HttpHeaders();h.setBearerAuth(drafterToken);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r=http.postForEntity("/api/v1/diagnostics/"+id+"/files",new HttpEntity<>(body,h),String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        Long uploaded=json.readTree(r.getBody()).path("data").path("fileId").asLong();
        until(()->files.findById(uploaded).orElseThrow().getParsingStatus().name().equals("SUCCESS"));return uploaded;
    }
    @Test void revisionUploadReanalysisAndResubmissionReuseWorkflowRecords() throws Exception {
        JsonNode created=request(HttpMethod.POST,"/diagnostics",drafterToken,Map.of("title","재제출 전체 순환","campaignId",campaign.getCampaignId(),"domainCode","ESG"));
        id=created.path("diagnosticId").asLong();Long original=upload("ethics_code.pdf");
        long initialResult=analyzeSuccessfully();
        JsonNode submitted=request(HttpMethod.POST,"/diagnostics/"+id+"/submit",drafterToken,Map.of("submitComment","최초 제출"));
        long approvalId=submitted.path("approvalId").asLong();
        request(HttpMethod.PATCH,"/approvals/"+approvalId,approverToken,Map.of("decision","APPROVED"));
        long reviewId=request(HttpMethod.POST,"/approvals/"+approvalId+"/submit-to-reviewer",approverToken,Map.of()).path("reviewId").asLong();
        request(HttpMethod.PATCH,"/reviews/"+reviewId,reviewerToken,Map.of("decision","REVISION_REQUIRED","comment","증빙 보완 요청"));
        assertThat(diagnostics.findById(id).orElseThrow().getStatus().name()).isEqualTo("RETURNED");
        request(HttpMethod.DELETE,"/files/"+original,drafterToken,null);
        Long revised=upload("ethics_code_revised.pdf");assertThat(revised).isNotEqualTo(original);
        long newResult=analyzeSuccessfully();assertThat(newResult).isNotEqualTo(initialResult);
        JsonNode resubmitted=request(HttpMethod.POST,"/diagnostics/"+id+"/submit",drafterToken,Map.of("submitComment","수정 증빙과 재분석 결과 제출"));
        assertThat(resubmitted.path("approvalId").asLong()).isEqualTo(approvalId);
        request(HttpMethod.PATCH,"/approvals/"+approvalId,approverToken,Map.of("decision","APPROVED"));
        JsonNode forwarded=request(HttpMethod.POST,"/approvals/"+approvalId+"/submit-to-reviewer",approverToken,Map.of());
        assertThat(forwarded.path("reviewId").asLong()).isEqualTo(reviewId);
        request(HttpMethod.PATCH,"/reviews/"+reviewId,reviewerToken,Map.of("decision","APPROVED"));
        assertThat(diagnostics.findById(id).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
        JsonNode history=request(HttpMethod.GET,"/diagnostics/"+id+"/history",drafterToken,null);
        assertThat(history.toString()).contains("REVIEW_REVISION_REQUIRED","REVIEW_APPROVED");
        assertThat(results.findByDiagnostic_DiagnosticIdOrderByAnalyzedAtDesc(id)).hasSize(2);
        System.out.printf("RECOVERY roundtrip diagnostic=%d approval=%d review=%d result=%d->%d COMPLETED%n",id,approvalId,reviewId,initialResult,newResult);
    }
    @AfterAll void shutdown() throws Exception {
        provider.shutdown();
        try(var paths=Files.walk(storage)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
    }
}
