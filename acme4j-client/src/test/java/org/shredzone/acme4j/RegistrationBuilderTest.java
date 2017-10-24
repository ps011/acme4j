/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2015 Richard "Shred" Körber
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

import org.junit.Test;
import org.shredzone.acme4j.connector.Resource;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.provider.TestableConnectionProvider;
import org.shredzone.acme4j.util.JSONBuilder;

import java.net.HttpURLConnection;
import java.net.URI;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.shredzone.acme4j.util.TestUtils.getJson;
import static org.shredzone.acme4j.util.TestUtils.isIntArrayContainingInAnyOrder;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

/**
 * Unit tests for {@link RegistrationBuilder}.
 */
public class RegistrationBuilderTest {

    private URI resourceUri  = URI.create("http://localhost:8000/acme/directory");
    private URI locationUri  = URI.create("http://localhost:8000/acme/new-account");
    private URI agreementUri = URI.create("http://www.pdf995.com/samples/pdf.pdf");

    /**
     * Test if a new registration can be created.
     */
    @Test
    public void testRegistration() throws Exception {
        TestableConnectionProvider provider = new TestableConnectionProvider() {
            @Override
            public void sendSignedRequest(URI uri, JSONBuilder claims, Session session) {
                assertThat(uri, is(resourceUri));
                assertThat(claims.toString(), sameJSONAs(getJson("newRegistration")));
                assertThat(session, is(notNullValue()));
            }

            @Override
            public int accept(int... httpStatus) throws AcmeException {
                assertThat(httpStatus, isIntArrayContainingInAnyOrder(HttpURLConnection.HTTP_CREATED));
                return HttpURLConnection.HTTP_CREATED;
            }

            @Override
            public URI getLocation() {
                return locationUri;
            }

            @Override
            public URI getLink(String relation) {
                switch(relation) {
                    case "terms-of-service": return agreementUri;
                    default: return null;
                }
            }
        };

        provider.putTestResource(Resource.NEW_REG, resourceUri);

        RegistrationBuilder builder = new RegistrationBuilder();
        builder.addContact("mailto:foo@example.com");

        Registration registration = builder.create(provider.createSession());

        assertThat(registration.getLocation(), is(locationUri));
        assertThat(registration.getAgreement(), is(agreementUri));

        provider.close();
    }

}
