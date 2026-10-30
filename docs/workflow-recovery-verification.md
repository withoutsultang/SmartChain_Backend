# 재제출·AI 장애 복구·ESG 계약 검증

2026-10-30, 기안자가 보완 증빙을 올린 뒤 새 분석 결과로 재결재·재심사를 거쳐 최종 승인되는 전체 순환을 검증했습니다. 분석 요청의 진행·실패·재시도 상태를 DB에 기록하고, ESG 필수 증빙 정의도 양쪽 저장소에서 동일하게 유지하도록 보강했습니다.

## 결과

|범위|확인 결과|근거|
|---|---|---|
|실제 AI 재제출 순환|기존 증빙 삭제 → 보완본 업로드 → 새 결과 저장 → 동일 Approval·Review 재사용 → COMPLETED|[실제 OpenAI·Clova 실행](https://github.com/withoutsultang/SmartChain_AI/actions/runs/41059678771)|
|PostgreSQL HTTP 검사|기존 권한 42개 + 재제출·복구 9개, 총 51개 통과|[실행](https://github.com/withoutsultang/SmartChain_Backend/actions/runs/41057940415) · [기록](recovery-postgres-evidence.json)|
|전체 백엔드|495개 중 494개 통과·1개 건너뜀, clean build 성공|[실행](https://github.com/withoutsultang/SmartChain_Backend/actions/runs/41058396126) · [집계](backend-test-summary.json)|
|프론트|Preview·로그인·분석 복구 정책 9개 및 프로덕션 빌드 통과|[실행](https://github.com/withoutsultang/SmartChain_Frontend/actions/runs/41057900216)|
|AI·OCR|응답 계약·실제 OCR 오류 처리·재처리·필수 계약 5개 통과|[실행](https://github.com/withoutsultang/SmartChain_AI/actions/runs/41057819667)|
|ESG 필수 계약|AI 엔진·공통 application.yaml·시험 YAML 모두 동일한 9개|[계약](https://github.com/withoutsultang/SmartChain_AI/blob/main/contracts/esg-required-slots.json) · [교차 검사](https://github.com/withoutsultang/SmartChain_AI/blob/main/scripts/verify_slot_contract.py)|

## 분석 요청의 수명

`POST /api/v1/ai/run/diagnostics/{id}/submit`은 `requestId`, `status`, `duplicate`를 반환하고, 같은 권한 정책을 사용하는 `GET .../status`에서 `resultId`, `errorCode`, `retryable`을 조회합니다.

- `PENDING → RUNNING → SUCCEEDED` 또는 `FAILED`를 기안 행에 보존합니다.
- [AiRunStateService](../src/main/java/com/smartchain/platform/domain/ai/service/AiRunStateService.java)는 DB 행 잠금으로 요청을 접수합니다. 같은 기안의 진행 중 요청은 기존 ID를 반환하고 추가 실행을 등록하지 않습니다. 완료 후 사용자가 다시 요청하는 분석은 새 시도로 구분합니다.
- [AiAnalysisService](../src/main/java/com/smartchain/platform/domain/ai/service/AiAnalysisService.java)는 성공 결과를 저장하기 직전에 현재 요청 ID와 활성 상태를 다시 확인합니다. 결과 저장과 성공 상태 변경은 같은 트랜잭션에서 커밋합니다.
- 외부 호출 실패는 별도 트랜잭션으로 실패 상태에 남습니다. 이전 성공 결과를 덮어쓰거나 새 결과로 복제하지 않습니다. 원본 예외 메시지 대신 제한된 오류 코드만 상태에 저장합니다.
- 설정된 전체 시도 시간보다 긴 만료 시간을 두고, 만료된 활성 요청은 실패 처리 후 다시 요청할 수 있게 합니다. 이전 작업이 늦게 끝나도 새 요청의 결과를 저장할 수 없습니다.
- 분석 중 증빙 변경은 제한합니다. [FileService](../src/main/java/com/smartchain/platform/domain/file/service/FileService.java)는 업로드·삭제 전에 같은 행 잠금과 현재 상태 확인을 사용합니다.
- [프론트 복구 정책](https://github.com/withoutsultang/SmartChain_Frontend/blob/develop/src/api/analysisPolicy.ts)은 현재 요청 ID의 성공만 완료로 판단합니다. 이전 성공 결과가 있어도 현재 실패를 성공으로 처리하지 않습니다.

## 9개 복구 검사의 범위

[실제 HTTP 회귀 시험](../src/test/java/com/smartchain/platform/integration/AiRecoveryHttpTest.java)은 다음을 확인합니다.

1. 이전 성공 후 시간 초과가 발생해도 결과 행은 유지되고, 재시도는 새 요청 ID·새 결과를 만듭니다.
2. AI HTTP 503 뒤 실패 상태를 조회할 수 있고, 정상 응답으로 재시도하면 성공합니다.
3. 동시에 보낸 8개 HTTP 요청이 요청 ID 한 개·AI Run API HTTP 호출 한 회·결과 한 행으로 처리됩니다.
4. Preview 시간 초과는 package ID를 남기지 않고, 재시도는 정상 package를 저장합니다.
5. 만료 시간을 지난 상태를 DB에 만들어 복구를 검사하고, 이전 요청의 늦은 저장·실패 통지가 새 요청에 영향을 주지 않는지 확인합니다. 실제 프로세스 재시작 시험은 아닙니다.
6. 분석 중 파일 삭제가 409로 거절되고 원래 파일 레코드는 유지됩니다.
7. 새 상태 조회 경로도 타사 계정 403·비로그인 401을 적용합니다.
8. `OCR_FAILED / NEED_FIX`는 내용 보완이 필요한 분석 결과로 보존하며, 실행 자체의 실패와 구분합니다.
9. 실제 기안 생성·업로드·분석·결재·보완 요청·증빙 교체·재분석·재제출·재심사·최종 승인과 이력을 확인합니다. Approval·Review는 새 행을 만들지 않고 재사용하고 분석 결과는 두 행으로 남습니다.

이 검사는 실제 Spring Boot HTTP 서버·JWT·PostgreSQL·WebClient를 실행하고, 외부 AI의 HTTP 응답만 제어해 실패를 주입합니다. 실패 검사에는 유료 API 호출을 사용하지 않습니다. 별도의 실제 OpenAI·Clova 시연은 합성 문서로 두 번의 분석을 실행합니다. 기안 생성은 브라우저 폼으로, 후속 상태 변경은 인증된 실제 HTTP API로 실행한 뒤 화면을 캡처합니다.

## ESG 필수 9개

[application.yaml](../src/main/resources/application.yaml)의 공통 영역으로 슬롯 정의를 옮겨 모든 프로필이 공유하도록 하고, [시험 설정](../src/test/resources/application-test.yaml)도 같은 필수 항목을 사용합니다. 선택 항목과 다른 도메인의 정책은 유지합니다. AI의 [기준 JSON](https://github.com/withoutsultang/SmartChain_AI/blob/main/contracts/esg-required-slots.json), 실제 엔진 정의, 백엔드 설정을 두 저장소의 `Portfolio checks`에서 비교합니다. 항목이 달라지면 빌드 검사에 실패합니다.

## 실행과 저장소 반영

- 백엔드 실행·전체 회귀 시험 소스: [ce785b12](https://github.com/withoutsultang/SmartChain_Backend/commit/ce785b12ec22bcc0b49f98c26f3b778c87caff3d). PostgreSQL 전용 실행은 같은 운영 코드의 [fb203fb5](https://github.com/withoutsultang/SmartChain_Backend/commit/fb203fb5f3b30293f4d4a28afda1da657c1b57ce)를 사용했습니다. 차이는 기존 단위 시험의 mock 초기화 보강입니다.
- 프론트: [b6c76c35](https://github.com/withoutsultang/SmartChain_Frontend/commit/b6c76c350250f51b928fe0d9df33d7f5608e594c).
- 새 DB는 기존 JPA 설정으로 상태 컬럼을 생성합니다. `ddl-auto=validate`를 사용하는 기존 PostgreSQL DB에는 실행 전에 [상태 컬럼 추가 SQL](sql/20261030_ai_run_state.sql)을 적용합니다. 운영 DB에 이 SQL을 실행하지 않았습니다.
- 이번 변경은 개인 포트폴리오 재검증 시점의 보강입니다. 실제 업무 당시 기여는 README의 원본 PR과 구분해 보존했습니다.
