/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.bluetooth.Message;
import nie.translator.rtranslator.bluetooth.Peer;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.voice_translation.SpokenLanguagePicker;
import nie.translator.rtranslator.voice_translation.guest.EchoGuard;
import nie.translator.rtranslator.voice_translation.guest.GuestSession;
import nie.translator.rtranslator.voice_translation.history.ConversationHistoryStore;
import nie.translator.rtranslator.voice_translation.StreamingSpeechChunker;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApiResult;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerMultiListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;


public class WalkieTalkieService extends VoiceTranslationService {
    private static final int TIME_BEFORE_REACTIVATING_TTS_AFTER_VOICE_END = 500;
    //properties
    public static final int SPEECH_BEAM_SIZE = 1;
    public static final int TRANSLATOR_BEAM_SIZE = 1;
    private static final int SPEECH_STREAM_TIMEOUT_MS = 20000;

    // commands
    public static final int CHANGE_FIRST_LANGUAGE = 22;
    public static final int CHANGE_SECOND_LANGUAGE = 23;
    public static final int GET_FIRST_LANGUAGE = 24;
    public static final int GET_SECOND_LANGUAGE = 25;
    public static final int START_MANUAL_RECOGNITION = 26;
    public static final int STOP_MANUAL_RECOGNITION = 27;
    public static final int START_RECOGNIZING_FIRST_LANGUAGE = 28;
    public static final int STOP_RECOGNIZING_FIRST_LANGUAGE = 29;
    public static final int START_RECOGNIZING_SECOND_LANGUAGE = 30;
    public static final int STOP_RECOGNIZING_SECOND_LANGUAGE = 31;
    public static final int START_RECOGNIZING_AUTO_LANGUAGE = 32;
    public static final int STOP_RECOGNIZING_AUTO_LANGUAGE = 33;
    // callbacks
    public static final int ON_FIRST_LANGUAGE = 22;
    public static final int ON_SECOND_LANGUAGE = 23;
    private RecognizerMultiListener speechRecognizerCallback;
    private RecognizerListener speechRecognizerSingleCallback;

