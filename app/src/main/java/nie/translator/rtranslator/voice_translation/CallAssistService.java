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

package nie.translator.rtranslator.voice_translation;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Experimental "call mode". Android only lets an app keep listening with the microphone during a phone call
 * if the app has an accessibility service turned on. This service exists only for that: it does nothing,
 * it reads nothing from the screen and it handles no events.
 */
public class CallAssistService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // nothing: we do not look at what happens on the screen
    }

    @Override
    public void onInterrupt() {
    }

    /** @return true if the user has turned this service on in the accessibility settings */
    public static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            String full = new ComponentName(context, CallAssistService.class).flattenToString();
            String shortName = new ComponentName(context, CallAssistService.class).flattenToShortString();
            for (String service : enabled.split(":")) {
                if (service.equalsIgnoreCase(full) || service.equalsIgnoreCase(shortName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
