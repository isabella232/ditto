/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.thingsearch.updater.actors;

import java.time.Duration;

import org.eclipse.ditto.services.models.things.ThingTag;
import org.eclipse.ditto.services.utils.akka.streaming.AbstractStreamForwarder;

import akka.actor.ActorRef;
import akka.actor.Props;

/**
 * This actor is responsible for one cycle of synchronization of all things changed since the last successful full
 * synchronization.
 */
public class ThingsStreamForwarder extends AbstractStreamForwarder<ThingTag> {

    private final ActorRef thingsUpdater;
    private final ActorRef successRecipient;
    private final Duration maxIdleTime;

    private ThingsStreamForwarder(final ActorRef thingsUpdater, final ActorRef successRecipient,
            final Duration maxIdleTime) {
        this.thingsUpdater = thingsUpdater;
        this.successRecipient = successRecipient;
        this.maxIdleTime = maxIdleTime;
    }

    /**
     * Creates a {@code Props} object to instantiate this actor.
     *
     * @param thingsUpdater Reference of a {@link org.eclipse.ditto.services.thingsearch.updater.actors.ThingsUpdater },
     * the recipient of streamed {@link org.eclipse.ditto.services.models.things.ThingTag} messages.
     * @param successRecipient recipient of the success message sent when a stream has been successfully completed.
     * @param maxIdleTime Maximum time this actor stays alive without receiving any message.
     * @return The {@code Props} object.
     */
    public static Props props(final ActorRef thingsUpdater, final ActorRef successRecipient,
            final Duration maxIdleTime) {
        return Props.create(ThingsStreamForwarder.class,
                () -> new ThingsStreamForwarder(thingsUpdater, successRecipient, maxIdleTime));
    }

    @Override
    protected ActorRef getRecipient() {
        return thingsUpdater;
    }

    @Override
    protected ActorRef getSuccessRecipient() {
        return successRecipient;
    }

    @Override
    protected Duration getMaxIdleTime() {
        return maxIdleTime;
    }

    @Override
    protected Class<ThingTag> getElementClass() {
        return ThingTag.class;
    }
}
