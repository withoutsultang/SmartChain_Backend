package com.smartchain.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartchain.platform.domain.diagnostic.entity.Campaign;
import com.smartchain.platform.domain.diagnostic.entity.Diagnostic;
import com.smartchain.platform.domain.diagnostic.repository.CampaignRepository;
import com.smartchain.platform.domain.diagnostic.repository.DiagnosticRepository;
import com.smartchain.platform.domain.user.entity.*;
import com.smartchain.platform.domain.user.repository.*;
import com.smartchain.platform.dto.diagnostic.create.DiagnosticCreateRequest;
import com.smartchain.platform.global.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DiagnosticController 상세 통합 테스트
 * Issue #52: API 통합 테스트 확장
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("DiagnosticController 통합 테스트")
class DiagnosticIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private IndustryRepository industryRepository;

    @Autowired
    private DomainRepository domainRepository;

    @Autowired
    private UserDomainRoleRepository userDomainRoleRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private DiagnosticRepository diagnosticRepository;

    private User drafterUser;
    private String drafterToken;
    private Domain esgDomain;
    private Campaign campaign;
    private Company company;

    @BeforeEach
    void setUp() {
        // 역할 설정
        Role drafterRole = roleRepository.save(new Role("기안자", "DRAFTER"));

        // 도메인 설정
        esgDomain = domainRepository.save(Domain.builder()
                .code("ESG")
                .name("ESG 실사")
                .description("ESG 실사 도메인")
                .isActive(true)
                .build());

        // 회사 설정
        Industry industry = industryRepository.save(new Industry("제조업", "MANUFACTURING"));
        company = companyRepository.save(Company.builder()
                .industry(industry)
                .name("(주)테스트협력사")
                .scale("중소기업")
                .businessNumber("123-45-67890")
                .ceoName("테스트대표")
                .address("서울시 강남구")
                .contactEmail("test@partner.com")
                .contactPhone("02-1234-5678")
                .build());

        // 기안자 사용자 설정
        drafterUser = User.builder()
                .name("기안자")
                .email("drafter@test.com")
                .userPassword("password123")
                .company(company)
                .role(drafterRole)
                .build();

        // 도메인 역할 매핑 (User entity의 addDomainRole 사용)
        UserDomainRole domainRole = UserDomainRole.builder()
                .user(drafterUser)
                .domain(esgDomain)
                .role(drafterRole)
                .build();
        drafterUser.addDomainRole(domainRole);
        drafterUser = userRepository.save(drafterUser);

        // 캠페인 설정
        campaign = campaignRepository.save(Campaign.builder()
                .campaignCode("CAMP-2026-001")
                .ownerCompanyId(1L)
                .domain(esgDomain)
                .title("2026년 1분기 ESG 진단")
                .content("ESG 진단 캠페인입니다")
                .periodStartDate(LocalDate.now().minusDays(30))
                .periodEndDate(LocalDate.now().plusDays(30))
                .deadline(LocalDate.now().plusDays(15))
                .build());

        // JWT 토큰 생성
        drafterToken = jwtTokenProvider.createAccessToken(drafterUser.getUserId(), drafterUser.getEmail(), "DRAFTER");
    }

    @Nested
    @DisplayName("기안 목록 조회")
    class GetDiagnosticList {

        @Test
        @DisplayName("기안 목록 조회 성공")
        void getDiagnosticList_Success() throws Exception {
            mockMvc.perform(get("/api/v1/diagnostics")
                            .header("Authorization", "Bearer " + drafterToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("도메인 코드로 필터링")
        void getDiagnosticList_FilterByDomainCode() throws Exception {
            mockMvc.perform(get("/api/v1/diagnostics")
                            .header("Authorization", "Bearer " + drafterToken)
                            .param("domainCode", "ESG")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("페이징 파라미터 적용")
        void getDiagnosticList_WithPaging() throws Exception {
            mockMvc.perform(get("/api/v1/diagnostics")
                            .header("Authorization", "Bearer " + drafterToken)
                            .param("page", "0")
                            .param("size", "5")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Nested
    @DisplayName("기안 상세 조회")
    class GetDiagnosticDetail {

        @Test
        @DisplayName("존재하지 않는 기안 조회 시 404 응답")
        void getDiagnosticDetail_NotFound() throws Exception {
            mockMvc.perform(get("/api/v1/diagnostics/{diagnosticId}", 99999L)
                            .header("Authorization", "Bearer " + drafterToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("기안 생성")
    class CreateDiagnostic {

        @Test
        @DisplayName("기안 생성 성공")
        void createDiagnostic_Success() throws Exception {
            DiagnosticCreateRequest request = DiagnosticCreateRequest.builder()
                    .title("2026년 1분기 ESG 자가진단")  // 필수 필드 추가
                    .campaignId(campaign.getCampaignId())
                    .domainCode("ESG")
                    .build();

            mockMvc.perform(post("/api/v1/diagnostics")
                            .header("Authorization", "Bearer " + drafterToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.diagnosticId").exists());
        }

        @Test
        @DisplayName("종료된 캠페인에는 기안 생성 불가")
        void createDiagnostic_ExpiredCampaign_Rejected() throws Exception {
            Campaign expired = campaignRepository.save(Campaign.builder()
                    .campaignCode("CAMP-EXPIRED-TEST").ownerCompanyId(1L)
                    .domain(esgDomain).title("종료 캠페인").content("종료 검증")
                    .periodStartDate(LocalDate.now().minusDays(60))
                    .periodEndDate(LocalDate.now().minusDays(1))
                    .deadline(LocalDate.now().minusDays(1)).build());
            DiagnosticCreateRequest request = DiagnosticCreateRequest.builder()
                    .title("종료 캠페인 제출").campaignId(expired.getCampaignId())
                    .domainCode("ESG").build();
            mockMvc.perform(post("/api/v1/diagnostics")
                            .header("Authorization", "Bearer " + drafterToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("D003"));
        }

        @Test
        @DisplayName("필수 필드 누락 시 400 응답")
        void createDiagnostic_MissingFields() throws Exception {
            DiagnosticCreateRequest request = new DiagnosticCreateRequest();

            mockMvc.perform(post("/api/v1/diagnostics")
                            .header("Authorization", "Bearer " + drafterToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }
}
