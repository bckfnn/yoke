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
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.*;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.json.DecodeException;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.io.IOException;
import java.util.*;

public class BodyParser extends Middleware {

    private final HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

    private void parseJson(final YokeHttpServerRequest request, final Buffer buffer, final Handler<Object> next) {
        try {
            String jsonString = buffer.toString("UTF-8");
            if (jsonString.length() > 0) {
                switch (jsonString.charAt(0)) {
                    case '{':
                        request.setBody(new JsonObject(jsonString));
                        next.handle(null);
                        break;
                    case '[':
                        request.setBody(new JsonArray(jsonString));
                        next.handle(null);
                        break;
                    default:
                        next.handle(400);
                }
            } else {
                next.handle(400);
            }
        } catch (DecodeException ex) {
            next.handle(ex);
        }
    }

    private void parseMap(final YokeHttpServerRequest request, final Buffer buffer, final Handler<Object> next) {
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(buffer.toString("UTF-8"), false);
        Map<String, String> params;
        Map<String, List<String>> prms = queryStringDecoder.parameters();
        if (prms.isEmpty()) {
            params = new HashMap<>();
        } else {
            params = new HashMap<>(prms.size());
            for (Map.Entry<String, List<String>> entry: prms.entrySet()) {
                params.put(entry.getKey(), entry.getValue().get(0));
            }
        }

        request.setBody(params);
        next.handle(null);
    }

    @SuppressWarnings("unchecked")
    private void parseMultipart(final YokeHttpServerRequest request, final Buffer buffer, final Handler<Object> next) {
        HttpPostRequestDecoder decoder = null;
        try {
            HttpRequest nettyReq = request.nettyRequest();
            decoder = new HttpPostRequestDecoder(factory, nettyReq);

            decoder.offer(new DefaultHttpContent(buffer.getByteBuf()));
            decoder.offer(LastHttpContent.EMPTY_LAST_CONTENT);

            for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                switch (data.getHttpDataType()) {
                    case Attribute:
                        if (request.body() == null) {
                            request.setBody(new HashMap<String, String>());
                        }
                        final Attribute attribute = (Attribute) data;
                        final Map<String, String> mapBody = request.mapBody();

                        String value = mapBody.get(attribute.getName());
                        // if there is more than 1 only the first is considered
                        if (value == null) {
                            mapBody.put(attribute.getName(), attribute.getValue());
                        }
                        break;
                    case FileUpload:
                        if (request.files() == null) {
                            request.setFiles(new HashMap<String, FileUpload>());
                        }
                        FileUpload fileUpload = (FileUpload) data;
                        request.files().put(fileUpload.getName(), fileUpload);
                        break;
                    default:
                        System.err.println(data);
                }
            }

            next.handle(null);
            // clean up
            decoder.cleanFiles();
        } catch (ErrorDataDecoderException | NotEnoughDataDecoderException | IncompatibleDataDecoderException | IOException e) {
            // clean up
            if (decoder != null) {
                decoder.cleanFiles();
            }
            next.handle(e);
        }
    }

    @Override
    public void handle(final YokeHttpServerRequest request, final Handler<Object> next) {
        final String method = request.method();

        // GET and HEAD have no setBody
        if ("GET".equals(method) || "HEAD".equals(method)) {
            next.handle(null);
        } else {
            final String contentType = request.headers().get("content-type");

            if (contentType != null) {

                final Buffer buffer = new Buffer(0);

                request.dataHandler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer event) {
                        if (request.bodyLengthLimit() != -1) {
                            if (buffer.length() < request.bodyLengthLimit()) {
                                buffer.appendBuffer(event);
                            } else {
                                request.dataHandler(null);
                                request.endHandler(null);
                                next.handle(413);
                            }
                        } else {
                            buffer.appendBuffer(event);
                        }
                    }
                });

                request.endHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void _void) {
                        if (contentType.contains("application/json")) {
                            parseJson(request, buffer, next);
                        } else if (contentType.contains("application/x-www-form-urlencoded")) {
                            parseMap(request, buffer, next);
                        } else if (contentType.contains("multipart/form-data")) {
                            parseMultipart(request, buffer, next);
                        } else {
                            next.handle(null);
                        }
                    }
                });
            }
        }
    }
}
