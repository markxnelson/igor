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

import static com.netflix.spinnaker.igor.model.BuildServiceProvider.WERCKER

class WerckerService implements BuildService {

    String groupKey;
    WerckerClient werckerClient
    String user
    String token
    String authHeaderValue
	String address
    String master
    WerckerCache cache
	private static String SPLITOR = "~";

    public WerckerService(String address, WerckerCache cache, String werckerHostId,
                          WerckerClient werckerClient, String user, String token, String master) {
        this.groupKey = werckerHostId
        this.werckerClient = werckerClient
        this.user = user
		this.cache = cache
        this.address = address
        this.master = master
        this.setToken(token)
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
        GenericBuild genericBuild = new GenericBuild()
        genericBuild.name = job
        genericBuild.number = buildNumber
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

    @Override
    int triggerBuildWithParameters(final String appAndPipelineName, final Map<String, String> queryParameters) {
        String[] split = appAndPipelineName.split("~")
        String appName = split[0]
        String pipelineName = split[1]
        List<Pipeline> pipelines = werckerClient.getPipelinesForApplication(
            authHeaderValue, user, appName)
        Pipeline pipeline = pipelines.find {p -> pipelineName.equals(p.pipelineName)}
        if (pipeline) {
            println "Triggering run for pipeline ${pipelineName} id: ${pipeline.id} "
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

            println "Triggered run ${run.id} at URL ${run.url} with build number ${runIdBuildNumbers.get(run.id)}"
            //return the integer build number for this run id
            return runIdBuildNumbers.get(run.id)
        } else {
            throw new BuildController.InvalidJobParameterException(
                "Could not retrieve pipeline ${pipelineName} for application ${appName} from Wercker!")

        }
    }

    List<String> getApplicationAndPipelineNames() {
        List<Application> apps = werckerClient.getApplicationsByOwner(authHeaderValue, this.user).each {a -> a.name}
        List<String> appAndPipelineNames = []
        apps.each {app ->
            List<Pipeline> pipelines = werckerClient.getPipelinesForApplication(authHeaderValue, user, app.name)
            pipelines.each {pipeline ->
                appAndPipelineNames.add(app.name + SPLITOR + pipeline.name)
            }
        }
        return appAndPipelineNames
    }

    List<Application> getPipelinesForApplication(String applicationName) {
        werckerClient.getPipelinesForApplication(authHeaderValue, user, applicationName)
    }

    Application getApplicationByName(String appName) {
        List<Application> applications = werckerClient.getApplications(authHeaderValue)
        return applications.find {a -> a.name == applicationName}
    }

    List<Run> getBuilds(String appAndPipelineName) {
        String[] split = appAndPipelineName.split(SPLITOR)
        String appName = split[0]
        String pipelineName = split[1]
        List<Run> runs = []
        Pipeline matchingPipeline = werckerClient.getPipelinesForApplication(
            authHeaderValue, user, appName).find {pipeline -> pipelineName == pipeline.name}
        return werckerClient.getRunsForPipeline(authHeaderValue, matchingPipeline.id)
    }
}
