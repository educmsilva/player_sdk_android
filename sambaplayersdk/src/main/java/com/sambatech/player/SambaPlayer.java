package com.sambatech.player;

import android.app.Activity;
import android.app.MediaRouteButton;
import android.content.Context;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.exoplayer.ExoPlayer;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.api.ResultCallbacks;
import com.google.android.gms.common.api.Status;
import com.google.android.libraries.mediaframework.exoplayerextensions.ExoplayerWrapper;
import com.google.android.libraries.mediaframework.exoplayerextensions.UnsupportedDrmException;
import com.google.android.libraries.mediaframework.exoplayerextensions.Video;
import com.google.android.libraries.mediaframework.layeredvideo.PlaybackControlLayer;
import com.google.android.libraries.mediaframework.layeredvideo.SimpleVideoPlayer;
import com.sambatech.player.adapter.CaptionsAdapter;
import com.sambatech.player.adapter.OutputAdapter;
import com.sambatech.player.cast.CastDRM;
import com.sambatech.player.cast.CastObject;
import com.sambatech.player.cast.CastOptionsProvider;
import com.sambatech.player.cast.CastQuery;
import com.sambatech.player.cast.SambaCast;
import com.sambatech.player.event.SambaCastListener;
import com.sambatech.player.event.SambaEvent;
import com.sambatech.player.event.SambaEventBus;
import com.sambatech.player.event.SambaPlayerListener;
import com.sambatech.player.model.SambaMedia;
import com.sambatech.player.model.SambaMediaConfig;
import com.sambatech.player.model.SambaPlayerError;
import com.sambatech.player.plugins.Captions;
import com.sambatech.player.plugins.PluginManager;
import com.sambatech.player.utils.Helpers;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents the player, responsible for managing media playback.
 *
 * @author Leandro Zanol - 7/12/15
 */
public class SambaPlayer extends FrameLayout {

