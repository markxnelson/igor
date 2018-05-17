package com.netflix.spinnaker.igor.wercker.model

/**
 * Represents a Wercker Run
 */
class Run {
    String id
    String url
    String branch
    String commitHash
    String message
    int progress
    String result
    String status
    Pipeline pipeline

    /*
    "id": "59bfcc67e134fb00016baf3f",
        "url": "https://dev.wercker.com/api/v3/runs/59bfcc67e134fb00016baf3f",
        "branch": "master",
        "commitHash": "ffe7317ce8deff23f14cf9d465fc136a4adcc59e",
        "createdAt": "2017-09-18T13:38:47.875Z",
        "finishedAt": "2017-09-18T13:39:01.768Z",
        "message": "Auto trigger from Pipeline \"push-quay\" to Pipeline \"deploy-kube-staging\"",
        "progress": 100,
        "result": "passed",
        "startedAt": "2017-09-18T13:38:48.199Z",
        "status": "finished",
        "user": {
            "userId": "4feadab2e755f5e15b000262",
            "meta": {
                "username": "bvdberg",
                "type": ""
            },
            "avatar": {
                "gravatar": "dff7a3e4eadab56aa69a24569cb61e98"
            },
            "name": "Benno",
            "type": "wercker"
        },
        "pipeline": {
            "id": "58347282dd22a501005268e7",
            "url": "https://dev.wercker.com/api/v3/pipelines/58347282dd22a501005268e7",
            "createdAt": "2016-11-22T16:29:54.792Z",
            "name": "deploy-kube-staging",
            "permissions": "read",
            "pipelineName": "deploy-kube",
            "setScmProviderStatus": false,
            "type": "pipeline"
        }
     */
}
