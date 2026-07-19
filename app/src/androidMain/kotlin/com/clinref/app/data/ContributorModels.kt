package com.clinref.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ContributorGroup(
    val contributorType: String = "",
    val headingTitle: String = "",
    val contributorList: List<Contributor> = emptyList()
)

@Serializable
data class Contributor(
    val contributorId: String = "",
    val name: String = "",
    val associations: List<String> = emptyList(),
    val disclosure: String = "",
    val id: String = "",
    val type: String = "",
    val hideInResults: Boolean = false
)
