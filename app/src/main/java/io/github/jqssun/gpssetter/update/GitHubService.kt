package io.github.jqssun.gpssetter.update

import retrofit2.Call
import retrofit2.http.GET

interface GitHubService {

    @GET("releases/latest")
    fun getReleases(): Call<GitHubRelease>

    @GET("releases")
    fun getAllReleases(): Call<List<GitHubRelease>>

    @GET("actions/runs?status=success&per_page=10")
    fun getWorkflowRuns(): Call<GitHubWorkflowRunsResponse>

}
