package com.ying.iptv.data;

/** 单个直播频道。 */
public class Channel {

    public final int number;
    public final String name;
    public final String url;
    public final String group;
    public final String logo;
    public final String tvgId;

    public Channel(int number, String name, String url, String group, String logo, String tvgId) {
        this.number = number;
        this.name = name;
        this.url = url;
        this.group = group;
        this.logo = logo;
        this.tvgId = tvgId;
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
