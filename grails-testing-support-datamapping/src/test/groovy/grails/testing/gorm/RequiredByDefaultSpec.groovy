/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package grails.testing.gorm

import spock.lang.Specification

import grails.gorm.annotation.Entity
import grails.validation.Validateable

class RequiredByDefaultSpec extends Specification implements DomainUnitTest<NullableTestRecord> {

    Closure doWithConfig() {
        { config -> config.grails.gorm.default.nullable = false }
    }

    void 'domain validation honors the configured required default and explicit nullable constraints'() {
        expect:
        !domain.validate()
        domain.errors.getFieldError('name').code == 'nullable'
        domain.errors.getFieldError('requiredName').code == 'nullable'
        !domain.errors.hasFieldErrors('optionalName')

        when:
        domain.name = 'record'
        domain.requiredName = 'required'

        then:
        domain.save()
        NullableTestRecord.count() == 1
    }

    void 'command object properties remain required'() {
        given:
        NullableTestCommand command = new NullableTestCommand()

        expect:
        !command.validate()
        command.errors.getFieldError('name').code == 'nullable'
    }
}

@Entity
class NullableTestRecord implements Serializable {
    String name
    String requiredName
    String optionalName

    static constraints = {
        requiredName nullable: false
        optionalName nullable: true
    }
}

class NullableTestCommand implements Validateable {
    String name
}
