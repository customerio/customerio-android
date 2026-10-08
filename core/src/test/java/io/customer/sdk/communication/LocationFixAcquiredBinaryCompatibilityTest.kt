package io.customer.sdk.communication

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test

/**
 * The JVM shape a module compiled against the three-property [Event.LocationFixAcquired] links
 * against, since core, location and geofence can ship at different versions. Driven through those
 * descriptors rather than source, which would bind to whichever overload exists now. Each case also
 * pins that accuracy a newer producer set survives what an older caller does with the event.
 */
class LocationFixAcquiredBinaryCompatibilityTest {
    private val eventClass = Class.forName("io.customer.sdk.communication.Event\$LocationFixAcquired")
    private val defaultConstructorMarker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
    private val double = Double::class.javaPrimitiveType!!
    private val int = Int::class.javaPrimitiveType!!
    private val boxedLong = Long::class.javaObjectType
    private val boxedFloat = Float::class.javaObjectType

    @Test
    fun legacyConstructor_expectFieldsCarriedAndAccuracyUnknown() {
        val event = eventClass.getDeclaredConstructor(double, double, boxedLong).newInstance(1.0, 2.0, 3L)

        event.call("getLatitude") shouldBeEqualTo 1.0
        event.call("getLongitude") shouldBeEqualTo 2.0
        event.call("getFixElapsedRealtimeMillis") shouldBeEqualTo 3L
        event.call("getHorizontalAccuracyMeters").shouldBeNull()
    }

    @Test
    fun legacyDefaultingConstructor_givenFixTimeOmitted_expectTimeAndAccuracyUnknown() {
        // Bit 2 set: how an old `LocationFixAcquired(lat, lng)` call compiles.
        val event = eventClass
            .getDeclaredConstructor(double, double, boxedLong, int, defaultConstructorMarker)
            .newInstance(1.0, 2.0, null, 0b100, null)

        event.call("getLatitude") shouldBeEqualTo 1.0
        event.call("getFixElapsedRealtimeMillis").shouldBeNull()
        event.call("getHorizontalAccuracyMeters").shouldBeNull()
    }

    @Test
    fun legacyCopy_expectNewValuesAndAccuracyKept() {
        val copy = eventClass.getDeclaredMethod("copy", double, double, boxedLong)
            .invoke(accurateEvent(), 5.0, 6.0, 7L)!!

        copy.call("getLatitude") shouldBeEqualTo 5.0
        copy.call("getLongitude") shouldBeEqualTo 6.0
        copy.call("getFixElapsedRealtimeMillis") shouldBeEqualTo 7L
        copy.call("getHorizontalAccuracyMeters") shouldBeEqualTo 12f
    }

    @Test
    fun legacyDefaultingCopy_givenOnlyLongitude_expectRestAndAccuracyKept() {
        // Bits 0 and 2 set: how an old `event.copy(longitude = 6.0)` call compiles.
        val copy = eventClass
            .getDeclaredMethod("copy\$default", eventClass, double, double, boxedLong, int, Any::class.java)
            .invoke(null, accurateEvent(), 0.0, 6.0, null, 0b101, null)!!

        copy.call("getLatitude") shouldBeEqualTo 1.0
        copy.call("getLongitude") shouldBeEqualTo 6.0
        copy.call("getFixElapsedRealtimeMillis") shouldBeEqualTo 3L
        copy.call("getHorizontalAccuracyMeters") shouldBeEqualTo 12f
    }

    @Test
    fun legacyComponents_expectPositionsUnchangedAndAccuracyAppended() {
        val event = accurateEvent()

        event.call("component1") shouldBeEqualTo 1.0
        event.call("component2") shouldBeEqualTo 2.0
        event.call("component3") shouldBeEqualTo 3L
        event.call("component4") shouldBeEqualTo 12f
    }

    private fun accurateEvent(): Any =
        eventClass.getDeclaredConstructor(double, double, boxedLong, boxedFloat).newInstance(1.0, 2.0, 3L, 12f)

    private fun Any.call(name: String): Any? = eventClass.getDeclaredMethod(name).invoke(this)
}
