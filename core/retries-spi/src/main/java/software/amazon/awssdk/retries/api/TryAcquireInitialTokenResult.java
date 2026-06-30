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

package software.amazon.awssdk.retries.api;

import java.time.Duration;
import java.util.Optional;

public class TryAcquireInitialTokenResult {
    private final AcquireInitialTokenResponse response;
    private final Duration nextAttemptDelay;

    public TryAcquireInitialTokenResult(AcquireInitialTokenResponse response, Duration nextAttemptDelay) {
        this.response = response;
        this.nextAttemptDelay = nextAttemptDelay;
    }

    public Optional<AcquireInitialTokenResponse> response() {
        return Optional.ofNullable(response);
    }

    public Optional<Duration> nextAttemptDelay() {
        return Optional.ofNullable(nextAttemptDelay);
    }
}
