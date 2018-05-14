package com.netflix.spinnaker.igor.config

import com.netflix.spinnaker.igor.IgorConfigurationProperties
import com.netflix.spinnaker.igor.wercker.WerckerService
import com.netflix.spinnaker.igor.service.BuildMasters
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import javax.validation.Valid

@Configuration
@Slf4j
@CompileStatic
@ConditionalOnProperty("wercker.enabled")
@EnableConfigurationProperties(WerckerProperties)
public class WerckerConfig {
    @Bean
    Map<String, WerckerService> werckerMasters(BuildMasters buildMasters,
                                               IgorConfigurationProperties igorConfigurationProperties,
                                               @Valid WerckerProperties werckerProperties) {
        log.info "creating werckerMasters"
        Map<String, WerckerService> werckerMasters = ( werckerProperties?.masters?.collectEntries { WerckerProperties.WerckerHost host ->
            log.info "bootstrapping Wercker ${host.address} as ${host.name}"
            [(host.name): new WerckerService(
                host.name)]
        })

        buildMasters.map.putAll werckerMasters
        werckerMasters
    }
}
