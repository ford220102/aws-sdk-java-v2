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

package software.amazon.awssdk.retries.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.retries.AdaptiveRetryStrategy;
import software.amazon.awssdk.retries.api.AcquireInitialTokenRequest;
import software.amazon.awssdk.retries.api.AcquireInitialTokenResponse;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.retries.api.RefreshRetryTokenRequest;
import software.amazon.awssdk.retries.api.RefreshRetryTokenResponse;
import software.amazon.awssdk.retries.api.TryAcquireInitialTokenResult;
import software.amazon.awssdk.retries.api.TryRefreshRetryTokenResult;
import software.amazon.awssdk.retries.api.internal.RefreshRetryTokenResponseImpl;
import software.amazon.awssdk.retries.internal.circuitbreaker.AcquireResponse;
import software.amazon.awssdk.retries.internal.circuitbreaker.TokenBucketStore;
import software.amazon.awssdk.retries.internal.ratelimiter.RateLimiterAcquireResponse;
import software.amazon.awssdk.retries.internal.ratelimiter.RateLimiterTokenBucket2;
import software.amazon.awssdk.retries.internal.ratelimiter.RateLimiterTokenBucketStore;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;

@SdkInternalApi
public final class DefaultAdaptiveRetryStrategy
    extends BaseRetryStrategy implements AdaptiveRetryStrategy {

    private static final Logger LOG = Logger.loggerFor(DefaultAdaptiveRetryStrategy.class);
    private final RateLimiterTokenBucketStore rateLimiterTokenBucketStore;

    DefaultAdaptiveRetryStrategy(Builder builder) {
        super(LOG, builder);
        this.rateLimiterTokenBucketStore = Validate.paramNotNull(builder.rateLimiterTokenBucketStore,
                                                                 "rateLimiterTokenBucketStore");
    }

    @Override
    protected Duration computeInitialBackoff(AcquireInitialTokenRequest request) {
        throw new UnsupportedOperationException("not supported");
        // RateLimiterTokenBucket2 bucket = rateLimiterTokenBucketStore.tokenBucketForScope(request.scope());
        // while (true) {
        //     RateLimiterTokenBucket2.AcquireResult acquireResult = bucket.tryAcquire();
        //     if (!acquireResult.response().isPresent()) {
        //         try {
        //             long spinDelayMs = acquireResult.delayUntilNext().toMillis();
        //             System.out.printf("computeInitialBackoff: spin %dms%n", spinDelayMs);
        //             Thread.sleep(spinDelayMs);
        //         } catch (InterruptedException e) {
        //             // ignored
        //         }
        //     } else {
        //         return acquireResult.response().get().delay();
        //     }
        // }
    }

    @Override
    public TryAcquireInitialTokenResult tryAcquireInitialToken(AcquireInitialTokenRequest request) {
        RateLimiterTokenBucket2 bucket = rateLimiterTokenBucketStore.tokenBucketForScope(request.scope());
        RateLimiterTokenBucket2.AcquireResult acquireResult = bucket.tryAcquire();
        Optional<RateLimiterAcquireResponse> rateLimiterResponse = acquireResult.response();

        if (!rateLimiterResponse.isPresent()) {
            return new TryAcquireInitialTokenResult(null, acquireResult.delayUntilNext());
        }

        AdaptiveRetryToken token = AdaptiveRetryToken.builder().scope(request.scope()).build();
        AcquireInitialTokenResponse acquireResponse = AcquireInitialTokenResponse.create(token, rateLimiterResponse.get().delay());
        return new TryAcquireInitialTokenResult(acquireResponse, Duration.ZERO);
    }

    @Override
    public TryRefreshRetryTokenResult tryRefreshRetryToken(RefreshRetryTokenRequest request) {
        AdaptiveRetryToken token = (AdaptiveRetryToken) request.token();
        if (token.rateLimiterAcquireStatus() != AdaptiveRetryToken.RateLimiterAcquireStatus.ACQUIRING) {
            token = setupTokenForAcquire(request);
        }

        RateLimiterTokenBucket2 bucket = rateLimiterTokenBucketStore.tokenBucketForScope(token.scope());
        RateLimiterTokenBucket2.AcquireResult acquireResult = bucket.tryAcquire();
        Optional<RateLimiterAcquireResponse> acquireResponse = acquireResult.response();

        if (!acquireResponse.isPresent()) {
            return new TryRefreshRetryTokenResult(null, token, acquireResult.delayUntilNext());
        }

        token = token.toBuilder()
                     .rateLimiterAcquireStatus(AdaptiveRetryToken.RateLimiterAcquireStatus.ACQUIRED)
                     .build();

        Duration backoff = super.computeBackoff(request, token);

        RefreshRetryTokenResponse response = RefreshRetryTokenResponse.create(token, backoff.plus(acquireResponse.get().delay()));
        return new TryRefreshRetryTokenResult(response, token, Duration.ZERO);
    }

    private AdaptiveRetryToken setupTokenForAcquire(RefreshRetryTokenRequest request) {
        AdaptiveRetryToken token = (AdaptiveRetryToken) request.token();

        // Check if we meet the preconditions needed for retrying. These will throw if the expected condition is not meet.
        // 1) is retryable?
        throwOnNonRetryableException(request);

        // 2) max attempts reached?
        throwOnMaxAttemptsReached(request);

        // 3) can we acquire a token?
        AcquireResponse acquireResponse = requestAcquireCapacity(request, token);
        throwOnAcquisitionFailure(request, acquireResponse);

        // All the conditions required to retry were meet, update the internal state before retrying.
        updateStateForRetry(request);

        // Refresh the retry token and compute the backoff delay.
        return ((AdaptiveRetryToken) refreshToken(request, acquireResponse))
            .toBuilder()
            .rateLimiterAcquireStatus(AdaptiveRetryToken.RateLimiterAcquireStatus.ACQUIRING)
            .build();
    }

    @Override
    protected Duration computeBackoff(RefreshRetryTokenRequest request, DefaultRetryToken token) {
        Duration backoff = super.computeBackoff(request, token);
        RateLimiterTokenBucket2 bucket = rateLimiterTokenBucketStore.tokenBucketForScope(token.scope());
        Duration acquireDelay;
        while (true) {
            RateLimiterTokenBucket2.AcquireResult acquireResult = bucket.tryAcquire();
            if (!acquireResult.response().isPresent()) {
                try {
                    long delayMs = acquireResult.delayUntilNext().toMillis();
                    System.out.printf("computeBackoff: spin %dms%n", delayMs);
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    // ignored
                }
            } else {
                acquireDelay = acquireResult.response().get().delay();
                break;
            }
        }
        return backoff.plus(acquireDelay);
    }

    @Override
    protected void updateStateForRetry(RefreshRetryTokenRequest request) {
        if (treatAsThrottling.test(request.failure())) {
            DefaultRetryToken token = asDefaultRetryToken(request.token());
            RateLimiterTokenBucket2 bucket = rateLimiterTokenBucketStore.tokenBucketForScope(token.scope());
            bucket.updateRateAfterThrottling();
        }
    }

    @Override
    protected void updateStateForSuccess(DefaultRetryToken token) {
        RateLimiterTokenBucket2 bucket = rateLimiterTokenBucketStore.tokenBucketForScope(token.scope());
        bucket.updateRateAfterSuccess();
    }

    @Override
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends BaseRetryStrategy.Builder implements AdaptiveRetryStrategy.Builder {
        private RateLimiterTokenBucketStore rateLimiterTokenBucketStore;

        Builder() {
        }

        Builder(DefaultAdaptiveRetryStrategy strategy) {
            super(strategy);
            this.rateLimiterTokenBucketStore = strategy.rateLimiterTokenBucketStore;
        }

        @Override
        public Builder retryOnException(Predicate<Throwable> shouldRetry) {
            setRetryOnException(shouldRetry);
            return this;
        }

        @Override
        public Builder maxAttempts(int maxAttempts) {
            setMaxAttempts(maxAttempts);
            return this;
        }

        @Override
        public Builder treatAsThrottling(Predicate<Throwable> treatAsThrottling) {
            setTreatAsThrottling(treatAsThrottling);
            return this;
        }

        @Override
        public Builder backoffStrategy(BackoffStrategy backoffStrategy) {
            setBackoffStrategy(backoffStrategy);
            return this;
        }

        @Override
        public Builder throttlingBackoffStrategy(BackoffStrategy backoffStrategy) {
            setThrottlingBackoffStrategy(backoffStrategy);
            return this;
        }

        public Builder circuitBreakerEnabled(Boolean circuitBreakerEnabled) {
            setCircuitBreakerEnabled(circuitBreakerEnabled);
            return this;
        }

        public Builder tokenBucketExceptionCost(int exceptionCost) {
            setTokenBucketExceptionCost(exceptionCost);
            return this;
        }

        public Builder throttlingTokenBucketExceptionCost(int throttlingExceptionCost) {
            setThrottlingTokenBucketExceptionCost(throttlingExceptionCost);
            return this;
        }

        public Builder rateLimiterTokenBucketStore(RateLimiterTokenBucketStore rateLimiterTokenBucketStore) {
            this.rateLimiterTokenBucketStore = rateLimiterTokenBucketStore;
            return this;
        }

        public Builder tokenBucketStore(TokenBucketStore tokenBucketStore) {
            setTokenBucketStore(tokenBucketStore);
            return this;
        }

        @Override
        public Builder useClientDefaults(boolean useClientDefaults) {
            setUseClientDefaults(useClientDefaults);
            return this;
        }

        @Override
        public AdaptiveRetryStrategy build() {
            return new DefaultAdaptiveRetryStrategy(this);
        }
    }
}
