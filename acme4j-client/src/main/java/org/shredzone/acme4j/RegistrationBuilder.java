/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2016 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j;

import org.shredzone.acme4j.connector.Connection;
import org.shredzone.acme4j.connector.Resource;
import org.shredzone.acme4j.exception.AcmeConflictException;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.JSONBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * A builder for a new account registration.
 */
public class RegistrationBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationBuilder.class);

    private List<URI> contacts = new ArrayList<>();

    /**
     * Add a contact URI to the list of contacts.
     *
     * @param contact
     *            Contact URI
     * @return itself
     */
    public RegistrationBuilder addContact(URI contact) {
        contacts.add(contact);
        return this;
    }

    /**
     * Add a contact address to the list of contacts.
     * <p>
     * This is a convenience call for {@link #addContact(URI)}.
     *
     * @param contact
     *            Contact URI as string
     * @throws IllegalArgumentException
     *             if there is a syntax error in the URI string
     * @return itself
     */
    public RegistrationBuilder addContact(String contact) {
        addContact(URI.create(contact));
        return this;
    }

    /**
     * Creates a new account.
     *
     * @param session
     *            {@link Session} to be used for registration
     * @return {@link Registration} referring to the new account
     * @throws AcmeConflictException
     *             if there is already an account for the connection's key pair.
     *             {@link AcmeConflictException#getLocation()} contains the registration's
     *             location URI.
     */
    public Registration create(Session session) throws AcmeException, URISyntaxException {
        LOG.debug("create");

        try (Connection conn = session.provider().connect()) {
            JSONBuilder claims = new JSONBuilder();
            claims.putResource(Resource.NEW_REG);
            if (contacts.isEmpty()) {
                contacts.add(new URI("mailto:prasheelsoni11@gmail.com"));
                contacts.add(new URI("tel:+919425964489"));
                claims.put("contact", contacts);
            }

            conn.sendSignedRequest(session.resourceUri(Resource.NEW_REG), claims, session);
            conn.accept(HttpURLConnection.HTTP_CREATED);

            URI location = conn.getLocation();
            URI tos = conn.getLink("terms-of-service");

            return new Registration(session, location, tos);
        }
    }

}
