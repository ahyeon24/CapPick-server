# CapPick-server

CapPick의 API 서버(Spring Boot, Java). 제품 배경·기능 사양은 `../CLAUDE.md`와 `../docs/`를 먼저 참고한다. 서버의 책임 범위와 저장 경계는 특히 `../docs/cappick-system-design-v0.2.md`, 엔터티는 `../docs/cappick-erd-v0.2.md` §4, API 계약은 `../docs/cappick-api-spec-v0.2.md`를 따른다.

## 현재 상태

**설치 검증(Installation, App Attest)**, **Job(content_analysis·classification, Gemini 연동)**, **검색어 해석(`POST /v1/search/prepare`)**, **사용량·삭제(UsageEntry, DeletionRequest)** 가 구현돼 있다 — API 명세 §3의 설치 검증 3개 엔드포인트, §4의 `POST /v1/jobs`(content_analysis·classification) · `GET /v1/jobs/{id}`, §6의 `POST /v1/search/prepare`, §7의 `GET /v1/usage` · `POST /v1/data-deletions` · `GET /v1/data-deletions/{id}`가 실제로 동작한다. 자세한 내용은 아래 "설치 검증", "Job 도메인", "자동 분류", "검색어 해석", "사용량·삭제", "전체 일일 예산 가드" 절 참고. 나머지 Job kind(folder_criteria/search_document) 중 `folder_criteria`는 만들지 않기로 결정했고(아래 "사용량·삭제" 절 참고 — 실은 `CapPick-ios/CLAUDE.md`에 근거를 남김), `search_document`는 여전히 없다.

## 스택

