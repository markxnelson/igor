/*
 * Copyright (c) 2017, 2018, Oracle Corporation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the Apache License Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * If a copy of the Apache License Version 2.0 was not distributed with this file,
 * You can obtain one at https://www.apache.org/licenses/LICENSE-2.0.html
 */
package com.netflix.spinnaker.igor.wercker

import com.netflix.spinnaker.igor.build.BuildController
import com.netflix.spinnaker.igor.build.model.GenericBuild
import com.netflix.spinnaker.igor.build.model.GenericGitRevision
import com.netflix.spinnaker.igor.build.model.Result
import com.netflix.spinnaker.igor.model.BuildServiceProvider
import com.netflix.spinnaker.igor.service.BuildService
import com.netflix.spinnaker.igor.wercker.model.Application
import com.netflix.spinnaker.igor.wercker.model.Pipeline
import com.netflix.spinnaker.igor.wercker.model.Run
import com.netflix.spinnaker.igor.wercker.model.RunPayload
import com.netflix.spinnaker.hystrix.SimpleHystrixCommand
import groovy.util.logging.Slf4j
import retrofit.RetrofitError
import retrofit.client.Response
import retrofit.mime.TypedByteArray
import com.netflix.spinnaker.igor.config.WerckerProperties.WerckerHost
import com.netflix.spinnaker.igor.jenkins.client.model.JobConfig
import com.netflix.spinnaker.igor.jenkins.client.model.JobList

import static com.netflix.spinnaker.igor.model.BuildServiceProvider.WERCKER
import static net.logstash.logback.argument.StructuredArguments.kv

import java.util.List

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Slf4j
class WerckerService implements BuildService {

    String groupKey;
    WerckerClient werckerClient
    String user
	List<String> organizations
    String token
    String authHeaderValue
	String address
    String master
    WerckerCache cache
	private static String SPLITOR = "/";

	public WerckerService(WerckerHost wercker, WerckerCache cache, WerckerClient werckerClient) {
        this.groupKey = wercker.name
        this.werckerClient = werckerClient
        this.user = wercker.user
		this.cache = cache
        this.address = address
        this.master = wercker.name
        this.setToken(token)
        this.address = wercker.address
        this.setToken(wercker.token)
		this.organizations = wercker.organizations
    }

    /**
     * Custom setter for token, in order to re-set the authHeaderValue
     * @param token
     * @return
     */
    public setToken(String token) {
        this.authHeaderValue = 'Bearer ' + token
    }

    @Override
    BuildServiceProvider buildServiceProvider() {
        return WERCKER
    }

    @Override
    List<GenericGitRevision> getGenericGitRevisions(final String job, final int buildNumber) {
        return null
    }

    @Override
    GenericBuild getGenericBuild(final String job, final int buildNumber) {
        //UI the user should be org
        //Note - GitlabCI throws UnsupportedOperationException for this - why is that?
        GenericBuild genericBuild = new GenericBuild()
        genericBuild.name = job
        genericBuild.building = true
        genericBuild.fullDisplayName = "Wercker Job " + job + " [" + buildNumber + "]"
        genericBuild.number = buildNumber
		//API
//      someBuild.url = address + "api/v3/runs/" + cache.getRunID(groupKey, job, buildNumber) 
		//UI the user should be org
        String[] split = job.split(SPLITOR)
        String org = split[0]
        String app = split[1]
        String pipeline = split[2]
        String runId = cache.getRunID(groupKey, job, buildNumber)
        if (runId == null) {
            throw new BuildController.BuildJobError(
                "Could not find build number ${buildNumber} for job ${job} - no matching run ID!")
        }

        Run run = werckerClient.getRunById(authHeaderValue, runId)

        genericBuild.building = (run.finishedAt == null)
        genericBuild.fullDisplayName = "Wercker Job " + job + " [" + runId + "]"
        String addr = address.endsWith("/") ? address.substring(0, address.length()-1) : address
        genericBuild.url = String.join("/", addr, org, app, "runs", pipeline, runId)
        genericBuild.result = mapRunToResult(run)
        return genericBuild
    }

