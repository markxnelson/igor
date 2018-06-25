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
import com.netflix.spinnaker.igor.wercker.model.Application
import com.netflix.spinnaker.igor.wercker.model.Pipeline
import com.netflix.spinnaker.igor.wercker.model.Run
import com.netflix.spinnaker.igor.wercker.model.Owner
import com.netflix.spinnaker.igor.model.BuildServiceProvider
import com.netflix.spinnaker.igor.service.BuildMasters
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.igor.history.model.Event
import com.netflix.spinnaker.igor.service.Front50Service
import com.netflix.spinnaker.igor.config.WerckerProperties.WerckerHost

import rx.schedulers.TestScheduler
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.List

import org.slf4j.Logger
import retrofit.RetrofitError
import rx.schedulers.Schedulers


class WerckerBuildMonitorSpec extends Specification {

    WerckerCache cache = Mock(WerckerCache)
    WerckerBuildMonitor monitor
	EchoService echoService = Mock(EchoService)
	WerckerClient client
    WerckerService mockService = Mock(WerckerService)
    WerckerService werckerService
	String werckerDev = 'https://dev.wercker.com/'
	String master = 'WerckerTestMaster'

    void setup() {
        client = Mock(WerckerClient)
        werckerService = new WerckerService(
			new WerckerHost(name: master, address: werckerDev), cache, client)
    }
	
    final MASTER = 'MASTER'
	final pipeline = 'myOrg/myApp/pollingTest'
    final List<String> APPS = []
    final TestScheduler scheduler = new TestScheduler()
	
	void 'no finished run'() {
		given:
		cache.getJobNames(MASTER) >> ['pipeline']
		BuildMasters buildMasters = Mock(BuildMasters)
		buildMasters.map >> [MASTER: mockService]
		
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
		mockService.getRunsSince(_) >> [pipeline: runs1]
		cache.getBuildNumber(_, _, _) >> 1
		
		when:
		monitor.onApplicationEvent(Mock(RemoteStatusChangedEvent))
		scheduler.advanceTimeBy(1L, TimeUnit.SECONDS.MILLISECONDS)

		then: 'initial poll'
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: mockService]
		1 * buildMasters.map >> [MASTER: mockService]
		0 * echoService.postEvent(_)

