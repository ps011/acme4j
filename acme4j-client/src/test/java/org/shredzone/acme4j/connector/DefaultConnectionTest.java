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
package org.shredzone.acme4j.connector;

import org.jose4j.base64url.Base64Url;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.CompactSerializer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.exception.*;
import org.shredzone.acme4j.util.JSON;
import org.shredzone.acme4j.util.JSONBuilder;
import org.shredzone.acme4j.util.TestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

/**
 * Unit tests for {@link DefaultConnection}.
 */
public class DefaultConnectionTest {

    private URI requestUri = URI.create("http://localhost:8000/acme/directory");
    private HttpURLConnection mockUrlConnection;
    private HttpConnector mockHttpConnection;
    private Session session;

    @Before
    public void setup() throws IOException {
        mockUrlConnection = mock(HttpURLConnection.class);

        mockHttpConnection = mock(HttpConnector.class);
        when(mockHttpConnection.openConnection(requestUri)).thenReturn(mockUrlConnection);

        session = TestUtils.session();
        session.setLocale(Locale.JAPAN);
    }

    /**
     * Test if {@link DefaultConnection#updateSession(Session)} does nothing if there is
     * no {@code Replay-Nonce} header.
     */
    @Test
    public void testNoNonceFromHeader() throws AcmeException {
        when(mockUrlConnection.getHeaderField("Replay-Nonce")).thenReturn(null);

        assertThat(session.getNonce(), is(nullValue()));
        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.updateSession(session);
        }
        assertThat(session.getNonce(), is(nullValue()));

        verify(mockUrlConnection).getHeaderField("Replay-Nonce");
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test that {@link DefaultConnection#updateSession(Session)} extracts a
     * {@code Replay-Nonce} header correctly.
     */
    @Test
    public void testGetNonceFromHeader() {
        byte[] nonce = "foo-nonce-foo".getBytes();

        when(mockUrlConnection.getHeaderField("Replay-Nonce"))
                .thenReturn(Base64Url.encode(nonce));

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.updateSession(session);
        }
        assertThat(session.getNonce(), is(nonce));

        verify(mockUrlConnection).getHeaderField("Replay-Nonce");
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test that {@link DefaultConnection#updateSession(Session)} fails on an invalid
     * {@code Replay-Nonce} header.
     */
    @Test
    public void testInvalidNonceFromHeader() {
        String badNonce = "#$%&/*+*#'";

        when(mockUrlConnection.getHeaderField("Replay-Nonce")).thenReturn(badNonce);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.updateSession(session);
            fail("Expected to fail");
        } catch (AcmeProtocolException ex) {
            assertThat(ex.getMessage(), org.hamcrest.Matchers.startsWith("Invalid replay nonce"));
        }

        verify(mockUrlConnection).getHeaderField("Replay-Nonce");
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test that an absolute Location header is evaluated.
     */
    @Test
    public void testGetAbsoluteLocation() throws Exception {
        when(mockUrlConnection.getHeaderField("Location")).thenReturn("https://example.com/otherlocation");
        when(mockUrlConnection.getURL()).thenReturn(new URL("https://localhost:8000/acme"));

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            URI location = conn.getLocation();
            assertThat(location, is(new URI("https://example.com/otherlocation")));
        }

        verify(mockUrlConnection).getHeaderField("Location");
        verify(mockUrlConnection).getURL();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test that a relative Location header is evaluated.
     */
    @Test
    public void testGetRelativeLocation() throws Exception {
        when(mockUrlConnection.getHeaderField("Location")).thenReturn("/otherlocation");
        when(mockUrlConnection.getURL()).thenReturn(new URL("https://example.org/acme"));

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            URI location = conn.getLocation();
            assertThat(location, is(new URI("https://example.org/otherlocation")));
        }

        verify(mockUrlConnection).getHeaderField("Location");
        verify(mockUrlConnection).getURL();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test that absolute and relative Link headers are evaluated.
     */
    @Test
    public void testGetLink() throws Exception {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Content-Type", Arrays.asList("application/json"));
        headers.put("Location", Arrays.asList("https://example.com/acme/reg/asdf"));
        headers.put("Link", Arrays.asList(
                        "<https://example.com/acme/new-authz>;rel=\"next\"",
                        "</recover-reg>;rel=recover",
                        "<https://example.com/acme/terms>; rel=\"terms-of-service\""
                    ));

        when(mockUrlConnection.getHeaderFields()).thenReturn(headers);
        when(mockUrlConnection.getURL()).thenReturn(new URL("https://example.org/acme"));

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            assertThat(conn.getLink("next"), is(new URI("https://example.com/acme/new-authz")));
            assertThat(conn.getLink("recover"), is(new URI("https://example.org/recover-reg")));
            assertThat(conn.getLink("terms-of-service"), is(new URI("https://example.com/acme/terms")));
            assertThat(conn.getLink("secret-stuff"), is(nullValue()));
        }
    }

    /**
     * Test that multiple link headers are evaluated.
     */
    @Test
    public void testGetMultiLink() {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Link", Arrays.asList(
                        "<https://example.com/acme/terms1>; rel=\"terms-of-service\"",
                        "<https://example.com/acme/terms2>; rel=\"terms-of-service\"",
                        "<https://example.com/acme/terms3>; rel=\"terms-of-service\""
                    ));