    Result mapRunToResult(final Run run) {
        if (run.finishedAt == null) return Result.BUILDING
        if ("notstarted".equals(run.status)) return Result.NOT_BUILT
        switch (run.result) {
            case "passed":
                return Result.SUCCESS
                break
            case "aborted":
                return Result.ABORTED
                break
            case "failed":
                return Result.FAILURE
                break
        }
        return Result.UNSTABLE
    }

    Response stopRunningBuild (String appAndPipelineName, Integer buildNumber){
        String[] split = appAndPipelineName.split(SPLITOR)
        String appName = split[0]
        String pipelineName = split[1]
        String runId = cache.getRunID(groupKey, appAndPipelineName, buildNumber)
        if (runId == null) {
            log.warn("Could not cancel build number {} for job {} - no matching run ID!",
                kv("buildNumber", buildNumber), kv("job", appAndPipelineName))
            return
        }
        log.info("Aborting Wercker run id {}", kv("runId", runId))
        return werckerClient.abortRun(authHeaderValue, runId, [:])
    }

    @Override
    int triggerBuildWithParameters(final String appAndPipelineName, final Map<String, String> queryParameters) {
        String[] split = appAndPipelineName.split(SPLITOR)
		String org = split[0]
		String appName = split[1]
		String pipelineName = split[2]
		
        List<Pipeline> pipelines = werckerClient.getPipelinesForApplication(
            authHeaderValue, org, appName)
		List<String> pnames = pipelines.collect { it.name + "|" +it.pipelineName + "|" + it.id }
        log.debug "triggerBuildWithParameters pipelines: ${pnames}"
        Pipeline pipeline = pipelines.find {p -> pipelineName.equals(p.name)}
        if (pipeline) {
            log.info("Triggering run for pipeline {} with id {}",
                kv("pipelineName", pipelineName), kv("pipelineId", pipeline.id))
            try {
                Map<String, Object> runInfo = werckerClient.triggerBuild(
                    authHeaderValue, new RunPayload(pipeline.id, 'Triggered from Spinnaker'))
                //TODO desagar the triggerBuild call above itself returns a Run, but the createdAt date
                //is not in ISO8601 format, and parsing fails. The following is a temporary
                //workaround - the getRunById call below gets the same Run object but Wercker
                //returns the date in the ISO8601 format for this case.
                Run run = werckerClient.getRunById(authHeaderValue, runInfo.get('id'))

                //Create an entry in the WerckerCache for this new run. This will also generate
                //an integer build number for the run
                Map<String, Integer> runIdBuildNumbers = cache.updateBuildNumbers(
                    master, appAndPipelineName, Collections.singletonList(run))

                log.info("Triggered run {} at URL {} with build number {}",
                    kv("runId", run.id), kv("url", run.url),
                    kv("buildNumber", runIdBuildNumbers.get(run.id)))

                //return the integer build number for this run id
                return runIdBuildNumbers.get(run.id)
            } catch (RetrofitError e) {
                def body = e.getResponse().getBody()
                String wkrMsg
                if (body instanceof TypedByteArray) {
                    wkrMsg = new String(((retrofit.mime.TypedByteArray) body).getBytes())
                } else {
                    wkrMsg = body.in().text
                }
                log.error("Failed to trigger build for pipeline {}. {}", kv("pipelineName", pipelineName), kv("errMsg", wkrMsg))
                throw new BuildController.BuildJobError(
                    "Failed to trigger build for pipeline ${pipelineName}! Error from Wercker is: ${wkrMsg}")
            }

        } else {
            throw new BuildController.InvalidJobParameterException(
                "Could not retrieve pipeline ${pipelineName} for application ${appName} from Wercker!")

        }
    }

