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

import com.netflix.discovery.DiscoveryClient
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.igor.IgorConfigurationProperties
import com.netflix.spinnaker.igor.config.WerckerProperties
import com.netflix.spinnaker.igor.history.EchoService

//TODO
//import com.netflix.spinnaker.igor.history.model.BuildContent
//import com.netflix.spinnaker.igor.history.model.BuildEvent
import com.netflix.spinnaker.igor.history.model.GenericBuildContent
import com.netflix.spinnaker.igor.history.model.GenericBuildEvent
import com.netflix.spinnaker.igor.build.model.GenericBuild
import com.netflix.spinnaker.igor.build.model.GenericProject
import com.netflix.spinnaker.igor.build.model.Result
//import com.netflix.spinnaker.igor.jenkins.client.model.Build
//import com.netflix.spinnaker.igor.jenkins.client.model.BuildArtifact
//import com.netflix.spinnaker.igor.jenkins.client.model.Project

import com.netflix.spinnaker.igor.model.BuildServiceProvider
import com.netflix.spinnaker.igor.polling.CommonPollingMonitor
import com.netflix.spinnaker.igor.polling.DeltaItem
import com.netflix.spinnaker.igor.polling.PollContext
import com.netflix.spinnaker.igor.polling.PollingDelta
import com.netflix.spinnaker.igor.service.BuildMasters
import com.netflix.spinnaker.igor.wercker.model.Run

import groovy.time.TimeCategory

import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import retrofit.RetrofitError

import javax.annotation.PreDestroy
import java.text.SimpleDateFormat
import java.util.List
import java.util.stream.Collectors

import static net.logstash.logback.argument.StructuredArguments.kv
/**
 * Monitors new wercker builds
 */
@Service
@SuppressWarnings('CatchException')
@ConditionalOnProperty('wercker.enabled')
class WerckerBuildMonitor extends CommonPollingMonitor<PipelineDelta, PipelinePollingDelta> {

    private final WerckerCache cache
    private final BuildMasters buildMasters
    private final boolean pollingEnabled
    private final Optional<EchoService> echoService
    private final WerckerProperties werckerProperties

    @Autowired
    WerckerBuildMonitor(IgorConfigurationProperties properties,
                        Registry registry,
                        Optional<DiscoveryClient> discoveryClient,
                        WerckerCache cache,
                        BuildMasters buildMasters,
                        @Value('${wercker.polling.enabled:true}') boolean pollingEnabled,
                        Optional<EchoService> echoService,
                        WerckerProperties werckerProperties) {
        super(properties, registry, discoveryClient)
        this.cache = cache
        this.buildMasters = buildMasters
        this.pollingEnabled = pollingEnabled
        this.echoService = echoService
        this.werckerProperties = werckerProperties
    }

    @Override
    String getName() {
        "werckerBuildMonitor"
    }

    @Override
    boolean isInService() {
        pollingEnabled && super.isInService()
    }

    @Override
    void initialize() {
    }

    @Override
    void poll(boolean sendEvents) {
        long startTime = System.currentTimeMillis()
        log.info "WerckerBuildMonitor Polling cycle started: ${new Date()}"
        buildMasters.filteredMap(BuildServiceProvider.WERCKER).keySet().parallelStream().forEach(
            { master -> pollSingle(new PollContext(master, !sendEvents)) }
        )
        log.info "WerckerBuildMonitor Polling cycle done in ${System.currentTimeMillis() - startTime}ms"
    }

    @PreDestroy
    void stop() {
        log.info('Stopped')
        if (!worker.isUnsubscribed()) {
            worker.unsubscribe()
        }
    }

    /**
     * Gets a list of pipelines for this master & processes runs between last poll stamp and a sliding upper bound stamp,
     * the cursor will be used to advanced to the upper bound when all builds are completed in the commit phase.
     */
    @Override
    protected PipelinePollingDelta generateDelta(PollContext ctx) {
        String master = ctx.partitionName
        log.info("Checking for new builds for $master")
        def startTime = System.currentTimeMillis()

        List<PipelineDelta> delta = []

        WerckerService werckerService = buildMasters.map[master] as WerckerService
		List<String> pipelines = werckerService.getApplicationAndPipelineNames()
		pipelines.forEach( { pipeline -> processRuns(werckerService, master, pipeline, delta)})
        log.debug("Took ${System.currentTimeMillis() - startTime}ms to retrieve Wercker pipelines (master: {})", kv("master", master))
        return new PipelinePollingDelta(master: master, items: delta)
    }
	
	Run getLastStartedAt(List<Run> runs) {
		Run last = runs.get(0);
		for( Run run : runs ) {
			if (run.startedAt != null) {
				if (last.startedAt == null) {
					last = run;
				} else {
			        last = (run.startedAt.fastTime >= last.startedAt.fastTime)? run : last;
				}
			}
		}
		return last;
	}
	
