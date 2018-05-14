/*
 * Copyright (c) 2017, 2018, Oracle Corporation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the Apache License Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * If a copy of the Apache License Version 2.0 was not distributed with this file,
 * You can obtain one at https://www.apache.org/licenses/LICENSE-2.0.html
 */
package com.netflix.spinnaker.igor.wercker

import com.netflix.spinnaker.igor.wercker.model.Application
import com.netflix.spinnaker.igor.wercker.model.Pipeline
import com.netflix.spinnaker.igor.wercker.model.Run
import com.netflix.spinnaker.igor.wercker.model.RunPayload
import com.netflix.spinnaker.igor.wercker.model.Workflow
import retrofit.client.Response
import retrofit.http.*

/**
 * Interface for interacting with a Wercker service using retrofit
 */
interface WerckerClient {

    /**
     * Get Applications for the given owner
     * @param owner - the application owner
     * @return
     */
    @GET('/api/v3/applications/{owner}')
    List<Application> getApplicationsByOwner(
        @Header('Authorization') String authHeader,
        @Path('owner') owner)

    @GET('/api/v3/applications?limit=300')
//    @GET('/api/applications')
    List<Application> getApplications(@Header('Authorization') String authHeader)
	
	@GET('/api/v3/applications?includePipelines=true&limit=300')
//    @GET('/api/applications')
	List<Application> getApplicationsWithPipelines(@Header('Authorization') String authHeader)

    @GET('/api/v3/runs')
    List<Run> getRunsForApplication(
        @Header('Authorization') String authHeader,
        @Query('applicationId') String applicationId)

    @GET('/api/v3/runs')
    List<Run> getRunsForPipeline(
        @Header('Authorization') String authHeader,
        @Query('pipelineId') String pipelineId)
	
	
	@GET('/api/v3/allruns')
	List<Run> getRunsSince(
		@Header('Authorization') String authHeader,
		@Query('since') long since)

    @GET('/api/v3/workflows')
    List<Workflow> getWorkflowsForApplication(
        @Header('Authorization') String authHeader,
        @Query('applicationId') String applicationId)

    @GET('/api/v3/applications/{username}/{appName}/pipelines')
    List<Pipeline> getPipelinesForApplication(
        @Header('Authorization') String authHeader,
        @Path('username') username,
        @Path('appName') appName
    )

    @POST('/api/v3/runs')
    Map<String, Object> triggerBuild(
        @Header('Authorization') String authHeader,
        @Body RunPayload runPayload
    )

    @GET('/api/v3/runs/{runId}')
    Run getRunById(@Header('Authorization') String authHeader,
                             @Path('runId') String runId)

    @PUT('/api/v3/runs/{runId}/abort')
    Response abortRun(@Header('Authorization') String authHeader,
                      @Path('runId') String runId,
                      @Body Map body)
}
