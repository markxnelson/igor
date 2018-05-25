/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.igor.wercker;

import com.netflix.spinnaker.igor.IgorConfigurationProperties;
import com.netflix.spinnaker.igor.wercker.model.Run;
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared cache of build details for jenkins
 */
@Service
public class WerckerCache {

    private static final String POLL_STAMP = "lastPollCycleTimestamp";

    private final RedisClientDelegate redisClientDelegate;
    private final IgorConfigurationProperties igorConfigurationProperties;

    @Autowired
    public WerckerCache(RedisClientDelegate redisClientDelegate,
                        IgorConfigurationProperties igorConfigurationProperties) {
        this.redisClientDelegate = redisClientDelegate;
        this.igorConfigurationProperties = igorConfigurationProperties;
    }

//    public List<String> getJobNames(String master) {
//        List<String> jobs = redisClientDelegate.withMultiClient(c -> {
//            return c.keys(prefix() + ":" + master + ":*").stream()
//                .map(JenkinsCache::extractJobName)
//                .collect(Collectors.toList());
//        });
//        jobs.sort(Comparator.naturalOrder());
//        return jobs;
//    }
//
//    public List<String> getTypeaheadResults(String search) {
//        List<String> results = redisClientDelegate.withMultiClient(c -> {
//            return c.keys(prefix() + ":*:*" + search.toUpperCase() + "*:*").stream()
//                .map(JenkinsCache::extractTypeaheadResult)
//                .collect(Collectors.toList());
//        });
//        results.sort(Comparator.naturalOrder());
//        return results;
//    }

//    public Map<String, Object> getLastBuild(String master, String job) {
//        String key = makeKey(master, job);
//        Map<String, String> result = redisClientDelegate.withCommandsClient(c -> {
//            if (!c.exists(key)) {
//                return null;
//            }
//            return c.hgetAll(key);
//        });
//
//        if (result == null) {
//            return new HashMap<>();
//        }
//
//        Map<String, Object> converted = new HashMap<>();
//        converted.put("lastBuildLabel", Integer.parseInt(result.get("lastBuildLabel")));
//        converted.put("lastBuildBuilding", Boolean.valueOf(result.get("lastBuildBuilding")));
//
//        return converted;
//    }
//
//    public void setLastBuild(String master, String job, int lastBuild, boolean building) {
//        String key = makeKey(master, job);
//        redisClientDelegate.withCommandsClient(c -> {
//            c.hset(key, "lastBuildLabel", Integer.toString(lastBuild));
//            c.hset(key, "lastBuildBuilding", Boolean.toString(building));
//        });
//    }

    public void setLastPollCycleTimestamp(String master, String pipeline, Long timestamp) {
        String key = makeKey(master, pipeline);
        System.out.println("~~~~ setLastPollCycleTimestamp " + key);
        redisClientDelegate.withCommandsClient(c -> {
            c.hset(key, POLL_STAMP, Long.toString(timestamp));
        });
    }

    public Long getLastPollCycleTimestamp(String master, String pipeline) {
        System.out.println("~~~~ getLastPollCycleTimestamp " + makeKey(master, pipeline));
        return redisClientDelegate.withCommandsClient(c -> {
            String ts = c.hget(makeKey(master, pipeline), POLL_STAMP);
            return ts == null ? null : Long.parseLong(ts);
        });
    }
    
    static Comparator<Run> runStartedAtComparator = new Comparator<Run>() {
		@Override
		public int compare(Run r1, Run r2) {
			long l = (r1.getStartedAt().toInstant().toEpochMilli() - r2.getStartedAt().toInstant().toEpochMilli());
			return l > 0 ? 1 : (l == 0 ? 0 : -1);
		}
    };
    
    public List<String> updateBuildNumbers(String master, String pipeline, List<Run> runs) {
        String key = makeKey(master, pipeline) + ":runs";
        final Map<String, String> existing = redisClientDelegate.withCommandsClient(c -> {
        	if (!c.exists(key)) {
        		return null;
        	}
        	return c.hgetAll(key);
        });

        System.out.println("~~~~~  " + ((existing == null || existing.size() == 0) ? "new" : "!!update!!" ) + " " + master + " " + pipeline);
    	List<Run> newRuns = (existing == null || existing.size() == 0) ? runs 
    			: runs.stream().filter(run -> !existing.containsKey(run.getId())).collect(Collectors.toList());
    	int startNumber = (existing == null || existing.size() == 0) ? 0 : existing.size();
    	newRuns.sort(runStartedAtComparator);
        return setBuildNumbers(master, pipeline, startNumber, newRuns); 
    }
    
    private List<String> setBuildNumbers(String master, String pipeline, int start, List<Run> runs) {
        List<String> newRunsIDs = new ArrayList<>();
    	for(int i = 0; i < runs.size(); i++) {
            System.out.println("    setBuildNumber " + (start+i) + " " + runs.get(i).getId() + " " + runs.get(i).getStartedAt());
    		setBuildNumber(master, pipeline, runs.get(i).getId() , start + i);
    		newRunsIDs.add(runs.get(i).getId());
    	}
    	return newRunsIDs;
    }

    public void setBuildNumber(String master, String pipeline, String runID, int number) {
        String key = makeKey(master, pipeline) + ":runs";
        redisClientDelegate.withCommandsClient(c -> {
            c.hset(key, runID, Integer.toString(number));
        });
    }

    public Long getBuildNumber(String master, String pipeline, String runID) {
        System.out.println("~~~~ getBuildNumbers " + makeKey(master, pipeline));
        return redisClientDelegate.withCommandsClient(c -> {
            String ts = c.hget(makeKey(master, pipeline)+ ":runs", runID);
            return ts == null ? null : Long.parseLong(ts);
        });
    }

    public Boolean getEventPosted(String master, String job, Long cursor, String runID) {
        String key = makeKey(master, job) + ":" + POLL_STAMP + ":" + cursor;
        return redisClientDelegate.withCommandsClient(c -> c.hget(key, runID) != null);
    }

    public void setEventPosted(String master, String job, Long cursor, String runID) {
        String key = makeKey(master, job) + ":" + POLL_STAMP + ":" + cursor;
        redisClientDelegate.withCommandsClient(c -> {
            c.hset(key, runID, "POSTED");
        });
    }

    public void pruneOldMarkers(String master, String job, Long cursor) {
        remove(master, job);
        redisClientDelegate.withCommandsClient(c -> {
            c.del(makeKey(master, job) + ":" + POLL_STAMP + ":" + cursor);
        });
    }

    public void remove(String master, String job) {
        redisClientDelegate.withCommandsClient(c -> {
            c.del(makeKey(master, job));
        });
    }

    private String makeKey(String master, String job) {
        return prefix() + ":" + master + ":" + job.toUpperCase() + ":" + job;
    }

//    private static String extractJobName(String key) {
//        return key.split(":")[3];
//    }
//
//    private static String extractTypeaheadResult(String key) {
//        String[] parts = key.split(":");
//        return parts[1] + ":" + parts[3];
//    }

    private String prefix() {
        return igorConfigurationProperties.getSpinnaker().getJedis().getPrefix();
    }
}
