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

        return new Playlist(reclassifyGroups(normalizeGroups(mergeDuplicates(channels))));
    }

    /**
     * 按频道名纠正分组（服务端分组不准：央视频源把大量卫视/CGTN 混进央视频组）。
     * 在频道合并与分组归一之后执行，线路 label 不受影响。
     * 规则（按优先级）：
     *   含"卫视" → 卫视；CGTN 开头 → 国际；CCTV 开头或含"央视" → 央视；
     *   CETV 开头 → 教育；国学频道 → 文旅；其余保持原分组。
     */
    private static List<Channel> reclassifyGroups(List<Channel> channels) {
        List<Channel> out = new ArrayList<>(channels.size());
        for (Channel c : channels) {
            String group = classifyGroup(c);
            if (group != null && !group.equals(c.group)) {
                out.add(new Channel(c.number, c.name, c.lines, group, c.logo, c.tvgId));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    private static String classifyGroup(Channel c) {
        String name = c.name == null ? "" : c.name;
        if (name.contains("卫视")) return "卫视";
        if (name.startsWith("CGTN")) return "国际";
        if (name.startsWith("CCTV") || name.contains("央视")) return "央视";
        if (name.startsWith("CETV")) return "教育";
        if ("国学频道".equals(name)) return "文旅";
        return null;
    }

    /**
     * 分组归一：把"央视频"并入"央视"分组（用户指定）。
     * 在频道合并之后执行，因此线路 label 仍保留原始分组名（央视频/央视），
     * 线路选择器里两条线依然能区分；只有分组列表里的归属被统一为"央视"。
     */
    private static List<Channel> normalizeGroups(List<Channel> channels) {
        List<Channel> out = new ArrayList<>(channels.size());
        for (Channel c : channels) {
            if ("央视频".equals(c.group)) {
                out.add(new Channel(c.number, c.name, c.lines, "央视", c.logo, c.tvgId));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 按 tvg-id（无 tvg-id 时按归一化频道名）合并同一频道的多条线路。
     * 合并后取首次出现的 number、name、logo、group；lines 按首次出现顺序，
     * label 取该条的 group（group 为空时用"线路"+序号）；同组内 label 重复时，
     * 后出现的 label 追加"（"+该条 name+"）"以区分（仅当该条 name 与合并后 name 不同）。
     * 合并后重编号，保证 number 从 1 起连续。
     */
    private static List<Channel> mergeDuplicates(List<Channel> channels) {
        List<Channel> merged = new ArrayList<>();
        java.util.LinkedHashMap<String, Integer> indexByKey = new java.util.LinkedHashMap<>();
        for (Channel c : channels) {
            String key = mergeKey(c);
            Integer idx = indexByKey.get(key);
            if (idx == null) {
                indexByKey.put(key, merged.size());
                merged.add(c);
                continue;
            }
            Channel first = merged.get(idx);
            List<Channel.Line> lines = new ArrayList<>(first.lines);
            // 首条 label 若为空（无分组），先规范为"线路1"，避免后续显示空 label
            if (lines.get(0).label == null || lines.get(0).label.isEmpty()) {
                lines.set(0, new Channel.Line("线路1", lines.get(0).url));
            }
            String label = (c.group == null || c.group.isEmpty())
                    ? "线路" + (lines.size() + 1) : c.group;
            if (labelTaken(lines, label) && !c.name.equals(first.name)) {
                label = label + "（" + c.name + "）";
            }
            lines.add(new Channel.Line(label, c.url));
            merged.set(idx, new Channel(first.number, first.name, lines,
                    first.group, first.logo, first.tvgId));
        }
        // 合并后重编号，保证 number 从 1 起连续
        List<Channel> out = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            Channel c = merged.get(i);
            out.add(new Channel(i + 1, c.name, c.lines, c.group, c.logo, c.tvgId));
        }
        return out;
    }

    private static String mergeKey(Channel c) {
        if (c.tvgId != null && !c.tvgId.isEmpty()) return "id:" + c.tvgId;
        String name = c.name == null ? "" : c.name.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        return "name:" + name;
    }

    private static boolean labelTaken(List<Channel.Line> lines, String label) {
        for (Channel.Line l : lines) {
            if (label.equals(l.label)) return true;
        }
        return false;
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