- Spring Boot 4.1.1, Java 21 (Gradle toolchain), Gradle 9.7.1 wrapper — `last-safe-server`(다른 프로젝트)와 버전 조합을 맞췄다.
- 의존성: web, data-jpa, validation, lombok, PostgreSQL 드라이버(`org.postgresql:postgresql`, 런타임), Flyway(`flyway-core`, `flyway-database-postgresql`, `org.springframework.boot:spring-boot-flyway`), App Attest CBOR 파싱용 `com.upokecenter:cbor`, `org.springframework.boot:spring-boot-starter-restclient`(아래 참고)
- **주의 — Spring Boot 4는 스타터를 잘게 쪼개서, "당연히 딸려올 것"이 안 딸려오는 경우가 있다.** 두 번 겪었다:
  - `flyway-core`만 추가하면 클래스패스에는 있어도 시작 시 마이그레이션이 자동 실행되지 않는다. `FlywayAutoConfiguration`이 `org.springframework.boot:spring-boot-flyway` 모듈로 분리됐다(docs.spring.io/spring-boot/appendix/auto-configuration-classes/spring-boot-flyway.html) — 처음엔 이걸 몰라서 앱이 조용히 "Schema validation: missing table" 오류로 죽었다.
  - `spring-boot-starter-webmvc`만으로는 `RestClient.Builder`가 빈으로 안 뜬다. `RestClientAutoConfiguration`도 Boot 4.1부터 `org.springframework.boot:spring-boot-starter-restclient`로 빠졌다(spring-projects/spring-boot#50768). `GeminiContentAnalysisClient`가 이 빈을 필요로 해서 처음엔 `@SpringBootTest`가 `NoSuchBeanDefinitionException`으로 죽었다.
  - 새 외부 라이브러리를 추가했는데 빈이 안 뜨거나 자동 설정이 안 먹으면, 이 문제부터 의심할 것.
- **Jackson 3.x 주의**: Boot 4.1은 Jackson 2.x가 아니라 **3.x**를 쓴다. 패키지가 `com.fasterxml.jackson.databind.*` → `tools.jackson.databind.*`(core/databind만, `jackson-annotations`는 그대로 `com.fasterxml.jackson.annotation.*`)로 바뀌었고, `JsonProcessingException`(checked)은 없어지고 `JacksonException`(unchecked)으로 통합됐다. `JsonNode.asText()`도 deprecated — `asString()`을 쓴다.
- **스키마 관리: Flyway로 확정 (2026-09-22, Installation이 첫 엔터티인 시점 결정)**. `src/main/resources/db/migration/`에 버전 SQL을 둔다. Hibernate(`ddl-auto=validate`)는 스키마를 만들지 않고 Flyway가 만든 것과 일치하는지만 검증한다.
- `spring.jackson.property-naming-strategy=SNAKE_CASE`를 전역 설정했다 — API 명세 §2 "JSON 필드명 snake_case"를 DTO마다 `@JsonProperty`로 반복하지 않기 위해서다. DTO는 camelCase로 짜면 된다.
- **DB 제품: PostgreSQL — 확정(2026-09-15 사용자 결정)**. 처음 스캐폴딩 때 `mysql-connector-j`를 잠정으로 넣었다가, "미정"이라는 문서 상태와 코드 상태가 어긋나 있는 걸 사용자가 지적해서 일단 뺐었다. 이후 MySQL/PostgreSQL 비교 후 사용자가 PostgreSQL로 확정했다 — job 결과·근거(evidence) 같은 반구조화 데이터에 JSONB가 유리하다는 게 근거였다.

### 로컬 개발 DB

로컬에 Homebrew PostgreSQL 17(`brew services`로 기동 중)에 `cappick_dev` 데이터베이스를 만들어 연결했다. `src/main/resources/application.properties`의 접속 정보(`simahyeon` 계정, 비밀번호 없음)는 이 기기 전용이라 다른 사람 기기·CI에서는 그대로 안 맞을 수 있다. 환경별 분리(profile, 환경변수)는 아직 하지 않았다.

## 패키지 구조 — 도메인 중심 (2026-09-15 확정, last-safe-server와 다름)

`src/main/java/com/cappick/` 아래:

```text
com.cappick
├── domain
│   ├── installation/   — Installation.java, InstallationStatus.java, InstallationChallenge.java,
│   │                     InstallationRepository.java, InstallationChallengeRepository.java,
│   │                     InstallationService.java, InstallationController.java, dto/ (구현됨)
│   ├── job/            — Job.java, JobKind.java, JobStatus.java, JobRepository.java,
│   │                     JobService.java, JobController.java, ContentAnalysisWorker.java(인터페이스),
│   │                     AsyncContentAnalysisWorker.java, JobStateTransitions.java, dto/ (구현됨 — content_analysis만)
│   ├── usage/          — 아직 없음
│   └── deletion/       — 아직 없음
├── external
│   ├── attestation/    — AttestationVerifier.java(인터페이스), AttestationRequest/Outcome,
│   │   │                 AssertionRequest/Outcome, DevBypassAttestationVerifier.java (구현됨)
│   │   └── apple/      — AppleAppAttestVerifier.java, AppleAppAttestProperties.java,
│   │                     AppleAuthenticatorData.java, AppleNonceExtension.java (구현됨)
│   └── contentanalysis/ — ContentAnalysisClient.java(인터페이스), ContentAnalysisRequest/Result (구현됨)
│       └── gemini/     — GeminiContentAnalysisClient.java, GeminiProperties.java (구현됨)
├── common
│   ├── AuthorizationHeaders.java — Authorization: Bearer 파싱 (구현됨, 여러 컨트롤러가 공유)
│   ├── exception/       — ChallengeNotFoundException 등 (구현됨, 아래 §12 참고)
│   └── response/        — ApiErrorBody, ApiErrorResponse, GlobalExceptionHandler (구현됨)
└── config               — AttestationConfig, ClockConfig, AiConfig, AsyncConfig (구현됨)
```

**주의**: 이전엔 `entity/`, `repository/`를 최상위에 따로 두는 안(layer 기준, last-safe-server 실제 구조와 동일)이었다가 지금 구조(domain 기준, 한 기능의 Entity/Repository/Service/Controller를 같은 폴더에)로 바꿨다. **last-safe-server를 그대로 복제한 게 아니라 의도적으로 다르게 간 것**이다 — CapPick 서버 데이터 모델이 Installation/Job/UsageEntry/DeletionRequest로 책임이 뚜렷이 나뉘어 있어 도메인 중심이 더 맞다고 판단해 사용자가 결정했다. `JobController`까지 `domain/job`에 두는 것도 지금 규모에선 허용한다 — API 계층이 커지면 그때 `api/job` 등으로 분리해도 된다.

## 설치 검증 (Installation / App Attest)

**결정 사항 (2026-09-22 사용자 확정, 이유는 대화 참고):**
1. **검증 방식: Apple App Attest + 개발용 우회.** `cappick.attestation.mode`(`application.properties`)가 `development`/`production`이면 실제 Apple 검증(`AppleAppAttestVerifier`), `bypass`면 검증을 건너뛴다(`DevBypassAttestationVerifier`). `AttestationConfig`가 `bypass` + `production` 스프링 프로필 조합이면 시작 자체를 실패시켜서 운영에서 실수로 켜지는 걸 막는다.
2. **자격 형식: 불투명 랜덤 토큰.** 32바이트 무작위 값을 base64url로 인코딩해 클라이언트에 평문으로 한 번 돌려주고, 서버에는 SHA-256 해시만 저장한다(`Installation.credentialHash`). JWT처럼 자체 서명 검증은 안 하고 매 요청마다 해시로 DB 조회한다 — 그 대신 즉시 폐기(`revoke()`)가 쉽다.
3. **Apple Developer Program: 아직 미가입.** 그래서 `cappick.attestation.mode=bypass`가 지금 유일하게 쓸 수 있는 값이고, iOS 쪽에서도 진짜 App Attest 호출(`DCAppAttestService`)은 아직 못 만들었다. 가입하면 `CapPick-ios`에 실제 attestKey/generateAssertion 연동을 붙이고, 그 페이로드로 `AppleAppAttestVerifier`를 처음으로 실제 검증해야 한다.

**엔드포인트 (API 명세 §3, 전부 구현·curl로 수동 검증함):**
- `POST /v1/installations/challenges` — 5분짜리 1회용 challenge 발급 (TTL 잠정치, §14 미정 목록 참고)
- `POST /v1/installations` — 등록. challenge를 소비하고 `AttestationVerifier.verifyAttestation()` 호출, 성공하면 `Installation` 저장 후 credential 반환(201)
- `POST /v1/installations/credentials/renew` — 갱신. `Authorization: Bearer <credential>`로 기존 설치를 찾고, 새 challenge + assertion을 검증한 뒤 credential을 교체(신뢰성 있는 재전송 방지를 위해 App Attest 카운터가 반드시 증가해야 함)

**같은 challenge 재사용, 존재하지 않는 credential로 갱신 시도 두 경우 다 curl로 직접 쏴서 409/401이 나오는 것까지 확인했다** — 자동 테스트뿐 아니라 실제 HTTP 응답으로.

**⚠️ AppleAppAttestVerifier는 진짜 Apple 기기 페이로드로 검증 안 됨.** 루트 인증서는 Apple 공식 URL(`https://www.apple.com/certificateauthority/Apple_App_Attestation_Root_CA.pem`)에서 직접 받아 `src/main/resources/apple/app-attest-root-ca.pem`에 넣고 fingerprint까지 확인했지만, CBOR 파싱·인증서 체인 검증·nonce 확장 파싱·공개키 해시 비교·서명 검증 각 단계는 전부 **합성 데이터로 만든 단위 테스트**로만 확인했다(진짜 Apple attestation object가 아님). Apple Developer 계정이 생기고 iOS에서 실제 App Attest 호출을 만들면, 그 페이로드로 이 클래스를 처음 실전 검증해야 한다 — last-safe-server의 `TagoBusPositionClient`가 "실제 키로 호출해 동작 확인은 아직 안 함"이라고 명시한 것과 같은 종류의 미검증이다.

**테스트 (71개, 전부 통과):** `InstallationServiceTest`(9, challenge 생명주기·해시 저장·재전송 방지 등 비즈니스 규칙, Mockito), `AppleNonceExtensionTest`(3)·`AppleAuthenticatorDataTest`(4, 손으로 만든 DER/authData 바이트로 파싱 검증), `EcPointEncodingTest`(50회 반복, EC 공개키 원시 포인트 인코딩·디코딩 왕복 — BigInteger의 leading-zero 버그를 반복 실행으로 잡으려는 목적), `DevBypassAttestationVerifierTest`(4).

## Job 도메인 (content_analysis, 2026-09-22)

API 명세 §4의 4개 kind 중 **content_analysis만** 구현했다. 나머지(folder_criteria, classification, search_document)는 아직 없다.

**AI 공급자: Gemini로 우선 시작 (사용자 결정, [[project-cappick]] 메모리 참고).** 추후 OpenAI와 비용·품질을 실제로 비교해볼 예정이라 **최종 결정 아님** — 그래서 `ContentAnalysisClient` 인터페이스 뒤에 `GeminiContentAnalysisClient` 하나만 두고, Service·Worker는 이 인터페이스만 안다. 공급자를 바꾸는 비용은 새 구현체 하나 추가하는 정도가 되게 하려는 목적이다.

**ERD·API 명세와 의도적으로 다르게 단순화한 부분** (Job.java 클래스 주석에도 있음):
- ERD의 `input_reference`/`input_expires_at`/`lease_until`은 아직 안 만들었다 — 별도 워커 프로세스·블롭 스토리지가 없어서 이미지를 저장할 필요 자체가 없다(요청과 같은 JVM 프로세스 안에서 바로 처리). 진짜 워커·큐를 도입하면 그때 이 필드들이 필요해진다.
- `result_reference`(별도 저장소 참조) 대신 결과 JSON을 `Job.result_json` 컬럼에 그대로 저장한다.
- `analysis_revision`(응답 필드)은 서버가 계산할 근거가 없어서 요청의 `content_revision`을 그대로 돌려주는 것으로 해석했다 — API 명세에 규칙이 없어 추정한 부분(`ContentAnalysisResultDTO` 주석).

**흐름**: `POST /v1/jobs`(multipart: `metadata` JSON 파트 + `image` 파일 파트, `Idempotency-Key`·`Authorization` 헤더 필수) → `JobService`가 같은 Installation+키로 기존 작업 있는지 확인(다르면 409, 같으면 기존 job 반환) → 없으면 QUEUED로 저장, `ContentAnalysisWorker.run()`을 비동기로 트리거, 202 즉시 응답 → `GET /v1/jobs/{id}`로 폴링.

**워커 = 지금은 그냥 스레드풀 (`AsyncConfig`, `@Async("jobExecutor")`).** 시스템 설계 §2 "메시지 큐는 부하 검증 후 결정, DB Job 테이블 기반으로 시작 가능"대로다. 서버가 재시작되면 진행 중이던 작업은 유실된다 — lease·재수집이 없다. 진짜 워커/큐를 붙일 때 다시 설계해야 한다.

**⚠️ 실제로 겪은 버그 1 — Spring self-invocation.** 처음엔 `AsyncContentAnalysisWorker` 안에 `markRunning`/`markSucceeded`/`markFailed`를 `@Transactional` 메서드로 두고 `run()`에서 `this.markRunning(...)` 식으로 불렀다. **컴파일도, Mockito 단위 테스트도 다 통과했지만, 실제로 서버를 띄워 진짜 Gemini 호출까지 돌려보니 실패했다**: `this.` 자기 자신 호출은 Spring의 트랜잭션 프록시를 안 거쳐서 `@Transactional`이 조용히 무시되고, `markRunning()`이 DB에 반영 안 된 채로 `markFailed()`가 "RUNNING이어야 하는데 QUEUED"라며 크래시했다. Mockito 테스트는 애초에 프록시가 없는 순수 객체라 이 버그를 잡을 수 없는 구조였다 — **실제 서버로 끝까지 돌려본 게 아니었으면 몰랐을 버그**. 상태 전이 세 메서드를 별도 빈(`JobStateTransitions`)으로 빼서 고쳤다 — `JobStateTransitions` 클래스 주석에 원인을 남겨뒀다. 앞으로 같은 클래스 안에서 `@Transactional`/`@Async` 메서드를 `this.`로 호출하는 코드를 보면 의심할 것.

**⚠️ 실제로 겪은 버그 2 — 같은 증상, 다른 원인: 트랜잭션 커밋 전에 `@Async` 워커를 띄움 (2026-09-22 수정, `JobService.submitContentAnalysis`).** 버그 1을 고친 뒤에도 "expected status RUNNING but was QUEUED" 예외가 **다시** 발생했다(iOS 자동 분석 큐를 실제로 돌려보다가 재현). 원인은 달랐다: `submitContentAnalysis()`가 `@Transactional`인데, 그 메서드 안에서 `jobs.save(job)` 직후 곧바로 `contentAnalysisWorker.run(...)`(`@Async`)을 호출하고 있었다. `@Async` 호출은 그 자리에서 바로 별도 스레드를 띄우는데, 감싸고 있는 `submitContentAnalysis()`의 트랜잭션은 메서드가 끝나야(즉 `run()` 호출이 리턴된 뒤) 커밋된다 — 그 사이에 워커 스레드가 먼저 `findById`를 실행하면 아직 커밋 안 된 job을 못 찾고 `markRunning`이 `Optional.ifPresent`로 조용히 no-op된다. 그 뒤 트랜잭션이 커밋되고 Gemini 호출이 끝나 `markFailed`가 도는 시점엔 job이 여전히 QUEUED라 `Job.requireStatus(RUNNING)`이 터진다. (당시 주석에 "커밋 직후에 워커가 findById해도 대부분 안전하다"고 위험을 인지하고도 넘어갔던 부분 — "대부분"이 문제였다.) `TransactionSynchronizationManager.registerSynchronization(...)`의 `afterCommit()` 콜백으로 워커 실행을 미뤄서 고쳤다 — 트랜잭션 동기화가 비활성 상태(Mockito 단위 테스트처럼 프록시 없이 직접 호출하는 경우)면 예전처럼 즉시 실행하는 폴백도 남겨뒀다. curl로 직접 6개 job을 연속 제출해 `IllegalStateException` 0건인 것까지 확인했다. **버그 1과 2는 증상(같은 예외 메시지)은 같지만 원인이 다르다** — self-invocation과 트랜잭션-커밋 타이밍은 둘 다 "@Async를 트랜잭션 경계와 무관하게 다룰 때" 생기는 별개의 함정이다.

**⚠️ 실제로 겪은 버그 3 — Gemini에 보내는 mime_type을 실제 형식과 무관하게 "image/png"로 하드코딩 (2026-09-25 발견·수정).** iOS 앱은 스크린샷을 정확히 JPEG로 인코딩해서 멀티파트 `image` 파트를 `Content-Type: image/jpeg`로 보내는데, `AsyncContentAnalysisWorker.run()`이 `ContentAnalysisRequest`를 만들 때 실제 형식을 안 보고 `"image/png"`를 그대로 박아 넣고 있었다 — 즉 서버가 Gemini에게 "이 바이트는 PNG다"라고 매번 거짓으로 알려주고 있었다. 재시도가 반복적으로 503("This model is currently experiencing high demand")로 실패하는 걸 보고 "혹시 우리 버그 아니냐"고 의심하다가 발견했다(사용자가 재확인을 요청한 덕분). **다만 리서치로 확인한 결과 이 mime_type 불일치가 503의 원인은 아니다** — Gemini API 개발자 포럼의 여러 독립된 사례(전혀 다른 요청·페이로드)가 똑같은 503 문구를 보고하고, 유료 티어에서도 30일간 37% 실패율을 겪은 사례까지 있어 이건 잘못된 입력이 아니라 모델 자체의 용량 부족(특히 `gemini-flash-latest` 같은 alias가 방금 나온 신규 모델을 가리킬 때 흔함, 출시 후 1~3주 지속 보고됨)이 원인이다 — 입력이 잘못됐다면 503이 아니라 400으로 나왔을 것이다. **그래도 이 mime_type 불일치는 별개로 명백한 버그라 고쳤다** — `JobController`가 `MultipartFile.getContentType()`을 그대로 `JobService.submitContentAnalysis(...)`→`ContentAnalysisWorker.run(...)`까지 흘려보내고, 없으면 `image/jpeg`로 기본값을 둔다(하드코딩된 값을 없애되, 실제로 클라이언트가 항상 보내는 형식으로 기본값을 맞춤). `AsyncContentAnalysisWorkerTest`에 mimeType이 그대로 전달되는지 고정하는 회귀 테스트를 추가했다.

**실제 검증 (curl + 진짜 Gemini API):**
1. 설치 등록 → `POST /v1/jobs`로 실제 스크린샷 제출 → 202 확인
2. 자기 수정 전: 위 self-invocation 버그로 크래시하는 것 확인 → 고침 → 재검증
3. 고친 뒤: 실제 서버가 실제 Gemini에 요청을 보내고, **의도한 실패 경로(429 quota 초과 → `FAILED` + `error.code=ANALYSIS_FAILED`)가 API 명세 §2 형식 그대로 클라이언트에 나가는 것까지 확인했다.**
4. **진짜 성공 케이스(2026-09-25 재검증) — 확인 완료.** 같은 날 앞서 `gemini-flash-latest`(→`gemini-3.8-flash`, 9월 2일 출시)와 `gemini-3.7-flash` 둘 다 반복적으로 503 "high demand"로 실패했다(job 4개 연속, 모두 실제로 Gemini까지 갔다 온 3~4초대 응답 — 우리 쪽 버그가 아니라 외부 리서치로 "신규 모델 출시 직후 1~3주 흔한 과부하" 패턴임을 확인). 모델을 `gemini-3.5-flash`(2026-05-19 출시, 더 오래돼서 붐빌 이유가 적은 버전)로 바꾸자 바로 성공했다 — job `a825748f-621b-466f-babb-a793c246db89`, 13초 만에 `SUCCEEDED`, `ocr_text`·`observed_evidence`·`search_text`·`embedding` 전부 실제 스크린샷(사주 명리 앱 화면) 내용과 정확히 일치하는 진짜 값으로 채워짐. **현재 `cappick.ai.gemini.model=gemini-3.5-flash`로 고정돼 있다** — AI 공급자·모델 최종 확정은 여전히 아니고, 신규 모델(3.8) 과부하가 가라앉으면 alias로 되돌릴 수 있다.

**출력 토큰(=비용) 줄이기 (2026-09-27).** 실사용 데이터 기반 비용 추정(월 1000장 기준 사용자당 2~4만원)을 보고 가장 손대기 쉬운 지점부터 줄였다 — `GeminiContentAnalysisClient.PROMPT`에서 `observed_evidence`는 "최대 5개, 각 30자 내외", `search_text`는 "1~2문장, 60자 이내"로 명시하고, `analysisSchema()`에도 `observed_evidence`에 `maxItems: 5`(Gemini structured output이 실제로 강제하는 걸로 문서화된 필드)를 추가했다. `items`/`search_text`의 `maxLength`는 강제 여부가 불확실해 best-effort 힌트로만 추가함(안 지켜져도 손해 없음). **`ocr_text`는 의도적으로 안 건드렸다** — "있는 그대로 전부" 추출이 OCR의 핵심 기능이라 여길 줄이면 검색 품질이 떨어진다(텍스트 밀도 높은 캡처는 원래도 출력이 길어지는 게 정상). **아직 실제 Gemini 호출로 토큰 감소량을 검증하지 않았다** — 무료 티어 RPD 예산이 빠듯해서(위 "자동 분류" 절 참고) 검증용 호출도 비용이라 사용자와 상의 후 실행할 것. 기존 `MockRestServiceServer` 기반 단위 테스트는 응답 바디만 고정하고 요청 스키마는 검증하지 않아 이 변경으로 깨지지 않음(전체 테스트 재확인 완료).

**분석 모델을 flash-lite로 내리고 OCR을 기기로 옮김 (2026-10-08, 사용자 결정 — 비용 절감).**
`cappick.ai.gemini.model=gemini-3.5-flash-lite`(유료 단가 입력 $0.30·출력 $2.50 / 1M, flash의 약
1/4). `gemini-eval/`의 18장(맥 스크린샷)으로 실제 호출해 잰 값(환율 1,400원 가정):

| 구성 | 입력 토큰 | 출력 토큰 | 장당 |
|---|---|---|---|
| flash-lite, 모델이 OCR까지 | 1,253 | 239 | 약 1.4원 |
| flash-lite, OCR은 기기(관찰·요약만) | 1,223 | 95 | 약 0.85원 |

생각 토큰은 한 번도 안 붙었고, 9/27의 분량 제한(observed_evidence 최대 5개·search_text 60자)도
실제로 지켜졌다(위 "출력 토큰 줄이기" 문단의 "아직 검증 안 함"은 이걸로 해소). **flash-lite는
글자 많은 화면에서 OCR을 빠뜨린다** — 04번(강의 슬라이드 3장이 한 화면)은 9월 flash 계열이
1,066자를 읽었는데 190자만 읽었다. 그래서 OCR은 앱이 Apple Vision으로 해서 `metadata.ocr_text`
(API 명세 §4 "선택 ocr_text", 원래부터 `ContentAnalysisRequest.ocrTextHint`까지 배선돼 있던 값)로
보내고, `GeminiContentAnalysisClient`는 힌트가 있으면 `PROMPT_WITHOUT_OCR`·ocr_text 없는 스키마로
호출한 뒤 힌트를 그대로 결과 `ocr_text`로 돌려준다. **힌트가 비어 있으면(기기 인식 실패·글자
없음) 예전처럼 모델이 OCR까지 한다.** OCR 텍스트를 모델 입력으로 다시 넣지는 않는다(이미지를
직접 보므로). 같은 `model` 값이 `/v1/search/prepare`에도 쓰이는데 그쪽은 flash-lite로 실제 호출해
본 적이 없다. 일일 예산도 두 kind가 같은 RPD 500을 나눠 쓰게 돼서 content-analysis=200·
classification=250으로 다시 나눴다. 테스트 2개 추가(힌트 사용·빈 힌트 폴백), 총 133개 통과.
비교 스크립트·결과 화면은 `../gemini-eval/`(git 밖)에 있다.

**테스트 (15개 추가, 총 86개 전부 통과):** `JobServiceTest`(5, 멱등성·소유권), `JobStateTransitionsTest`(4, 상태 전이 로직), `AsyncContentAnalysisWorkerTest`(2, 오케스트레이션 — 실제 버그를 재현하진 못하는 한계가 있다는 걸 위 문단에 명시함), `GeminiContentAnalysisClientTest`(4, `MockRestServiceServer`로 실제 검증된 요청/응답 형태 고정).

## 자동 분류 (`classification` Job kind, AI-03, 2026-09-25)

API 명세 §4 4개 kind 중 **classification**을 content_analysis에 이어 구현했다(folder_criteria·
search_document는 아직 없음). 기능명세서 §3.3.1 "직접 근거 기반 분류 규칙"(간접 연관성만으로
소속을 추측하지 않음, 여러 폴더에 각각 근거가 있으면 모두 match 가능, AI가 새 폴더를 추천·생성
하지 않음)을 그대로 프롬프트에 옮겼다 — `GeminiClassificationClient.RULES` 상단 주석·본문 참고.

**흐름은 content_analysis와 다르다 — 이미지가 아니라 텍스트만 오간다.** classification은
`POST /v1/jobs`를 그대로 쓰지만 **`application/json`**이다(content_analysis는 multipart).
`JobController`가 `Content-Type`으로 두 메서드를 나눈다 — 지금은 JSON kind가 classification
하나뿐이라 바로 그 DTO로 바인딩하지만, folder_criteria·search_document가 생기면 바디의 `kind`
필드로 다시 분기해야 한다(주석에 명시). 입력은 content_analysis가 이미 뽑아둔
`ocr_text`/`observed_evidence`와, 판단 대상 폴더들의 `id`/`criteria_text`/`criteria_version`
목록 — 원본 이미지를 다시 보내지 않는다.

**classification은 폴더 소속을 저장하는 API가 아니다(API 명세 §4).** 요청한 폴더별로
`match`/`no_match`/`uncertain` 판정과 근거만 돌려준다. 실제로 `FolderCapture`에 반영하는 건
클라이언트(로컬 DB) 책임이고, 이 서버 작업은 아직 이 판정을 부르는 로컬 큐·UI와 연결돼 있지
않다 — 이번 작업은 "서버 뼈대"까지다.

**API 명세 §4 "누락된 판정은 성공한 no_match로 간주하지 않는다"를 강한 불변식으로 구현했다.**
`GeminiClassificationClient.classify()`가 Gemini 응답을 요청한 폴더 집합과 대조해:
- 응답에 없는 folder_id(모델이 판정을 빠뜨림) → `uncertain`으로 채워 넣는다(`no_match`로
  둔갑시키지 않음).
- 요청에 없는 folder_id(모델이 지어냄) → 버린다.
- `criteria_version`은 모델 응답을 신뢰하지 않고 항상 원래 요청 값을 그대로 쓴다 — 모델이 버전
  번호를 잘못 옮겨 적을 이유를 아예 만들지 않기 위해서다(모델에게 애초에 요구하지 않음).

이 불변식 덕에 호출자는 항상 "요청한 폴더 수 == 응답 판정 수"를 신뢰할 수 있다.

**Job.contentRevision 컬럼을 그대로 재사용한다** — classification에서는 `analysis_revision`을
담는다(스키마 변경 없음, content_analysis의 `content_revision`과 같은 컬럼이지만 의미만 다르게
씀). **`JobStatusResponse.result`를 `ContentAnalysisResultDTO` 고정 타입에서 `Object`로
일반화했다** — kind마다 result 모양이 달라서(`JobService.getStatus`가 `job.getKind()`로 분기해
`ContentAnalysisResultDTO`/`ClassificationResultDTO` 중 하나로 역직렬화), 이걸 안 고치면 GET이
항상 content_analysis 모양으로 (잘못) 파싱을 시도했을 것이다.

**`JobStateTransitions.markSucceeded`/`markClassificationSucceeded`가 `finishSucceeded` 공통
로직을 공유하도록 리팩터링했다** — 결과 JSON 직렬화·TTL 계산·실패 시 대체 처리가 kind와 무관하게
같아서다.

**`folder_criteria` kind(폴더 생성 시 모호한 기준을 AI가 다듬어주는 것)는 만들지 않기로
결정함(2026-09-26, 사용자 결정, 근거는 `CapPick-ios/CLAUDE.md`에 남김)** — AI가 기준을
"구체화"하는 과정 자체가 사용자 의도를 오히려 좁힐 위험이 있다고 판단해서, 지금처럼 폴더의
`criteriaText`를 사용자가 입력한 그대로(또는 비었으면 폴더 제목으로) classification에 넘기는
느슨한 방식을 유지한다. 이 결정 이후 `FolderCapture` 반영·자동 제출 큐·RECLASS-01 재분류는
iOS 쪽에서 이미 구현됐다(`CapPick-ios/CLAUDE.md` "AI-03"·"RECLASS-01" 절 참고) — 이 문단이
한동안 갱신 안 돼서 "아직 안 됨"으로 남아있었다.

**classification 전용 모델로 분리함 (2026-09-26).** 처음엔 content_analysis와 같은
`cappick.ai.gemini.model`(`gemini-3.5-flash`)을 그대로 썼는데, AI Studio 대시보드로 실측한 결과
`gemini-3.5-flash`·`gemini-3.8-flash`가 무료 티어 RPD(하루 요청 한도)가 똑같이 20인 반면
`gemini-3.5-flash-lite`는 RPD 500이었다 — classification은 이미 뽑아둔 텍스트를 폴더 기준과
비교하는 반복적인 구조화 판단이라(이미지를 다시 이해할 필요 없음) 더 가벼운 모델로도 충분하다고
판단해 `cappick.ai.gemini.classification-model=gemini-3.5-flash-lite`로 분리했다
(`GeminiProperties.classificationModel()`, `GeminiClassificationClient`만 사용). **모델별로 RPD가
독립적이라 이 분리만으로 하루 처리 가능한 캡처 수가 실질적으로 늘어난다** — 예전엔 캡처 한 장이
content_analysis+classification 두 요청을 같은 20 RPD에서 나눠 써서 하루 최대 10장이었는데, 이제
content_analysis 20 RPD를 거의 그대로 다 쓸 수 있다(classification은 별도 500 RPD라 병목이 아님).
`gemini-3.8-flash`로 올리지 않은 이유는 RPD가 3.5와 동일한데 출시 직후 503 과부하를 이미 겪은
전례가 있어서다(위 "실제 검증" 절 4번 참고) — 리스크만 지고 얻는 게 없다.

**출력 토큰(=비용) 줄이기 (2026-10-08).** 분류 비용이 폴더 수에 비례해 늘던 원인은 모델이
폴더마다 써내던 출력이었다(로컬 DB의 실제 결과 5건 기준 폴더당 JSON 약 150자 — 36자 UUID
되받아쓰기 + no_match에도 붙던 근거 문장). 두 가지를 바꿨다: (1) 모델과는 실제 `folder_id` 대신
요청 순서대로 매긴 번호(1부터)만 주고받고 `GeminiClassificationClient`가 다시 `folder_id`로
되돌린다, (2) `evidence`는 match일 때만(한 문장 40자 내외) 쓰게 하고 스키마 `required`에서 뺐다 —
no_match·uncertain은 `evidence`가 빈 문자열로 나간다. API 응답 모양은 그대로다(`folder_id`·
`evidence` 필드 유지). **앱 쪽 영향**: 자동 분류는 match의 근거만 저장하므로 변화 없음. RECLASS-01
미리보기의 "자동 제거 예정" 행은 이제 근거 문구가 안 나온다(앱이 빈 근거는 숨김). "요청에 없는
폴더는 버리고 빠진 폴더는 uncertain" 불변식은 번호 기준으로 그대로 유지. 테스트 1개 추가(번호↔ID
매핑·요청 본문에 실제 ID가 안 나가는지·evidence 생략 처리).

