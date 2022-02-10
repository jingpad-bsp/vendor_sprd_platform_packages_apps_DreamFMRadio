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

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.graphics.drawable.ColorDrawable;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.RadioGroup.OnCheckedChangeListener;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.widget.EditText;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.lang.reflect.Field;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import android.util.DisplayMetrics;
import android.Manifest;
import android.os.Environment;
import android.os.EnvironmentEx;
import android.os.storage.StorageManager;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.os.storage.VolumeInfo;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import com.android.fmradio.FmRecorder;
import com.android.fmradio.FmService.TimeCountListener;
import com.android.fmradio.FmStation.Station;
import com.android.fmradio.views.FmCircleSeekBar;


/**
 * This class interact with user, provide FM basic function.
 */
public class FmMainActivity extends Activity implements CurrentStationItem.FmObserverListener{

    private static final String TAG = "FmMainActivity";
    private static final int MSGID_UPDATEUI_DELAY = 300;

    private static final int NO_DIALOG = -1;
    private static final int REGULAYLY_POWER_OFF = 0;
    private static final int RECORD_FILE_PATH_DIALOG = 1;
    private static final int DIALOG_COUNT = 2;
    private Dialog[] mDialogs;

    private final int ACTION_OPERATE_TYPE = 1;
    private final int ACTION_ADD_FAVORITE_TYPE = 2;
    private final int ACTION_REMOVE_FROM_FAVORITE_TYPE = 3;

    // UI views
    private TextView mTextStationValue = null;
    private FmCircleSeekBar mCircleSeekBar;
    private FrameLayout mMainCircle;
    private LinearLayout mMainUiUpView;
    private DisplayMetrics mDisplayMetrics = null;
    // station info text view
    private TextView mSearchTips = null;
    private TextView mFreqUnit = null;
    private TextView mTextStationName = null;
    private TextView mTextRdsPs = null;
    private TextView mTextRdsRt = null;
    // UI button
    private TextView mButtonPrevStation = null;
    private TextView mButtonNextStation = null;
    private TextView mButtonAddToFavorite = null;
    private TextView mButtonEnterStationList = null;
    private TextView mButtonStartRecord = null;
    private TextView mButtonPlay = null;
    // Menu
    private MenuItem mMenuItemHeadset = null;
    private MenuItem mMenuItemRecordList = null;
    private MenuItem mMenuItemFilePath = null;
    private MenuItem mMenuItemRegularlyPoweroff = null;
    // State variables
    private boolean mIsServiceBinded = false;
    private boolean mIsActivityForeground = true;
    private boolean mIsBackPressed = false;

    private FmService mService = null;
    private Context mContext = null;
    private AudioManager mAudioManager = null;
    private CurrentStationItem mCurrentStationItem = null;
    private static final String STOPRECORD = "stopRecord";
    private static List mRecordeFilePathList = null;
    private UserManager mUserManager;
    private boolean mIsPowerupBeforeServiceKilled = true;
    private long lasttime_press_spreakermode;
    private long lasttime_press_recorder;
    private long lasttime_check_permission;
    private boolean mWaitingPermission = false;

    @Override
    public void onCurrentStationChange() {
        Log.d(TAG,"onCurrentStationChange currentStationFreq : "+mCurrentStationItem.stationFreq);
        refreshStationUI();
    }

    private void operateData(int stationFreq, int type) {
        Log.d(TAG,"operateData type = "+type+" stationFreq : "+stationFreq);
        switch (type) {
            case ACTION_ADD_FAVORITE_TYPE:
                if (FmStation.isStationExist(mContext, stationFreq)) {
                    FmStation.addToFavorite(mContext, stationFreq);
                } else {
                    ContentValues values = new ContentValues(3);
                    values.put(Station.FREQUENCY, stationFreq);
                    values.put(Station.IS_FAVORITE, 1);
                    values.put(Station.IS_SHOW, FmStation.Station.IS_NOT_SCAN);
                    FmStation.insertStationToDb(mContext, values);
                }
                break;
            case ACTION_REMOVE_FROM_FAVORITE_TYPE:
                FmStation.removeFromFavorite(mContext, stationFreq);
                break;
            default:
                break;
        }
    }

    private void operateFmFavoriteStation(int stationFreq ,int type) {
        MyAsyncTask operateTask= new MyAsyncTask();
        operateTask.execute(ACTION_OPERATE_TYPE, stationFreq, type);
    }

