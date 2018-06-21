/*
 * Copyright (c) 2017, 2018, Oracle Corporation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the Apache License Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * If a copy of the Apache License Version 2.0 was not distributed with this file,
 * You can obtain one at https://www.apache.org/licenses/LICENSE-2.0.html
 */
package com.netflix.spinnaker.igor.wercker

import com.netflix.spinnaker.igor.IgorConfigurationProperties
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import com.netflix.spinnaker.igor.wercker.model.Run;
import redis.clients.jedis.JedisPool
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class WerckerCacheSpec extends Specification {

    EmbeddedRedis embeddedRedis = EmbeddedRedis.embed()

    RedisClientDelegate redisClientDelegate = new JedisClientDelegate(embeddedRedis.pool as JedisPool)

    @Subject
    WerckerCache cache = new WerckerCache(redisClientDelegate, new IgorConfigurationProperties())

    final master = 'testWerckerMaster'
    final test = 'test'
	final pipeline = 'myOrg/myApp/myTestPipeline'

    void cleanup() {
        embeddedRedis.pool.resource.withCloseable {
            it.flushDB()
        }
    }

    void 'lastPollCycleTimestamp get overridden'() {
		long now1 = System.currentTimeMillis();
        when:
        cache.setLastPollCycleTimestamp(master, 'myOrg/myApp/myPipeline', now1)
        then:
        cache.getLastPollCycleTimestamp(master, 'myOrg/myApp/myPipeline') == now1
		
		long now2 = System.currentTimeMillis();
        when:
        cache.setLastPollCycleTimestamp(master, 'myOrg/myApp/myPipeline', now2)
        then:
        cache.getLastPollCycleTimestamp(master, 'myOrg/myApp/myPipeline') == now2
    }

    void 'generates buildNumbers ordered by startedAt'() {
		long now = System.currentTimeMillis();
		List<Run> runs1 = [
			new Run(id:"b",    startedAt: new Date(now-10)),
			new Run(id:"a",    startedAt: new Date(now-11)),
			new Run(id:"init", startedAt: new Date(now-12)),
		]
        when:
        cache.updateBuildNumbers(master, pipeline, runs1)

        then:
        cache.getBuildNumber(master, pipeline, 'init') == 0
        cache.getBuildNumber(master, pipeline, 'a') == 1
		
		List<Run> runs2 = [
			new Run(id:"latest", startedAt: new Date(now)),
			new Run(id:"d",      startedAt: new Date(now-1)),
			new Run(id:"c",      startedAt: new Date(now-2)),
		]
		cache.updateBuildNumbers(master, pipeline, runs2)

		expect:
		cache.getBuildNumber(master, pipeline, 'latest') == 5
		cache.getBuildNumber(master, pipeline, 'd') == 4
    }
}