	/**
	 * wercker.pipeline = project|job 
	 * wercker.run = build
	 */
    private void processRuns(WerckerService werckerService, String master, String pipeline, List<PipelineDelta> delta) {
		List<Run> allRuns = werckerService.getBuilds(pipeline)
        log.info "Wercker Polling pipeline: ${pipeline}"
        if (allRuns.empty) {
            log.debug("[{}:{}] has no runs skipping...", kv("master", master), kv("pipeline", pipeline))
            return
        }
		Run lastStartedAt = getLastStartedAt(allRuns)
        try {
            Long cursor = cache.getLastPollCycleTimestamp(master, pipeline)
			//The last build/run 
            Long lastBuildStamp = lastStartedAt.startedAt.fastTime //job.lastBuild.timestamp as Long
            Date upperBound     = lastStartedAt.startedAt //new Date(lastBuildStamp)
            if (cursor == lastBuildStamp) {
                log.debug("[${master}:${pipeline}] is up to date. skipping")
                return
            }
			cache.updateBuildNumbers(master, pipeline, allRuns)
			List<Run> allBuilds = allRuns.findAll { it.startedAt.fastTime > cursor  }
            log.info "Wercker Polling pipeline: ${pipeline} recent runs ${allBuilds}"

            if (!cursor && !igorProperties.spinnaker.build.handleFirstBuilds) {
                cache.setLastPollCycleTimestamp(master, pipeline, lastBuildStamp)
                return
            }
            List<Run> currentlyBuilding = allBuilds.findAll { it.finishedAt == null }
            List<Run> completedBuilds = allBuilds.findAll { it.finishedAt != null }
			
            cursor = !cursor ? lastBuildStamp : cursor
            Date lowerBound = new Date(cursor)

            if (!igorProperties.spinnaker.build.processBuildsOlderThanLookBackWindow) {
                completedBuilds = onlyInLookBackWindow(completedBuilds)
            }
			//Should we use the newRunIDs to calculate the Delta
            delta.add(new PipelineDelta(
                cursor: cursor,
                name: pipeline,
                lastBuildStamp: lastBuildStamp,
                upperBound: upperBound,
                lowerBound: lowerBound,
                completedBuilds: completedBuilds,
                runningBuilds: currentlyBuilding
            ))
        } catch (e) {
            log.error("Error processing runs for [{}:{}]", kv("master", master), kv("pipeline", pipeline), e)
            if (e.cause instanceof RetrofitError) {
                def re = (RetrofitError) e.cause
                log.error("Error communicating with jenkins for [{}:{}]: {}", kv("master", master), kv("pipeline", pipeline), kv("url", re.url), re);
            }
        }
    }
	
    private List<Run> onlyInLookBackWindow(List<Run> builds) {
        use(TimeCategory) {
            def offsetSeconds = pollInterval.seconds
            def lookBackWindowMins = igorProperties.spinnaker.build.lookBackWindowMins.minutes
            Date lookBackDate = (offsetSeconds + lookBackWindowMins).ago
            return builds.stream().filter({
//              Date buildEndDate = new Date((it.timestamp as Long) + it.duration)
                Date buildEndDate = it.finishedAt
                return buildEndDate.after(lookBackDate)
            }).collect(Collectors.toList())
        }
    }
	
	private GenericBuild toBuild(String master, String pipeline, Run run) {
		Result res = (run.finishedAt == null) ? Result.BUILDING : (run.result.equals("passed")? Result.SUCCESS : Result.FAILURE)
		return new GenericBuild ( 
					building: (run.finishedAt == null),
					result: res,
					number: cache.getBuildNumber(master, pipeline, run.id),
					timestamp: run.startedAt.fastTime as String,
					id: run.id,
					url: run.url
				);
	}
	
    @Override
    protected void commitDelta(PipelinePollingDelta delta, boolean sendEvents) {
        String master = delta.master
        delta.items.parallelStream().forEach { pipeline -> //job = pipeline
            // post events for finished builds
            pipeline.completedBuilds.forEach { run -> //build = run
                Boolean eventPosted = cache.getEventPosted(master, pipeline.name, run.id)	
				GenericBuild build = toBuild(master, pipeline.name, run)
//				Build build = new Build ( 
//					building: (run.finishedAt == null),
//					result: run.result,
//					number: cache.getBuildNumber(master, pipeline.name, run.id),
//					timestamp: run.startedAt.fastTime as String,
//					id: run.id,
//					url: run.url
//				)
                if (!eventPosted) {
                    log.info("[${master}:${pipeline.name}]:${build.id} event posted")
                    cache.setEventPosted(master, pipeline.name, run.id)
                    if (sendEvents) {
                        postEvent(new GenericProject(pipeline.name, build), master)
                    }
                }
            }

            // advance cursor when all builds have completed in the interval
            if (pipeline.runningBuilds.isEmpty()) {
                log.info("[{}:{}] has no other builds between [${pipeline.lowerBound} - ${pipeline.upperBound}], advancing cursor to ${pipeline.lastBuildStamp}", kv("master", master), kv("pipeline", pipeline.name))
                cache.pruneOldMarkers(master, pipeline.name, pipeline.cursor)
                cache.setLastPollCycleTimestamp(master, pipeline.name, pipeline.lastBuildStamp)
            }
        }
    }

    @Override
    protected Integer getPartitionUpperThreshold(String partition) {
        return werckerProperties.masters.find { partition == it.name }?.itemUpperThreshold
    }

    private void postEvent(GenericProject project, String master) {
        if (!echoService.isPresent()) {
            log.warn("Cannot send build notification: Echo is not configured")
            registry.counter(missedNotificationId.withTag("monitor", getClass().simpleName)).increment()
            return
        }
        echoService.get().postEvent(new GenericBuildEvent(content: new GenericBuildContent(project: project, master: master, type: "wercker")))
    }

    private static class PipelinePollingDelta implements PollingDelta<PipelineDelta> {
        String master
        List<PipelineDelta> items
    }

    private static class PipelineDelta implements DeltaItem {
        Long cursor
        String name
        Long lastBuildStamp
        Date lowerBound
        Date upperBound
        List<Run> completedBuilds
        List<Run> runningBuilds
    }
}