    // objects
    private Translator translator;
    private Recognizer speechRecognizer;
    private CustomLocale firstLanguage;
    private CustomLocale secondLanguage;
    private Translator.TranslateListener firstResultTranslateListener;
    private Translator.TranslateListener secondResultTranslateListener;
    private final long conversationStartedAt = System.currentTimeMillis();
    private final EchoGuard guestEchoGuard = new EchoGuard();
    private boolean textFromGuestLink = false;   // true while we handle a text that came through the guest link, not through our mic
    private boolean guestRecognizing = false;   // true while we recognize what a guest said through the guest link
    private volatile double firstLanguageAudioProbability = -1;   // from the last automatic recognition, -1 if unknown
    // streaming speech (the translation is spoken phrase by phrase while it is generated)
    private final StreamingSpeechChunker speechChunker = new StreamingSpeechChunker();
    private final Object speechStreamLock = new Object();
    private final HashSet<String> languagesNotifiedAsMissingVoice = new HashSet<>();
    private boolean speechStreamPending = false;   // true between the first spoken phrase and the final result of a translation
    private boolean micRestartDeferred = false;    // true if the tts finished its queue while more phrases were still expected
    private final Runnable speechStreamTimeout = new Runnable() {
        @Override
        public void run() {
            if (abortSpeechStream()) {
                startVoiceRecorder();
                notifyMicActivated();
            }
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        translator = ((Global) getApplication()).getTranslator();
        GuestSession.setHost(guestHost);
        speechRecognizer = ((Global) getApplication()).getSpeechRecognizer();
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        isAudioMute = !sharedPreferences.getBoolean("walkieTalkieAutoTTS", true);
        clientHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull android.os.Message message) {
                int command = message.getData().getInt("command", -1);
                if (command != -1) {
                    if (!WalkieTalkieService.super.executeCommand(command, message.getData())) {
                        switch (command) {
                            case START_SOUND: {
                                isAudioMute = false;
                                SharedPreferences sharedPreferences = WalkieTalkieService.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                                SharedPreferences.Editor editor = sharedPreferences.edit();
                                editor.putBoolean("walkieTalkieAutoTTS", true);
                                editor.apply();
                                break;
                            }
                            case STOP_SOUND: {
                                isAudioMute = true;
                                SharedPreferences sharedPreferences = WalkieTalkieService.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                                SharedPreferences.Editor editor = sharedPreferences.edit();
                                editor.putBoolean("walkieTalkieAutoTTS", false);
                                editor.apply();
                                if(ttsEngine != null) {
                                    ttsEngine.stop();
                                }
                                break;
                            }
                            case CHANGE_FIRST_LANGUAGE:
                                CustomLocale newFirstLanguage = (CustomLocale) message.getData().getSerializable("language");
                                if (!firstLanguage.equals(newFirstLanguage)) {
                                    firstLanguage = newFirstLanguage;
                                }
                                break;
                            case CHANGE_SECOND_LANGUAGE:
                                CustomLocale newSecondLanguage = (CustomLocale) message.getData().getSerializable("language");
                                if (!secondLanguage.equals(newSecondLanguage)) {
                                    secondLanguage = newSecondLanguage;
                                }
                                break;
                            case GET_FIRST_LANGUAGE:
                                Bundle bundle = new Bundle();
                                bundle.putInt("callback", ON_FIRST_LANGUAGE);
                                bundle.putSerializable("language", firstLanguage);
                                WalkieTalkieService.super.notifyToClient(bundle);
                                break;
                            case GET_SECOND_LANGUAGE:
                                Bundle bundle2 = new Bundle();
                                bundle2.putInt("callback", ON_SECOND_LANGUAGE);
                                bundle2.putSerializable("language", secondLanguage);
                                WalkieTalkieService.super.notifyToClient(bundle2);
                                break;
                            case RECEIVE_TEXT:
                                String text = message.getData().getString("text", null);
                                if (text != null) {
                                    //we stop speech recognition
                                    stopVoiceRecorder();
                                    notifyMicDeactivated();   // we notify the client
                                    translator.detectLanguage(new NeuralNetworkApiResult(text), true, new CustomLocale[]{firstLanguage, secondLanguage}, new Translator.DetectLanguageListener() {
                                        @Override
                                        public void onDetectedText(final NeuralNetworkApiResult result) {
                                            // here the result returns with the same language as the text sent from the Fragment editText
                                            if (result.getLanguage().equalsLanguage(firstLanguage)) {
                                                translator.translate(result.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, Global.RTranslatorMode.WALKIE_TALKIE_MODE, firstResultTranslateListener);
                                            } else {
                                                translator.translate(result.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, Global.RTranslatorMode.WALKIE_TALKIE_MODE, secondResultTranslateListener);
                                            }
                                        }

                                        @Override
                                        public void onFailure(int[] reasons, long value) {
                                            WalkieTalkieService.super.notifyError(reasons,value);
                                        }
                                    });
                                }
                                break;
                            case START_MANUAL_RECOGNITION:
                                isMicAutomatic = false;
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.setManualMode(true);
                                }
                                break;
                            case STOP_MANUAL_RECOGNITION:
                                isMicAutomatic = true;
                                if(manualRecognizingFirstLanguage || manualRecognizingSecondLanguage || manualRecognizingAutoLanguage){
                                    if(mVoiceRecorder != null) {
                                        mVoiceRecorder.stop();
                                    }
                                    manualRecognizingFirstLanguage = false;
                                    manualRecognizingSecondLanguage = false;
                                    manualRecognizingAutoLanguage = false;
                                }
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.setManualMode(false);
                                }
                                break;
                            case START_RECOGNIZING_FIRST_LANGUAGE:
                                if(!manualRecognizingSecondLanguage && !isMicAutomatic) {
                                    manualRecognizingFirstLanguage = true;
                                    if(mVoiceRecorder != null) {
                                        mVoiceRecorder.startRecording();
                                    }
                                    Log.d("voice", "started manual listening left");
                                }
                                break;
                            case STOP_RECOGNIZING_FIRST_LANGUAGE:
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.stopRecording();
                                }
                                Log.d("voice", "stopped manual listening left");
                                break;
                            case START_RECOGNIZING_SECOND_LANGUAGE:
                                if(!manualRecognizingFirstLanguage && !isMicAutomatic) {
                                    manualRecognizingSecondLanguage = true;
                                    if(mVoiceRecorder != null) {
                                        mVoiceRecorder.startRecording();
                                    }
                                    Log.d("voice", "started manual listening right");
                                }
                                break;
                            case STOP_RECOGNIZING_SECOND_LANGUAGE:
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.stopRecording();
                                }
                                Log.d("voice", "stopped manual listening right");
                                break;
                            case START_RECOGNIZING_AUTO_LANGUAGE:
                                if(!manualRecognizingAutoLanguage && !isMicAutomatic) {
                                    manualRecognizingAutoLanguage = true;
                                    if(mVoiceRecorder != null) {
                                        mVoiceRecorder.startRecording();
                                    }
                                }
                                break;
                            case STOP_RECOGNIZING_AUTO_LANGUAGE:
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.stopRecording();
                                }
                                break;
                        }
                    }
                }
                return false;
            }
        });
        mVoiceCallback = new Recorder.SimpleCallback() {
            @Override
            public void onVoiceStart() {
                super.onVoiceStart();
                if(ttsEngine != null){
                    mainTTSHandler.removeCallbacksAndMessages(null);  //cancel an eventual delayed resume command
                    ttsEngine.pause();
                }
                // we notify the client
                WalkieTalkieService.super.notifyVoiceStart();
            }

            @Override
            public void onVoice(@NonNull float[] data, int size) {
                super.onVoice(data,size);
                if(manualRecognizingFirstLanguage){
                    // we start the speech recognition in only the first language
                    speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, firstLanguage.getCode());

                } else if (manualRecognizingSecondLanguage) {
                    // we start the speech recognition in only the second language
                    speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, secondLanguage.getCode());

                }else if(manualRecognizingAutoLanguage){
                    // we start the speech recognition in both languages
                    speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, firstLanguage.getCode(), secondLanguage.getCode());

                }else if(isMicAutomatic) {
                    // we start the speech recognition in both languages
                    speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, firstLanguage.getCode(), secondLanguage.getCode());
                }
            }

            @Override
            public void onVoiceEnd() {
                super.onVoiceEnd();
                // we notify the client
                WalkieTalkieService.super.notifyVoiceEnd();
                // we resume tts after a delay, in case the user has not done speaking
                mainTTSHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if(ttsEngine != null) ttsEngine.resume();
                    }
                }, TIME_BEFORE_REACTIVATING_TTS_AFTER_VOICE_END);
            }

            @Override
            public void onVolumeLevel(float volumeLevel) {
                super.onVolumeLevel(volumeLevel);
                // we notify the client
                WalkieTalkieService.super.notifyVolumeLevel(volumeLevel);
            }
        };
        speechRecognizerCallback = new RecognizerMultiListener() {
            @Override
            public void onSpeechLanguageProbability(double firstLanguageProbability) {
                firstLanguageAudioProbability = firstLanguageProbability;
            }

            @Override
            public void onSpeechRecognizedResult(String text1, String languageCode1, double confidenceScore1, String text2, String languageCode2, double confidenceScore2) {
                NeuralNetworkApiResult firstResult = new NeuralNetworkApiResult(text1, confidenceScore1, true);
                NeuralNetworkApiResult secondResult = new NeuralNetworkApiResult(text2, confidenceScore2, true);
                compareResults(firstResult, secondResult);
                manualRecognizingAutoLanguage = false;
            }

            @Override
            public void onError(int[] reasons, long value) {
                manualRecognizingAutoLanguage = false;
                //we restart the mic here
                startVoiceRecorder();
                notifyMicActivated();
            }
        };
        speechRecognizerSingleCallback = new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                if (guestRecognizing && !manualRecognizingFirstLanguage && !manualRecognizingSecondLanguage) {
                    // what a guest said through the guest link
                    guestRecognizing = false;
                    if (text == null || text.trim().isEmpty()) {
                        startVoiceRecorder();
                        notifyMicActivated();
                    } else {
                        textFromGuestLink = true;
                        translate(text, secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                        textFromGuestLink = false;
                    }
                    return;
                }
                if(manualRecognizingFirstLanguage){
                    translate(text, firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
                } else if (manualRecognizingSecondLanguage) {
                    translate(text, secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                }
                manualRecognizingFirstLanguage = false;
                manualRecognizingSecondLanguage = false;
            }

            @Override
            public void onError(int[] reasons, long value) {
                boolean wasGuest = guestRecognizing;
                guestRecognizing = false;
                manualRecognizingFirstLanguage = false;
                manualRecognizingSecondLanguage = false;
                if (wasGuest) {
                    startVoiceRecorder();
                }
                notifyMicActivated();
            }
        };
        firstResultTranslateListener = new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, String[] synonyms, long resultID, boolean isFinal, ResultType resultType, CustomLocale inputLanguage, CustomLocale outputLanguage) {
                ((Global) getApplication()).getTTSLanguages(true, new Global.GetLocalesListListener() {
                    @Override
                    public void onSuccess(ArrayList<CustomLocale> ttsLanguages) {
                        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, translatedText), inputLanguage, outputLanguage, resultID, true, isFinal);
                        GuestSession.publish("a" + resultID, true, textToTranslate, translatedText, isFinal);
                        if (isFinal && GuestSession.isActive()) {
                            guestEchoGuard.remember(translatedText, System.currentTimeMillis());
                        }
                        // we speak the translation phrase by phrase while it is still being generated
                        boolean canSpeak = CustomLocale.containsLanguage(ttsLanguages, outputLanguage);
                        boolean restartMic = speakStreaming(textToTranslate, translatedText, resultID, true, isFinal, inputLanguage, outputLanguage, canSpeak);
                        WalkieTalkieService.super.notifyMessage(message);
                        // we save every new message in the exchanged messages so that the fragment can restore them
                        WalkieTalkieService.super.addOrUpdateMessage(message);
                        //if the tts is not active (or has already spoken everything) we restart the mic here
                        if(restartMic){
                            startVoiceRecorder();
                            notifyMicActivated();
                        }
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, translatedText), inputLanguage, outputLanguage, resultID, true, isFinal);
                        GuestSession.publish("a" + resultID, true, textToTranslate, translatedText, isFinal);
                        if (isFinal && GuestSession.isActive()) {
                            guestEchoGuard.remember(translatedText, System.currentTimeMillis());
                        }
                        WalkieTalkieService.super.notifyMessage(message);
                        // we save every new message in the exchanged messages so that the fragment can restore them
                        WalkieTalkieService.super.addOrUpdateMessage(message);
                        //we restart the mic here
                        startVoiceRecorder();
                        notifyMicActivated();
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieService.super.notifyError(reasons,value);
                //if the tts is not active (or a streamed speech was cut short and has nothing left to say) we restart the mic here
                boolean streamNeedsMicRestart = abortSpeechStream();
                if(ttsEngine == null || !ttsEngine.isActive() || isAudioMute || streamNeedsMicRestart){
                    startVoiceRecorder();
                    notifyMicActivated();
                }
            }
        };
        secondResultTranslateListener = new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, String[] synonyms, long resultID, boolean isFinal, ResultType resultType, CustomLocale inputLanguage, CustomLocale outputLanguage) {
                ((Global) getApplication()).getTTSLanguages(true, new Global.GetLocalesListListener() {
                    @Override
                    public void onSuccess(ArrayList<CustomLocale> ttsLanguages) {
                        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, translatedText), inputLanguage, outputLanguage, resultID, false, isFinal);
                        GuestSession.publish("b" + resultID, false, textToTranslate, translatedText, isFinal);
                        // we speak the translation phrase by phrase while it is still being generated
                        boolean canSpeak = CustomLocale.containsLanguage(ttsLanguages, outputLanguage);
                        boolean restartMic = speakStreaming(textToTranslate, translatedText, resultID, false, isFinal, inputLanguage, outputLanguage, canSpeak);
                        WalkieTalkieService.super.notifyMessage(message);
                        // we save every new message in the exchanged messages so that the fragment can restore them
                        WalkieTalkieService.super.addOrUpdateMessage(message);
                        //if the tts is not active (or has already spoken everything) we restart the mic here
                        if(restartMic){
                            startVoiceRecorder();
                            notifyMicActivated();
                        }
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, translatedText), firstLanguage, outputLanguage, resultID, false, isFinal);
                        GuestSession.publish("b" + resultID, false, textToTranslate, translatedText, isFinal);
                        WalkieTalkieService.super.notifyMessage(message);
                        // we save every new message in the exchanged messages so that the fragment can restore them
                        WalkieTalkieService.super.addOrUpdateMessage(message);
                        //we restart the mic here
                        startVoiceRecorder();
                        notifyMicActivated();
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieService.super.notifyError(reasons,value);
                //if the tts is not active (or a streamed speech was cut short and has nothing left to say) we restart the mic here
                boolean streamNeedsMicRestart = abortSpeechStream();
                if(ttsEngine == null || !ttsEngine.isActive() || isAudioMute || streamNeedsMicRestart){
                    startVoiceRecorder();
                    notifyMicActivated();
                }
            }
        };
        //voice recorder initialization
        initializeVoiceRecorder();
    }

    /**
     * Speaks the translation phrase by phrase while it is still being generated, instead of waiting for the whole text.
     * Must be called for every partial and final result of a translation.
     * @return true if the caller must restart the mic (nothing is being spoken and nothing more will be)
     */
    private boolean speakStreaming(String textToTranslate, String translatedText, long resultID, boolean isMine, boolean isFinal, CustomLocale inputLanguage, CustomLocale outputLanguage, boolean languageHasVoice) {
        synchronized (speechStreamLock) {
            // if the phone of a guest reads the conversation aloud, this phone stays silent for what its own user says
            boolean guestHears = isMine && GuestSession.isGuestListening();
            boolean canSpeak = languageHasVoice && !guestHears && !isAudioMute && ttsEngine != null && ttsEngine.isActive();
            if (!canSpeak) {
                if (isFinal && !languageHasVoice && !guestHears && !isAudioMute) {
                    notifyVoiceMissing(outputLanguage);
                }
                boolean micWasLeftOff = speechStreamPending && (micRestartDeferred || ttsEngine == null || !ttsEngine.isBusy());
                clearSpeechStream();
                return isFinal || micWasLeftOff;
            }
            if (!isFinal && !isStreamingSpeechEnabled()) {
                return false;   // old behaviour: speak only when the translation is complete
            }
            if (isFinal) {
                speechStreamPending = false;
                mainHandler.removeCallbacks(speechStreamTimeout);
            }
            String chunk = speechChunker.nextChunk(resultID, translatedText, isFinal);
            if (chunk != null) {
                if (!isFinal) {
                    speechStreamPending = true;
                    // safety net: if the translation never completes, we do not want to leave the mic off forever
                    mainHandler.removeCallbacks(speechStreamTimeout);
                    mainHandler.postDelayed(speechStreamTimeout, SPEECH_STREAM_TIMEOUT_MS);
                }
                micRestartDeferred = false;
                speak(new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, chunk), inputLanguage, outputLanguage, resultID, isMine, isFinal), outputLanguage);
                return false;
            }
            if (isFinal) {
                // nothing left to speak: if the tts has already finished the previous phrases the mic is still off
                boolean restartMic = micRestartDeferred || !ttsEngine.isBusy();
                micRestartDeferred = false;
                return restartMic;
            }
            return false;
        }
    }

    /**
     * Saves the conversation that is ending to the History, on this phone, if the user has not turned that off.
     */
    private void saveConversationToHistory() {
        try {
            final Context context = getApplicationContext();
            if (!ConversationHistoryStore.isEnabled(context) || firstLanguage == null || secondLanguage == null) {
                return;
            }
            final ConversationHistoryStore.Conversation conversation = new ConversationHistoryStore.Conversation();
            conversation.startedAt = conversationStartedAt;
            conversation.firstLanguage = firstLanguage.getDisplayNameWithoutTTS();
            conversation.secondLanguage = secondLanguage.getDisplayNameWithoutTTS();
            for (GuiMessage message : new ArrayList<>(getMessages())) {
                if (message == null || !message.isFinal() || message.getMessage() == null) {
                    continue;
                }
                String translated = message.getMessage().getText();
                if (translated == null || translated.trim().isEmpty()) {
                    continue;
                }
                ConversationHistoryStore.Line line = new ConversationHistoryStore.Line();
                line.firstSpeaker = message.isMine();
                String original = message.getMessage().getTextToTranslate();
                line.original = original == null ? "" : original;
                line.translated = translated;
                conversation.lines.add(line);
            }
            if (conversation.lines.isEmpty()) {
                return;
            }
            new Thread("saveConversationHistory") {
                @Override
                public void run() {
                    ConversationHistoryStore.save(context, conversation);
                }
            }.start();
        } catch (Throwable ignored) {
            // history is a convenience, it must never disturb the end of a conversation
        }
    }

    /**
     * The guest of a guest conversation (a person who follows the conversation in a browser, without the app)
     * always uses the second language.
     */
    private final GuestSession.Host guestHost = new GuestSession.Host() {
        @Override
        public void onGuestText(final String text) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (firstLanguage == null || secondLanguage == null) {
                        return;
                    }
                    // the translation will be spoken by this phone, so the mic stays off until then (as after a spoken phrase)
                    stopVoiceRecorder();
                    textFromGuestLink = true;
                    translate(text, secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                    textFromGuestLink = false;
                }
            });
        }

        @Override
        public void onGuestAudio(final float[] samples) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (firstLanguage == null || secondLanguage == null || speechRecognizer == null) {
                        return;
                    }
                    // the guest always speaks the second language, so we recognize only that one
                    stopVoiceRecorder();
                    guestRecognizing = true;
                    speechRecognizer.recognize(samples, SPEECH_BEAM_SIZE, secondLanguage.getCode());
                }
            });
        }

        @Override
        public void onGuestSessionStarted() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (secondLanguage == null) {
                        return;
                    }
                    GuestSession.setSpeechLanguage(secondLanguage.getCode());
                    if (!"en".equals(secondLanguage.getCode())) {
                        translateGuestLabel(0);
                    }
                }
            });
        }
    };

    /**
     * Translates the few texts of the guest page into the language of the guest, one after the other.
     */
    private void translateGuestLabel(final int index) {
        if (index >= GuestSession.LABELS.length || translator == null || secondLanguage == null) {
            return;
        }
        try {
            translator.translate(GuestSession.LABELS[index], new CustomLocale("en"), secondLanguage, TRANSLATOR_BEAM_SIZE, false, Global.RTranslatorMode.WALKIE_TALKIE_MODE, new Translator.TranslateListener() {
                @Override
                public void onTranslatedText(String textToTranslate, String translatedText, String[] synonyms, long resultID, boolean isFinal, ResultType resultType, CustomLocale inputLanguage, CustomLocale outputLanguage) {
                    if (isFinal) {
                        GuestSession.setLabel(index, translatedText);
                        translateGuestLabel(index + 1);
                    }
                }

                @Override
                public void onFailure(int[] reasons, long value) {
                    // the page keeps its English texts
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean isStreamingSpeechEnabled() {
        // read every time, so a change in the settings applies without restarting the mode
        return getSharedPreferences("default", Context.MODE_PRIVATE).getBoolean("walkieTalkieStreamingTTS", true);
    }

    /**
     * Tells the user, once per language, why a translation is shown but not spoken.
     */
    private void notifyVoiceMissing(final CustomLocale language) {
        final String code = language.getLanguage();
        synchronized (languagesNotifiedAsMissingVoice) {
            if (!languagesNotifiedAsMissingVoice.add(code)) {
                return;
            }
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(WalkieTalkieService.this, getString(R.string.error_tts_voice_missing, language.getDisplayLanguage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Ends a streamed speech whose translation will not complete.
     * @return true if the mic was left off by the stream and must be restarted by the caller
     */
    private boolean abortSpeechStream() {
        synchronized (speechStreamLock) {
            boolean micWasLeftOff = speechStreamPending && (micRestartDeferred || ttsEngine == null || !ttsEngine.isBusy());
            clearSpeechStream();
            return micWasLeftOff;
        }
    }

    private void clearSpeechStream() {
        speechStreamPending = false;
        micRestartDeferred = false;
        speechChunker.reset();
        mainHandler.removeCallbacks(speechStreamTimeout);
    }

    @Override
    protected boolean deferMicRestartForSpeechStream() {
        synchronized (speechStreamLock) {
            if (speechStreamPending) {
                micRestartDeferred = true;
                return true;
            }
            return false;
        }
    }

    public void initializeVoiceRecorder(){
        if (Tools.hasPermissions(this, Global.REQUIRED_PERMISSIONS_VOICE)) {
            //voice recorder initialization
            Global global = (Global) getApplication();
            super.mVoiceRecorder = new Recorder(global, false, mVoiceCallback, null, global.getVad());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int startType = super.onStartCommand(intent, flags, startId);
        final CustomLocale finalFirstLanguage = this.firstLanguage;
        final CustomLocale finalSecondLanguage = this.secondLanguage;

        //getGroup the languages
        firstLanguage = (CustomLocale) intent.getSerializableExtra("firstLanguage");
        secondLanguage = (CustomLocale) intent.getSerializableExtra("secondLanguage");

        if(finalFirstLanguage==null || finalSecondLanguage==null ) {  //se è il primo avvio
            //we attach the speech recognition callbacks
            speechRecognizer.addMultiCallback(speechRecognizerCallback);
            speechRecognizer.addCallback(speechRecognizerSingleCallback);
        }
        return startType;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(speechStreamTimeout);
        Log.i("walkieTalkie", "WalkieTalkie service destroyed");
        saveConversationToHistory();
        GuestSession.setHost(null);
        GuestSession.stop();
        //disconnect speechRecognizerCallback
        speechRecognizer.removeMultiCallback(speechRecognizerCallback);
        speechRecognizer.removeCallback(speechRecognizerSingleCallback);
        // Stop recognizer
        speechRecognizer.stop();
        super.onDestroy();
    }

    private void compareResults(NeuralNetworkApiResult firstResult, NeuralNetworkApiResult secondResult) {
        if(isMetaText(firstResult.getText()) && !isMetaText(secondResult.getText())){
            translate(secondResult.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
            return;
        }
        if(isMetaText(secondResult.getText()) && !isMetaText(firstResult.getText())){
            translate(firstResult.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
            return;
        }
        translator.detectLanguage(firstResult, secondResult, false, new CustomLocale[]{firstLanguage, secondLanguage}, new Translator.DetectMultiLanguageListener() {
            @Override
            public void onDetectedText(NeuralNetworkApiResult firstResult, NeuralNetworkApiResult secondResult, int message) {
                if (message == ErrorCodes.BOTH_RESULTS_SUCCESS){  // if both results languages were found
                    if (firstResult.getLanguage().equalsLanguage(firstLanguage)) {
                        if (secondResult.getLanguage().equalsLanguage(secondLanguage)) {  // if both have recognized their respective language
                            compareResultsConfidence(firstResult, secondResult);
                        } else {  // if only the first result language is recognized
                            translate(firstResult.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
                        }
                    } else {
                        if (secondResult.getLanguage().equalsLanguage(secondLanguage)) {  // if only the second result language is recognized

                            translate(secondResult.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                        } else {  // if neither of them recognized his language but another (or none)
                            compareResultsConfidence(firstResult, secondResult);
                        }
                    }
                }else if(message == ErrorCodes.SECOND_RESULT_FAIL){  // if only the first result language was found
                    translate(firstResult.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
                }else if(message == ErrorCodes.FIRST_RESULT_FAIL){   // if only the second result language was found
                    translate(secondResult.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                }
                // if both results languages were not found the onFailure method is called
            }

            @Override
            public void onFailure(int[] reasons, long value) {  // if both results languages were not found
                compareResultsConfidence(firstResult, secondResult);
            }
        });
    }

    private void translate(final String textToTranslate, final CustomLocale languageInput, final CustomLocale languageOutput, int beamSize, boolean saveResults, final Translator.TranslateListener responseListener){
        if (!textFromGuestLink && languageInput != null && languageInput.equals(secondLanguage) && GuestSession.isGuestListening()
                && guestEchoGuard.isEcho(textToTranslate, System.currentTimeMillis())) {
            // our mic heard the phone of the guest reading aloud what we said: we do not translate it back
            startVoiceRecorder();
            notifyMicActivated();
            return;
        }
        if(!isMetaText(textToTranslate)) {
            translator.translate(textToTranslate, languageInput, languageOutput, beamSize, saveResults, Global.RTranslatorMode.WALKIE_TALKIE_MODE, responseListener);
        }else{
            Log.i("walkieTalkie", "skipped translation of: "+textToTranslate+" because it is meta text");
            //we restart the mic here
            startVoiceRecorder();
            notifyMicActivated();
        }
    }

    private void compareResultsConfidence(NeuralNetworkApiResult firstResult, NeuralNetworkApiResult secondResult) {
        // the text did not settle which language was spoken: we trust the sound of the audio first, then the confidence
        if (SpokenLanguagePicker.isFirstLanguage(firstLanguageAudioProbability, firstResult.getConfidenceScore(), secondResult.getConfidenceScore())) {
            translate(firstResult.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
        } else {
            translate(secondResult.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
        }
    }

    public static class WalkieTalkieServiceCommunicator extends VoiceTranslationServiceCommunicator {
        private ArrayList<LanguageListener> firstLanguageListeners = new ArrayList<>();
        private ArrayList<LanguageListener> secondLanguageListeners = new ArrayList<>();

        public WalkieTalkieServiceCommunicator(int id) {
            super(id);
            super.serviceHandler = new Handler(new Handler.Callback() {
                @Override
                public boolean handleMessage(@NonNull android.os.Message msg) {
                    msg.getData().setClassLoader(Peer.class.getClassLoader());
                    int callbackMessage = msg.getData().getInt("callback", -1);
                    Bundle data= msg.getData();
                    if(!executeCallback(callbackMessage,data)){
                        switch (callbackMessage){
                            case ON_FIRST_LANGUAGE:{
                                CustomLocale language = (CustomLocale) data.getSerializable("language");
                                while (!firstLanguageListeners.isEmpty()) {
                                    firstLanguageListeners.remove(0).onLanguage(language);
                                }
                                break;
                            }
                            case ON_SECOND_LANGUAGE:{
                                CustomLocale language = (CustomLocale) data.getSerializable("language");
                                while (!secondLanguageListeners.isEmpty()) {
                                    secondLanguageListeners.remove(0).onLanguage(language);
                                }
                                break;
                            }
                        }
                    }
                    return true;
                }
            });
        }

        public void getFirstLanguage(LanguageListener responseListener){
            firstLanguageListeners.add(responseListener);
            if (firstLanguageListeners.size() == 1) {
                Bundle bundle = new Bundle();
                bundle.putInt("command", GET_FIRST_LANGUAGE);
                super.sendToService(bundle);
            }
        }

        public void getSecondLanguage(LanguageListener responseListener){
            secondLanguageListeners.add(responseListener);
            if (secondLanguageListeners.size() == 1) {
                Bundle bundle = new Bundle();
                bundle.putInt("command", GET_SECOND_LANGUAGE);
                super.sendToService(bundle);
            }
        }

        public void changeFirstLanguage(CustomLocale language) {
            Bundle bundle = new Bundle();
            bundle.putInt("command", WalkieTalkieService.CHANGE_FIRST_LANGUAGE);
            bundle.putSerializable("language", language);
            super.sendToService(bundle);
        }

        public void changeSecondLanguage(CustomLocale language) {
            Bundle bundle = new Bundle();
            bundle.putInt("command", WalkieTalkieService.CHANGE_SECOND_LANGUAGE);
            bundle.putSerializable("language", language);
            super.sendToService(bundle);
        }

        public void startManualRecognition() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_MANUAL_RECOGNITION);
            super.sendToService(bundle);
        }

        public void stopManualRecognition() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_MANUAL_RECOGNITION);
            super.sendToService(bundle);
        }

        public void startRecognizingFirstLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_RECOGNIZING_FIRST_LANGUAGE);
            super.sendToService(bundle);
        }

        public void stopRecognizingFirstLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_RECOGNIZING_FIRST_LANGUAGE);
            super.sendToService(bundle);
        }

        public void startRecognizingSecondLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_RECOGNIZING_SECOND_LANGUAGE);
            super.sendToService(bundle);
        }

        public void stopRecognizingSecondLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_RECOGNIZING_SECOND_LANGUAGE);
            super.sendToService(bundle);
        }

        public void startRecognizingAutoLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_RECOGNIZING_AUTO_LANGUAGE);
            super.sendToService(bundle);
        }

        public void stopRecognizingAutoLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_RECOGNIZING_AUTO_LANGUAGE);
            super.sendToService(bundle);
        }
    }

    public interface LanguageListener{
        void onLanguage(CustomLocale language);
    }
}