package com.azdo.dashboard

import org.json.JSONObject

data class Project(val id: String, val name: String)
data class Definition(val id: Int, val name: String, val path: String)
data class Run(val id: Int, val number: String, val status: String, val result: String, val branch: String, val time: String, val uri: String = "") {
    val summary get() = "$status · ${result.ifBlank { "pending" }} · $branch"
}
data class TestResult(val name: String, val outcome: String, val duration: String)
data class Artifact(val name: String, val type: String, val url: String)
data class Report(val name: String, val text: String, val html: Boolean)
data class Account(val organization: String, val pat: String)

fun JSONObject.project() = Project(getString("id"), getString("name"))
private fun JSONObject.text(name: String) = if (isNull(name)) "" else optString(name)
fun JSONObject.definition() = Definition(getInt("id"), getString("name"), text("path"))
fun JSONObject.run() = Run(getInt("id"), text("buildNumber"), text("status"),
    text("result"), text("sourceBranch"), text("finishTime").ifBlank { text("queueTime") }, text("uri"))
fun JSONObject.testResult() = TestResult(text("testCaseTitle").ifBlank { text("automatedTestName") },
    text("outcome"), text("durationInMs"))
fun JSONObject.artifact(): Artifact {
    val resource = optJSONObject("resource") ?: JSONObject()
    return Artifact(text("name"), resource.text("type"), resource.text("downloadUrl"))
}
