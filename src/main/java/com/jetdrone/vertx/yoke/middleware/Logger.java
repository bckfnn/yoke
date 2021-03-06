/*
 * Copyright 2011-2012 the original author or authors.
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
package com.jetdrone.vertx.yoke.middleware;

import com.jetdrone.vertx.yoke.Middleware;
import org.vertx.java.core.Handler;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class Logger extends Middleware {

    static final SimpleDateFormat ISODATE;

    static {
        ISODATE = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS zzz");
        ISODATE.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public enum Format {
        DEFAULT,
        SHORT,
        TINY
    }

    final boolean immediate;
    final Format format;
    final org.vertx.java.core.logging.Logger logger;

    public Logger(boolean immediate, Format format, org.vertx.java.core.logging.Logger logger) {
        this.immediate = immediate;
        this.format = format;
        this.logger = logger;
    }

    public Logger(Format format, org.vertx.java.core.logging.Logger logger) {
        this(false, format, logger);
    }

    public Logger(org.vertx.java.core.logging.Logger logger) {
        this(false, Format.DEFAULT, logger);
    }


    private void log(YokeHttpServerRequest request, long start) {
        int contentLength = 0;
        if (immediate) {
            Object obj = request.headers().get("content-length");
            if (obj != null) {
                contentLength = Integer.parseInt(obj.toString());
            }
        } else {
            Object obj = request.response().headers().get("content-length");
            if (obj != null) {
                contentLength = Integer.parseInt(obj.toString());
            }
        }

        int status = request.response().getStatusCode();
        String message = null;

        switch (format) {
            case DEFAULT:
                Object referrer = request.headers().get("referrer");
                Object userAgent = request.headers().get("user-agent");

                message = String.format("%s - - [%s] \"%s %s %s\" %d %d \"%s\" \"%s\"",
                        request.remoteAddress().getHostString(),
                        ISODATE.format(new Date(start)),
                        request.method(),
                        request.uri(),
                        request.protocolVersion(),
                        status,
                        contentLength,
                        referrer == null ? "" : referrer,
                        userAgent == null ? "" : userAgent);
                break;
            case SHORT:
                message = String.format("%s - %s %s %s %d %d - %d ms",
                        request.remoteAddress().getHostString(),
                        request.method(),
                        request.uri(),
                        request.protocolVersion(),
                        status,
                        contentLength,
                        (System.currentTimeMillis() - start));
                break;
            case TINY:
                message = String.format("%s %s %d %d - %d ms",
                        request.method(),
                        request.uri(),
                        request.response().getStatusCode(),
                        contentLength,
                        (System.currentTimeMillis() - start));
                break;
        }

        if (status >= 500) {
            logger.error(message);
        } else if (status >= 400) {
            logger.warn(message);
        } else if (status >= 300) {
            logger.warn(message);
        } else {
            logger.info(message);
        }
    }

    @Override
    public void handle(final YokeHttpServerRequest request, Handler<Object> next) {
        final long start = System.currentTimeMillis();

        if (immediate) {
            log(request, start);
        } else {
            request.response().endHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    log(request, start);
                }
            });
        }

        next.handle(null);
    }
}
