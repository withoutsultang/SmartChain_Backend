package com.smartchain.platform.domain.ai.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlotConfigPropertiesTest {

    private SlotConfigProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SlotConfigProperties();

        // ESG 도메인 슬롯 설정 (docs/API_CONTRACT_SSOT.md §8.6 기준)
        SlotConfigProperties.SlotDefinition esgElectricityUsage = createSlot(
            "esg.energy.electricity.usage", List.of("전기", "electricity", "전력사용"), true);
        SlotConfigProperties.SlotDefinition esgGasUsage = createSlot(
            "esg.energy.gas.usage", List.of("가스", "gas", "도시가스"), true);
        SlotConfigProperties.SlotDefinition esgMsds = createSlot(
            "esg.hazmat.msds", List.of("msds", "sds", "물질안전"), true);
        SlotConfigProperties.SlotDefinition esgEthics = createSlot(
            "esg.ethics.code", List.of("윤리강령", "ethics", "행동강령"), true);
        SlotConfigProperties.SlotDefinition esgWaterUsage = createSlot(
            "esg.energy.water.usage", List.of("수도", "water", "용수"), false);

        // Safety 도메인 슬롯 설정
        SlotConfigProperties.SlotDefinition safetyEducation = createSlot(
            "safety.education.status", List.of("교육이수", "안전교육", "education status"), true);
        SlotConfigProperties.SlotDefinition safetyFire = createSlot(
            "safety.fire.inspection", List.of("소방점검", "fire inspection"), true);
        SlotConfigProperties.SlotDefinition safetyRisk = createSlot(
            "safety.risk.assessment", List.of("위험성평가", "risk assessment"), true);
        SlotConfigProperties.SlotDefinition safetyManagement = createSlot(
            "safety.management.system", List.of("안전보건관리", "management system"), true);
        SlotConfigProperties.SlotDefinition safetyTbm = createSlot(
            "safety.tbm", List.of("tbm", "toolbox", "작업전회의"), false);

        // Compliance 도메인 슬롯 설정
        SlotConfigProperties.SlotDefinition complianceContract = createSlot(
            "compliance.contract.sample", List.of("표준계약", "하도급계약", "contract sample"), true);
        SlotConfigProperties.SlotDefinition compliancePrivacy = createSlot(
            "compliance.education.privacy", List.of("개인정보교육", "privacy education"), true);
        SlotConfigProperties.SlotDefinition complianceFairTrade = createSlot(
            "compliance.fair.trade", List.of("공정거래", "fair trade", "자율점검"), true);
        SlotConfigProperties.SlotDefinition complianceEthicsReport = createSlot(
            "compliance.ethics.report", List.of("내부신고", "ethics report"), false);

        properties.setDomains(Map.of(
            "esg", List.of(esgElectricityUsage, esgGasUsage, esgMsds, esgEthics, esgWaterUsage),
            "safety", List.of(safetyEducation, safetyFire, safetyRisk, safetyManagement, safetyTbm),
            "compliance", List.of(complianceContract, compliancePrivacy, complianceFairTrade, complianceEthicsReport)
        ));
    }

    private SlotConfigProperties.SlotDefinition createSlot(String name, List<String> keywords, boolean required) {
        SlotConfigProperties.SlotDefinition slot = new SlotConfigProperties.SlotDefinition();
        slot.setName(name);
        slot.setKeywords(keywords);
        slot.setRequired(required);
        return slot;
    }

    @Nested
    @DisplayName("matchSlotName")
    class MatchSlotNameTest {

        @Test
        @DisplayName("ESG 전기사용량 파일명을 esg.energy.electricity.usage로 매칭한다")
        void matchSlotName_esgElectricity() {
            assertThat(properties.matchSlotName("전기사용량_2024.xlsx", "ESG"))
                .isEqualTo("esg.energy.electricity.usage");
            assertThat(properties.matchSlotName("electricity_usage.csv", "ESG"))
                .isEqualTo("esg.energy.electricity.usage");
        }

        @Test
        @DisplayName("ESG 가스사용량 파일명을 esg.energy.gas.usage로 매칭한다")
        void matchSlotName_esgGas() {
            assertThat(properties.matchSlotName("도시가스_사용량.xlsx", "ESG"))
                .isEqualTo("esg.energy.gas.usage");
            assertThat(properties.matchSlotName("gas_usage_report.pdf", "ESG"))
                .isEqualTo("esg.energy.gas.usage");
        }

        @Test
        @DisplayName("Safety TBM 파일명을 safety.tbm으로 매칭한다 (필수 아님)")
        void matchSlotName_safetyTbm() {
            assertThat(properties.matchSlotName("TBM_점검표.pdf", "SAFETY"))
                .isEqualTo("safety.tbm");
            assertThat(properties.matchSlotName("toolbox_meeting.xlsx", "SAFETY"))
                .isEqualTo("safety.tbm");
        }

        @Test
        @DisplayName("Safety 위험성평가 파일명을 safety.risk.assessment로 매칭한다")
        void matchSlotName_safetyRisk() {
            assertThat(properties.matchSlotName("위험성평가서_2024.xlsx", "SAFETY"))
                .isEqualTo("safety.risk.assessment");
            assertThat(properties.matchSlotName("risk assessment report.pdf", "SAFETY"))
                .isEqualTo("safety.risk.assessment");
        }

        @Test
        @DisplayName("Compliance 공정거래 파일명을 compliance.fair.trade로 매칭한다")
        void matchSlotName_complianceFairTrade() {
            assertThat(properties.matchSlotName("공정거래_자율점검표.xlsx", "COMPLIANCE"))
                .isEqualTo("compliance.fair.trade");
            assertThat(properties.matchSlotName("fair trade checklist.pdf", "COMPLIANCE"))
                .isEqualTo("compliance.fair.trade");
        }

        @Test
        @DisplayName("매칭되는 키워드가 없으면 domain.other를 반환한다")
        void matchSlotName_fallbackToOther() {
            assertThat(properties.matchSlotName("unknown_file.pdf", "SAFETY"))
                .isEqualTo("safety.other");
            assertThat(properties.matchSlotName("random.doc", "ESG"))
                .isEqualTo("esg.other");
            assertThat(properties.matchSlotName("misc.xlsx", "COMPLIANCE"))
                .isEqualTo("compliance.other");
        }

        @Test
        @DisplayName("존재하지 않는 도메인이면 domain.other를 반환한다")
        void matchSlotName_unknownDomain() {
            assertThat(properties.matchSlotName("test.pdf", "UNKNOWN"))
                .isEqualTo("unknown.other");
        }

        @Test
        @DisplayName("대소문자 구분 없이 매칭한다")
        void matchSlotName_caseInsensitive() {
            assertThat(properties.matchSlotName("MSDS_문서.pdf", "esg"))
                .isEqualTo("esg.hazmat.msds");
            assertThat(properties.matchSlotName("TBM_meeting.pdf", "safety"))
                .isEqualTo("safety.tbm");
        }
    }

    @Nested
    @DisplayName("getSlotsForDomain")
    class GetSlotsForDomainTest {

        @Test
        @DisplayName("해당 도메인의 슬롯 목록을 반환한다")
        void getSlotsForDomain_returnsList() {
            assertThat(properties.getSlotsForDomain("esg")).hasSize(5);
            assertThat(properties.getSlotsForDomain("safety")).hasSize(5);
            assertThat(properties.getSlotsForDomain("compliance")).hasSize(4);
        }

        @Test
        @DisplayName("존재하지 않는 도메인은 빈 목록을 반환한다")
        void getSlotsForDomain_unknownReturnsEmpty() {
            assertThat(properties.getSlotsForDomain("nonexistent")).isEmpty();
        }
    }

    @Nested
    @DisplayName("getRequiredSlots")
    class GetRequiredSlotsTest {

        @Test
        @DisplayName("주입된 ESG 단위 시험 fixture에서 필수 4개를 필터링한다")
        void getRequiredSlots_esg() {
            List<SlotConfigProperties.SlotDefinition> required = properties.getRequiredSlots("esg");
            assertThat(required).hasSize(4);
            assertThat(required).extracting("name")
                .containsExactlyInAnyOrder(
                    "esg.energy.electricity.usage",
                    "esg.energy.gas.usage",
                    "esg.hazmat.msds",
                    "esg.ethics.code"
                );
        }

        @Test
        @DisplayName("Safety 도메인의 필수 슬롯은 4개다 (TBM은 필수 아님)")
        void getRequiredSlots_safety() {
            List<SlotConfigProperties.SlotDefinition> required = properties.getRequiredSlots("safety");
            assertThat(required).hasSize(4);
            assertThat(required).extracting("name")
                .containsExactlyInAnyOrder(
                    "safety.education.status",
                    "safety.fire.inspection",
                    "safety.risk.assessment",
                    "safety.management.system"
                );
            // TBM은 필수가 아님을 명시적으로 검증
            assertThat(required).extracting("name")
                .doesNotContain("safety.tbm");
        }

        @Test
        @DisplayName("Compliance 도메인의 필수 슬롯은 3개다")
        void getRequiredSlots_compliance() {
            List<SlotConfigProperties.SlotDefinition> required = properties.getRequiredSlots("compliance");
            assertThat(required).hasSize(3);
            assertThat(required).extracting("name")
                .containsExactlyInAnyOrder(
                    "compliance.contract.sample",
                    "compliance.education.privacy",
                    "compliance.fair.trade"
                );
        }
    }

    /**
     * application.yaml에서 로드된 슬롯 정의를 검증하는 통합 테스트
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DisplayName("YAML 로딩 통합 테스트")
    class YamlLoadingIntegrationTest {

        @Autowired
        private SlotConfigProperties yamlProperties;

        @Test
        @DisplayName("ESG 도메인은 16개 슬롯(기타 포함), AI 계약의 9개 필수로 정의되어야 한다")
        void yamlLoading_esgSlotCount() {
            List<SlotConfigProperties.SlotDefinition> esgSlots = yamlProperties.getSlotsForDomain("esg");
            List<SlotConfigProperties.SlotDefinition> esgRequired = yamlProperties.getRequiredSlots("esg");

            assertThat(esgSlots).hasSize(16);
            assertThat(esgRequired).hasSize(9);
        }

        @Test
        @DisplayName("Safety 도메인은 9개 슬롯(기타 포함), 4개 필수로 정의되어야 한다")
        void yamlLoading_safetySlotCount() {
            List<SlotConfigProperties.SlotDefinition> safetySlots = yamlProperties.getSlotsForDomain("safety");
            List<SlotConfigProperties.SlotDefinition> safetyRequired = yamlProperties.getRequiredSlots("safety");

            assertThat(safetySlots).hasSize(9);
            assertThat(safetyRequired).hasSize(4);
        }

        @Test
        @DisplayName("Compliance 도메인은 8개 슬롯(기타 포함), 3개 필수로 정의되어야 한다")
        void yamlLoading_complianceSlotCount() {
            List<SlotConfigProperties.SlotDefinition> complianceSlots = yamlProperties.getSlotsForDomain("compliance");
            List<SlotConfigProperties.SlotDefinition> complianceRequired = yamlProperties.getRequiredSlots("compliance");

            assertThat(complianceSlots).hasSize(8);
            assertThat(complianceRequired).hasSize(3);
        }

        @Test
        @DisplayName("Safety TBM 슬롯은 필수가 아니어야 한다")
        void yamlLoading_safetyTbmNotRequired() {
            List<SlotConfigProperties.SlotDefinition> safetyRequired = yamlProperties.getRequiredSlots("safety");

            assertThat(safetyRequired).extracting("name")
                .doesNotContain("safety.tbm");
        }
    }
}
