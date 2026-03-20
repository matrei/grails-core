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
package org.grails.web.taglib

import spock.lang.Specification

import grails.testing.web.taglib.TagLibUnitTest
import org.grails.plugins.web.taglib.FormTagLib

class FormRenderingTagLibTests extends Specification implements TagLibUnitTest<FormTagLib> {

    void 'renders time zone select'() {
        given:
        def output = applyTemplate('<g:timeZoneSelect name="foo" locale="en_US"/>')

        and: 'JDK versions differ in timezone display name formatting (including nbsp variants), normalize before assertions'
        def normalizedOutput = output.replace('\u202F', ' ').replaceAll(/\s+/, ' ')

        expect:
        normalizedOutput.startsWith('<select name="foo" id="foo" >')
        normalizedOutput.contains('value="Pacific/Galapagos"')
        normalizedOutput.contains(' -6:0.0 [Pacific/Galapagos]</option>')
        normalizedOutput.contains('value="US/Central"')
        normalizedOutput.contains(' -6:0.0 [US/Central]</option>')
        normalizedOutput.endsWith('</select>')
    }
}
