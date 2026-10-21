package io.customer.sdk.core.network

import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.tls.CertificateFixtures.cleanLeaf
import io.customer.commontest.tls.CertificateFixtures.cleanRoot
import io.customer.commontest.tls.CertificateFixtures.compromisedIntermediate
import io.customer.commontest.tls.CertificateFixtures.emsignRoot
import io.customer.commontest.tls.CertificateFixtures.forgedLeaf
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

class CertificateDenylistTest : JUnit5Test() {

    @Test
    fun isBlocked_whenCertificateIsCompromisedEmudhraIntermediate_thenReturnsTrue() {
        CertificateDenylist.isBlocked(compromisedIntermediate).shouldBeTrue()
    }

    @Test
    fun isBlocked_whenCertificateIsEmudhraRoot_thenReturnsFalse() {
        // Only the leaked intermediates are listed. The root's own key was not compromised.
        CertificateDenylist.isBlocked(emsignRoot).shouldBeFalse()
    }

    @Test
    fun isBlocked_whenCertificateIsUnrelated_thenReturnsFalse() {
        CertificateDenylist.isBlocked(cleanRoot).shouldBeFalse()
        CertificateDenylist.isBlocked(cleanLeaf).shouldBeFalse()
        CertificateDenylist.isBlocked(forgedLeaf).shouldBeFalse()
    }

    @Test
    fun containsBlocked_whenChainIncludesCompromisedIntermediate_thenReturnsTrue() {
        CertificateDenylist.containsBlocked(arrayOf(forgedLeaf, compromisedIntermediate)).shouldBeTrue()
    }

    @Test
    fun containsBlocked_whenChainIsClean_thenReturnsFalse() {
        CertificateDenylist.containsBlocked(arrayOf(cleanLeaf, cleanRoot)).shouldBeFalse()
    }

    @Test
    fun containsBlocked_whenChainIsEmpty_thenReturnsFalse() {
        CertificateDenylist.containsBlocked(emptyArray()).shouldBeFalse()
    }
}
