package com.cappick.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 지금은 이 스레드풀이 "워커"다 — 별도 메시지 큐는 부하 검증 후 결정한다는 시스템 설계 §2대로
 * 프로세스 안에서 비동기 실행만 한다. 서버가 재시작되면 진행 중이던 작업은 유실된다(lease·재수집
 * 없음) — 별도 워커/큐 도입 시 다시 설계해야 한다(§14 미확정 사항).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("job-worker-");
        executor.initialize();
        return executor;
    }
}
