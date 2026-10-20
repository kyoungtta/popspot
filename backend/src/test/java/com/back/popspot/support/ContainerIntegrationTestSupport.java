package com.back.popspot.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.back.popspot.domain.payment.client.TossPaymentsClient;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * MySQL·Redis 컨테이너를 쓰는 통합 테스트 베이스.
 * 컨테이너는 JVM 당 한 번만 띄운다 (싱글턴 컨테이너, 종료는 Testcontainers Ryuk 이 담당).
 * 클래스마다 컨테이너를 재시작하면 포트가 바뀌는데, 캐시된 Spring 컨텍스트는 이전 포트를 계속 바라보게 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
@ActiveProfiles("test")
public abstract class ContainerIntegrationTestSupport {

    static final MySQLContainer<?> mysql =
        new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("popspot_test")
            .withUsername("test")
            .withPassword("test");

    static final GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    static {
        mysql.start();
        redis.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockitoBean
    private S3Client s3Client;

    @MockitoBean
    private S3Presigner s3Presigner;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;
}