**실제 Gemini 검증 (2026-10-08, `../gemini-eval/classify_*.py`, 18장 × 테스트 폴더 5개, flash-lite)**:
장당 평균 출력 435→151토큰, 입력 786→652토큰, 약 1.85원→0.80원(환율 1,400원 가정). 같은 요청을
4번씩 보내 보니 **형식과 무관하게 판정이 원래 흔들렸다**(회차마다 갈린 폴더 판정: 변경 전 10/90,
변경 후 9/90 — 전부 "공부"·"코딩" 같은 한 단어 기준의 경계 사례). 다만 변경 후에는 uncertain이
줄고 match/no_match로 확정하는 쪽으로 성향이 조금 바뀌었다. 흔들림을 줄이려고
`generationConfig.temperature=0`을 넣었고, 같은 테스트(3회씩)에서 갈린 판정이 2/90으로 줄었다
(완전히 0은 아님). 시뮬레이터 E2E로도 확인: 새 스크린샷 3장이 content_analysis→classification
전부 SUCCEEDED, 사주 화면이 "운세" 폴더에 자동으로 들어갔다.

**테스트 (12개 추가, 총 106개)**: `JobServiceTest`(4, classification 제출·멱등성·GET 파싱),
`AsyncClassificationWorkerTest`(2), `JobStateTransitionsTest`(1),
`GeminiClassificationClientTest`(5, `MockRestServiceServer` — 누락 폴더 uncertain 처리·지어낸
folder_id 제거 케이스 포함). **실제 Gemini 응답으로 검증한 적은 아직 없다** — 이 세션 내내 무료
한도(quota)와 일시적 과부하(503) 때문에 content_analysis 재검증도 못 끝난 상태라, classification
프롬프트·스키마는 `GeminiSearchInterpretationClient`와 마찬가지로 **아직 한 번도 진짜 Gemini
응답으로 확인 안 된 잠정 형태**다. 다음에 Gemini가 정상 동작할 때 실제 응답부터 확인할 것.

