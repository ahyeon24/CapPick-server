# CapPick-server

CapPick의 API 서버(Spring Boot, Java). 제품 배경·기능 사양은 `../CLAUDE.md`와 `../docs/`를 먼저 참고한다. 서버의 책임 범위와 저장 경계는 특히 `../docs/cappick-system-design-v0.2.md`, 엔터티는 `../docs/cappick-erd-v0.2.md` §4, API 계약은 `../docs/cappick-api-spec-v0.2.md`를 따른다.

## 현재 상태

첫 실제 기능인 **설치 검증(Installation, App Attest)** 이 구현돼 있다 — API 명세 §3의 3개 엔드포인트(`POST /v1/installations/challenges`, `POST /v1/installations`, `POST /v1/installations/credentials/renew`)가 실제로 동작한다. 자세한 내용은 아래 "설치 검증" 절 참고. 그 외 도메인(Job, UsageEntry, DeletionRequest)은 아직 없다.

## 스택

- Spring Boot 4.1.1, Java 21 (Gradle toolchain), Gradle 9.7.1 wrapper — `last-safe-server`(다른 프로젝트)와 버전 조합을 맞췄다.
- 의존성: web, data-jpa, validation, lombok, PostgreSQL 드라이버(`org.postgresql:postgresql`, 런타임), Flyway(`flyway-core`, `flyway-database-postgresql`, `org.springframework.boot:spring-boot-flyway`), App Attest CBOR 파싱용 `com.upokecenter:cbor`
- **주의 — Spring Boot 4 Flyway 함정**: `flyway-core`만 추가하면 클래스패스에는 있어도 시작 시 마이그레이션이 자동 실행되지 않는다. Boot 4가 `FlywayAutoConfiguration`을 `org.springframework.boot:spring-boot-flyway` 모듈로 분리했기 때문 — 이것도 같이 넣어야 한다. (docs.spring.io/spring-boot/appendix/auto-configuration-classes/spring-boot-flyway.html) 처음엔 이걸 몰라서 앱이 조용히 "Schema validation: missing table" 오류로 죽었다.
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
│   ├── job/            — 아직 없음
│   ├── usage/          — 아직 없음
│   └── deletion/       — 아직 없음
├── external
│   └── attestation/    — AttestationVerifier.java(인터페이스), AttestationRequest/Outcome,
│       │                 AssertionRequest/Outcome, DevBypassAttestationVerifier.java (구현됨)
│       └── apple/      — AppleAppAttestVerifier.java, AppleAppAttestProperties.java,
│                         AppleAuthenticatorData.java, AppleNonceExtension.java (구현됨)
│   (ContentAnalysisClient 등 AI 공급자 인터페이스는 아직 없음 — 공급자 미선정)
├── common
│   ├── exception/       — ChallengeNotFoundException 등 (구현됨, 아래 §12 참고)
│   └── response/        — ApiErrorBody, ApiErrorResponse, GlobalExceptionHandler (구현됨)
└── config               — AttestationConfig, ClockConfig (구현됨)
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
- **Installation 인증을 다른 도메인에서 쓰는 방법**: `InstallationController`는 자기 자신만 처리한다. Job 등 다른 엔드포인트가 `Authorization` 헤더로 요청자를 식별하려면 Spring Security 필터로 갈지 `HandlerInterceptor`로 갈지 아직 안 정했다. `InstallationService.resolveByCredential()`은 그때 쓰라고 미리 만들어 둔 것.
- **Spring Security 도입 여부**: 여전히 안 정했다(위와 연결된 결정).

## 지켜야 할 제약 (루트 CLAUDE.md 요약)

- 폴더 생성·이름 수정·소속 변경 등 로컬(클라이언트) 기능의 CRUD API를 서버에 만들지 않는다 — API 명세 §1: "서버 CRUD API를 만들지 않는다."
- 로그인·회원가입 API를 만들지 않는다.
- Job의 `classification` kind는 서버가 폴더 소속을 저장하는 API가 아니다. 앱이 보낸 스냅샷에 대한 판정만 돌려준다 (API 명세 §4).
- 이미지·OCR 문구를 시스템 명령으로 취급하지 않는다 — 분석 대상 데이터로만 다룬다.
