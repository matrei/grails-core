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
package org.grails.web.errors

import grails.config.Config
import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.web.mapping.UrlMappingsHolder
import grails.web.mapping.exceptions.UrlMappingException
import org.grails.web.mapping.DefaultUrlMappingEvaluator
import org.grails.web.mapping.DefaultUrlMappingsHolder
import org.grails.web.mapping.mvc.GrailsControllerUrlMappings
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.servlet.view.CompositeViewResolver
import org.grails.web.util.WebUtils as GrailsWebUtils
import org.springframework.context.support.StaticApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.context.support.StaticWebApplicationContext
import org.springframework.web.servlet.ViewResolver
import org.springframework.web.servlet.view.InternalResourceView
import spock.lang.Issue
import spock.lang.Specification

import jakarta.servlet.http.HttpServletRequest

class GrailsExceptionResolverSpec extends Specification {

    private static final String GRAILS = 'a GrailsWebRequest'
    private static final String PLAIN_OVER_GRAILS = 'plain attributes over a stored GrailsWebRequest'
    private static final String PLAIN = 'plain attributes'
    private static final String NOTHING = 'nothing'

    private final MockServletContext servletContext = new MockServletContext()
    private StaticWebApplicationContext webContext

    def cleanup() {
        RequestContextHolder.resetRequestAttributes()
        webContext?.close()
    }

    def "exception not thrown if an UrlMappingException is thrown while trying to match a request uri with a UrlMappingInfo "() {
        given:
        GrailsExceptionResolver grailsExceptionResolver = new GrailsExceptionResolver()

        when:
        def urlMappingsHolder = Mock(UrlMappingsHolder)
        urlMappingsHolder.match(_ as String) >> { String uri ->
            throw new UrlMappingException('Unable to establish controller name to dispatch for')
        }
        HttpServletRequest request = new MockHttpServletRequest()
        Map params = grailsExceptionResolver.extractRequestParamsWithUrlMappingHolder(urlMappingsHolder, request)

        then:
        noExceptionThrown()
        params.isEmpty()
    }
    void "getRequestLogMessage masks excluded request parameters case-insensitively"() {
        given:
            def config = Mock(Config)
            config.getProperty('grails.exceptionresolver.logRequestParameters', Boolean, _) >> true
            config.getProperty('grails.exceptionresolver.params.exclude', List, _) >> [null, 'password', 'token']
            config.getProperty('grails.exceptionresolver.logAuditor', Boolean, false) >> false
            config.getProperty('grails.exceptionresolver.logRemoteAddr', Boolean, false) >> false
            config.getProperty('grails.exceptionresolver.logFullStackTraceOnFilter', Boolean, true) >> false
            config.getProperty('grails.exceptionresolver.logFullStackTrace', Boolean, false) >> false
            def grailsApp = Mock(GrailsApplication)
            grailsApp.getConfig() >> config
            def resolver = new GrailsExceptionResolver()
            resolver.grailsApplication = grailsApp
            def request = new MockHttpServletRequest('POST', '/login')
            request.addParameter('Password', 'secret')
            request.addParameter('apiToken', 'visible')
            request.addParameter('TOKEN', 'abc123')
            request.addParameter('username', 'sherlock')

        when:
            def msg = resolver.getRequestLogMessage('RuntimeException', request, 'boom')

        then:
            msg.contains('Password: ***')
            msg.contains('TOKEN: ***')
            msg.contains('username: sherlock')
            msg.contains('apiToken: visible')
            !msg.contains('Password: secret')
            !msg.contains('TOKEN: abc123')
    }

    @Issue('https://github.com/apache/grails-core/issues/16129')
    void "an error handler mapped to #handler is forwarded to with #attributes bound"() {
        given:
        def resolver = resolverFor(mappings, true)
        def request = new MockHttpServletRequest(servletContext, 'GET', '/fail')
        def response = new MockHttpServletResponse()
        bind(attributes, request, response)
        def original = new IllegalStateException('original')

        when:
        def result = resolver.resolveException(request, response, null, original)

        then: 'the error handler is dispatched with the exception being resolved'
        result.empty
        response.forwardedUrl == '/errors/serverError'
        (request.getAttribute(GrailsExceptionResolver.EXCEPTION_ATTRIBUTE) as Throwable).cause.is(original)

        where:
        handler                | attributes        | mappings
        'a controller'         | GRAILS            | { '500'(controller: 'errors', action: 'serverError') }
        'a controller'         | PLAIN_OVER_GRAILS | { '500'(controller: 'errors', action: 'serverError') }
        'a controller closure' | GRAILS            | { '500'(controller: { 'errors' }, action: 'serverError') }
        'a controller closure' | PLAIN_OVER_GRAILS | { '500'(controller: { 'errors' }, action: 'serverError') }
        'an HTTP method map'   | PLAIN_OVER_GRAILS | { '500'(controller: 'errors', action: [GET: 'serverError']) }
        'a controller'         | GRAILS            | { "/$controller/$action?"(); '500'(controller: 'errors', action: 'serverError') }
        'a controller'         | PLAIN_OVER_GRAILS | { "/$controller/$action?"(); '500'(controller: 'errors', action: 'serverError') }
    }

