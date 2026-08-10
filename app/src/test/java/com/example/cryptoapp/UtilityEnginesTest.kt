package com.example.cryptoapp

import com.example.cryptoapp.Utils.DataToolsEngine
import com.example.cryptoapp.Utils.UnitConverterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 新增离线工具的核心逻辑回归测试。 */
class UtilityEnginesTest {
    @Test fun base64AndHexRoundTrip() {
        val value = "Toolbox 你好"
        val base64 = DataToolsEngine.transform(DataToolsEngine.Operation.BASE64_ENCODE, value)
        assertEquals(value, DataToolsEngine.transform(DataToolsEngine.Operation.BASE64_DECODE, base64))
        val hex = DataToolsEngine.transform(DataToolsEngine.Operation.HEX_ENCODE, value)
        assertEquals(value, DataToolsEngine.transform(DataToolsEngine.Operation.HEX_DECODE, hex))
    }

    @Test fun timestampUsesSecondsOrMilliseconds() {
        assertEquals("1970-01-01 00:00:01", DataToolsEngine.transform(
            DataToolsEngine.Operation.TIMESTAMP_TO_DATE, "1", ZoneOffset.UTC))
        assertEquals("1000", DataToolsEngine.transform(
            DataToolsEngine.Operation.DATE_TO_TIMESTAMP, "1970-01-01 00:00:01", ZoneOffset.UTC))
    }

    @Test fun generatedPasswordUsesRequestedLength() {
        val password = DataToolsEngine.transform(DataToolsEngine.Operation.PASSWORD_GENERATE, "32")
        assertEquals(32, password.length)
        assertTrue(password.toSet().size > 8)
    }

    @Test fun commonUnitConversionsAreCorrect() {
        assertEquals("1", UnitConverterEngine.convert(0, 3, 2, .001)) // 0.001 km = 1 m
        assertEquals("32", UnitConverterEngine.convert(2, 0, 1, 0.0)) // 0 ℃ = 32 ℉
        assertEquals("1024", UnitConverterEngine.convert(3, 2, 1, 1.0)) // 1 MB = 1024 KB
    }
}