    class MyAsyncTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(Integer... params) {
            Log.d(TAG,"operate database type:"+params[0] +" station : "+params[1] +" type:" + params[2]);
            operateData(params[1], params[2]);
            return null;
        }
    }

    // Service listener for service callback
    private FmListener mFmRadioListener = new FmListener() {
        @Override
        public void onCallBack(Bundle bundle) {
            int flag = bundle.getInt(FmListener.CALLBACK_FLAG);
            if (flag == FmListener.MSGID_FM_EXIT) {
                mHandler.removeCallbacksAndMessages(null);
            }
            // remove tag message first, avoid too many same messages in queue.
            Message msg = mHandler.obtainMessage(flag);
            msg.setData(bundle);
            mHandler.removeMessages(flag);
            mHandler.sendMessage(msg);
        }
    };

    // Button click listeners on UI
    private final View.OnClickListener mButtonClickListener = new View.OnClickListener() {
        private Toast mToast;
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.button_add_to_favorite:
                    updateFavoriteStation();
                    break;
                case R.id.button_prevstation:
                    seekStation(mCurrentStationItem.stationFreq, false); // false: previous station
                    break;
                case R.id.button_nextstation:
                    seekStation(mCurrentStationItem.stationFreq, true); // true: previous station
                    break;
                case R.id.button_enter_to_stationlist:
                    enterStationList();
                    break;
                case R.id.button_record:
		    if ((SystemClock.elapsedRealtime() -lasttime_press_recorder) >= 800) {
                            lasttime_press_recorder = SystemClock.elapsedRealtime();
	                    if(checkBuildRecordingPermission()) {
        	                startButtonClicked();
                	    }
		    }
                    break;
                case R.id.play_button:
                    if(!FmUtils.isAirplane(FmMainActivity.this) && !FmUtils.supportShortAntenna && !isWiredHeadsetIn()){
                        if (mToast == null) {
                            mToast = Toast.makeText(mContext,getString(R.string.fm_no_headset_text),Toast.LENGTH_SHORT);
                        } else {
                            mToast.cancel();
                            mToast = Toast.makeText(mContext,getString(R.string.fm_no_headset_text),Toast.LENGTH_SHORT);
                        }
                        mToast.show();
                        break;
                    }
                    if (null != mService) {
                        if (mService.getPowerStatus() == FmService.POWER_UP) {
                            if (mService.isMuted()) {
                                mService.setMuteAsync(false, false);
                            } else {
                                powerDownFm();
                            }
                        } else {
                            if(!mIsServiceBinded) {
                               startForegroundService(new Intent(FmMainActivity.this, FmService.class));
                               mIsServiceBinded = bindService();
                            }
                            powerUpFm();
                        }
                    }
                    break;
                default:
                    Log.d(TAG, "mButtonClickListener.onClick, invalid view id");
                    break;
            }
        }
    };

    /**
     * Main thread handler to update UI
     */
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "mHandler.handleMessage, what = " + msg.what + ",hashcode:"
                            + mHandler.hashCode());
            Bundle bundle;
            switch (msg.what) {
                case FmListener.MSGID_POWERUP_FINISHED:
                    bundle = msg.getData();
                    boolean isPowerup = (mService.getPowerStatus() == FmService.POWER_UP);
                    if (isPowerup) {
                        refreshImageButton(true);
                        refreshActionMenuItem(true);
                    }
                    refreshPlayButton(true);
                    mCircleSeekBar.setStationValueBySeek(mCurrentStationItem.stationFreq,isPowerup);
                    closeOptionsMenu();
                    break;
                    /* Bug 607554 FM continue to play after stopped with hook key and press DUT volume key. @{ */
                case FmListener.MSGID_SET_MUTE_FINISHED:
                    boolean ismute = mService.isMuted();
                    if(mService.getPowerStatus() == FmService.POWER_UP){
                        Log.d(TAG,"set mute:ismute =" +ismute);
                    }
                    refreshImageButton(!ismute);
                    refreshActionMenuItem(!ismute);

                    mButtonPlay.setEnabled(!mService.isScanning());
                    mButtonPlay.setCompoundDrawablesWithIntrinsicBounds(null,
                        getResources().getDrawable(((!ismute)
                                             ? R.drawable.btn_fm_stop_selector
                                             : R.drawable.btn_fm_start_selector), null), null, null);
                    //closeOptionsMenu();

                    break;
                case FmListener.MSGID_SWITCH_ANTENNA:
                    bundle = msg.getData();
                    boolean hasAntenna = bundle.getBoolean(FmListener.KEY_IS_SWITCH_ANTENNA);
                    // if receive headset plug out, need set headset mode on ui
                    Log.d(TAG, "supportShortAntenna = " + FmUtils.supportShortAntenna + ", hasAntenna = " + hasAntenna);
                    if (FmUtils.supportShortAntenna) {
                        mMenuItemHeadset.setVisible(hasAntenna);
                        // going on recording,while support short antenna.
                    }
                    break;
                case FmListener.LISTEN_SPEAKER_MODE_CHANGED:
                    bundle = msg.getData();
                    boolean isSpeakerMode = bundle.getBoolean(FmListener.KEY_IS_SPEAKER_MODE);
                    if (mMenuItemHeadset != null) {
                        mMenuItemHeadset.setIcon(isSpeakerMode ? R.drawable.btn_fm_headset_selector : R.drawable.btn_fm_speaker_selector);
                    }
                    break;
                case FmListener.MSGID_POWERDOWN_FINISHED:
                    refreshImageButton(false);
                    refreshActionMenuItem(false);
                    refreshPlayButton(true);
                    closeDialogIfNecessary();
                    //update freq text when drag and powerdown
                    mCircleSeekBar.setStationValueBySeek(mCurrentStationItem.stationFreq,mService.getPowerStatus() == FmService.POWER_UP);
                    closeOptionsMenu();
                    break;

                case FmListener.MSGID_TUNE_FINISHED:
                    bundle = msg.getData();
                    boolean isTune = bundle.getBoolean(FmListener.KEY_IS_TUNE);
                    boolean isPowerUp = (mService.getPowerStatus() == FmService.POWER_UP);

                    // tune fail,should resume button status
                    if (!isTune) {
                        Log.d(TAG, "mHandler.tune: " + isTune);
                        refreshActionMenuItem(isPowerUp);
                        refreshImageButton(isPowerUp);
                        refreshPlayButton(true);
                        mSearchTips.setVisibility(View.INVISIBLE);
                        return;
                    }
                    mSearchTips.setVisibility(View.INVISIBLE);
                    refreshImageButton(true);
                    refreshActionMenuItem(true);
                    refreshPlayButton(true);
                    break;
                case FmListener.MSGID_FM_EXIT:
                    bundle = msg.getData();
                    int powerState = bundle.getInt(FmListener.KEY_STATE_BEFORE_EXIT);
                    mIsPowerupBeforeServiceKilled = powerState == FmService.POWER_UP ? true : false;
                    Log.d(TAG, "mIsPowerupBeforeServiceKilled: " + mIsPowerupBeforeServiceKilled);
                    break;
                case FmListener.MSGID_SCAN_FINISHED:
                    Log.d(TAG,"MSGID_SCAN_FINISHED");
                    updateMenuStatus();
                    break;
                    // for ui fresh when notification action processing.
                case FmListener.MSGID_SET_UI_DISABLE:
                    bundle = msg.getData();
                    boolean isSeek= bundle.getBoolean(FmListener.KEY_IS_SEEK);
                    boolean checkPowerUp = ((mService != null) ? mService.getPowerStatus() == FmService.POWER_UP : false);
                    // not set seek when power down occured.
                    if(isSeek && checkPowerUp){
                        mSearchTips.setVisibility(View.VISIBLE);
                    }else
                        mSearchTips.setVisibility(View.INVISIBLE);

                    refreshImageButton(false);
                    if (mMenuItemHeadset != null) {
                        mMenuItemHeadset.setEnabled(false);
                    }
                    closeOptionsMenu();
                    refreshPlayButton(false);
                    break;
                case FmListener.MSGID_REFRESH:
                    bundle = msg.getData();
                    String recordTimeInMillis = bundle.getString(FmListener.KEY_RECORD_TIME);
                    mButtonStartRecord.setText(recordTimeInMillis);
                    if (canRecordingInStorage() && !hasEnoughSpace()) {
                        mService.stopRecordingNoSpaceAsync();
                    }
                    break;
                case FmListener.LISTEN_RECORDSTATE_CHANGED:
                    bundle = msg.getData();
                    int newState = bundle.getInt(FmListener.KEY_RECORDING_STATE);
                    Log.d(TAG, " handleMessage, record state changed: newState = " + newState);
                    if ( newState == FmRecorder.STATE_RECORDING) {
                        mButtonStartRecord.setCompoundDrawablesWithIntrinsicBounds(null,
                                getResources().getDrawable(R.drawable.btn_fm_rec_stop_selector, null), null, null);
                    } else if (newState == FmRecorder.STATE_IDLE) {
                        mButtonStartRecord.setCompoundDrawablesWithIntrinsicBounds(null,
                                getResources().getDrawable(R.drawable.btn_fm_rec_selector, null), null, null);
                        mButtonStartRecord.setText(getResources().getString(R.string.record_title));
                    }
                    if (newState == FmRecorder.STATE_SAVED) {
                        String recordingname = bundle.getString(FmListener.KEY_RECORD_NAME);
                        showToast(recordingname + getString(R.string.toast_record_saved_end));
                    } else {
                        mButtonStartRecord.setEnabled(true);
                    }
                    break;
                case FmListener.LISTEN_RECORDERROR:
                    bundle = msg.getData();
                    int errorType = bundle.getInt(FmListener.KEY_RECORDING_ERROR_TYPE);
                    handleRecordError(errorType);
                    break;
                case FmListener.MSGID_STORAGR_CHANGED:
                    if (mDialogs[RECORD_FILE_PATH_DIALOG] != null && mDialogs[RECORD_FILE_PATH_DIALOG].isShowing()) {
                        mDialogs[RECORD_FILE_PATH_DIALOG].dismiss();
                        showDialogs(RECORD_FILE_PATH_DIALOG);
                    }
                    break;

                case FmListener.MSGID_RDS_DATA_UPDATE:
                    bundle = msg.getData();
                    mTextRdsPs.setText(bundle.getString(FmListener.KEY_PS_INFO));
                    mTextRdsRt.setText(bundle.getString(FmListener.KEY_RT_INFO));
                    mTextRdsRt.requestFocus();
                    break;

                default:
                    break;
            }
        }
    };

    private boolean canRecordingInStorage() {
        boolean ret = false;
        if (isStartRecording()) {
            //mount sdcard as internal storage
            if (FmUtils.FM_RECORD_STORAGE_PATH_SDCARD.equals(FmUtils.FM_RECORD_STORAGE_PATH_NAME)
                 && !Environment.MEDIA_MOUNTED.equals(EnvironmentEx.getExternalStoragePathState())) {
                return false;
            }
            ret = true;
        }
        return ret;
    }

    private boolean hasEnoughSpace() {
        String recordingSdcard = FmUtils.getDefaultStoragePath();
        if (recordingSdcard == null || recordingSdcard.isEmpty()) {
            return false;
        }
        return (FmUtils.hasEnoughSpace(recordingSdcard));
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        /**
         * called by system when bind service
         * @param className component name
         * @param service service binder
         */
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mService = ((FmService.ServiceBinder) service).getService();
            if (null == mService) {
                Log.e(TAG, "onServiceConnected, mService is null");
                finish();
                return;
            }

            Log.d(TAG, "onServiceConnected, mService is not null");
            invalidateOptionsMenu();
            mService.registerFmRadioListener(mFmRadioListener);
            mService.setFmActivityForground(mIsActivityForeground);
            mService.setTimerListenr(new MyTimeCountListener());

            if (!mService.isServiceInited()) {
                mService.initService();
                if (mIsPowerupBeforeServiceKilled) {
                    powerUpFm();
                }
            } else {
                if (mService.isDeviceOpen()) {
                    // update circle ui when re-entry fm main ui.
                    mCircleSeekBar.setStationValueBySeek(mCurrentStationItem.stationFreq,mService.getPowerStatus() == FmService.POWER_UP);
                }
                updateMenuStatus();
            }
        }
        /**
         * When unbind service will call this method
         * @param className The component name
         */
        @Override
        public void onServiceDisconnected(ComponentName className) {
        }
    };

    /**
     * Update the favorite UI state
     */
    private void updateFavoriteStation() {
        if (mCurrentStationItem.isFavorite == 1) {
            // Remove from favorite
            operateFmFavoriteStation(mCurrentStationItem.stationFreq, ACTION_REMOVE_FROM_FAVORITE_TYPE);
        } else {
            // Add to favorite
            operateFmFavoriteStation(mCurrentStationItem.stationFreq, ACTION_ADD_FAVORITE_TYPE);
        }
    }

    /**
     * Called when the activity is first created, initial variables
     * @param savedInstanceState The saved bundle in onSaveInstanceState
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getApplicationContext();
        setContentView(R.layout.main);

        FmUtils.restoreRecordDefaultPath();
        mUserManager = (UserManager) this.getSystemService(Context.USER_SERVICE);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mRecordeFilePathList = new ArrayList();
        mDisplayMetrics = getResources().getDisplayMetrics();

        initUiComponent();
        getActionBar().setElevation(0);
        mCurrentStationItem = CurrentStationItem.getInstance();
        mCurrentStationItem.ps = "";
        mCurrentStationItem.rt = "";
        refreshStationUI();
        mCircleSeekBar.setSeekBarChangeListener(new FmCircleSeekBar.OnSeekChangeListener() {
            public void onProgressChange(FmCircleSeekBar view, String newFreq, boolean fresh) {
                // only touch ACTION_UP, change to freq
                if(fresh){
                    tuneStation(FmUtils.computeStation(Float.parseFloat(newFreq)));
                }else{
                    // just update freq value during touch moving
                    mTextStationValue.setText(newFreq);
                }
            }
        });

        mCurrentStationItem.registerContentObserver();
        mCurrentStationItem.registerObserver(this);
        // for permission error dialog call back
        FmUtils.setOnErrorPermissionListener(new FmUtils.onErrorPermissionListener() {
            @Override
            public void onCallback() {
                Log.d(TAG,"error permission dialog callback,just do nothing now.");
            }
        });
        registerButtonClickListener();
        mDialogs = new Dialog[DIALOG_COUNT];
        FmUtils.getDefaultCustomTime();

        Log.d(TAG,"onCreate, supportShortAntenna =" + FmUtils.supportShortAntenna);
    }

    /**
     * Go to station list activity
     */
    private void enterStationList() {
        if (mService != null) {
            // AMS change the design for background start activity. need check app is background in app code
            if (mService.isActivityForeground()) {
                Intent intent = new Intent();
                intent.setClass(FmMainActivity.this, FmFavoriteActivity.class);
                startActivity(intent);
            }
        }
    }

    /**
     * Refresh main ui about the station related views, like rds and sation value.
     * will be triggered by database changes.
     * @param station The station frequency
     */
    private void refreshStationUI() {
        mTextStationValue.setText(FmUtils.formatStation(mCurrentStationItem.stationFreq));
        mTextStationName.setText(mCurrentStationItem.stationName);
        mTextRdsPs.setText(mCurrentStationItem.ps);
        mTextRdsRt.setText(mCurrentStationItem.rt);
        mTextRdsRt.requestFocus();
        boolean isPowerUp = ((mService != null) ? mService.getPowerStatus() == FmService.POWER_UP : false);
        mCircleSeekBar.setStationValueBySeek(mCurrentStationItem.stationFreq,isPowerUp);

        //mButtonAddToFavorite update
        mButtonAddToFavorite.setCompoundDrawablesWithIntrinsicBounds(null,
                getResources().getDrawable(mCurrentStationItem.isFavorite == 1 ? R.drawable.btn_fm_favorite_on_selector : R.drawable.btn_fm_favorite_off_selector, null), null, null);
        mButtonAddToFavorite.setText(getString(mCurrentStationItem.isFavorite == 1 ? R.string.remove_favorite : R.string.favorites));

    }


    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG,"onStart");
        // case: remove write permission background, and re-entry fm.
        // purpos: ereset storage path as phone after clear permission
        if (FmUtils.isExternalStorage()) {
            Log.d(TAG,"onstart FM_RECORD_STORAGE_PATH_NAME="+FmUtils.FM_RECORD_STORAGE_PATH_NAME);
            Uri externalStorageUri =FmUtils.getCurrentAccessUri(FmUtils.FM_RECORD_STORAGE_PATH_NAME);
            if (externalStorageUri == null) {
                if(isStartRecording()){
                    if (mService != null) mService.disCardRecordingAsync();
                    handleRecordError(FmRecorder.ERROR_SDCARD_WRITE_PERMISSION);
                }
                FmUtils.FM_RECORD_STORAGE_PATH = 0;
                FmUtils.FM_RECORD_STORAGE_PATH_NAME = FmUtils.FM_RECORD_STORAGE_PATH_PHONE;
                FmUtils.saveRecordDefaultPath();
            }
        }

        if (!mIsServiceBinded) {
            startForegroundService(new Intent(FmMainActivity.this, FmService.class));
            mIsServiceBinded = bindService();
        }
        if (!mIsServiceBinded) {
            Log.e(TAG, "onStart, cannot bind FM service");
            finish();
            return;
        }
    }

    /**
     * Refresh UI, when stop search, dismiss search dialog, pop up recording dialog if FM stopped
     * when recording in background
     */
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG,"onResume");
        mIsActivityForeground = true;
        if (null == mService) {
            Log.d(TAG, "onResume, mService is null");
            return;
        }
        mService.setFmActivityForground(mIsActivityForeground);
        updateMenuStatus();
    }

    /**
     * When activity is paused call this method, indicate activity enter background if press exit,
     * power down FM
     */
    @Override
    public void onPause() {
        Log.d(TAG,"onPause");
        mIsActivityForeground = false;
        if (null != mService) {
            mService.setFmActivityForground(mIsActivityForeground);
        }
        super.onPause();
    }

    /**
     * Called when activity enter stopped state, unbind service, if exit pressed, stop service
     */
    @Override
    public void onStop() {
        Log.d(TAG,"onStop");
        unBindService();
        super.onStop();
    }

    /**
     * When activity destroy, unregister broadcast receiver and remove handler message
     */
    @Override
    public void onDestroy() {
        Log.d(TAG,"onDestory");
        // Remove dialogs who is showing,or there will be a window leak.
        closeDialogIfNecessary();
        // need to call this function because if doesn't do this,after
        // configuration change will have many instance and recording time
        // or playing time will not refresh
        // Remove all the handle message
        mHandler.removeCallbacksAndMessages(null);
        if (mService != null) {
            mService.unregisterFmRadioListener(mFmRadioListener);
        }
        mCurrentStationItem.unRegisterContentObserver();
        mCurrentStationItem.unRegisterObserver(this);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // refresh station ui for configuration change.
        refreshStationUI();
    }
    /**
     * dialogs count : 2
     */
    private void showDialogs(int id) {
        if(mDialogs[id] != null && mDialogs[id].isShowing()) return;
        switch (id) {
            case REGULAYLY_POWER_OFF:
                LayoutInflater inflater2 = LayoutInflater.from(this);
                View view2 = inflater2.inflate(R.layout.regularly_poweroff_dialog, null);
                final RadioGroup radioGroup = (RadioGroup) view2.findViewById(R.id.radioGroup);
                radioGroup.setOnCheckedChangeListener(new RadioGroupListener());
                RadioButton time_15min = (RadioButton) view2.findViewById(R.id.time_15min);
                RadioButton time_30min = (RadioButton) view2.findViewById(R.id.time_30min);
                RadioButton time_60min = (RadioButton) view2.findViewById(R.id.time_60min);
                RadioButton time_90min = (RadioButton) view2.findViewById(R.id.time_90min);
                FmUtils.getDefaultCustomTime();
                Log.d(TAG, "GLOBAL_CUSTOM_TIME:" + FmUtils.GLOBAL_CUSTOM_TIME);
                if (FmUtils.GLOBAL_CUSTOM_TIME == 15.0) {
                    time_15min.setChecked(true);
                } else if (FmUtils.GLOBAL_CUSTOM_TIME == 30.0) {
                    time_30min.setChecked(true);
                } else if (FmUtils.GLOBAL_CUSTOM_TIME == 60.0) {
                    time_60min.setChecked(true);
                } else if (FmUtils.GLOBAL_CUSTOM_TIME == 90.0) {
                    time_90min.setChecked(true);
                }

                mDialogs[REGULAYLY_POWER_OFF] = new AlertDialog.Builder(this)
                        .setView(view2)
                        .setPositiveButton(R.string.button_ok,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        // time is avilable. dismiss dialog.
                                        dialog.dismiss();
                                        FmUtils.setDefaulCustomTime();
                                        long millisInFuture = (long) (FmUtils.GLOBAL_CUSTOM_TIME * 60 * 1000);
                                        mService.startTimer(millisInFuture, 1000);
                                    }
                                })
                        .setNegativeButton(R.string.button_cancel,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        // dialog dismiss
                                        dialog.dismiss();
                                    }
                                }).create();
                mDialogs[REGULAYLY_POWER_OFF].setCanceledOnTouchOutside(false);
                mDialogs[REGULAYLY_POWER_OFF].show();
                break;
            // file path select dialog
            case RECORD_FILE_PATH_DIALOG:
                if (Environment.MEDIA_MOUNTED.equals(EnvironmentEx.getInternalStoragePathState())) {
                    mRecordeFilePathList.add(FmUtils.FM_RECORD_STORAGE_PATH_PHONE);
                }
                if (mUserManager.isSystemUser()) {
                    Log.i(TAG, "is  SystemUser");
                    if (Environment.MEDIA_MOUNTED.equals(EnvironmentEx.getExternalStoragePathState())) {
                        mRecordeFilePathList.add(FmUtils.FM_RECORD_STORAGE_PATH_SDCARD);
                    }
                } else {
                    Log.i(TAG, "is not SystemUser");
                }
                Log.d(TAG, "FM_RECORD_STORAGE_PATH_NAME:" + FmUtils.FM_RECORD_STORAGE_PATH_NAME
                        + " ,FM_RECORD_STORAGE_PATH:" + FmUtils.FM_RECORD_STORAGE_PATH);
                final ArrayList<String> list = new ArrayList<String>(mRecordeFilePathList);
                // Create pathNameList to add storage path names in different languages.
                ArrayList<String> pathNameList = new ArrayList<>();
                for (String storagePath : list) {
                    if (storagePath.equals(FmUtils.FM_RECORD_STORAGE_PATH_PHONE)) {
                        pathNameList.add(String.valueOf(getResources().getString(R.string.storage_phone)));
                    } else if (storagePath.equals(FmUtils.FM_RECORD_STORAGE_PATH_SDCARD)) {
                        pathNameList.add(String.valueOf(getResources().getString(R.string.storage_sd)));
                    }
                }
                mDialogs[RECORD_FILE_PATH_DIALOG] = new AlertDialog.Builder(this)
                        .setTitle(getResources().getString(R.string.select_file_path))
                        .setSingleChoiceItems((pathNameList.toArray(new String[mRecordeFilePathList.size()]))
                                , FmUtils.FM_RECORD_STORAGE_PATH,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        FmUtils.FM_RECORD_STORAGE_PATH = which;
                                        FmUtils.FM_RECORD_STORAGE_PATH_NAME = (String) list
                                                .get(which);
                                        if (which > 0) {
                                            requestScopedDirectoryAccess(FmUtils.FM_RECORD_STORAGE_PATH_NAME);
                                        } else {
                                            FmUtils.saveRecordDefaultPath();
                                        }
                                        dialog.dismiss();
                                    }
                                })
                        .setNegativeButton(R.string.button_cancel,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                }).create();
                mDialogs[RECORD_FILE_PATH_DIALOG].setCanceledOnTouchOutside(false);
                mRecordeFilePathList.clear();
                mDialogs[RECORD_FILE_PATH_DIALOG].show();
                break;
            default:
                break;
        }
    }
    // dismiss dialog in some case.
    private void closeDialogIfNecessary(){
        for(int i=0;i<DIALOG_COUNT;i++) {
            if (mDialogs[i] != null && mDialogs[i].isShowing()) {
                mDialogs[i].dismiss();
            }
        }
    }
    /**
     * Create options menu
     * @param menu The option menu
     * @return true or false indicate need to handle other menu item
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.fm_action_bar, menu);
        mMenuItemHeadset = menu.findItem(R.id.fm_headset);
        if (FmUtils.supportShortAntenna) {
            mMenuItemHeadset.setVisible(isWiredHeadsetIn());
        }
        mMenuItemRecordList = menu.findItem(R.id.fm_record_list);
        mMenuItemRegularlyPoweroff = menu.findItem(R.id.fm_regularly_poweroff);
        mMenuItemFilePath = menu.findItem(R.id.fm_record_path);
        return true;
    }
    /**
     * Prepare options menu
     * @param menu The option menu
     * @return true or false indicate need to handle other menu item
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (null == mService) {
            Log.d(TAG, "onPrepareOptionsMenu, mService is null");
            return true;
        }
        int powerStatus = mService.getPowerStatus();
        boolean isPowerUp = (powerStatus == FmService.POWER_UP);
        boolean isPowerdown = (powerStatus == FmService.POWER_DOWN);
        boolean isSeeking = mService.isSeeking();
        refreshActionMenuItem(isSeeking ? false : (isPowerUp && !mService.isMuted()));

        // Timer count
        mMenuItemRegularlyPoweroff.setTitle(mService.mIsStartTimeCount ? R.string.cancel_regularly_poweroff : R.string.fm_regularly_poweroff);
        if (isPowerdown || (mService.getRecorderState() == FmRecorder.STATE_RECORDING) || !mButtonStartRecord.isEnabled()) {
            mMenuItemFilePath.setEnabled(false);
        } else {
            mMenuItemFilePath.setEnabled(true);
        }
	// when power down, mButtonStartRecord is disenabled, but actually recordList option should be enabled.
        mMenuItemRecordList.setEnabled((!isStartRecording() && mButtonStartRecord.isEnabled()) || isPowerdown);
        mMenuItemHeadset.setIcon(mService.isSpeakerUsed() ? R.drawable.btn_fm_headset_selector : R.drawable.btn_fm_speaker_selector);
        return true;
    }

    /**
     * Handle event when option item selected
     * @param item The clicked item
     * @return true or false indicate need to handle other menu item or not
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.fm_headset:
                if(mService != null && 
			 ((SystemClock.elapsedRealtime() -lasttime_press_spreakermode) >= 1000)) {
			lasttime_press_spreakermode = SystemClock.elapsedRealtime();
                    if (mService.isSpeakerUsed()) {
                        Log.d(TAG, "change to headset");
                        setSpeakerPhoneOn(false);
                    } else {
                        Log.d(TAG, "change to speaker");
                        setSpeakerPhoneOn(true);
                    }
                }
                break;
            case R.id.fm_record_list:
                refreshImageButton(false);
                refreshActionMenuItem(false);
                refreshPlayButton(false);
                enterRecordList();
                break;
            case R.id.fm_regularly_poweroff:
                if(mService != null) {
                    if (mService.mIsStartTimeCount) {
                        mService.stopTimer();
                    } else {
                        showDialogs(REGULAYLY_POWER_OFF);
                    }
                }
                break;
            case R.id.fm_record_path:
                showDialogs(RECORD_FILE_PATH_DIALOG);
                break;
            default:
                Log.e(TAG, "onOptionsItemSelected, invalid options menu item.");
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startButtonClicked() {
        if (null == mService) {
            Log.e(TAG, "onClick, mService is null");
            return;
        }
        mButtonStartRecord.setEnabled(false);
        // Stop or start recording
        if (!isStartRecording()) {
            if (FmService.POWER_UP != mService.getPowerStatus()) {
                Log.d(TAG, "fm is power down,don't start recording,return");
                return;
            }
            if (mService.isAudioRecording()) {
                showToast(getString(R.string.same_application_running));
                return;
            }
            mService.startRecordingAsync();
        } else {
            mService.stopRecordingAsync();
        }
    }

    /**
    *do not support type-c as antenna.
    *check whether wired headset is exist
    *@return true:wired headset is exist,otherwise return false
    *
    */
    private boolean isWiredHeadsetIn() {
        AudioDeviceInfo[] audioDeviceInfo = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo info : audioDeviceInfo) {
            //wired headset(with mic or not) is pluged in
            if ((info.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET)||(info.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES)){
                Log.d(TAG,"Wired headset is exist");
                return true;
            }
        }
        return false;
    }


    /**
     * Power up FM
     */
    private void powerUpFm() {
        refreshImageButton(false);
        refreshActionMenuItem(false);
        refreshPlayButton(false);
        mService.powerUpAsync(mCurrentStationItem.stationFreq);
    }

    /**
     * Power down FM
     */
    private void powerDownFm() {
        refreshImageButton(false);
        refreshActionMenuItem(false);
        refreshPlayButton(false);
        mService.powerDownAsync();
        mCircleSeekBar.setPressedStatus(false);
    }
    private void setSpeakerPhoneOn(boolean isSpeaker) {
        mService.setSpeakerPhoneOn(isSpeaker);
    }

    /**
     * @param station The tune station
     */
    private void tuneStation(final int station) {
        if (null != mService) {
            refreshImageButton(false);
            refreshActionMenuItem(false);
            refreshPlayButton(false);
            mService.tuneStationAsync(station);
        }
    }

    /**
     * Seek station according current frequency and direction
     * @param station The seek start station
     * @param direction The seek direction
     */
    private void seekStation(final int station, boolean direction) {
        // If the seek AsyncTask has been executed and not canceled, cancel it
        // before start new.
        if (null != mService) {
            // disable circle touch event handler.will enabled when tune finished.
            mCircleSeekBar.setEnabled(false);
            mService.seekStationAsync(station, direction);
        }
    }

    private void refreshImageButton(boolean enabled) {
        mButtonPrevStation.setEnabled(enabled);
        mButtonStartRecord.setEnabled(enabled);
        mButtonNextStation.setEnabled(enabled);
        mButtonEnterStationList.setEnabled(enabled);
        mButtonAddToFavorite.setEnabled(enabled);
        mCircleSeekBar.setEnabled(enabled);
    }

    // Refresh action menu except power menu
    private void refreshActionMenuItem(boolean enabled) {
        if (mMenuItemHeadset != null) {
            int powerStatus = mService.getPowerStatus();
	    boolean isPowerdown = (powerStatus == FmService.POWER_DOWN);
            mMenuItemRecordList.setEnabled((!isStartRecording() && mButtonStartRecord.isEnabled()) || isPowerdown);
            mMenuItemHeadset.setEnabled(enabled);
            mMenuItemRegularlyPoweroff.setEnabled(enabled);
            mMenuItemFilePath.setEnabled(enabled && !isStartRecording());
        }
    }

    // Refresh play/stop float button
    private void refreshPlayButton(boolean enabled) {
        boolean isPowerUp = ((mService != null) ? (mService.getPowerStatus() == FmService.POWER_UP)
                : false);
        mButtonPlay.setEnabled(enabled);
        mButtonPlay.setTextColor(getColor(enabled ? R.color.theme_windowBackground : R.color.mainui_circle_disable));
        mButtonPlay.setCompoundDrawablesWithIntrinsicBounds(null,
                getResources().getDrawable((isPowerUp
                                   ? R.drawable.btn_fm_stop_selector
                                   : R.drawable.btn_fm_start_selector), null), null, null);
        if(mService != null && !mService.mIsStartTimeCount) {
            mButtonPlay.setText(getString(isPowerUp ? R.string.power_button_power_off : R.string.power_button_power_on));
        }
        mButtonPlay.setContentDescription((isPowerUp ? getString(R.string.fm_stop) : getString(R.string.fm_play)));
        refreshMainUiForPowerStatus(isPowerUp);
    }

    // update main ui back color for powerdown & powerup.
    private void refreshMainUiForPowerStatus(boolean isPowerUp){
        mMainUiUpView.setBackgroundColor(getColor(isPowerUp ? R.color.theme_windowBackground : R.color.mainui_upview_disable));
        ColorDrawable drawable = new ColorDrawable(getColor(isPowerUp ? R.color.theme_windowBackground : R.color.mainui_upview_disable));
        getActionBar().setBackgroundDrawable(drawable);
        getWindow().setStatusBarColor(getColor(isPowerUp ? R.color.theme_windowBackground : R.color.mainui_upview_disable));
        mTextStationValue.setTextColor(getColor(isPowerUp ? R.color.mainui_station_info : R.color.mainui_station_info_disable));
        mFreqUnit.setTextColor(getColor(isPowerUp ? R.color.mainui_station_info : R.color.mainui_station_info_disable));
    }

    /**
     * Called when back pressed
     */
    @Override
    public void onBackPressed() {
        mIsBackPressed = true;
        super.onBackPressed();
    }

    private void showToast(CharSequence text) {
        Toast.makeText(mContext, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d(TAG, "onSaveInstanceState");
    }

    private boolean bindService() {
        return bindService(new Intent(FmMainActivity.this, FmService.class),
                    mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void unBindService() {
        Log.d(TAG,"mIsServiceBinded :"+mIsServiceBinded);
        if (mIsServiceBinded) {
            unbindService(mServiceConnection);
            mIsServiceBinded = false;
        }
    }

    /**
     * Update menu status, and animation
     */
    private void updateMenuStatus() {
        int powerStatus = mService.getPowerStatus();
        boolean isPowerUp = (powerStatus == FmService.POWER_UP);
        boolean isDuringPowerup = (powerStatus == FmService.DURING_POWER_UP);
        boolean isSeeking = mService.isSeeking();
        boolean isScanning  =  mService.isScanning();
        boolean fmStatus = (isSeeking || isDuringPowerup || isScanning);
        // when seeking, all button should disabled,else should update as origin status
        boolean fresh = fmStatus ? false : (isPowerUp && !mService.isMuted());
        refreshImageButton(fresh);
        refreshActionMenuItem(fresh);
        refreshPlayButton(!fmStatus);
    }

    private void initUiComponent() {
        mSearchTips =(TextView) findViewById(R.id.mainui_search_tips);
        mFreqUnit =(TextView) findViewById(R.id.freq_mhz);
        mTextStationName = (TextView) findViewById(R.id.station_name);
        mTextRdsPs = (TextView) findViewById(R.id.station_rds_ps);
        mTextRdsRt = (TextView) findViewById(R.id.station_rds_rt);
        mTextStationValue = (TextView)findViewById(R.id.tv_perencet_set_perencet);
        mCircleSeekBar = (FmCircleSeekBar)findViewById(R.id.m_circleSeekBar);
        mMainUiUpView = (LinearLayout) findViewById(R.id.up_view);

        // for circle ui  adapter.
        mMainCircle = (FrameLayout) findViewById(R.id.main_circle);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mMainCircle.getLayoutParams();
        params.width = (int)Math.round(mDisplayMetrics.widthPixels * 0.7);
        params.height = (int)Math.round(mDisplayMetrics.widthPixels * 0.7);
        mMainCircle.setLayoutParams(params);

        mButtonAddToFavorite = (TextView) findViewById(R.id.button_add_to_favorite);
        mButtonEnterStationList = (TextView) findViewById(R.id.button_enter_to_stationlist);
        mButtonStartRecord = (TextView) findViewById(R.id.button_record);
        mButtonPrevStation = (TextView) findViewById(R.id.button_prevstation);
        mButtonNextStation = (TextView) findViewById(R.id.button_nextstation);
        mButtonPlay = (TextView) findViewById(R.id.play_button);
    }

    private void registerButtonClickListener() {
        mButtonAddToFavorite.setOnClickListener(mButtonClickListener);
        mButtonPrevStation.setOnClickListener(mButtonClickListener);
        mButtonNextStation.setOnClickListener(mButtonClickListener);
        mButtonPlay.setOnClickListener(mButtonClickListener);
        mButtonEnterStationList.setOnClickListener(mButtonClickListener);
        mButtonStartRecord.setOnClickListener(mButtonClickListener);
    }

    /**
     * add for timer count check
     */
    class RadioGroupListener implements OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(RadioGroup group, int checkedId) {
            Log.d(TAG, "onCheckedChanged checkId:" + checkedId);
            switch (checkedId) {
                case R.id.time_15min:
                    FmUtils.GLOBAL_CUSTOM_TIME = 15;
                    break;
                case R.id.time_30min:
                    FmUtils.GLOBAL_CUSTOM_TIME = 30;
                    break;
                case R.id.time_60min:
                    FmUtils.GLOBAL_CUSTOM_TIME = 60;
                    break;
                case R.id.time_90min:
                    FmUtils.GLOBAL_CUSTOM_TIME = 90;
                    break;
                default:
                    break;
            }
        }
    }

    class MyTimeCountListener implements TimeCountListener {
        @Override
        public void onTimerTick(long millisUntilFinished) {
            String str = FmUtils.makeTimeString((int)millisUntilFinished);
            mButtonPlay.setText(str);
        }
        @Override
        public void onTimerFinish() {
          //TODO: do something here when timer shutdown.
          showToast(getString(R.string.timer_power_down_toast));
        }

        @Override
        public void onUpdateTimeString() {
            if(!mService.mIsStartTimeCount) {
                mButtonPlay.setText(getString( R.string.power_button_power_off));
            }
        }
    }

    /**
     * for entering recording files list
     */
    private void enterRecordList() {
        if (mService != null) {
            if (mService.isActivityForeground()) {
                Intent intent = new Intent();
                intent.setClass(FmMainActivity.this, FmRecordListActivity.class);
                startActivity(intent);
            }
        }
    }
    // for external storage access permission
    private void requestScopedDirectoryAccess(String storagePath) {
        int requestCode = -1;
        if(FmUtils.getCurrentAccessUri(storagePath) != null){
            FmUtils.saveRecordDefaultPath();
            return ;
        }
        StorageManager storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = storageManager.getStorageVolumes();
        if (storagePath.equals(FmUtils.FM_RECORD_STORAGE_PATH_SDCARD)) {
            storagePath = EnvironmentEx.getExternalStoragePath().toString();
            requestCode = FmUtils.SD_REQUEST_CODE;
        }
        Log.i(TAG,"requestScopedDirectoryAccess storagePath: " + storagePath);
        for (StorageVolume volume : volumes) {
            File volumePath = volume.getPathFile();
            Log.i(TAG,"requestScopedDirectoryAccess volumePath: " + volumePath);
            if (!volume.isPrimary() && volumePath != null &&
                    Environment.getExternalStorageState(volumePath).equals(Environment.MEDIA_MOUNTED)
                    && volumePath.toString().contains(storagePath)) {
                Log.i(TAG,"really createAccessIntent for : " + volumePath);
                final Intent intent = volume.createOpenDocumentTreeIntent();
                if (intent != null) {
                    mButtonStartRecord.setEnabled(false);
                    startActivityForResult(intent, requestCode);
                }
            }
        }
    }

    // handle record error
    private void handleRecordError(int errorType) {
        Log.d(TAG, "handleRecordError, errorType = " + errorType);
        String showString = null;
        switch (errorType) {
            case FmRecorder.ERROR_SDCARD_NOT_PRESENT:
                showToast(getString(R.string.toast_storage_device_missing));
                break;

            case FmRecorder.ERROR_SDCARD_INSUFFICIENT_SPACE:
                showToast(getString(R.string.toast_sdcard_insufficient_space));
                break;

            case FmRecorder.ERROR_RECORDER_INTERNAL:
                showToast(getString(R.string.toast_recorder_internal_error));
                break;

            case FmRecorder.ERROR_SDCARD_WRITE_FAILED:
                showToast(getString(R.string.toast_recorder_internal_error));
                break;

            case FmRecorder.ERROR_RECORD_FAILED:
                showToast(getString(R.string.toast_play_fm));
                break;

            case FmRecorder.ERROR_SDCARD_WRITE_PERMISSION:
                showToast(getString(R.string.error_permissions));
                break;

            default:
                Log.w(TAG, "handleRecordError, invalid record error");
                break;
        }
        //should enable record button again
        mButtonStartRecord.setEnabled(true);
    }

    private boolean isStartRecording() {
        if(mService != null){
            return mService.getRecorderState() == FmRecorder.STATE_RECORDING;
        } else{
            return false;
        }
    }

    private boolean isStopRecording() {
        return mService.getRecorderState() == FmRecorder.STATE_IDLE;
    }

    // for runtime permission access permission
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        operatePermissionsResult(requestCode, 0, null, null, grantResults);
    }

    // for external storage permission request.
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult");
        operatePermissionsResult(requestCode, resultCode, data, null, null);
    }
    // for permission request result handle
    public void operatePermissionsResult(int requestCode, int resultCode, Intent data, String permissions[], int[] grantResults){
        switch (requestCode) {
            case FmUtils.FM_PERMISSIONS_REQUEST_CODE:
                mWaitingPermission = false;
                if (mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        && mContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                        && mContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    if (null != mService) {
                        if (mService.mIsAudioFocusHeld) {
                            mButtonStartRecord.setEnabled(true);
                            startButtonClicked();
                        } else {
                            if (mService.isMuted()) {
                                handleRecordError(FmRecorder.ERROR_RECORD_FAILED);
                            }
                        }
                    }
                } else {
                    FmUtils.showErrorPermissionDialog(getResources().getString(R.string.record_error),
                            getResources().getString(R.string.error_permissions),
                            getResources().getString(R.string.dialog_dismiss),
                            FmMainActivity.this);
                }
                break;
            case FmUtils.SD_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK) {
                    Uri uri = null;
                    if (data != null) {
                        uri = data.getData();
                        if (uri != null) {
                            String documentId = DocumentsContract.getTreeDocumentId(uri);
                            if (!documentId.endsWith(":") || "primary:".equals(documentId) ) {
                                FmUtils.showPermissionFailDialog(FmMainActivity.this,
                                        R.string.error_external_storage_access, R.string.superuser_request_confirm);
                                return;
                            }
                            final ContentResolver resolver = getContentResolver();
                            final int modeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            resolver.takePersistableUriPermission(uri, modeFlags);
                            FmUtils.saveRecordDefaultPath();
                            //button set enable status if external storage is granted
                            mButtonStartRecord.setEnabled(true);
                        }
                    }
                } else {
                    FmUtils.showPermissionFailDialog(FmMainActivity.this, R.string.error_external_storage_access, R.string.feedback_description_external_access);
                    FmUtils.restoreRecordDefaultPath();
                    mButtonStartRecord.setEnabled(true);
                }
                break;
            default:
                break;
        }

    }
    // runtime permission.
    public boolean checkBuildRecordingPermission() {
        if (mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                &&
                mContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                &&
                mContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
	 if ((SystemClock.elapsedRealtime() -lasttime_check_permission) <= 10000 && mWaitingPermission) 
	 	return false;
	 
        int numPermissionsToRequest = 0;
        boolean requestMicrophonePermission = false;
        boolean requestStorageWritePermission = false;
        boolean requestStorageReadPermission = false;

        if (mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission = true;
            numPermissionsToRequest++;
        }

        if (mContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestStorageWritePermission = true;
            numPermissionsToRequest++;
        }

        if (mContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestStorageReadPermission = true;
            numPermissionsToRequest++;
        }

        String[] permissionsToRequest = new String[numPermissionsToRequest];
        int permissionsRequestIndex = 0;
        if (requestMicrophonePermission) {
            permissionsToRequest[permissionsRequestIndex] = Manifest.permission.RECORD_AUDIO;
            permissionsRequestIndex++;
        }
        if (requestStorageWritePermission) {
            permissionsToRequest[permissionsRequestIndex] = Manifest.permission.WRITE_EXTERNAL_STORAGE;
            permissionsRequestIndex++;
        }
        if (requestStorageReadPermission) {
            permissionsToRequest[permissionsRequestIndex] = Manifest.permission.READ_EXTERNAL_STORAGE;
            permissionsRequestIndex++;
        }
	 lasttime_check_permission = SystemClock.elapsedRealtime();
         mWaitingPermission = true;
        Log.d(TAG, "requestPermissions for " + FmUtils.FM_PERMISSIONS_REQUEST_CODE);
        this.requestPermissions(permissionsToRequest,
                FmUtils.FM_PERMISSIONS_REQUEST_CODE);
        return false;
    }

}
