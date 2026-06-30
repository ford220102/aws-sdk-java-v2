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

import java.util.function.Consumer;

public class AdaptiveRetryToken extends DefaultRetryToken {
    public enum RateLimiterAcquireStatus {
        ACQUIRING,
        ACQUIRED
    }

    private final RateLimiterAcquireStatus rateLimiterAcquireStatus;

    protected AdaptiveRetryToken(Builder builder) {
        super(builder);
        this.rateLimiterAcquireStatus = builder.rateLimiterAcquireStatus;
    }

    public RateLimiterAcquireStatus rateLimiterAcquireStatus() {
        return rateLimiterAcquireStatus;
    }

    @Override
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends DefaultRetryToken.Builder {
        private RateLimiterAcquireStatus rateLimiterAcquireStatus = RateLimiterAcquireStatus.ACQUIRING;

        Builder() {
        }

        Builder(AdaptiveRetryToken token) {
            super(token);
        }

        public Builder rateLimiterAcquireStatus(RateLimiterAcquireStatus rateLimiterAcquireStatus) {
            this.rateLimiterAcquireStatus = rateLimiterAcquireStatus;
            return this;
        }

        @Override
        public Builder scope(String scope) {
            super.scope(scope);
            return this;
        }

        @Override
        public Builder state(TokenState state) {
            super.state(state);
            return this;
        }

        @Override
        public Builder increaseAttempt() {
            super.increaseAttempt();
            return this;
        }

        @Override
        public Builder capacityAcquired(int capacityAcquired) {
            super.capacityAcquired(capacityAcquired);
            return this;
        }

        @Override
        public Builder capacityRemaining(int capacityRemaining) {
            super.capacityRemaining(capacityRemaining);
            return this;
        }

        @Override
        public Builder addFailure(Throwable failure) {
            super.addFailure(failure);
            return this;
        }

        @Override
        public Builder copy() {
            return null;
        }

        @Override
        public Builder applyMutation(Consumer<DefaultRetryToken.Builder> mutator) {
            super.applyMutation(mutator);
            return this;
        }

        @Override
        public AdaptiveRetryToken build() {
            return new AdaptiveRetryToken(this);
        }
    }
}
