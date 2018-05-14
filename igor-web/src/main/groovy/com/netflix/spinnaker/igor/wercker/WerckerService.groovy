package com.netflix.spinnaker.igor.wercker

import com.netflix.spinnaker.igor.build.model.GenericBuild
import com.netflix.spinnaker.igor.build.model.GenericGitRevision
import com.netflix.spinnaker.igor.model.BuildServiceProvider
import com.netflix.spinnaker.igor.service.BuildService

import static com.netflix.spinnaker.igor.model.BuildServiceProvider.WERCKER

class WerckerService implements BuildService {

    private String groupKey;
    public WerckerService(String werckerHostId) {
        this.groupKey = werckerHostId
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
}
