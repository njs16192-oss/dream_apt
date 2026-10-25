package com.dreamapt.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ApiRequestThrottleTest {

    // 가짜 시계: sleep 하면 그만큼 시간이 흐른다
    private final AtomicLong now = new AtomicLong();
    private final List<Duration> sleeps = new ArrayList<>();
    private final ApiRequestThrottle throttle = new ApiRequestThrottle(Duration.ofMillis(300), now::get, d -> {
        sleeps.add(d);
        now.addAndGet(d.toNanos());
    });

    @Test
    void 첫_호출은_기다리지_않는다() {
        throttle.acquire();

        assertThat(sleeps).isEmpty();
        assertThat(throttle.requestCount()).isEqualTo(1);
    }

    @Test
    void 간격이_덜_지났으면_남은_시간만큼_기다린다() {
        throttle.acquire();
        now.addAndGet(Duration.ofMillis(100).toNanos());
        throttle.acquire();

        assertThat(sleeps).containsExactly(Duration.ofMillis(200));
    }

    @Test
    void 간격이_이미_지났으면_기다리지_않는다() {
        throttle.acquire();
        now.addAndGet(Duration.ofMillis(500).toNanos());
        throttle.acquire();
        throttle.acquire();

        assertThat(sleeps).containsExactly(Duration.ofMillis(300));
        assertThat(throttle.requestCount()).isEqualTo(3);
    }
}