	/**
	 * Returns List of all Wercker jobs in the format of type/org/app/pipeline 
	 */
	public List<String> getJobs() {
		List<String> jobs = []
		long start = System.currentTimeMillis();
		List<Application> apps = applications();
		log.debug "getApplications: ${apps.size()} applications in ${System.currentTimeMillis() - start}ms!!"
		start = System.currentTimeMillis()
		apps.each { app ->
            try {
				List<Pipeline> pipelines = app.pipelines? app.pipelines: pipelines(app.owner.name, app.name);
				jobs.addAll( pipelines.collect { 
					it.type + SPLITOR + app.owner.name + SPLITOR + app.name + SPLITOR + it.name } )
				
//                pipelines.each { pipeline ->
//                    String pipelineName = pipeline.type + SPLITOR +
//					    app.owner.name + SPLITOR + app.name + SPLITOR + pipeline.name;
//                    jobs.add(pipelineName)
//				}
			} catch(retrofit.RetrofitError err) {
				log.info "Error getting pipelines for ${app.owner.name } ${app.name} ${err} ${err.getClass()}"
			}
		}
		log.info "!! getPipelines: ${jobs.size()} pipelines in ${System.currentTimeMillis() - start}ms!!"
        return jobs
	}
	
	List<Application> applications() {
		new SimpleHystrixCommand<List<Application>>(groupKey, buildCommandKey("getApplications"), {
		    return werckerClient.getApplicationsWithPipelines(authHeaderValue)
		}).execute();
	}
	
	List<Pipeline> pipelines(String org, String app) {
//429 too many		new SimpleHystrixCommand<List<Pipeline>>(groupKey, buildCommandKey("getPipelines"), {
		    return werckerClient.getPipelinesForApplication(authHeaderValue, org, app)
//		}).execute();
	}
	
  /**
   * A CommandKey should be unique per group (to ensure broken circuits do not span Wercker masters)
   */
    private String buildCommandKey(String id) {
        return "${groupKey}-${id}"
    }
	
    public List<String> XgetApplicationAndPipelineNames() {
		List<String> jobs = []
		if (organizations != null && !organizations.isEmpty()) {
			List<String> orgs = organizations
			if (!organizations.contains(user)) { 
				orgs = organizations.collect()
				orgs.add(user);
			}
			orgs.each { orgApp ->
				String org = orgApp;
				List<String> apps = [];
				String[] split = orgApp.split(SPLITOR)
				if (split.size()== 1) {
					apps = werckerClient.getApplicationsByOwner(authHeaderValue, org).collect { it.name }
				} else if (split.size()== 2) {
					org = split[0]
					apps = [split[1]];
				}
				apps.forEach({app ->
				    try {
						List<Pipeline> pipelines = werckerClient.getPipelinesForApplication(authHeaderValue, org, app)
//						jobs = pipelines
//						    .findAll{ it.type != 'pipeline' }
//							.collect{ org + SPLITOR + app + SPLITOR + it.name }
						pipelines.each { pipeline ->
							if (pipeline.type == 'git') {
								jobs.add(org + SPLITOR + app + SPLITOR + pipeline.name)
							}
						}
					} catch(retrofit.RetrofitError err) {
						log.info "Error getting pipelines for ${org} ${app} pipelines: ${err} ${err.getClass()}"
					}
				});
			}
		} else {
		    long start = System.currentTimeMillis()
		    List<Application> apps = werckerClient.getApplications(authHeaderValue)
		    log.debug "getAllApps ${apps.size()} ${System.currentTimeMillis() - start}ms"
		    start = System.currentTimeMillis()
			start = System.currentTimeMillis()
	        apps.each {app ->
				try {
					
		            List<Pipeline> pipelines = werckerClient.getPipelinesForApplication(authHeaderValue, app.owner.name, app.name)
					pipelines.each { pipeline ->
						if (pipeline.type == 'git') {
							String pipelineName = app.owner.name + SPLITOR + app.name + SPLITOR + pipeline.name
							jobs.add(pipelineName)
						}
		            }
				} catch(retrofit.RetrofitError err) {
					log.info "Error getting pipelines for ${app.owner.name } ${app.name} ${err} ${err.getClass()}"
				}	
	        }
		    log.debug "getAllPipeline: ${System.currentTimeMillis() - start}ms ${jobs.size()}"
		}
        return jobs
    }
	
