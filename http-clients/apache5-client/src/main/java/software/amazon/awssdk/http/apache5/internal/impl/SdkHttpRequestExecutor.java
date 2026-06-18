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

package software.amazon.awssdk.http.apache5.internal.impl;

import java.io.IOException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HeaderElements;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.Closer;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Timeout;

public class SdkHttpRequestExecutor extends HttpRequestExecutor {

    // Preserve the 4.x behavior where anything other than a "100 continue" response for a request with "Expect: 100-continue"
    // terminates the connection. In 5.x, 3xx responses still cause the client to send the body.
    public ClassicHttpResponse execute(
        ClassicHttpRequest request,
        HttpClientConnection conn,
        HttpResponseInformationCallback informationCallback,
        HttpContext localContext) throws IOException, HttpException {
        Args.notNull(request, "HTTP request");
        Args.notNull(conn, "Client connection");
        Args.notNull(localContext, "HTTP context");
        HttpCoreContext context = HttpCoreContext.castOrCreate(localContext);
        try {
            context.setSSLSession(conn.getSSLSession());
            context.setEndpointDetails(conn.getEndpointDetails());

            conn.sendRequestHeader(request);
            boolean expectContinue = false;
            HttpEntity entity = request.getEntity();
            if (entity != null) {
                Header expect = request.getFirstHeader(HttpHeaders.EXPECT);
                expectContinue = expect != null && HeaderElements.CONTINUE.equalsIgnoreCase(expect.getValue());
                if (!expectContinue) {
                    conn.sendRequestEntity(request);
                }
            }
            conn.flush();
            ClassicHttpResponse response = null;
            while (response == null) {
                if (expectContinue) {
                    Timeout timeout = Timeout.ofSeconds(3);
                    if (conn.isDataAvailable(timeout)) {
                        response = conn.receiveResponseHeader();
                        int status = response.getCode();
                        if (status == HttpStatus.SC_CONTINUE) {
                            // discard 100-continue
                            response = null;
                            conn.sendRequestEntity(request);
                        } else if (status < HttpStatus.SC_SUCCESS) {
                            if (informationCallback != null) {
                                informationCallback.execute(response, conn, context);
                            }
                            response = null;
                            continue;
                        } else {
                            conn.terminateRequest(request);
                        }
                    } else {
                        conn.sendRequestEntity(request);
                    }
                    conn.flush();
                    expectContinue = false;
                } else {
                    response = conn.receiveResponseHeader();
                    int status = response.getCode();
                    if (status < HttpStatus.SC_INFORMATIONAL) {
                        throw new ProtocolException("Invalid response: " + new StatusLine(response));
                    }
                    if (status < HttpStatus.SC_SUCCESS) {
                        if (informationCallback != null && status != HttpStatus.SC_CONTINUE) {
                            informationCallback.execute(response, conn, context);
                        }
                        response = null;
                    }
                }
            }
            if (MessageSupport.canResponseHaveBody(request.getMethod(), response)) {
                conn.receiveResponseEntity(response);
            }
            return response;

        } catch (HttpException | SSLException ex) {
            Closer.closeQuietly(conn);
            throw ex;
        } catch (SocketTimeoutException ex) {
            // If the server isn't responsive, we want to close the connection immediately
            // We set the socket timeout to a minimal value in such a case, because in some cases, the connection
            // might only have access to an SSLSocket that it will try to close gracefully.
            conn.setSocketTimeout(Timeout.ONE_MILLISECOND);
            Closer.close(conn, CloseMode.IMMEDIATE);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            Closer.close(conn, CloseMode.IMMEDIATE);
            throw ex;
        }
    }
}
