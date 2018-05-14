/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.igor.wercker

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.igor.IgorConfigurationProperties
import com.netflix.spinnaker.igor.config.WerckerProperties
import com.netflix.spinnaker.igor.history.EchoService
import com.netflix.spinnaker.igor.history.model.Event
//import com.netflix.spinnaker.igor.jenkins.client.model.Build
//import com.netflix.spinnaker.igor.jenkins.client.model.BuildsList
//import com.netflix.spinnaker.igor.jenkins.client.model.Project
//import com.netflix.spinnaker.igor.jenkins.client.model.ProjectsList
//import com.netflix.spinnaker.igor.jenkins.service.JenkinsService
import com.netflix.spinnaker.igor.polling.PollContext
import com.netflix.spinnaker.igor.service.BuildMasters
import com.netflix.spinnaker.igor.service.Front50Service
import org.slf4j.Logger
import retrofit.RetrofitError
import rx.schedulers.Schedulers
import spock.lang.Specification
/**
 * Tests for JenkinsBuildMonitor
 */
@SuppressWarnings(['DuplicateNumberLiteral', 'PropertyName'])
class WerckerBuildMonitorSpec extends Specification {

    WerckerCache cache = Mock(WerckerCache)
    WerckerService werckerService = Mock(WerckerService)
    EchoService echoService = Mock()
    Front50Service front50Service = Mock()
    IgorConfigurationProperties igorConfigurationProperties = new IgorConfigurationProperties()
    WerckerBuildMonitor monitor

    final MASTER = 'MASTER'

    void setup() {
        monitor = new WerckerBuildMonitor(
            igorConfigurationProperties,
            new NoopRegistry(),
            Optional.empty(),
            cache,
            new BuildMasters(map: [MASTER: jenkinsService]),
            true,
            Optional.of(echoService),
            Optional.of(front50Service),
            new WerckerProperties()
        )

        monitor.worker = Schedulers.immediate().createWorker()
    }
}
