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
import groovy.util.logging.Slf4j
import retrofit.RetrofitError
import retrofit.client.Response
import retrofit.mime.TypedByteArray
import com.netflix.spinnaker.igor.config.WerckerProperties.WerckerHost
import static com.netflix.spinnaker.igor.model.BuildServiceProvider.WERCKER
import static net.logstash.logback.argument.StructuredArguments.kv

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
//    public WerckerService(String address, WerckerCache cache, String werckerHostId,
//                          WerckerClient werckerClient, String user, String token, String master) {
//        this.groupKey = werckerHostId/
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
        GenericBuild someBuild = new GenericBuild()
        someBuild.name = job
        someBuild.building = true
        someBuild.fullDisplayName = "Wercker Job " + job + " [" + buildNumber + "]"
        someBuild.number = buildNumber
		//API
//      someBuild.url = address + "api/v3/runs/" + cache.getRunID(groupKey, job, buildNumber) 
		//UI the user should be org
        String[] split = job.split(SPLITOR)
        String app = split[0]
        String pipeline = split[1]
        String runId = cache.getRunID(groupKey, job, buildNumber)
        if (runId == null) {
            throw new BuildController.BuildJobError(
                "Could not find build number ${buildNumber} for job ${job} - no matching run ID!")
        }

        Run run = werckerClient.getRunById(authHeaderValue, runId)

        genericBuild.building = (run.finishedAt == null)
        genericBuild.fullDisplayName = "Wercker Job " + job + " [" + runId + "]"
        String addr = address.endsWith("/") ? address.substring(0, address.length()-1) : address
        genericBuild.url = String.join("/", addr, user, app, "runs", pipeline, runId)
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
        String[] split = appAndPipelineName.split("~")
        String appName = split[0]
        String pipelineName = split[1]
        List<Pipeline> pipelines = werckerClient.getPipelinesForApplication(
            authHeaderValue, user, appName)
        Pipeline pipeline = pipelines.find {p -> pipelineName.equals(p.pipelineName)}
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

    public List<String> getApplicationAndPipelineNames() {
		List<String> appAndPipelineNames = []
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
						pipelines.each {pipeline ->
							appAndPipelineNames.add(org + SPLITOR + app + SPLITOR + pipeline.name)
						}
					} catch(retrofit.RetrofitError err) {
						log.info "Error getting pipelines for ${org} ${app} pipelines: ${err} ${err.getClass()}"
					}
				});
			}
		} else {
		long start = System.currentTimeMillis()
//	        List<Application> apps = werckerClient.getApplicationsByOwner(authHeaderValue, user).each {a -> a.name}
		    List<Application> apps = werckerClient.getApplications(authHeaderValue)
		log.info "~~~ getAllApps ${apps.size()} ${System.currentTimeMillis() - start}ms"
		start = System.currentTimeMillis()
//			log.info "~~~ getApplicationAndPipelineNames getAllApps : ${System.currentTimeMillis() - start}"
			start = System.currentTimeMillis()
	        apps.each {app ->
				try {
					
		            List<Pipeline> pipelines = werckerClient.getPipelinesForApplication(authHeaderValue, app.owner.name, app.name)
		            pipelines.each { pipeline ->
						String pipelineName = app.owner.name + SPLITOR + app.name + SPLITOR + pipeline.name
		                appAndPipelineNames.add(pipelineName)
		            }
				} catch(retrofit.RetrofitError err) {
					log.info "Error getting pipelines for ${app.owner.name } ${app.name} ${err} ${err.getClass()}"
				}	
	        }
		log.info "~~~ getAllPipeline: ${System.currentTimeMillis() - start}ms ${appAndPipelineNames.size()}"
		}
        return appAndPipelineNames
    }
	
    public List<String> getApplications() {
		log.info "~~~ ${groupKey}.getApplications for ${organizations}"
		List<String> orgApps = []
		long start = System.currentTimeMillis()
		if (organizations != null && !organizations.isEmpty()) {
			List<String> orgs = organizations
			if (!organizations.contains(user)) { 
				orgs = organizations.collect()
				orgs.add(user);
			}
log.info "       ~~~~ for orgS${orgs}"
			orgs.each { orgApp ->
				String org = orgApp;
				String[] split = orgApp.split(SPLITOR)
log.info "       ~~~~    for org ${org}"
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
		log.info "~~~ ${groupKey}.getApplications: ${orgApps}  ${System.currentTimeMillis() - start}ms"
        return orgApps
    }
	
	List<String> appsOf(String org) {
		return werckerClient.getApplicationsByOwner(authHeaderValue, org).collect { org + SPLITOR + it.name };
	}

    public List<String> getPipelines(String applicationName) {
		String[] split = applicationName.split(SPLITOR)
		String org = (split.size() == 1)? user : split[0]
		String app = (split.size() == 1)? applicationName : split[1]
        List<String> pipelines  = werckerClient.getPipelinesForApplication(authHeaderValue, org, app)
		log.info "~~~ ${groupKey}.getPipelines for ${applicationName}: ${pipelines}"
		return pipelines;
    }
	
	public List<String> getPipelines(String org, String app) {
		log.info "~~~ ${groupKey}.getPipelines for ${org} ${app}"
		List<String> pipelines  = werckerClient
		  .getPipelinesForApplication(authHeaderValue, org, app)
		  .collect { it.name }
		log.info "~~~ ${groupKey}.getPipelines for ${org} ${app} ${pipelines}"
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
        List<Run> runs = []
		String pipelineId = cache.getPipelineID(groupKey, appAndPipelineName)
		if (pipelineId == null) {
        Pipeline matchingPipeline = werckerClient.getPipelinesForApplication(
            authHeaderValue, owner, appName).find {pipeline -> pipelineName == pipeline.name}
			if (matchingPipeline) {
				pipelineId = matchingPipeline.id;
				cache.setPipelineID(groupKey, appAndPipelineName, pipelineId)
			}
		}
		log.info "~~~getBuilds for ${groupKey} ${appAndPipelineName} ${pipelineId}"
        return werckerClient.getRunsForPipeline(authHeaderValue, pipelineId)
    }
}