## 검색어 해석 (`POST /v1/search/prepare`, 2026-09-22)

API 명세 §6. **Job이 아니다** — `content_analysis`처럼 큐잉·폴링하지 않는 동기 요청/응답 하나로 끝난다.
`domain/search/{SearchController, SearchService, dto/}`, `external/searchinterpretation/{SearchInterpretationClient
(인터페이스), SearchInterpretationResult, gemini/GeminiSearchInterpretationClient}`.

**실제 캡처 검색(키워드·의미 결합, 폴더·날짜·접근가능 필터)은 서버가 아니라 기기에서 한다** — 이
엔드포인트는 자연어 질의를 해석해서 `normalized_query`/`lexical_terms`/`captured_from`/`captured_before`/
`date_interpretation`/`embedding`만 돌려준다. 서버는 스크린샷·폴더 데이터를 전혀 다루지 않는다
(시스템 설계 §5).

**`GeminiContentAnalysisClient`와 코드를 공유하지 않는다 — 의도적인 중복.** `callGemini()`/구조화
출력 스키마 패턴은 거의 같지만, 이미지 분석과 검색어 해석이 서로 다른 AI 공급자로 독립적으로 바뀔
수 있어야 한다는 게(컨벤션 #8) 두 인터페이스를 애초에 나눈 이유라, 구현도 따로 둔다. `GeminiProperties`
(모델·임베딩모델 설정)만 그대로 재사용한다 — 지금은 같은 Gemini 프로젝트를 쓰지만 우연일 뿐이다.

**`embedding_model_version` 불일치 = 409 `SEARCH_MODEL_MISMATCH`, Gemini 호출 전에 즉시 실패.**
클라이언트가 로컬 캡처들을 임베딩한 모델 버전을 요청에 실어 보내고, 서버가 지금 쓰는
`GeminiProperties.embeddingModel()`과 다르면 비교 자체가 무의미해서(차원·공간이 다른 벡터) 바로
거절한다 — `SearchServiceTest`가 이 경우 Gemini를 아예 안 부르는 것까지 확인한다.

**날짜 해석은 Gemini에게 통째로 맡긴다.** `now`+`timezone`을 프롬프트에 넣고 "지난달"·"어제" 같은
표현을 ISO-8601 UTC 구간(시작 포함·끝 미포함)으로 계산해 달라고 구조화 출력으로 받는다. 서버가 직접
자연어 날짜 파싱 라이브러리를 쓰지 않는다 — 프롬프트·스키마가 잘못돼도 `parseInstantOrNull`이
조용히 null로 떨어뜨려서 검색 자체는 실패시키지 않는다(날짜 조건 없음으로 취급).

**실제 검증 (curl, GEMINI_API_KEY 없이):** 모델 버전 불일치 → 409 `SEARCH_MODEL_MISMATCH` 확인,
정상 버전인데 키가 없어서 Gemini 호출 실패 → 503 `SEARCH_UNAVAILABLE`(retryable=true) 확인,
Authorization 헤더 없음 → 400. **실제 Gemini 응답으로 `date_interpretation`·`lexical_terms` 품질을
검증하는 건 아직 못 했다** — 오늘 이미 무료 한도가 소진된 상태였다. 다음에 `GEMINI_API_KEY`가 있는
상태로 서버를 띄우면 실제 응답부터 확인할 것 — 프롬프트("검색어 해석 프롬프트", `GeminiSearchInterpretationClient`
상단)는 실제 스크린샷으로 검증된 `GeminiContentAnalysisClient`의 프롬프트와 달리 **아직 한 번도
진짜 Gemini 응답으로 확인 안 된 잠정 프롬프트**다.

**테스트 (8개 추가, 총 94개):** `SearchServiceTest`(2, 정상 흐름·모델 불일치 시 Gemini 미호출),
`GeminiSearchInterpretationClientTest`(6, `MockRestServiceServer` — 요청/응답 형태는 실제
Gemini로 검증된 게 아니라 `GeminiContentAnalysisClient`의 실제 검증된 형태를 참고해 **추정**한
것이므로, 실제 응답을 받으면 이 테스트의 스텁 JSON도 맞춰 갱신해야 할 수 있다).

## 사용량·삭제 (UsageEntry·DeletionRequest, 2026-09-26)

API 명세 §7, ERD §4. `domain/usage/`·`domain/deletion/`은 그동안 디렉토리만 있고 파일이
0개였다 — 이번에 채웠다. `GET /v1/usage`, `POST /v1/data-deletions`, `GET /v1/data-deletions/{id}`
가 실제로 동작한다.

**사용량 추적은 실제로 하되, 한도는 막지 않는다(사용자 결정, 2026-09-26).** `used_units`·
`reserved_units`는 `UsageEntry`(예약/확정/해제 원자적 전이, ERD §4 그대로)로 실계산해서 돌려주지만,
`limit_units`·`resets_at`은 항상 null, `can_submit`은 항상 true다 — ERD §6·API 명세 §8이
"사용량 단위와 한도"를 명시적으로 미정이라고 못박아서, 임의 숫자로 확정하지 않았다(§14 컨벤션).
`UsageEntry.operationKey`는 Job의 `idempotencyKey`를 그대로 재사용한다 — Job 하나당 사용량
항목이 정확히 하나만 생기고, Job이 멱등 재제출되면 `UsageService.reserve()`가 중복 생성 없이
기존 항목을 그대로 돌려준다. **"Job 1건 = 1 unit"은 잠정 단위다** — 실제 과금 단위는 여전히
미정. Job 성공 시 `confirm()`(과금 확정), 실패 시 `release()`(과금 안 함)로 전이한다 — "실패
비용" 정책 자체는 미정이라 "실패하면 과금 안 함"을 잠정 기본값으로 뒀다(API 명세 §5).

**`folder_criteria` Job kind는 만들지 않기로 결정했다(2026-09-26, 사용자 결정).** 폴더 생성 시
모호한 기준을 AI가 "구체화"하는 기능을 검토했지만, 그 구체화 자체가 또 하나의 해석 단계라
사용자 의도를 오히려 좁힐 위험이 있다고 판단했다 — 상세 근거와 대안(느슨한 기준 + 쉬운 수동
교정)은 `CapPick-ios/CLAUDE.md`에 남겼다. 이 결정 덕분에 이번 작업(usage/deletion)에만 집중할
수 있었다.

**설치 콘텐츠 전체 삭제 = Job 행 삭제.** 지금 `Job`은 이미지 바이트를 저장하지 않고
`result_json`만 갖고 있어서(위 "Job 도메인" 절 참고), 행 자체를 지우는 게 곧 콘텐츠 삭제다.
`JobRepository.deleteByInstallation()`으로 지운다. **덕분에 "늦게 도착한 결과 저장 차단"은
별도 코드 없이 공짜로 해결됐다** — `JobStateTransitions`의 모든 전이 메서드가 이미
`jobs.findById(jobId).ifPresent(...)` 관용구를 쓰고 있어서(위 "실제로 겪은 버그 2" 절의
self-invocation 버그를 고치며 이미 있던 패턴), 삭제된 뒤 도착한 워커 결과는 `findById`가 빈
값을 반환해 조용히 no-op된다. `UsageEntry`는 삭제해도 안 건드린다 — "사용량 보호 기록은
콘텐츠 삭제와 별도 정책"이라는 ERD §4 설명을 그대로 따른 것으로, curl로 직접 삭제 전후
`usage_entry` 테이블 내용이 그대로인 것까지 확인했다.

**설치 전체 삭제 진행 중엔 새 Job 제출을 막는다(API 명세 §7 "처리 중 앱도 신규 제출을
멈춘다").** `JobService.submitContentAnalysis`/`submitClassification` 맨 앞에서
`DeletionRequestRepository.existsActiveByInstallation()`(PENDING·PROCESSING을 active로
간주)을 확인해 활성 삭제가 있으면 409 `DELETION_IN_PROGRESS`로 거부한다.

**⚠️ 실제로 겪은 버그 — Spring 빈 순환 참조(self-invocation과는 다른 원인).** 처음엔
`AsyncDeletionWorker`가 상태 전이(`markProcessing`/`deleteContent`/`markFailed`)를
`DeletionService`의 메서드로 직접 호출하게 만들었다. `./gradlew test`의
`CapPickServerApplicationTests.contextLoads()`가 `BeanCurrentlyInCreationException`으로
바로 실패했다 — `DeletionService`가 비동기 디스패치를 위해 `DeletionWorker`(인터페이스)를
의존하는데, 그 구현체 `AsyncDeletionWorker`가 다시 `DeletionService`를 의존하니 Spring이 두
빈을 서로 먼저 만들 수가 없었다. `ContentAnalysisWorker` 계열이 상태 전이를 `JobStateTransitions`
라는 별도 빈으로 뺀 것과 똑같은 이유(원인은 self-invocation으로 다르지만)로,
`DeletionStateTransitions`를 새로 만들어 `AsyncDeletionWorker`가 그걸 의존하게 바꿔서 순환을
끊었다. **Mockito 단위 테스트로는 이 버그를 못 잡는다** — 순수 Java 객체 조립이라 순환이 있어도
그냥 되기 때문에, `contextLoads()`처럼 실제 Spring 컨테이너를 띄우는 테스트가 있어야만 드러난다
(Job 쪽 self-invocation 버그를 "실제 서버로 끝까지 돌려본 게 아니었으면 몰랐을 버그"라고 남긴
것과 같은 교훈 — 이번엔 통합 테스트 한 줄이 그 역할을 대신 해줬다).

**실제 검증 (curl + 로컬 PostgreSQL, GEMINI_API_KEY 없이):**
1. 설치 등록 → content_analysis Job 제출 → GEMINI_API_KEY가 없어 즉시 FAILED로 끝남 →
   `usage_entry` 테이블에서 해당 Job의 `operation_key`가 `RELEASED` 상태인 것 확인(예약 →
   실패 → 해제가 실제로 원자적으로 돌아감).
2. `POST /v1/data-deletions` → `GET /v1/data-deletions/{id}`로 `completed` 확인 → 삭제 전
   Job을 `GET /v1/jobs/{id}`로 조회하면 404 `JOB_NOT_FOUND`(행이 실제로 삭제됨) → 같은
   Idempotency-Key로 재요청하면 새로 안 만들고 기존 `completed` 그대로 반환 → `usage_entry`는
   삭제 후에도 그대로 남아있음(위 설명대로).
3. **"진행 중 새 Job 제출 차단"은 라이브 curl로는 못 재현했다** — 삭제 자체가 DB 삭제뿐이라
   PENDING/PROCESSING으로 머무는 시간이 너무 짧아서(밀리초 단위), 순차 curl 호출 사이에 이미
   COMPLETED로 끝나 있었다. 이 부분은 `JobServiceTest`가 `existsActiveByInstallation`을
   mocking해서 결정론적으로 검증한다(라이브 검증이 필요하면 `DeletionStateTransitions.
   deleteContent`에 인위적 지연을 넣고 다시 볼 것).

**테스트 (23개 추가, 총 126개):** `UsageServiceTest`(6, 예약 멱등성·confirm/release 전이·
카테고리별 집계), `DeletionServiceTest`(4, 멱등 재제출·진행 중 삭제 차단·소유권 스코프),
`DeletionStateTransitionsTest`(5, PENDING→PROCESSING→COMPLETED/FAILED 전이·Job 삭제 호출
확인), `AsyncDeletionWorkerTest`(2, 성공/실패 경로에서 전이 메서드 호출 순서 확인),
`JobServiceTest`(2 추가, Job 제출 시 실제 예약되는지·진행 중 삭제 시 차단되는지),
`JobStateTransitionsTest`(기존 3개 테스트에 confirm/release 호출 검증 추가).

## 전체 일일 예산 가드 (`UsageService.requireDailyBudgetAvailable`, 2026-09-27)

**문제**: 서버 전체가 Gemini 키 하나를 공유한다(설치별 쿼터 분리 없음, 루트 CLAUDE.md·§14
미정 사항 참고). 무료 티어 RPD(요청 수/일)는 **키가 아니라 프로젝트 단위**이고(2026-09-27
웹 확인, `ai.google.dev/gemini-api/docs/rate-limits` — "applied per project, not per API
key"), 실측치는 content_analysis 계열 flash 모델 20, classification용 flash-lite 500(AI
Studio 대시보드 2026-09-26 확인, 위 "자동 분류" 절 참고). 진짜 "설치별 격리"를 설계해도 나눌
파이 자체가 사용자 한 명 몫도 안 될 만큼 작다는 게 근본 제약이라, 지금 단계에서는 **설치별
공정 분배가 아니라 전체 예산 소진 시 그레이스풀 종료**만 구현했다(사용자 결정, 2026-09-27).

**구현**: `UsageEntryRepository.countByCategoryAndCreatedAtGreaterThanEqual`로 카테고리별 전체
설치 합산 오늘자 건수를 세고(RESERVED·CONFIRMED·RELEASED 전부 포함 — Gemini가 실패 응답도
요청 자체는 카운트할 가능성이 있어 확인 안 된 채로 안전한 쪽으로 셌다), `UsageBudgetProperties`
(`cappick.usage.daily-budget.*`)에 설정된 한도 이상이면 `DailyBudgetExhaustedException` →
429 `DAILY_BUDGET_EXHAUSTED`(재시도 가능)로 즉시 거부한다. `JobService.submitContentAnalysis`/
`submitClassification`의 새 Job 생성 경로(멱등 재제출 분기 이후)에서 호출한다. "오늘"의 기준은
Gemini RPD 리셋 시각인 **태평양 시간 자정**이다(서버 자체는 UTC로 돔, `ClockConfig`) —
`UsageService.RPD_RESET_ZONE`.

**의도적으로 domain 계층에 "Gemini"라는 이름을 안 끌어들였다** — `UsageBudgetProperties`는
`GeminiProperties`와 분리된 별도 `@ConfigurationProperties`(컨벤션 #4 "외부 의존성 격리"). 한도
숫자 자체는 지금 유일한 공급자인 Gemini의 실측 RPD에서 나왔지만, 이 레이어는 그 사실을 몰라도
되게 했다.

**알려진 한계 (이번엔 안 고침)**:
- 설치별 공정 분배 없음 — 지금은 실사용 설치가 사실상 1개뿐이라 "한 설치가 예산을 독차지"하는
  상황 자체가 없다. 진짜 다중 사용자가 생기면 그때 설치별 상한을 추가로 설계해야 한다.
- content_analysis Job은 실제로 Gemini 호출을 두 번 한다(generateContent + embedContent,
  `GeminiContentAnalysisClient` 참고). 여기 한도는 generateContent 모델의 실측 RPD 기준이고,
  embedContent가 쓰는 embedding-model의 실제 RPD는 아직 확인 안 했다 — 더 낮은 것으로 확인되면
  이 값을 낮춰야 한다.
- 설정값(content-analysis=18, classification=450)은 실측 RPD(20·500)보다 낮춘 안전 마진일 뿐
  확정된 제품 정책이 아니다.

**실제 검증 (curl, GEMINI_API_KEY 없이 — 서버 단에서 막히므로 실제 Gemini 쿼터 소모 없음)**:
`--cappick.usage.daily-budget.content-analysis=2`로 띄운 뒤 새 설치로 content_analysis Job을
3번 연속 제출 → 실제 로컬 DB에 태평양 시간 "오늘" 기준 이미 쌓여있던 실사용 데이터(7건, 이
세션에서 실제로 캡처를 분석해본 기록)까지 정확히 집계돼 3번 다 429 `DAILY_BUDGET_EXHAUSTED`로
즉시 거부되는 것 확인 — 합성 테스트 데이터가 아니라 실제 누적 데이터로 경계 계산이 맞는지까지
검증한 것.

**테스트 (5개 추가, 총 130개)**: `UsageServiceTest`(3, 한도 미설정 시 통과·한도 이내 통과·한도
도달 시 예외), `JobServiceTest`(2, 두 kind 모두 제출 시 가드 호출 검증 + content_analysis
예산 소진 시 Job 미생성 확인).

## 코딩 컨벤션

`last-safe-server`에서 검증된 패턴 중 CapPick에 맞는 부분(엔터티 불변성, 소유권 스코프 쿼리, 외부 연동 인터페이스 격리)을 가져오고, CapPick의 시스템 설계·ERD·API 계약에 필요한 부분(멱등성, 상태 전이, Job 비동기 처리, 임시 데이터 삭제, 로그 민감정보 제외)을 CapPick 전용으로 확장한 것이다. last-safe-server 구조를 그대로 복제하지 않는다.

CapPick 서버 코드가 우선하는 네 가지: **① 소유권** — Installation의 데이터가 다른 Installation에 노출되지 않는다. **② 멱등성** — 같은 논리 작업 때문에 AI 비용과 데이터가 중복 생성되지 않는다. **③ 상태 일관성** — 비동기 작업과 늦은 응답이 최신 상태를 망가뜨리지 않는다. **④ 외부 의존성 격리** — AI 공급자·인프라 선택이 바뀌어도 핵심 도메인 로직을 최대한 유지한다.

### 1. 엔터티 — setter 없이 상태 전이를 표현

`@Data`·공개 setter를 쓰지 않는다. `@Getter` + `@NoArgsConstructor(access = PROTECTED)`(JPA용) + 생성 시 필요한 값만 받는 `@Builder` 생성자.

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installation_id", nullable = false)
    private Installation installation;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Builder
    public Job(Installation installation, String idempotencyKey, JobKind kind) {
        this.installation = installation;
        this.idempotencyKey = idempotencyKey;
        this.kind = kind;
        this.status = JobStatus.QUEUED;
    }

    // 상태 변경은 job.setStatus(...)가 아니라 의미가 드러나는 메서드로 한다.
    public void start() { /* QUEUED -> RUNNING, 허용 안 된 전이면 예외 */ }
    public void succeed(/* result */) { /* RUNNING -> SUCCEEDED */ }
    public void fail(String errorCode) { /* RUNNING -> FAILED */ }
}
```

### 2. Enum — DB에는 문자열로 저장

의미 있는 상태값은 문자열/숫자를 코드 곳곳에 직접 쓰지 않고 Enum으로 정의하고, `@Enumerated(EnumType.STRING)`을 쓴다. ordinal 저장은 쓰지 않는다 — enum 선언 순서가 바뀌어도 기존 DB 값 의미가 안 변하게.

### 3. Repository — Installation 소유권을 쿼리에 포함

ERD §5: "서버는 모든 Job·Usage·Deletion 조회 시 요청 설치와 소유 설치가 같은지 검증한다." `findById()` 후 서비스에서 소유권을 검사하는 방식을 기본으로 쓰지 않는다.

```java
public interface JobRepository extends JpaRepository<Job, Long> {
    Optional<Job> findByIdAndInstallation(Long id, Installation installation);
    Optional<Job> findByInstallationAndIdempotencyKey(Installation installation, String idempotencyKey);
}
```

적용 대상: Job, UsageEntry, DeletionRequest — Installation 소유로 추가되는 모든 서버 데이터. 다른 Installation의 자원은 존재 여부를 노출하지 않는다(API 명세 §2: 401/404 규칙). 단, 워커나 내부 관리 로직처럼 사용자 요청의 소유권 검사가 목적이 아닌 경우엔 별도 쿼리를 정의해도 된다.

### 4. Controller — 비즈니스 로직을 두지 않는다

Controller는 HTTP 요청 수신, 입력 DTO 검증, Installation 정보 전달, Service 호출, 응답 변환만 한다. Job 상태 판단·멱등성 판단·사용량 예약·AI 호출 여부·재시도 판단·삭제 상태 변경 같은 로직은 Service나 도메인 객체가 담당한다.

### 5. DTO와 Entity를 분리

API 요청/응답(`JobCreateRequest`, `JobStatusResponse` 등)과 JPA Entity를 직접 공유하지 않는다. Controller에서 Entity를 그대로 반환하지 않는다 — Entity 변경이 바로 API 계약 변경으로 이어지지 않게 한다.

### 6. 멱등성 — 비용 발생 작업의 핵심 규칙

API 명세 §2 기준: 같은 Installation에서 같은 `Idempotency-Key` + 같은 request digest → 기존 결과 재사용. 같은 키 + 다른 digest → `409 IDEMPOTENCY_CONFLICT`. Controller의 단순 중복 체크 수준으로 구현하지 않는다. Job 생성과 Usage 예약처럼 함께 성공·실패해야 하는 DB 변경은 트랜잭션 경계를 명확히 하고, 외부 AI 호출은 트랜잭션 안에서 하지 않는다(§12).

### 7. 상태 전이 — 정의된 전이만 허용

Job 등의 상태는 `QUEUED → RUNNING → SUCCEEDED`/`FAILED` 같은 정의된 전이만 허용한다(재시도·취소·콘텐츠 삭제 등 실제 상태는 API 명세 §5 기준). Service가 상태 필드를 임의로 덮어쓰지 않고 엔터티의 의미 있는 메서드(`start()`, `succeed()`, `fail()`)를 쓴다. 잘못된 상태에서의 실행은 조용히 넘어가지 않고 명시적으로 실패시킨다.

### 8. 외부 연동 — 인터페이스 뒤에 공급자 구현을 격리

AI 공급자 의존성을 비즈니스 로직에서 직접 쓰지 않는다(기능명세서 §5: 공급자 미선정).

```java
// external/ContentAnalysisClient.java
public interface ContentAnalysisClient {
    ContentAnalysisResult analyze(ContentAnalysisRequest request);
}
```

Service는 `ContentAnalysisClient` 같은 인터페이스만 알고 구체적인 공급자 클래스를 모른다. 실제 HTTP 통신은 `external/<provider>/<Provider>ContentAnalysisClient.java` 구현체가 한다. 공급자가 미정인 동안 특정 API 형식·모델명·timeout·retry 정책을 임의로 확정하지 않는다.

### 9. 외부 API 호출 — 실패와 비즈니스 결과를 구분

네트워크 오류·AI 공급자 장애를 정상 AI 판정 결과와 혼동하지 않는다. `classification`의 `no_match`는 AI가 정상 판단한 결과이고, AI 공급자 timeout은 작업 실패다(API 명세 §4). 외부 오류를 `no_match`나 빈 결과로 바꿔서 숨기지 않는다. 재시도 가능/불가능 오류를 구분한다.

### 10. Worker — 중복 실행 가능성을 전제로 구현

동일 작업이 절대 두 번 실행되지 않는다고 가정하지 않는다(시스템 설계 §8: "중복 전달이 가능하다고 가정하고 결과 반영을 멱등 처리한다"). 작업 획득 시 현재 상태·lease를 확인하고, 결과 저장 시에도 최신 상태를 검증해 늦게 도착한 결과가 더 최신 상태를 덮어쓰지 않게 한다. 별도 메시지 큐가 반드시 있다고 가정하지 않는다 — 시스템 설계 §2대로 DB Job 테이블 기반으로 시작할 수 있다.

### 11. Transaction — DB와 외부 I/O의 경계를 분리

```text
[Transaction] Job 상태 RUNNING 변경 → commit
[외부]        AI API 호출
[Transaction] 결과 저장, Job 상태 SUCCEEDED 변경, Usage 확정 → commit
```
장시간 걸리는 외부 AI 호출을 DB 트랜잭션 안에서 하지 않는다(ERD §5: "외부 AI 호출은 DB 트랜잭션 밖에서 실행한다"). 원자성이 필요한 DB 작업끼리는 같은 트랜잭션으로 묶는다.

### 12. 예외 — API 오류 코드와 내부 예외를 구분

`RuntimeException` 하나로 뭉뚱그리지 않는다. `ChallengeNotFoundException`, `ChallengeExpiredException`, `ChallengeAlreadyUsedException`, `AttestationVerificationException`, `InstallationNotFoundException`처럼 의미별로 나누고(`common/exception/`, `GlobalExceptionHandler`가 상태 코드로 매핑), 앞으로 추가할 Job 쪽도 `JobNotFoundException`, `IdempotencyConflictException`, `QuotaExceededException`, `ResultExpiredException`처럼 같은 방식을 따른다. API에서는 공통 오류 형식(API 명세 §2)을 유지한다.

```json
{"error":{"code":"QUOTA_EXCEEDED","message":"분석 한도에 도달했습니다.","retryable":false},"request_id":"req_example"}
```

내부 예외 메시지·stack trace를 클라이언트에 그대로 노출하지 않는다.

### 13. 로그 — 사용자 콘텐츠와 비밀정보를 남기지 않음

로그에 이미지 원본, OCR 전체 내용, AI 비밀키, Installation credential 원문, `Authorization` 헤더, 외부 공급자 secret을 남기지 않는다(API 명세 §2: "서버 오류 메시지와 로그에 이미지·OCR·비밀키를 포함하지 않는다"). 운영 추적엔 `requestId`, `jobId`, `installationId`, `jobKind`, `jobStatus`, `errorCode`, `processingTime` 같은 최소 식별자·상태만 쓴다.

### 14. 미확정 사항을 코드로 먼저 확정하지 않는다

구현 편의를 위해 설계 문서상 미정인 걸 임의로 정하지 않는다. 현재 미정: AI 공급자·모델, 설치 검증 방식, 작업 큐 제품(메시지 큐 도입 여부), 사용량 단위·실제 한도, 콘텐츠 보존 기간, 업로드 크기 제한, 재시도 세부 정책, 물리 스키마·마이그레이션 방식(DB 제품 자체는 PostgreSQL로 확정됨). 임시 선택이 꼭 필요하면 코드·문서에 **temporary/provisional**임을 명시한다.

### 가져오지 않은 것

- `assembly` 패키지: last-safe 고유의 "여러 도메인 결과를 하나로 조립" 역할. CapPick엔 대응 개념이 없어 안 만든다.
- last-safe-server의 `entity/`, `repository/` 최상위 분리 구조: 위 "패키지 구조"에서 설명한 대로 의도적으로 안 따르고 domain 중심으로 바꿨다.

## 빌드/실행 명령어

```bash
./gradlew compileJava   # 컴파일만
./gradlew test          # 테스트
./gradlew bootRun        # 실행 — 로컬 PostgreSQL(cappick_dev)에 붙어서 기동 확인됨
```

## 구현 시작 전 반드시 확인할 미정 사항

전체 목록·원칙은 위 코딩 컨벤션 §14 참고. 설치 검증 방식(App Attest+우회)과 스키마 관리(Flyway)는 확정됐으니 목록에서 뺐다. 남은 것:

- **challenge·credential TTL 정확한 값**: `InstallationService`의 `CHALLENGE_TTL`(5분), `CREDENTIAL_TTL`(30일)은 코드에 "잠정치"라고 주석 달아둔 임의 값이다. API 명세 §8 "멱등 키 및 임시 콘텐츠 보존 기간 확정"이 실제 결정 시점이다.
- **challenge 발급 엔드포인트 호출량 제한**: API 명세 §3 "이 경로도 호출량 제한 대상"이라고 명시돼 있지만 아직 아무 제한도 안 걸려 있다. 지금은 누구나 challenge를 무한정 발급받을 수 있다.
- **Spring Security 도입 여부**: 아직 안 정했다. 지금은 `JobController`도 `InstallationController`처럼 `AuthorizationHeaders` + `InstallationService.resolveByCredential()`을 컨트롤러에서 직접 부르는 수작업 방식이다 — 컨트롤러가 늘어나면 필터/인터셉터로 공통화할지 정해야 한다.
- **AI 결과 result_json 보존 기간·워커 재시도 정책**: `JobStateTransitions.RESULT_TTL`(24시간)은 잠정치다. 작업 실패 시 재시도 정책(자동 재시도 여부, 횟수)도 아직 없다 — 지금은 실패하면 그냥 FAILED로 끝난다.
- **content_analysis 외 kind가 생겼을 때의 컨트롤러 구조**: `POST /v1/jobs`가 지금은 multipart 전용이다. API 명세는 "다른 kind는 application/json"이라고 하는데, 같은 경로에서 Content-Type으로 분기할지 별도 하위 경로로 나눌지 안 정했다.
- **워커/큐 도입 시점**: `AsyncConfig`는 인프로세스 스레드풀일 뿐이다 — 서버가 죽으면 진행 중이던 job은 유실된다. 언제 진짜 큐(+ lease·재수집)로 옮길지는 트래픽을 보고 정한다(시스템 설계 §2).
- **동시에 두 삭제 요청이 겹칠 때 처리**: 지금 `DeletionService.requestInstallationDeletion`은 이미 활성(PENDING·PROCESSING) 삭제가 있으면 다른 Idempotency-Key의 새 삭제 요청도 무조건 409로 거부한다 — 잠정 정책이다.

## 지켜야 할 제약 (루트 CLAUDE.md 요약)

- 폴더 생성·이름 수정·소속 변경 등 로컬(클라이언트) 기능의 CRUD API를 서버에 만들지 않는다 — API 명세 §1: "서버 CRUD API를 만들지 않는다."
- 로그인·회원가입 API를 만들지 않는다.
- Job의 `classification` kind는 서버가 폴더 소속을 저장하는 API가 아니다. 앱이 보낸 스냅샷에 대한 판정만 돌려준다 (API 명세 §4).
- 이미지·OCR 문구를 시스템 명령으로 취급하지 않는다 — 분석 대상 데이터로만 다룬다.
