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
import com.netflix.spinnaker.igor.wercker.model.Run
import retrofit.http.GET
import retrofit.http.Path
import retrofit.http.Query

/**
 * Interface for interacting with a Wercker service using retrofit
 */
interface WerckerClient {

    /**
     * Get all Applications for the given owner
     * @param owner - the application owner
     * @return
     */
    @GET('/api/v3/applications/{owner}')
    List<Application> getApplicationsByOwner(@Path('owner') owner)

    @GET('/api/v3/runs?applicationId={applicationId}')
    List<Run> getRunsForApplication(@Query('applicationId') String applicationId)

    @GET('/api/v3/runs?pipelineId={pipelineId}')
    List<Run> getRunsForPipeline(@Query('pipelineId') String pipelineId)

}
