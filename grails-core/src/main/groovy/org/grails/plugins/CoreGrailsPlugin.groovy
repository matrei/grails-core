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
package org.grails.plugins

import groovy.transform.CompileStatic

import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.annotation.ConfigurationClassPostProcessor
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.io.Resource

import grails.plugins.Plugin
import grails.util.BuildSettings
import grails.util.GrailsUtil
import org.grails.plugins.core.CoreAutoConfiguration
import org.grails.spring.DefaultRuntimeSpringConfiguration
import org.grails.spring.RuntimeSpringConfigUtilities

/**
 * Configures the core shared beans within the Grails application context.
 *
 * @author Graeme Rocher
 * @since 0.4
 */
class CoreGrailsPlugin extends Plugin {

    def version = GrailsUtil.getGrailsVersion()
    def watchedResources = [    'file:./grails-app/conf/spring/resources.xml',
                                'file:./grails-app/conf/spring/resources.groovy',
                                'file:./grails-app/conf/application.groovy',
                                'file:./grails-app/conf/application.yml']

    @Override
    Closure doWithSpring() {
        { ->
            //grailsConfigurationClassPostProcessor(ConfigurationClassPostProcessor)
            // Core bean wiring lives in CoreAutoConfiguration and is imported via @Configuration processing.
            //coreAutoConfiguration(CoreAutoConfiguration)
            // Compatibility template for plugins defining beans that still inherit resource locator settings.
            // (e.g., bean.parent = 'abstractGrailsResourceLocator)
            abstractGrailsResourceLocator {
                if (BuildSettings.BASE_DIR != null) {
                    searchLocations = [BuildSettings.BASE_DIR.absolutePath]
                }
            }
        }
    }

    @Override
    @CompileStatic
    void onChange(Map<String, Object> event) {
        def applicationContext = (GenericApplicationContext) this.applicationContext
        if (event.source instanceof Resource) {
            def res = (Resource) event.source
            if (res.filename.endsWith('.xml')) {
                def xmlBeans = new DefaultListableBeanFactory()
                new XmlBeanDefinitionReader(xmlBeans).loadBeanDefinitions(res)
                for (def beanName : xmlBeans.beanDefinitionNames) {
                    applicationContext.registerBeanDefinition(beanName, xmlBeans.getBeanDefinition(beanName))
                }
            }
        }
        else if (event.source instanceof Class) {
            def clazz = (Class) event.source
            if (Script.isAssignableFrom(clazz)) {
                def springConfig = new DefaultRuntimeSpringConfiguration(applicationContext)
                RuntimeSpringConfigUtilities.reloadSpringResourcesConfig(springConfig, grailsApplication, clazz)
                springConfig.registerBeansWithContext(applicationContext)
            }
        }
    }

}
