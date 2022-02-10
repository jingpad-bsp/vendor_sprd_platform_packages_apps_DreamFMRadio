
package com.android.fmradio;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.hardware.radio.ProgramList;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.RadioTuner;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class FmRadioManager {

    private static final String TAG = "FmRadioManager";
    private RadioManager.FmBandDescriptor mFmDescriptor;
    private final List<RadioManager.ModuleProperties> mModules = new ArrayList<>();
    private RadioTuner mRadioTuner;
    private RadioManager mRadioManager;
    private RadioTuner.Callback mInternalRadioTunerCallback = new InternalRadioCallback();
    private ProgramList mProgramList;
    private ProgramList.OnCompleteListener mProgramListCompleteListener = new AutoScanOnCompleteListener();
    private Context mContext;
    private AudioManager mAudioManager;
    private static final String RDS_KEY = "sprdsetrds";
    private static final String RDS_ON = "sprdrdson";
    private static final String RDS_OFF = "sprdrdsoff";

    public FmRadioManager(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mRadioManager = (RadioManager) mContext.getSystemService(Context.RADIO_SERVICE);
    }

    private void openTuner() {
        int status = mRadioManager.listModules(mModules);
        if (status != RadioManager.STATUS_OK) {
            Log.w(TAG, "Load modules failed with status: " + status);
            return;
        }
        Log.d(TAG, "initialze(); listModules complete: " + mModules);
        if (mModules.size() == 0) {
            Log.w(TAG, "No radio modules on device.");
            return;
        }

        Log.d(TAG,"activity oncreate. openTuner....");
        mRadioTuner = mRadioManager.openTuner(mModules.get(0).getId(), null, true,
                mInternalRadioTunerCallback, null /* handler */);
        if (mRadioTuner != null && FmUtils.support50ksearch) {
            Map parametersMap = new HashMap();
            parametersMap.put("spacing","50k");
            mRadioTuner.setParameters(parametersMap);
        }

    }

    public boolean openDev() {
        openTuner();
        Log.d(TAG,"openDev() mRadioTuner = "+mRadioTuner);
        return (mRadioTuner != null);
    }

    public boolean closeDev() {
        if (mRadioTuner != null) {
            Log.d(TAG,"closeDev() mRadioTuner.close()");
            mRadioTuner.close();
            mRadioTuner = null;
        }
        return true;
    }

    public boolean powerUp(int frequency) {
        return true;
    }

    public boolean powerDown() {
        return true;
    }

    public boolean stopScan() {
        if (mRadioTuner != null) {
            Log.d(TAG,"broadcastradio stopScan()");
            Map parametersMap = new HashMap();
            parametersMap.put("stopScan","");
            mRadioTuner.setParameters(parametersMap);
            //mProgramList.close();
        }
        return true;
    }

    public int[] autoScan(int start_freq) {
        if(mRadioTuner != null) {
            try {
                mProgramList = mRadioTuner.getDynamicProgramList(null);
                Log.d(TAG,"broadcastradio autoScan result:" +mProgramList.toList());
            } catch (IllegalStateException e) {
                Log.d(TAG,"Can not autoScan because tunersession has closed");
                openTuner();
                mProgramList = mRadioTuner.getDynamicProgramList(null);
            }
            mProgramList.addOnCompleteListener(mProgramListCompleteListener);
        }
        return null;
    }

    public float seekStation(int frequency, boolean isUp) {
        int result = 0;
        if(mRadioTuner != null) {
            result = mRadioTuner.scan(isUp ? RadioTuner.DIRECTION_UP : RadioTuner.DIRECTION_DOWN, true);
            Log.d(TAG,"result = "+result);
            if (result == RadioManager.STATUS_INVALID_OPERATION) {
                openTuner();
                result = mRadioTuner.scan(isUp ? RadioTuner.DIRECTION_UP : RadioTuner.DIRECTION_DOWN, true);
            }
        }
        return (float) result;
    }

    public boolean tuneRadio(int frequency) {
        int result = 0;
        if(mRadioTuner != null) {
            result = mRadioTuner.tune(frequency, 0 /* subChannel */);
            Log.d(TAG, "Tuning to station: " + frequency + " result: " + result);
            if (result == RadioManager.STATUS_INVALID_OPERATION) {
                openTuner();
                result = mRadioTuner.tune(frequency, 0 /* subChannel */);
            }
        }
        return (result == RadioManager.STATUS_OK ? true : false);
    }

    public int switchAntenna(int antenna) {
        if (mRadioTuner != null) {
            Map parametersMap = new HashMap();
            Map resultMap = new HashMap();
            parametersMap.put("antenna",""+antenna);
            resultMap = mRadioTuner.setParameters(parametersMap);
            Log.d("chirs","resultMap = "+resultMap);
            String value  = resultMap.get("antenna").toString();
            return Integer.parseInt(value);
        }
        return 0;
    }

    public boolean setAudioPathEnable(boolean enable) {
        if (enable) {
            mAudioManager.setDeviceConnectionStateForFM(AudioManager.DEVICE_OUT_FM_HEADSET,
                    AudioSystem.DEVICE_STATE_AVAILABLE, "", "");
        } else {
            mAudioManager.setDeviceConnectionStateForFM(AudioManager.DEVICE_OUT_FM_HEADSET,
                    AudioSystem.DEVICE_STATE_UNAVAILABLE, "", "");
        }
        return true;
    }

    public boolean setSpeakerEnable(boolean isSpeaker) {
        mAudioManager.setFmSpeakerOn(isSpeaker);
        return true;
    }

    // add begain for new feature RDS bug-448080
    public int setRdsMode(boolean rdsMode, boolean enableAf) {
        return 0;
    }

    public void setRdsOnOff(boolean enable) {
        if (mRadioTuner != null) {
            Map parametersMap = new HashMap();
            Map resultMap = new HashMap();
            parametersMap.put(RDS_KEY, enable ? RDS_ON : RDS_OFF);
            resultMap = mRadioTuner.setParameters(parametersMap);
            Log.d(TAG,"setRdsOnOff " + enable);
        }
    }

    public int setAfMode(boolean enableAf) {
        return 0;
    }

    public int isRdsSupported() {
        return 1;
    }

    /**
     * A extension of {@link android.hardware.radio.RadioTuner.Callback} that delegates to a
     * callback registered on this service.
     */
    private class InternalRadioCallback extends RadioTuner.Callback {

        @Override
        public void onProgramInfoChanged(RadioManager.ProgramInfo info) {
            Log.d(TAG,"onProgramInfoChanged info = "+info);
            ((FmService) mContext).onProgramInfoChanged(info);
        }

        @Override
        public void onMetadataChanged(RadioMetadata metadata){
            if (metadata != null) {
                String ps = metadata.getString(RadioMetadata.METADATA_KEY_RDS_PS);
                String rt = metadata.getString(RadioMetadata.METADATA_KEY_RDS_RT);
                Log.d(TAG,"onMetadataChanged ps = " + ps + ", rt = " + rt);
                ((FmService) mContext).onMetadataChanged(ps, rt);
            }
        }

        @Override
        public void onConfigurationChanged(RadioManager.BandConfig config){
            Log.d(TAG,"onConfigurationChanged.");
        }

        @Override
        public void onError(int status){
            Log.d(TAG,"onError.status: " + status);
        }

        @Override
        public void onControlChanged(boolean control){
            Log.d(TAG,"onControlChanged.");
        }
    }

    private class AutoScanOnCompleteListener implements ProgramList.OnCompleteListener {

        @Override
        public void onComplete() {
            List<RadioManager.ProgramInfo> list = mProgramList.toList();
            Log.d(TAG,"onComplete mProgramList = "+list);
            ((FmService) mContext).onAutoScanComplete(list);
            mProgramList.close();
            mProgramList.removeOnCompleteListener(mProgramListCompleteListener);
        }
    }
}
