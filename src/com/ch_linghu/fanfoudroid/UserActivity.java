package com.ch_linghu.fanfoudroid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import com.ch_linghu.fanfoudroid.data.Tweet;
import com.ch_linghu.fanfoudroid.data.User;
import com.ch_linghu.fanfoudroid.helper.ImageManager;
import com.ch_linghu.fanfoudroid.helper.MemoryImageCache;
import com.ch_linghu.fanfoudroid.helper.Utils;
import com.ch_linghu.fanfoudroid.ui.base.TwitterListBaseActivity;
import com.ch_linghu.fanfoudroid.ui.base.WithHeaderActivity;
import com.ch_linghu.fanfoudroid.ui.module.MyListView;
import com.ch_linghu.fanfoudroid.ui.module.TweetArrayAdapter;
import com.ch_linghu.fanfoudroid.weibo.Paging;
import com.ch_linghu.fanfoudroid.weibo.WeiboException;
import com.google.android.photostream.UserTask;

public class UserActivity extends TwitterListBaseActivity implements MyListView.OnNeedMoreListener {

  private static final String TAG = "UserActivity";

  // State.
  private String mUsername;
  private String mScreenName;
  private String mMe;
  private ArrayList<Tweet> mTweets;
  private User mUser;
  private Boolean mIsFollowing;
  private Boolean mIsFollower = false;
  private int mNextPage = 1;
  private Bitmap mProfileBitmap;
  private MemoryImageCache mImageCache;

  private static class State {
    State(UserActivity activity) {
      mTweets = activity.mTweets;
      mUser = activity.mUser;
      mIsFollowing = activity.mIsFollowing;
      mIsFollower = activity.mIsFollower;
      mNextPage = activity.mNextPage;
      mProfileBitmap = activity.mProfileBitmap;
  	  mImageCache = activity.mImageCache;
    }

    public ArrayList<Tweet> mTweets;
    public User mUser;
    public boolean mIsFollowing;
    public boolean mIsFollower;
    public int mNextPage;
    public Bitmap mProfileBitmap;
	public MemoryImageCache mImageCache;
  }

  // Views.
  private MyListView mTweetList;
  private TextView mUserText;
  private TextView mNameText;
  private ImageView mProfileImage;
  private Button mFollowButton;

  private TweetArrayAdapter mAdapter;

  // Tasks.
  private UserTask<Void, Void, TaskResult> mRetrieveTask;
  private UserTask<Void, Void, TaskResult> mFriendshipTask;
  private UserTask<Void, Void, TaskResult> mLoadMoreTask;

  private static final String EXTRA_USER = "user";
  private static final String EXTRA_NAME_SCREEN = "name";

  private static final String LAUNCH_ACTION = "com.ch_linghu.fanfoudroid.USER";

