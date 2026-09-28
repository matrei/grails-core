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
package grails.openapi

import groovy.json.JsonSlurper

import io.swagger.v3.oas.annotations.media.Schema as SchemaAnnotation

import grails.artefact.Artefact
import grails.rest.RestfulController
import grails.validation.Validateable

import spock.lang.Specification
import spock.lang.Unroll

/**
 * What the {@code @Schema} of a property says of it, as the written document says it.
 */
class AnnotatedPropertySpec extends Specification {

    @Unroll
    void 'a property whose @Schema makes it optional is not required by a defaulted constraint in OpenAPI #version'() {
        when: 'a Validateable declaring no constraints, so each property is constrained nullable: false by default'
        Map schema = written(version, [InventorySummaryController]) {
            '/inventory'(resources: 'inventorySummary')
        }.components.schemas['InventorySummary']

        then: 'a property its annotation makes nullable or not required is not required'
        schema.required == ['allowBooking']

        and: 'the annotation that made a property optional still says it can be null'
        nullable(version, schema.get('properties').stateAbbreviation)
        nullable(version, schema.get('properties').price)
        !nullable(version, schema.get('properties').note)

        where:
        version << ['openapi_3_0', 'openapi_3_1']
    }

    @Unroll
    void 'what a property @Schema says of whether it is required wins over its declared constraint in OpenAPI #version'() {
        when:
        Map schema = written(version, [ReservationController]) {
            '/reservations'(resources: 'reservation')
        }.components.schemas['Reservation']

        then: 'required where the annotation says so, though the constraint lets it be null, and not where it is nullable'
        schema.required == ['code']

        and: 'each can still be null where the constraint or the annotation says it can'
        nullable(version, schema.get('properties').code)
        nullable(version, schema.get('properties').guest)
        nullable(version, schema.get('properties').note)

        where:
        version << ['openapi_3_0', 'openapi_3_1']
    }

    private static Map written(String version, List<Class<?>> controllers, Closure mappings) {
        def openApi = OpenApiFixture.document(['springdoc.api-docs.version': version], controllers, [], mappings)
        (Map) new JsonSlurper().parseText(GrailsOpenApiGenerator.serialize(openApi, 'json'))
    }

    private static boolean nullable(String version, Map property) {
        version == 'openapi_3_0' ? property.nullable == true : 'null' in property.type
    }
}

@SchemaAnnotation(name = 'InventorySummary')
class InventorySummaryCommand implements Validateable {

    @SchemaAnnotation(maxLength = 2, nullable = true)
    String stateAbbreviation

    @SchemaAnnotation(type = 'number', format = 'double', nullable = true)
    BigDecimal price

    @SchemaAnnotation(requiredMode = SchemaAnnotation.RequiredMode.NOT_REQUIRED)
    String note

    Boolean allowBooking
}

@Artefact('Controller')
class InventorySummaryController extends RestfulController<InventorySummaryCommand> {
    InventorySummaryController() { super(InventorySummaryCommand) }
}

@SchemaAnnotation(name = 'Reservation')
class ReservationCommand implements Validateable {

    @SchemaAnnotation(requiredMode = SchemaAnnotation.RequiredMode.REQUIRED)
    String code

    @SchemaAnnotation(nullable = true)
    String guest

    String note

    static constraints = {
        code nullable: true
        guest nullable: false
        note nullable: true
    }
}

@Artefact('Controller')
class ReservationController extends RestfulController<ReservationCommand> {
    ReservationController() { super(ReservationCommand) }
}
