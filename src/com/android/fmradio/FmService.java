/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.fmradio;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Notification.BigTextStyle;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.database.Cursor;
import android.hardware.radio.RadioManager;
import android.media.AudioDevicePort;
import android.media.AudioDevicePortConfig;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.AudioManager.OnAudioPortUpdateListener;
import android.media.AudioMixPort;
import android.media.AudioPatch;
import android.media.AudioPort;
import android.media.AudioPortConfig;
import android.media.AudioRecord;
import android.media.AudioSystem;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioDeviceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.text.TextUtils;
import android.widget.Toast;
import android.util.Log;
import android.media.session.MediaSessionManager;
import android.view.KeyEvent;
import android.media.AudioAttributes;

import com.android.fmradio.FmStation.Station;
import com.android.fmradio.utils.FmStationItem;
import com.android.fmradio.CurrentStationItem;


import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

import android.os.AsyncTask;

import android.content.ComponentName;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.os.SystemProperties;

import android.provider.Settings;
/**
 * @}
 */

/**
 * Background service to control FM or do background tasks.
 */
public class FmService extends Service implements FmRecorder.OnRecorderStateChangedListener,
        CurrentStationItem.FmObserverListener {
    // Logging
    private static final String TAG = "FmService";

    // Broadcast messages from other sounder APP to FM service
    private static final String SOUND_POWER_DOWN_MSG = "com.android.music.musicservicecommand.sprd";
    private static final String FM_SEEK_PREVIOUS = "fmradio.seek.previous";
    private static final String FM_SEEK_NEXT = "fmradio.seek.next";
    private static final String FM_TURN_OFF = "fmradio.turnoff";
    private static final String CMDPAUSE = "pause";
    // add for storage left space check
    private static final int LEVEL_UNKNOWN = -1;
    private static final int LEVEL_NORMAL = 0;
    private static final int LEVEL_LOW = 1;
    private static final int LEVEL_FULL = 2;
    private static final String ACTION_DEVICE_STORAGE_LOW_STATE = "sprd.intent.action.DEVICE_STORAGE_STATE";
    private static final String EXTRA_CURRENT_LEVEL = "current_level";

    // HandlerThread Keys
    private static final String FM_FREQUENCY = "frequency";
    private static final String OPTION = "option";
    private static final String RECODING_FILE_NAME = "name";
    private static final String STOPRECORD = "stopRecord";

    // Headset
    private static final int HEADSET_PLUG_IN = 1;

    // Notification id
    private static final int NOTIFICATION_ID = 1;

    // ignore audio data
    private static final int AUDIO_FRAMES_TO_IGNORE_COUNT = 3;

    // Set audio policy for FM
    // should check AUDIO_POLICY_FORCE_FOR_MEDIA in audio_policy.h
    private static final int FOR_PROPRIETARY = 1;

    // FM recorder
    FmRecorder mFmRecorder = null;
    private BroadcastReceiver mSdcardListener = null;
    private int mRecordState = FmRecorder.STATE_INVALID;
    private int mRecorderErrorType = -1;
    // If eject record sdcard, should set Value false to not record.
    // Key is sdcard path(like "/storage/sdcard0"), V is to enable record or
    // not.
    private HashMap<String, Boolean> mSdcardStateMap = new HashMap<String, Boolean>();
    // The show name in save dialog but saved in service
    // If modify the save title it will be not null, otherwise it will be null
    private String mModifiedRecordingName = null;
    // record the listener list, will notify all listener in list
    private ArrayList<Record> mRecords = new ArrayList<Record>();
    // record FM whether in recording mode
    private boolean mIsInRecordingMode = false;
    // record sd card path when start recording
    private static String sRecordingSdcard = "";

    // RDS
    // PS String
    private String mPsString = "";
    // RT String
    private String mRtTextString = "";
    // Notification target class name
    private String mTargetClassName = FmMainActivity.class.getName();

    // State variables
    // Record whether FM is in native scan state
    private boolean mIsNativeScanning = false;
    // Record whether FM is in scan thread
    private boolean mIsScanning = false;
    // Record whether FM is in seeking state
    private boolean mIsNativeSeeking = false;
    // Record whether FM is in native seek
    private boolean mIsSeeking = false;
    // Record whether searching progress is canceled
    private boolean mIsStopScanCalled = false;
    // Record whether is speaker used
    private boolean mIsSpeakerUsed = false;
    // Record whether device is open
    private boolean mIsDeviceOpen = false;
    // Record whether device is ready when first to tuning or seeking
    private boolean mIsDeviceReady = false;
    // Record Power Status
    private int mPowerStatus = POWER_DOWN;

    public static int POWER_UP = 0;
    public static int DURING_POWER_UP = 1;
    public static int POWER_DOWN = 2;
    // Record whether service is init
    private boolean mIsServiceInited = false;
    // Fm power down by loss audio focus,should make power down menu item can
    // click
    private boolean mIsPowerDown = false;

    // FmRecordActivity foreground
    private boolean mIsFmActivityForeground = false;
    // Instance variables
    private Context mContext = null;
    private AudioManager mAudioManager = null;
    private MediaSessionManager mMediaSessionManager;
    private boolean mDown = false;
    private long mLastClickTime = 0;
    public static final String ACTION_FM_HEADSET_NEXT = "com.android.fmradio.HEADSET.NEXT";
    private ActivityManager mActivityManager = null;
    // private MediaPlayer mFmPlayer = null;
    private WakeLock mWakeLock = null;
    // Audio focus is held or not
    public boolean mIsAudioFocusHeld = false;
    // Focus transient lost
    private boolean mPausedByTransientLossOfFocus = false;
    // Headset plug state (0:long antenna plug in, 1:long antenna plug out)
    private int mValueHeadSetPlug = 1;
    // For bind service
    private final IBinder mBinder = new ServiceBinder();
    // Broadcast to receive the external event
    private FmServiceBroadcastReceiver mBroadcastReceiver = null;
    // Async handler
    private FmRadioServiceHandler mFmServiceHandler;
    // Lock for lose audio focus and receive SOUND_POWER_DOWN_MSG
    // at the same time
    // while recording call stop recording not finished(status is still
    // RECORDING), but
    // SOUND_POWER_DOWN_MSG will exitFm(), if it is RECORDING will discard the
    // record.
    // 1. lose audio focus -> stop recording(lock) -> set to IDLE and show save
    // dialog
    // 2. exitFm() -> check the record status, discard it if it is recording
    // status(lock)
    // Add this lock the exitFm() while stopRecording()
    private Object mStopRecordingLock = new Object();
    // The listener for exit, should finish favorite when exit FM
    private static OnExitListener sExitListener = null;
    // The latest status for mute/unmute
    private boolean mIsMuted = false;
    /*
     * // Audio Patch private AudioPatch mAudioPatch = null;
     */
    private Object mAudioPatch = null;

    private Object mRenderLock = new Object();
    //  fix bug 527684
    private Object mNotificationLock = new Object();

    private Notification.Builder mNotificationBuilder = null;
    private BigTextStyle mNotificationStyle = null;
    private String mFMRadioNotification = "com.android.fmradio";
    private NotificationManager mNotificationManager;

    private FmRadioManager mFmManager = null;
    // Modify for Bug782729, Unable to start activity ComponentInfo
    private static final String FM_STOP_RECORDING = "fmradio.stop.recording";
    private static final int TIME_BASE = 60;
    private boolean mShouldNotPowerUp = false;

    private CurrentStationItem mCurrentStationItem = null;

    private static final Object mRecordLock = new Object();

    private Handler mMediaKeyHandler;
    private Toast mToast;
    private String mToastText;

    @Override
    public void onCurrentStationChange() {
        mPsString = mCurrentStationItem.ps;
        mRtTextString = mCurrentStationItem.rt;
        Log.d(TAG, "onCurrentStationChange mRtTextString : " + mRtTextString + " mPsString : "
                + mPsString + "CurrentStation frequency : " + mCurrentStationItem.stationFreq);
        if(getPowerStatus() == POWER_UP){
          updatePlayingNotification();
        }
    }

    public void onProgramInfoChanged(RadioManager.ProgramInfo info) {
         if (!isDeviceReady()) return;
        mFmServiceHandler.removeMessages(FmListener.MSGID_TUNE_COMPLETE);
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_TUNE_COMPLETE);
        Bundle bundle = new Bundle();
        bundle.putInt("stationFreq", info.getChannel());
        msg.setData(bundle);
        mFmServiceHandler.sendMessage(msg);
    }

    public void onMetadataChanged(String ps, String rt) {
         if (!isDeviceReady()) return;
         sendRdsMessage(rt, ps);
    }

    private boolean isDeviceReady() {
        boolean bReady = true;
        if (mPowerStatus == POWER_DOWN || !mIsDeviceReady) {
            bReady = false;
            Log.d(TAG, "isDeviceReady mPowerStatus: " + mPowerStatus + ", mIsDeviceReady: " + mIsDeviceReady);
        }
        return (bReady);
    }

    public void onAutoScanComplete(List<RadioManager.ProgramInfo> programList) {
        int[] stations = null;
        if (programList != null) {
            stations = new int[programList.size()];
            for (int i = 0; i < programList.size(); i++) {
                stations[i] = programList.get(i).getChannel();
            }
        }

        mFmServiceHandler.removeMessages(FmListener.MSGID_SCAN_COMPLETE);
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_SCAN_COMPLETE);
        Bundle bundle = new Bundle();
        bundle.putIntArray("stations", stations);
        msg.setData(bundle);
        mFmServiceHandler.sendMessage(msg);

    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * class use to return service instance
     */
    public class ServiceBinder extends Binder {
        /**
         * get FM service instance
         *
         * @return service instance
         */
        FmService getService() {
            return FmService.this;
        }
    }

    private void sendRdsMessage(String rt, String ps) {
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_RDS_DATA_UPDATE);
        Bundle rdsdata = new Bundle();
        rdsdata.putString("rt", rt);
        rdsdata.putString("ps", ps);
        msg.setData(rdsdata);
        mFmServiceHandler.removeMessages(FmListener.MSGID_RDS_DATA_UPDATE);
        mFmServiceHandler.sendMessage(msg);
    }

    private void notifyRdsChanged(String rt, String ps) {
        Bundle bundle = new Bundle(3);
        bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_RDS_DATA_UPDATE);
        bundle.putString(FmListener.KEY_RT_INFO, rt);
        bundle.putString(FmListener.KEY_PS_INFO, ps);
        notifyActivityStateChanged(bundle);
    }

    private void updateRdsInfo(String rt, String ps) {
        if (mPowerStatus == POWER_UP) {
            mCurrentStationItem.rt = rt;
            mCurrentStationItem.ps = ps;
        } else {
            mCurrentStationItem.rt = "";
            mCurrentStationItem.ps = "";
        }

        notifyRdsChanged(mCurrentStationItem.rt, mCurrentStationItem.ps);
        if (mPowerStatus == POWER_UP) {
            updatePlayingNotification();
        }
    }

    /**
     * Broadcast monitor external event, Other app want FM stop, Phone shut down, screen state,
     * headset state
     */
    private class FmServiceBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String command = intent.getStringExtra("command");
            Log.d(TAG, "onReceive, action = " + action + " / command = " + command);
            // other app want FM stop, stop FM
            if ((SOUND_POWER_DOWN_MSG.equals(action) && CMDPAUSE.equals(command))) {
                // need remove all messages, make power down will be execute
                mFmServiceHandler.removeCallbacksAndMessages(null);
                exitFm();
                stopSelf();
                // phone shut down, so exit FM
            } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
                /**
                 * here exitFm, system will send broadcast, system will shut down, so fm does not
                 * need call back to activity
                 */
                Log.d(TAG, "save it if recording when shutdown");
                String saveName = FmRecorder.RECORDING_FILE_PREFIX + "" + getRecordingName();
                saveRecording(saveName);
                unregisterSdcardListener();
                mFmServiceHandler.removeCallbacksAndMessages(null);
                exitFm();
                stopSelf();
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                Log.d(TAG, "save it if recording when user_switch");
                String saveName = FmRecorder.RECORDING_FILE_PREFIX + "" + getRecordingName();
                saveRecording(saveName);
                unregisterSdcardListener();
                mFmServiceHandler.removeCallbacksAndMessages(null);
                exitFm();
                stopSelf();
             } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if ((mPowerStatus == POWER_UP)) {
                    mFmManager.setRdsOnOff(true);
                }
                // screen off, if FM play,and rds option is open, close rds
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if ((mPowerStatus == POWER_UP)) {
                    mFmManager.setRdsOnOff(false);
                }
            } else if (AudioManager.STREAM_MUTE_CHANGED_ACTION.equals(action)) {
                if (intent.hasExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE)
                        && intent.hasExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED)) {
                    int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                    boolean muted = intent.getBooleanExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false);
                    Log.i(TAG, " Get Volume streamType=" + streamType + " mute=" + muted);
                    if (streamType == AudioManager.STREAM_MUSIC) {
                        if (getPowerStatus() == POWER_UP) {
                            setMuteAsync(muted, false);
                        } else {
                            Log.i(TAG, "fm powerdown, do nothing for STREAM_MUTE_CHANGED_ACTION");
                        }
                    }
                }
                // usb control end.
            } else if (ACTION_FM_HEADSET_NEXT.equals(action)) {
                seekStationAsync(mCurrentStationItem.stationFreq, true);
                /**
                 * @}
                 */

                /**
                 *  bug492835, FM audio route change.
                 *
                 * @{
                 */
            } else if (AudioManager.VOLUME_CHANGED_ACTION.equals(action)) {
                if (mIsScanning || mIsSeeking || mIsMuted) {
                    return;
                }
                // change volume by FM
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                int streamValue = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                if (streamType == AudioManager.STREAM_MUSIC) {
                    int StreamMusicVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    Log.d(TAG, "STREAM_MUSIC value " + StreamMusicVolume + " EXTRA_VOLUME_STREAM_VALUE " + streamValue);
                    if (StreamMusicVolume != -1) {
                        if (StreamMusicVolume == 0) {
                            if (mPowerStatus == POWER_UP) {
                                mAudioManager.setParameter("FM_Volume", "" + StreamMusicVolume);
                            }
                        } else {
                            setVolume(StreamMusicVolume);
                        }
                    }
                }
                /**
                 * @}
                 */
            } else if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
                //bug939562 FM does not support type-c headset
                if (isTypecPlugIn(intent)) return;

                // Avoid Service is killed,and receive headset plug in broadcast again
                if (!mIsServiceInited) {
                    Log.d(TAG, "onReceive, mIsServiceInited is false");
                    return;
                }

                if(!FmUtils.supportShortAntenna){
                    /*
                     * If ear phone insert and activity is foreground. power up FM automatic
                     */
                    if ((0 == mValueHeadSetPlug) && isActivityForeground()) {
                        powerUpAsync(mCurrentStationItem.stationFreq);
                    } else if (1 == mValueHeadSetPlug) {
                        mFmServiceHandler.removeMessages(FmListener.MSGID_SCAN_FINISHED);
                        mFmServiceHandler.removeMessages(FmListener.MSGID_SEEK_FINISHED);
                        mFmServiceHandler.removeMessages(FmListener.MSGID_TUNE_FINISHED);
                        mFmServiceHandler.removeMessages(
                                FmListener.MSGID_POWERDOWN_FINISHED);
                        mFmServiceHandler.removeMessages(
                                FmListener.MSGID_POWERUP_FINISHED);
                        focusChanged(AudioManager.AUDIOFOCUS_LOSS);
                        // Need check to switch to earphone mode for audio will
                        // change to AudioSystem.FORCE_NONE
                        setForceUse(false);
                        // For bug773332,save user's option to shared preferences.
                        FmUtils.setIsSpeakerModeOnFocusLost(mContext, mIsSpeakerUsed);
                        //Toast.makeText(mContext,getString(R.string.fm_no_headset_text),Toast.LENGTH_SHORT).show();
                    }
                } else {
                    if (mPowerStatus == POWER_UP) {
                        boolean isAudioSpeakerMode = mValueHeadSetPlug == 1;
                        Log.d(TAG, "onReceive, action = " + action + " / isAudioSpeakerMode = " + isAudioSpeakerMode + ", change antenna to " + mValueHeadSetPlug);
                        switchAntenna(mValueHeadSetPlug);
                        setSpeakerPhoneOn(isAudioSpeakerMode);
                    }
                }
                // switch antenna should not impact audio focus status
                switchAntennaAsync(mValueHeadSetPlug);

            } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                if (intent.hasExtra("state")) {
                    boolean enable = intent.getBooleanExtra("state", false);
                    Log.d(TAG, "airplane mode enable " + enable);
                    if (enable) {
                        mFmServiceHandler.removeMessages(FmListener.MSGID_SCAN_FINISHED);
                        mFmServiceHandler.removeMessages(FmListener.MSGID_SEEK_FINISHED);
                        mFmServiceHandler.removeMessages(FmListener.MSGID_TUNE_FINISHED);
                        mFmServiceHandler.removeMessages(
                                FmListener.MSGID_POWERDOWN_FINISHED);
                        mFmServiceHandler.removeMessages(
                                FmListener.MSGID_POWERUP_FINISHED);
                        abandonAudioFocus();
                        focusChanged(AudioManager.AUDIOFOCUS_LOSS);
                        setMute(true);
                        setForceUse(false);
                        showToast(getString(R.string.airplane_message));
                    }
                }
            } else if(action.equals(ACTION_DEVICE_STORAGE_LOW_STATE)){
                int level = intent.getIntExtra(EXTRA_CURRENT_LEVEL, -1);
                if(level == LEVEL_LOW && mFmRecorder != null && mFmRecorder.getState() == FmRecorder.STATE_RECORDING){
                    stopRecording();
                    onRecorderError(FmRecorder.ERROR_SDCARD_INSUFFICIENT_SPACE);
                    Log.e(TAG, "onReceive, phone does not have sufficient space!!");
                }
            } else if (action.equals(Intent.ACTION_DEVICE_STORAGE_LOW)) {
                if (mFmRecorder != null && mFmRecorder.getState() == FmRecorder.STATE_RECORDING) {
                    stopRecording();
                    onRecorderError(FmRecorder.ERROR_SDCARD_INSUFFICIENT_SPACE);
                    Log.e(TAG, "onReceive, phone does not have sufficient space!!!");
                }
            }
        }
    }

    /**
     * Handle sdcard mount/unmount event. 1. Update the sdcard state map 2. If the recording sdcard
     * is unmounted, need to stop and notify
     */
    private class SdcardListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If eject record sdcard, should set this false to not
            // record.
            String action = intent.getAction();
            Log.d(TAG, "SdcardListener onReceive, action = " + action);
            updateSdcardStateMap(intent);

            FmUtils.checkAndResetStoragePath(context, intent.getData().getPath());
            if (Intent.ACTION_MEDIA_EJECT.equals(action)
                    || Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                notifyRefreshSavePathDialog();
            }
            if (Intent.ACTION_MEDIA_EJECT.equals(action)) {
                // If not unmount recording sd card, do nothing;
                if (isRecordingCardUnmount(intent)) {
                    if (mFmRecorder.getState() == FmRecorder.STATE_RECORDING) {
                        stopRecording();
                        onRecorderError(FmRecorder.ERROR_SDCARD_NOT_PRESENT);
                        // onError will triger stoprecorder,so remove unnecessary code below
                        //mFmRecorder.discardRecording();
                    } else {
                        Bundle bundle = new Bundle(2);
                        bundle.putInt(FmListener.CALLBACK_FLAG,
                                FmListener.LISTEN_RECORDSTATE_CHANGED);
                        bundle.putInt(FmListener.KEY_RECORDING_STATE,
                                FmRecorder.STATE_IDLE);
                        notifyActivityStateChanged(bundle);
                    }
                }
            }
            return;
        }
    }

    /**
     * check whether type-c is plugged in or out.if plug in then Display a toast
     *
     * @return true ,Type-c headset is plugged in or out;false, wired headset is plugged in or out
     */
    private boolean isTypecPlugIn(Intent intent) {
        int device = intent.getIntExtra("device", -1);
        int valueHeadSetPlug = (intent.getIntExtra("state", -1) == HEADSET_PLUG_IN) ? 0 : 1;
        if (device == AudioSystem.DEVICE_OUT_USB_HEADSET || device == AudioSystem.DEVICE_IN_USB_HEADSET) {//type-c is plugged in or out
            if ((valueHeadSetPlug == 0)) {
                Toast.makeText(getApplicationContext(), getString(R.string.toast_type_c_error), Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        mValueHeadSetPlug = valueHeadSetPlug;
        return false;

    }

    /**
     * whether antenna available
     *
     * @return true, antenna available; false, antenna not available
     */
    public boolean isAntennaAvailable() {
        AudioDeviceInfo[] audioDeviceInfo = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo info : audioDeviceInfo) {
            //wired headset(with mic or not) is pluged in
            if ((info.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) || (info.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES)) {
                Log.d(TAG, "Wired headset is exist");
                return true;
            }
        }
        Log.d(TAG,"Wired headset is not exist");
        return false;
        //mAudioManager.isWiredHeadsetOn();
    }

    private void setForceUse(boolean isSpeaker) {
        Log.d(TAG, "setForceUseSpeaker:" + isSpeaker);
        mFmManager.setSpeakerEnable(isSpeaker);
        mIsSpeakerUsed = isSpeaker;

        Bundle bundle = new Bundle(2);
        bundle.putInt(FmListener.CALLBACK_FLAG,
                FmListener.LISTEN_SPEAKER_MODE_CHANGED);
        bundle.putBoolean(FmListener.KEY_IS_SPEAKER_MODE, mIsSpeakerUsed);
        notifyActivityStateChanged(bundle);
    }

    /**
     * Set FM audio from speaker or not
     *
     * @param isSpeaker true if set FM audio from speaker
     */
    public void setSpeakerPhoneOn(boolean isSpeaker) {
        Log.d(TAG, "setSpeakerPhoneOn " + isSpeaker);
        /**
         *  bug492835, FM audio route change. Original Android code: setForceUse(isSpeaker);
         *
         * @{
         */
        setMute(true);
        setForceUse(isSpeaker);
        setMute(false);
        /* add for bug679730, fm save status of speaker/headset when audio focus loss,
         * But if we change it manually by menuItem, the state will not refresh,in that case,
         * fm will play by speaker rather than headset afer audio focus gain.
         * So refresh states when click speaker/headset menuItem
         */
        FmUtils.setIsSpeakerModeOnFocusLost(mContext, isSpeaker);
        /**
         * @}
         */
    }

    private synchronized void startRender() {
        //bug492835, FM audio route change.
        mFmManager.setAudioPathEnable(true);
        mMediaSessionManager.setOnMediaKeyListener(mMediaKeyListener, mMediaKeyHandler);
        Log.d(TAG, "startRender,setOnMediaKeyListener");
        mIsRender = true;
        synchronized (mRenderLock) {
            mRenderLock.notify();
        }
    }
    private synchronized void stopRender() {
        Log.d(TAG, "stopRender");
        mFmManager.setAudioPathEnable(false);
        mMediaSessionManager.setOnMediaKeyListener(null, null);
        Log.d(TAG, "stopRender,unsetOnMediaKeyListener");
        mIsRender = false;
    }

    private AudioRecord mAudioRecord = null;
    private AudioTrack mAudioTrack = null;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_CONFIGURATION_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int RECORD_BUF_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            CHANNEL_CONFIG, AUDIO_FORMAT);
    private boolean mIsRender = false;

    AudioDevicePort mAudioSource = null;
    AudioDevicePort mAudioSink = null;


    // A2dp or speaker mode should render
    private synchronized boolean isRender() {
        return (mIsRender && (mPowerStatus == POWER_UP) && mIsAudioFocusHeld);
    }

    /**
     * open FM device, should be call before power up
     *
     * @return true if FM device open, false FM device not open
     */
    private void openDevice() {
        if (!mIsDeviceOpen) {
            mIsDeviceOpen = mFmManager.openDev();
        }
    }

    /**
     * close FM device
     *
     * @return true if close FM device success, false close FM device failed
     */
    private void closeDevice() {
        if (mIsDeviceOpen) {
            mIsDeviceOpen = !mFmManager.closeDev();
        }
        // quit looper
        mFmServiceHandler.getLooper().quit();
    }

    private void onlyCloseDevice() {
        if (mIsDeviceOpen) {
            mIsDeviceOpen = !mFmManager.closeDev();
        }
    }
    
    /**
     * get FM device opened or not
     *
     * @return true FM device opened, false FM device closed
     */
    public boolean isDeviceOpen() {
        return mIsDeviceOpen;
    }

    /**
     * power up FM, and make FM voice output from earphone
     *
     * @param frequency
     */
    public void powerUpAsync(int frequency) {
        final int bundleSize = 1;
        mFmServiceHandler.removeMessages(FmListener.MSGID_POWERUP_FINISHED);
        mFmServiceHandler.removeMessages(FmListener.MSGID_POWERDOWN_FINISHED);
        Bundle bundle = new Bundle(bundleSize);
        bundle.putInt(FM_FREQUENCY, frequency);
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_POWERUP_FINISHED);
        msg.setData(bundle);
        mFmServiceHandler.sendMessage(msg);
    }

    private boolean powerUp(int frequency) {
        if (mPowerStatus == POWER_UP) {
            return true;
        }
        //  bug492835, FM audio route change. if (!mWakeLock.isHeld()) { mWakeLock.acquire(); }
        if (isCallStatus() || !requestAudioFocus()) {
            //clear notification
            Log.d(TAG, "power up failed,request audio focus failed,remove notification");
            stopForeground(true);
            mPowerStatus = POWER_DOWN;
            return false;
        }
        mPowerStatus = DURING_POWER_UP;

        // if device open fail when chip reset, it need open device again before
        // power up
        openDevice();
        if (!mIsDeviceOpen) {
            mPowerStatus = POWER_DOWN;
            //clear notification
            Log.d(TAG, "power up failed,remove notification");
            stopForeground(true);
            return false;
        }

        Log.d(TAG, "power up");
        mPowerStatus = POWER_UP;
        if (FmUtils.supportShortAntenna){
            boolean isAntennaAvailable = isAntennaAvailable();
            Log.d(TAG, "handlePowerUp: wether earphone is plugged in -->" + isAntennaAvailable);
            switchAntenna(isAntennaAvailable ? 0 : 1);
        }
        // need mute after power up
        setMute(true);

        return (mPowerStatus == POWER_UP);
    }

    private boolean playFrequency(int frequency) {
        Log.d(TAG, "playFrequency");
        updatePlayingNotification();

        //bug492835, FM audio route change. if (!mWakeLock.isHeld()) { mWakeLock.acquire(); }
        if (mIsSpeakerUsed) {
            setForceUse(mIsSpeakerUsed);
        }
        enableFmAudio(true);
        setMute(false);

        return (mPowerStatus == POWER_UP);
    }

    /**
     * power down FM
     */
    public void powerDownAsync() {
        // if power down Fm, should remove message first.
        // not remove all messages, because such as recorder message need
        // to execute after or before power down
        mFmServiceHandler.removeMessages(FmListener.MSGID_SCAN_FINISHED);
        mFmServiceHandler.removeMessages(FmListener.MSGID_SEEK_FINISHED);
        mFmServiceHandler.removeMessages(FmListener.MSGID_TUNE_FINISHED);
        mFmServiceHandler.removeMessages(FmListener.MSGID_POWERDOWN_FINISHED);
        mFmServiceHandler.removeMessages(FmListener.MSGID_POWERUP_FINISHED);
        mFmServiceHandler.removeMessages(FmListener.MSGID_SCAN_COMPLETE);
        mFmServiceHandler.removeMessages(FmListener.MSGID_TUNE_COMPLETE);
        mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_POWERDOWN_FINISHED);
    }

    /**
     * Power down FM
     *
     * @return true if power down success
     */
    private boolean powerDown() {
        if (mPowerStatus == POWER_DOWN) {
            if (mIsStartTimeCount) stopTimer();
            return true;
        }

        setMute(true);
        enableFmAudio(false);

        // change antenna to headset to power save.
        if (FmUtils.supportShortAntenna) {
            Log.d(TAG, "powerdown change antenna to long as default");
            switchAntenna(0);
        }

         onlyCloseDevice();
        // activity used for update powerdown menu
        mPowerStatus = POWER_DOWN;
        mIsDeviceReady = false;
        sendRdsMessage("","");
        if (mIsStartTimeCount && !mPausedByTransientLossOfFocus) {
            stopTimer();
        }
        //clear notification
        Log.d(TAG, "power down,remove notification");
        mFmServiceHandler.removeMessages(FmListener.MSGID_UPDATE_NOTIFICATION);
        stopForeground(true);
        return true;
    }

    public int getPowerStatus() {
        return mPowerStatus;
    }

    /**
     * Tune to a station
     *
     * @param frequency The frequency to tune
     * @return true, success; false, fail.
     */
    public void tuneStationAsync(int frequency) {
        mFmServiceHandler.removeMessages(FmListener.MSGID_TUNE_FINISHED);
        final int bundleSize = 1;
        Bundle bundle = new Bundle(bundleSize);
        bundle.putInt(FM_FREQUENCY, frequency);
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_TUNE_FINISHED);
        msg.setData(bundle);
        mFmServiceHandler.sendMessage(msg);
    }

    private boolean tuneStation(int frequency) {
        if (mPowerStatus == POWER_UP) {
            boolean bRet = mFmManager.tuneRadio(frequency);
            return bRet;
        }

        // if earphone is not insert, not power up
        if (!isAntennaAvailable()) {
            Log.d(TAG, "tuneStation failed due to antenna not available....");
            return false;
        }

        // if not power up yet, should powerup first
        boolean tune = false;

        if (powerUp(frequency)) {
            tune = playFrequency(frequency);
        }

        return tune;
    }

    /**
     * Seek station according frequency and direction
     *
     * @param frequency start frequency(100KHZ, 87.5)
     * @param isUp      direction(true, next station; false, previous station)
     * @return the frequency after seek
     */
    public void seekStationAsync(int frequency, boolean isUp) {
        mFmServiceHandler.removeMessages(FmListener.MSGID_SEEK_FINISHED);
        final int bundleSize = 2;
        Bundle bundle = new Bundle(bundleSize);
        bundle.putInt(FM_FREQUENCY, frequency);
        bundle.putBoolean(OPTION, isUp);
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_SEEK_FINISHED);
        msg.setData(bundle);
        mFmServiceHandler.sendMessage(msg);
    }

    private float seekStation(int frequency, boolean isUp) {
        if (mPowerStatus != POWER_UP) {
            return -1;
        }

        mIsNativeSeeking = true;
        float fRet = mFmManager.seekStation(frequency, isUp);
        mIsNativeSeeking = false;
        // make mIsStopScanCalled false, avoid stop scan make this true,
        // when start scan, it will return null.
        mIsStopScanCalled = false;
        return fRet;
    }

    /**
     * Scan stations
     */
    public void startScanAsync() {
        mFmServiceHandler.removeMessages(FmListener.MSGID_SCAN_FINISHED);
        mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_SCAN_FINISHED);
    }

    /**
     * bug474750, scan from current freq. Original Android code: private int[] startScan() {
     *
     * @{
     */
    private int[] startScan(int start_freq) {
        int[] stations = null;

        setMute(true);
        if (!mIsStopScanCalled) {
            mIsNativeScanning = true;
            /**
             *  bug474750, scan from current freq. Original Android code: stationsInShort =
             * FmNative.autoScan();
             *
             * @{
             */
            stations = mFmManager.autoScan(start_freq);
            mIsNativeScanning = false;
        }

        mIsStopScanCalled = false;

        return stations;
    }

    /**
     * @}
     */

    /**
     * Check FM Radio is in scan progress or not
     *
     * @return if in scan progress return true, otherwise return false.
     */
    public boolean isScanning() {
        return mIsScanning;
    }

    /**
     * Stop scan progress
     *
     * @return true if can stop scan, otherwise return false.
     */
    public boolean stopScan() {
        if (mPowerStatus != POWER_UP) {
            return false;
        }

        boolean bRet = false;
        mFmServiceHandler.removeMessages(FmListener.MSGID_SCAN_FINISHED);
        mFmServiceHandler.removeMessages(FmListener.MSGID_SEEK_FINISHED);
        if (mIsNativeScanning || mIsNativeSeeking) {
            mIsStopScanCalled = true;
            bRet = mFmManager.stopScan();
        }
        return bRet;
    }

    /**
     * Check FM is in seek progress or not
     *
     * @return true if in seek progress, otherwise return false.
     */
    public boolean isSeeking() {
        return mIsNativeSeeking;
    }

    /**
     * Mute or unmute FM voice
     *
     * @param mute       true for mute, false for unmute
     * @param stopRecord true for stoprecording , false for not when "mute" fm. Add for new behaviours,related to bug671774
     * @return (true, success ; false, failed)
     */
    public void setMuteAsync(boolean mute, boolean stopRecord) {
        mFmServiceHandler.removeMessages(FmListener.MSGID_SET_MUTE_FINISHED);
        final int bundleSize = 2;
        Bundle bundle = new Bundle(bundleSize);
        bundle.putBoolean(OPTION, mute);
        bundle.putBoolean(STOPRECORD, stopRecord);
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_SET_MUTE_FINISHED);
        msg.setData(bundle);
        mFmServiceHandler.sendMessage(msg);
    }

    /**
     * Mute or unmute FM voice
     *
     * @param mute true for mute, false for unmute
     * @return (1, success ; other, failed)
     */
    public void setMute(boolean mute) {
        if (mPowerStatus != POWER_UP) {
            Log.w(TAG, "setMute, FM is not powered up");
            return;
        }
        Log.d(TAG, "fmapp setMute:" + mute);
        int streamValue = mute ? 0 : mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        setVolume(streamValue);
        mIsMuted = mute;
    }

    /**
     * Check the latest status is mute or not
     *
     * @return (true, mute ; false, unmute)
     */
    public boolean isMuted() {
        return mIsMuted;
    }

    /**
     * Check whether speaker used or not
     *
     * @return true if use speaker, otherwise return false
     */
    public boolean isSpeakerUsed() {
        return mIsSpeakerUsed;
    }

    /**
     * Initial service and current station
     *
     * @param iCurrentStation current station frequency
     */
    public void initService() {
        mIsServiceInited = true;
    }

    /**
     * Check service is initialed or not
     *
     * @return true if initialed, otherwise return false
     */
    public boolean isServiceInited() {
        return mIsServiceInited;
    }

    /**
     * resume FM audio
     */
    private void resumeFmAudio() {
        // If not check mIsAudioFocusHeld && power up, when scan canceled,
        // this will be resume first, then execute power down. it will cause
        // nosise.
        if (mIsAudioFocusHeld && (mPowerStatus == POWER_UP)) {
            enableFmAudio(true);
        }
    }

    /**
     * Switch antenna There are two types of antenna(long and short) If long antenna(most is this
     * type), must plug in earphone as antenna to receive FM. If short antenna, means there is a
     * short antenna if phone already, can receive FM without earphone.
     *
     * @param antenna antenna (0-for headset(long antenna), 1 short antenna)
     * @return (0, success ; 1 failed ; 2 not support)
     */
    public void switchAntennaAsync(int antenna) {
        final int bundleSize = 1;
        mFmServiceHandler.removeMessages(FmListener.MSGID_SWITCH_ANTENNA);

        Bundle bundle = new Bundle(bundleSize);
        bundle.putInt(FmListener.SWITCH_ANTENNA_VALUE, antenna);
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_SWITCH_ANTENNA);
        msg.setData(bundle);
        mFmServiceHandler.sendMessage(msg);
    }

    /**
     * Need native support whether antenna support interface.
     *
     * @param antenna antenna (0, long antenna, 1 short antenna)
     * @return (0, success ; 1 failed ; 2 not support)
     */
    private int switchAntenna(int antenna) {
        // if fm not powerup, switchAntenna will flag whether has earphone
        return mFmManager.switchAntenna(antenna);
    }

    /**
     * Start recording
     */
    public void startRecordingAsync() {
        mFmServiceHandler.removeMessages(FmListener.MSGID_STARTRECORDING_FINISHED);
        mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_STARTRECORDING_FINISHED);
    }

    private void startRecording() {
        /**
         *  bug492835, FM audio route change.
         *
         * @{
         */
        synchronized (mRecordLock) {
            // bug 1084143, add judgement of power status later
            if(getPowerStatus() != POWER_UP) {
                Log.d(TAG, "startRecording, fm is power down,don't start recording");
                onRecorderError(FmRecorder.ERROR_RECORDER_INTERNAL);
                return;
            }
            sRecordingSdcard = FmUtils.getDefaultStoragePath();
            if (sRecordingSdcard == null || sRecordingSdcard.isEmpty()) {
                Log.d(TAG, "startRecording, may be no sdcard");
                onRecorderError(FmRecorder.ERROR_SDCARD_NOT_PRESENT);
                return;
            }

            mAudioManager.setParameter("fm_record", "1");

            if (mFmRecorder == null) {
                mFmRecorder = new FmRecorder();
                mFmRecorder.registerRecorderStateListener(FmService.this);
            }

            if (isSdcardReady(sRecordingSdcard)) {
                mFmRecorder.startRecording(mContext);
            } else {
                onRecorderError(FmRecorder.ERROR_SDCARD_NOT_PRESENT);
            }
        }
    }

    private boolean isCallStatus() {
        int audioMode = mAudioManager.getMode();
        boolean flag = (audioMode == AudioManager.MODE_IN_COMMUNICATION || audioMode == AudioManager.MODE_RINGTONE);
        Log.d(TAG, "isCallStatus audioMode:" + audioMode);
        return (flag);
    }

    private boolean isSdcardReady(String sdcardPath) {
        if (!mSdcardStateMap.isEmpty()) {
            if (mSdcardStateMap.get(sdcardPath) != null && !mSdcardStateMap.get(sdcardPath)) {
                Log.d(TAG, "isSdcardReady, return false");
                return false;
            }
        }
        return true;
    }

    public void disCardRecordingAsync() {
        mFmServiceHandler.removeMessages(FmListener.MSGID_DISCARDRECORDING_FINISHED);
        mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_DISCARDRECORDING_FINISHED);
    }

    private void disCardRecording() {
        mFmRecorder.discardRecording();
    }

    /**
     * stop recording
     */
    public void stopRecordingAsync() {
        mFmServiceHandler.removeMessages(FmListener.MSGID_STOPRECORDING_FINISHED);
        mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_STOPRECORDING_FINISHED);
    }

    private void showToast(String text) {
        if (mToast != null) {
            mToast.cancel();
        }
        mToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        mToast.show();
    }

    private boolean stopRecording() {
        /**
         *  bug492835, FM audio route change.
         *
         * @{
         */
        // mAudioManager.setParameter("fm_record", "0");
        /**
         * @}
         */
        synchronized (mRecordLock) {
            if (mFmRecorder == null) {
                Log.e(TAG, "stopRecording, called without a valid recorder!!");
                mAudioManager.setParameter("fm_record", "0");
                return false;
            }
            synchronized (mStopRecordingLock) {
                mFmRecorder.stopRecording();
            }
            mAudioManager.setParameter("fm_record", "0");
        }
        return true;
    }

    /**
     * Save recording file according name or discard recording file if name is null
     *
     * @param newName New recording file name
     */
    public void saveRecordingAsync() {
        mFmServiceHandler.removeMessages(FmListener.MSGID_SAVERECORDING_FINISHED);
        mFmServiceHandler.sendEmptyMessageDelayed(FmListener.MSGID_SAVERECORDING_FINISHED, 500);
    }

    private boolean saveRecording(String newName) {
        if (mFmRecorder == null || newName == null) {
            Log.e(TAG, "saveRecording, mFmRecorder is null!!");
            return false;
        }
        return mFmRecorder.saveRecording(FmService.this, newName);
    }

    /**
     * Get record time
     *
     * @return Record time
     */
    public long getRecordTime() {
        if (mFmRecorder != null) {
            return mFmRecorder.getRecordTime();
        }
        return 0;
    }

    /**
     * Get record state
     *
     * @return record state
     */
    public int getRecorderState() {
        if (null != mFmRecorder) {
            return mFmRecorder.getState();
        }
        return FmRecorder.STATE_INVALID;
    }

    /**
     * Get recording file name
     *
     * @return recording file name
     */
    public String getRecordingName() {
        if (null != mFmRecorder) {
            return mFmRecorder.getRecordFileName();
        }
        return null;
    }

    /**
     * Is recording tmp file exist
     *
     * @return boolean, is recording file exist or not
     */

    public boolean isRecordingTmpFileExist() {
        return null != mFmRecorder && mFmRecorder.isRecordingTmpFileExist();
    }

    public void stopRecordingNoSpaceAsync() {
        mFmServiceHandler.removeMessages(FmListener.MSGID_STOPRECORDING_NOSPACE);
        mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_STOPRECORDING_NOSPACE);
    }

    private void stopRecordingNoSpace() {
        if (mFmRecorder != null && mFmRecorder.getState() == FmRecorder.STATE_RECORDING) {
            stopRecording();
            onRecorderError(FmRecorder.ERROR_SDCARD_INSUFFICIENT_SPACE);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = getApplicationContext();
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        mCurrentStationItem = CurrentStationItem.getInstance();

        mFmManager = new FmRadioManager(this);

        createNotificationChannel();
        mCurrentStationItem.registerObserver(this);
        /**
         * @}
         */

        /*
         *  bug492835, FM audio route change. mWakeLock =
         * powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
         * mWakeLock.setReferenceCounted(false);
         */
        FmUtils.mContext = this;
        sRecordingSdcard = FmUtils.getDefaultStoragePath();
        //clear speaker/headset status in sharedpreference when app start to avoid device using error as bellow:
        //(speaker-killed-restart(headset)-audiofocus change-speaker)
        //TODO: maybe class member will be fine, for status save and use.
        FmUtils.setIsSpeakerModeOnFocusLost(mContext, false);
        registerFmBroadcastReceiver();
        registerSdcardReceiver();
        /*
         * registerAudioPortUpdateListener();
         */
        HandlerThread handlerThread = new HandlerThread("FmRadioServiceThread");
        handlerThread.start();
        mFmServiceHandler = new FmRadioServiceHandler(handlerThread.getLooper());
        //mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_OPEN_DEVICE);
        // add for support short antenna.
        if (FmUtils.supportShortAntenna && !isAntennaAvailable()) {
            mIsSpeakerUsed = true;
        }
        // set speaker to default status, avoid setting->clear data.
        setForceUse(mIsSpeakerUsed);
        //  bug568587, new feature FM new UI
        mTimeCountListener = null;
        mMediaSessionManager = (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        HandlerThread mediaThread = new HandlerThread("FmRadioMediaThread");
        mediaThread.start();
        mMediaKeyHandler = new Handler(mediaThread.getLooper());
        mMediaSessionManager.setOnMediaKeyListener(mMediaKeyListener, mMediaKeyHandler);
    }

    /*
     *Add for bug766021, for headset control on androidO and later.
     */
    private MediaSessionManager.OnMediaKeyListener mMediaKeyListener =
            new MediaSessionManager.OnMediaKeyListener() {
                @Override
                public boolean onMediaKey(KeyEvent event) {
                    int keycode = event.getKeyCode();
                    int action = event.getAction();
                    long eventtime = event.getEventTime();

                    if ((keycode == KeyEvent.KEYCODE_HEADSETHOOK)
                            || (keycode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                        if (action == KeyEvent.ACTION_DOWN) {
                            if (mDown) {
                            } else if (event.getRepeatCount() == 0) {
                                // only consider the first event in a sequence, not the repeat events,
                                // so that we don't trigger in cases where the first event went to
                                // a different app (e.g. when the user ends a phone call by
                                // long pressing the headset button)
                                // The service may or may not be running, but we need to send it
                                // a command.
                                //eventtime equals zero when turn on talkback.
                                //otherwise below's  condition maybe a invalid code
                                if (eventtime - mLastClickTime > 300 || eventtime == 0) {
                                    // double click play next
                                    Intent fmIntent = new Intent(ACTION_FM_HEADSET_NEXT);
                                    mContext.sendBroadcast(fmIntent);
                                    Log.d(TAG, "send headset next");
                                    mLastClickTime = 0;
                                }
                                mDown = true;
                            }
                        } else {
                            mDown = false;
                        }
                    }
//                    }
                    return true;
                }
            };

    private void registerAudioPortUpdateListener() {
        if (mAudioPortUpdateListener == null) {
            mAudioPortUpdateListener = new FmOnAudioPortUpdateListener();
            mAudioManager.registerAudioPortUpdateListener(mAudioPortUpdateListener);
        }
    }

    private void unregisterAudioPortUpdateListener() {
        if (mAudioPortUpdateListener != null) {
            mAudioManager.unregisterAudioPortUpdateListener(mAudioPortUpdateListener);
            mAudioPortUpdateListener = null;
        }
    }


    private synchronized int createAudioPatch() {
        Log.d(TAG, "createAudioPatch");
        int status = AudioManager.ERROR;
        if (mAudioPatch != null) {
            Log.d(TAG, "createAudioPatch, mAudioPatch is not null, return");
            return status;
        }
        mAudioPatch = new Object();
        /*
         * mAudioSource = null; mAudioSink = null; ArrayList<AudioPort> ports = new
         * ArrayList<AudioPort>(); mAudioManager.listAudioPorts(ports); for (AudioPort port : ports)
         * { if (port instanceof AudioDevicePort) { int type = ((AudioDevicePort) port).type();
         * String name = AudioSystem.getOutputDeviceName(type); if (type ==
         * AudioSystem.DEVICE_IN_FM_TUNER) { mAudioSource = (AudioDevicePort) port; } else if (type
         * == AudioSystem.DEVICE_OUT_WIRED_HEADSET || type ==
         * AudioSystem.DEVICE_OUT_WIRED_HEADPHONE) { mAudioSink = (AudioDevicePort) port; } } } if
         * (mAudioSource != null && mAudioSink != null) { AudioDevicePortConfig sourceConfig =
         * (AudioDevicePortConfig) mAudioSource .activeConfig(); AudioDevicePortConfig sinkConfig =
         * (AudioDevicePortConfig) mAudioSink.activeConfig(); AudioPatch[] audioPatchArray = new
         * AudioPatch[] {null}; status = mAudioManager.createAudioPatch(audioPatchArray, new
         * AudioPortConfig[] {sourceConfig}, new AudioPortConfig[] {sinkConfig}); mAudioPatch =
         * audioPatchArray[0]; }
         */
        return status;
    }

    private FmOnAudioPortUpdateListener mAudioPortUpdateListener = null;

    private class FmOnAudioPortUpdateListener implements OnAudioPortUpdateListener {
        /**
         * Callback method called upon audio port list update.
         *
         * @param portList the updated list of audio ports
         */
        @Override
        public void onAudioPortListUpdate(AudioPort[] portList) {
            // Ingore audio port update
        }

        /**
         * Callback method called upon audio patch list update.
         *
         * @param patchList the updated list of audio patches
         */
        @Override
        public void onAudioPatchListUpdate(AudioPatch[] patchList) {
            /*
             * if (mPowerStatus != POWER_UP) { Log.d(TAG, "onAudioPatchListUpdate, not power up");
             * return; } if (!mIsAudioFocusHeld) { Log.d(TAG,
             * "onAudioPatchListUpdate no audio focus"); return; } if (mAudioPatch != null) {
             * ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
             * mAudioManager.listAudioPatches(patches); // When BT or WFD is connected, native will
             * remove the patch (mixer -> device). // Need to recreate AudioRecord and AudioTrack
             * for this case. if (isPatchMixerToDeviceRemoved(patches)) { Log.d(TAG,
             * "onAudioPatchListUpdate reinit for BT or WFD connected"); initAudioRecordSink();
             * startRender(); return; } if (isPatchMixerToEarphone(patches)) { stopRender(); } else
             * { releaseAudioPatch(); startRender(); } } else if (mIsRender) { ArrayList<AudioPatch>
             * patches = new ArrayList<AudioPatch>(); mAudioManager.listAudioPatches(patches); if
             * (isPatchMixerToEarphone(patches)) { int status; stopAudioTrack(); stopRender();
             * status = createAudioPatch(); if (status != AudioManager.SUCCESS){ Log.d(TAG,
             * "onAudioPatchListUpdate: fallback as createAudioPatch failed"); startRender(); } } }
             */
        }

        /**
         * Callback method called when the mediaserver dies
         */
        @Override
        public void onServiceDied() {
            enableFmAudio(false);
        }
    }

    private synchronized void releaseAudioPatch() {
        if (mAudioPatch != null) {
            Log.d(TAG, "releaseAudioPatch");
            /*
             * mAudioManager.releaseAudioPatch(mAudioPatch);
             */
            mAudioPatch = null;
        }
        mAudioSource = null;
        mAudioSink = null;
    }

    private void registerFmBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(SOUND_POWER_DOWN_MSG);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        //filter.addAction(ACTION_DEVICE_STORAGE_LOW_STATE);
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        filter.addAction(ACTION_FM_HEADSET_NEXT);
        filter.addAction(Intent.ACTION_USER_SWITCHED);

        mBroadcastReceiver = new FmServiceBroadcastReceiver();
        registerReceiver(mBroadcastReceiver, filter);
    }

    private void unregisterFmBroadcastReceiver() {
        if (null != mBroadcastReceiver) {
            unregisterReceiver(mBroadcastReceiver);
            mBroadcastReceiver = null;
        }
    }

    @Override
    public void onDestroy() {
        mAudioManager.setParameters("AudioFmPreStop=1");
        setMute(true);
        if (mIsStartTimeCount) stopTimer();
        mMediaKeyHandler.getLooper().quit();
        mMediaSessionManager.setOnMediaKeyListener(null, null);
        unregisterFmBroadcastReceiver();
        unregisterSdcardListener();
        mCurrentStationItem.unRegisterObserver(this);
        abandonAudioFocus();
        /*  fix bug 554217 FM have no sound when reopen FM after remove FM from recents. @{ */
        if (mIsDeviceOpen) {
            exitFm();
        }
        /* @} */
        if (null != mFmRecorder) {
            mFmRecorder = null;
        }
        stopRender();
        releaseAudioPatch();
        stopForeground(true);
        /*
         * unregisterAudioPortUpdateListener();
         */
        super.onDestroy();
    }

    /**
     * Exit FMRadio application
     */
    private void exitFm() {
        mIsAudioFocusHeld = false;
        // Stop FM recorder if it is working
        if (null != mFmRecorder) {
            synchronized (mStopRecordingLock) {
                int fmState = mFmRecorder.getState();
                if (FmRecorder.STATE_RECORDING == fmState) {
                    mFmRecorder.stopRecording();
                }
            }
        }

        // When exit, we set the audio path back to earphone.
        if (mIsNativeScanning || mIsNativeSeeking) {
            stopScan();
        }

        mFmServiceHandler.removeCallbacksAndMessages(null);
        mFmServiceHandler.removeMessages(FmListener.MSGID_FM_EXIT);
        mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_FM_EXIT);
    }



    /* @} */

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Change the notification string.
        if (mPowerStatus == POWER_UP) {
            updatePlayingNotification();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        // Modify for Bug782729, Unable to start activity ComponentInfo
        Log.d(TAG,"onStartCommand");
        showPlayingNotification();
        if(getPowerStatus() != POWER_UP) {
            stopForeground(true);
        }
        if (intent != null) {
            String action = intent.getAction();
            if (FM_SEEK_PREVIOUS.equals(action)) {
                seekStationAsync(mCurrentStationItem.stationFreq, false);
            } else if (FM_SEEK_NEXT.equals(action)) {
                seekStationAsync(mCurrentStationItem.stationFreq, true);
            } else if (FM_TURN_OFF.equals(action)) {
                setFmUIDisable(FmListener.BTN_NOTI_TURN_OFF,false);
                powerDownAsync();
            } else if (FM_STOP_RECORDING.equals(action)) {
                mFmServiceHandler.removeMessages(
                        FmListener.MSGID_STARTRECORDING_FINISHED);
                mFmServiceHandler.removeMessages(
                        FmListener.MSGID_STOPRECORDING_FINISHED);
                Log.d(TAG, "click notification button stop recording.");
                stopRecording();
            }
        }
        return START_NOT_STICKY;
    }

    private void setFmUIDisable(int notificationButtonType,boolean isSeek) {
        Bundle bundle = new Bundle(2);
        bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_SET_UI_DISABLE);
        bundle.putBoolean(FmListener.KEY_IS_SEEK, isSeek);
        notifyActivityStateChanged(bundle);
    }

    /**
     * Open or close FM Radio audio
     *
     * @param enable true, open FM audio; false, close FM audio;
     */
    private void enableFmAudio(boolean enable) {
        Log.d(TAG, "enableFmAudio:" + enable);
        if (enable) {
            if ((mPowerStatus != POWER_UP) || !mIsAudioFocusHeld) {
                Log.d(TAG, "enableFmAudio, current not available return.mIsAudioFocusHeld:"
                        + mIsAudioFocusHeld);
                return;
            }

            ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
            mAudioManager.listAudioPatches(patches);
            if (mAudioPatch == null) {
                Log.d(TAG, "mAudioPatch == null");
                if (isPatchMixerToEarphone(patches)) {
                    int status;
                    //stopRender();
                    status = createAudioPatch();
                    if (status != AudioManager.SUCCESS) {
                        Log.d(TAG, "enableFmAudio: fallback as createAudioPatch failed");
                        startRender();
                    }
                } else {
                    createAudioPatch();
                    startRender();
                }
            }
        } else {
            releaseAudioPatch();
            stopRender();
        }
    }

    // Make sure patches count will not be 0
    private boolean isPatchMixerToEarphone(ArrayList<AudioPatch> patches) {
        int deviceCount = 0;
        int deviceEarphoneCount = 0;
        for (AudioPatch patch : patches) {
            AudioPortConfig[] sources = patch.sources();
            AudioPortConfig[] sinks = patch.sinks();
            AudioPortConfig sourceConfig = sources[0];
            AudioPortConfig sinkConfig = sinks[0];
            AudioPort sourcePort = sourceConfig.port();
            AudioPort sinkPort = sinkConfig.port();
            Log.d(TAG, "isPatchMixerToEarphone " + sourcePort + " ====> " + sinkPort);
            if (sourcePort instanceof AudioMixPort && sinkPort instanceof AudioDevicePort) {
                deviceCount++;
                int type = ((AudioDevicePort) sinkPort).type();
                if (type == AudioSystem.DEVICE_OUT_WIRED_HEADSET ||
                        type == AudioSystem.DEVICE_OUT_WIRED_HEADPHONE) {
                    deviceEarphoneCount++;
                }
            }
        }
        if (deviceEarphoneCount == 1 && deviceCount == deviceEarphoneCount) {
            return true;
        }
        return false;
    }

    /**
     * Add for bug700238,add notification channel for androidO platform
     * Create Notification Channel
     */
    private void createNotificationChannel() {
        Log.d(TAG, "createNotificationChannel");
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            final String nameChannel = mContext.getString(R.string.app_name, "FMRadio");
            final NotificationChannel channel = new NotificationChannel(mFMRadioNotification, nameChannel, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);
            mNotificationManager.createNotificationChannel(channel);
        }
    }


    /**
     * Show notification
     */
    private void showPlayingNotification() {
        if (mNotificationManager != null && mNotificationManager.getNotificationChannel(mFMRadioNotification) == null) {
            createNotificationChannel();
        }
        String radioText = mCurrentStationItem.rt;
        String stationName =mCurrentStationItem.stationName;
        if (TextUtils.isEmpty(stationName)) {
            stationName = FmUtils.formatStation(mCurrentStationItem.stationFreq)+"MHz";
        } else {
            stationName = mCurrentStationItem.stationName + "-" + FmUtils.formatStation(mCurrentStationItem.stationFreq) + "MHz";
        }
        if(TextUtils.isEmpty(radioText)){
            radioText = getString(R.string.app_name);
        }
        Intent aIntent = new Intent(Intent.ACTION_MAIN);
        aIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        aIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        aIntent.setClassName(getPackageName(), mTargetClassName);
        PendingIntent pAIntent = PendingIntent.getActivity(mContext, 0, aIntent, 0);
        if (mIsScanning) {
            Notification.Builder builder = new Notification.Builder(mContext)
                   .setSmallIcon(R.drawable.notification_stat_fm)
                   .setShowWhen(false)
                   .setAutoCancel(true)
                   .setContentIntent(pAIntent)
                   .setContentTitle(getString(R.string.station_searching_tips));
            Log.d(TAG, "startForeground showNotification but no action");
            // add notification channel for androidO platform
            builder.setChannel(mFMRadioNotification);
            Notification notification = builder.build();
            startForeground(NOTIFICATION_ID, notification);
            return;
        }

        if (getRecorderState() == FmRecorder.STATE_RECORDING) {
            Intent intent = new Intent(FM_STOP_RECORDING);
            intent.setClass(mContext, FmService.class);
            PendingIntent pendingIntent = PendingIntent.getService(mContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder builder = new Notification.Builder(this)
                    .setContentText(stationName + "-" + getString(R.string.recording_tips))
                    .setShowWhen(false)
                    .setAutoCancel(true)
                    .setSmallIcon(R.drawable.notification_stat_fm_rec)
                    .addAction(R.drawable.btn_fm_rec_stop_enabled, getText(R.string.stop_record),
                            pendingIntent);

            builder.setContentIntent(pAIntent);
            // add notification channel for androidO platform
            builder.setChannel(mFMRadioNotification);

            String recordTime = FmUtils.makeTimeString((int)getRecordTime());
            Bundle bundle = new Bundle(2);
            bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_REFRESH);
            bundle.putString(FmListener.KEY_RECORD_TIME, recordTime);
            notifyActivityStateChanged(bundle);

            builder.setContentTitle(recordTime);
            Notification notification = builder.build();
            startForeground(NOTIFICATION_ID, notification);
            return;
        }

        synchronized (mNotificationLock) {

            mNotificationBuilder = new Notification.Builder(mContext);
            mNotificationBuilder.setSmallIcon(R.drawable.notification_stat_fm);
            mNotificationBuilder.setShowWhen(false);
            mNotificationBuilder.setAutoCancel(true);

            if (mPowerStatus == POWER_UP) {
                Intent intent = new Intent(FM_SEEK_PREVIOUS);
                intent.setClass(mContext, FmService.class);
                PendingIntent pIntent = PendingIntent.getService(mContext, 0, intent, 0);
                String title = getString(R.string.fm_prevstation_title);
                mNotificationBuilder.addAction(R.drawable.btn_fm_prevstation, title, pIntent);
                intent = new Intent(FM_TURN_OFF);
                intent.setClass(mContext, FmService.class);
                pIntent = PendingIntent.getService(mContext, 0, intent, 0);
                title = getString(R.string.power_button_power_off);
                mNotificationBuilder.addAction(R.drawable.btn_fm_rec_stop_enabled, title, pIntent);
                intent = new Intent(FM_SEEK_NEXT);
                intent.setClass(mContext, FmService.class);
                pIntent = PendingIntent.getService(mContext, 0, intent, 0);
                title = getString(R.string.fm_nextstation_title);
                mNotificationBuilder.addAction(R.drawable.btn_fm_nextstation, title, pIntent);
            }
                mNotificationBuilder.setContentIntent(pAIntent);
                mNotificationBuilder.setContentTitle(stationName);
                // If radio text is "" or null, we also need to update notification.
                mNotificationBuilder.setContentText(radioText);
                // add notification channel for androidO platform
                mNotificationBuilder.setChannel(mFMRadioNotification);
                Log.d(TAG, "showPlayingNotification PS:" + stationName + ", RT:" + radioText);
                Notification n = mNotificationBuilder.build();
                n.flags &= ~Notification.FLAG_NO_CLEAR;
                startForeground(NOTIFICATION_ID, n);
        }
    }

    /**
     * Update notification
     */
    public void updatePlayingNotification() {
        Log.d(TAG,"updatePlayingNotification");
        mFmServiceHandler.removeMessages(FmListener.MSGID_UPDATE_NOTIFICATION);
        mFmServiceHandler.sendEmptyMessage(FmListener.MSGID_UPDATE_NOTIFICATION);
    }

    /**
     * Register sdcard listener for record
     */
    private void registerSdcardReceiver() {
        if (mSdcardListener == null) {
            mSdcardListener = new SdcardListener();
        }
        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("file");
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        registerReceiver(mSdcardListener, filter);
    }

    private void unregisterSdcardListener() {
        if (null != mSdcardListener) {
            unregisterReceiver(mSdcardListener);
            mSdcardListener = null;
        }
    }

    private void updateSdcardStateMap(Intent intent) {
        String action = intent.getAction();
        String sdcardPath = null;
        Uri mountPointUri = intent.getData();
        if (mountPointUri != null) {
            sdcardPath = mountPointUri.getPath();
            Log.d(TAG, "updateSdcardStateMap mountPointUri:" + mountPointUri + " ,sdcardPath:" + sdcardPath);
            if (sdcardPath != null) {
                if (Intent.ACTION_MEDIA_EJECT.equals(action)) {
                    mSdcardStateMap.put(sdcardPath, false);
                } else if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)) {
                    mSdcardStateMap.put(sdcardPath, false);
                } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                    mSdcardStateMap.put(sdcardPath, true);
                }
            }
        }
    }

    /**
     * Notify FM recorder state
     *
     * @param state The current FM recorder state
     */
    @Override
    public void onRecorderStateChanged(int state) {
        Log.i(TAG, "onRecorderStateChanged mRecordState = " + mRecordState + " state = " + state);
        //update notification when record station change
        updatePlayingNotification();
        if ((mAudioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION || mAudioManager.getMode() == AudioManager.MODE_RINGTONE)
                && ((mRecordState == FmRecorder.STATE_INVALID || mRecordState == FmRecorder.STATE_IDLE) && state == FmRecorder.STATE_RECORDING)) {
            Log.e(TAG, "start recording failed because of call incoming");
            mFmRecorder.stopRecording();
        } else {
            mRecordState = state;
           if (state == FmRecorder.STATE_IDLE && isRecordingTmpFileExist()) {
               if (getRecordTime() < 1000) {
                   disCardRecordingAsync();
                   showToast(getString(R.string.toast_record_not_saved_fortime));
               } else {
                   saveRecordingAsync();
               }
           }

            Bundle bundle = new Bundle(2);
            bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.LISTEN_RECORDSTATE_CHANGED);
            bundle.putInt(FmListener.KEY_RECORDING_STATE, state);
            notifyActivityStateChanged(bundle);
        }
    }

    /**
     * Notify FM recorder error message
     *
     * @param error The recorder error type
     */
    @Override
    public void onRecorderError(int error) {
        // if media server die, will not enable FM audio, and convert to
        // ERROR_PLAYER_INATERNAL, call back to activity showing toast.
        mRecorderErrorType = error;

        Bundle bundle = new Bundle(2);
        bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.LISTEN_RECORDERROR);
        bundle.putInt(FmListener.KEY_RECORDING_ERROR_TYPE, mRecorderErrorType);
        notifyActivityStateChanged(bundle);
    }

    /**
     * Check the headset is plug in or plug out
     *
     * @return true for plug in; false for plug out
     */
    private boolean isHeadSetIn() {
        return (0 == mValueHeadSetPlug);
    }

    private void focusChanged(int focusState) {
        mIsAudioFocusHeld = false;
        if (mIsNativeScanning || mIsNativeSeeking) {
            // make stop scan from activity call to service.
            // notifyActivityStateChanged(FMRadioListener.LISTEN_SCAN_CANCELED);
            stopScan();
        }

        // using handler thread to update audio focus state
        updateAudioFocusAync(focusState);
    }

    /**
     * Request audio focus
     *
     * @return true, success; false, fail;
     */
    public boolean requestAudioFocus() {
        if (mIsAudioFocusHeld) {
            Log.d(TAG, "requestAudioFocus,mIsAudioFocusHeld=true,just return.");
            return true;
        }

        //int audioFocus = mAudioManager.requestAudioFocus(mAudioFocusChangeListener,
        //      AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        int audioFocus = mAudioManager.requestAudioFocus(mAudioFocusChangeListener,
                new AudioAttributes.Builder()
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC).build(),
                AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS);
        mIsAudioFocusHeld = (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioFocus);
        Log.d(TAG, "requestAudioFocus,result =" + mIsAudioFocusHeld);
        return mIsAudioFocusHeld;
    }

    /**
     * Abandon audio focus
     */
    public void abandonAudioFocus() {
        mAudioManager.abandonAudioFocus(mAudioFocusChangeListener);
        mIsAudioFocusHeld = false;
    }

    /**
     * Use to interact with other voice related app
     */
    private final OnAudioFocusChangeListener mAudioFocusChangeListener =
            new OnAudioFocusChangeListener() {
                /**
                 * Handle audio focus change ensure message FIFO
                 *
                 * @param focusChange audio focus change state
                 */
                @Override
                public void onAudioFocusChange(int focusChange) {
                    Log.d(TAG, "onAudioFocusChange " + focusChange);
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            synchronized (this) {
                                mAudioManager.setParameters("AudioFmPreStop=1");
                                setMute(true);
                                // bug641763,move forceToHeadsetMode before setMute,so it can be process earlier than music play.
                                // when fm playing and change to music,there won't be music leak.
                                if (mIsSpeakerUsed) {
                                    setForceUse(false);
                                    // save user's option to shared preferences.
                                    FmUtils.setIsSpeakerModeOnFocusLost(mContext, false);
                                }
                                //  bug598566 Music will play in speaker for a second when FM played in speaker.
                                // forceToHeadsetMode();
                                focusChanged(AudioManager.AUDIOFOCUS_LOSS);
                            }
                            break;

                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            synchronized (this) {
                                mAudioManager.setParameters("AudioFmPreStop=1");
                                setMute(true);
                                // bug641763,move forceToHeadsetMode before setMute,so it can be process earlier than recorder play.
                                // when fm playing and change to recorder,there won't be recorder voice leak.
                                if (mIsSpeakerUsed) {
                                    setForceUse(false);
                                    // save user's option to shared preferences.
                                    FmUtils.setIsSpeakerModeOnFocusLost(mContext, true);
                                }

                                //  bug598566 Music will play in speaker for a second when FM played in speaker.
                                // forceToHeadsetMode();
                                focusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
                            }
                            break;

                        case AudioManager.AUDIOFOCUS_GAIN:
                            synchronized (this) {
                                /**
                                 *  bug517912, FM can not resume playing probability.
                                 *
                                 * @{
                                 */
                                mIsAudioFocusHeld = true;
                                /**
                                 * @}
                                 */
                                /* Originally add this judgement for  bug567038 bug569270  on android6.0,
                                 * after android6.0 this judgement is not neccessary,and also results to new issue like bug766118,so remove it.
                                 * if (!isAudioRecording() || (mFmRecorder != null && (mFmRecorder.getState() == FmRecorder.STATE_RECORDING)))
                                 */
                                updateAudioFocusAync(AudioManager.AUDIOFOCUS_GAIN);
                            }
                            break;

                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            synchronized (this) {
                                updateAudioFocusAync(
                                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
                            }
                            break;

                        default:
                            break;
                    }
                }
            };

    public boolean isAudioRecording() {
        boolean isRecodring = false;
        if (AudioSystem.isSourceActive(MediaRecorder.AudioSource.MIC)
                || AudioSystem.isSourceActive(MediaRecorder.AudioSource.CAMCORDER)
            /*For bug696571, remove this case or fm will not play again after autonavi map navagaion broadcast */
            /*|| AudioSystem.isSourceActive(MediaRecorder.AudioSource.VOICE_RECOGNITION)*/) {
            isRecodring = true;
        }
        return isRecodring;
    }

    /**
     * Audio focus changed, will send message to handler thread. synchronized to ensure one message
     * can go in this method.
     *
     * @param focusState AudioManager state
     */
    private synchronized void updateAudioFocusAync(int focusState) {
        final int bundleSize = 1;
        Bundle bundle = new Bundle(bundleSize);
        bundle.putInt(FmListener.KEY_AUDIOFOCUS_CHANGED, focusState);
        Message msg = mFmServiceHandler.obtainMessage(FmListener.MSGID_AUDIOFOCUS_CHANGED);
        msg.setData(bundle);
        mFmServiceHandler.sendMessage(msg);
    }

    /**
     * Audio focus changed, update FM focus state.
     *
     * @param focusState AudioManager state
     */
    private void updateAudioFocus(int focusState) {
        Log.d(TAG, "updateAudioFocus focusState:" + focusState);
        switch (focusState) {
            case AudioManager.AUDIOFOCUS_LOSS:
                if (mPowerStatus == POWER_UP) {
                    mPausedByTransientLossOfFocus = false;
                }
                // play back audio will output with music audio
                // May be affect other recorder app, but the flow can not be
                // execute earlier,
                // It should ensure execute after start/stop record.
                if (mFmRecorder != null) {
                    int fmState = mFmRecorder.getState();
                    // only handle recorder state, not handle playback state
                    if (fmState == FmRecorder.STATE_RECORDING) {
                        mFmServiceHandler.removeMessages(
                                FmListener.MSGID_STARTRECORDING_FINISHED);
                        mFmServiceHandler.removeMessages(
                                FmListener.MSGID_STOPRECORDING_FINISHED);
                        stopRecording();
                    }
                }
                handlePowerDown();
                //forceToHeadsetMode();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (mPowerStatus == POWER_UP) {
                    mPausedByTransientLossOfFocus = true;
                }
                // play back audio will output with music audio
                // May be affect other recorder app, but the flow can not be
                // execute earlier,
                // It should ensure execute after start/stop record.
                if (mFmRecorder != null) {
                    int fmState = mFmRecorder.getState();
                    if (fmState == FmRecorder.STATE_RECORDING) {
                        mFmServiceHandler.removeMessages(
                                FmListener.MSGID_STARTRECORDING_FINISHED);
                        mFmServiceHandler.removeMessages(
                                FmListener.MSGID_STOPRECORDING_FINISHED);
                        stopRecording();
                    }
                }
                handlePowerDown();
                //forceToHeadsetMode();
                break;

            case AudioManager.AUDIOFOCUS_GAIN:
                if (FmUtils.getIsSpeakerModeOnFocusLost(mContext)) {
                    setForceUse(true);
                    FmUtils.setIsSpeakerModeOnFocusLost(mContext, false);
                }
                try {
                    Thread.sleep(300);  // we sleep 300ms to wait 3rdpart app to have setmode() done.
                } catch (Exception e) {
                    Log.d(TAG, "updateAudioFocus Sleep exception");
                }

                Log.d(TAG, "updateAudioFocus mShouldNotPowerUp:" + mShouldNotPowerUp + ",mPausedByTransientLossOfFocus:" + mPausedByTransientLossOfFocus);
                if (mShouldNotPowerUp) {
                    mShouldNotPowerUp = false;
                    mPausedByTransientLossOfFocus = false;
                    return;
                }
                if ((mPowerStatus != POWER_UP) && mPausedByTransientLossOfFocus) {
                    final int bundleSize = 1;
                    mFmServiceHandler.removeMessages(FmListener.MSGID_POWERUP_FINISHED);
                    mFmServiceHandler.removeMessages(FmListener.MSGID_POWERDOWN_FINISHED);
                    Bundle bundle = new Bundle(bundleSize);
                    bundle.putInt(FM_FREQUENCY, mCurrentStationItem.stationFreq);
                    handlePowerUp(bundle);
                }
                setMuteAsync(false, false);
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                setMuteAsync(true, false);
                break;

            default:
                break;
        }
    }

    /**
     * FM Radio listener record
     */
    private static class Record {
        int mHashCode; // hash code
        FmListener mCallback; // call back
    }

    /**
     * Register FM Radio listener, activity get service state should call this method register FM
     * Radio listener
     *
     * @param callback FM Radio listener
     */
    public void registerFmRadioListener(FmListener callback) {
        synchronized (mRecords) {
            // register callback in AudioProfileService, if the callback is
            // exist, just replace the event.
            Record record = null;
            int hashCode = callback.hashCode();
            final int n = mRecords.size();
            for (int i = 0; i < n; i++) {
                record = mRecords.get(i);
                if (hashCode == record.mHashCode) {
                    return;
                }
            }
            record = new Record();
            record.mHashCode = hashCode;
            record.mCallback = callback;
            mRecords.add(record);
        }
    }

    /**
     * Call back from service to activity
     *
     * @param bundle The message to activity
     */
    private void notifyActivityStateChanged(Bundle bundle) {
        synchronized (mRecords) {
            if (!mRecords.isEmpty()) {
                Iterator<Record> iterator = mRecords.iterator();
                while (iterator.hasNext()) {
                    Record record = (Record) iterator.next();

                    FmListener listener = record.mCallback;

                    if (listener == null) {
                        iterator.remove();
                        return;
                    }

                    listener.onCallBack(bundle);
                }
            }
        }
    }

    /**
     * Call back from service to the current request activity Scan need only notify
     * FmFavoriteActivity if current is FmFavoriteActivity
     *
     * @param bundle The message to activity
     */
    private void notifyCurrentActivityStateChanged(Bundle bundle) {
        synchronized (mRecords) {
            if (!mRecords.isEmpty()) {
            Log.d(TAG, "notifyCurrentActivityStateChanged = " + mRecords.size());
                if (mRecords.size() > 0) {
                    Record record = mRecords.get(mRecords.size() - 1);
                    FmListener listener = record.mCallback;
                    if (listener == null) {
                        mRecords.remove(record);
                        return;
                    }
                    listener.onCallBack(bundle);
                }
            }
        }
    }

    /**
     * Unregister FM Radio listener
     *
     * @param callback FM Radio listener
     */
    public void unregisterFmRadioListener(FmListener callback) {
        remove(callback.hashCode());
    }

    /**
     * Remove call back according hash code
     *
     * @param hashCode The call back hash code
     */
    private void remove(int hashCode) {
        synchronized (mRecords) {
            Iterator<Record> iterator = mRecords.iterator();
            while (iterator.hasNext()) {
                Record record = (Record) iterator.next();
                if (record.mHashCode == hashCode) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Check recording sd card is unmount
     *
     * @param intent The unmount sd card intent
     * @return true or false indicate whether current recording sd card is unmount or not
     */
    public boolean isRecordingCardUnmount(Intent intent) {
        String unmountSDCard = intent.getData().toString();
        Log.d(TAG, "unmount sd card file path: " + unmountSDCard);
        return unmountSDCard.equalsIgnoreCase("file://" + sRecordingSdcard) ? true : false;
    }

    private int[] updateStations(int[] stations) {
        Log.d(TAG, "updateStations. searched stations:" + Arrays.toString(stations));
        int firstValidstation = mCurrentStationItem.stationFreq;
        int stationNum = batchInsertStationToDb(stations, null);
        int searchedNum = (stations == null ? 0 : stations.length);
        Log.d(TAG, "updateStations .firstValidstation:" + firstValidstation +
                ",searchedNum:" + searchedNum);
        return (new int[]{
                firstValidstation, searchedNum
        });
    }


    private int batchInsertStationToDb(int[] stations, List<FmStationItem> favoriteList) {
        if (null == stations) return 0;
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ContentResolver resolver = mContext.getContentResolver();
        for (int i = 0; i < stations.length; i++) {
            if (!FmUtils.isValidStation(stations[i]) || FmStation.isFavoriteStation(mContext, stations[i])) {
                Log.d(TAG, "station is favorite : " + stations[i]);
                continue;
            }
            ContentProviderOperation.Builder op = ContentProviderOperation.newInsert(Station.CONTENT_URI);
            op.withYieldAllowed(false);
            Log.d(TAG, "station : " + stations[i]);
            ContentValues values = new ContentValues();
            values.clear();
            values.put(Station.FREQUENCY, stations[i]);
            values.put(Station.IS_SHOW, Station.IS_SCAN);
            op.withValues(values);
            ops.add(op.build());
        }
        Log.d(TAG, "ops size : " + ops.size());
        int stationNum = ops.size();
        if (stationNum > 0) {
            try {
                ContentProviderResult[] result = resolver.applyBatch(FmStation.AUTHORITY, ops);
                ops.clear();
                Log.d(TAG, "batch opreate db result count : " + result.length);
            } catch (Exception e) {
                Log.d(TAG, "Batch operate exception");
                e.printStackTrace();
            }
        } else {
            mContext.getContentResolver().notifyChange(FmStation.Station.CONTENT_URI, null);
        }
        return stationNum;
    }

    private boolean isFavoriteStation(List<FmStationItem> favoriteList, int station) {
        Log.d(TAG, "favorite list size : " + favoriteList.size());
        for (FmStationItem item : favoriteList) {
            Log.d(TAG, "stationFreq : " + item.stationFreq + " : station : " + station);
            if (station == item.stationFreq)
                return true;
        }
        return false;

    }

    /**
     * The background handler
     */
    class FmRadioServiceHandler extends Handler {
        public FmRadioServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Bundle bundle;
            boolean isPowerup = false;
            boolean isSwitch = true;
            Log.d(TAG,"msg.what" + msg.what);

            switch (msg.what) {
                // open device
                case FmListener.MSGID_OPEN_DEVICE:
                    if (requestAudioFocus()) {
                        openDevice();
                    }
                    break;

                // power up
                case FmListener.MSGID_POWERUP_FINISHED:
                    bundle = msg.getData();
                    handlePowerUp(bundle);
                    break;

                // power down
                case FmListener.MSGID_POWERDOWN_FINISHED:
                    //Modify for 642451,Start record fm after make fm close after 1 minute,the time in recrod activity is wrong.
                    if (mFmRecorder != null) {
                        int fmState = mFmRecorder.getState();
                        if (fmState == FmRecorder.STATE_RECORDING) {
                            mFmServiceHandler.removeMessages(
                                    FmListener.MSGID_STARTRECORDING_FINISHED);
                            mFmServiceHandler.removeMessages(
                                    FmListener.MSGID_STOPRECORDING_FINISHED);
                            stopRecording();
                        }
                    }
                    handlePowerDown();
                    onlyCloseDevice();
                    break;

                // fm exit
                case FmListener.MSGID_FM_EXIT:
                    if (mIsSpeakerUsed) {
                        setForceUse(false);
                        // SPRD: Modify for 707075,DUT automatically opens FM speaker while sending file using Bluetooth
                        FmUtils.setIsSpeakerModeOnFocusLost(mContext, false);
                    }
                    int powerState = getPowerStatus();
                    powerDown();
                    closeDevice();

                    bundle = new Bundle(1);
                    bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_FM_EXIT);
                    bundle.putInt(FmListener.KEY_STATE_BEFORE_EXIT, powerState);
                    notifyActivityStateChanged(bundle);
                    // Finish favorite when exit FM
                    if (sExitListener != null) {
                        sExitListener.onExit();
                    }
                    break;

                // switch antenna
                case FmListener.MSGID_SWITCH_ANTENNA:
                    bundle = msg.getData();
                    int value = bundle.getInt(FmListener.SWITCH_ANTENNA_VALUE);

                    bundle.putInt(FmListener.CALLBACK_FLAG,
                            FmListener.MSGID_SWITCH_ANTENNA);
                    bundle.putBoolean(FmListener.KEY_IS_SWITCH_ANTENNA, (0 == value));
                    notifyActivityStateChanged(bundle);
                    break;

                // tune to station
                case FmListener.MSGID_TUNE_FINISHED:
                    bundle = msg.getData();
                    int tuneFreq = bundle.getInt(FM_FREQUENCY);
                    /**
                     * bug547387 Set FM mute before tune station.
                     *
                     * @{
                     */
                    setMute(true);

                    tuneStation(tuneFreq);
                    break;

                // seek to station
                case FmListener.MSGID_SEEK_FINISHED:
                    if(mPowerStatus == POWER_UP){
                        setFmUIDisable(FmListener.BTN_NOTI_SEEK,true);
                        updateRdsInfo("", "");
                        bundle = msg.getData();
                        mIsSeeking = true;
                        /**
                         * Bug530129 Set FM mute before seek station,then unmute when finished.
                         *
                         * @{
                         */
                        setMute(true);
                        /**
                         * @}
                         */
                        seekStation(bundle.getInt(FM_FREQUENCY), bundle.getBoolean(OPTION));
                        boolean isStationTunningSuccessed = false;
                    }
                    break;

                // start scan
                case FmListener.MSGID_SCAN_FINISHED:
                    int[] result = null;
                    if (powerUp(FmUtils.DEFAULT_STATION)) {
                        //should update notification syncronized or will be blocked by startScan.
                        mIsScanning = true;
                        showPlayingNotification();
                        // clear database before searching;
                        FmStation.cleanSearchedStations(mContext);
                        int start_freq = (int) msg.arg1;
                        startScan(start_freq);
                    }
                    break;

                //broadcastradio tune finished
                case FmListener.MSGID_TUNE_COMPLETE:
                    bundle = msg.getData();
                    int frequency = bundle.getInt("stationFreq");
                    mCurrentStationItem.stationFreq = frequency;
                    Log.d(TAG, "tune frequency : " + frequency);
                    mCurrentStationItem.setCurrent(mCurrentStationItem.stationFreq);
                    if (getPowerStatus() != FmService.POWER_DOWN) {
                        //Bug 904160 - SharkL3 shows playing FM but no FM sound appear in earphone/speaker during particular scenario
                        if (mAudioPatch == null) {
                            enableFmAudio(true);
                        }
                        setMute(false);
                    }
                    bundle = new Bundle();
                    bundle.putInt(FmListener.CALLBACK_FLAG,
                            FmListener.MSGID_TUNE_FINISHED);
                    bundle.putBoolean(FmListener.KEY_IS_TUNE, getPowerStatus() != FmService.POWER_DOWN);
                    notifyActivityStateChanged(bundle);
                    mIsSeeking = false;
                    break;
                //broadcastradio scan finished
                case FmListener.MSGID_SCAN_COMPLETE:
                    bundle = msg.getData();
                    int[] scanStations = bundle.getIntArray("stations");
                    int[] scanResult = null;
                    boolean isScanning = true;
                    if ((scanStations != null) && scanStations.length != 0 && scanStations[0] == -100) {
                        isScanning = false;
                        scanResult = new int[]{
                                -1, 0
                        };
                        //Add for 645915,A pop sound occurs after search stations.
                        if (mIsAudioFocusHeld) {
                            setMute(false);
                        }
                    } else {
                        scanResult = updateStations(scanStations);
                        //  Bug 557077 FM haven't get stations when FM loss AudioFocus
                        if (mIsAudioFocusHeld)
                            tuneStationAsync(mCurrentStationItem.stationFreq);
                    }

                    bundle = new Bundle(4);
                    bundle.putInt(FmListener.CALLBACK_FLAG,
                            FmListener.MSGID_SCAN_FINISHED);
                    bundle.putInt(FmListener.KEY_STATION_NUM, scanResult[1]);

                    mIsScanning = false;
                    bundle.putBoolean(FmListener.KEY_IS_SCAN, mIsScanning);
                 // Only notify the newest request activity
                    notifyCurrentActivityStateChanged(bundle);
                    break;
                 // audio focus changed
                case FmListener.MSGID_AUDIOFOCUS_CHANGED:
                    bundle = msg.getData();
                    int focusState = bundle.getInt(FmListener.KEY_AUDIOFOCUS_CHANGED);
                    updateAudioFocus(focusState);
                    break;

                case FmListener.MSGID_SET_MUTE_FINISHED:
                    bundle = msg.getData();
                    // bug 671774 Change for case: when notification rings,fm "mute"
                    //but recorder should do not stop and recorded file is normal.
                    boolean isStopRecord = bundle.getBoolean(STOPRECORD);
                    boolean mute = bundle.getBoolean(OPTION);
                    if (mute && !isStopRecord) {
                        mIsMuted = true;
                        mAudioManager.setParameter("FM_Volume", "" + 0);
                    } else {
                        setMute(mute);
                    }
                    /* Bug 607554 FM continue to play after stopped with hook key and press DUT volume key. @{ */
                    bundle = new Bundle(2);
                    bundle.putInt(FmListener.CALLBACK_FLAG,
                            FmListener.MSGID_SET_MUTE_FINISHED);
                    bundle.putBoolean(STOPRECORD, isStopRecord);
                    notifyActivityStateChanged(bundle);
                    /* Bug 607554 End@} */
                    break;

                /********** recording **********/
                case FmListener.MSGID_STARTRECORDING_FINISHED:
                    startRecording();
                    break;

                case FmListener.MSGID_STOPRECORDING_FINISHED:
                    stopRecording();
                    break;
                case FmListener.MSGID_DISCARDRECORDING_FINISHED:
                    disCardRecording();
                    break;

                case FmListener.MSGID_STOPRECORDING_NOSPACE:
                    stopRecordingNoSpace();
                    break;

                case FmListener.MSGID_SAVERECORDING_FINISHED:
                    if (saveRecording(getRecordingName())) {
                        bundle = new Bundle(3);
                        bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.LISTEN_RECORDSTATE_CHANGED);
                        bundle.putInt(FmListener.KEY_RECORDING_STATE, FmRecorder.STATE_SAVED);
                        bundle.putString(FmListener.KEY_RECORD_NAME, getRecordingName());
                        notifyActivityStateChanged(bundle);
                    }
                    break;
                case FmListener.MSGID_RDS_DATA_UPDATE:
                    Bundle data = msg.getData();
                    //if (getPowerStatus() == FmService.POWER_UP) {
                    Log.d(TAG, "MSGID_RDS_DATA_UPDATE rt = " + data.getString("rt") + " ps " + data.getString("ps") + "CurrentStation frequency : " + mCurrentStationItem.stationFreq);
                    updateRdsInfo(data.getString("rt"), data.getString("ps"));
                    break;
                case FmListener.MSGID_UPDATE_NOTIFICATION:
                    if (getRecorderState() == FmRecorder.STATE_RECORDING) {
                        mFmServiceHandler.sendEmptyMessageDelayed(
                                FmListener.MSGID_UPDATE_NOTIFICATION, FmListener.TIME_REFRESH_DELAY);
                    }
                    showPlayingNotification();
                    break;

                default:
                    break;
            }
        }

    }

    /*
     *  fix bug 552726 PS data disappear when back from FM station
     * list. @{
     */
    /*private void updateRDSToDb(ContentValues values) {
        if (FmStation.isStationExist(mContext, mCurrentStationItem.stationFreq)) {
            FmStation.updateStationToDb(mContext, mCurrentStationItem.stationFreq, values);
        } else {
            Log.d(TAG, "updateRdsToDb currrent station is not exist");
            FmStation.insertStationToDb(mContext, values);
        }
    }*/

    /**
     * The UI handler
     */
    private Handler mFmServiceUIHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    };

    /**
     * handle power down, execute power down and call back to activity.
     */
    private void handlePowerDown() {
        Bundle bundle;
        powerDown();
        bundle = new Bundle(1);
        bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_POWERDOWN_FINISHED);
        notifyActivityStateChanged(bundle);
    }

    /**
     * handle power up, execute power up and call back to activity.
     *
     * @param bundle power up frequency
     */
    private void handlePowerUp(Bundle bundle) {
        boolean isPowerUp = false;
        boolean isSwitch = true;
        int curFrequency = bundle.getInt(FM_FREQUENCY);
        if (FmUtils.isAirplane(this)) {
            Log.d(TAG, "handlePowerUp, airplane is on");
            showToast(getString(R.string.airplane_message));
            bundle = new Bundle(1);
            bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_POWERUP_FINISHED);
            notifyActivityStateChanged(bundle);
            //clear notification
            Log.d(TAG, "power up failed for airplane,remove notification");
            stopForeground(true);
            return;
        }
        if (!FmUtils.supportShortAntenna) {
            if (!isAntennaAvailable()) {
                Log.d(TAG, "handlePowerUp, earphone is not ready");
                //Toast.makeText(mContext,getString(R.string.fm_no_headset_text),Toast.LENGTH_SHORT).show();
                bundle = new Bundle(1);
                bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_POWERUP_FINISHED);
                notifyActivityStateChanged(bundle);
                //clear notification
                Log.d(TAG, "power up failed for no headset,remove notification");
                stopForeground(true);
                return;
            }
        }
        if (powerUp(curFrequency)) {
            // update for powerup.
            bundle = new Bundle(1);
            bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_POWERUP_FINISHED);
            notifyActivityStateChanged(bundle);
            //mIsDeviceReady set to true must before tuning or seeking, otherwise will lost the first ps notfication
            mIsDeviceReady = true;
            if (FmUtils.isFirstTimePlayFm(mContext)) {
                isPowerUp = firstPlaying(curFrequency);
                FmUtils.setIsFirstTimePlayFm(mContext);
            } else {
                mFmManager.tuneRadio(curFrequency);
                isPowerUp = playFrequency(curFrequency);
            }
            mPausedByTransientLossOfFocus = false;
        }else{
            showToast(getString(R.string.not_available));
            bundle = new Bundle(1);
            bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_POWERUP_FINISHED);
            notifyActivityStateChanged(bundle);
        }
    }

    /**
     * check FM is foreground or background
     */
    public boolean isActivityForeground() {
        return  mIsFmActivityForeground;
    }

    /**
     * mark FmRecordActivity activity is foreground or not
     *
     * @param isForeground
     */
    public void setFmActivityForground(boolean isForeground) {
        mIsFmActivityForeground = isForeground;
    }

    /**
     * Get the recording sdcard path when staring record
     *
     * @return sdcard path like "/storage/sdcard0"
     */
    public static String getRecordingSdcard() {
        return sRecordingSdcard;
    }

    /**
     * The listener interface for exit
     */
    public interface OnExitListener {
        /**
         * When Service finish, should notify FmFavoriteActivity to finish
         */
        void onExit();
    }

    /**
     * Register the listener for exit
     *
     * @param listener The listener want to know the exit event
     */
    public static void registerExitListener(OnExitListener listener) {
        sExitListener = listener;
    }

    /**
     * Unregister the listener for exit
     *
     * @param listener The listener want to know the exit event
     */
    public static void unregisterExitListener(OnExitListener listener) {
        sExitListener = null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        /**
         *   bug490967, remove FM in recent, re-enter the radio does not open. Original
         * Android code: exitFm();
         *
         * @{
         */
        super.onTaskRemoved(rootIntent);
        if (getRecorderState() == FmRecorder.STATE_RECORDING) {
            Log.d(TAG, "fm is recording when onTaskRemoved, save recording file");
            stopRecording();
            saveRecordWithoutDialog();
        }
        stopSelf();
        /**
         * @}
         */
    }

    /*
     * Add for saving record when clean fm by recent menu.
     */
    private void saveRecordWithoutDialog() {
        String recordingName = getRecordingName();
        String saveName = recordingName;
        if (saveName != null) {
            boolean tmpfileExistBeforeSave = isRecordingTmpFileExist();
            long recordingTime = getRecordTime();
            Log.d(TAG, "saveName = " + saveName + "recordingTime= " + recordingTime);
            if (tmpfileExistBeforeSave) {
                saveRecordForRemoveTask(saveName);
                //saveRecording(saveName);
            }
        } else {
            Log.d(TAG, "saveName = " + saveName + ", mService=" + this + ",file not saved");
        }
    }

    /*
     * Add for bug 827388, save recording should be async, or will block onDestroy
     */
    private void saveRecordForRemoveTask(String saveName) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                saveRecording(saveName);
                Log.d(TAG, "doInbackground. save recording done.");
                return null;
            }

        }.execute();
    }

    private boolean firstPlaying(int frequency) {
        boolean isSeekTune = false;
        // for main ui search tips show
        Bundle bundle;
        bundle = new Bundle(2);
        bundle.putInt(FmListener.CALLBACK_FLAG, FmListener.MSGID_SET_UI_DISABLE);
        bundle.putBoolean(FmListener.KEY_IS_SEEK, true);
        notifyActivityStateChanged(bundle);

        float result = mFmManager.seekStation(frequency, true);
        isSeekTune = result < 0 ? false : true;

        // for main ui update when seek finished(search tips dismiss)
        bundle = new Bundle(1);
        bundle.putInt(FmListener.CALLBACK_FLAG,
                FmListener.MSGID_TUNE_FINISHED);
        bundle.putBoolean(FmListener.KEY_IS_TUNE, isSeekTune);
        notifyActivityStateChanged(bundle);
        return isSeekTune;
    }

    /**
     * bug492835, FM audio route change.
     */
    public boolean setVolume(int volume) {
        if (mIsDeviceOpen) {
            mAudioManager.setParameter("FM_Volume", "" + volume);
            Log.d(TAG, "setVolume FM_Volume=" + volume);
            return true;
        }
        return false;
    }

    /* @} */

    /**
     * bug568587, Regularly power off. @{
     */
    public boolean mIsStartTimeCount = false;
    private TimeCountListener mTimeCountListener;
    private TimeCount mTimeCount;

    public interface TimeCountListener {
        public void onTimerTick(long millisUntilFinished);

        public void onTimerFinish();

        public void onUpdateTimeString();
    }

    public void setTimerListenr(TimeCountListener myTimerListenr) {
        mTimeCountListener = myTimerListenr;
    }

    public void startTimer(long millisInFuture, long countDownInterval) {
        Log.d(TAG, "startTimer");
        if (mTimeCount != null) {
            mTimeCount.cancel();
        }
        mTimeCount = new TimeCount(millisInFuture, countDownInterval);
        mTimeCount.start();
        mIsStartTimeCount = true;
        mTimeCountListener.onUpdateTimeString();
    }

    public void stopTimer() {
        Log.d(TAG, "stopTimer");
        mFmServiceUIHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mTimeCount != null) {
                    mTimeCount.cancel();
                    mIsStartTimeCount = false;
                    mTimeCountListener.onUpdateTimeString();
                }
            }
        });
    }

    class TimeCount extends CountDownTimer {

        public TimeCount(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);
        }

        @Override
        public void onTick(long millisUntilFinished) {
            mTimeCountListener.onTimerTick(millisUntilFinished);
        }

        @Override
        public void onFinish() {
            mIsStartTimeCount = false;
            mTimeCountListener.onTimerFinish();
            Log.d(TAG, "TimeCount mPausedByTransientLossOfFocus:" + mPausedByTransientLossOfFocus
                    + ",mIsNativeScanning:" + mIsNativeScanning);
            if (mPausedByTransientLossOfFocus) {
                mShouldNotPowerUp = true;
            }
            // Trigger power down if power up
            focusChanged(AudioManager.AUDIOFOCUS_LOSS);
        }
    }

    /**
     * @}
     */
    /*  Fix for bug 596494 Remove and reconnect the OTG storage, the recording file save path still display OTG storage. @{ */
    private void notifyRefreshSavePathDialog() {
        Bundle bundle = new Bundle();
        bundle.putInt(FmListener.CALLBACK_FLAG,
                FmListener.MSGID_STORAGR_CHANGED);
        notifyActivityStateChanged(bundle);
    }
    /* Bug 596494 End@} */

    private String addPaddingForString(long time) {
        StringBuilder builder = new StringBuilder();
        if (time >= 0 && time < 10) {
            builder.append("0");
        }
        return builder.append(time).toString();
    }
}
