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

import org.springframework.context.support.StaticMessageSource

import grails.testing.web.taglib.TagLibUnitTest
import org.grails.plugins.web.taglib.ApplicationTagLib

class MessageTagTests extends Specification implements TagLibUnitTest<ApplicationTagLib> {

    void 'message tag renders message code in template'() {
        given:
        (messageSource as StaticMessageSource).with {
            addMessage('test.code', new Locale('en'), 'hello world!')
        }

        and:
        def output = applyTemplate('<g:message code="test.code" />')

        expect:
        output == 'hello world!'
    }

    void 'message tag resolves codes and arguments'() {
        given:
        (messageSource as StaticMessageSource).with {
            addMessage('test.code', new Locale('en'), 'hello world!')
            addMessage('test.args', new Locale('en'), 'hello {0}!')
        }

        when:
        def output = applyTemplate('<g:message code="test.code" />')

        then:
        output == 'hello world!'

        when:
        output = applyTemplate('<g:message code="test.args" args="${["fred"]}" />')

        then:
        output == 'hello fred!'
    }

    void 'message tag applies codec when encodeAs is specified'() {
        given:
        (messageSource as StaticMessageSource).with {
            addMessage('test.code', new Locale('en'), '>>&&')
        }

        when:
        def output = applyTemplate('<g:message code="test.code" encodeAs="HTML" />')

        then:
        output == '&gt;&gt;&amp;&amp;'
    }
}