    @Issue('https://github.com/apache/grails-core/issues/16129')
    void "the default error view renders the exception when #handler cannot be forwarded to with #attributes bound"() {
        given:
        def resolver = resolverFor(mappings, controllerMappings)
        def request = new MockHttpServletRequest(servletContext, 'GET', '/fail')
        def response = new MockHttpServletResponse()
        bind(attributes, request, response)
        def original = new IllegalStateException('original')

        when:
        def result = resolver.resolveException(request, response, null, original)

        then: 'the exception being resolved is not replaced by the failure to reach its error handler'
        result.viewName == '/error'
        response.forwardedUrl == null
        (result.model[GrailsExceptionResolver.EXCEPTION_ATTRIBUTE] as Throwable).cause.is(original)

        where:
        handler                           | attributes | controllerMappings | mappings
        'a controller'                    | PLAIN      | true               | { '500'(controller: 'errors', action: 'serverError') }
        'a controller'                    | NOTHING    | true               | { '500'(controller: 'errors', action: 'serverError') }
        'a controller closure'            | PLAIN      | true               | { '500'(controller: { 'errors' }, action: 'serverError') }
        'a controller closure'            | PLAIN      | false              | { '500'(controller: { 'errors' }, action: 'serverError') }
        'a controller closure'            | NOTHING    | true               | { '500'(controller: { 'errors' }, action: 'serverError') }
        'an HTTP method map'              | PLAIN      | true               | { '500'(controller: 'errors', action: [GET: 'serverError']) }
        'a controller closure that fails' | GRAILS     | true               | { '500'(controller: { throw new IllegalStateException('broken mapping') }) }
    }

    @Issue('https://github.com/apache/grails-core/issues/16129')
    void "an error handler mapped to a view renders with #attributes bound"() {
        given:
        def resolver = resolverFor({ '500'(view: '/serverError') }, true)
        def request = new MockHttpServletRequest(servletContext, 'GET', '/fail')
        def response = new MockHttpServletResponse()
        bind(attributes, request, response)
        def original = new IllegalStateException('original')

        when:
        def result = resolver.resolveException(request, response, null, original)

        then:
        (result.view as InternalResourceView).url == '/serverError'
        (result.model[GrailsExceptionResolver.EXCEPTION_ATTRIBUTE] as Throwable).cause.is(original)

        where:
        attributes << [GRAILS, PLAIN_OVER_GRAILS, PLAIN, NOTHING]
    }

    private GrailsExceptionResolver resolverFor(Closure mappings, boolean controllerMappings) {
        def grailsApplication = new DefaultGrailsApplication().tap {
            initialise()
        }
        def evaluatorContext = new StaticApplicationContext()
        evaluatorContext.beanFactory.registerSingleton(GrailsApplication.APPLICATION_ID, grailsApplication)
        evaluatorContext.refresh()
        UrlMappingsHolder urlMappings = new DefaultUrlMappingsHolder(
                new DefaultUrlMappingEvaluator(evaluatorContext).evaluateMappings(mappings))
        if (controllerMappings) {
            urlMappings = new GrailsControllerUrlMappings(grailsApplication, urlMappings)
        }
        ViewResolver viewResolver = { String name, Locale locale -> new InternalResourceView(name) } as ViewResolver

        webContext = new StaticWebApplicationContext()
        webContext.beanFactory.registerSingleton(GrailsApplication.APPLICATION_ID, grailsApplication)
        webContext.beanFactory.registerSingleton(UrlMappingsHolder.BEAN_ID, urlMappings)
        webContext.beanFactory.registerSingleton(CompositeViewResolver.BEAN_NAME,
                new CompositeViewResolver(viewResolvers: [viewResolver]))
        webContext.refresh()
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, webContext)

        def resolver = new GrailsExceptionResolver()
        resolver.servletContext = servletContext
        resolver.grailsApplication = grailsApplication
        resolver.exceptionMappings = ['java.lang.Exception': '/error'] as Properties
        resolver
    }

    private void bind(String attributes, MockHttpServletRequest request, MockHttpServletResponse response) {
        switch (attributes) {
            case GRAILS:
                GrailsWebUtils.storeGrailsWebRequest(new GrailsWebRequest(request, response, servletContext))
                break
            case PLAIN_OVER_GRAILS:
                GrailsWebUtils.storeGrailsWebRequest(new GrailsWebRequest(request, response, servletContext))
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response))
                break
            case PLAIN:
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response))
                break
            default:
                RequestContextHolder.resetRequestAttributes()
        }
    }
}
