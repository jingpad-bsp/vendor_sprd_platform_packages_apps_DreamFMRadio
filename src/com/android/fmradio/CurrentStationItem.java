package com.android.fmradio;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.database.Cursor;
import android.database.ContentObserver;
import android.util.Log;
import android.preference.PreferenceManager;
import java.util.List;
import java.util.ArrayList;

import com.android.fmradio.FmStation.Station;

public class CurrentStationItem{
    private static final String TAG = "CurrentStationItem";

    private static Context mContext;

    private static final int MSGID_UPDATEUI_DELAY = 200;

    public int stationFreq;
    public String stationName;
    public String rt;
    public String ps; //program_service
    //1:favorite 0:not favorite
    public int isFavorite; //1
    public int isShow; // 1:station is scanned ,0: is not sanned

    public interface FmObserverListener {
        void onCurrentStationChange();
    }


    private static List<FmObserverListener> observerList = new ArrayList<>();


    public void registerObserver(FmObserverListener observerListener) {
        observerList.add(observerListener);
    }


    public void unRegisterObserver(FmObserverListener observerListener) {
        if (observerList.contains(observerListener)) {
            observerList.remove(observerListener);
        }else {
            Log.d(TAG,"unRegisterObserver: the registration you want to cancel is not in observerList!");
        }
    }

    private static void notifyCurrentStationChange() {
        for (FmObserverListener observerListener : observerList) {
            observerListener.onCurrentStationChange();
        }
    }


    private volatile static CurrentStationItem currentStationItem;
    private CurrentStationItem() {}
    public static CurrentStationItem getInstance() {
        if (currentStationItem == null) {
            synchronized (CurrentStationItem.class) {
                if (currentStationItem == null) {
                    currentStationItem = new CurrentStationItem();
                }
            }
        }
        return currentStationItem;
    }

    private void initCurrentStation() {
        currentStationItem.stationFreq = FmStation.getCurrentStation(mContext);
        setCurrent(currentStationItem.stationFreq);
    }

    public void setContext(Context context) {
        mContext = context;
        initCurrentStation();
    }

    public void registerContentObserver() {
        if(mContext != null) {
            mContext.getContentResolver().registerContentObserver(
                    Station.CONTENT_URI, false, mContentObserver);
        }else {
            Log.d(TAG,"registerContentObserver: mContext is null");
        }
    }

    public void unRegisterContentObserver() {
        if(mContext != null) {
            mContext.getContentResolver().unregisterContentObserver(mContentObserver);
        }else {
            Log.d(TAG,"unRegisterContentObserver: mContext is null");
        }
    }

    private final ContentObserver mContentObserver = new ContentObserver(mHandler) {
        public void onChange(boolean selfChange) {
            Log.d(TAG,"ContentObserver onChange");
            if (mHandler.hasMessages(FmListener.MSGID_QUERY_DATABASE)) {
                mHandler.removeMessages(FmListener.MSGID_QUERY_DATABASE);
            }
            mHandler.sendEmptyMessageDelayed(FmListener.MSGID_QUERY_DATABASE,MSGID_UPDATEUI_DELAY);
        };
    };

    private static Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG,
                    "handleMessage, what = " + msg.what + ",hashcode:"
                            + mHandler.hashCode());
            setCurrent(currentStationItem.stationFreq);

        }
    };

    public static void setCurrentStation(Cursor cursor) {
        String stationName = null;
        if (cursor != null && cursor.moveToFirst()) {
            Log.d(TAG,"cusor is not null stationFreq : "+ currentStationItem.stationFreq);
            if(currentStationItem.stationFreq != cursor.getInt(cursor.getColumnIndex(FmStation.Station.FREQUENCY))) {
                Log.d(TAG,"Cusor is not stationFreq : "+ currentStationItem.stationFreq + " ignore");
                return;
            }
            currentStationItem.stationName = cursor.getString(cursor.getColumnIndex(FmStation.Station.STATION_NAME));
            currentStationItem.isFavorite= cursor.getInt(cursor.getColumnIndex(FmStation.Station.IS_FAVORITE));
            currentStationItem.isShow= cursor.getInt(cursor.getColumnIndex(FmStation.Station.IS_SHOW));
            notifyCurrentStationChange();
        } else {
            Log.d(TAG,"cusor is null stationFreq : "+ currentStationItem.stationFreq);
        }
    }

    public static void setCurrent(int freq) {
        Log.d(TAG,"setCurrent freq : "+freq);
        MyAsyncTask queryTask= new MyAsyncTask();
        queryTask.execute(0, freq, 0);
    }

    static class MyAsyncTask extends AsyncTask<Integer, Void, Cursor> {
        @Override
        protected Cursor doInBackground(Integer... params) {
            Log.d(TAG," station frep : "+params[1]);
            Cursor cursor = getData(params[1]);
            if ((cursor == null) || (cursor.getCount() == 0)) {
                Log.d(TAG,"insert stationFreq="+params[1]+" type : "+params[2]);
                FmStation.insertStationToDb(mContext, params[1], "", FmStation.Station.IS_NOT_SCAN);
            } else{
                FmStation.setCurrentStation(mContext, params[1]);
            }
            return cursor;
        }

            @Override
            protected void onPostExecute(Cursor cursor) {
                setCurrentStation(cursor);
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

    private static Cursor getData(int freq) {
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                    Station.CONTENT_URI,
                    null,
                    Station.FREQUENCY + "=?",
                    new String[]{
                            String.valueOf(freq)
                    },
                    null);
        }catch(Exception e){
            e.printStackTrace();
        }
        if (cursor != null) Log.d(TAG, "getData cursor.count:" + cursor.getCount());
        return cursor;
    }

}