	private final ExoplayerWrapper.PlaybackListener playbackListener =
			new ExoplayerWrapper.PlaybackListener() {
		@Override
		public void onStateChanged(boolean playWhenReady, int playbackState) {
			Log.i("SambaPlayer", "state: " + playWhenReady + " " + playbackState + "; playing: " + isPlaying());

			switch (playbackState) {
				case ExoPlayer.STATE_READY:
					if (playWhenReady) {
                        if (!_hasStarted) {
                            _hasStarted = true;
	                        _currentRetryIndex = 0;

	                        destroyError();
                            SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.START));

	                        //Show controls
							player.show();

	                        if (!media.isLive && _initialTime > 0) {
		                        seek(_initialTime);
		                        _initialTime = 0;
	                        }
                        }

                        dispatchPlay();
                    }
                    else dispatchPause();
					player.hideLoading();
					break;
				case ExoPlayer.STATE_ENDED:
					if (!playWhenReady)
						break;

					stopProgressTimer();
					pause();
					seek(0);
					SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.FINISH));
					_hasFinished = true;
					player.hideLoading();
					break;
				case ExoPlayer.STATE_BUFFERING:
					player.showLoading();
					break;

			}
		}

		@Override
		public void onError(final Exception e) {
			Log.d("SambaPlayer", "Error: " + media, e);

			// URL not found (or cannot reach server)
			if (Helpers.isNetworkAvailable(getContext())) {
				// check whether it can fallback (changes error criticity) or fail otherwise
				if (_currentBackupIndex < media.backupUrls.length) {
					if (_initialTime == 0f)
						_initialTime = getCurrentTime();

					media.url = media.backupUrls[_currentBackupIndex++];

					destroyInternal();
					create(false);
					dispatchError(SambaPlayerError.unknown.setValues(e.hashCode(), "Connecting...", false, e));
					return;
				}
			}
			// no network connection
			else if (_currentRetryIndex++ < media.retriesTotal) {
				final AtomicInteger secs = new AtomicInteger(8);
				final Timer timer = new Timer();

				if (_initialTime == 0f)
					_initialTime = getCurrentTime();

				destroyInternal();

				timer.scheduleAtFixedRate(new TimerTask() {
					@Override
					public void run() {
						((Activity) SambaPlayer.this.getContext()).runOnUiThread(new Runnable() {
							@Override
							public void run() {
								if (secs.get() == 0) {
									timer.cancel();
									timer.purge();
									create(false);
								}

								dispatchError(SambaPlayerError.unknown.setValues(e.hashCode(),
										secs.get() > 0 ? String.format("Reconnecting in %ss", secs) : "Connecting...",
										false, e, R.drawable.ic_nosignal_disable));

								secs.decrementAndGet();
							}
						});
					}
				}, 0, 1000);
				return;
			}

			dispatchError(SambaPlayerError.unknown.setValues(e.hashCode(),
					e.getCause() instanceof UnsupportedDrmException ? "You're not allowed to "
						+ (media.isAudioOnly ? "listen to this audio" : "watch this video")
						: "Oops! Please try again later...", true, e));
		}

		@Override
		public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
			//Log.i("SambaPlayer", unappliedRotationDegrees+" "+width + " " + height);
			SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.RESIZE, width, height,
					unappliedRotationDegrees, pixelWidthHeightRatio));
		}
	};

	private final PlaybackControlLayer.PlayCallback playListener = new PlaybackControlLayer.PlayCallback() {
		@Override
		public void onPlay() {
			if (player.getPlaybackState() == ExoPlayer.STATE_ENDED)
				seek(0);
		}
	};

	private final PlaybackControlLayer.FullscreenCallback fullscreenListener = new PlaybackControlLayer.FullscreenCallback() {
		@Override
		public void onGoToFullscreen() {
			SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.FULLSCREEN));
		}

		@Override
		public void onReturnFromFullscreen() {
			SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.FULLSCREEN_EXIT));
		}
	};

	private final AdapterView.OnItemClickListener outputMenuItemListener = new AdapterView.OnItemClickListener() {
		@Override
		public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
			player.closeOutputMenu();
			changeOutput((SambaMedia.Output) parent.getItemAtPosition(position));
		}
	};

    private final AdapterView.OnItemClickListener captionMenuItemListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            player.closeCaptionMenu();
            changeCaption(position);
        }
    };

	private final Runnable progressDispatcher = new Runnable() {
		@Override
		public void run() {
			if (player == null) return;

			SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.PROGRESS, getCurrentTime(), getDuration()));
		}
	};

	private final SambaCastListener castListener = new SambaCastListener() {

		RemoteMediaClient castPlayer;
		
		private int lastPosition = 0;

		private final PlaybackControlLayer.InterceptableListener interceptableListener = new PlaybackControlLayer.InterceptableListener() {
			@Override
			public boolean onPlay() {
				dispatchPlay();
				sambaCast.playCast();
				return false;
			}

			@Override
			public boolean onPause() {
				dispatchPause();
				sambaCast.pauseCast();
				return false;
			}

			@Override
			public boolean onSeek(int position) {
				sambaCast.seekTo(position);
				return false;
			}

			@Override
			public int getCurrentTime() {
				return castPlayer != null ? (int)castPlayer.getApproximateStreamPosition() : 0;
			}

			@Override
			public int getDuration() {
				return castPlayer != null ? (int)castPlayer.getStreamDuration() : 0;
			}
		};

		@Override
		public void onConnected(final CastSession castSession) {

			stopProgressTimer();
			pause();

			final RemoteMediaClient remoteMediaClient = castSession.getRemoteMediaClient();
			if (remoteMediaClient == null) return;

			// enabling hook for API and user actions
			player.setInterceptableListener(interceptableListener);
			player.setAutoHide(false);
			player.setControlsVisible(false, "outputMenu", "captionMenu");

			// converting SambaMedia to MediaInfo
			MediaMetadata movieMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
			movieMetadata.putString(MediaMetadata.KEY_TITLE, media.title);
			movieMetadata.putString(MediaMetadata.KEY_SUBTITLE, media.title);

			CastQuery qs = new CastQuery(true, CastOptionsProvider.environment.toString(),
					CastOptionsProvider.appId, (int)getCurrentTime(), SambaPlayer.this.getCaption());

			CastObject castObject = new CastObject(media.title, media.id,
					(int) getDuration(),media.themeColorHex,
					media.projectHash, qs, "", CastOptionsProvider.playerUrl);

			if (media.drmRequest != null)
				castObject.setDrm(new CastDRM(media.drmRequest.getLicenseParam("SessionId"),
						media.drmRequest.getLicenseParam("Ticket")));

			MediaInfo mediaInfo = new MediaInfo.Builder(castObject.toString())
					.setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
					.setContentType("video/mp4")
					.setMetadata(movieMetadata)
					.build();

			remoteMediaClient.load(mediaInfo, false, 0).setResultCallback(new ResultCallbacks<RemoteMediaClient.MediaChannelResult>() {
				@Override
				public void onSuccess(@NonNull RemoteMediaClient.MediaChannelResult mediaChannelResult) {
					Log.d("load", mediaChannelResult.getStatus().toString());
				}

				@Override
				public void onFailure(@NonNull Status status) {
					Log.d("load", status.toString());
				}
			});

			sambaCast.registerDeviceForProgress(true);

			lastPosition = 0;

			Cast.MessageReceivedCallback messageReceived = new Cast.MessageReceivedCallback() {
				@Override
				public void onMessageReceived(CastDevice castDevice, String namespace, String message)  {
					Log.i("Message Received", castDevice.toString() + namespace + message);

					try {
						JSONObject jsonObject = new JSONObject(message);

						if (jsonObject.has("progress") && jsonObject.has("duration")) {
							float progress = jsonObject.getInt("progress");
							float duration = jsonObject.getInt("duration");

							lastPosition = (int)progress;

							if (player != null)
								player.setCurrentTime(progress, duration);
						}
						else if (jsonObject.has("type")) {
							jsonObject = new JSONObject(message);
							String type = jsonObject.getString("type");

							if (type.equalsIgnoreCase("finish"))
								sambaCast.stopCasting();
						}
					} catch (JSONException e) {
						e.printStackTrace();
					}
				}
			};

			try {
				castSession.setMessageReceivedCallbacks(CastOptionsProvider.CUSTOM_NAMESPACE,messageReceived);
			} catch (IOException e) {
				e.printStackTrace();
			}

			this.castPlayer = remoteMediaClient;
			player.updatePlayPauseButton(true);
		}

		@Override
		public void onDisconnected() {
			player.setControlsVisible(true,
					outputMenu != null ? "outputMenu" : null,
					captionMenu != null ? "captionMenu" : null);
			player.setAutoHide(true);
			player.seek(lastPosition*1000);
			lastPosition = 0;
			// disabling hook for API and user actions
			player.setInterceptableListener(null);
			play();
			startProgressTimer();
		}
	};

	private SimpleVideoPlayer player;
	private View _errorScreen;
	private @NonNull SambaMediaConfig media = new SambaMediaConfig();
	private Timer progressTimer;
	private boolean _hasStarted;
	private boolean _hasFinished;
	private OrientationEventListener orientationEventListener;
	private View outputMenu;
	private View captionMenu;
	private SambaCast sambaCast;
	private boolean autoFsMode;
	private boolean enableControls;
	private boolean _disabled;
	private int _currentBackupIndex;
	private int _currentRetryIndex;
	private float _initialTime = 0f;

	public SambaPlayer(Context context, AttributeSet attrs) {
		super(context, attrs);
		applyAttributes(getContext().getTheme().obtainStyledAttributes(attrs, R.styleable.SambaPlayer, 0, 0));
	}

	/**
	 * Defines/overwrites current media.
	 * @param media The media to be played.
	 */
	public void setMedia(@NonNull SambaMedia media) {
		SambaMediaConfig m = new SambaMediaConfig(media);

		this.media = m;
		_initialTime = m.initialTime;

		if (m.blockIfRooted && Helpers.isDeviceRooted()) {
			_disabled = true;
			dispatchError(SambaPlayerError.rootedDevice);
			return;
		}

		destroy();

		// TODO: create thumbnail or create audio player

		PluginManager.getInstance().onLoad(this);
	}

	/**
	 * Retrieves the current media in use.
	 *
	 * Always returns a non null media data after LOAD event has been dispatched,
	 * but before its dispatch null checks must be made.
	 *
	 * @return Media data
	 */
	public SambaMedia getMedia() {
		return media;
	}

	/**
	 * Resumes media playback.
	 */
	public void play() {
		// in case of forbidden rooted device
		if (_disabled) return;

		// defer play if plugins not loaded yet
		if (!PluginManager.getInstance().isLoaded()) {
			PluginManager.getInstance().setPendingPlay(true);
			return;
		}

		// create internal player if it doesn't exist
		if (player == null) {
			create();
			return;
		}

		player.play();
	}

	/**
	 * Pauses media playback.
	 */
	public void pause() {
		if (player == null || !_hasStarted) return;

		player.pause();
	}

	/**
	 * Stops media playback returning the video to it's beginning.
	 */
	public void stop() {
		if (player == null) return;

		player.stop();
		SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.STOP));
	}

	/**
	 * Moves the media to a specific position.
	 * @param position New position of the media in seconds
	 */
	public void seek(float position) {
		if (player == null) return;

		player.seek(Math.round(position * 1000f));
	}

	/**
	 * Enables or disables controls.
	 * @param flag true to show or hide the controls
	 */
	public void setEnableControls(boolean flag) {
		if(media.isAudioOnly) return;

		if(player != null) {
			if(flag)
				player.enableControls();
			else
				player.disableControls();
		}
		else enableControls = flag;
	}

	/**
	 * Sets fullscreen mode on and off.
	 * @param flag true to enter in the fullscreen mode on and false to exit
	 */
	public void setFullscreen(boolean flag) {
		if (player == null) return;

		player.setFullscreen(flag);
	}

	/**
	 * Indicates the fullscreen mode on or off.
	 * @return boolean Whether fullscreen mode is on of off.
	 */
	public boolean isFullscreen() {
		return player != null && player.isFullscreen();
	}

	/**
	 * Shows player controls.
	 */
	public void show() {
		if (player == null) return;

		player.show();
	}

	/**
	 * Hides player controls.
	 */
	public void hide() {
		if (player == null) return;

		player.hide();
	}

	/**
	 * Sets whether the player should go automatically on fullscreen or not.
	 * @param flag true to enable auto fullscreen mode and false to disable it
	 */
	public void setAutoFullscreenMode(boolean flag) {
		autoFsMode = flag;
	}

	/**
	 * Gets the current time on the video.
	 * @return Float Current time
	 */
	public float getCurrentTime() {
		return player != null ? player.getCurrentPosition()/1000f : 0f;
	}

	/**
	 * Gets the total duration of the video.
	 * @return Float total duration
	 */
	public float getDuration() {
		return player != null ? player.getDuration()/1000f : media.duration;
	}

	/**
	 * Indicates whether media is being reproduced.
	 * @return The state of media playback
	 */
	public boolean isPlaying() {
		return player != null && player.shouldBePlaying();
	}

	/**
	 * Indicates whether media has already started playing.
	 * @return True if media has started playing
	 */
	public boolean hasStarted() {
		return _hasStarted;
	}

	/**
	 * Indicates whether media has finished at least once.
	 * Does not imply playing it thoroughly without seeking.
	 * @return True once media hits the end
	 */
	public boolean hasFinished() {
		return _hasFinished;
	}

	/**
	 * Changes the current output.
	 * @param output SambaMedia.Output indicating the new output
	 */
	public void changeOutput(@NonNull SambaMedia.Output output) {
		if (output.url == null || output.url.isEmpty()) {
			//dispatchError(SambaPlayerError.emptyUrl);
			Log.e("SambaPlayer", "URL not found for output \"" + output.label + "\".");
			return;
		}

		int currentPosition = player.getCurrentPosition();

		for (SambaMedia.Output o : media.outputs)
			o.isDefault = o.label.equals(output.label);

		media.url = output.url;

		destroyInternal();
		create(false);
		player.seek(currentPosition);
	}

	/*
	 * Changes the current output.
	 * @param index The index in the outputs array.
	 */
	/*public void changeOutput(int index) {

	}*/

	/**
	 * Changes the current caption.
	 * @param index The index in the captions array
	 */
	public void changeCaption(int index) {
		Captions plugin = (Captions) PluginManager.getInstance().getPlugin(Captions.class);

		if (plugin == null) return;

		plugin.changeCaption(index);
	}

	public String getCaption() {
		if (captionMenu == null) return null;

		CaptionsAdapter adapter = (CaptionsAdapter) ((ListView) captionMenu.findViewById(R.id.menu_list)).getAdapter();
		SambaMedia.Caption caption = (SambaMedia.Caption) adapter.getItem(adapter.currentIndex);

		return String.format("[%s,ffcc00,42]", caption.language);

	}

	/**
	 * If set, Chromecast support will be enabled inside player view.
	 * @param sambaCast The SambaCast instance
	 */
	public void setSambaCast(@NonNull SambaCast sambaCast) {
		this.sambaCast = sambaCast;
		setupCast();
	}

	/**
	 * Destroys the player and it's events.
	 */
	public void destroy() {
		destroy(null);
	}

	/**
	 * Destroys the player and it's events and shows an error screen.
	 * @param error Error type to show
	 */
	public void destroy(SambaPlayerError error) {
		PluginManager.getInstance().onDestroy();
		destroyInternal();
		SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.UNLOAD));

		if (error != null)
			showError(error);
		else destroyError();
	}

	/* End Player API */

	private void applyAttributes(TypedArray attrs) {
		try {
			setAutoFullscreenMode(attrs.getBoolean(R.styleable.SambaPlayer_autoFullscreenMode, false));
			setEnableControls(attrs.getBoolean(R.styleable.SambaPlayer_enableControls, true));
		}
		finally {
			attrs.recycle();
		}
	}

	private void create() {
		create(true);
	}

	private void create(boolean notify) {
		if (player != null) {
			Log.i("SambaPlayer", "Player already created!");
			return;
		}

		if (media.url == null || media.url.isEmpty()) {
			dispatchError(SambaPlayerError.emptyUrl);
			return;
		}

		Video.VideoType videoType = Video.VideoType.OTHER;

		switch (media.type.toLowerCase()) {
			case "hls":
				videoType = Video.VideoType.HLS;
				break;
			case "dash":
				videoType = Video.VideoType.DASH;
				break;
		}

		// no autoplay if there's ad because ImaWrapper takes control of the player
		player = new SimpleVideoPlayer((Activity)getContext(), this,
				new Video(media.url, videoType, media.drmRequest), media.title,
				(sambaCast == null || !sambaCast.isCasting()) && (media.adUrl == null || media.adUrl.isEmpty()),
				media.isAudioOnly);

		player.setThemeColor(media.themeColor);

		// Move the content player's surface layer to the background so that the ad player's surface
		// layer can be overlaid on top of it during ad playback.
		player.moveSurfaceToBackground();

		//Live treatment
		if (media.isLive) {
			((Activity)getContext()).findViewById(R.id.time_container).setVisibility(INVISIBLE);

			player.setControlsVisible(false, "seekbar");
			player.addActionButton(ContextCompat.getDrawable(getContext(), R.drawable.ic_live),
					getContext().getString(R.string.live), null);
		}

		player.addPlaybackListener(playbackListener);
		player.setPlayCallback(playListener);

		if (media.isAudioOnly) {
			player.setControlsVisible(true, "play");
			player.setControlsVisible(false, "fullscreen", "playLarge", "topChrome");
			//playbackControlLayer.swapControls("time", "seekbar");
			player.setBackgroundColor(0xFF434343);
			player.setChromeColor(0x00000000);
		}
		else player.setFullscreenCallback(fullscreenListener);

		// Fullscreen
		orientationEventListener = new OrientationEventListener(getContext()) {

			{ enable(); }

			@Override
			public void onOrientationChanged(int orientation) {
				if (Settings.System.getInt(getContext().getContentResolver(),
						Settings.System.ACCELEROMETER_ROTATION, 0) == 0 || !autoFsMode || player == null)
					return;

				if(orientation <= 15 && orientation >= 0) {
					if(player.isFullscreen()) {
						player.setFullscreen(false);
					}
					SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.PORTRAIT));
				}else if((orientation >= 80 && orientation <= 100 ) || (orientation >= 260 && orientation <= 290)){
					if(!player.isFullscreen()) {
						player.setFullscreen(true);
					}
					SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.LANDSCAPE));
				}
			}
		};

		if (!media.isAudioOnly) {
			// Output Menu
			// TODO: it might not be here
			if (media.outputs != null && media.outputs.size() > 1) {
				outputMenu = ((Activity) getContext()).getLayoutInflater().inflate(R.layout.menu_layout, null);

				TextView cancelButton = (TextView) outputMenu.findViewById(R.id.menu_cancel_button);
				//cancelButton.setTextColor(media.themeColor);

				cancelButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						player.closeOutputMenu();
					}
				});

				OutputAdapter outputAdapter = new OutputAdapter(getContext(), media.outputs);
				ListView outputMenuList = (ListView) outputMenu.findViewById(R.id.menu_list);

				outputMenuList.setAdapter(outputAdapter);
				outputMenuList.setOnItemClickListener(outputMenuItemListener);
				outputAdapter.notifyDataSetChanged();

				player.setOutputMenu(outputMenu);
			}

			//Captions
			if (media.captions != null && media.captions.size() > 0) {
				captionMenu = ((Activity)getContext()).getLayoutInflater().inflate(R.layout.menu_layout, null);

				TextView captionCancelButton = (TextView) captionMenu.findViewById(R.id.menu_cancel_button);
				TextView captionTitle = (TextView) captionMenu.findViewById(R.id.menu_label);
				captionTitle.setText(getContext().getString(R.string.captions));

				captionCancelButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						player.closeCaptionMenu();
					}
				});

				CaptionsAdapter captionsAdapter = new CaptionsAdapter(getContext(), media.captions);
				ListView captionMenuList = (ListView) captionMenu.findViewById(R.id.menu_list);

				captionMenuList.setAdapter(captionsAdapter);
				captionMenuList.setOnItemClickListener(captionMenuItemListener);
				captionMenuList.deferNotifyDataSetChanged();

				player.setCaptionMenu(captionMenu);
			}

			if (!enableControls) {
				player.disableControls();
			}

			PluginManager.getInstance().onInternalPlayerCreated(player);

			if (notify) {
				SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.LOAD, this));
			}
		}

		/*player.addActionButton(ContextCompat.getDrawableRes(getContext(), R.drawable.share),
		        getContext().getString(R.string.share_facebook), new OnClickListener() {
			@Override
			public void onClick(View v) {}
		});*/

		setupCast();

		if (sambaCast != null && sambaCast.isCasting())
			castListener.onConnected(sambaCast.getCastSession());
	}

	private void destroyInternal() {
		if (player == null)
			return;

		stopProgressTimer();
		stop();

		if (outputMenu != null) {
			((ListView)outputMenu.findViewById(R.id.menu_list)).setOnItemClickListener(null);
			outputMenu.findViewById(R.id.menu_cancel_button).setOnClickListener(null);
		}

        if (captionMenu != null) {
            ((ListView)captionMenu.findViewById(R.id.menu_list)).setOnItemClickListener(null);
            captionMenu.findViewById(R.id.menu_cancel_button).setOnClickListener(null);
        }

		orientationEventListener.disable();
		player.removePlaybackListener(playbackListener);
		player.setPlayCallback(null);
		player.setFullscreenCallback(null);

		if (sambaCast != null) {
			player.setInterceptableListener(null);
			sambaCast.setEventListener(null);
		}

		player.release();

		outputMenu = null;
		captionMenu = null;
		orientationEventListener = null;
		player = null;
		_hasStarted = false;
		_hasFinished = false;
		_disabled = false;
	}

	private void showError(@NonNull SambaPlayerError error) {
		if (_errorScreen == null)
			_errorScreen = ((Activity)getContext()).getLayoutInflater().inflate(R.layout.error_screen, this, false);

		TextView textView = (TextView) _errorScreen.findViewById(R.id.error_message);
		textView.setText(error.toString());

		// removes images if audio player
		if (media.isAudioOnly)
			textView.setCompoundDrawables(null, null, null, null);
		// set custom image
		else if (error.getDrawableRes() > 0)
			textView.setCompoundDrawablesWithIntrinsicBounds(0, error.getDrawableRes(), 0, 0);
		// default error image
		else textView.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.error_icon, 0, 0);

		if (_errorScreen.getParent() == null)
			addView(_errorScreen);
	}

	private void destroyError() {
		if (_errorScreen == null) return;
		removeView(_errorScreen);
		_errorScreen = null;
	}

    private void startProgressTimer() {
		if (progressTimer != null)
			return;

		progressTimer = new Timer();
		progressTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				((Activity)getContext()).runOnUiThread(progressDispatcher);
			}
		}, 0, 250);
	}

	private void stopProgressTimer() {
		if (progressTimer == null)
			return;

		progressTimer.cancel();
		progressTimer.purge();
		progressTimer = null;
	}

	private void dispatchPlay() {
		SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.PLAY));
		startProgressTimer();
	}

	private void dispatchPause() {
		stopProgressTimer();
		SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.PAUSE));
	}

	private void dispatchError(@NonNull SambaPlayerError error) {
		// give user the chance to customize error message before showing it (in case of critical)
		SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.ERROR, error));

		if (error.isCritical())
			destroy(error);
		else showError(error);
	}

	private void setupCast() {
		// if Chromecast support is enabled
		if (sambaCast == null || media.isLive || media.isAudioOnly) return;

		sambaCast.setEventListener(castListener);

		if (player != null) {
			MediaRouteButton button = sambaCast.getButton();
			ViewGroup parent = (ViewGroup)button.getParent();

			if (parent != null)
				parent.removeView(button);

			player.addActionButton(button);
		}
	}
}
