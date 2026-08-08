package com.back.popspot.global.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.back.popspot.domain.queue.repository.PopupQueueEntryRepository;
import com.back.popspot.global.queue.config.WaitingQueueProperties;
import com.back.popspot.global.queue.service.WaitingQueueRedisService;
import com.back.popspot.global.redis.RedisKeys;

/**
 * 활성 대기열 팝업 인덱스(Set) 스윕의 race condition 테스트.
 *
 * <p>손실 시나리오: 스케줄러가 popupId의 ZSET이 없다고 판단(대기열 소진) → 그 사이 유저가
 * enqueue(ZADD + SADD) → SREM이 방금 들어온 popupId를 인덱스에서 제거. ZSET에는 대기자가
 * 있는데 인덱스에는 없으므로, 그 팝업에 신규 enqueue가 더 들어오지 않으면 인덱스를 되살릴
 * 트리거가 없어 스케줄러가 영영 admitBatch 대상에서 제외한다 → 대기자 전원이 멈춘다.
 *
 * <p>{@link ActiveWaitingPopupsIndexTest}의 Testcontainers(MySQL + Redis) 셋업을 따른다.
 */
@SpringBootTest(
    classes = ActivePopupsSweepRaceTest.TestConfig.class,
    properties = {
        "waiting-queue.batch-size=3",
        "waiting-queue.scheduler-fixed-rate-ms=3600000",
        "waiting-queue.proceed-ttl-seconds=60",
        "waiting-queue.poll-interval-seconds=1",
        "waiting-queue.queue-ttl-buffer-seconds=300",
    }
)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("활성 대기열 인덱스 스윕 race condition 테스트")
class ActivePopupsSweepRaceTest {

    /** 옛 구현의 EXISTS → SREM 사이 창을 눈에 보이게 벌리는 폭(ms). */
    private static final long RACE_WINDOW_MS = 300L;

    /** 원자 스윕 불변식 반복 검증 횟수. */
    private static final int ATOMIC_ITERATIONS = 100;

    // ── 최소 Spring 컨텍스트 (StringRedisTemplate + JPA Repository만) ──────────
    @Configuration
    @ImportAutoConfiguration({
        AopAutoConfiguration.class,
        DataRedisAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class,
        TransactionAutoConfiguration.class
    })
    @EnableConfigurationProperties(WaitingQueueProperties.class)
    @EntityScan(basePackages = "com.back.popspot")
    @EnableJpaRepositories(basePackages = "com.back.popspot")
    static class TestConfig {}

    // ── 컨테이너 ──────────────────────────────────────────────────────────────

