/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2019 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package co.elastic.apm.agent.spring.webflux;

import co.elastic.apm.agent.bci.VisibleForAdvice;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.Scope;
import co.elastic.apm.agent.impl.context.Request;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import co.elastic.apm.agent.impl.transaction.Transaction;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.server.ServerWebExchange;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

@VisibleForAdvice
public class RequestHandlerAdvice {

    @VisibleForAdvice
    public static final String TRANSACTION_ATTRIBUTE = RequestHandlerAdvice.class.getName() + ".transaction";

    public static final Logger logger = LoggerFactory.getLogger(RequestHandlerAdvice.class);

    @Nullable
    @VisibleForAdvice
    public static ElasticApmTracer tracer;

    @VisibleForAdvice
    public static void init(ElasticApmTracer tracer) {
        RequestHandlerAdvice.tracer = tracer;
    }

    @Nullable
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(@Advice.Argument(0) ServerWebExchange exchange,
                                     @Advice.Local("transaction") Transaction transaction,
                                     @Advice.Local("scope") Scope scope) {

        if (tracer == null) {
            return;
        }


        final Transaction transactionAttr = (Transaction) exchange.getAttribute(TRANSACTION_ATTRIBUTE);
        if (tracer.currentTransaction() == null && transactionAttr != null) {
            scope = transactionAttr.activateInScope();
        }

        ServerHttpRequest request = exchange.getRequest();
        transaction = tracer.startTransaction(TraceContext.fromTraceparentHeader(),
            request.getHeaders().getFirst(TraceContext.TRACE_PARENT_HEADER),
            exchange.getClass().getClassLoader()).activate();


        final Request transactionRequest = transaction.getContext().getRequest();

        transactionRequest.withMethod(request.getMethod().name());

        transactionRequest.getSocket()
            .withEncrypted(request.getSslInfo() != null)
            .withRemoteAddress(request.getRemoteAddress().toString());

        transactionRequest.getUrl()
            .withProtocol(request.getURI().getScheme())
            .withHostname(request.getURI().getHost())
            .withPort(request.getURI().getPort())
            .withPathname(request.getURI().getPath())
            .withSearch(request.getURI().getQuery());

    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterExecute(@Advice.Argument(0) ServerWebExchange exchange,
                                    @Advice.Argument(1) Object handler,
                                    @Advice.Local("transaction") Transaction transaction,
                                    @Advice.Local("scope") Scope scope,
                                    @Advice.Thrown @Nullable Throwable t,
                                    @Advice.This Object thiz) {

        if (tracer == null) {
            return;
        }

        logger.info(handler.getClass().getName());

        if (transaction != null) {
            HttpStatus responseCode = exchange.getResponse().getStatusCode();
            if (handler != null) {
                if (handler instanceof HandlerMethod) {
                    HandlerMethod handlerMethod = (HandlerMethod) handler;
                    transaction.withName(handlerMethod.getMethod().getDeclaringClass().getSimpleName() + "#"
                        + handlerMethod.getMethod().getName());
                } else if (handler instanceof HandlerFunction<?>){
                    for (Method method : handler.getClass().getMethods()) {
                        if (method.getName().equals("handle")) {
                            transaction.withName(method.getDeclaringClass().getSimpleName() + "#"
                                + method.getName());
                        }
                    }
                }
            }
            transaction.withResultIfUnset("Http " + responseCode.value());
            transaction.withType("request");
            if (t != null) {
                transaction.captureException(t);
            }

            transaction.deactivate();
            transaction.end();
        }

    }
}
