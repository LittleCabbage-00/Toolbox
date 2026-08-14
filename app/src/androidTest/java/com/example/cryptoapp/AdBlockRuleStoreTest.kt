package com.example.cryptoapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cryptoapp.Browser.AdBlockRuleStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证发布包中的默认规则可被加载，并只对广告域名返回拦截结果。 */
@RunWith(AndroidJUnit4::class)
class AdBlockRuleStoreTest {
    @Test fun bundledRulesBlockKnownAdDomainWithoutBlockingNormalSite() {
        val store = AdBlockRuleStore.get(InstrumentationRegistry.getInstrumentation().targetContext)
        store.setEnabled(true)
        assertTrue(store.status().count > 1_000)
        assertTrue(store.shouldBlock("https://googleads.g.doubleclick.net/pagead/ads"))
        assertFalse(store.shouldBlock("https://www.example.com/article"))
    }
}
