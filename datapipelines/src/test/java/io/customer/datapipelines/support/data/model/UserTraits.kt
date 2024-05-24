package io.customer.datapipelines.support.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable data class representing user traits.
 * The class is helpful for testing purposes as it can be serialized and can be used as user traits.
 */
@Serializable
data class UserTraits(
    @SerialName("first_name")
    val firstName: String,
    val ageInYears: Int
)
