package de.moleman1024.audiowagon

import org.junit.Assert.assertEquals
import org.junit.Test

class UtilTest {

    @Test
    fun sanitizeYear_yearWithSlash_sanitizesString() {
        assertEquals("2008", Util.sanitizeYear("2008 / 2014"))
    }

    @Test
    fun sanitizeYear_timestamp_sanitizesString() {
        assertEquals("2014", Util.sanitizeYear("2014-06-20T07:00:00Z"))
    }

}
