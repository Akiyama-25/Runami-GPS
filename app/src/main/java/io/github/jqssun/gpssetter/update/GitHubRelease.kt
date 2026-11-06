package io.github.jqssun.gpssetter.update

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

class GitHubRelease {

    @SerializedName("id")
    @Expose
    var id: Long? = null

    @SerializedName("tag_name")
    @Expose
    var tagName: String? = null

    @SerializedName("name")
    @Expose
    var name: String? = null

    @SerializedName("prerelease")
    @Expose
    var prerelease: Boolean? = null

    @SerializedName("published_at")
    @Expose
    var publishedAt: String? = null

    @SerializedName("assets")
    @Expose
    var assets: List<Asset>? = null

    @SerializedName("body")
    @Expose
    var body: String? = null

    class Asset {

        @SerializedName("id")
        @Expose
        var id: Long? = null

        @SerializedName("name")
        @Expose
        var name: String? = null

        @SerializedName("browser_download_url")
        @Expose
        var browserDownloadUrl: String? = null
    }
}

class GitHubWorkflowRunsResponse {

    @SerializedName("workflow_runs")
    @Expose
    var workflowRuns: List<WorkflowRun>? = null

    class WorkflowRun {

        @SerializedName("id")
        @Expose
        var id: Long? = null

        @SerializedName("name")
        @Expose
        var name: String? = null

        @SerializedName("run_number")
        @Expose
        var runNumber: Int? = null

        @SerializedName("head_sha")
        @Expose
        var headSha: String? = null

        @SerializedName("display_title")
        @Expose
        var displayTitle: String? = null

        @SerializedName("html_url")
        @Expose
        var htmlUrl: String? = null

        @SerializedName("updated_at")
        @Expose
        var updatedAt: String? = null
    }
}