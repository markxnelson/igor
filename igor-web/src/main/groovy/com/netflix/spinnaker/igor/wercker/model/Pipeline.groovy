package com.netflix.spinnaker.igor.wercker.model

/**
 * Represents a Wercker Pipeline
 */
class Pipeline {
    String id
    String url
    String name
    String permissions
    String pipelineName //In the wercker.yml, I think
    boolean setScmProviderStatus
    String type
    /*
                "id": "58347282dd22a501005268e7",
            "url": "https://dev.wercker.com/api/v3/pipelines/58347282dd22a501005268e7",
            "createdAt": "2016-11-22T16:29:54.792Z",
            "name": "deploy-kube-staging",
            "permissions": "read",
            "pipelineName": "deploy-kube",
            "setScmProviderStatus": false,
            "type": "pipeline"
     */
}
