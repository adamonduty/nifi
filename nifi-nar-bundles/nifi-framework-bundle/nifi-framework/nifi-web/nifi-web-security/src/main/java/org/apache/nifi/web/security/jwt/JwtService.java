/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import io.jsonwebtoken.UnsupportedJwtException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.admin.service.AdministrationException;
import org.apache.nifi.admin.service.KeyService;
import org.apache.nifi.web.security.token.LoginAuthenticationToken;

/**
 *
 */
public class JwtService {

    private final static String AUTHORIZATION = "Authorization";

    private final KeyService keyService;

    public JwtService(final KeyService keyService) {
        this.keyService = keyService;
    }

    /**
     * Gets the Authentication by extracting a JWT token from the specified request.
     *
     * @param request Request to extract the token from
     * @return The user identifier from the token
     */
    public String getAuthentication(final HttpServletRequest request) {
        // extract/verify token from incoming request
        final String authorization = request.getHeader(AUTHORIZATION);
        final String token = StringUtils.substringAfterLast(authorization, " ");

        try {
            final Jws<Claims> jwt = Jwts.parser().setSigningKeyResolver(new SigningKeyResolverAdapter() {
                @Override
                public byte[] resolveSigningKeyBytes(JwsHeader header, Claims claims) {
                    final String identity = claims.getSubject();
                    final String key = keyService.getKey(identity);

                    // ensure we were able to find a key that was previously issued by this key service for this user
                    if (key == null) {
                        throw new UnsupportedJwtException("Unable to determine signing key for " + identity);
                    }

                    return key.getBytes(StandardCharsets.UTF_8);
                }
            }).parseClaimsJws(token);
            return jwt.getBody().getSubject();
        } catch (final MalformedJwtException | UnsupportedJwtException | SignatureException | ExpiredJwtException | IllegalArgumentException | AdministrationException e) {
            return null;
        }
    }

    /**
     * Adds a token for the specified authentication in the specified response.
     *
     * @param response The response to add the token to
     * @param authentication The authentication to generate a token for
     * @throws java.io.IOException if an io exception occurs
     */
    public void addToken(final HttpServletResponse response, final LoginAuthenticationToken authentication) throws IOException {
        // set expiration to one day from now
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(calendar.getTimeInMillis() + authentication.getExpiration());

        // create a token the specified authentication
        final String identity = authentication.getPrincipal().toString();
        final String username = authentication.getName();

        // get/create the key for this user
        final String key = keyService.getOrCreateKey(identity);
        final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        // build the token
        final String token = Jwts.builder().setSubject(identity).claim("preferred_username", username).setExpiration(calendar.getTime()).signWith(SignatureAlgorithm.HS512, keyBytes).compact();

        // add the token as a response header
        final PrintWriter out = response.getWriter();
        out.print(token);

        // mark the response as successful
        response.setStatus(HttpServletResponse.SC_CREATED);
        response.setContentType("text/plain");
    }

}