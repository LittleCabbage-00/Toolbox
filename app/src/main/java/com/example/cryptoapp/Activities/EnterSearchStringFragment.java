package com.example.cryptoapp.Activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cryptoapp.Base.BaseActivity;
import com.example.cryptoapp.Browser.BrowserHistoryEntry;
import com.example.cryptoapp.Browser.BrowserHistoryStore;
import com.example.cryptoapp.Browser.BrowserPreferences;
import com.example.cryptoapp.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** 带共享元素感动画和可删除历史建议的搜索入口。 */
public class EnterSearchStringFragment extends BaseActivity {
    private static final long DURATION = 280L;
    private final PathInterpolator emphasized = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final SearchHistoryAdapter historyAdapter = new SearchHistoryAdapter();
    private MaterialCardView searchCard;
    private MaterialCardView historyCard;
    private EditText searchInput;
    private View scrim;
    private BrowserHistoryStore historyStore;
    private float initialTranslation;
    private boolean finishing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_enter_search_string);
        searchCard = findViewById(R.id.input_layout);
        historyCard = findViewById(R.id.searchHistoryCard);
        searchInput = findViewById(R.id.search_text_tv);
        scrim = findViewById(R.id.scrim);
        historyStore = new BrowserHistoryStore(this);
        RecyclerView historyList = findViewById(R.id.searchHistoryList);
        // RecyclerView 不会自行排列子项；缺少 LayoutManager 时即使仓库中有记录也只会跳过绘制。
        historyList.setLayoutManager(new LinearLayoutManager(this));
        historyList.setAdapter(historyAdapter);
        findViewById(R.id.cancelSearch).setOnClickListener(v -> closeWithAnimation());
        findViewById(R.id.clearSearchHistory).setOnClickListener(v -> confirmClearHistory());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { closeWithAnimation(); }
        });
        searchInput.setOnEditorActionListener(this::onEditorAction);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshHistory(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchCard.post(this::playEnterAnimation);
        refreshHistory();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshHistory();
    }

    private void refreshHistory() {
        SharedPreferences settings = getSharedPreferences(BrowserPreferences.CONFIG, MODE_PRIVATE);
        boolean visible = settings.getBoolean(BrowserPreferences.SHOW_SEARCH_HISTORY, true);
        List<BrowserHistoryEntry> entries = visible
                ? historyStore.search(searchInput == null ? "" : searchInput.getText().toString())
                : new ArrayList<>();
        if (entries.size() > 6) entries = entries.subList(0, 6);
        historyAdapter.setEntries(entries);
        historyCard.setVisibility(visible && !entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmClearHistory() {
        new MaterialAlertDialogBuilder(this).setTitle("删除全部历史记录？")
                .setMessage("此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部删除", (dialog, which) -> { historyStore.clear(); refreshHistory(); })
                .show();
    }

    private void openHistory(BrowserHistoryEntry entry) {
        startActivity(new Intent(this, SearchActivity.class).putExtra("web_address", entry.getUrl()));
        finish();
    }

    private void playEnterAnimation() {
        int originY = getIntent().getIntExtra("y", 0);
        int[] target = new int[2];
        searchCard.getLocationOnScreen(target);
        initialTranslation = originY == 0 ? dp(24) : originY - target[1];
        searchCard.setTranslationY(initialTranslation);
        searchCard.setScaleX(0.94f);
        searchCard.setScaleY(0.94f);
        scrim.setAlpha(0f);
        historyCard.setAlpha(0f);
        searchCard.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(DURATION).setInterpolator(emphasized).start();
        scrim.animate().alpha(1f).setDuration(DURATION).setInterpolator(emphasized).start();
        historyCard.animate().alpha(1f).setStartDelay(100L).setDuration(220L).start();
        searchInput.requestFocus();
        searchInput.postDelayed(() -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT), 180L);
    }

    private boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
        boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
        if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || enter) {
            String query = searchInput.getText().toString().trim();
            if (!query.isEmpty()) {
                startActivity(new Intent(this, SearchActivity.class).putExtra("web_address", query));
                finish();
            }
            return true;
        }
        return false;
    }

    private void closeWithAnimation() {
        if (finishing) return;
        finishing = true;
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        searchCard.animate().translationY(initialTranslation).scaleX(0.94f).scaleY(0.94f).setDuration(DURATION)
                .setInterpolator(emphasized).withEndAction(() -> { finish(); overridePendingTransition(0, 0); }).start();
        scrim.animate().alpha(0f).setDuration(DURATION).setInterpolator(emphasized).start();
        historyCard.animate().alpha(0f).setDuration(140L).start();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private final class SearchHistoryAdapter extends RecyclerView.Adapter<SearchHistoryAdapter.Holder> {
        private final List<BrowserHistoryEntry> entries = new ArrayList<>();
        void setEntries(List<BrowserHistoryEntry> values) { entries.clear(); entries.addAll(values); notifyDataSetChanged(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_history, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) { holder.bind(entries.get(position)); }
        @Override public int getItemCount() { return entries.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            private final TextView title = itemView.findViewById(R.id.historyTitle);
            private final TextView url = itemView.findViewById(R.id.historyUrl);
            Holder(View itemView) { super(itemView); }
            void bind(BrowserHistoryEntry entry) {
                title.setText(entry.getTitle());
                url.setText(entry.getUrl());
                itemView.setOnClickListener(v -> openHistory(entry));
                itemView.findViewById(R.id.deleteHistory).setOnClickListener(v -> {
                    historyStore.delete(entry.getId());
                    refreshHistory();
                });
            }
        }
    }
}
