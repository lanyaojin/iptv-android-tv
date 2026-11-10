package com.ying.iptv.data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 容错的 M3U / M3U8 频道列表解析器。
 *
 * 支持 #EXTINF 属性（tvg-id / tvg-name / tvg-logo / group-title）、#EXTGRP 分组、
 * 带引号内含逗号的频道名、CRLF、BOM；忽略 #EXTVLCOPT、#KODIPROP 等指令。
 */
public final class M3uParser {

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"");

    private M3uParser() {
    }

    public static Playlist parse(String text) {
        List<Channel> channels = new ArrayList<>();
        if (text == null) return new Playlist(channels);
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        String pendingGroup = null;
        String pendingLogo = null;
        String pendingTvgId = null;
        String pendingName = null;
        boolean awaitingUrl = false;

        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTINF")) {
                ExtInfo info = parseExtInf(line);
                pendingName = info.name;
                pendingLogo = info.logo;
                pendingTvgId = info.tvgId;
                // #EXTGRP 已经给出的分组优先保留，除非 #EXTINF 显式声明了 group-title
                if (info.group != null) pendingGroup = info.group;
                awaitingUrl = true;
                continue;
            }

            if (line.startsWith("#EXTGRP:")) {
                String g = line.substring(8).trim();
                pendingGroup = g.isEmpty() ? null : g;
                continue;
            }

            if (line.startsWith("#")) continue; // 其它指令忽略

            if (!awaitingUrl) {
                // 没有 #EXTINF 的裸地址也当作频道，避免整份源丢失
                if (isPlayable(line)) {
                    channels.add(new Channel(channels.size() + 1, "频道 " + (channels.size() + 1), line, null, null, null));
                }
                continue;
            }

            if (isPlayable(line)) {
                String name = (pendingName == null || pendingName.isEmpty())
                        ? "频道 " + (channels.size() + 1)
                        : pendingName;
                channels.add(new Channel(channels.size() + 1, name, line, pendingGroup, pendingLogo, pendingTvgId));
            }

            awaitingUrl = false;
            pendingName = null;
            pendingLogo = null;
            pendingTvgId = null;
            pendingGroup = null;
        }

        return new Playlist(channels);
    }

    private static boolean isPlayable(String line) {
        if (line.startsWith("#")) return false;
        String lower = line.toLowerCase();
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("rtsp://")
                || lower.startsWith("rtmp://")
                || lower.startsWith("udp://")
                || lower.startsWith("rtp://")
                || lower.startsWith("file://");
    }

    private static ExtInfo parseExtInf(String line) {
        int comma = firstCommaOutsideQuotes(line);
        String meta = comma > 0 ? line.substring(0, comma) : line;
        String name = comma > 0 ? line.substring(comma + 1).trim() : "";

        String group = null, logo = null, tvgId = null, tvgName = null;
        Matcher m = ATTR.matcher(meta);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String value = m.group(2).trim();
            if (value.isEmpty()) continue;
            switch (key) {
                case "group-title":
                    group = value;
                    break;
                case "tvg-logo":
                    logo = value;
                    break;
                case "tvg-id":
                    tvgId = value;
                    break;
                case "tvg-name":
                    tvgName = value;
                    break;
                default:
                    break;
            }
        }
        if (name.isEmpty()) name = tvgName != null ? tvgName : "";
        return new ExtInfo(name, group, logo, tvgId);
    }

    /** 找到引号外的第一个逗号：属性区与频道名的分隔符（频道名可再含逗号）。 */
    private static int firstCommaOutsideQuotes(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) return i;
        }
        return -1;
    }

    private static final class ExtInfo {
        final String name, group, logo, tvgId;

        ExtInfo(String name, String group, String logo, String tvgId) {
            this.name = name;
            this.group = group;
            this.logo = logo;
            this.tvgId = tvgId;
        }
    }
}
