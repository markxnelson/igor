package com.netflix.spinnaker.igor.wercker.model

class RunPayload {
    String pipelineId
    String message

    RunPayload(final String pipelineId, final String message) {
        this.pipelineId = pipelineId
        this.message = message
    }
}
