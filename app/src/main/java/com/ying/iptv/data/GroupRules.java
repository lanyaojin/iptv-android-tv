package com.ying.iptv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 频道分组归类规则。
 *
 * 优先级（首条命中胜）：
 *   1. 服务端后台「关键字自动分组规则」(/api/group-keyword-rules) —— 用户在 admin 里改，
 *      客户端刷新订阅时自动同步，无需重新编译 APK。
 *   2. 地域组保护：源站已明确按地域分组的频道，不再被频道名里的「卫视」等关键词抢走。
 *   3. 内置名称规则（CCTV / CGTN / CETV / 卫视 …）。
 *   4. 内置原组归并（央视频→央视、影视类→影视、地域→港澳台/海外 …）。
 *
 * 规则统一用「分组名 → 关键词列表」表达，便于测试与后续扩展。
 */
public final class GroupRules {

    /** 地域组：这些分组由源站明确给出，优先按地域归并，不走名称规则。 */
    private static final Map<String, String> REGION_GROUPS = regionGroups();

    /** 内置名称规则，顺序即优先级。 */
    private static final List<Rule> NAME_RULES = Arrays.asList(
            new Rule("国际", "CGTN"),
            new Rule("教育", "CETV"),
            new Rule("央视", "CCTV", "央视"),
            new Rule("卫视", "卫视"),
            new Rule("文旅", "国学频道"));

    /** 内置原组归并。 */
    private static final Map<String, String> GROUP_MAP = groupMap();

    private final List<Rule> serverRules;

    private GroupRules(List<Rule> serverRules) {
        this.serverRules = serverRules == null ? Collections.emptyList() : serverRules;
    }

    /** 仅内置规则（离线缓存、非本系统订阅、规则接口不可用时）。 */
    public static GroupRules builtIn() {
        return new GroupRules(Collections.emptyList());
    }

    /**
     * 解析服务端返回的规则 JSON：{ "success": true, "data": [ {"group":"央视","keywords":["CCTV"]} ] }
     * 任意解析失败都退化为内置规则，绝不影响频道加载。
     */
    public static GroupRules withServerJson(String json) {
        if (json == null || json.isEmpty()) return builtIn();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return builtIn();
            List<Rule> rules = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                String group = o.optString("group", "").trim();
                JSONArray keywords = o.optJSONArray("keywords");
                if (group.isEmpty() || keywords == null) continue;
                List<String> words = new ArrayList<>();
                for (int j = 0; j < keywords.length(); j++) {
                    String w = keywords.optString(j, "").trim();
                    if (!w.isEmpty()) words.add(w);
                }
                if (!words.isEmpty()) rules.add(new Rule(group, words.toArray(new String[0])));
            }
            return new GroupRules(rules);
        } catch (Exception ignored) {
            return builtIn();
        }
    }

    /** 返回目标分组；null 表示保持原分组。 */
    public String classify(String name, String group) {
        String n = name == null ? "" : name;

        // 1. 服务端规则优先（用户在 admin 后台维护）
        for (Rule r : serverRules) {
            if (r.matches(n)) return r.group;
        }

        // 2. 地域组保护：澳门莲花卫视等不再被「卫视」抢走
        String region = group == null ? null : REGION_GROUPS.get(group);
        if (region != null) return region;

        // 3. 内置名称规则
        for (Rule r : NAME_RULES) {
            if (r.matches(n)) return r.group;
        }

        // 4. 内置原组归并
        if (group != null) {
            String mapped = GROUP_MAP.get(group);
            if (mapped != null) return mapped;
        }
        return null;
    }

    private static Map<String, String> regionGroups() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String g : new String[]{"澳门", "台湾", "香港"}) m.put(g, "港澳台");
        for (String g : new String[]{"马来西亚", "韩国", "新加坡", "日本", "美国", "英国", "法国", "德国"}) {
            m.put(g, "海外");
        }
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> groupMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("央视频", "央视");
        m.put("娱乐时尚", "影视");
        m.put("娱乐综艺", "影视");
        m.put("抖音", "网络直播");
        m.put("虎牙", "网络直播");
        return Collections.unmodifiableMap(m);
    }

    private static final class Rule {
        final String group;
        final String[] keywords;

        Rule(String group, String... keywords) {
            this.group = group;
            this.keywords = keywords;
        }

        boolean matches(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String k : keywords) {
                if (lower.contains(k.toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }
    }
}
