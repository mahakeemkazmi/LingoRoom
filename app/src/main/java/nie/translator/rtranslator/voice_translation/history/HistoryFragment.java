/*
 * Copyright 2026 Exelere.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation.history;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

import nie.translator.rtranslator.R;
import nie.translator.rtranslator.voice_translation.BottomNavBar;
import nie.translator.rtranslator.voice_translation.VoiceTranslationActivity;

/**
 * Past conversations saved on this phone. With the favoritesOnly argument it shows only the starred ones.
 */
public class HistoryFragment extends Fragment {
    public static final String ARGUMENT_FAVORITES_ONLY = "favoritesOnly";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<ConversationHistoryStore.Conversation> conversations = new ArrayList<>();
    private VoiceTranslationActivity activity;
    private boolean favoritesOnly;
    private TextView emptyText;
    private RowsAdapter adapter;

    public static HistoryFragment newInstance(boolean favoritesOnly) {
        HistoryFragment fragment = new HistoryFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean(ARGUMENT_FAVORITES_ONLY, favoritesOnly);
        fragment.setArguments(arguments);
        return fragment;
    }

    public boolean isFavoritesOnly() {
        Bundle arguments = getArguments();
        return arguments != null && arguments.getBoolean(ARGUMENT_FAVORITES_ONLY, false);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (VoiceTranslationActivity) requireActivity();
        favoritesOnly = isFavoritesOnly();
        ((TextView) view.findViewById(R.id.historyTitle)).setText(favoritesOnly ? R.string.home_nav_favorites : R.string.home_nav_history);
        emptyText = view.findViewById(R.id.historyEmpty);
        emptyText.setText(favoritesOnly ? R.string.favorites_empty : R.string.history_empty);
        ListView list = view.findViewById(R.id.historyList);
        adapter = new RowsAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, row, position, id) -> showConversation(conversations.get(position)));
        BottomNavBar.setup(view, activity, favoritesOnly ? BottomNavBar.FAVORITES : BottomNavBar.HISTORY);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        final Context context = activity.getApplicationContext();
        new Thread("historyLoad") {
            @Override
            public void run() {
                final ArrayList<ConversationHistoryStore.Conversation> loaded = ConversationHistoryStore.list(context, favoritesOnly);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    conversations.clear();
                    conversations.addAll(loaded);
                    adapter.notifyDataSetChanged();
                    emptyText.setVisibility(conversations.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }
        }.start();
    }

    private static String title(ConversationHistoryStore.Conversation conversation) {
        if (conversation.secondLanguage.isEmpty()) {
            return conversation.firstLanguage;   // a group conversation: it has a name, not a pair of languages
        }
        return conversation.firstLanguage + "  \u2194  " + conversation.secondLanguage;
    }

    private static String date(ConversationHistoryStore.Conversation conversation) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(conversation.startedAt));
    }

    /**
     * @param firstLanguageOnly true for a transcript in one language: what was said in the first language as it was said,
     * and everything else in its translation
     */
    private static String transcript(ConversationHistoryStore.Conversation conversation, boolean firstLanguageOnly) {
        StringBuilder text = new StringBuilder();
        for (ConversationHistoryStore.Line line : conversation.lines) {
            String label = line.name.isEmpty() ? "" : line.name;
            if (!firstLanguageOnly && !conversation.secondLanguage.isEmpty()) {
                String language = line.firstSpeaker ? conversation.firstLanguage : conversation.secondLanguage;
                label = label.isEmpty() ? language : label + " (" + language + ")";
            }
            if (firstLanguageOnly) {
                String own = line.firstSpeaker && !line.original.isEmpty() ? line.original : line.translated;
                text.append(label.isEmpty() ? "" : label + ": ").append(own).append("\n");
            } else {
                if (!label.isEmpty()) {
                    text.append(label).append("\n");
                }
                if (!line.original.isEmpty()) {
                    text.append(line.original).append("\n");
                }
                text.append(line.translated).append("\n\n");
            }
        }
        return text.toString().trim();
    }

    private void share(ConversationHistoryStore.Conversation conversation, boolean firstLanguageOnly) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, title(conversation) + ", " + date(conversation));
            intent.putExtra(Intent.EXTRA_TEXT, title(conversation) + "\n" + date(conversation) + "\n\n" + transcript(conversation, firstLanguageOnly));
            startActivity(Intent.createChooser(intent, getString(R.string.history_share)));
        } catch (Throwable ignored) {
        }
    }

    private void showConversation(final ConversationHistoryStore.Conversation conversation) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title(conversation))
                .setMessage(transcript(conversation, false))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.history_share, (dialog, which) -> share(conversation, false));
        if (!conversation.secondLanguage.isEmpty()) {
            builder.setNeutralButton(getString(R.string.history_share_one, conversation.firstLanguage), (dialog, which) -> share(conversation, true));
        }
        builder.show();
    }

    private void confirmDelete(final ConversationHistoryStore.Conversation conversation) {
        new AlertDialog.Builder(activity)
                .setMessage(R.string.history_delete_question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.history_delete, (dialog, which) -> {
                    ConversationHistoryStore.delete(activity.getApplicationContext(), conversation.id);
                    reload();
                })
                .show();
    }

    private class RowsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return conversations.size();
        }

        @Override
        public Object getItem(int position) {
            return conversations.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView : LayoutInflater.from(parent.getContext()).inflate(R.layout.component_history_row, parent, false);
            final ConversationHistoryStore.Conversation conversation = conversations.get(position);
            ((TextView) row.findViewById(R.id.historyRowTitle)).setText(title(conversation));
            ((TextView) row.findViewById(R.id.historyRowDate)).setText(date(conversation));
            String preview = "";
            if (!conversation.lines.isEmpty()) {
                ConversationHistoryStore.Line first = conversation.lines.get(0);
                preview = first.original.isEmpty() ? first.translated : first.original;
            }
            ((TextView) row.findViewById(R.id.historyRowPreview)).setText(preview);
            ImageButton star = row.findViewById(R.id.historyRowStar);
            int starColor = ContextCompat.getColor(activity, conversation.favorite ? R.color.brand_yellow : R.color.light_gray);
            ImageViewCompat.setImageTintList(star, ColorStateList.valueOf(starColor));
            star.setContentDescription(getString(conversation.favorite ? R.string.history_remove_favorite : R.string.history_add_favorite));
            star.setOnClickListener(v -> {
                ConversationHistoryStore.setFavorite(activity.getApplicationContext(), conversation.id, !conversation.favorite);
                reload();
            });
            row.findViewById(R.id.historyRowDelete).setOnClickListener(v -> confirmDelete(conversation));
            return row;
        }
    }
}
