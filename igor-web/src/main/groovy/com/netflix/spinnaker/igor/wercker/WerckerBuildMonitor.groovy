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
import com.netflix.spinnaker.igor.history.model.BuildContent
import com.netflix.spinnaker.igor.history.model.BuildEvent
import com.netflix.spinnaker.igor.jenkins.client.model.Build
import com.netflix.spinnaker.igor.jenkins.client.model.BuildArtifact
import com.netflix.spinnaker.igor.jenkins.client.model.Project

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
class WerckerBuildMonitor extends CommonPollingMonitor<JobDelta, JobPollingDelta> {

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
     * Gets a list of jobs for this master & processes builds between last poll stamp and a sliding upper bound stamp,
     * the cursor will be used to advanced to the upper bound when all builds are completed in the commit phase.
     */
    @Override
    protected JobPollingDelta generateDelta(PollContext ctx) {
        String master = ctx.partitionName
        log.info("Checking for new builds for $master")
        def startTime = System.currentTimeMillis()

        List<JobDelta> delta = []

        WerckerService werckerService = buildMasters.map[master] as WerckerService
		List<String> pipelines = werckerService.getApplicationAndPipelineNames()
//        List<Project> jobs = jenkinsService.getProjects()?.getList() ?:[]
//        pipelines.forEach( { pipeline -> processBuildsOfProject(jenkinsService, master, job, delta)})
		pipelines.forEach( { pipeline -> processBuildsOfProject(werckerService, master, pipeline, delta)})
//        log.debug("Took ${System.currentTimeMillis() - startTime}ms to retrieve projects (master: {})", kv("master", master))
        return new JobPollingDelta(master: master, items: delta)
    }
	
//	private void processBuildsOfProject(WerckerService werckerService, String master, String pipeline, List<JobDelta> delta) {
//        log.info "Wercker Polling pipeline: ${pipeline}"
//		List<Run> runs = werckerService.getBuilds(pipeline) 
//		Run lastStartedAt = getLastStartedAt(runs)
//		log.info "Wercker Polling ~~~~~~~~ pipeline: ${pipeline} lastStartedAt ${lastStartedAt.startedAt}"
//		runs.each { run ->
//			
//			long longI = 0L
//			long longD = 0L
////			if (run != null && run.startedAt) {
//				//2018-05-21T21:47:05.099Z
////				SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
////				Date date = format.parse(run.startedAt)
////				
////				 longI = java.time.Instant.parse(run.startedAt).toEpochMilli()
//				 String start = run.startedAt.getDateTimeString()
//				 longI = run.startedAt.fastTime
//				 longD = run.startedAt.toInstant().toEpochMilli()
////			}
//			
////			run.finishedAt
////			java.time.Instant inst = run.startedAt.toInstant()
////			java.time.Instant.parse(run.startedAt)
//				 log.info "         run createdAt: ${run.createdAt}"
//				 log.info "             startedAt: ${run.startedAt}"
//				 log.info "            finishedAt: ${run.finishedAt}"
//		}
//		
//	}
	
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
	
