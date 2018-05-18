/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.igor.wercker

import com.netflix.spinnaker.igor.wercker.model.Application
import com.netflix.spinnaker.igor.wercker.model.Pipeline
import com.netflix.spinnaker.igor.wercker.model.Run
import com.netflix.spinnaker.igor.wercker.model.Workflow
import retrofit.http.GET
import retrofit.http.Header
import retrofit.http.Path
import retrofit.http.Query

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

    @GET('/api/applications')
    List<Application> getApplications(@Header('Authorization') String authHeader)

    @GET('/api/v3/runs')
    List<Run> getRunsForApplication(
        @Header('Authorization') String authHeader,
        @Query('applicationId') String applicationId)

    @GET('/api/v3/runs')
    List<Run> getRunsForPipeline(
        @Header('Authorization') String authHeader,
        @Query('pipelineId') String pipelineId)

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

}
