/*
 * Copyright 2021 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.plugin.vertx.interceptor;

import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.context.TraceId;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.request.ClientHeaderAdaptor;
import com.navercorp.pinpoint.bootstrap.plugin.request.ClientRequestAdaptor;
import com.navercorp.pinpoint.bootstrap.plugin.request.ClientRequestRecorder;
import com.navercorp.pinpoint.bootstrap.plugin.request.ClientRequestWrapper;
import com.navercorp.pinpoint.bootstrap.plugin.request.ClientRequestWrapperAdaptor;
import com.navercorp.pinpoint.bootstrap.plugin.request.DefaultRequestTraceWriter;
import com.navercorp.pinpoint.bootstrap.plugin.request.RequestTraceWriter;
import com.navercorp.pinpoint.bootstrap.plugin.request.util.CookieExtractor;
import com.navercorp.pinpoint.bootstrap.plugin.request.util.CookieRecorder;
import com.navercorp.pinpoint.bootstrap.plugin.request.util.CookieRecorderFactory;
import com.navercorp.pinpoint.plugin.vertx.HttpRequestClientHeaderAdaptor;
import com.navercorp.pinpoint.plugin.vertx.SamplingRateFlagAccessor;
import com.navercorp.pinpoint.plugin.vertx.VertxConstants;
import com.navercorp.pinpoint.plugin.vertx.VertxCookieExtractor;
import com.navercorp.pinpoint.plugin.vertx.VertxHttpClientConfig;
import com.navercorp.pinpoint.plugin.vertx.VertxHttpClientRequestWrapper;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

public abstract class Http1xClientConnectionCreateRequestInterceptor implements AroundInterceptor {
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final boolean isDebug = logger.isDebugEnabled();

    private TraceContext traceContext;
    private MethodDescriptor descriptor;
    private final ClientRequestRecorder<ClientRequestWrapper> clientRequestRecorder;
    private final CookieRecorder<HttpRequest> cookieRecorder;
    private final RequestTraceWriter<HttpRequest> requestTraceWriter;

    abstract String getHost(Object[] args);

    public Http1xClientConnectionCreateRequestInterceptor(TraceContext traceContext, MethodDescriptor descriptor) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;

        final VertxHttpClientConfig config = new VertxHttpClientConfig(traceContext.getProfilerConfig());
        ClientRequestAdaptor<ClientRequestWrapper> clientRequestAdaptor = ClientRequestWrapperAdaptor.INSTANCE;
        this.clientRequestRecorder = new ClientRequestRecorder<>(config.isParam(), clientRequestAdaptor);

        CookieExtractor<HttpRequest> cookieExtractor = new VertxCookieExtractor();
        this.cookieRecorder = CookieRecorderFactory.newCookieRecorder(config.getHttpDumpConfig(), cookieExtractor);

        ClientHeaderAdaptor<HttpRequest> clientHeaderAdaptor = new HttpRequestClientHeaderAdaptor();
        this.requestTraceWriter = new DefaultRequestTraceWriter<>(clientHeaderAdaptor, traceContext);
    }

    @Override
    public void before(Object target, Object[] args) {
        if (isDebug) {
            logger.beforeInterceptor(target, args);
        }

        if (Boolean.FALSE == isSamplingRate(target)) {
            return;
        }

        final Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        if (!trace.canSampled()) {
            return;
        }

        try {
            final SpanEventRecorder recorder = trace.traceBlockBegin();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("BEFORE. Caused:{}", t.getMessage(), t);
            }
        }
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        if (isDebug) {
            logger.afterInterceptor(target, args, result, throwable);
        }

        if (Boolean.FALSE == isSamplingRate(target)) {
            writeSamplingRateFalse(result);
            return;
        }

        final Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        if (!trace.canSampled()) {
            // Defense
            writeSamplingRateFalse(result);
            return;
        }

        try {
            final SpanEventRecorder recorder = trace.currentSpanEventRecorder();
            recorder.recordApi(descriptor);
            recorder.recordException(throwable);
            recorder.recordServiceType(VertxConstants.VERTX_HTTP_CLIENT);

            if (!validate(args, result)) {
                return;
            }

            final HttpRequest request = (HttpRequest) result;
            final HttpHeaders headers = request.headers();
            if (headers == null) {
                return;
            }

            final String host = getHost(args);
            // generate next trace id.
            final TraceId nextId = trace.getTraceId().getNextTraceId();
            recorder.recordNextSpanId(nextId.getSpanId());
            requestTraceWriter.write(request, nextId, host);
            final ClientRequestWrapper clientRequest = new VertxHttpClientRequestWrapper(request, host);
            this.clientRequestRecorder.record(recorder, clientRequest, throwable);
            this.cookieRecorder.record(recorder, request, throwable);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("AFTER. Caused:{}", t.getMessage(), t);
            }
        } finally {
            trace.traceBlockEnd();
        }
    }

    private boolean isSamplingRate(Object target) {
        if (target instanceof SamplingRateFlagAccessor) {
            final SamplingRateFlagAccessor samplingRateFlagAccessor = (SamplingRateFlagAccessor) target;
            if (samplingRateFlagAccessor != null) {
                final Boolean samplingRateFlag = samplingRateFlagAccessor._$PINPOINT$_getSamplingRateFlag();
                if (samplingRateFlag != null) {
                    return samplingRateFlag;
                }
            }
        }
        return true;
    }

    private void writeSamplingRateFalse(Object result) {
        if (result instanceof HttpRequest) {
            final HttpRequest request = (HttpRequest) result;
            requestTraceWriter.write(request);
        }
    }

    private boolean validate(final Object[] args, final Object result) {
        if (!(result instanceof HttpRequest)) {
            logger.debug("Invalid result object. {}.", result);
            return false;
        }

        return true;
    }
}