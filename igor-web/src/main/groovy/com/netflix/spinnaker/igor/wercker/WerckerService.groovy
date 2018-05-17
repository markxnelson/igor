package com.netflix.spinnaker.igor.wercker

import com.netflix.spinnaker.igor.build.model.GenericBuild
import com.netflix.spinnaker.igor.build.model.GenericGitRevision
import com.netflix.spinnaker.igor.model.BuildServiceProvider
import com.netflix.spinnaker.igor.service.BuildService
import com.netflix.spinnaker.igor.wercker.model.Application
import com.netflix.spinnaker.igor.wercker.model.Run

import static com.netflix.spinnaker.igor.model.BuildServiceProvider.WERCKER

class WerckerService implements BuildService {

    String groupKey;
    WerckerClient werckerClient

    public WerckerService(String werckerHostId, WerckerClient werckerClient) {
        this.groupKey = werckerHostId
        this.werckerClient = werckerClient
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

    List<Application> getApplicationNames

    List<Run> getBuilds(String pipelineId) {
        werckerClient.getRunsForPipeline()
    }
}
