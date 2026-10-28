package com.ally.tvtube;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String NETWORK_ERROR = "Không kết nối được máy chủ. Giữ nút Tìm để kiểm tra địa chỉ.";

    private static final int TAB_RECOMMEND = 0;
    private static final int TAB_HISTORY = 1;
    private static final int TAB_FAV = 2;

    private EditText editSearch;
    private Button btnSearch;
    private GridView grid;
    private TextView txtStatus;
    private TextView txtHint;

    private Button tabRecommend;
    private Button tabHistory;
    private Button tabFav;
    private final Button[] chips = new Button[6];

    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoAdapter adapter;
    private final ArrayList<RowItem> displayItems = new ArrayList<RowItem>();
    private final ArrayList<Api.Video> searchResults = new ArrayList<Api.Video>();

    private boolean isSearching = false;
    private int currentTab = TAB_RECOMMEND;
    private String currentQuery = "";

    private long okDownTime = 0L;
    private boolean okLongHandled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Crash.install(this);
        showLastCrash();
        setContentView(R.layout.activity_main);

        editSearch = (EditText) findViewById(R.id.editSearch);
        btnSearch = (Button) findViewById(R.id.btnSearch);
        grid = (GridView) findViewById(R.id.grid);
        txtStatus = (TextView) findViewById(R.id.txtStatus);
        txtHint = (TextView) findViewById(R.id.txtHint);

        Ui.applyFont(getWindow().getDecorView(), Ui.font(this, 0));

        if (txtHint != null) {
            txtHint.setText("Máy chủ: " + Api.baseUrl(this) + "   •   Giữ nút Tìm để đổi địa chỉ");
        }
        if (txtStatus != null) {
            txtStatus.setGravity(Gravity.CENTER);
        }

        grid.setNumColumns(4);
        adapter = new VideoAdapter(displayItems);
        grid.setAdapter(adapter);

        setupTabs();
        setupChips();
        setupSearchControls();
        setupGrid();

        selectTab(TAB_RECOMMEND);

        String lastQuery = getSharedPreferences("tv", MODE_PRIVATE).getString("last_query", "");
        if (lastQuery != null && lastQuery.length() > 0) {
            editSearch.setText(lastQuery);
            doSearch();
        } else {
            showEmptyStatus();
        }
    }

    private void setupTabs() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.rgb(25, 25, 25));

        tabRecommend = createTabButton("Gợi ý", TAB_RECOMMEND);
        tabHistory = createTabButton("Lịch sử", TAB_HISTORY);
        tabFav = createTabButton("Yêu thích", TAB_FAV);

        bar.addView(tabRecommend, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(tabHistory, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(tabFav, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ViewGroup root = findContentRoot();
        if (root != null) {
            if (root instanceof LinearLayout) {
                ((LinearLayout) root).addView(bar, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            } else {
                root.addView(bar, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
        }
    }

    private Button createTabButton(String text, final int tab) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(16);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setId(View.generateViewId());

        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(tab);
                v.requestFocus();
            }
        });

        b.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }

                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (currentTab > TAB_RECOMMEND) {
                        selectTab(currentTab - 1);
                        focusCurrentTab();
                    }
                    return true;
                }

                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (currentTab < TAB_FAV) {
                        selectTab(currentTab + 1);
                        focusCurrentTab();
                    }
                    return true;
                }

                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusGridOrSearch();
                    return true;
                }

                return false;
            }
        });

        return b;
    }

    private ViewGroup findContentRoot() {
        View content = findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            ViewGroup contentGroup = (ViewGroup) content;
            if (contentGroup.getChildCount() > 0) {
                View child = contentGroup.getChildAt(0);
                if (child instanceof ViewGroup) {
                    return (ViewGroup) child;
                }
            }
            return contentGroup;
        }
        return null;
    }

    private void setupChips() {
        int[] chipIds = {R.id.chip1, R.id.chip2, R.id.chip3, R.id.chip4, R.id.chip5, R.id.chip6};
        for (int i = 0; i < chipIds.length; i++) {
            final Button chip = (Button) findViewById(chipIds[i]);
            chips[i] = chip;
            if (chip == null) {
                continue;
            }

            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editSearch.setText(chip.getText().toString());
                    if (currentTab != TAB_RECOMMEND) {
                        selectTab(TAB_RECOMMEND);
                    }
                    doSearch();
                }
            });

            chip.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return focusGridOrSearch();
                    }
                    return false;
                }
            });
        }
    }

    private void setupSearchControls() {
        if (btnSearch != null) {
            btnSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doSearch();
                }
            });

            btnSearch.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showServerDialog();
                    return true;
                }
            });
        }

        if (editSearch != null) {
            editSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                        actionId == EditorInfo.IME_NULL ||
                        (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                        doSearch();
                        return true;
                    }
                    return false;
                }
            });

            editSearch.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return focusGridOrSearch();
                    }
                    return false;
                }
            });
        }
    }

    private void setupGrid() {
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    int pos = grid.getSelectedItemPosition();
                    if (pos < 0 || pos < grid.getNumColumns()) {
                        focusCurrentTab();
                        return true;
                    }
                    return false;
                }

                if (event.getAction() == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {

                    if (event.getRepeatCount() == 0) {
                        okDownTime = System.currentTimeMillis();
                        okLongHandled = false;
                    } else {
                        if (!okLongHandled &&
                            (currentTab == TAB_HISTORY || currentTab == TAB_FAV) &&
                            System.currentTimeMillis() - okDownTime > 500L) {

                            okLongHandled = true;
                            int pos = grid.getSelectedItemPosition();
                            if (pos < 0 && displayItems.size() > 0) {
                                pos = 0;
                            }
                            if (pos >= 0 && pos < displayItems.size()) {
                                toggleFavoriteForRow(displayItems.get(pos));
                            }
                        }
                    }
                    return true;
                }

                if (event.getAction() == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {

                    if (!okLongHandled) {
                        int pos = grid.getSelectedItemPosition();
                        if (pos < 0 && displayItems.size() > 0) {
                            pos = 0;
                        }
                        long held = System.currentTimeMillis() - okDownTime;
                        if (pos >= 0 && pos < displayItems.size()) {
                            if ((currentTab == TAB_HISTORY || currentTab == TAB_FAV) && held > 500L) {
                                okLongHandled = true;
                                toggleFavoriteForRow(displayItems.get(pos));
                            } else {
                                openRow(displayItems.get(pos));
                            }
                        }
                    }

                    okLongHandled = false;
                    return true;
                }

                return false;
            }
        });

        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < displayItems.size()) {
                    RowItem row = displayItems.get(position);
                    if (row != null && row.video != null) Api.prefetch(MainActivity.this, row.video.id);
                }
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < displayItems.size()) {
                    openRow(displayItems.get(position));
                }
            }
        });

        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if ((currentTab == TAB_HISTORY || currentTab == TAB_FAV) &&
                    position >= 0 && position < displayItems.size()) {
                    toggleFavoriteForRow(displayItems.get(position));
                    return true;
                }
                return false;
            }
        });
    }

    private void selectTab(int tab) {
        if (tab < TAB_RECOMMEND || tab > TAB_FAV) {
            return;
        }
        currentTab = tab;
        updateTabStyles();
        updateChipsVisibility();
        refreshCurrentList();
    }

    private void updateTabStyles() {
        Button[] tabs = {tabRecommend, tabHistory, tabFav};
        for (int i = 0; i < tabs.length; i++) {
            Button b = tabs[i];
            if (b == null) {
                continue;
            }
            if (i == currentTab) {
                b.setBackgroundColor(Color.rgb(0, 120, 215));
                b.setTextColor(Color.WHITE);
            } else {
                b.setBackgroundColor(Color.rgb(45, 45, 45));
                b.setTextColor(Color.LTGRAY);
            }
        }
    }

    private void updateChipsVisibility() {
        boolean show = (currentTab == TAB_RECOMMEND);
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] != null) {
                chips[i].setVisibility(show ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void refreshCurrentList() {
        if (adapter == null) {
            return;
        }

        displayItems.clear();

        if (currentTab == TAB_RECOMMEND) {
            for (int i = 0; i < searchResults.size(); i++) {
                displayItems.add(new RowItem(searchResults.get(i), 0));
            }

            if (displayItems.isEmpty()) {
                if (currentQuery != null && currentQuery.length() > 0) {
                    showEmptyResultStatus();
                } else {
                    showEmptyStatus();
                }
            } else {
                if (txtStatus != null) {
                    txtStatus.setVisibility(View.VISIBLE);
                    txtStatus.setGravity(Gravity.CENTER);
                    txtStatus.setText("Tìm thấy " + displayItems.size() + " video");
                }
            }
        } else if (currentTab == TAB_HISTORY) {
            ArrayList<Store.HistoryItem> history = Store.getHistory(this);
            for (int i = 0; i < history.size(); i++) {
                Store.HistoryItem h = history.get(i);
                displayItems.add(new RowItem(h.video, h.posMs));
            }

            if (displayItems.isEmpty()) {
                if (txtStatus != null) {
                    txtStatus.setVisibility(View.VISIBLE);
                    txtStatus.setGravity(Gravity.CENTER);
                    txtStatus.setText("Chưa xem video nào.");
                }
            } else {
                if (txtStatus != null) {
                    txtStatus.setVisibility(View.VISIBLE);
                    txtStatus.setGravity(Gravity.CENTER);
                    txtStatus.setText("Lịch sử xem: " + displayItems.size() + " video");
                }
            }
        } else if (currentTab == TAB_FAV) {
            ArrayList<Api.Video> favorites = Store.getFavorites(this);
            for (int i = 0; i < favorites.size(); i++) {
                displayItems.add(new RowItem(favorites.get(i), 0));
            }

            if (displayItems.isEmpty()) {
                if (txtStatus != null) {
                    txtStatus.setVisibility(View.VISIBLE);
                    txtStatus.setGravity(Gravity.CENTER);
                    txtStatus.setText("Chưa có video yêu thích.");
                }
            } else {
                if (txtStatus != null) {
                    txtStatus.setVisibility(View.VISIBLE);
                    txtStatus.setGravity(Gravity.CENTER);
                    txtStatus.setText("Yêu thích: " + displayItems.size() + " video");
                }
            }
        }

        adapter.notifyDataSetChanged();
    }

    private boolean focusGridOrSearch() {
        if (adapter != null && adapter.getCount() > 0) {
            grid.requestFocus();
            if (grid.getSelectedItemPosition() < 0) {
                grid.setSelection(0);
            }
            return true;
        }
        if (editSearch != null) {
            editSearch.requestFocus();
            return true;
        }
        return false;
    }

    private void focusCurrentTab() {
        if (currentTab == TAB_RECOMMEND && tabRecommend != null) {
            tabRecommend.requestFocus();
        } else if (currentTab == TAB_HISTORY && tabHistory != null) {
            tabHistory.requestFocus();
        } else if (currentTab == TAB_FAV && tabFav != null) {
            tabFav.requestFocus();
        }
    }

    private void showEmptyStatus() {
        if (txtStatus == null) {
            return;
        }
        txtStatus.setVisibility(View.VISIBLE);
        txtStatus.setGravity(Gravity.CENTER);
        txtStatus.setText("Nhập từ khoá và bấm Tìm để xem video.");
    }

    private void showEmptyResultStatus() {
        if (txtStatus == null) {
            return;
        }
        txtStatus.setVisibility(View.VISIBLE);
        txtStatus.setGravity(Gravity.CENTER);
        txtStatus.setText("Không tìm thấy video. Hãy thử từ khoá khác.");
    }

    private void doSearch() {
        if (isSearching) {
            if (currentTab != TAB_RECOMMEND) {
                selectTab(TAB_RECOMMEND);
            }
            if (txtStatus != null) {
                txtStatus.setVisibility(View.VISIBLE);
                txtStatus.setGravity(Gravity.CENTER);
                txtStatus.setText("Đang tìm...");
            }
            return;
        }

        final String query = editSearch.getText().toString().trim();
        if (query.length() == 0) {
            searchResults.clear();
            currentQuery = "";
            if (currentTab != TAB_RECOMMEND) {
                selectTab(TAB_RECOMMEND);
            } else {
                refreshCurrentList();
            }
            return;
        }

        if (currentTab != TAB_RECOMMEND) {
            selectTab(TAB_RECOMMEND);
        }

        isSearching = true;
        getSharedPreferences("tv", MODE_PRIVATE).edit().putString("last_query", query).apply();
        Store.addSearch(this, query);

        if (txtStatus != null) {
            txtStatus.setVisibility(View.VISIBLE);
            txtStatus.setGravity(Gravity.CENTER);
            txtStatus.setText("Đang tìm...");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ArrayList<Api.Video> results = Api.search(MainActivity.this, query);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if ((isFinishing() || isDestroyed())) {
                                return;
                            }
                            isSearching = false;
                            searchResults.clear();
                            if (results != null) {
                                searchResults.addAll(results);
                            }
                            currentQuery = query;
                            if (currentTab == TAB_RECOMMEND) {
                                refreshCurrentList();
                            }
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if ((isFinishing() || isDestroyed())) {
                                return;
                            }
                            isSearching = false;

                            String msg = e.getMessage();
                            if (msg == null || msg.length() == 0) {
                                msg = NETWORK_ERROR;
                            }

                            if (currentTab == TAB_RECOMMEND) {
                                if (txtStatus != null) {
                                    txtStatus.setVisibility(View.VISIBLE);
                                    txtStatus.setGravity(Gravity.CENTER);
                                    if (msg.indexOf("kết nối") >= 0 ||
                                        msg.indexOf("Máy chủ") >= 0 ||
                                        msg.indexOf("Không thể") >= 0) {
                                        txtStatus.setText(NETWORK_ERROR);
                                    } else {
                                        txtStatus.setText(msg);
                                    }
                                }
                            } else {
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void toggleFavoriteForRow(RowItem row) {
        if (row == null || row.video == null) {
            return;
        }

        boolean added = Store.toggleFavorite(this, row.video);
        Toast.makeText(this, added ? "Đã thêm vào yêu thích" : "Đã bỏ yêu thích", Toast.LENGTH_SHORT).show();

        if (currentTab == TAB_FAV) {
            refreshCurrentList();
        }
    }

    private void openRow(RowItem row) {
        if (row == null || row.video == null) {
            return;
        }

        Api.Video video = row.video;
        int startPos = row.posMs;

        Store.addHistory(this, video, 0);

        Intent intent = new Intent();
        intent.setClassName("com.ally.tvtube", "com.ally.tvtube.ExoPlayerActivity");
        intent.putExtra("id", video.id);
        intent.putExtra("title", video.title);
        intent.putExtra("posMs", startPos);
        startActivity(intent);
    }

    private void showServerDialog() {
        final EditText input = new EditText(this);
        input.setText(Api.baseUrl(this));
        input.setSingleLine();

        new AlertDialog.Builder(this)
            .setTitle("Địa chỉ máy chủ")
            .setMessage("Ví dụ: http://192.168.1.10:8000")
            .setView(input)
            .setPositiveButton("Lưu", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String url = normalizeUrl(input.getText().toString());
                    if (url.length() > 0) {
                        Api.setBaseUrl(MainActivity.this, url);
                        if (txtHint != null) {
                            txtHint.setText("Máy chủ: " + Api.baseUrl(MainActivity.this) + "   •   Giữ nút Tìm để đổi địa chỉ");
                        }
                    }
                }
            })
            .setNeutralButton("Kiểm tra", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    checkServer(normalizeUrl(input.getText().toString()));
                }
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null && currentTab != TAB_RECOMMEND) {
            refreshCurrentList();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }


    /** Neu lan truoc app tung vang, hien noi dung tep loi de nguoi dung doc/gui di. */
    private void showLastCrash() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "loi.txt");
            if (!f.exists() || f.length() == 0) return;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 40) { sb.append(line).append('\n'); n++; }
            r.close();
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Lần trước app bị lỗi")
                    .setMessage(sb.toString())
                    .setPositiveButton("Đóng", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.length() == 0) {
            return "";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String pingHealth(String base) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(base + "/health").openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            c.disconnect();
            return (code == 200) ? "Máy chủ trả lời tốt" : ("Máy chủ trả lỗi " + code);
        } catch (Exception e) {
            return "Không kết nối được: " + e.getClass().getSimpleName();
        }
    }

    private void checkServer(final String base) {
        if (base.length() == 0) {
            Toast.makeText(this, "Chưa nhập địa chỉ", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Đang kiểm tra…", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = pingHealth(base);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if ((isFinishing() || isDestroyed())) {
                            return;
                        }
                        Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private static String formatPos(int posMs) {
        if (posMs < 0) {
            posMs = 0;
        }
        int totalSeconds = posMs / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (hours > 0) {
            return hours + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
        }
        return minutes + ":" + twoDigits(seconds);
    }

    private static String twoDigits(int value) {
        if (value < 10) {
            return "0" + value;
        }
        return String.valueOf(value);
    }

    private static class RowItem {
        Api.Video video;
        int posMs;

        RowItem(Api.Video video, int posMs) {
            this.video = video;
            this.posMs = posMs;
        }
    }

    private static class VideoAdapter extends BaseAdapter {
        private final ArrayList<RowItem> items;

        VideoAdapter(ArrayList<RowItem> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;

            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
                holder = new ViewHolder();
                holder.imgThumb = (ImageView) convertView.findViewById(R.id.imgThumb);
                holder.txtTitle = (TextView) convertView.findViewById(R.id.txtTitle);
                holder.txtMeta = (TextView) convertView.findViewById(R.id.txtMeta);
                holder.card = convertView.findViewById(R.id.card);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            RowItem row = items.get(position);
            Api.Video video = row.video;

            holder.txtTitle.setText(video.title == null ? "" : video.title);

            String meta = video.author == null ? "" : video.author;
            if (video.duration != null && video.duration.length() > 0) {
                if (meta.length() > 0) {
                    meta += " • ";
                }
                meta += video.duration;
            }
            if (row.posMs > 0) {
                if (meta.length() > 0) {
                    meta += " • ";
                }
                meta += "xem tiếp " + formatPos(row.posMs);
            }
            holder.txtMeta.setText(meta);

            final View targetCard = holder.card != null ? holder.card : convertView;
            targetCard.setFocusable(false);
            targetCard.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    Ui.focusScale(v, hasFocus);
                }
            });

            ImageLoader.load(holder.imgThumb, video.thumb, 320, 180);

            return convertView;
        }
    }

    private static class ViewHolder {
        ImageView imgThumb;
        TextView txtTitle;
        TextView txtMeta;
        View card;
    }
}
