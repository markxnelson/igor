/*
 * Copyright (c) 2017, 2018, Oracle Corporation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the Apache License Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * If a copy of the Apache License Version 2.0 was not distributed with this file,
 * You can obtain one at https://www.apache.org/licenses/LICENSE-2.0.html
 */
package com.netflix.spinnaker.igor.config

import com.netflix.spinnaker.igor.IgorConfigurationProperties
import com.netflix.spinnaker.igor.config.WerckerProperties.WerckerHost
import com.netflix.spinnaker.igor.service.BuildMasters
import com.netflix.spinnaker.igor.wercker.WerckerCache
import com.netflix.spinnaker.igor.wercker.WerckerClient
import com.netflix.spinnaker.igor.wercker.WerckerService
import com.squareup.okhttp.OkHttpClient
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import retrofit.Endpoints
import retrofit.RestAdapter
import retrofit.client.OkClient

import javax.validation.Valid
import java.util.concurrent.TimeUnit

@Configuration
@Slf4j
@CompileStatic
@ConditionalOnProperty("wercker.enabled")
@EnableConfigurationProperties(WerckerProperties)
public class WerckerConfig {
    @Bean
    Map<String, WerckerService> werckerMasters(BuildMasters buildMasters, WerckerCache cache,
                                               IgorConfigurationProperties igorConfigurationProperties,
                                               @Valid WerckerProperties werckerProperties) {
        log.info "creating werckerMasters"
        Map<String, WerckerService> werckerMasters = ( werckerProperties?.masters?.collectEntries {
            WerckerProperties.WerckerHost host ->
            log.info "bootstrapping Wercker ${host.address} as ${host.name}"
            //[(host.name): new WerckerService( host.address, cache,
            //    host.name, werckerClient(host), host.getUser(), host.getToken(), host.name)]/
            [(host.name): new WerckerService(host, cache, werckerClient(host))]
        })

        buildMasters.map.putAll werckerMasters
        werckerMasters
    }

    static WerckerClient werckerClient(WerckerHost host, int timeout = 30000) {
        OkHttpClient client = new OkHttpClient()
        client.setReadTimeout(timeout, TimeUnit.MILLISECONDS)
        return new RestAdapter.Builder()
            .setEndpoint(Endpoints.newFixedEndpoint(host.address))
//            .setRequestInterceptor(requestInterceptor)
            .setClient(new OkClient(client))
            .build()
            .create(WerckerClient)
    }
}
