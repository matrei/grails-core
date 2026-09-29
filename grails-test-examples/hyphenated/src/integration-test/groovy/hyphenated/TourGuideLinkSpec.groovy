/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package hyphenated

import java.net.http.HttpClient

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Tag

import grails.testing.mixin.integration.Integration
import org.apache.grails.testing.http.client.HttpClientSupport

/**
 * Under the hyphenated URL converter a request holds its controller name as the URL wrote it, as
 * {@code tour-desk} for {@code TourDeskController}. Three controllers serve {@link TourGuide}, two of them
 * in the {@code backOffice} namespace and none named after it, and a link or redirect to a tour guide still
 * stays in the controller handling the request.
 */
@Integration
@Tag('http-client')
class TourGuideLinkSpec extends Specification implements HttpClientSupport {

    @Shared
    HttpClient noRedirectClient

    void setup() {
        noRedirectClient = noRedirectClient ?: newHttpClientWith {
            followRedirects(HttpClient.Redirect.NEVER)
        }
    }

    void 'a tour guide saved from a form through #controller is shown by it'() {
        when:
        def response = httpPostForm([:], "${path}/save", [name: 'Ada'], noRedirectClient)

        then:
        response.assertStatus(302)
        URI.create(response.headerValue('Location')).path ==~ "${expected}/show/\\d+"

        where:
        controller              | path                        || expected
        'TourDeskController'    | '/back-office/tour-desk'    || '/backOffice/tour-desk'
        'GuideLedgerController' | '/back-office/guide-ledger' || '/backOffice/guide-ledger'
        'CityGuidesController'  | '/city-guides'              || '/city-guides'
    }

    void 'a link to a tour guide rendered by #controller targets it'() {
        given:
        Long id = TourGuide.withNewTransaction {
            new TourGuide(name: 'Grace').save(flush: true, failOnError: true).id
        }

        when:
        def response = http("${path}/guide-link/${id}")

        then:
        response.assertStatus(200)
        response.body() == "${expected}/show/${id}"

        where:
        controller              | path                        || expected
        'TourDeskController'    | '/back-office/tour-desk'    || '/backOffice/tour-desk'
        'GuideLedgerController' | '/back-office/guide-ledger' || '/backOffice/guide-ledger'
        'CityGuidesController'  | '/city-guides'              || '/city-guides'
    }
}
