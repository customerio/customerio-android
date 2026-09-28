package io.customer.geofence.replay

import java.io.File

/**
 * Writes a temporary scenario file. Fixture geometry in this file is synthetic (around latitude 10,
 * longitude 20): the SDK decides on distances, not places.
 */
internal fun scenarioFile(vararg lines: String): File {
    val file = File.createTempFile("scenario", ".scenario.ndjson")
    file.deleteOnExit()
    file.writeText(lines.joinToString("\n") + "\n")
    return file
}

internal fun header(name: String, platform: String = "android", sourceKind: String? = null) =
    """{"k":"scenario","v":1,"name":"$name","platform":"$platform","sdk":"test","device":"synthetic"""" +
        (sourceKind?.let { ""","source":{"kind":"$it"}""" } ?: "") + "}"

/** A fence at the device's position, so containment is unambiguous. */
internal fun fenceAtDevice(id: String) =
    """{"id":"$id","name":"Here","geosetIds":["7"],"latitude":10.00000,"longitude":20.00000,"radius":250.0,"transitionTypes":[]}"""

/** ~5 km north of the device: registered, but nowhere near it. */
internal fun fenceFarAway(id: String) =
    """{"id":"$id","name":"Away","geosetIds":["7"],"latitude":10.04510,"longitude":20.00000,"radius":250.0,"transitionTypes":[]}"""
