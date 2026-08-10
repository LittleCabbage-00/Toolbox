package com.example.cryptoapp.Browser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将 WebView 捕获到的 HLS 播放列表和大量媒体分片归并成可操作的资源。
 * 同一条流只展示一次；下载时优先把 master/index.m3u8 交给 FFmpeg，由其按清单顺序合并全部分片。
 */
public final class MediaResourceAggregator {
    private static final Set<String> GENERIC_NAMES = new HashSet<>();
    private static final Set<String> QUALITY_NAMES = new HashSet<>();

    static {
        Collections.addAll(GENERIC_NAMES, "master", "index", "playlist", "manifest", "media",
                "video", "stream", "chunklist", "prog_index", "live");
        Collections.addAll(QUALITY_NAMES, "144", "240", "360", "480", "540", "576", "720",
                "1080", "1440", "2160", "4320", "144p", "240p", "360p", "480p", "540p",
                "576p", "720p", "1080p", "1440p", "2160p", "4320p");
    }

    private MediaResourceAggregator() {}

    public static List<Resource> aggregate(Collection<String> source) {
        Map<String, HlsGroup> hlsGroups = new LinkedHashMap<>();
        Map<String, Resource> direct = new LinkedHashMap<>();
        for (String url : source) {
            if (url == null || url.trim().isEmpty()) continue;
            if (isHlsManifest(url) || isHlsSegment(url)) {
                String key = streamKey(url);
                HlsGroup group = hlsGroups.computeIfAbsent(key, ignored -> new HlsGroup());
                if (isHlsManifest(url)) group.addManifest(url);
                else group.segments.add(url);
            } else {
                direct.putIfAbsent(url, new Resource(url, false, 0, 0, "媒体文件"));
            }
        }

        List<Resource> result = new ArrayList<>();
        for (HlsGroup group : hlsGroups.values()) {
            String manifest = group.bestManifest;
            if (manifest != null) {
                result.add(new Resource(manifest, true, group.segments.size(), group.manifestCount,
                        "HLS 视频流"));
            } else if (!group.segments.isEmpty()) {
                // 没捕获到播放列表时仍合并成一个提示项，避免数百个分片淹没界面。
                result.add(new Resource(null, true, group.segments.size(), 0,
                        "HLS 分片组（等待播放列表）"));
            }
        }
        result.sort(Comparator
                .comparing((Resource value) -> !value.hls)
                .thenComparing(value -> !value.isReady())
                .thenComparing((Resource value) -> value.segmentCount, Comparator.reverseOrder()));
        result.addAll(direct.values());
        return result;
    }

    public static boolean isHlsManifest(String url) {
        String path = cleanPath(url);
        return path.endsWith(".m3u8") || path.contains(".m3u8/");
    }

    public static boolean isHlsSegment(String url) {
        String path = cleanPath(url);
        return path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".cmfv")
                || path.endsWith(".cmfa") || path.endsWith(".m2ts");
    }

    private static String cleanPath(String url) {
        try {
            String path = new URI(url).getPath();
            return path == null ? "" : path.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            int query = url.indexOf('?');
            return (query >= 0 ? url.substring(0, query) : url).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 为播放器会话生成稳定流标识。算法从路径末端寻找内容 ID，跳过清晰度、时间戳、
     * CDN 临时 token 和 master/index 等通用名称，因此签名刷新或播放分片增长不会产生新条目。
     */
    public static String streamKey(String url) {
        try {
            URI uri = new URI(url);
            String authority = (uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT))
                    + "://" + (uri.getAuthority() == null ? "" : uri.getAuthority().toLowerCase(Locale.ROOT));
            String path = uri.getPath() == null ? "" : uri.getPath();
            String[] raw = path.split("/");
            List<String> parts = new ArrayList<>();
            for (String part : raw) if (!part.isEmpty()) parts.add(part);
            if (parts.isEmpty()) return authority;

            String file = parts.get(parts.size() - 1);
            String stem = stripExtension(file).toLowerCase(Locale.ROOT);
            if (isHlsManifest(url)) {
                stem = stripVariant(stem);
                if (isStableIdentity(stem)) return authority + "|media:" + stem;
            }

            // 分片使用所在内容目录；通用清单也从父目录向上寻找稳定内容 ID。
            for (int index = parts.size() - 2; index >= 0; index--) {
                String candidate = parts.get(index).toLowerCase(Locale.ROOT);
                if (isStableIdentity(candidate)) return authority + "|media:" + candidate;
            }
            return authority + "|path:" + (parts.size() > 1 ? parts.get(parts.size() - 2) : stem);
        } catch (Exception ignored) {
            String clean = url.split("\\?", 2)[0];
            int slash = clean.lastIndexOf('/');
            return slash > 0 ? clean.substring(0, slash) : clean;
        }
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String stripVariant(String value) {
        return value.replaceFirst("(?i)(?:[-_](?:144|240|360|480|540|576|720|1080|1440|2160|4320)p?)$", "");
    }

    private static boolean isStableIdentity(String value) {
        if (value == null || value.length() < 2 || GENERIC_NAMES.contains(value)
                || QUALITY_NAMES.contains(value)) return false;
        if (value.matches("\\d{9,}")) return false; // 时间戳或过期时间
        if (value.matches("(?i)[0-9a-f]{8}-[0-9a-f-]{27,}")) return false;
        // 长随机串通常是 CDN 鉴权 token；带语义分隔符的普通 slug 不受影响。
        return value.length() < 20 || !value.matches("(?i)[a-z0-9_-]+");
    }

    private static int manifestScore(String url) {
        String path = cleanPath(url);
        int score = 0;
        if (path.contains("master")) score += 1000;
        if (path.contains("index")) score += 500;
        if (path.contains("playlist")) score += 300;
        // 同等情况下优先上层清单，它通常包含全部清晰度和音轨。
        score -= path.split("/").length * 5;
        return score;
    }

    private static final class HlsGroup {
        final List<String> segments = new ArrayList<>();
        String bestManifest;
        int bestScore = Integer.MIN_VALUE;
        int manifestCount;

        void addManifest(String url) {
            manifestCount++;
            int score = manifestScore(url);
            // 同分时选择最后捕获的 URL，避免继续使用已经过期的 CDN 签名。
            if (bestManifest == null || score >= bestScore) {
                bestManifest = url;
                bestScore = score;
            }
        }
    }

    public static final class Resource {
        public final String url;
        public final boolean hls;
        public final int segmentCount;
        public final int manifestCount;
        public final String typeLabel;

        Resource(String url, boolean hls, int segmentCount, int manifestCount, String typeLabel) {
            this.url = url;
            this.hls = hls;
            this.segmentCount = segmentCount;
            this.manifestCount = manifestCount;
            this.typeLabel = typeLabel;
        }

        public boolean isReady() { return url != null && !url.isEmpty(); }
    }
}
