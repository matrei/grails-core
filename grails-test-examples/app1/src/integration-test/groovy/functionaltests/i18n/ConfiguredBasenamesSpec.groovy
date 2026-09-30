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
package functionaltests.i18n

import grails.testing.mixin.integration.Integration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.MessageSource
import spock.lang.Specification

/**
 * The messageSource bean in resources.groovy lists its basenames explicitly. Those bundles must be
 * resolved ahead of the ones Grails discovers in grails-app/i18n, in the order listed (GH #11795).
 */
@Integration
class ConfiguredBasenamesSpec extends Specification {

    @Autowired
    MessageSource messageSource

    void 'a bundle listed before messages overrides a code that messages also defines'() {
        expect:
        messageSource.getMessage('basenames.order.probe', [] as Object[], Locale.ENGLISH) == 'From overrides'
    }

    void 'a configured bundle outside grails-app/i18n is resolved'() {
        expect:
        messageSource.getMessage('basenames.external.probe', [] as Object[], Locale.ENGLISH) == 'From external bundle'
    }

    void 'discovered application and plugin bundles still resolve'() {
        expect:
        messageSource.getMessage('default.boolean.true', [] as Object[], Locale.ENGLISH) == 'True'
        messageSource.getMessage('my.plugin.message', [] as Object[], Locale.ENGLISH) == 'From a Plugin'
    }
}