		cleanup:
		monitor.stop()
	}
	
	void 'initial poll with completed runs'() {
		given:
		cache.getJobNames(MASTER) >> ['pipeline']
		BuildMasters buildMasters = Mock(BuildMasters)
		buildMasters.map >> [MASTER: mockService]
		
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
		mockService.getRunsSince(_) >> [pipeline: runs1]
		cache.getBuildNumber(_, _, _) >> 1
		
		when:
		monitor.onApplicationEvent(Mock(RemoteStatusChangedEvent))
		scheduler.advanceTimeBy(1L, TimeUnit.SECONDS.MILLISECONDS)

		then: 'initial poll'
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: mockService]
		1 * buildMasters.map >> [MASTER: mockService]
		1 * echoService.postEvent(_)

		cleanup:
		monitor.stop()
	}
	
	void 'select latest one form multiple completed runs'() {
		given:
		cache.getJobNames(MASTER) >> ['pipeline']
		BuildMasters buildMasters = Mock(BuildMasters)
		buildMasters.map >> [MASTER: mockService]
		
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
		mockService.getRunsSince(_) >> [:]
		cache.getBuildNumber(_, _, _) >> 1
		
		when:
		monitor.onApplicationEvent(Mock(RemoteStatusChangedEvent))
		scheduler.advanceTimeBy(1L, TimeUnit.SECONDS.MILLISECONDS)

		then: 'initial poll'
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: mockService]
		1 * buildMasters.map >> [MASTER: mockService]
		0 * echoService.postEvent(_)
		
		when:
		scheduler.advanceTimeBy(998L, TimeUnit.SECONDS.MILLISECONDS)

		then:
		0 * buildMasters.map >> [MASTER: mockService]
		0 * mockService.getRunsSince(_) >> [:]

		when: 'poll at 1 second'
		long now = System.currentTimeMillis();
		List<Run> runs1 = [
			new Run(id:"b",    startedAt: new Date(now-10)),
			new Run(id:"a",    startedAt: new Date(now-11), finishedAt: new Date(now-11)),
			new Run(id:"init", startedAt: new Date(now-12), finishedAt: new Date(now-10)),
		]
		cache.getLastPollCycleTimestamp(_, _) >> (now - 1000)
		mockService.getRunsSince(_) >> [pipeline: runs1]
		cache.getBuildNumber(_, _, _) >> 1
		scheduler.advanceTimeBy(2L, TimeUnit.SECONDS.MILLISECONDS)
		
		then:
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: mockService]
		1 * buildMasters.map >> [MASTER: mockService]
		1 * echoService.postEvent(_)
		
		cleanup:
		monitor.stop()
	}
	
	
	void 'get runs of multiple pipelines'() {
		setup:
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
		cache.getBuildNumber(_, _, _) >> 1
		client.getRunsSince(_,_) >> []
		
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

		when: 'poll at 1 second'
		long now = System.currentTimeMillis();
		def org = 'myOrg'	
		def apps = [appOf('app0', org, [pipeOf('p00', 'pipeline')]), 
			        appOf('app1', org, [pipeOf('p10', 'git'), pipeOf('p11', 'git')]), 
					appOf('app2', org, [pipeOf('p20', 'git')]), 
					appOf('app3', org, [pipeOf('p30', 'git')])]
		List<Run> runs1 = [
			runOf('run0', now-10, now-1, apps[0], apps[0].pipelines[0]),
			runOf('run1', now-10, now-1, apps[1], apps[1].pipelines[0]),
			runOf('run2', now-10, now-2, apps[2], apps[2].pipelines[0]),
			runOf('run3', now-10, now-1, apps[1], apps[1].pipelines[1]),
			runOf('run4', now-10, now-1, apps[2], apps[2].pipelines[0]),
			runOf('run5', now-10, null,  apps[3], apps[3].pipelines[0]),
			runOf('run6', now-10, now-2, apps[0], apps[0].pipelines[0]),
			runOf('run6', now-10, now-3, apps[0], apps[0].pipelines[0]),
		]
		client.getRunsSince(_,_) >> runs1
		cache.getLastPollCycleTimestamp(_, _) >> (now - 1000)
		cache.getBuildNumber(_, _, _) >> 1
		scheduler.advanceTimeBy(2L, TimeUnit.SECONDS.MILLISECONDS)

		then:
		1 * buildMasters.filteredMap(BuildServiceProvider.WERCKER) >> [MASTER: werckerService]
		1 * buildMasters.map >> [MASTER: werckerService]
        1 * cache.setEventPosted('MASTER', 'myOrg/app0/p00', 'run0')
        1 * cache.setEventPosted('MASTER', 'myOrg/app1/p10', 'run1')
		1 * cache.setEventPosted('MASTER', 'myOrg/app1/p11', 'run3')
		1 * cache.setEventPosted('MASTER', 'myOrg/app2/p20', 'run4')
		0 * cache.setEventPosted('MASTER', 'myOrg/app3/p30', 'run5')
		4 * echoService.postEvent(_)

		cleanup:
		monitor.stop()
	}

	Application appOf(String name, String owner, List<Pipeline> pipelines) {
		return new Application(name: name, owner: new Owner(name: owner), pipelines: pipelines)
	}
	
	Pipeline pipeOf(String name, String type, String id=name) {
		return new Pipeline(id: id, name: name, type: type)
	}
	
	Run runOf(String id, long startedAt, Long finishedAt, Application app, Pipeline pipe) {
		return new Run(id: id, startedAt: new Date(startedAt), finishedAt: finishedAt? new Date(finishedAt) : null, application: app, pipeline: pipe)
	}
}