  public static Intent createIntent(String user, String name) {
    Intent intent = new Intent(LAUNCH_ACTION);
    intent.putExtra(EXTRA_USER, user);
    intent.putExtra(EXTRA_NAME_SCREEN, name);

    return intent;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
	super.onCreate(savedInstanceState);

    // user name
    mMe = TwitterApplication.nApi.getUserId();
    
    
    // 用户栏（用户名/头像）
    mUserText 	  = (TextView) findViewById(R.id.tweet_user_text);
    mNameText 	  = (TextView) findViewById(R.id.realname_text);
    mProfileImage = (ImageView) findViewById(R.id.profile_image);
    
    // follow button
    mFollowButton = (Button) findViewById(R.id.follow_button);
    mFollowButton.setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        confirmFollow();
      }
    });

    Intent intent = getIntent();
    Uri data = intent.getData();

    // Input username
    mUsername = intent.getStringExtra(EXTRA_USER);
    mScreenName = intent.getStringExtra(EXTRA_NAME_SCREEN);

    if (TextUtils.isEmpty(mUsername)) {
      mUsername = data.getLastPathSegment();
    }
    
    // Set header title 
    String header_title = (!TextUtils.isEmpty(mScreenName)) ? mScreenName : mUsername;
    setHeaderTitle("@" + header_title);
    
    setTitle("@" + mUsername);
    mUserText.setText("@" + mUsername);

    State state = (State) getLastNonConfigurationInstance();
    
    
    boolean wasRunning = Utils.isTrue(savedInstanceState, SIS_RUNNING_KEY);

    if (state != null && !wasRunning) {
      mTweets = state.mTweets;
      mUser = state.mUser;
      mIsFollowing = state.mIsFollowing;
      mIsFollower = state.mIsFollower;
      mNextPage = state.mNextPage;
      mProfileBitmap = state.mProfileBitmap;
      mImageCache = state.mImageCache;

      draw();
    } else {
      doRetrieve();
    }

  }

  @Override
  protected void onResume() {
    super.onResume();

    if (!getApi().isLoggedIn()) {
      Log.i(TAG, "Not logged in.");
      handleLoggedOut();
      return;
    }
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return createState();
  }

  private synchronized State createState() {
    return new State(this);
  }

  private static final String SIS_RUNNING_KEY = "running";

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    if (mRetrieveTask != null
        && mRetrieveTask.getStatus() == UserTask.Status.RUNNING) {
      outState.putBoolean(SIS_RUNNING_KEY, true);
    }
  }

  @Override
  protected void onDestroy() {
    Log.i(TAG, "onDestroy.");

    if (mRetrieveTask != null
        && mRetrieveTask.getStatus() == UserTask.Status.RUNNING) {
      mRetrieveTask.cancel(true);
    }

    if (mFriendshipTask != null
        && mFriendshipTask.getStatus() == UserTask.Status.RUNNING) {
      mFriendshipTask.cancel(true);
    }

    if (mLoadMoreTask != null
        && mLoadMoreTask.getStatus() == UserTask.Status.RUNNING) {
      mLoadMoreTask.cancel(true);
    }

    super.onDestroy();
  }


  // UI helpers.

  private void updateProgress(String progress) {
    mProgressText.setText(progress);
  }

  private void draw() {
    if (mProfileBitmap != null) {
      mProfileImage.setImageBitmap(mProfileBitmap);
    }

    mAdapter.refresh(mTweets, mImageCache);

    if (mUser != null) {
      mNameText.setText(mUser.name);
    }

    if (mUsername.equalsIgnoreCase(mMe)) {
      mFollowButton.setVisibility(View.GONE);
    } else if (mIsFollowing != null) {
      mFollowButton.setVisibility(View.VISIBLE);

      if (mIsFollowing) {
        mFollowButton.setText(R.string.user_label_unfollow);
      } else {
        mFollowButton.setText(R.string.user_label_follow);
      }
    }
  }


  private enum TaskResult {
    OK, IO_ERROR, AUTH_ERROR, CANCELLED
  }


  public void doRetrieve() {
    Log.i(TAG, "Attempting retrieve.");
    
    // 旋转刷新按钮
	animRotate(refreshButton);

    if (mRetrieveTask != null
        && mRetrieveTask.getStatus() == UserTask.Status.RUNNING) {
      Log.w(TAG, "Already retrieving.");
    } else {
      mRetrieveTask = new RetrieveTask().execute();
    }
  }

  private void doLoadMore() {
    Log.i(TAG, "Attempting load more.");

    if (mLoadMoreTask != null
        && mLoadMoreTask.getStatus() == UserTask.Status.RUNNING) {
      Log.w(TAG, "Already loading more.");
    } else {
      mLoadMoreTask = new LoadMoreTask().execute();
    }
  }

  private void onRetrieveBegin() {
    updateProgress(getString(R.string.page_status_refreshing));
  }

  private void onLoadMoreBegin() {
    updateProgress(getString(R.string.page_status_refreshing));
    animRotate(refreshButton);
  }

  private class RetrieveTask extends UserTask<Void, Void, TaskResult> {
    @Override
    public void onPreExecute() {
      onRetrieveBegin();
    }

    ArrayList<Tweet> mTweets = new ArrayList<Tweet>();

    @Override
    public TaskResult doInBackground(Void... params) {
      List<com.ch_linghu.fanfoudroid.weibo.Status> statusList;

      ImageManager imageManager = getImageManager();

      try {
        statusList = getApi().getUserTimeline(mUsername, new Paging(mNextPage));
        
      } catch (WeiboException e) {
        Log.e(TAG, e.getMessage(), e);
        return TaskResult.IO_ERROR;
      } 

      for (com.ch_linghu.fanfoudroid.weibo.Status status : statusList) {
        if (isCancelled()) {
          return TaskResult.CANCELLED;
        }

        Tweet tweet;

        tweet = Tweet.create(status);
        mTweets.add(tweet);

        if (mUser == null) {
          mUser = User.create(status.getUser());
        }
      
        if (isCancelled()) {
          return TaskResult.CANCELLED;
        }
      }

      addTweets(mTweets);

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      publishProgress();

	 mImageCache = new MemoryImageCache();
     if (!Utils.isEmpty(mUser.profileImageUrl)) {
        try {
          Bitmap bitmap = imageManager.fetchImage(mUser.profileImageUrl);
          setProfileBitmap(bitmap);
      	  mImageCache.put(mUser.profileImageUrl, bitmap);
        } catch (IOException e) {
          Log.e(TAG, e.getMessage(), e);
        }
      }

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      publishProgress();

      try {
        mIsFollowing = getApi().existsFriendship(mMe, mUsername);
        mIsFollower = getApi().existsFriendship(mUsername, mMe);
      } catch (WeiboException e) {
        Log.e(TAG, e.getMessage(), e);
        return TaskResult.IO_ERROR;
      }

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      return TaskResult.OK;
    }

    @Override
    public void onProgressUpdate(Void... progress) {
      draw();
    }

    @Override
    public void onPostExecute(TaskResult result) {
      if (result == TaskResult.AUTH_ERROR) {
        updateProgress(getString(R.string.user_prompt_this_person_has_protected_their_updates));

        return;
      } else if (result == TaskResult.OK) {
    	refreshButton.clearAnimation();
        draw();
      } else {
        // Do nothing.
      }

      updateProgress("");
    }
  }

  private class LoadMoreTask extends UserTask<Void, Void, TaskResult> {
    @Override
    public void onPreExecute() {
      onLoadMoreBegin();
    }

    ArrayList<Tweet> mTweets = new ArrayList<Tweet>();

    @Override
    public TaskResult doInBackground(Void... params) {
      List<com.ch_linghu.fanfoudroid.weibo.Status> statusList;

      try {
        statusList = getApi().getUserTimeline(mUsername, new Paging(mNextPage));
      } catch (WeiboException e) {
        Log.e(TAG, e.getMessage(), e);
        return TaskResult.IO_ERROR;
      }

      for (com.ch_linghu.fanfoudroid.weibo.Status status : statusList) {
        if (isCancelled()) {
          return TaskResult.CANCELLED;
        }

        Tweet tweet;

        tweet = Tweet.create(status);
        mTweets.add(tweet);
      }

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      addTweets(mTweets);

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      return TaskResult.OK;
    }

    @Override
    public void onProgressUpdate(Void... progress) {
      draw();
    }

    @Override
    public void onPostExecute(TaskResult result) {
      if (result == TaskResult.AUTH_ERROR) {
        logout();
      } else if (result == TaskResult.OK) {
    	refreshButton.clearAnimation();
        draw();
      } else {
        // Do nothing.
      }

      updateProgress("");
    }
  }

  private class FriendshipTask extends UserTask<Void, Void, TaskResult> {

    private boolean mIsDestroy;

    public FriendshipTask(boolean isDestroy) {
      mIsDestroy = isDestroy;
    }

    @Override
    public void onPreExecute() {
      mFollowButton.setEnabled(false);

      if (mIsDestroy) {
        updateProgress(getString(R.string.user_status_unfollowing));
      } else {
        updateProgress(getString(R.string.user_status_following));
      }
    }

    @Override
    public TaskResult doInBackground(Void... params) {
      com.ch_linghu.fanfoudroid.weibo.User user;

      String id = mUser.id;

      try {
        if (mIsDestroy) {
          user = getApi().destroyFriendship(id);
        } else {
          user = getApi().createFriendship(id);
        }
      } catch (WeiboException e) {
        Log.e(TAG, e.getMessage(), e);
        return TaskResult.IO_ERROR;
      }

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      User.create(user);

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      return TaskResult.OK;
    }

    @Override
    public void onPostExecute(TaskResult result) {
      if (result == TaskResult.AUTH_ERROR) {
        logout();
      } else if (result == TaskResult.OK) {
        mIsFollowing = !mIsFollowing;
        draw();
      } else {
        // Do nothing.
      }

      mFollowButton.setEnabled(true);
      updateProgress("");
    }
  }


  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuItem item = menu.add(0, OPTIONS_MENU_ID_DM, 0, R.string.page_title_direct_messages);
    item.setIcon(android.R.drawable.ic_menu_send);

    item = menu.add(0, OPTIONS_MENU_ID_FOLLOW, 0, R.string.user_label_follow);
    item.setIcon(android.R.drawable.ic_menu_add);

    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuItem item = menu.findItem(OPTIONS_MENU_ID_DM);
    item.setEnabled(mIsFollower);

    item = menu.findItem(OPTIONS_MENU_ID_FOLLOW);

    if (mIsFollowing == null) {
      item.setEnabled(false);
      item.setTitle(R.string.user_label_follow);
      item.setIcon(android.R.drawable.ic_menu_add);
    } else if (mIsFollowing) {
      item.setTitle(R.string.user_label_unfollow);
      item.setIcon(android.R.drawable.ic_menu_close_clear_cancel);
    } else {
      item.setTitle(R.string.user_label_follow);
      item.setIcon(android.R.drawable.ic_menu_add);
    }

    return super.onPrepareOptionsMenu(menu);
  }

  private static final int DIALOG_CONFIRM = 0;

  private void confirmFollow() {
    showDialog(DIALOG_CONFIRM);
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    AlertDialog dialog = new AlertDialog.Builder(this).create();

    dialog.setTitle(R.string.user_label_follow);
    dialog.setButton(DialogInterface.BUTTON_POSITIVE, "Doesn't matter", mConfirmListener);
    dialog.setButton(DialogInterface.BUTTON_NEUTRAL,
        getString(R.string.general_lable_cancel), mCancelListener);
    dialog.setMessage("FOO");

    return dialog;
  }

  @Override
  protected void onPrepareDialog(int id, Dialog dialog) {
    super.onPrepareDialog(id, dialog);

    AlertDialog confirmDialog = (AlertDialog) dialog;

    String action = mIsFollowing ? getString(R.string.user_label_unfollow) :
        getString(R.string.user_label_follow);
    String message = action + " " + mUsername + "?";

    (confirmDialog.getButton(DialogInterface.BUTTON_POSITIVE)).setText(action);
    confirmDialog.setMessage(message);
  }

  private DialogInterface.OnClickListener mConfirmListener = new DialogInterface.OnClickListener() {
    public void onClick(DialogInterface dialog, int whichButton) {
      toggleFollow();
    }
  };

  private DialogInterface.OnClickListener mCancelListener = new DialogInterface.OnClickListener() {
    public void onClick(DialogInterface dialog, int whichButton) {
    }
  };

  private void toggleFollow() {
    if (mFriendshipTask != null
        && mFriendshipTask.getStatus() == UserTask.Status.RUNNING) {
      Log.w(TAG, "Already updating friendship.");
      return;
    }

    mFriendshipTask = new FriendshipTask(mIsFollowing).execute();

    // TODO: should we do a timeline refresh here?
  }

  @Override
  public void needMore() {
    if (!isLastPage()) {
      doLoadMore();
    }
  }

  public boolean isLastPage() {
    return mNextPage == -1;
  }

  private synchronized void addTweets(ArrayList<Tweet> tweets) {
    if (tweets.size() == 0) {
      mNextPage = -1;
      return;
    }

    mTweets.addAll(tweets);

    ++mNextPage;
  }

  private synchronized void setProfileBitmap(Bitmap bitmap) {
    mProfileBitmap = bitmap;
  }

