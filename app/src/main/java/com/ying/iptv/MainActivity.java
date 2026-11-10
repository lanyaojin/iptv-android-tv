package com.ying.iptv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ying.iptv.data.Channel;
import com.ying.iptv.data.Playlist;
import com.ying.iptv.data.PlaylistRepository;
import com.ying.iptv.data.Prefs;
import com.ying.iptv.data.Source;
import com.ying.iptv.player.PlayerController;
import com.ying.iptv.ui.ChannelAdapter;
import com.ying.iptv.ui.GroupAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 直播主界面：打开即播、遥控器换台、频道列表、收藏。 */
public class MainActivity extends AppCompatActivity
        implements ChannelAdapter.Listener, GroupAdapter.Listener, PlayerController.Listener {

    private static final String GROUP_FAVORITES = "@fav";
    private static final String GROUP_UNGROUPED = "@none";
    private static final String STATE_GROUP = "selected_group";

    private Prefs prefs;
    private Playlist playlist;
    private Channel current;
    private String selectedGroup;
    private String loadedSourceUrl;
    private boolean loadingPlaylist;

    private PlayerView playerView;
    private PlayerController controller;

    private LinearLayout status;
    private ProgressBar progress;
    private TextView statusText;
    private LinearLayout emptyPanel;
    private TextView emptyAction;
    private LinearLayout overlay;
    private LinearLayout infoBar;
    private LinearLayout switchBanner;
    private TextView infoNumber, infoName, infoGroup, infoClock;
    private TextView bannerNumber, bannerName;
    private TextView listTitle, listCount, btnSettings, numberInput;
    private RecyclerView channelList, groupList;

    private ChannelAdapter channelAdapter;
    private GroupAdapter groupAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder digits = new StringBuilder();
    private long lastBackTime;

    private final Runnable commitDigits = () -> {
        String text = digits.toString();
        digits.setLength(0);
        numberInput.setVisibility(View.GONE);
        if (text.isEmpty() || playlist == null) return;
        try {
            int number = Integer.parseInt(text);
            Channel channel = playlist.byNumber(number);
            if (channel != null) play(channel, true);
            else toast("没有 " + number + " 号频道");
        } catch (NumberFormatException ignored) {
        }
    };

    private final Runnable hideBanner = () -> switchBanner.setVisibility(View.GONE);
    private final Runnable hideInfo = () -> infoBar.setVisibility(View.GONE);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = Prefs.get(this);
        if (savedInstanceState != null) selectedGroup = savedInstanceState.getString(STATE_GROUP);

        bindViews();
        setupLists();

        controller = new PlayerController(this, playerView);
        controller.setListener(this);

        loadPlaylist(false);
    }

    private void bindViews() {
        playerView = findViewById(R.id.player_view);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.status_text);
        emptyPanel = findViewById(R.id.empty_panel);
        emptyAction = findViewById(R.id.empty_action);
        overlay = findViewById(R.id.overlay);
        infoBar = findViewById(R.id.info_bar);
        switchBanner = findViewById(R.id.switch_banner);
        infoNumber = findViewById(R.id.info_number);
        infoName = findViewById(R.id.info_name);
        infoGroup = findViewById(R.id.info_group);
        infoClock = findViewById(R.id.info_clock);
        bannerNumber = findViewById(R.id.banner_number);
        bannerName = findViewById(R.id.banner_name);
        listTitle = findViewById(R.id.list_title);
        listCount = findViewById(R.id.list_count);
        btnSettings = findViewById(R.id.btn_settings);
        numberInput = findViewById(R.id.number_input);
        channelList = findViewById(R.id.channel_list);
        groupList = findViewById(R.id.group_list);

        emptyAction.setOnClickListener(v -> openSettings());
        btnSettings.setOnClickListener(v -> openSettings());
    }

    private void setupLists() {
        groupAdapter = new GroupAdapter(this);
        groupList.setLayoutManager(new LinearLayoutManager(this));
        groupList.setAdapter(groupAdapter);
        groupList.setNextFocusRightId(R.id.channel_list);

        channelAdapter = new ChannelAdapter(this);
        GridLayoutManager grid = new GridLayoutManager(this, 4);
        channelList.setLayoutManager(grid);
        channelList.setAdapter(channelAdapter);
        channelList.setItemAnimator(null);
        channelList.setNextFocusLeftId(R.id.group_list);
        btnSettings.setNextFocusDownId(R.id.channel_list);
        btnSettings.setNextFocusLeftId(R.id.channel_list);
    }

    // ---------------- 直播源加载 ----------------

    private void loadPlaylist(boolean force) {
        if (loadingPlaylist) return;
        Source source = prefs.currentSource();
        if (source == null) {
            // 尚未配置直播源：进入引导页，去设置里添加
            loadingPlaylist = false;
            emptyPanel.setVisibility(View.VISIBLE);
            emptyAction.requestFocus();
            return;
        }
        loadingPlaylist = true;
        loadedSourceUrl = source.url;
        emptyPanel.setVisibility(View.GONE);
        setStatus(getString(R.string.loading), true);

        PlaylistRepository.load(this, source.url, force, new PlaylistRepository.Callback() {
            @Override
            public void onLoading(boolean fromNetwork) {
                setStatus(getString(R.string.loading), true);
            }

            @Override
            public void onSuccess(Playlist result, boolean fromCache) {
                loadingPlaylist = false;
                playlist = result;
                hideStatus();
                refreshGroups();
                refreshChannelList();

                Channel target = current != null ? findByUrl(current.url) : findLastChannel();
                if (target == null) target = playlist.channels.get(0);
                play(target, current != null);
            }

            @Override
            public void onError(String message) {
                loadingPlaylist = false;
                setStatus(getString(R.string.source_refresh_failed, message), false);
                if (playlist == null || playlist.isEmpty()) {
                    emptyPanel.setVisibility(View.VISIBLE);
                    emptyAction.requestFocus();
                }
            }
        });
    }

    private Channel findLastChannel() {
        String last = prefs.getLastUrl();
        if (last == null) return null;
        return findByUrl(last);
    }

    private Channel findByUrl(String url) {
        if (playlist == null || url == null) return null;
        for (Channel c : playlist.channels) {
            if (url.equals(c.url)) return c;
        }
        return null;
    }

    // ---------------- 播放 ----------------

    private void play(Channel channel, boolean showBanner) {
        if (channel == null) return;
        current = channel;
        prefs.setLastUrl(channel.url);
        controller.play(channel.url);
        updateInfo(channel);
        channelAdapter.setSelected(channel.number);
        setStatus(getString(R.string.loading_channel, channel.name), true);
        if (showBanner && overlay.getVisibility() != View.VISIBLE) showSwitchBanner(channel);
    }

    private void switchChannel(int delta) {
        if (playlist == null || playlist.isEmpty()) return;
        int index = current == null ? -1 : playlist.channels.indexOf(current);
        if (index < 0) index = 0;
        int next = (index + delta + playlist.size()) % playlist.size();
        play(playlist.channels.get(next), true);
    }

    @Override
    public void onReady() {
        hideStatus();
    }

    @Override
    public void onBuffering() {
        if (current != null) setStatus("缓冲中…", true);
    }

    @Override
    public void onRetrying(int attempt) {
        setStatus("信号中断，正在第 " + attempt + " 次重连…", true);
    }

    @Override
    public void onFailed(String message) {
        setStatus(getString(R.string.play_failed, message), false);
    }

    // ---------------- 界面状态 ----------------

    private void setStatus(String text, boolean spinning) {
        statusText.setText(text);
        progress.setVisibility(spinning ? View.VISIBLE : View.GONE);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    private void updateInfo(Channel channel) {
        infoNumber.setText(channel.displayNumber());
        infoName.setText(channel.name);
        String group = (channel.group == null || channel.group.isEmpty())
                ? getString(R.string.ungrouped) : channel.group;
        Source source = prefs.currentSource();
        infoGroup.setText(source != null ? group + " · " + source.displayName() : group);
        infoClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
    }

    private void showSwitchBanner(Channel channel) {
        bannerNumber.setText(channel.displayNumber());
        bannerName.setText(channel.name);
        switchBanner.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, 2600);
    }

    private void showInfoBar() {
        if (current == null) return;
        updateInfo(current);
        infoBar.setVisibility(View.VISIBLE);
        switchBanner.setVisibility(View.GONE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, 6000);
    }

    // ---------------- 频道列表 ----------------

    private void showOverlay() {
        if (playlist == null || playlist.isEmpty()) {
            openSettings();
            return;
        }
        overlay.setVisibility(View.VISIBLE);
        infoBar.setVisibility(View.GONE);
        switchBanner.setVisibility(View.GONE);
        refreshGroups();
        refreshChannelList();
        focusCurrentChannel();
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
        channelList.clearFocus();
        groupList.clearFocus();
    }

    private void focusCurrentChannel() {
        int position = current == null ? 0 : channelAdapter.positionOf(current.number);
        if (position == RecyclerView.NO_POSITION) position = 0;
        final int target = position;
        channelList.post(() -> {
            RecyclerView.LayoutManager manager = channelList.getLayoutManager();
            if (manager instanceof GridLayoutManager) {
                ((GridLayoutManager) manager).scrollToPositionWithOffset(target, 0);
            }
            channelList.post(() -> {
                RecyclerView.ViewHolder holder = channelList.findViewHolderForAdapterPosition(target);
                if (holder != null) holder.itemView.requestFocus();
                else channelList.requestFocus();
            });
        });
    }

    private void refreshGroups() {
        if (playlist == null) return;
        List<GroupAdapter.Entry> entries = new ArrayList<>();
        entries.add(new GroupAdapter.Entry(null, getString(R.string.all_channels), playlist.size()));
        entries.add(new GroupAdapter.Entry(GROUP_FAVORITES, getString(R.string.favorites), favorites().size()));
        for (String group : playlist.groups) {
            entries.add(new GroupAdapter.Entry(group, group, playlist.byGroup(group).size()));
        }
        if (playlist.hasUngrouped) {
            entries.add(new GroupAdapter.Entry(GROUP_UNGROUPED, getString(R.string.ungrouped), ungrouped().size()));
        }
        groupAdapter.setData(entries, selectedGroup);
    }

    private void refreshChannelList() {
        if (playlist == null) return;
        List<Channel> channels = channelsForGroup(selectedGroup);
        channelAdapter.setData(channels, current == null ? -1 : current.number, prefs.getFavorites());

        String title;
        if (selectedGroup == null) title = getString(R.string.all_channels);
        else if (GROUP_FAVORITES.equals(selectedGroup)) title = getString(R.string.favorites);
        else if (GROUP_UNGROUPED.equals(selectedGroup)) title = getString(R.string.ungrouped);
        else title = selectedGroup;
        listTitle.setText(title);
        listCount.setText(getString(R.string.channel_count, channels.size()));
        groupAdapter.setSelected(selectedGroup);
    }

    private List<Channel> channelsForGroup(String group) {
        if (playlist == null) return new ArrayList<>();
        if (group == null) return playlist.channels;
        if (GROUP_FAVORITES.equals(group)) return favorites();
        if (GROUP_UNGROUPED.equals(group)) return ungrouped();
        return playlist.byGroup(group);
    }

    private List<Channel> favorites() {
        List<Channel> out = new ArrayList<>();
        if (playlist == null) return out;
        Set<String> keys = prefs.getFavorites();
        for (Channel c : playlist.channels) if (keys.contains(c.key())) out.add(c);
        return out;
    }

    private List<Channel> ungrouped() {
        List<Channel> out = new ArrayList<>();
        if (playlist == null) return out;
        for (Channel c : playlist.channels) {
            if (c.group == null || c.group.isEmpty()) out.add(c);
        }
        return out;
    }

    // ---------------- 回调 ----------------

    @Override
    public void onClick(Channel channel) {
        play(channel, false);
        hideOverlay();
    }

    @Override
    public void onLongClick(Channel channel) {
        boolean added = prefs.toggleFavorite(channel.key());
        toast(added ? getString(R.string.favorite_add) : getString(R.string.favorite_remove));
        refreshGroups();
        refreshChannelList();
    }

    @Override
    public void onGroupClick(GroupAdapter.Entry entry) {
        selectedGroup = entry.key;
        refreshChannelList();
        channelList.post(() -> {
            RecyclerView.ViewHolder holder = channelList.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else channelList.requestFocus();
        });
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int code = event.getKeyCode();
            boolean overlayShown = overlay.getVisibility() == View.VISIBLE;

            if (code == KeyEvent.KEYCODE_BACK) {
                if (overlayShown) {
                    hideOverlay();
                    return true;
                }
                if (infoBar.getVisibility() == View.VISIBLE) {
                    infoBar.setVisibility(View.GONE);
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackTime < 2000) {
                    finish();
                } else {
                    lastBackTime = now;
                    toast(getString(R.string.press_again_exit));
                }
                return true;
            }

            if (code == KeyEvent.KEYCODE_MENU) {
                openSettings();
                return true;
            }

            switch (code) {
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (!overlayShown) {
                        switchChannel(1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (!overlayShown) {
                        switchChannel(-1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (!overlayShown) {
                        switchChannel(-1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!overlayShown) {
                        switchChannel(1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!overlayShown) {
                        showOverlay();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_INFO:
                    showInfoBar();
                    return true;
                default:
                    break;
            }

            if (!overlayShown && code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                onDigit(code - KeyEvent.KEYCODE_0);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void onDigit(int digit) {
        if (playlist == null || playlist.isEmpty()) return;
        digits.append(digit);
        if (digits.length() > 3) digits.deleteCharAt(0);
        numberInput.setText(digits.toString());
        numberInput.setVisibility(View.VISIBLE);
        handler.removeCallbacks(commitDigits);
        handler.postDelayed(commitDigits, 1200);
    }

    // ---------------- 生命周期 ----------------

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (current != null && playlist != null) play(current, false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) controller.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        Source source = prefs.currentSource();
        if (source == null) {
            if (!loadingPlaylist) {
                emptyPanel.setVisibility(View.VISIBLE);
                emptyAction.requestFocus();
            }
            return;
        }
        String url = source.url;
        boolean sourceChanged = loadedSourceUrl != null && !url.equals(loadedSourceUrl);
        if (prefs.needRefresh() || sourceChanged) {
            prefs.setNeedRefresh(false);
            loadPlaylist(true);
        } else if (!loadingPlaylist && playlist != null) {
            refreshGroups();
            refreshChannelList();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_GROUP, selectedGroup);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (controller != null) controller.release();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
