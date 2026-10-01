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
package org.grails.spring.context.support

import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import spock.lang.Specification

/**
 * Covers how the basenames configured on a {@link PluginAwareResourceBundleMessageSource} bean are
 * combined with the bundles it discovers itself (GH #11795).
 */
class PluginAwareResourceBundleMessageSourceSpec extends Specification {

    Resource overrides
    Resource messages

    void setup() {
        overrides = new TestResource('overrides.properties', '''\
            shared.message=Overrides Message
            overrides.only=Only In Overrides
        '''.stripIndent().getBytes('UTF-8'))

        messages = new TestResource('messages.properties', '''\
            shared.message=Messages Message
            messages.only=Only In Messages
        '''.stripIndent().getBytes('UTF-8'))
    }

    void 'configured basenames are resolved ahead of discovered basenames'() {
        given: 'a bundle listed on the bean that discovery would otherwise rank last'
        def messageSource = messageSourceDiscovering(messages, overrides)
        messageSource.setBasenames('overrides', 'messages')

        when:
        messageSource.afterPropertiesSet()

        then: 'the configured order decides which bundle wins a shared code'
        messageSource.getMessage('shared.message', null, Locale.ENGLISH) == 'Overrides Message'
        messageSource.getMessage('overrides.only', null, Locale.ENGLISH) == 'Only In Overrides'
        messageSource.getMessage('messages.only', null, Locale.ENGLISH) == 'Only In Messages'
    }

    void 'a configured basename that discovery does not find is kept'() {
        given: 'only the messages bundle is discoverable, the overrides bundle is configured explicitly'
        def messageSource = messageSourceDiscovering(messages)
        messageSource.setBasenames('overrides')

        when:
        messageSource.afterPropertiesSet()

        then: 'both bundles resolve and the configured one wins'
        messageSource.getMessage('shared.message', null, Locale.ENGLISH) == 'Overrides Message'
        messageSource.getMessage('messages.only', null, Locale.ENGLISH) == 'Only In Messages'
    }

    void 'discovered basenames alone are used when none are configured'() {
        given:
        def messageSource = messageSourceDiscovering(messages, overrides)

        when:
        messageSource.afterPropertiesSet()

        then: 'discovery order applies and every discovered bundle resolves'
        messageSource.getMessage('shared.message', null, Locale.ENGLISH) == 'Messages Message'
        messageSource.getMessage('overrides.only', null, Locale.ENGLISH) == 'Only In Overrides'
    }

    void 'null or empty configured basenames fall back to the discovered ones'() {
        given:
        def messageSource = messageSourceDiscovering(messages)
        messageSource.setBasenames(basenames as String[])

        when:
        messageSource.afterPropertiesSet()

        then:
        messageSource.getMessage('shared.message', null, Locale.ENGLISH) == 'Messages Message'

        where:
        basenames << [null, []]
    }

    private PluginAwareResourceBundleMessageSource messageSourceDiscovering(Resource... discovered) {
        def messageSource = new PluginAwareResourceBundleMessageSource()
        messageSource.setResourceLoader(new DefaultResourceLoader() {
            @Override
            Resource getResourceByPath(String path) {
                path.startsWith('overrides') ? overrides : messages
            }
        })
        messageSource.setSearchClasspath(true)
        messageSource.setResourceResolver(new PathMatchingResourcePatternResolver() {
            @Override
            Resource[] getResources(String locationPattern) {
                discovered
            }
        })
        messageSource
    }

    static class TestResource extends ByteArrayResource {
        final String filename

        TestResource(String filename, byte[] byteArray) {
            super(byteArray)
            this.filename = filename
        }
    }

}
