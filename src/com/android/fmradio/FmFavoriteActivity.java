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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.AsyncTask;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.Spanned;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.app.ProgressDialog;
import android.widget.RelativeLayout;
import android.content.res.Resources;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

import com.android.fmradio.FmService.OnExitListener;
import com.android.fmradio.FmStation.Station;
import com.android.fmradio.views.FmVisualizerView;
import com.android.fmradio.utils.FmStationItem;

/**
 * This class interact with user, provider edit station information, such as add to favorite, edit
 * favorite, delete from favorite
 */
public class FmFavoriteActivity extends Activity {
    // Logging
    private static final String TAG = "FmFavoriteActivity";

    private ListView mLvFavorites = null; // list view
    private LinearLayout mSearchTips = null;
    private View emptyView = null;
    private Context mContext = null; // application context
    private OnExitListener mExitListener = null;
    private MyFavoriteAdapter mMyAdapter;

    private final int ACTION_QUERY_TYPE = 0;
    private final int ACTION_OPERATE_TYPE = 1;
    private final int ACTION_ADD_FAVORITE_TYPE = 2;
    private final int ACTION_DELETE_TYPE = 3;
    private final int ACTION_RENAME_TYPE = 4;
    private final int ACTION_REMOVE_FROM_FAVORITE_TYPE = 5;

    private String mStrName = null;
    private MenuItem mMenuItemSearch = null;
    private boolean mIsActivityForeground = true;
    private FmService mService = null;
    private boolean mIsServiceBinded = false;

    private List<FmStationItem> mStationList = new ArrayList<FmStationItem>();
    private int mFavoriteStationCount = 0;
    private PopupWindow popupWindow = null;
    private int mEditStationFreq = 0;
    private String mEditStationName = "";

    private int INPUT_MAX_LENGTH = 12;

    private static final int MSGID_QUERY_FMFAVORITE = 104;
    private static final int MSGID_UPDATEUI_DELAY = 300;
    private static final int MSGID_RENAME_COMPLETE = 1000;
    private static final int MSGID_RENAME_ERROR = 1001;

    private CurrentStationItem mCurrentStationItem;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().requestFeature(Window.FEATURE_ACTION_BAR);
        setContentView(R.layout.favorite);
        mContext = getApplicationContext();

        // display action bar and navigation button
        ActionBar actionBar = getActionBar();
        actionBar.setTitle(getString(R.string.station_title));
        actionBar.setDisplayHomeAsUpEnabled(true);

        mSearchTips = (LinearLayout) findViewById(R.id.search_tips);
        mCurrentStationItem = CurrentStationItem.getInstance();
        queryFmFavoriteStation();
        mContext.getContentResolver().registerContentObserver(
                Station.CONTENT_URI, false, mContentObserver);

