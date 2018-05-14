/*
 * Copyright (c) 2017, 2018, Oracle Corporation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the Apache License Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * If a copy of the Apache License Version 2.0 was not distributed with this file,
 * You can obtain one at https://www.apache.org/licenses/LICENSE-2.0.html
 */
package com.netflix.spinnaker.igor.wercker

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.igor.IgorConfigurationProperties
import com.netflix.spinnaker.igor.config.WerckerProperties
import com.netflix.spinnaker.igor.history.EchoService
import com.netflix.spinnaker.igor.wercker.WerckerService
import com.netflix.spinnaker.igor.wercker.model.Run
import com.netflix.spinnaker.igor.model.BuildServiceProvider
import com.netflix.spinnaker.igor.service.BuildMasters
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.igor.history.model.Event
import com.netflix.spinnaker.igor.service.Front50Service

import rx.schedulers.TestScheduler
import spock.lang.Specification
import spock.lang.Subject

import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.List

import org.slf4j.Logger
import retrofit.RetrofitError
import rx.schedulers.Schedulers
/**
 * Tests for JenkinsBuildMonitor
 */
@SuppressWarnings(['DuplicateNumberLiteral', 'PropertyName'])
class WerckerBuildMonitorSpec extends Specification {

    WerckerCache cache = Mock(WerckerCache)
    WerckerService werckerService = Mock(WerckerService)
    WerckerBuildMonitor monitor
	EchoService echoService = Mock(EchoService)

//	EmbeddedRedis embeddedRedis = EmbeddedRedis.embed()
//	RedisClientDelegate redisClientDelegate = new JedisClientDelegate(embeddedRedis.pool as JedisPool)
//	WerckerCache cache = new WerckerCache(redisClientDelegate, new IgorConfigurationProperties())
	
    final MASTER = 'MASTER'
	final pipeline = 'myOrg/myApp/pollingTest'
    final List<String> APPS = []
    final TestScheduler scheduler = new TestScheduler()

//    void setup() {
//        monitor = new WerckerBuildMonitor(
//            igorConfigurationProperties,
//            new NoopRegistry(),
//            Optional.empty(),
//            cache,
//            new BuildMasters(map: [MASTER: jenkinsService]),
//            true,
//            Optional.of(echoService),
//            Optional.of(front50Service),
//            new WerckerProperties()
//        )
//
//        monitor.worker = Schedulers.immediate().createWorker()
//    }
	
	void 'no finished run'() {
		given:
		cache.getJobNames(MASTER) >> ['pipeline']
		BuildMasters buildMasters = Mock(BuildMasters)
		buildMasters.map >> [MASTER: werckerService]
		
		long now = System.currentTimeMillis();
		List<Run> runs1 = [
			new Run(id:"b",    startedAt: new Date(now-10)),
			new Run(id:"a",    startedAt: new Date(now-11)),
			new Run(id:"init", startedAt: new Date(now-12)),
		]
		
		def cfg = new IgorConfigurationProperties()
		cfg.spinnaker.build.pollInterval = 1
		monitor = new WerckerBuildMonitor(
			cfg,
			new NoopRegistry(),
			Optional.empty(),
			cache,
			buildMasters,
			true,
			Optional.of(echoService),
			Optional.empty(),
			new WerckerProperties()
		)
		monitor.worker = scheduler.createWorker()
		werckerService.getRunsSince(_) >> [pipeline: runs1]
		cache.getBuildNumber(_, _, _) >> 1
		
		when:
		monitor.onApplicationEvent(Mock(RemoteStatusChangedEvent))
		scheduler.advanceTimeBy(1L, TimeUnit.SECONDS.MILLISECONDS)

		then: 'initial poll'
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: werckerService]
		1 * buildMasters.map >> [MASTER: werckerService]
		0 * echoService.postEvent(_)

		cleanup:
		monitor.stop()
	}
	
	void 'initial poll with completed runs'() {
		given:
		cache.getJobNames(MASTER) >> ['pipeline']
		BuildMasters buildMasters = Mock(BuildMasters)
		buildMasters.map >> [MASTER: werckerService]
		
		long now = System.currentTimeMillis();
		List<Run> runs1 = [
			new Run(id:"b",    startedAt: new Date(now-10)),
			new Run(id:"a",    startedAt: new Date(now-11), finishedAt: new Date(now-11)),
			new Run(id:"init", startedAt: new Date(now-12), finishedAt: new Date(now-10)),
		]
		
		def cfg = new IgorConfigurationProperties()
		cfg.spinnaker.build.pollInterval = 1
		monitor = new WerckerBuildMonitor(
			cfg,
			new NoopRegistry(),
			Optional.empty(),
			cache,
			buildMasters,
			true,
			Optional.of(echoService),
			Optional.empty(),
			new WerckerProperties()
		)
		monitor.worker = scheduler.createWorker()
		werckerService.getRunsSince(_) >> [pipeline: runs1]
		cache.getBuildNumber(_, _, _) >> 1
		
		when:
		monitor.onApplicationEvent(Mock(RemoteStatusChangedEvent))
		scheduler.advanceTimeBy(1L, TimeUnit.SECONDS.MILLISECONDS)

		then: 'initial poll'
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: werckerService]
		1 * buildMasters.map >> [MASTER: werckerService]
		1 * echoService.postEvent(_)

		cleanup:
		monitor.stop()
	}
	
	void 'select latest one form multiple completed runs'() {
		given:
		cache.getJobNames(MASTER) >> ['pipeline']
		BuildMasters buildMasters = Mock(BuildMasters)
		buildMasters.map >> [MASTER: werckerService]
		
		def cfg = new IgorConfigurationProperties()
		cfg.spinnaker.build.pollInterval = 1
		monitor = new WerckerBuildMonitor(
			cfg,
			new NoopRegistry(),
			Optional.empty(),
			cache,
			buildMasters,
			true,
			Optional.of(echoService),
			Optional.empty(),
			new WerckerProperties()
		)
		monitor.worker = scheduler.createWorker()
		werckerService.getRunsSince(_) >> [:]
		cache.getBuildNumber(_, _, _) >> 1
		
		when:
		monitor.onApplicationEvent(Mock(RemoteStatusChangedEvent))
		scheduler.advanceTimeBy(1L, TimeUnit.SECONDS.MILLISECONDS)

		then: 'initial poll'
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: werckerService]
		1 * buildMasters.map >> [MASTER: werckerService]
		0 * echoService.postEvent(_)
		
		when:
		scheduler.advanceTimeBy(998L, TimeUnit.SECONDS.MILLISECONDS)

		then:
		0 * buildMasters.map >> [MASTER: werckerService]
		0 * werckerService.getRunsSince(_) >> [:]

		when: 'poll at 1 second'
		long now = System.currentTimeMillis();
		List<Run> runs1 = [
			new Run(id:"b",    startedAt: new Date(now-10)),
			new Run(id:"a",    startedAt: new Date(now-11), finishedAt: new Date(now-11)),
			new Run(id:"init", startedAt: new Date(now-12), finishedAt: new Date(now-10)),
		]
		cache.getLastPollCycleTimestamp(_, _) >> (now - 1000)
		werckerService.getRunsSince(_) >> [pipeline: runs1]
		cache.getBuildNumber(_, _, _) >> 1
		scheduler.advanceTimeBy(2L, TimeUnit.SECONDS.MILLISECONDS)
		
		then:
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: werckerService]
		1 * buildMasters.map >> [MASTER: werckerService]
		1 * echoService.postEvent(_)
		
		cleanup:
		monitor.stop()
	}
}
