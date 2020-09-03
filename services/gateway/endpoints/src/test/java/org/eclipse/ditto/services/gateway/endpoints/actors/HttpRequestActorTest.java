/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.gateway.endpoints.actors;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.model.base.acks.AcknowledgementRequest;
import org.eclipse.ditto.model.base.acks.DittoAcknowledgementLabel;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.services.gateway.endpoints.routes.whoami.Whoami;
import org.eclipse.ditto.signals.acks.base.Acknowledgement;
import org.eclipse.ditto.signals.commands.messages.SendThingMessageResponse;
import org.eclipse.ditto.signals.commands.things.modify.ModifyAttribute;
import org.eclipse.ditto.signals.commands.things.modify.ModifyAttributeResponse;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.ResponseEntity;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.stream.SystemMaterializer;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;
import akka.util.ByteString;

/**
 * Unit test for {@link HttpRequestActor}.
 */
public final class HttpRequestActorTest extends AbstractHttpRequestActorTest {

    @Test
    public void handlesWhoamiCommand() throws ExecutionException, InterruptedException {
        final DittoHeaders dittoHeaders = createAuthorizedHeaders();
        final Whoami whoami = Whoami.of(dittoHeaders);
        final HttpResponse expectedResponse = createExpectedWhoamiResponse(whoami);
        final HttpRequest request = HttpRequest.GET("/whoami");
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();

        final ActorRef underTest = createHttpRequestActor(TestProbe.apply(system).ref(), request, responseFuture);
        underTest.tell(Whoami.of(dittoHeaders), ActorRef.noSender());

        assertThat(responseFuture.get()).isEqualTo(expectedResponse);
    }