        mMyAdapter = new MyFavoriteAdapter(mContext);
        emptyView = (View) findViewById(R.id.empty_tips);
        mLvFavorites = (ListView) findViewById(R.id.station_list);
        mLvFavorites.setAdapter(mMyAdapter); // set adapter
        mLvFavorites.setOnItemLongClickListener(new StationListOnItemLongClickListener());
        mLvFavorites.setEmptyView(emptyView);
        mLvFavorites.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            /**
             * @param parent adapter view
             * @param view item view
             * @param position current position
             * @param id current id
             */
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                TextView textView = (TextView) view.findViewById(R.id.lv_station_freq);
                if(textView != null && mService != null) {
                    int frequency = (Integer) textView.getTag();
                    if (frequency != mCurrentStationItem.stationFreq) {
                        mService.tuneStationAsync(frequency);
                    }
                }
            }
        });

        // Finish favorite when exit FM
        mExitListener = new FmService.OnExitListener() {
            @Override
            public void onExit() {
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        FmFavoriteActivity.this.finish();
                    }
                });
            }
        };
        FmService.registerExitListener(mExitListener);
        if (!mIsServiceBinded) {
            bindService();
        }
    }

    /**
     * When menu is selected
     * @param item The selected menu item
     * @return true to consume it, false to can handle other
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            // toolbar back clicked.
            case android.R.id.home:
                onBackPressed();
                break;
            case R.id.fm_station_list_refresh:
                //if ((null != mService) && (!mService.isSeeking())) {
                if ((null != mService) && (!mService.isSeeking())&&(mService.isAntennaAvailable()||FmUtils.supportShortAntenna)) {
                    refreshSearchMenuItem(false);
                    onOptionsItemSelectedScan();
                }
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * To avoid IllegalStateException in monkey test.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!this.isResumed()) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * On back pressed.
     */
    @Override
    public void onBackPressed() {
        if ((mService != null) && mService.isScanning()) {
            Log.d(TAG, "onBackPressed, isScanning, just return.");
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.fm_station_list_menu, menu);
        mMenuItemSearch = menu.findItem(R.id.fm_station_list_refresh);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (null != mService) {
            boolean isScan = mService.isScanning();
            boolean isSeek = mService.isSeeking();
            boolean isRecording = mService.getRecorderState() == FmRecorder.STATE_RECORDING;
            refreshSearchMenuItem(!isRecording && (!isScan)&&(!isSeek));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    /*
    * TODO: maybe move the judgement here,and just call it without parameter.
    * */
    private void refreshSearchMenuItem(boolean enable){
        mMenuItemSearch.setEnabled(enable);
    }

    static final class ViewHolder {
        RelativeLayout mListItemLayout;
        TextView mIndex;
        ImageView mStationTypeView;
        TextView mStationFreqView;
        TextView mStationNameView;
        FmVisualizerView mPlayIndicator;
    }

    static final class TipsViewHolder {
        TextView mFavoriteTag;
    }

    private String sortOrder = "IS_FAVORITE DESC , FREQUENCY";

    private Cursor getData() {
        Cursor cursor = null;
        try{
            cursor = mContext.getContentResolver().query(Station.CONTENT_URI,
                    FmStation.COLUMNS, "IS_SHOW=?", new String[]{"1"}, sortOrder);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if(cursor != null) Log.d(TAG, "getData cursor.count:" + cursor.getCount());
        return cursor;
    }

    private void operateData(int stationFreq, int type) {
        switch (type) {
            case ACTION_ADD_FAVORITE_TYPE:
                FmStation.addToFavorite(mContext, stationFreq);
                break;
            case ACTION_REMOVE_FROM_FAVORITE_TYPE:
                FmStation.removeFromFavorite(mContext, stationFreq);
                break;
            case ACTION_RENAME_TYPE:
                int updateResult = 0;
                if (mStrName != null) {
                    updateResult = FmStation.updateStationToDb(mContext, stationFreq, mStrName);
                    mStrName = null;
                }
                if (updateResult != 0) {
                    mHandler.sendEmptyMessage(MSGID_RENAME_COMPLETE);
                }else {
                    Log.d(TAG, "operateData, ACTION_RENAME_TYPE, updateResult == 0");
                    mHandler.sendEmptyMessage(MSGID_RENAME_ERROR);
                }
                break;
            case ACTION_DELETE_TYPE:
                FmStation.deleteStationInDb(mContext, stationFreq);
                break;
        }
    }

    private void queryFmFavoriteStation() {
        MyAsyncTask queryTask = new MyAsyncTask();
        queryTask.execute(ACTION_QUERY_TYPE, 0, 0);
    }

    private void operateFmFavoriteStation(int stationFreq, int type) {
        MyAsyncTask operateTask = new MyAsyncTask();
        operateTask.execute(ACTION_OPERATE_TYPE, stationFreq, type);
    }

    class MyAsyncTask extends AsyncTask<Integer, Void, Cursor> {
        @Override
        protected Cursor doInBackground(Integer... params) {
            Log.d(TAG, "operate database type:" + params[0]);
            if (params[0] == ACTION_QUERY_TYPE) {
                Cursor cursor = getData();
                return cursor;
            } else {
                Log.d(TAG, "stationFreq=" + params[1]);
                operateData(params[1], params[2]);
                return null;
            }
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            refreshFmFavorityList(cursor);
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    class MyFavoriteAdapter extends BaseAdapter {
        private LayoutInflater mInflater;
        private static final int TYPE_ITEM = 0;// item value
        private static final int TYPE_TIPS_FAVORITE = 1;// show tips
        private static final int TYPE_TIPS_OTHER = 2;//item type count
        private static final int TYPE_COUNT = 3;//item type count
        private int stationCountInList = 0; // for calculating list view count to show
        private int positionInList = -1; // for getView map position to right index value of mStationList

        public MyFavoriteAdapter(Context context) {
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            stationCountInList =  mStationList.size();
            if(stationCountInList == 0){
                return 0;
            }else{
                if(mFavoriteStationCount > 0 && mFavoriteStationCount < stationCountInList){
                    // 2 tips
                    return stationCountInList + 2;
                }else {
                    // 1 tips
                    return stationCountInList + 1;
                }
            }
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }
        @Override
        public int getItemViewType(int position) {
            // 3 cases:
            if (mFavoriteStationCount == 0) {
                // all other
                if(position == 0) {
                    return TYPE_TIPS_OTHER;
                }else {
                    positionInList = position-1;
                    return TYPE_ITEM;
                }
            } else if(mFavoriteStationCount == stationCountInList){
                //all favorite
                if (position == 0) {
                    return TYPE_TIPS_FAVORITE;
                }else{
                    positionInList = position-1;
                    return TYPE_ITEM;
                }
            }else{
                // favorite + others
                if(position == 0){
                    return TYPE_TIPS_FAVORITE;
                }else if(position == mFavoriteStationCount+1){
                    return TYPE_TIPS_OTHER;
                }else{
                    if(position < mFavoriteStationCount+1) {
                        // favorite list
                        positionInList = position - 1;
                    }else{
                        // other list
                        positionInList = position - 2;
                    }
                    return TYPE_ITEM;
                }
            }
        }

        @Override
        public int getViewTypeCount() {
            return TYPE_COUNT;
        }
        // for tip not clickable
        @Override
        public boolean isEnabled(int position) {
            // item type, clickable.
            if(getItemViewType(position) == 0) {
                return true;
            }
            return false;
        }

        // add for height adjust dynamic adjustment.
        private int dip2px(Context context,float dipValue)
        {
            Resources r = context.getResources();
            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, dipValue, r.getDisplayMetrics());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder viewHolder = null;
            TipsViewHolder tipsViewHolder = null;
            switch (getItemViewType(position)) {
                case TYPE_ITEM:
                    if (null == convertView) {
                        viewHolder = new ViewHolder();
                        convertView = mInflater.inflate(R.layout.station_item, null);
                        viewHolder.mListItemLayout = (RelativeLayout)convertView
                                .findViewById(R.id.list_item);
                        viewHolder.mIndex = (TextView) convertView
                                .findViewById(R.id.list_item_index);
                        viewHolder.mStationTypeView = (ImageView) convertView
                                .findViewById(R.id.lv_station_favorite);
                        viewHolder.mStationFreqView = (TextView) convertView
                                .findViewById(R.id.lv_station_freq);
                        viewHolder.mStationNameView = (TextView) convertView
                                .findViewById(R.id.lv_station_name);
                        viewHolder.mPlayIndicator = (FmVisualizerView) convertView
                                .findViewById(R.id.fm_play_indicator);

                        convertView.setTag(viewHolder);
                    } else {
                        viewHolder = (ViewHolder) convertView.getTag();
                    }

                    final FmStationItem item;
                    item = mStationList.get(positionInList);
                    viewHolder.mIndex.setText(positionInList + 1 + ".");

                    if (item.stationFreq == mCurrentStationItem.stationFreq) {
                        viewHolder.mPlayIndicator.setVisibility(View.VISIBLE);
                        if (mService != null && mService.getPowerStatus() == FmService.POWER_UP && !mService.isMuted()) {
                            viewHolder.mPlayIndicator.startAnimation();
                        } else {
                            viewHolder.mPlayIndicator.stopAnimation();
                        }
                    } else {
                        viewHolder.mPlayIndicator.stopAnimation();
                        viewHolder.mPlayIndicator.setVisibility(View.INVISIBLE);
                    }
                    // add for item height dynamic adjustment
                    ViewGroup.LayoutParams params=(viewHolder.mListItemLayout).getLayoutParams();
                    params.height =dip2px(FmFavoriteActivity.this , TextUtils.isEmpty(item.stationName) ? 48 : 72);
                    (viewHolder.mListItemLayout).setLayoutParams(params);

                    if(TextUtils.isEmpty(item.stationName)){
                        viewHolder.mStationNameView.setText(item.stationName);
                        viewHolder.mStationFreqView.setText(FmUtils.formatStation(item.stationFreq) + "MHz");
                    }else {
                        viewHolder.mStationNameView.setText(FmUtils.formatStation(item.stationFreq) + "MHz");
                        viewHolder.mStationFreqView.setText(item.stationName);
                    }
                    viewHolder.mStationNameView.setVisibility(TextUtils.isEmpty(item.stationName) ? View.GONE : View.VISIBLE);
                    viewHolder.mStationNameView.setTag(item.stationName);
                    viewHolder.mStationFreqView.setTag(item.stationFreq);
                    viewHolder.mStationTypeView.setEnabled(true);
                    if (0 == item.isFavorite) {
                        viewHolder.mStationTypeView.setAlpha(0.54f);
                        viewHolder.mStationTypeView.setImageResource(R.drawable.stationlist_fav_off);
                        viewHolder.mStationTypeView.setColorFilter(Color.BLACK,
                                PorterDuff.Mode.SRC_ATOP);
                    } else {
                        viewHolder.mStationTypeView.setAlpha(1.0f);
                        viewHolder.mStationTypeView.setImageResource(R.drawable.stationlist_fav_on);
                        viewHolder.mStationTypeView.setColorFilter(null);
                    }

                    viewHolder.mStationTypeView.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (0 == item.isFavorite) {
                                addFavorite(item.stationFreq);
                            } else {
                                deleteFromFavorite(item.stationFreq);
                            }
                        }
                    });
                    break;
                case TYPE_TIPS_FAVORITE:
                    tipsViewHolder = new TipsViewHolder();
                    if (null == convertView) {
                        convertView = mInflater.inflate(R.layout.station_list_tips, null);
                        tipsViewHolder.mFavoriteTag = (TextView) convertView.findViewById(R.id.tv_Favorite_Tag);
                        convertView.setTag(tipsViewHolder);
                    }else{
                        tipsViewHolder = (TipsViewHolder) convertView.getTag();
                    }
                    tipsViewHolder.mFavoriteTag.setVisibility(View.VISIBLE);
                    tipsViewHolder.mFavoriteTag.setText(getString(R.string.favorite_station_tag));
                    break;
                case TYPE_TIPS_OTHER:
                    tipsViewHolder = new TipsViewHolder();
                    if (null == convertView) {
                        convertView = mInflater.inflate(R.layout.station_list_tips, null);
                        tipsViewHolder.mFavoriteTag = (TextView) convertView.findViewById(R.id.tv_Favorite_Tag);
                        convertView.setTag(tipsViewHolder);
                    }else{
                        tipsViewHolder = (TipsViewHolder) convertView.getTag();
                    }
                    tipsViewHolder.mFavoriteTag.setVisibility(View.VISIBLE);
                    tipsViewHolder.mFavoriteTag.setText(getString(R.string.other_station_tag));
                    break;
                default:
                    break;
            }
            return convertView;
        }
    }

    /**
     * Add searched station as favorite station
     */
    public void addFavorite(int stationFreq) {
        // update the station name and station type in database according the frequency
        operateFmFavoriteStation(stationFreq, ACTION_ADD_FAVORITE_TYPE);
    }

    /**
     * Delete favorite from favorite station list, make it as searched station
     */
    private void deleteFromFavorite(int stationFreq) {
        // update the station type from favorite to searched.
        operateFmFavoriteStation(stationFreq, ACTION_REMOVE_FROM_FAVORITE_TYPE);
    }

    private void deleteStation(int stationFreq) {
        operateFmFavoriteStation(stationFreq, ACTION_DELETE_TYPE);
    }


    private void renameFavorite(int stationFreq) {
        operateFmFavoriteStation(stationFreq, ACTION_RENAME_TYPE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsActivityForeground = true;
        if (null != mService) {
            mService.setFmActivityForground(mIsActivityForeground);
        }
    }

    @Override
    protected void onPause() {
        mIsActivityForeground = false;
        if (null != mService) {
            mService.setFmActivityForground(mIsActivityForeground);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestory");
        FmService.unregisterExitListener(mExitListener);
        if (mService != null) {
            mService.unregisterFmRadioListener(mFmRadioListener);
        }
        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
        unbindService();
        super.onDestroy();
    }

    private void bindService() {
        mIsServiceBinded = bindService(new Intent(FmFavoriteActivity.this, FmService.class),
                mServiceConnection, Context.BIND_AUTO_CREATE);
        if (!mIsServiceBinded) {
            Log.e(TAG, "bindService, mIsServiceBinded is false");
            finish();
        }
    }

    private void unbindService() {
        if (mIsServiceBinded) {
            unbindService(mServiceConnection);
        }
    }

    // Service listener
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

    /**
     * Main thread handler to update UI
     */
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG,"handleMessage, what = " + msg.what + ",hashcode:" + mHandler.hashCode());
            Bundle bundle;
            switch (msg.what) {
                case MSGID_QUERY_FMFAVORITE:
                    queryFmFavoriteStation();
                    break;
                case FmListener.MSGID_SCAN_FINISHED:
                    bundle = msg.getData();
                    // cancel scan happen
                    boolean isScan = bundle.getBoolean(FmListener.KEY_IS_SCAN);
                    int searchedNum = bundle.getInt(FmListener.KEY_STATION_NUM);
                    showTipView(false);
                    refreshSearchMenuItem(true);
                    if (searchedNum == 0) {
                        Toast.makeText(mContext, getString(R.string.toast_cannot_search),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    break;
                case FmListener.MSGID_SWITCH_ANTENNA:
                    if (!FmUtils.supportShortAntenna) {
                      bundle = msg.getData();
                      boolean isHeadset = bundle.getBoolean(FmListener.KEY_IS_SWITCH_ANTENNA);
                      // if receive headset plugout, need set headset mode on ui
                      if (!isHeadset) {
                        finish();
                      }
                    }
                    break;
                case FmListener.LISTEN_RECORDSTATE_CHANGED:
                    invalidateOptionsMenu();
                    break;
                case FmListener.MSGID_POWERDOWN_FINISHED:
                    finish();
                    break;
                case FmListener.MSGID_TUNE_FINISHED:
                    mMyAdapter.notifyDataSetChanged();
		    boolean inScan = mService.isScanning();
                    if (!inScan) {
                       showTipView(false);
                    }
                    invalidateOptionsMenu();
                    break;
                case FmListener.MSGID_SET_UI_DISABLE:
                    refreshSearchMenuItem(false);
                    break;
                case MSGID_RENAME_ERROR:
                    //TODO
                    break;
                default:
                    break;
            }
        }
    };

    private final ContentObserver mContentObserver = new ContentObserver(mHandler) {
        public void onChange(boolean selfChange) {
            Log.d(TAG, "mContentObserver onChange");
            if (mHandler.hasMessages(MSGID_QUERY_FMFAVORITE)) {
                mHandler.removeMessages(MSGID_QUERY_FMFAVORITE);
            }
            mHandler.sendEmptyMessageDelayed(MSGID_QUERY_FMFAVORITE, MSGID_UPDATEUI_DELAY);
        }
    };

    // When call bind service, it will call service connect. register call back
    // listener and initial device
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
            if (mService.getPowerStatus() == FmService.POWER_DOWN) {
                Log.d(TAG,"onServiceConnected but power status is POWER_DOWN");
                finish();
                return;
            }
            mService.registerFmRadioListener(mFmRadioListener);
            mService.setFmActivityForground(mIsActivityForeground);

            boolean isScan = mService.isScanning();
            if (isScan) {
                showTipView(true);
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
     * show scan tips
     * @param true:show tip ,false:hide
     **/
    private void showTipView(boolean isShow) {
        mSearchTips.setVisibility(isShow ? View.VISIBLE : View.GONE);
    }

    private void onOptionsItemSelectedScan() {
        if (null != mService) {
            showTipView(true);
            mService.startScanAsync();
        }
    }

    private void refreshFmFavorityList(Cursor cursor) {
        if (cursor == null) {
            return;
        }
        mStationList.clear();
        mFavoriteStationCount = 0;
        for (int i = 0; i < cursor.getCount(); i++) {
            FmStationItem item = new FmStationItem();
            if (cursor.moveToPosition(i)) {
                item.isFavorite = cursor.getInt(cursor.getColumnIndex(FmStation.Station.IS_FAVORITE));
                item.stationFreq = cursor.getInt(cursor.getColumnIndex(FmStation.Station.FREQUENCY));
                item.stationName = cursor.getString(cursor.getColumnIndex(FmStation.Station.STATION_NAME));
                item.isShow = cursor.getInt(cursor.getColumnIndex(FmStation.Station.IS_SHOW));
                if (item.isFavorite == 1) {
                    mFavoriteStationCount++;
                }
                mStationList.add(item);
            }
        }
        Log.d(TAG,"refreshFmFavorityList, mStationList count:" + mStationList.size() + ", favoriteStation count:" + mFavoriteStationCount);
        mMyAdapter.notifyDataSetChanged();
    }

    private class StationListOnItemLongClickListener implements OnItemLongClickListener {
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
            TextView textView = (TextView) view.findViewById(R.id.lv_station_freq);
            if (textView == null ) {
             Log.d(TAG,"onItemLongClick textView of lv_station_freq is null");
             return false; }
            mEditStationFreq = (Integer) textView.getTag();
            TextView textName = (TextView) view.findViewById(R.id.lv_station_name);
            mEditStationName = (String) textName.getTag();
            showPopupWindow(view);
            return true;
        }
    }

    private void showPopupWindow(View parent) {
        View contentView = LayoutInflater.from(mContext).inflate(R.layout.station_edit_popupwindow, null);
        // rename station that selected.
        TextView tv_edit = (TextView) contentView.findViewById(R.id.tv_edit);
        tv_edit.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (popupWindow != null) {
                    popupWindow.dismiss();
                    showEditDialog();
                }
            }
        });
        // delete station that selected.
        TextView tv_delete = (TextView) contentView.findViewById(R.id.tv_delete);
        tv_delete.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (popupWindow != null) {
                    popupWindow.dismiss();
                    showDeleteDialog(v);
                }
            }
        });
        popupWindow = new PopupWindow(contentView, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true);
        popupWindow.setTouchable(true);
        popupWindow.setTouchInterceptor(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return false;
            }
        });
        int[] location = new int[2];
        parent.getLocationOnScreen(location);
        int offsetX = mLvFavorites.getWidth()/3 + (int)getResources().getDimension(R.dimen.fm_play_indicator_width);
        int offsetY = parent.getHeight()/3;

        popupWindow.showAtLocation(mLvFavorites, Gravity.TOP | Gravity.LEFT, location[0] + offsetX,
                location[1] + offsetY);
    }

    private void showDeleteDialog(View view){
        int frequency = mEditStationFreq;

        new AlertDialog.Builder(FmFavoriteActivity.this)
                .setMessage(getString(R.string.station_delete))
                .setPositiveButton(R.string.button_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteStation(frequency);
                    }
                })
                .setNegativeButton(R.string.button_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                    }
                })
                .create().show();
    }

    private void showEditDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.station_edit_dialog, null);
        final EditText et_name = (EditText) view.findViewById(R.id.edit_name);
        int stationFreq = mEditStationFreq;
        String stationName = mEditStationName;
        if (TextUtils.isEmpty(stationName)) {
            stationName = FmUtils.formatStation(stationFreq);
        }
        et_name.setText(stationName);
        et_name.setFocusable(true);
        et_name.setFocusableInTouchMode(true);
        et_name.requestFocus();
        et_name.setFilters(new InputFilter[]{new MaxTextLengthFilter(INPUT_MAX_LENGTH)});

        AlertDialog mEditDialog = new AlertDialog.Builder(this)
                .setPositiveButton(R.string.button_ok, null)
                .setNegativeButton(R.string.button_cancel, null)
                .setView(view).create();

        mEditDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                Button positionButton = mEditDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                Button negativeButton = mEditDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                positionButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String strName = et_name.getText().toString().trim();
                        if (strName.length() == 0) {
                            showToast(getString(R.string.station_name_notnull));
                            et_name.setText("");
                            // dialog do not dismiss
                            return;
                        }
                        if (strName.matches(".*[/\\\\:*?\"<>|\t].*")) {
                            showToast(getString(R.string.recording_name_illegal));
                            return;
                        }
                        if (!FmUtils.isStationNameExist(strName)) {
                             mStrName = strName;
                             renameFavorite(stationFreq);
                             mEditDialog.dismiss();
                         } else {
                             showToast(getString(R.string.station_name_exists));
                             //dialog do not dismiss.
                         }
                    }
                });
                negativeButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mEditDialog.dismiss();
                    }
                });
            }
        });

        mEditDialog.setCanceledOnTouchOutside(false);
        mEditDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        mEditDialog.show();
    }

    class MaxTextLengthFilter implements InputFilter {
        private int mMax;

        public MaxTextLengthFilter(int max){
            mMax = max;
        }

          public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
                  int dstart, int dend) {
              int keep = mMax - (dest.length() - (dend - dstart));
              if(keep < (end - start)){
                  showToast(getString(R.string.input_length_overstep));
              }
              if (keep <= 0) {
                  return "";
              } else if (keep >= end - start) {
                  return null; // keep original
              } else {
                  keep += start;
                  if (Character.isHighSurrogate(source.charAt(keep - 1))) {
                      --keep;
                      if (keep == start) {
                          return "";
                      }
                  }
                  return source.subSequence(start, keep);
              }
          }
    }

    /*
     *This method for toast show
     *@param text, what to show by toast
     */
    private void showToast(String text) {
        FmUtils.showToast(mContext, text);
    }
}
