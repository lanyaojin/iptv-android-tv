package com.ying.iptv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.ying.iptv.data.Prefs;
import com.ying.iptv.data.Source;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** 界面与偏好的无头冒烟测试。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AppSmokeTest {

    @Test
    public void noSourcesByDefaultUntilUserAdds() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs prefs = Prefs.get(context);
        assertEquals(0, prefs.getSources().size());
        assertNull(prefs.currentSource());
        assertTrue(prefs.isRemember());

        prefs.addSource(new Source("测试源", "http://127.0.0.1:1/a.m3u"));
        assertEquals(1, prefs.getSources().size());
        assertEquals("http://127.0.0.1:1/a.m3u", prefs.currentSource().url);
    }

    @Test
    public void sourceCrudKeepsCurrentIndexValid() {
        Context context = ApplicationProvider.getApplicationContext();
        Prefs prefs = Prefs.get(context);
        int before = prefs.getSources().size();

        prefs.addSource(new Source("测试源", "http://127.0.0.1:1/a.m3u"));
        assertEquals(before + 1, prefs.getSources().size());
        assertEquals(before, prefs.getCurrentSource());

        prefs.updateCurrentSource(new Source("改名", "http://127.0.0.1:1/b.m3u"));
        assertEquals("改名", prefs.currentSource().name);
        assertEquals("http://127.0.0.1:1/b.m3u", prefs.currentSource().url);
    }

    @Test
    public void favoritesToggle() {
        Prefs prefs = Prefs.get(ApplicationProvider.getApplicationContext());
        String key = "id:test-fav";
        assertFalse(prefs.isFavorite(key));
        assertTrue(prefs.toggleFavorite(key));
        assertTrue(prefs.isFavorite(key));
        assertFalse(prefs.toggleFavorite(key));
    }

    @Test
    public void mainActivityInflatesAndSurvivesKeys() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();

            assertNotNull(activity.findViewById(R.id.player_view));
            assertNotNull(activity.findViewById(R.id.channel_list));
            assertNotNull(activity.findViewById(R.id.group_list));
            assertEquals(View.GONE, activity.findViewById(R.id.overlay).getVisibility());

            int[] keys = {
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_INFO,
                    KeyEvent.KEYCODE_1,
                    KeyEvent.KEYCODE_BACK,
            };
            for (int code : keys) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
            // 未配置频道时按返回不应崩溃，仅提示再次返回退出
            assertFalse(activity.isFinishing());
        }
    }

    @Test
    public void settingsActivityInflates() {
        try (ActivityController<SettingsActivity> controller =
                     Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = controller.get();
            assertNotNull(activity.findViewById(R.id.row_source));
            assertNotNull(activity.findViewById(R.id.row_remember));
            assertNotNull(activity.findViewById(R.id.value_about));
        }
    }
}
