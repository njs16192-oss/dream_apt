package com.dreamapt.collect;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * API 호출 직전에 불러서 호출 사이 최소 간격을 지키고, 호출 수를 센다.
 * 배치와 관리 API가 같은 인스턴스를 쓰므로 둘이 동시에 호출해도 간격이 지켜진다.
 * 호출 수는 프로세스 전체 누적값이라, 실행 하나의 호출 수는 시작 전후 차이로 구한다.
 */
@Component
public class ApiRequestThrottle {

    /** 테스트에서 실제로 기다리지 않도록 바꿔 끼운다 */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final AtomicLong requestCount = new AtomicLong();

    private long lastRequestNanos;
    private boolean requested;

    @Autowired
    public ApiRequestThrottle(DataGoKrProperties properties) {
        this(properties.requestInterval());
    }

    /** 공공데이터포털 밖의 API(도로명주소 등)용. 호출 간격과 호출 수를 따로 센다 */
    public ApiRequestThrottle(Duration interval) {
        this(interval == null ? Duration.ZERO : interval,
                System::nanoTime, d -> TimeUnit.NANOSECONDS.sleep(d.toNanos()));
    }

    ApiRequestThrottle(Duration interval, LongSupplier nanoTime, Sleeper sleeper) {
        this.intervalNanos = interval.toNanos();
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    /**
     * 마지막 호출로부터 간격이 덜 지났으면 남은 시간만큼 기다린다. 기다리는 동안 다른 호출은 순서를 기다린다.
     */
    public synchronized void acquire() {
        if (requested) {
            long wait = intervalNanos - (nanoTime.getAsLong() - lastRequestNanos);
            if (wait > 0) {
                try {
                    sleeper.sleep(Duration.ofNanos(wait));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TradeApiException("API 호출 대기 중 인터럽트", e);
                }
            }
        }
        lastRequestNanos = nanoTime.getAsLong();
        requested = true;
        requestCount.incrementAndGet();
    }

    /** 지금까지 acquire 한 횟수 (실패한 호출 포함) */
    public long requestCount() {
        return requestCount.get();
    }
}
