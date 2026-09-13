package dev.wakilabs.wakidownload

import dev.wakilabs.wakidownload.cloud.OneDrive
import org.junit.Assert.assertEquals
import org.junit.Test

class OneDriveTest {
    /** RFC 7636 appendix B test vector. */
    @Test fun pkceChallengeMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OneDrive.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
}
