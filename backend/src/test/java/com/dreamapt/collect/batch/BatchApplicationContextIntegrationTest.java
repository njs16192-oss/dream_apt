package com.dreamapt.collect.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;

import com.dreamapt.support.TestcontainersConfiguration;

/**
 * 배치 CLI(batch 프로필, web-application-type: none)처럼 웹 서버 없이 앱이 뜨는지 본다.
 * 다른 통합 테스트는 웹 컨텍스트라서, 웹에서만 있는 빈에 기대는 설정이 배치 CLI를 깨도 잡지 못했다 (#26 이후).
 * batch 프로필은 켜지 않는다. 켜면 CollectJobCommandLineRunner가 시작할 때 Job을 실행한다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class BatchApplicationContextIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void 웹_서버_없이도_앱이_뜨고_수집_Job은_있지만_웹_보안_설정은_없다() {
        assertThat(context.containsBean(RunningCollectJobs.JOB_NAME)).isTrue();
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
    }
}
