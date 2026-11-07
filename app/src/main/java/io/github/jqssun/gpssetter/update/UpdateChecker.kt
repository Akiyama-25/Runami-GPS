package io.github.jqssun.gpssetter.update

import android.content.Context
import android.os.Parcelable
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File
import javax.inject.Inject

class UpdateChecker @Inject constructor(private val apiResponse: GitHubService) {

    companion object {
        const val REPO_RELEASES_URL = "https://github.com/Akiyama-25/Runami-GPS/releases"
        const val DEFAULT_APK_NAME = "app-release.apk"
    }

    fun getLatestRelease() = callbackFlow {
        withContext(Dispatchers.IO) {
            val channel = PrefManager.updateChannel
            if (channel == "action") {
                val update = checkActionChannelUpdate()
                trySend(update)
            } else {
                val update = checkReleaseChannelUpdate()
                trySend(update)
            }
        }
        awaitClose { }
    }

    private fun checkReleaseChannelUpdate(): Update? {
        val release = getReleaseList() ?: getAllReleasesList()?.firstOrNull {
            val tag = it.tagName.orEmpty()
            !tag.startsWith("Action-", ignoreCase = true) && it.prerelease != true
        } ?: return null

        val currentTag = release.tagName ?: return null
        if (currentTag == BuildConfig.TAG_NAME || currentTag == "v${BuildConfig.TAG_NAME}") {
            return null
        }

        return buildUpdateFromRelease(release)
    }

    private fun checkActionChannelUpdate(): Update? {
        val allReleases = getAllReleasesList().orEmpty()
        val actionRelease = allReleases.firstOrNull {
            it.tagName?.startsWith("Action-", ignoreCase = true) == true
        }

        if (actionRelease != null) {
            val remoteTag = actionRelease.tagName ?: return null
            if (isNewerActionVersion(remoteTag, BuildConfig.TAG_NAME)) {
                return buildUpdateFromRelease(actionRelease)
            }
            return null
        }

        val latestRun = getWorkflowRunsList()?.firstOrNull() ?: return null
        val runNumber = latestRun.runNumber ?: return null
        val remoteActionTag = "Action-$runNumber"
        if (!isNewerActionVersion(remoteActionTag, BuildConfig.TAG_NAME)) {
            return null
        }

        val shortSha = latestRun.headSha?.take(7) ?: ""
        val changelog = buildString {
            append("Channel: Action ($remoteActionTag)\n")
            if (!latestRun.displayTitle.isNullOrBlank()) {
                append("Commit: ${latestRun.displayTitle}\n")
            }
            if (shortSha.isNotEmpty()) {
                append("SHA: $shortSha")
            }
        }
        val assetFileName = "Runami-GPS-$remoteActionTag.apk"
        val downloadUrl = "$REPO_RELEASES_URL/download/$remoteActionTag/$assetFileName"
        return Update(
            name = "Runami GPS $remoteActionTag",
            changelog = changelog,
            timestamp = latestRun.updatedAt ?: "",
            assetUrl = downloadUrl,
            assetName = assetFileName,
            releaseUrl = latestRun.htmlUrl ?: REPO_RELEASES_URL
        )
    }

    private fun isNewerActionVersion(remoteTag: String, currentTag: String): Boolean {
        if (remoteTag.equals(currentTag, ignoreCase = true)) {
            return false
        }
        val remoteNum = remoteTag.removePrefix("Action-").removePrefix("action-").toIntOrNull()
        val currentNum = currentTag.removePrefix("Action-").removePrefix("action-").toIntOrNull()
        if (remoteNum != null && currentNum != null) {
            return remoteNum > currentNum
        }
        return true
    }

    private fun buildUpdateFromRelease(gitHubReleaseResponse: GitHubRelease): Update? {
        val currentTag = gitHubReleaseResponse.tagName ?: return null
        val asset = gitHubReleaseResponse.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
        val releaseUrl = asset?.browserDownloadUrl
            ?.replace("/download/", "/tag/")
            ?.let { url ->
                val lastSlash = url.lastIndexOf("/")
                if (lastSlash > 0) url.substring(0, lastSlash) else url
            } ?: "$REPO_RELEASES_URL/tag/$currentTag"

        val name = gitHubReleaseResponse.name?.takeIf { it.isNotBlank() } ?: currentTag
        val body = gitHubReleaseResponse.body?.takeIf { it.isNotBlank() } ?: "Update $currentTag available."
        val publishedAt = gitHubReleaseResponse.publishedAt ?: ""
        val fallbackAssetName = "Runami-GPS-$currentTag.apk"
        val downloadUrl = asset?.browserDownloadUrl
            ?: "$REPO_RELEASES_URL/download/$currentTag/$fallbackAssetName"

        return Update(
            name = name,
            changelog = body,
            timestamp = publishedAt,
            assetUrl = downloadUrl,
            assetName = asset?.name ?: fallbackAssetName,
            releaseUrl = releaseUrl
        )
    }

    private fun getReleaseList(): GitHubRelease? {
        return runCatching {
            apiResponse.getReleases().execute().body()
        }.getOrNull()
    }

    private fun getAllReleasesList(): List<GitHubRelease>? {
        return runCatching {
            apiResponse.getAllReleases().execute().body()
        }.getOrNull()
    }

    private fun getWorkflowRunsList(): List<GitHubWorkflowRunsResponse.WorkflowRun>? {
        return runCatching {
            apiResponse.getWorkflowRuns().execute().body()?.workflowRuns
        }.getOrNull()
    }

    fun clearCachedDownloads(context: Context) {
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    @Parcelize
    data class Update(
        val name: String,
        val changelog: String,
        val timestamp: String,
        val assetUrl: String,
        val assetName: String,
        val releaseUrl: String
    ) : Parcelable
}
