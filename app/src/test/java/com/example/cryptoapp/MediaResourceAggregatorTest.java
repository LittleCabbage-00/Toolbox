package com.example.cryptoapp;

import com.example.cryptoapp.Browser.MediaResourceAggregator;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaResourceAggregatorTest {
    @Test public void combinesManifestAndSegmentsIntoOneHlsResource() {
        List<MediaResourceAggregator.Resource> result = MediaResourceAggregator.aggregate(Arrays.asList(
                "https://cdn.example/hls/movie-1/720/0001.ts",
                "https://cdn.example/hls/movie-1/720/0002.ts",
                "https://cdn.example/hls/movie-1/720/index.m3u8",
                "https://cdn.example/hls/movie-1/master.m3u8"));

        assertEquals(1, result.size());
        assertTrue(result.get(0).hls);
        assertEquals(2, result.get(0).segmentCount);
        assertEquals("https://cdn.example/hls/movie-1/master.m3u8", result.get(0).url);
    }

    @Test public void keepsDifferentVideosAndDirectFilesSeparate() {
        List<MediaResourceAggregator.Resource> result = MediaResourceAggregator.aggregate(Arrays.asList(
                "https://cdn.example/hls/movie-1/master.m3u8",
                "https://cdn.example/hls/movie-2/master.m3u8",
                "https://cdn.example/files/trailer.mp4"));

        assertEquals(3, result.size());
        assertFalse(result.get(2).hls);
    }

    @Test public void orphanSegmentsBecomeOneWaitingGroup() {
        List<MediaResourceAggregator.Resource> result = MediaResourceAggregator.aggregate(Arrays.asList(
                "https://cdn.example/hls/movie-1/001.ts",
                "https://cdn.example/hls/movie-1/002.ts"));

        assertEquals(1, result.size());
        assertFalse(result.get(0).isReady());
        assertEquals(2, result.get(0).segmentCount);
    }

    @Test public void rotatingCdnTokensAndExpiryStillBecomeOneVideo() {
        List<MediaResourceAggregator.Resource> result = MediaResourceAggregator.aggregate(Arrays.asList(
                "https://cdn.example/hls/OldToken_123456789012/1786300000/61000/61129/61129.m3u8?sig=old",
                "https://cdn.example/hls/NewToken_987654321098/1786303600/61000/61129/611290.ts",
                "https://cdn.example/hls/NewToken_987654321098/1786303600/61000/61129/61129.m3u8?sig=new"));

        assertEquals(1, result.size());
        assertEquals("https://cdn.example/hls/NewToken_987654321098/1786303600/61000/61129/61129.m3u8?sig=new",
                result.get(0).url);
    }

    @Test public void qualityDirectoriesShareTheSameStreamKey() {
        assertEquals(
                MediaResourceAggregator.streamKey("https://cdn.example/hls/movie-1/master.m3u8"),
                MediaResourceAggregator.streamKey("https://cdn.example/hls/movie-1/720/index.m3u8?token=2"));
        assertEquals(
                MediaResourceAggregator.streamKey("https://cdn.example/hls/movie-1/master.m3u8"),
                MediaResourceAggregator.streamKey("https://cdn.example/hls/movie-1/1080/00001.ts"));
    }
}
