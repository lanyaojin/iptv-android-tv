package com.ying.iptv.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单个直播频道；同一频道可有多条线路（多源合并后）。 */
public class Channel {

    /** 一条播放线路：来源分组名 + 播放地址。 */
    public static final class Line {
        public final String label;
        public final String url;

        public Line(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    public final int number;
    public final String name;
    /** 首条线路的 URL（保持现有代码用 channel.url 不变）。 */
    public final String url;
    /** 首条线路的分组（保持现有代码用 channel.group 不变）。 */
    public final String group;
    public final String logo;
    public final String tvgId;
    /** 全部线路，按首次出现顺序；不可变。 */
    public final List<Line> lines;

    public Channel(int number, String name, String url, String group, String logo, String tvgId) {
        this(number, name, Collections.singletonList(new Line(group, url)), group, logo, tvgId);
    }

    /**
     * 多线路构造器。lines 放在第 3 位：与旧构造器的第 3 位（String url）类型不同，
     * 调用点传 null 字面量时也不会产生重载歧义。
     */
    public Channel(int number, String name, List<Line> lines, String group, String logo, String tvgId) {
        this.number = number;
        this.name = name;
        this.group = group;
        this.logo = logo;
        this.tvgId = tvgId;
        List<Line> copy = new ArrayList<>(lines);
        this.lines = Collections.unmodifiableList(copy);
        this.url = copy.isEmpty() ? null : copy.get(0).url;
    }

    public boolean hasMultipleLines() {
        return lines.size() > 1;
    }

    public int lineCount() {
        return lines.size();
    }

    public String lineLabel(int index) {
        if (index < 0 || index >= lines.size()) return null;
        return lines.get(index).label;
    }

    public String lineUrl(int index) {
        if (index < 0 || index >= lines.size()) return null;
        return lines.get(index).url;
    }

    /** 返回该地址在线路中的下标，找不到返回 -1。 */
    public int lineIndexOfUrl(String url) {
        if (url == null) return -1;
        for (int i = 0; i < lines.size(); i++) {
            if (url.equals(lines.get(i).url)) return i;
        }
        return -1;
    }

    /** 收藏等场景的稳定标识，优先使用 tvg-id，其次使用地址。 */
    public String key() {
        if (tvgId != null && !tvgId.isEmpty()) return "id:" + tvgId;
        return "url:" + url;
    }

    public String displayNumber() {
        return String.format(java.util.Locale.US, "%03d", number);
    }
}
