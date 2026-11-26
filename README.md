# CapPick-server

**캡픽(스크린샷 검색·정리 아이폰 앱)의 API 서버.** AI 분석 작업과 사용량을 관리한다. (Spring Boot)

- 설치 검증: 로그인 없이 App Attest로 앱 설치를 확인하고 요청을 보호한다.
- AI 작업(Job): 스크린샷 분석과 폴더 분류를 Gemini로 비동기 처리하고, 앱은 결과를 폴링한다.
- 비용 관리: 실제 호출로 토큰을 재며 모델과 프롬프트를 조정했다. 장당 분석 비용 약 1.4원 → 약 0.85원. 전체 일일 예산을 넘으면 요청을 막는다.
- 사용량 조회와 데이터 삭제 요청 API.

**기술**: Spring Boot 4.1 · Java 21 · PostgreSQL · Flyway · Gemini API

관련 저장소: [CapPick-ios](https://github.com/ahyeon24/CapPick-ios) · [CapPick-docs](https://github.com/ahyeon24/CapPick-docs)(기획·화면·API 명세)
