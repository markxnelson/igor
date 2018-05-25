package com.netflix.spinnaker.igor.wercker

import com.netflix.spinnaker.igor.build.model.GenericBuild
import com.netflix.spinnaker.igor.build.model.GenericGitRevision
import com.netflix.spinnaker.igor.model.BuildServiceProvider
import com.netflix.spinnaker.igor.service.BuildService
import com.netflix.spinnaker.igor.wercker.model.Application
import com.netflix.spinnaker.igor.wercker.model.Pipeline
import com.netflix.spinnaker.igor.wercker.model.Run

import static com.netflix.spinnaker.igor.model.BuildServiceProvider.WERCKER

class WerckerService implements BuildService {

    String groupKey;
    WerckerClient werckerClient
    String user
    String token
    String authHeaderValue
	private static String SPLITOR = "~";

    public WerckerService(String werckerHostId, WerckerClient werckerClient, String user, String token) {
        this.groupKey = werckerHostId
        this.werckerClient = werckerClient
        this.user = user
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
        //TODO desagar job = pipeline, build=run => we don't have numeric build numbers. how to implement??
        //Note - GitlabCI throws UnsupportedOperationException for this - why is that?
        GenericBuild someBuild = new GenericBuild()
        someBuild.name = job
        someBuild.building = true
        someBuild.fullDisplayName = "Wercker Job " + job + " [" + buildNumber + "]"
        someBuild.number = buildNumber
        someBuild.url = "werckerUrlHere"
        return someBuild
    }

    @Override
    int triggerBuildWithParameters(final String job, final Map<String, String> queryParameters) {
        //Always build 42 for now
        return 42
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

    Pipeline getPipelineByNameAndAppId(final String pipelineName, final String appId) {
        return werckerClient.getPipelinesForApplication(authHeaderValue, app.id)
    }
}