    public List<String> getApplications() {
		List<String> orgApps = []
		long start = System.currentTimeMillis()
		if (organizations != null && !organizations.isEmpty()) {
			List<String> orgs = organizations
			if (!organizations.contains(user)) { 
				orgs = organizations.collect()
				orgs.add(user);
			}
			orgs.each { orgApp ->
				String org = orgApp;
				String[] split = orgApp.split(SPLITOR)
				if (split.size() == 1) {
					orgApps.addAll(appsOf(org))					
				} else if (split.size() == 2) {
					org = split[0];
					if (split[1] == "*") {
						orgApps.addAll(appsOf(org))
					} else {
						orgApps.add(orgApp);
					}
				}
			}
		} else {
		    orgApps = werckerClient.getApplications(authHeaderValue).collect { it.owner.name + SPLITOR + it.name }
		}
		log.debug "${groupKey}.getApplications: ${orgApps}  ${System.currentTimeMillis() - start}ms"
        return orgApps
    }
	
	List<String> appsOf(String org) {
		return werckerClient.getApplicationsByOwner(authHeaderValue, org).collect { org + SPLITOR + it.name };
	}

	public List<String> getPipelines(String org, String app) {
		log.debug "${groupKey}.getPipelines for ${org} ${app}"
		List<String> pipelines  = werckerClient
		  .getPipelinesForApplication(authHeaderValue, org, app)
		  .collect { it.name }
		log.debug "${groupKey}.getPipelines for ${org} ${app} ${pipelines}"
		return pipelines;
	}

    Application getApplicationByName(String appName) {
        List<Application> applications = werckerClient.getApplications(authHeaderValue)
        return applications.find {a -> a.name == applicationName}
    }

    List<Run> getBuilds(String appAndPipelineName) {
        String[] split = appAndPipelineName.split(SPLITOR)
        String owner = split[0]
        String appName = split[1]
        String pipelineName = split[2]
		String pipelineId = cache.getPipelineID(groupKey, appAndPipelineName)
		if (pipelineId == null) {
			try {		
				List<Pipeline> pipelines = werckerClient.getPipelinesForApplication(authHeaderValue, owner, appName)
				Pipeline matchingPipeline = pipelines.find {pipeline -> pipelineName == pipeline.name}
				if (matchingPipeline) {
					pipelineId = matchingPipeline.id;
					cache.setPipelineID(groupKey, appAndPipelineName, pipelineId)
				}
			} catch(retrofit.RetrofitError err) {
				log.info "Error getting pipelines for ${owner} ${appName} ${err} ${err.getClass()}"
			}	
		}
		log.debug "getBuilds for ${groupKey} ${appAndPipelineName} ${pipelineId}"
        return pipelineId? werckerClient.getRunsForPipeline(authHeaderValue, pipelineId) : [];
    }
	
	String pipelineKey(Run run) {
		return run.getApplication().owner.name + SPLITOR + 
		       run.getApplication().name + SPLITOR + 
			   run.getPipeline().name;
	}
	
	Map<String, List<Run>> getRunsSince(long since) {
		Map<String, List<Run>> pipelineRuns = [:];
		List<Run> allRuns = werckerClient.getRunsSince(authHeaderValue, since); //1528430400000L
		log.debug "getRunsSince ${since} : ${allRuns.size()} ${allRuns}"
		allRuns.forEach({ run ->
			String pipelineKey = pipelineKey(run);
			run.startedAt = run.startedAt?:run.createdAt;
			List<Run> runs = pipelineRuns.get(pipelineKey);
			if (runs) {
				runs.add(run);
			} else {
				runs = [run];
				pipelineRuns.put(pipelineKey, runs);
			}
		});
		return pipelineRuns;
	}
	
	//TODO this is Jenkins JobConfig
	JobConfig getJobConfig(String jobName) {
		return new JobConfig(
			description: 'WerckerPipeline ' + jobName,
			name: jobName);
	}
}
