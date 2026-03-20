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
package org.grails.plugins.core;

import java.beans.PropertyEditor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

import grails.config.ConfigProperties;
import grails.config.Settings;
import grails.core.GrailsApplication;
import grails.util.BuildSettings;
import grails.util.Environment;
import org.grails.beans.support.PropertiesEditor;
import org.grails.core.io.DefaultResourceLocator;
import org.grails.core.io.ResourceLocator;
import org.grails.core.support.ClassEditor;
import grails.core.support.proxy.DefaultProxyHandler;
import org.grails.dev.support.DevelopmentShutdownHook;
import org.grails.spring.aop.autoproxy.GroovyAwareAspectJAwareAdvisorAutoProxyCreator;
import org.grails.spring.aop.autoproxy.GroovyAwareInfrastructureAdvisorAutoProxyCreator;
import org.grails.spring.beans.GrailsApplicationAwareBeanPostProcessor;
import org.grails.spring.beans.PluginManagerAwareBeanPostProcessor;
import org.grails.spring.context.support.GrailsPlaceholderConfigurer;
import org.grails.spring.context.support.MapBasedSmartPropertyOverrideConfigurer;

/**
 * Core beans.
 *
 * @author graemerocher
 * @since 4.0
 */
@AutoConfiguration(before = { PropertyPlaceholderAutoConfiguration.class })
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class CoreAutoConfiguration {

    private static final String INTERNAL_AUTO_PROXY_CREATOR_BEAN_NAME = "org.springframework.aop.config.internalAutoProxyCreator";
    private static final String SPRING_PROXY_TARGET_CLASS_CONFIG = "spring.aop.proxy-target-class";

    @Value("${" + Settings.SPRING_PLACEHOLDER_PREFIX + ":#{null}}")
    private String placeholderPrefix;

    @Bean
    @Primary
    public ClassLoader classLoader(GrailsApplication grailsApplication) {
        return grailsApplication.getClassLoader();
    }

    @Bean
    @Primary
    public ConfigProperties grailsConfigProperties(GrailsApplication grailsApplication) {
        return new ConfigProperties(grailsApplication.getConfig());
    }

    @Bean
    @Primary
    PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        GrailsPlaceholderConfigurer grailsPlaceholderConfigurer = new GrailsPlaceholderConfigurer();
        if (placeholderPrefix != null) {
            grailsPlaceholderConfigurer.setPlaceholderPrefix(placeholderPrefix);
        }
        return grailsPlaceholderConfigurer;
    }

    @Bean
    MapBasedSmartPropertyOverrideConfigurer grailsBeanOverrideConfigurer(GrailsApplication grailsApplication) {
        MapBasedSmartPropertyOverrideConfigurer configurer = new MapBasedSmartPropertyOverrideConfigurer();
        configurer.setGrailsApplication(grailsApplication);
        return configurer;
    }

    @Bean(name = INTERNAL_AUTO_PROXY_CREATOR_BEAN_NAME)
    AbstractAdvisorAutoProxyCreator internalAutoProxyCreator(GrailsApplication grailsApplication) {
        ClassLoader classLoader = grailsApplication.getClassLoader();
        boolean hasAspectJ = ClassUtils.isPresent("org.aspectj.lang.annotation.Around", classLoader);
        boolean disableAspectJ = grailsApplication.getConfig().getProperty(Settings.SPRING_DISABLE_ASPECTJ, Boolean.class, false);

        AbstractAdvisorAutoProxyCreator proxyCreator = hasAspectJ && !disableAspectJ
            ? new GroovyAwareAspectJAwareAdvisorAutoProxyCreator()
            : new GroovyAwareInfrastructureAdvisorAutoProxyCreator();

        Boolean proxyTargetClass = grailsApplication.getConfig().getProperty(SPRING_PROXY_TARGET_CLASS_CONFIG, Boolean.class);
        if (proxyTargetClass != null) {
            proxyCreator.setProxyTargetClass(proxyTargetClass);
        }
        return proxyCreator;
    }

    @Bean
    BeanFactoryPostProcessor internalAutoProxyCreatorBeanClassConfigurer(GrailsApplication grailsApplication) {
        return (ConfigurableListableBeanFactory beanFactory) -> {
            if (!beanFactory.containsBeanDefinition(INTERNAL_AUTO_PROXY_CREATOR_BEAN_NAME)) {
                return;
            }
            BeanDefinition beanDefinition = beanFactory.getBeanDefinition(INTERNAL_AUTO_PROXY_CREATOR_BEAN_NAME);
            if (beanDefinition.getBeanClassName() == null) {
                beanDefinition.setBeanClassName(resolveAutoProxyCreatorClass(grailsApplication).getName());
            }
        };
    }

    private Class<? extends AbstractAdvisorAutoProxyCreator> resolveAutoProxyCreatorClass(GrailsApplication grailsApplication) {
        ClassLoader classLoader = grailsApplication.getClassLoader();
        boolean hasAspectJ = ClassUtils.isPresent("org.aspectj.lang.annotation.Around", classLoader);
        boolean disableAspectJ = grailsApplication.getConfig().getProperty(Settings.SPRING_DISABLE_ASPECTJ, Boolean.class, false);
        return hasAspectJ && !disableAspectJ
            ? GroovyAwareAspectJAwareAdvisorAutoProxyCreator.class
            : GroovyAwareInfrastructureAdvisorAutoProxyCreator.class;
    }

    @Bean
    BeanFactoryPostProcessor grailsBeanPackagesComponentScan(GrailsApplication grailsApplication) {
        return beanFactory -> {
            List<?> configuredPackages = grailsApplication.getConfig().getProperty(Settings.SPRING_BEAN_PACKAGES, List.class, Collections.emptyList());
            if (configuredPackages.isEmpty()) {
                return;
            }
            if (!(beanFactory instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry)) {
                return;
            }
            org.springframework.context.annotation.ClassPathBeanDefinitionScanner scanner =
                new org.springframework.context.annotation.ClassPathBeanDefinitionScanner(registry, true);
            List<String> packagesToScan = new ArrayList<>();
            for (Object pkg : configuredPackages) {
                if (pkg != null) {
                    packagesToScan.add(pkg.toString());
                }
            }
            if (!packagesToScan.isEmpty()) {
                scanner.scan(packagesToScan.toArray(new String[0]));
            }
        };
    }

    @Bean
    GrailsApplicationAwareBeanPostProcessor grailsApplicationAwarePostProcessor(GrailsApplication grailsApplication) {
        return new GrailsApplicationAwareBeanPostProcessor(grailsApplication);
    }

    @Bean
    PluginManagerAwareBeanPostProcessor pluginManagerPostProcessor() {
        return new PluginManagerAwareBeanPostProcessor();
    }

    @Bean
    @Conditional(DevelopmentModeWithJlineCondition.class)
    DevelopmentShutdownHook shutdownHook() {
        return new DevelopmentShutdownHook();
    }

    @Bean
    org.springframework.beans.factory.config.CustomEditorConfigurer customEditors() {
        org.springframework.beans.factory.config.CustomEditorConfigurer configurer = new org.springframework.beans.factory.config.CustomEditorConfigurer();
        Map<Class<?>, Class<? extends PropertyEditor>> editors = new LinkedHashMap<>();
        editors.put(Class.class, ClassEditor.class);
        editors.put(java.util.Properties.class, PropertiesEditor.class);
        configurer.setCustomEditors(editors);
        return configurer;
    }

    @Bean
    DefaultProxyHandler proxyHandler() {
        return new DefaultProxyHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "grailsResourceLocator")
    ResourceLocator grailsResourceLocator() {
        DefaultResourceLocator locator = new DefaultResourceLocator();
        if (BuildSettings.BASE_DIR != null) {
            locator.setSearchLocations(List.of(BuildSettings.BASE_DIR.getAbsolutePath()));
        }
        return locator;
    }

    static class DevelopmentModeWithJlineCondition implements Condition {
        @Override
        public boolean matches(@NonNull ConditionContext context, @NonNull AnnotatedTypeMetadata metadata) {
            if (Environment.isWarDeployed() || Environment.getCurrent() != Environment.DEVELOPMENT) {
                return false;
            }
            ClassLoader classLoader = context.getClassLoader();
            return classLoader != null && ClassUtils.isPresent("jline.Terminal", classLoader);
        }
    }
}
