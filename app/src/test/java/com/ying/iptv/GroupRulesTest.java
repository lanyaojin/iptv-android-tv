package com.ying.iptv;

import static org.junit.Assert.assertEquals;

import com.ying.iptv.data.GroupRules;
import com.ying.iptv.data.M3uParser;
import com.ying.iptv.data.Playlist;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 服务端后台分组规则的解析与应用（org.json 需要 Robolectric 环境）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class GroupRulesTest {

    private static final String M3U =
            "#EXTINF:-1 group-title=\"央视频\",CCTV1综合\nhttp://h/ysp1\n"
                    + "#EXTINF:-1 group-title=\"影视\",电影一台\nhttp://h/m1\n";

    @Test
    public void serverRulesOverrideBuiltInRules() {
        String json = "{\"success\":true,\"data\":[{\"group\":\"我的收藏台\",\"keywords\":[\"CCTV1\"]}]}";
        GroupRules rules = GroupRules.withServerJson(json);
        assertEquals("我的收藏台", rules.classify("CCTV1综合", "央视频"));
        Playlist p = M3uParser.parse(M3U, rules);
        assertEquals(1, p.byGroup("我的收藏台").size());
        // 未命中的频道仍走内置规则
        assertEquals(1, p.byGroup("影视").size());
    }

    @Test
    public void serverRulesUseFirstMatchWins() {
        String json = "{\"success\":true,\"data\":["
                + "{\"group\":\"优先组\",\"keywords\":[\"电影\"]},"
                + "{\"group\":\"次选组\",\"keywords\":[\"一台\"]}]}";
        GroupRules rules = GroupRules.withServerJson(json);
        assertEquals("优先组", rules.classify("电影一台", "影视"));
    }

    @Test
    public void brokenServerRulesFallBackToBuiltIn() {
        for (String bad : new String[]{null, "", "not-json", "{\"success\":false}", "{\"data\":42}"}) {
            Playlist p = M3uParser.parse(M3U, GroupRules.withServerJson(bad));
            assertEquals("央视", p.channels.get(0).group);
        }
    }
}