    @Container
    static final MySQLContainer<?> mysql =
        new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("popspot_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // ── Autowired ─────────────────────────────────────────────────────────────

    @Autowired StringRedisTemplate redisTemplate;
    @Autowired WaitingQueueProperties queueProperties;
    @Autowired PopupQueueEntryRepository queueEntryRepository;

    WaitingQueueRedisService service;
    ExecutorService executor;

    @BeforeEach
    void setUp() {
        service = new WaitingQueueRedisService(redisTemplate, queueProperties, queueEntryRepository);
        executor = Executors.newFixedThreadPool(2);
        cleanup();
        // JPA/커넥션 워밍업 — 첫 enqueue의 초기화 지연이 race 타이밍을 흔들지 않도록
        service.enqueue(1L, "999", LocalDateTime.now().plusDays(1));
        cleanup();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        cleanup();
    }

    // ── TC-1: 스윕 도중 enqueue가 끼어도 대기자를 잃지 않아야 한다 (red/green) ──

    @Test
    @DisplayName("스윕이 판단하는 도중 enqueue가 끼어들어도 대기자 있는 팝업이 인덱스에서 지워지지 않는다")
    void sweep_doesNotDropPopupEnqueuedDuringDecision() throws Exception {
        long popupId = 500L;
        String userId = "1";

        // 대기열이 막 소진된 상태: 인덱스에는 남아있고 ZSET은 없음
        redisTemplate.opsForSet().add(RedisKeys.activeWaitingPopups(), String.valueOf(popupId));

        // 스윕이 "이 팝업 죽었다"고 판단하는 지점에서 창을 벌리고, 그 안에서 enqueue를 끝낸다.
        //   수정 전: EXISTS(false)를 이미 읽은 뒤라 뒤이은 SREM이 방금 들어온 팝업을 지운다 → 실패
        //   수정 후: 판단-쓰기 전체가 Lua 한 방이라 enqueue 이후 실행되고 살아있는 ZSET을 본다 → 통과
        CountDownLatch windowOpened = new CountDownLatch(1);
        CountDownLatch enqueueDone = new CountDownLatch(1);
        RaceProbeRedisTemplate probe = new RaceProbeRedisTemplate(
            redisTemplate.getRequiredConnectionFactory(), windowOpened, enqueueDone);
        WaitingQueueRedisService probedService =
            new WaitingQueueRedisService(probe, queueProperties, queueEntryRepository);

        Future<?> sweeper = executor.submit(() -> {
            probedService.getActivePopupIds();
            return null;
        });
        Future<?> enqueuer = executor.submit(() -> {
            windowOpened.await(30, TimeUnit.SECONDS);
            service.enqueue(popupId, userId, LocalDateTime.now().plusDays(1));   // 프로브 없는 경로
            enqueueDone.countDown();
            return null;
        });

        enqueuer.get(60, TimeUnit.SECONDS);
        sweeper.get(60, TimeUnit.SECONDS);

        // 대기자는 ZSET에 분명히 들어있는데
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.popupWaitingQueue(popupId), userId))
            .as("창 안에서 enqueue된 대기자는 ZSET에 존재해야 한다")
            .isNotNull();
        // 인덱스에도 남아있어야 한다. 빠지면 스케줄러가 이 팝업을 영영 admit하지 못한다.
        assertThat(redisTemplate.opsForSet().isMember(
            RedisKeys.activeWaitingPopups(), String.valueOf(popupId)))
            .as("대기자가 있는 popupId는 스윕 후에도 활성 인덱스에 남아야 한다")
            .isTrue();
    }

    // ── TC-2: 원자 스윕은 같은 상황에서 잃지 않는다 ───────────────────────────

    @Test
    @DisplayName("Lua 원자 스윕은 enqueue와 동시에 실행돼도 대기자 있는 팝업을 인덱스에서 지우지 않는다")
    void atomicSweep_neverLosesConcurrentlyEnqueuedPopup() throws Exception {
        // Lua는 원자 실행이라 창을 벌릴 수 없다. 대신 enqueue와 스윕을 동시 출발시키는 것을
        // 반복해, "ZSET에 대기자가 있으면 인덱스에도 반드시 있다" 불변식이 매번 지켜지는지 본다.
        List<Long> violations = new ArrayList<>();

        for (int i = 0; i < ATOMIC_ITERATIONS; i++) {
            long popupId = 600L + i;   // enqueue는 동일 user+popup WAITING이 있으면 조기 반환하므로 매번 새 popupId
            String userId = "1";

            // TC-1과 같은 출발선: 인덱스에는 있고 ZSET은 없는 상태
            redisTemplate.opsForSet().add(RedisKeys.activeWaitingPopups(), String.valueOf(popupId));

            CyclicBarrier start = new CyclicBarrier(2);
            Future<?> sweeper = executor.submit(() -> {
                start.await();
                service.getActivePopupIds();
                return null;
            });
            Future<?> enqueuer = executor.submit(() -> {
                start.await();
                service.enqueue(popupId, userId, LocalDateTime.now().plusDays(1));
                return null;
            });
            sweeper.get(30, TimeUnit.SECONDS);
            enqueuer.get(30, TimeUnit.SECONDS);

            Long waiters = redisTemplate.opsForZSet().size(RedisKeys.popupWaitingQueue(popupId));
            boolean indexed = Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(
                RedisKeys.activeWaitingPopups(), String.valueOf(popupId)));
            if (waiters != null && waiters > 0 && !indexed) {
                violations.add(popupId);
            }
        }

        assertThat(violations)
            .as("대기자가 있는데 인덱스에서 빠진 popupId (스케줄러가 영영 admit하지 못함)")
            .isEmpty();
    }

    // ── TC-3: 원자 스윕도 죽은 popupId 청소는 그대로 한다 ─────────────────────

    @Test
    @DisplayName("Lua 원자 스윕은 ZSET 없는 죽은 popupId를 인덱스에서 청소하고 산 팝업만 반환한다")
    void atomicSweep_stillCleansDeadPopupIds() {
        long livePopupId = 700L;
        long deadPopupId = 701L;
        service.enqueue(livePopupId, "1", LocalDateTime.now().plusDays(1));
        redisTemplate.opsForSet().add(RedisKeys.activeWaitingPopups(), String.valueOf(deadPopupId));

        Set<Long> active = service.getActivePopupIds();

        assertThat(active).contains(livePopupId);
        assertThat(active).doesNotContain(deadPopupId);
        assertThat(redisTemplate.opsForSet().isMember(
            RedisKeys.activeWaitingPopups(), String.valueOf(deadPopupId))).isFalse();
    }

    // ── race 프로브 ───────────────────────────────────────────────────────────

    /**
     * 스윕이 "이 팝업 죽었다"고 판단하는 지점에서 race 창을 벌리는 테스트용 템플릿.
     *
     * <p>수정 전 구현은 popupId마다 {@code hasKey}(EXISTS)로 판단하므로, EXISTS가 false를
     * 반환한 직후를 창으로 잡는다 — 이미 "죽었다"고 읽은 뒤이므로 뒤이은 SREM은 그 사이
     * enqueue된 팝업까지 지운다.
     *
     * <p>수정 후 구현은 판단과 SREM이 Lua 한 방 안에 있어 창을 벌릴 수 없다. 대신 스크립트
     * 실행 직전을 창으로 잡아 enqueue가 먼저 끝나게 한다 — 스크립트는 살아있는 ZSET을 보므로
     * 지우지 않아야 한다. 어느 쪽이든 "스윕이 판단하는 시점 주변에 enqueue가 낀다"는 동일한
     * 상황이고, 옛 구현만 대기자를 잃는다.
     *
     * <p>{@link #RACE_WINDOW_MS} sleep은 latch만으로는 겹치지 않을 수 있는 창을 확실히 벌린다.
     */
    static class RaceProbeRedisTemplate extends StringRedisTemplate {

        private final CountDownLatch windowOpened;
        private final CountDownLatch enqueueDone;
        private final java.util.concurrent.atomic.AtomicBoolean fired =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        RaceProbeRedisTemplate(RedisConnectionFactory connectionFactory,
                               CountDownLatch windowOpened, CountDownLatch enqueueDone) {
            super(connectionFactory);
            this.windowOpened = windowOpened;
            this.enqueueDone = enqueueDone;
        }

        /** 수정 전 경로: EXISTS(false)를 읽은 직후 = SREM 직전 */
        @Override
        public Boolean hasKey(String key) {
            Boolean exists = super.hasKey(key);
            if (Boolean.FALSE.equals(exists)) {
                openWindow();
            }
            return exists;
        }

        /** 수정 후 경로: Lua 스윕 실행 직전 */
        @Override
        public <T> T execute(org.springframework.data.redis.core.script.RedisScript<T> script,
                             List<String> keys, Object... args) {
            openWindow();
            return super.execute(script, keys, args);
        }

        private void openWindow() {
            if (!fired.compareAndSet(false, true)) {
                return;
            }
            windowOpened.countDown();
            try {
                Thread.sleep(RACE_WINDOW_MS);
                enqueueDone.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void cleanup() {
        for (String pattern : List.of("waiting:popup:*", "seq:popup:*", "proceed:popup:*", RedisKeys.activeWaitingPopups())) {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
        queueEntryRepository.deleteAll();
    }
}