    private void processBuildsOfProject(WerckerService werckerService, String master, String pipeline, List<JobDelta> delta) {
		List<Run> allBuilds = werckerService.getBuilds(pipeline)
        log.info "Wercker Polling pipeline: ${pipeline}"
        if (allBuilds.empty) {
            log.debug("[{}:{}] has no builds skipping...", kv("master", master), kv("pipeline", pipeline))
            return
        }
		Run lastStartedAt = getLastStartedAt(allBuilds)
        try {
            Long cursor = cache.getLastPollCycleTimestamp(master, pipeline)
            Long lastBuildStamp = lastStartedAt.startedAt.fastTime //job.lastBuild.timestamp as Long
            Date upperBound     = lastStartedAt.startedAt //new Date(lastBuildStamp)
			
			log.info "Wercker Polling ~~~~~~~~ pipeline: ${pipeline} lastStartedAt ${lastStartedAt.startedAt}"
			log.info "Wercker Polling ~~~~~~~~         cursor: ${cursor} "
			log.info "Wercker Polling ~~~~~~~~ lastBuildStamp: ${lastBuildStamp}"
//			allBuilds.each { run ->
//				 log.info "         run createdAt: ${run.createdAt}"
//				 log.info "             startedAt: ${run.startedAt}"
//				 log.info "            finishedAt: ${run.finishedAt}"
//			}
			List<String> newRunIDs = cache.updateBuildNumbers(master, pipeline, allBuilds)
            if (cursor == lastBuildStamp) {
                log.debug("[${master}:${pipeline}] is up to date. skipping")
                return
            }

            if (!cursor && !igorProperties.spinnaker.build.handleFirstBuilds) {
                cache.setLastPollCycleTimestamp(master, pipeline, lastBuildStamp)
                return
            }
//            List<Build> allBuilds = getBuilds(jenkinsService, master, job, cursor, lastBuildStamp)
            List<Run> currentlyBuilding = allBuilds.findAll { it.finishedAt == null }
            List<Run> completedBuilds = allBuilds.findAll { it.finishedAt != null }
//			
//			log.info "                ~~~~~~~~ currentlyBuilding ${currentlyBuilding.size()}"
//			currentlyBuilding.each { run ->
//				 log.info "         run createdAt: ${run.createdAt}"
//				 log.info "             startedAt: ${run.startedAt}"
//				 log.info "            finishedAt: ${run.finishedAt}"
//			}
//			log.info "               ~~~~~~~~ completedBuilds ${completedBuilds.size()}"
//			completedBuilds.each { run ->
//				 log.info "         run createdAt: ${run.createdAt}"
//				 log.info "             startedAt: ${run.startedAt}"
//				 log.info "            finishedAt: ${run.finishedAt}"
//			}
			
            cursor = !cursor ? lastBuildStamp : cursor
            Date lowerBound = new Date(cursor)

            if (!igorProperties.spinnaker.build.processBuildsOlderThanLookBackWindow) {
                completedBuilds = onlyInLookBackWindow(completedBuilds)
            }
			
			log.info " !! echoService ${echoService}"
			
            delta.add(new JobDelta(
                cursor: cursor,
                name: pipeline,
                lastBuildStamp: lastBuildStamp,
                upperBound: upperBound,
                lowerBound: lowerBound,
                completedBuilds: completedBuilds,
                runningBuilds: currentlyBuilding,
				newStartedRuns: newRunIDs
            ))

        } catch (e) {
            log.error("Error processing builds for [{}:{}]", kv("master", master), kv("pipeline", pipeline), e)
            if (e.cause instanceof RetrofitError) {
                def re = (RetrofitError) e.cause
                log.error("Error communicating with jenkins for [{}:{}]: {}", kv("master", master), kv("job", pipeline), kv("url", re.url), re);
            }
        }
    }

//    private List<Build> getBuilds(JenkinsService jenkinsService, String master, Project job, Long cursor, Long lastBuildStamp) {
//        if (!cursor) {
//            log.debug("[${master}:${job.name}] setting new cursor to ${lastBuildStamp}")
//            return jenkinsService.getBuilds(job.name).getList() ?: []
//        }
//
//        // filter between last poll and jenkins last build included
//        return (jenkinsService.getBuilds(job.name).getList() ?: []).findAll { build ->
//            Long buildStamp = build.timestamp as Long
//            return buildStamp <= lastBuildStamp && buildStamp > cursor
//        }
//    }
	
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
	
	//	@Element(required = false)
	//	String result
	//	String timestamp
	//	@Element(required = false)
	//	Long duration
	//	@Element(required = false)
	//	Integer estimatedDuration
	//	@Element(required = false)
	//	String id
	//	String url
	//	@Element(required = false)
	//	String builtOn
	//	@Element(required = false)
	//	String fullDisplayName
	//
	//	@ElementList(required = false, name = "artifact", inline = true)
	//	List<BuildArtifact> artifacts
	//
	//	Build toJenkinsBuild(Run run) {
	//		return new Build(
	//			building: run
	//			number:
	//		);
	//	}
	
	
    @Override
    protected void commitDelta(JobPollingDelta delta, boolean sendEvents) {
        String master = delta.master
		
		log.info "!!!!!! commitDelta  echoService ${echoService}"
		
        delta.items.parallelStream().forEach { job -> //job = pipeline
            // post events for finished builds
            job.completedBuilds.forEach { run -> //build = run
                Boolean eventPosted = cache.getEventPosted(master, job.name, job.cursor, run.id)
				
				Build build = new Build( 
					building: (run.finishedAt == null),
					number: cache.getBuildNumber(master, job.name, run.id),
					timestamp: run.startedAt.fastTime as String,
					id: run.id,
					url: run.url
				)
                if (!eventPosted) {
                    log.debug("[${master}:${job.name}]:${build.id} event posted")
                    cache.setEventPosted(master, job.name, job.cursor, run.id)
                    if (sendEvents) {
                        postEvent(new Project(name: job.name, lastBuild: build), master)
                    }
                }
            }

            // advance cursor when all builds have completed in the interval
            if (job.runningBuilds.isEmpty()) {
                log.info("[{}:{}] has no other builds between [${job.lowerBound} - ${job.upperBound}], advancing cursor to ${job.lastBuildStamp}", kv("master", master), kv("job", job.name))
                cache.pruneOldMarkers(master, job.name, job.cursor)
                cache.setLastPollCycleTimestamp(master, job.name, job.lastBuildStamp)
            }
        }
    }

    @Override
    protected Integer getPartitionUpperThreshold(String partition) {
        return werckerProperties.masters.find { partition == it.name }?.itemUpperThreshold
    }

    private void postEvent(Project project, String master) {
        if (!echoService.isPresent()) {
            log.warn("Cannot send build notification: Echo is not configured")
            registry.counter(missedNotificationId.withTag("monitor", getClass().simpleName)).increment()
            return
        }
        echoService.get().postEvent(new BuildEvent(content: new BuildContent(project: project, master: master)))
    }

    private static class JobPollingDelta implements PollingDelta<JobDelta> {
        String master
        List<JobDelta> items
    }

    private static class JobDelta implements DeltaItem {
        Long cursor
        String name
        Long lastBuildStamp
        Date lowerBound
        Date upperBound
        List<Run> completedBuilds
        List<Run> runningBuilds
		List<Run> newStartedRuns
    }
}