        when(mockUrlConnection.getHeaderFields()).thenReturn(headers);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            assertThat(conn.getLinks("terms-of-service"), containsInAnyOrder(
                        URI.create("https://example.com/acme/terms1"),
                        URI.create("https://example.com/acme/terms2"),
                        URI.create("https://example.com/acme/terms3")
            ));
        }
    }

    /**
     * Test that no link headers are properly handled.
     */
    @Test
    public void testGetNoLink() {
        Map<String, List<String>> headers = Collections.emptyMap();
        when(mockUrlConnection.getHeaderFields()).thenReturn(headers);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            assertThat(conn.getLinks("something"), is(nullValue()));
        }
    }

    /**
     * Test that no Location header returns {@code null}.
     */
    @Test
    public void testNoLocation() throws Exception {
        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            URI location = conn.getLocation();
            assertThat(location, is(nullValue()));
        }

        verify(mockUrlConnection).getHeaderField("Location");
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test if Retry-After header with absolute date is correctly parsed.
     */
    @Test
    public void testHandleRetryAfterHeaderDate() throws AcmeException, IOException {
        Instant retryDate = Instant.now().plus(Duration.ofHours(10));
        String retryMsg = "absolute date";

        when(mockUrlConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_ACCEPTED);
        when(mockUrlConnection.getHeaderField("Retry-After")).thenReturn(retryDate.toString());
        when(mockUrlConnection.getHeaderFieldDate("Retry-After", 0L)).thenReturn(retryDate.toEpochMilli());

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.handleRetryAfter(retryMsg);
            fail("no AcmeRetryAfterException was thrown");
        } catch (AcmeRetryAfterException ex) {
            assertThat(ex.getRetryAfter(), is(retryDate));
            assertThat(ex.getMessage(), is(retryMsg));
        }

        verify(mockUrlConnection, atLeastOnce()).getResponseCode();
        verify(mockUrlConnection, atLeastOnce()).getHeaderField("Retry-After");
    }

    /**
     * Test if Retry-After header with relative timespan is correctly parsed.
     */
    @Test
    public void testHandleRetryAfterHeaderDelta() throws AcmeException, IOException {
        int delta = 10 * 60 * 60;
        long now = System.currentTimeMillis();
        String retryMsg = "relative time";

        when(mockUrlConnection.getResponseCode())
                .thenReturn(HttpURLConnection.HTTP_ACCEPTED);
        when(mockUrlConnection.getHeaderField("Retry-After"))
                .thenReturn(String.valueOf(delta));
        when(mockUrlConnection.getHeaderFieldDate(
                        ArgumentMatchers.eq("Date"),
                        ArgumentMatchers.anyLong()))
                .thenReturn(now);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.handleRetryAfter(retryMsg);
            fail("no AcmeRetryAfterException was thrown");
        } catch (AcmeRetryAfterException ex) {
            assertThat(ex.getRetryAfter(), is(Instant.ofEpochMilli(now).plusSeconds(delta)));
            assertThat(ex.getMessage(), is(retryMsg));
        }

        verify(mockUrlConnection, atLeastOnce()).getResponseCode();
        verify(mockUrlConnection, atLeastOnce()).getHeaderField("Retry-After");
    }

    /**
     * Test if no Retry-After header is correctly handled.
     */
    @Test
    public void testHandleRetryAfterHeaderNull() throws AcmeException, IOException {
        when(mockUrlConnection.getResponseCode())
                .thenReturn(HttpURLConnection.HTTP_ACCEPTED);
        when(mockUrlConnection.getHeaderField("Retry-After"))
                .thenReturn(null);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.handleRetryAfter("no header");
        } catch (AcmeRetryAfterException ex) {
            fail("an AcmeRetryAfterException was thrown");
        }

        verify(mockUrlConnection, atLeastOnce()).getResponseCode();
        verify(mockUrlConnection, atLeastOnce()).getHeaderField("Retry-After");
    }

    /**
     * Test if no HTTP_ACCEPTED status is correctly handled.
     */
    @Test
    public void testHandleRetryAfterNotAccepted() throws AcmeException, IOException {
        when(mockUrlConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.handleRetryAfter("http ok");
        } catch (AcmeRetryAfterException ex) {
            fail("an AcmeRetryAfterException was thrown");
        }

        verify(mockUrlConnection, atLeastOnce()).getResponseCode();
    }

    /**
     * Test if an {@link AcmeServerException} is thrown on an acme problem.
     */
    @Test
    public void testAccept() throws Exception {
        when(mockUrlConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            int rc = conn.accept(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_ACCEPTED);
            assertThat(rc, is(HttpURLConnection.HTTP_OK));
        }

        verify(mockUrlConnection).getResponseCode();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test if an {@link AcmeServerException} is thrown on an acme problem.
     */
    @Test
    public void testAcceptThrowsException() throws Exception {
        String jsonData = "{\"type\":\"urn:ietf:params:acme:error:unauthorized\",\"detail\":\"Invalid response: 404\"}";

        when(mockUrlConnection.getHeaderField("Content-Type")).thenReturn("application/problem+json");
        when(mockUrlConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_FORBIDDEN);
        when(mockUrlConnection.getErrorStream()).thenReturn(new ByteArrayInputStream(jsonData.getBytes("utf-8")));

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.accept(HttpURLConnection.HTTP_OK);
            fail("Expected to fail");
        } catch (AcmeServerException ex) {
            assertThat(ex.getType(), is("urn:ietf:params:acme:error:unauthorized"));
            assertThat(ex.getMessage(), is("Invalid response: 404"));
            assertThat(ex.getAcmeErrorType(), is("unauthorized"));
        } catch (AcmeException ex) {
            fail("Expected an AcmeServerException");
        }

        verify(mockUrlConnection, atLeastOnce()).getHeaderField("Content-Type");
        verify(mockUrlConnection, atLeastOnce()).getResponseCode();
        verify(mockUrlConnection).getErrorStream();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test if an {@link AcmeServerException} is thrown on another problem.
     */
    @Test
    public void testAcceptThrowsOtherException() throws IOException {
        when(mockUrlConnection.getHeaderField("Content-Type"))
                .thenReturn("application/problem+json");
        when(mockUrlConnection.getResponseCode())
                .thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection) {
            @Override
            public JSON readJsonResponse() {
                JSONBuilder result = new JSONBuilder();
                result.put("type", "urn:zombie:error:apocalypse");
                result.put("detail", "Zombie apocalypse in progress");
                return result.toJSON();
            };
        }) {
            conn.conn = mockUrlConnection;
            conn.accept(HttpURLConnection.HTTP_OK);
            fail("Expected to fail");
        } catch (AcmeServerException ex) {
            assertThat(ex.getType(), is("urn:zombie:error:apocalypse"));
            assertThat(ex.getMessage(), is("Zombie apocalypse in progress"));
            assertThat(ex.getAcmeErrorType(), is(nullValue()));
        } catch (AcmeException ex) {
            fail("Expected an AcmeServerException");
        }

        verify(mockUrlConnection).getHeaderField("Content-Type");
        verify(mockUrlConnection, atLeastOnce()).getResponseCode();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test if an {@link AcmeException} is thrown if there is no error type.
     */
    @Test
    public void testAcceptThrowsNoTypeException() throws IOException {
        when(mockUrlConnection.getHeaderField("Content-Type"))
                .thenReturn("application/problem+json");
        when(mockUrlConnection.getResponseCode())
                .thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection) {
            @Override
            public JSON readJsonResponse() {
                return JSON.empty();
            };
        }) {
            conn.conn = mockUrlConnection;
            conn.accept(HttpURLConnection.HTTP_OK);
            fail("Expected to fail");
        } catch (AcmeNetworkException ex) {
            fail("Did not expect an AcmeNetworkException");
        } catch (AcmeException ex) {
            assertThat(ex.getMessage(), isEmptyOrNullString());
        }

        verify(mockUrlConnection).getHeaderField("Content-Type");
        verify(mockUrlConnection, atLeastOnce()).getResponseCode();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test if an {@link AcmeException} is thrown if there is a generic error.
     */
    @Test
    public void testAcceptThrowsServerException() throws IOException {
        when(mockUrlConnection.getHeaderField("Content-Type"))
                .thenReturn("text/html");
        when(mockUrlConnection.getResponseCode())
                .thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);
        when(mockUrlConnection.getResponseMessage())
                .thenReturn("Infernal Server Error");

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.accept(HttpURLConnection.HTTP_OK);
            fail("Expected to fail");
        } catch (AcmeException ex) {
            assertThat(ex.getMessage(), is("HTTP 500: Infernal Server Error"));
        }

        verify(mockUrlConnection).getHeaderField("Content-Type");
        verify(mockUrlConnection, atLeastOnce()).getResponseCode();
        verify(mockUrlConnection, atLeastOnce()).getResponseMessage();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test GET requests.
     */
    @Test
    public void testSendRequest() throws Exception {
        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.sendRequest(requestUri, session);
        }

        verify(mockUrlConnection).setRequestMethod("GET");
        verify(mockUrlConnection).setRequestProperty("Accept-Charset", "utf-8");
        verify(mockUrlConnection).setRequestProperty("Accept-Language", "ja-JP");
        verify(mockUrlConnection).setDoOutput(false);
        verify(mockUrlConnection).connect();
        verify(mockUrlConnection, atLeast(0)).getHeaderFields();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test signed POST requests.
     */
    @Test
    public void testSendSignedRequest() throws Exception {
        final byte[] nonce1 = "foo-nonce-1-foo".getBytes();
        final byte[] nonce2 = "foo-nonce-2-foo".getBytes();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        when(mockUrlConnection.getOutputStream()).thenReturn(outputStream);

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection) {
            @Override
            public void updateSession(Session session) {
                assertThat(session, is(sameInstance(DefaultConnectionTest.this.session)));
                if (session.getNonce() == null) {
                    session.setNonce(nonce1);
                } else if (session.getNonce() == nonce1) {
                    session.setNonce(nonce2);
                } else {
                    fail("unknown nonce");
                }
            };
        }) {
            JSONBuilder cb = new JSONBuilder();
            cb.put("foo", 123).put("bar", "a-string");
            //Test-1
            conn.sendSignedRequest(requestUri, cb, DefaultConnectionTest.this.session);
        }

        verify(mockUrlConnection).setRequestMethod("HEAD");
        verify(mockUrlConnection, times(2)).setRequestProperty("Accept-Language", "ja-JP");
        verify(mockUrlConnection, times(2)).connect();

        verify(mockUrlConnection).setRequestMethod("POST");
        verify(mockUrlConnection).setRequestProperty("Accept", "application/json");
        verify(mockUrlConnection).setRequestProperty("Accept-Charset", "utf-8");
        verify(mockUrlConnection).setRequestProperty("Content-Type", "application/jose+json");
        verify(mockUrlConnection).setDoOutput(true);
        verify(mockUrlConnection).setFixedLengthStreamingMode(outputStream.toByteArray().length);
        verify(mockUrlConnection).getOutputStream();
        verify(mockUrlConnection, atLeast(0)).getHeaderFields();
        verifyNoMoreInteractions(mockUrlConnection);

        String serialized = new String(outputStream.toByteArray(), "utf-8");
        String[] written = CompactSerializer.deserialize(serialized);
        String header = Base64Url.decodeToUtf8String(written[0]);
        String claims = Base64Url.decodeToUtf8String(written[1]);
        String signature = written[2];

        StringBuilder expectedHeader = new StringBuilder();
        expectedHeader.append('{');
        expectedHeader.append("\"nonce\":\"").append(Base64Url.encode(nonce1)).append("\",");
        expectedHeader.append("\"url\":\"").append(requestUri).append("\",");
        expectedHeader.append("\"alg\":\"RS256\",");
        expectedHeader.append("\"jwk\":{");
        expectedHeader.append("\"kty\":\"").append(TestUtils.KTY).append("\",");
        expectedHeader.append("\"e\":\"").append(TestUtils.E).append("\",");
        expectedHeader.append("\"n\":\"").append(TestUtils.N).append("\"");
        expectedHeader.append("}}");

        assertThat(header, sameJSONAs(expectedHeader.toString()));
        assertThat(claims, sameJSONAs("{\"foo\":123,\"bar\":\"a-string\"}"));
        assertThat(signature, not(isEmptyOrNullString()));

        JsonWebSignature jws = new JsonWebSignature();
        jws.setCompactSerialization(serialized);
        jws.setKey(DefaultConnectionTest.this.session.getKeyPair().getPublic());
        assertThat(jws.verifySignature(), is(true));
    }

    /**
     * Test signed POST requests if there is no nonce.
     */
    @Test(expected = AcmeProtocolException.class)
    public void testSendSignedRequestNoNonce() throws Exception {
        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            JSONBuilder cb = new JSONBuilder();

            conn.sendSignedRequest(requestUri, cb, DefaultConnectionTest.this.session);
        }
    }

    /**
     * Test getting a JSON response.
     */
    @Test
    public void testReadJsonResponse() throws Exception {
        String jsonData = "{\n\"foo\":123,\n\"bar\":\"a-string\"\n}\n";

        when(mockUrlConnection.getHeaderField("Content-Type")).thenReturn("application/json");
        when(mockUrlConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(mockUrlConnection.getInputStream()).thenReturn(new ByteArrayInputStream(jsonData.getBytes("utf-8")));

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            JSON result = conn.readJsonResponse();
            assertThat(result.keySet(), hasSize(2));
            assertThat(result.get("foo").asInt(), is(123));
            assertThat(result.get("bar").asString(), is("a-string"));
        }

        verify(mockUrlConnection).getHeaderField("Content-Type");
        verify(mockUrlConnection).getResponseCode();
        verify(mockUrlConnection).getInputStream();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test that a certificate is downloaded correctly.
     */
    @Test
    public void testReadCertificate() throws Exception {
        X509Certificate original = TestUtils.createCertificate();

        when(mockUrlConnection.getHeaderField("Content-Type")).thenReturn("application/pkix-cert");
        when(mockUrlConnection.getInputStream()).thenReturn(new ByteArrayInputStream(original.getEncoded()));

        X509Certificate downloaded;
        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            downloaded = conn.readCertificate();
        }

        assertThat(original, not(nullValue()));
        assertThat(downloaded, not(nullValue()));
        assertThat(original.getEncoded(), is(equalTo(downloaded.getEncoded())));

        verify(mockUrlConnection).getHeaderField("Content-Type");
        verify(mockUrlConnection).getInputStream();
        verifyNoMoreInteractions(mockUrlConnection);
    }

    /**
     * Test that a bad certificate throws an exception.
     */
    @Test(expected = AcmeProtocolException.class)
    public void testReadBadCertificate() throws Exception {
        X509Certificate original = TestUtils.createCertificate();
        byte[] badCert = original.getEncoded();
        Arrays.sort(badCert); // break it

        when(mockUrlConnection.getHeaderField("Content-Type")).thenReturn("application/pkix-cert");
        when(mockUrlConnection.getInputStream()).thenReturn(new ByteArrayInputStream(badCert));

        try (DefaultConnection conn = new DefaultConnection(mockHttpConnection)) {
            conn.conn = mockUrlConnection;
            conn.readCertificate();
        }
    }

}