    @Test
    public void generateLocationHeaderInTwinPersistedAcknowledgementWithCreatedStatusCode() throws Exception {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String attributeName = "foo";
            final JsonValue attributeValue = JsonValue.of("bar");
            final JsonPointer attributePointer = JsonPointer.of(attributeName);
            final AcknowledgementLabel customAckLabel = AcknowledgementLabel.of("custom-ack");

            final DittoHeaders dittoHeaders = createAuthorizedHeaders().toBuilder()
                    .responseRequired(true)
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.TWIN_PERSISTED),
                            AcknowledgementRequest.of(customAckLabel))
                    .build();

            final ModifyAttribute command =
                    ModifyAttribute.of(thingId, attributePointer, attributeValue, dittoHeaders);
            final ModifyAttributeResponse createAttributeResponse =
                    ModifyAttributeResponse.created(thingId, attributePointer, attributeValue, dittoHeaders);
            final Acknowledgement customAcknowledgement =
                    Acknowledgement.of(customAckLabel, thingId, HttpStatusCode.FORBIDDEN, DittoHeaders.empty());

            final TestProbe proxyActorProbe = TestProbe.apply(system);

            final HttpRequest request =
                    HttpRequest.PUT("/api/2/things/" + thingId.toString() + "/attributes/" + attributeName);
            final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();

            final ActorRef underTest = createHttpRequestActor(proxyActorProbe.ref(), request, responseFuture);
            underTest.tell(command, ActorRef.noSender());

            proxyActorProbe.expectMsg(command);
            proxyActorProbe.reply(createAttributeResponse);
            proxyActorProbe.reply(customAcknowledgement);

            final HttpResponse response = responseFuture.get();
            assertThat(HttpStatusCode.forInt(response.status().intValue())).contains(HttpStatusCode.FAILED_DEPENDENCY);

            final JsonObject responseBody = JsonObject.of(
                    response.entity()
                            .toStrict(1000L, SystemMaterializer.get(system).materializer())
                            .toCompletableFuture()
                            .join()
                            .getData()
                            .utf8String()
            );
            assertThat(responseBody.getValue("twin-persisted/status"))
                    .contains(JsonValue.of(HttpStatusCode.CREATED.toInt()));
            assertThat(responseBody.getValue("twin-persisted/headers/location"))
                    .contains(JsonValue.of("/api/2/things/" + thingId + "/attributes/" + attributeName));
        }};
    }

    @Test
    public void handlesThingModifyCommand() throws ExecutionException, InterruptedException {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String attributeName = "foo";
            final JsonPointer attributePointer = JsonPointer.of(attributeName);

            final DittoHeaders dittoHeaders = createAuthorizedHeaders();
            final DittoHeaders expectedHeaders = dittoHeaders.toBuilder()
                    .responseRequired(true)
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.TWIN_PERSISTED))
                    .build();

            final ModifyAttributeResponse probeResponse =
                    ModifyAttributeResponse.modified(thingId, attributePointer, expectedHeaders);

            final StatusCode expectedHttpStatusCode = StatusCodes.NO_CONTENT;
            final boolean expectHttpResponseHeaders = true;

            testThingModifyCommand(thingId, attributeName, attributePointer, dittoHeaders, expectedHeaders,
                    probeResponse, expectedHttpStatusCode, expectHttpResponseHeaders);
        }};
    }

    @Test
    public void handlesThingModifyCommandLive() throws ExecutionException, InterruptedException {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String attributeName = "foo";
            final JsonPointer attributePointer = JsonPointer.of(attributeName);

            final DittoHeaders dittoHeaders = createAuthorizedHeaders().toBuilder()
                    .channel("live")
                    .build();
            final DittoHeaders expectedHeaders = dittoHeaders.toBuilder()
                    .responseRequired(true)
                    .channel("live")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();

            final ModifyAttributeResponse probeResponse =
                    ModifyAttributeResponse.modified(thingId, attributePointer, expectedHeaders);

            final StatusCode expectedHttpStatusCode = StatusCodes.NO_CONTENT;
            final boolean expectHttpResponseHeaders = true;

            testThingModifyCommand(thingId, attributeName, attributePointer, dittoHeaders, expectedHeaders,
                    probeResponse, expectedHttpStatusCode, expectHttpResponseHeaders);
        }};
    }

    @Test
    public void liveResponseHeadersDoNotCauseSpurious202Accepted() throws Exception {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String attributeName = "foo";
            final JsonPointer attributePointer = JsonPointer.of(attributeName);

            final DittoHeaders dittoHeaders = createAuthorizedHeaders().toBuilder()
                    .channel("live")
                    .build();
            final DittoHeaders expectedHeaders = dittoHeaders.toBuilder()
                    .responseRequired(true)
                    .channel("live")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();
            final DittoHeaders responseRequiredFalseHeaders = expectedHeaders.toBuilder()
                    .responseRequired(false)
                    .build();

            final ModifyAttributeResponse probeResponse =
                    ModifyAttributeResponse.modified(thingId, attributePointer, responseRequiredFalseHeaders);

            final StatusCode expectedHttpStatusCode = StatusCodes.NO_CONTENT;
            final boolean expectHttpResponseHeaders = false; // response headers are different

            testThingModifyCommand(thingId, attributeName, attributePointer, dittoHeaders, expectedHeaders,
                    probeResponse, expectedHttpStatusCode, expectHttpResponseHeaders);
        }};

    }

    @Test
    public void handlesThingModifyCommandLiveNoResponseRequired() throws ExecutionException, InterruptedException {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String attributeName = "foo";
            final JsonPointer attributePointer = JsonPointer.of(attributeName);

            final DittoHeaders dittoHeaders = createAuthorizedHeaders().toBuilder()
                    .responseRequired(false)
                    .channel("live")
                    .build();

            final DittoHeaders expectedHeaders = dittoHeaders.toBuilder()
                    .acknowledgementRequests(Collections.emptySet())
                    .build();

            final ModifyAttributeResponse probeResponse = null;

            final StatusCode expectedHttpStatusCode = StatusCodes.ACCEPTED;
            final boolean expectHttpResponseHeaders = false;

            testThingModifyCommand(thingId, attributeName, attributePointer, dittoHeaders, expectedHeaders,
                    probeResponse, expectedHttpStatusCode, expectHttpResponseHeaders);
        }};
    }

    @Test
    public void handlesThingModifyCommandLiveNoResponseRequiredAndLiveResponseAckRequest()
            throws ExecutionException, InterruptedException {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String attributeName = "foo";
            final JsonPointer attributePointer = JsonPointer.of(attributeName);

            final DittoHeaders dittoHeaders = createAuthorizedHeaders().toBuilder()
                    .responseRequired(false)
                    .channel("live")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();
            // headers shall be same:
            final DittoHeaders expectedHeaders = dittoHeaders.toBuilder()
                    .build();

            final ModifyAttributeResponse probeResponse =
                    ModifyAttributeResponse.modified(thingId, attributePointer, expectedHeaders);

            final StatusCode expectedHttpStatusCode = StatusCodes.ACCEPTED;
            final boolean expectHttpResponseHeaders = false;

            testThingModifyCommand(thingId, attributeName, attributePointer, dittoHeaders, expectedHeaders,
                    probeResponse, expectedHttpStatusCode, expectHttpResponseHeaders);
        }};
    }

    @Test
    public void handlesMessageCommand() throws ExecutionException, InterruptedException {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String messageSubject = "sayPing";

            final DittoHeaders dittoHeaders = createAuthorizedHeaders()
                    .toBuilder()
                    .responseRequired(true)
                    .channel("live")
                    .build();

            final DittoHeaders expectedHeaders = dittoHeaders.toBuilder()
                    .responseRequired(true)
                    .channel("live")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();

            final String contentType = "application/json";
            final JsonValue responsePayload = JsonValue.of("poooong");
            final SendThingMessageResponse<?> probeResponse =
                    buildSendThingMessageResponse(thingId, messageSubject, expectedHeaders, contentType,
                            responsePayload);

            final StatusCode expectedHttpStatusCode = StatusCodes.IMUSED;
            final boolean expectHttpResponseHeaders = true;
            final ResponseEntity expectedHttpResponseEntity = HttpEntities.create(
                    ContentTypes.parse(contentType), ByteString.ByteStrings.fromString(responsePayload.toString(),
                            Charset.defaultCharset()));

            testMessageCommand(thingId, messageSubject, dittoHeaders, expectedHeaders,
                    probeResponse, expectedHttpStatusCode, expectHttpResponseHeaders, expectedHttpResponseEntity);
        }};
    }

    @Test
    public void handlesMessageCommandNoResponseRequired() throws ExecutionException, InterruptedException {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String messageSubject = "sayPing";

            final DittoHeaders dittoHeaders = createAuthorizedHeaders().toBuilder()
                    .channel("live")
                    .responseRequired(false)
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();
            // headers shall be same:
            final DittoHeaders expectedHeaders = dittoHeaders.toBuilder()
                    .build();

            final String contentType = "application/json";
            final JsonValue responsePayload = JsonValue.of("poooong");
            final SendThingMessageResponse<?> probeResponse =
                    buildSendThingMessageResponse(thingId, messageSubject, expectedHeaders, contentType,
                            responsePayload);

            final StatusCode expectedHttpStatusCode = StatusCodes.ACCEPTED;
            final boolean expectHttpResponseHeaders = false;
            final ResponseEntity expectedHttpResponseEntity = null;

            testMessageCommand(thingId, messageSubject, dittoHeaders, expectedHeaders,
                    probeResponse, expectedHttpStatusCode, expectHttpResponseHeaders, expectedHttpResponseEntity);
        }};
    }

    @Test
    public void handlesMessageCommandNoResponseRequiredAndLiveResponseAckRequest()
            throws ExecutionException, InterruptedException {
        new TestKit(system) {{
            final ThingId thingId = ThingId.generateRandom();
            final String messageSubject = "sayPing";

            final DittoHeaders dittoHeaders = createAuthorizedHeaders().toBuilder()
                    .channel("live")
                    .responseRequired(false)
                    .build();

            final DittoHeaders expectedHeaders = dittoHeaders.toBuilder()
                    .acknowledgementRequests(Collections.emptySet())
                    .build();

            final SendThingMessageResponse<?> probeResponse = null;

            final StatusCode expectedHttpStatusCode = StatusCodes.ACCEPTED;
            final boolean expectHttpResponseHeaders = false;
            final ResponseEntity expectedHttpResponseEntity = null;

            testMessageCommand(thingId, messageSubject, dittoHeaders, expectedHeaders,
                    probeResponse, expectedHttpStatusCode, expectHttpResponseHeaders, expectedHttpResponseEntity);
        }};
    }
}
