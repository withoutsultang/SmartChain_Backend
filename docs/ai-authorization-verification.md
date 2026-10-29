# AI 회사·도메인·기안 소유권 검증

2026-10-29, AI 요청과 결과 조회에 기안의 기존 권한 정책을 동일하게 적용하고 PostgreSQL 기반 실제 HTTP 요청으로 확인했습니다. [실행 기록](https://github.com/withoutsultang/SmartChain_Backend/actions/runs/40881578094) · [기계 판독 근거](ai-authorization-evidence.json)

## 확인한 정책

|사용자·요청|확인 결과|
|---|---|
|같은 도메인의 다른 회사 결재자|5개 AI 경로 모두 403|
|같은 회사지만 해당 도메인 권한이 없는 결재자|5개 AI 경로 모두 403|
|같은 회사·도메인의 다른 기안자|5개 AI 경로 모두 403|
|다른 도메인의 수신자|5개 AI 경로 모두 403|
|본인 기안 ID에 다른 기안의 파일 ID를 넣은 Preview|403|
|본인 기안자|Preview·Submit·결과·상세·이력 정상 응답|
|같은 회사·도메인의 결재자|결과·상세·이력 조회 정상 응답|
|해당 도메인의 원청 수신자|회사 경계를 넘어 필요한 결과·상세·이력 조회 정상 응답|
|비로그인·GUEST|각각 401·403|

검사한 경로는 `/api/v1/ai/run/diagnostics/{diagnosticId}` 아래의 `/preview`, `/submit`, `/result`, `/result/detail`, `/history`입니다.

## 구현과 검증

[컨트롤러](../src/main/java/com/smartchain/platform/domain/ai/controller/AiAnalysisController.java)에서 JWT 인증 주체의 사용자 ID를 받아 [DiagnosticService의 기존 회사·도메인·소유권 정책](../src/main/java/com/smartchain/platform/domain/diagnostic/service/DiagnosticService.java)을 먼저 확인합니다. 결과 조회와 비동기 작업 등록 전에 검사하므로 차단된 요청에는 분석 결과가 반환되지 않고 AI 호출·비동기 분석 등록·Preview 패키지 변경도 일어나지 않습니다.

[Preview 서비스](../src/main/java/com/smartchain/platform/domain/ai/service/AiAnalysisService.java)는 파일의 소속 기안 ID도 대조합니다. 허용된 기안 ID를 이용해 다른 기안의 파일을 분석 대상으로 섞는 요청을 차단합니다.

[회귀 시험](../src/test/java/com/smartchain/platform/integration/AiAuthorizationHttpTest.java)은 실제 Spring Boot HTTP 서버, JWT 필터, JPA 저장 데이터와 PostgreSQL 16을 사용했습니다. HTTP 요청과 DB 권한 검사는 실제 구현을 실행하며 외부 AI 클라이언트와 비동기 분석 경계만 대체했습니다. 정상 사용자의 접근 유지와 차단된 요청의 외부 호출 부재를 함께 확인합니다. API 키와 유료 AI 호출은 사용하지 않았습니다.

동일한 HTTP 시험을 수정 전 커밋과 보완 후 커밋에 각각 실행했습니다. 수정 전에는 권한 없는 인증 사용자의 5개 경로 요청이 200/202로 허용되고 결과 내용이 반환되는 현상을 재현했습니다. 보완 후 해당 요청과 파일 혼용 요청 21개는 모두 403으로 차단됐습니다. 정상 접근·비로그인·GUEST 검사까지 포함한 **42개 검사 모두 통과**했습니다. 수정 전 실행의 성공 표시는 당시 동작의 재현에 성공했다는 뜻입니다.

- 수정 전: [4f90197c](https://github.com/withoutsultang/SmartChain_Backend/commit/4f90197cecf82d392591ae874c2978c338a91c88)
- 보완 후: [acc662fb](https://github.com/withoutsultang/SmartChain_Backend/commit/acc662fb69c1554127966ece5251980bec917eee)
- 전체 회귀 시험: **486개 중 485개 통과·1개 건너뜀, clean build 성공**. [실행](https://github.com/withoutsultang/SmartChain_Backend/actions/runs/40881578096) · [집계](backend-test-summary.json)

`AI authorization verification`은 수동으로 수정 전후의 PostgreSQL HTTP 검사를 재현합니다. 일반 `Portfolio checks`에서도 동일한 회귀 시험을 H2로 실행하며 현재 코드의 차단 정책을 검사합니다. 이 보강은 개인 포트폴리오 재검증 시점의 개선이며 팀 원본 저장소는 변경하지 않았습니다.
