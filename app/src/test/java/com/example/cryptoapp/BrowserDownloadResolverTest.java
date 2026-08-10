package com.example.cryptoapp;

import com.example.cryptoapp.Browser.BrowserDownloadResolver;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BrowserDownloadResolverTest {
    @Test public void infersModernImageExtensionFromMime() {
        var info = BrowserDownloadResolver.resolve(
                "https://images.example.test/render?id=42", "", "image/avif; charset=binary");
        assertTrue(info.getFileName().endsWith(".avif"));
        assertEquals("image/avif", info.getMimeType());
    }

    @Test public void decodesUtf8ContentDispositionAndAppendsExtension() {
        var info = BrowserDownloadResolver.resolve(
                "https://example.com/download", "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A", "application/pdf");
        assertEquals("报告.pdf", info.getFileName());
        assertEquals("application/pdf", info.getMimeType());
    }

    @Test public void infersMimeTypeFromUrlExtension() {
        var info = BrowserDownloadResolver.resolve(
                "https://example.com/files/archive.zip?token=1", null, "application/octet-stream");
        assertEquals("archive.zip", info.getFileName());
        assertEquals("application/zip", info.getMimeType());
    }

    @Test public void sanitizesUnsafeServerFileName() {
        var info = BrowserDownloadResolver.resolve(
                "https://example.com/file", "attachment; filename=bad/name?.txt", "text/plain; charset=utf-8");
        assertEquals("bad_name_.txt", info.getFileName());
        assertEquals("text/plain", info.getMimeType());
    }
}
