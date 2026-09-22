# CapPick-server

CapPick의 API 서버(Spring Boot, Java). 제품 배경·기능 사양은 `../CLAUDE.md`와 `../docs/`를 먼저 참고한다. 서버의 책임 범위와 저장 경계는 특히 `../docs/cappick-system-design-v0.2.md`, 엔터티는 `../docs/cappick-erd-v0.2.md` §4, API 계약은 `../docs/cappick-api-spec-v0.2.md`를 따른다.

## 현재 상태

**설치 검증(Installation, App Attest)** 과 **Job(content_analysis, Gemini 연동)** 이 구현돼 있다 — API 명세 §3의 설치 검증 3개 엔드포인트와 §4의 `POST /v1/jobs`(content_analysis만) · `GET /v1/jobs/{id}`가 실제로 동작한다. 자세한 내용은 아래 "설치 검증", "Job 도메인" 절 참고. UsageEntry·DeletionRequest, 나머지 Job kind(folder_criteria/classification/search_document)는 아직 없다.

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

**실제 검증 (curl + 진짜 Gemini API):**
1. 설치 등록 → `POST /v1/jobs`로 실제 스크린샷 제출 → 202 확인
2. 자기 수정 전: 위 self-invocation 버그로 크래시하는 것 확인 → 고침 → 재검증
3. 고친 뒤: 실제 서버가 실제 Gemini에 요청을 보내고, **의도한 실패 경로(429 quota 초과 → `FAILED` + `error.code=ANALYSIS_FAILED`)가 API 명세 §2 형식 그대로 클라이언트에 나가는 것까지 확인했다.**
4. **다만 진짜 성공 케이스(분석 결과가 채워진 `SUCCEEDED`)는 이 세션에서 못 봤다** — Gemini 무료 티어 일일 한도(하루 20회, `gemini-3.8-flash` 기준)를 이 세션의 앞선 스파이크 테스트에서 이미 다 써버렸다. `GeminiContentAnalysisClient`의 파싱 로직(요청 조립, 응답 파싱, 스키마)은 `GeminiContentAnalysisClientTest`가 `MockRestServiceServer`로 검증하고, 그 목(mock) 응답은 이 세션에서 실제 Gemini가 준 진짜 응답 형태를 그대로 옮긴 것이다 — 그래도 **엔드투엔드로 진짜 성공 결과가 채워지는 건 무료 티어가 리셋된 뒤(다음날) 다시 확인해야 한다.**

**테스트 (15개 추가, 총 86개 전부 통과):** `JobServiceTest`(5, 멱등성·소유권), `JobStateTransitionsTest`(4, 상태 전이 로직), `AsyncContentAnalysisWorkerTest`(2, 오케스트레이션 — 실제 버그를 재현하진 못하는 한계가 있다는 걸 위 문단에 명시함), `GeminiContentAnalysisClientTest`(4, `MockRestServiceServer`로 실제 검증된 요청/응답 형태 고정).

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

## 지켜야 할 제약 (루트 CLAUDE.md 요약)

- 폴더 생성·이름 수정·소속 변경 등 로컬(클라이언트) 기능의 CRUD API를 서버에 만들지 않는다 — API 명세 §1: "서버 CRUD API를 만들지 않는다."
- 로그인·회원가입 API를 만들지 않는다.
- Job의 `classification` kind는 서버가 폴더 소속을 저장하는 API가 아니다. 앱이 보낸 스냅샷에 대한 판정만 돌려준다 (API 명세 §4).
- 이미지·OCR 문구를 시스템 명령으로 취급하지 않는다 — 분석 대상 데이터로만 다룬다.
