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

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Keeps past conversations on the phone, one small file each, in the app's private storage.
 * Nothing here is sent anywhere. Saving can be turned off in the settings.
 */
public final class ConversationHistoryStore {
    public static final String PREFERENCE_KEY = "saveConversationHistory";
    private static final int MAX_CONVERSATIONS = 200;   // older ones that are not favourites are removed

    public static class Line {
        public boolean firstSpeaker;
        public String name = "";   // who spoke, when a guest of a room gave a name
        public String original = "";
        public String translated = "";
    }

    public static class Conversation {
        public String id = "";
        public long startedAt;
        public String firstLanguage = "";
        public String secondLanguage = "";
        public boolean favorite;
        public final ArrayList<Line> lines = new ArrayList<>();
    }

    private ConversationHistoryStore() {
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("default", Context.MODE_PRIVATE).getBoolean(PREFERENCE_KEY, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("default", Context.MODE_PRIVATE).edit().putBoolean(PREFERENCE_KEY, enabled).apply();
    }

    private static File directory(Context context) {
        File directory = new File(context.getFilesDir(), "history");
        directory.mkdirs();
        return directory;
    }

    private static File file(Context context, String id) {
        // ids are numbers made by this class, but we never trust a name with a path in it
        return new File(directory(context), id.replaceAll("[^0-9A-Za-z_-]", "") + ".json");
    }

    public static synchronized void save(Context context, Conversation conversation) {
        try {
            if (conversation.id.isEmpty()) {
                conversation.id = String.valueOf(conversation.startedAt > 0 ? conversation.startedAt : System.currentTimeMillis());
            }
            JSONObject json = new JSONObject();
            json.put("id", conversation.id);
            json.put("startedAt", conversation.startedAt);
            json.put("firstLanguage", conversation.firstLanguage);
            json.put("secondLanguage", conversation.secondLanguage);
            json.put("favorite", conversation.favorite);
            JSONArray lines = new JSONArray();
            for (Line line : conversation.lines) {
                JSONObject item = new JSONObject();
                item.put("first", line.firstSpeaker);
                item.put("name", line.name);
                item.put("original", line.original);
                item.put("translated", line.translated);
                lines.put(item);
            }
            json.put("lines", lines);
            try (FileOutputStream output = new FileOutputStream(file(context, conversation.id))) {
                output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            trim(context);
        } catch (Throwable ignored) {
            // history is a convenience, a failed save must never disturb a conversation
        }
    }

    @Nullable
    private static Conversation read(File file) {
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream input = new FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            }
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Conversation conversation = new Conversation();
            conversation.id = json.optString("id");
            conversation.startedAt = json.optLong("startedAt");
            conversation.firstLanguage = json.optString("firstLanguage");
            conversation.secondLanguage = json.optString("secondLanguage");
            conversation.favorite = json.optBoolean("favorite");
            JSONArray lines = json.optJSONArray("lines");
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    JSONObject item = lines.optJSONObject(i);
                    if (item == null) continue;
                    Line line = new Line();
                    line.firstSpeaker = item.optBoolean("first");
                    line.name = item.optString("name");
                    line.original = item.optString("original");
                    line.translated = item.optString("translated");
                    conversation.lines.add(line);
                }
            }
            return conversation.id.isEmpty() ? null : conversation;
        } catch (Throwable error) {
            return null;
        }
    }

    /** @return the saved conversations, newest first */
    public static synchronized ArrayList<Conversation> list(Context context, boolean favoritesOnly) {
        ArrayList<Conversation> result = new ArrayList<>();
        File[] files = directory(context).listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.getName().endsWith(".json")) continue;
                Conversation conversation = read(file);
                if (conversation != null && (!favoritesOnly || conversation.favorite)) {
                    result.add(conversation);
                }
            }
        }
        Collections.sort(result, new Comparator<Conversation>() {
            @Override
            public int compare(Conversation a, Conversation b) {
                return Long.compare(b.startedAt, a.startedAt);
            }
        });
        return result;
    }

    public static synchronized void setFavorite(Context context, String id, boolean favorite) {
        Conversation conversation = read(file(context, id));
        if (conversation != null) {
            conversation.favorite = favorite;
            save(context, conversation);
        }
    }

    public static synchronized void delete(Context context, String id) {
        File file = file(context, id);
        if (file.exists()) {
            file.delete();
        }
    }

    private static void trim(Context context) {
        ArrayList<Conversation> all = list(context, false);
        for (int i = all.size() - 1; i >= 0 && all.size() > MAX_CONVERSATIONS; i--) {
            if (!all.get(i).favorite) {
                delete(context, all.get(i).id);
                all.remove(i);
            }
        }
    }
}
