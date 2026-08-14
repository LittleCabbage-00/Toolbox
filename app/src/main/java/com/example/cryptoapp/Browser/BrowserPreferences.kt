package com.example.cryptoapp.Browser

/** 浏览器和主页共享的配置键，避免不同页面各自拼写字符串造成配置失效。 */
object BrowserPreferences {
    const val CONFIG = "config"
    const val HOME_URL = "home_url"
    const val SEARCH_METHOD = "search_method"
    const val METHOD_NAME = "method_name"
    const val METHOD_INDEX = "method_num"
    const val BING_WALLPAPER = "bing_pic_check"
    const val BING_SAVE_AUTO = "bing_save_auto"
    const val BING_SAVE_PORTRAIT = "bing_save_portrait"
    const val SAVE_HISTORY = "save_browser_history"
    const val SHOW_SEARCH_HISTORY = "show_search_history"
    const val SHOW_BOOKMARK_BAR = "show_bookmark_bar"
    const val JAVASCRIPT_ENABLED = "javascript_enabled"
    const val BLOCK_THIRD_PARTY_COOKIES = "block_third_party_cookies"
    const val BLOCK_WEB_ADS = "block_web_ads"
    const val AD_BLOCK_UPDATED_AT = "ad_block_updated_at"
    const val THEME_MODE = "theme_mode"
    const val SNIFF_VIDEO_FORMAT = "sniff_video_format"
    const val SNIFF_AUDIO_FORMAT = "sniff_audio_format"
}