@Override
protected String getActivityTitle() {
	return "@" + mUsername;
}

@Override
protected Tweet getContextItemTweet(int position) {
	if (position >= 1){
		return (Tweet)mAdapter.getItem(position-1);
	}else{
		return null;
	}
}

@Override
protected int getLayoutId() {
	return R.layout.user;
}

@Override
protected com.ch_linghu.fanfoudroid.ui.module.TweetAdapter getTweetAdapter() {
	return mAdapter;
}

@Override
protected ListView getTweetList() {
	return mTweetList;
}

@Override
protected void setupState() {
    mTweets = new ArrayList<Tweet>();
    mAdapter = new TweetArrayAdapter(this, mImageCache);
    // Add Header to ListView
    mTweetList 	  = (MyListView) findViewById(R.id.tweet_list);
    View header = View.inflate(this, R.layout.user_header, null);
    mTweetList.addHeaderView(header);
    mTweetList.setAdapter(mAdapter);
    mTweetList.setOnNeedMoreListener(this);
}

@Override
protected void updateTweet(Tweet tweet) {
	// TODO Simple and stupid implementation
	for (Tweet t : mTweets){
		if (t.id.equals(tweet.id)){
			t.favorited = tweet.favorited;
			break;
		}
	}
}

@Override
protected boolean useBasicMenu() {
	return true;
}
  
 

}