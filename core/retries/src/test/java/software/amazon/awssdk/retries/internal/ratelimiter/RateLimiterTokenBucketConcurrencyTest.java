/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.retries.internal.ratelimiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimiterTokenBucketConcurrencyTest {
    private static final int N_THREADS = 16;
    private FixedClock clock;
    private RateLimiterTokenBucket bucket;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        clock = new FixedClock();
        bucket = new RateLimiterTokenBucket(clock);
        executor = Executors.newFixedThreadPool(N_THREADS);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void updateRateAfterThrottling_concurrentThrottlesWithinDebounceWindow_doesNotCompoundReduction() throws Exception {
        // Prime a high measured send rate, then enable the bucket and apply a single reduction with one throttle.
        clock.setTime(0.0);
        for (int i = 0; i < 50; i++) {
            bucket.updateRateAfterSuccess();
        }
        clock.setTime(0.5);
        bucket.updateRateAfterThrottling();
        double fillAfterFirstThrottle = bucket.currentState().fillRate();

        // Fire a burst of throttles at the same timestamp (within the debounce window). Each must be a no-op,
        // otherwise the reductions would compound (0.7^N) as they did before debouncing.
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(N_THREADS);
        for (int i = 0; i < N_THREADS; i++) {
            futures.add(executor.submit((Callable<Void>) () -> {
                start.await();
                bucket.updateRateAfterThrottling();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        assertThat(bucket.currentState().fillRate())
            .as("throttles within the debounce window must not compound the rate reduction")
            .isEqualTo(fillAfterFirstThrottle);

        // A throttle past the debounce window must reduce the rate again (proving the window, not the floor,
        // caused the no-ops above).
        clock.setTime(0.7);
        bucket.updateRateAfterThrottling();
        assertThat(bucket.currentState().fillRate())
            .as("a throttle after the debounce window should reduce the rate")
            .isLessThan(fillAfterFirstThrottle);
    }

    static final class FixedClock implements RateLimiterClock {
        private volatile double time;

        @Override
        public double time() {
            return time;
        }

        void setTime(double t) {
            this.time = t;
        }
    }
}
