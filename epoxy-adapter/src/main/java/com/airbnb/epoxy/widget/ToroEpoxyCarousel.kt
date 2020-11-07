package com.airbnb.epoxy.widget

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Message
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.CallSuper
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.annotation.Dimension
import androidx.annotation.FloatRange
import androidx.annotation.Px
import androidx.annotation.RequiresApi
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowInsetsCompat
import androidx.customview.view.AbsSavedState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.ActivityRecyclerPool
import com.airbnb.epoxy.AutoModel
import com.airbnb.epoxy.Carousel
import com.airbnb.epoxy.EpoxyAdapter
import com.airbnb.epoxy.EpoxyController
import com.airbnb.epoxy.EpoxyItemSpacingDecorator
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyPlayerHolder
import com.airbnb.epoxy.EpoxyRecyclerView
import com.airbnb.epoxy.ModelView
import com.airbnb.epoxy.SimpleEpoxyController
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.epoxy.UnboundedViewPool
import com.airbnb.epoxy.annotations.RemoveIn
import com.airbnb.epoxy.isActivityDestroyed
import com.airbnb.epoxy.media.PlaybackInfo
import com.airbnb.epoxy.preload.EpoxyModelPreloader
import com.airbnb.epoxy.preload.EpoxyPreloader
import com.airbnb.epoxy.preload.PreloadErrorHandler
import com.airbnb.epoxy.preload.PreloadRequestHolder
import com.airbnb.epoxy.preload.ViewMetadata
import com.airbnb.epoxy.toro.CacheManager
import com.airbnb.epoxy.toro.PlayerDispatcher
import com.airbnb.epoxy.toro.PlayerSelector
import com.airbnb.epoxy.toro.ToroPlayer
import com.airbnb.epoxy.toro.ToroUtil
import com.airbnb.viewmodeladapter.R
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_MATCH_HEIGHT)
class ToroEpoxyCarousel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : Carousel(context, attrs, defStyleAttr) {
    private val TAG = "ToroLib:Container"

    internal val SOME_BLINKS = 50L  // 3 frames ...

    private val playerManager: PlayerManager = PlayerManager()
    private val childLayoutChangeListener: ChildLayoutChangeListener
    private var playerDispatcher = PlayerDispatcher.DEFAULT
    private var recyclerListener: RecyclerListenerImpl? = null  // null = not attached/detached
    private var playerSelector = PlayerSelector.DEFAULT   // null = do nothing
    private var animatorFinishHandler: Handler? = null  // null = not attached/detached
    private var behaviorCallback: BehaviorCallback? = null

    init {
        childLayoutChangeListener = ChildLayoutChangeListener(this)
        requestDisallowInterceptTouchEvent(true)
    }

    private fun setRecyclerListener2(listener: RecyclerListener?) {
        if (recyclerListener == null) {
            recyclerListener = RecyclerListenerImpl(this)
        }
        recyclerListener!!.delegate = listener
    }

    override fun onChildAttachedToWindow(child: View) {
        //TODO: Needs manual logic merge with Carousel
        super.onChildAttachedToWindow(child) //Carousel's
        Log.d("TAG", "onChildAttachedToWindow")

        child.addOnLayoutChangeListener(childLayoutChangeListener)
        val holder = child.tag

        if (holder !is EpoxyPlayerHolder) return

        playbackInfoCache.onPlayerAttached(holder)
        if (playerManager.manages(holder)) {
            // I don't expect this to be called. If this happens, make sure to note the scenario.
            Log.w(TAG, "!!Already managed: player = [$holder]")
            // Only if container is in idle state and player is not playing.
            if (scrollState == RecyclerView.SCROLL_STATE_IDLE && !holder.isPlaying) {
                playerManager.play(holder, playerDispatcher)
            }
        } else {
            // LeakCanary report a leak of OnGlobalLayoutListener but I cannot figure out why ...
            child.viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
                override fun onGlobalLayout() {
                    child.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (Common.allowsToPlay(holder)) {
                        if (playerManager.attachPlayer(holder)) {
                            dispatchUpdateOnAnimationFinished(false)
                        }
                    }
                }
            })
        }
    }

    override fun onChildDetachedFromWindow(child: View) {
        //TODO: Needs manual logic merge with Carousel
        super.onChildDetachedFromWindow(child)
        Log.d("TAG", "onChildDetachedFromWindow")

        child.removeOnLayoutChangeListener(childLayoutChangeListener)
        val holder = child.tag
        //noinspection PointlessNullCheck
        if (holder == null || holder !is EpoxyPlayerHolder) return

        val playerManaged = playerManager.manages(holder)
        if (holder.isPlaying) {
            if (!playerManaged) {
                throw IllegalStateException(
                    "Player is playing while it is not in managed state: $holder"
                )
            }
            this.savePlaybackInfo(holder.playerOrder, holder.currentPlaybackInfo)
            playerManager.pause(holder)
        }
        if (playerManaged) {
            playerManager.detachPlayer(holder)
        }
        playbackInfoCache.onPlayerDetached(holder)
        // RecyclerView#onChildDetachedFromWindow(View) is called after other removal finishes, so
        // sometime it happens after all Animation, but we also need to update playback here.
        // If there is no anymore child view, this call will end early.
        dispatchUpdateOnAnimationFinished(true)
        // finally release the player
        // if player manager could not manager player, release by itself.
        if (!playerManager.release(holder)) holder.release()
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        // Need to handle the dead playback even when the Container is still scrolling/flinging.
        val players = playerManager.players
        // 1. Find players those are managed but not qualified to play anymore.
        run {
            var i = 0
            val size = players.size
            while (i < size) {
                val player = players[i]
                if (Common.allowsToPlay(player)) {
                    i++
                    continue
                }
                if (player.isPlaying) {
                    this.savePlaybackInfo(player.playerOrder, player.currentPlaybackInfo)
                    playerManager.pause(player)
                }
                if (!playerManager.release(player)) player.release()
                playerManager.detachPlayer(player)
                i++
            }
        }

        Log.d("TAG", "onScrollStateChanged $state")

        // 2. Refresh the good players list.
        val layout = super.getLayoutManager()
        // current number of visible 'Virtual Children', or zero if there is no LayoutManager available.
        val childCount = layout?.childCount ?: 0
        Log.d("TAG", "childCount $childCount")
        if (childCount <= 0 || state != RecyclerView.SCROLL_STATE_IDLE) {
            playerManager.deferPlaybacks()
            return
        }

        for (i in 0 until childCount) {
            val child = layout!!.getChildAt(i)
            val holder = child?.tag
            if (holder is EpoxyPlayerHolder) {
                // Check candidate's condition
                if (Common.allowsToPlay(holder)) {
                    Log.d("TAG", "allowsToPlay")
                    if (!playerManager.manages(holder)) {
                        playerManager.attachPlayer(holder)
                    }
                    // Don't check the attach result, because the player may be managed already.
                    if (!holder.isPlaying) {  // not playing or not ready to play.
                        playerManager.initialize(holder, this@ToroEpoxyCarousel)
                        Log.d("TAG", "initialize")
                    } else {
                        Log.d("TAG", "isPlaying")
                    }
                }

                Log.d("TAG", "EpoxyPlayerHolder")
            }
        }

        val source = playerManager.players
        val count = source.size
        if (count < 1) return   // No available player, return.

        val candidates = ArrayList<ToroPlayer>()
        for (i in 0 until count) {
            val player = source[i]
            if (player.wantsToPlay()) candidates.add(player)
        }
        Collections.sort(candidates, Common.ORDER_COMPARATOR)

        val toPlay = if (playerSelector != null)
            playerSelector.select(this, candidates)
        else
            emptyList()
        for (player in toPlay) {
            if (!player.isPlaying) playerManager.play(player, playerDispatcher)
        }

        source.removeAll(toPlay)
        // Now 'source' contains only ones need to be paused.
        for (player in source) {
            if (player.isPlaying) {
                this.savePlaybackInfo(player.playerOrder, player.currentPlaybackInfo)
                playerManager.pause(player)
            }
        }
    }

    /**
     * Filter current managed [ToroPlayer]s using [Filter]. Result is sorted by Player
     * order obtained from [ToroPlayer.getPlayerOrder].
     *
     * @param filter the [Filter] to a [ToroPlayer].
     * @return list of players accepted by [Filter]. Empty list if there is no available player.
     */
    fun filterBy(filter: Filter): List<ToroPlayer> {
        val result = ArrayList<ToroPlayer>()
        for (player in playerManager.players) {
            if (filter.accept(player)) result.add(player)
        }
        Collections.sort(result, Common.ORDER_COMPARATOR)
        return result
    }

    /**
     * An utility interface, used by [Container] to filter for [ToroPlayer].
     */
    interface Filter {

        /**
         * Check a [ToroPlayer] for a condition.
         *
         * @param player the [ToroPlayer] to check.
         * @return `true` if this accepts the [ToroPlayer], `false` otherwise.
         */
        fun accept(player: ToroPlayer): Boolean

        companion object {

            /**
             * A built-in [Filter] that accepts only [ToroPlayer] that is playing.
             */
            val PLAYING: Filter = object : Filter {
                override fun accept(player: ToroPlayer): Boolean {
                    return player.isPlaying
                }
            }

            /**
             * A built-in [Filter] that accepts only [ToroPlayer] that is managed by Container.
             * Actually any [ToroPlayer] to be filtered is already managed.
             */
            val MANAGING: Filter = object : Filter {
                override fun accept(player: ToroPlayer): Boolean {
                    return true
                }
            }
        }
    }

    /**
     * Setup a [PlayerSelector]. Set a `null` [PlayerSelector] will stop all
     * playback.
     *
     * @param playerSelector new [PlayerSelector] for this [Container].
     */
    fun setPlayerSelector(playerSelector: PlayerSelector?) {
        if (this.playerSelector === playerSelector) return
        this.playerSelector = playerSelector
        // dispatchUpdateOnAnimationFinished(true); // doesn't work well :(
        // Immediately update.
        this.onScrollStateChanged(RecyclerView.SCROLL_STATE_IDLE)
    }

    /**
     * Get current [PlayerSelector]. Can be `null`.
     *
     * @return current [.playerSelector]
     */
    fun getPlayerSelector(): PlayerSelector? {
        return playerSelector
    }

    fun setPlayerDispatcher(playerDispatcher: PlayerDispatcher) {
        this.playerDispatcher = checkNotNull(playerDispatcher)
    }

    /** Define the callback that to be used later by [Behavior] if setup.  */
    fun setBehaviorCallback(behaviorCallback: BehaviorCallback?) {
        this.behaviorCallback = behaviorCallback
    }

    ////// Handle update after data change animation

    internal fun getMaxAnimationDuration(): Long {
        val animator = itemAnimator ?: return SOME_BLINKS.toLong()
        return Common.max(
            animator.addDuration, animator.moveDuration, animator.removeDuration,
            animator.changeDuration
        )
    }

    internal fun dispatchUpdateOnAnimationFinished(immediate: Boolean) {
        if (scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        if (animatorFinishHandler == null) return
        val duration: Long = if (immediate) SOME_BLINKS else getMaxAnimationDuration()
        if (itemAnimator != null) {
            itemAnimator!!.isRunning {
                animatorFinishHandler!!.removeCallbacksAndMessages(null)
                animatorFinishHandler!!.sendEmptyMessageDelayed(-1, duration)
            }
        } else {
            animatorFinishHandler!!.removeCallbacksAndMessages(null)
            animatorFinishHandler!!.sendEmptyMessageDelayed(-1, duration)
        }
    }

    ////// Adapter Data Observer setup

    /**
     * See [ToroDataObserver]
     */
    private val dataObserver = ToroDataObserver()

    //// PlaybackInfo Cache implementation
    private var playbackInfoCache = PlaybackInfoCache(this)
    private var playerInitializer = Initializer.DEFAULT
    private var cacheManager: CacheManager? = null // null by default

    fun getPlayerInitializer() = playerInitializer

    fun setPlayerInitializer(playerInitializer: Initializer) {
        this.playerInitializer = playerInitializer
    }

    /**
     * Save [PlaybackInfo] for the current [ToroPlayer] of a specific order.
     * If called with [PlaybackInfo.SCRAP], it is a hint that the Player is completed and need
     * to be re-initialized.
     *
     * @param order order of the [ToroPlayer].
     * @param playbackInfo current [PlaybackInfo] of the [ToroPlayer]. Null info will be ignored.
     */
    fun savePlaybackInfo(order: Int, playbackInfo: PlaybackInfo?) {
        if (playbackInfo != null) playbackInfoCache.savePlaybackInfo(order, playbackInfo)
    }

    /**
     * Get the cached [PlaybackInfo] at a specific order.
     *
     * @param order order of the [ToroPlayer] to get the cached [PlaybackInfo].
     * @return cached [PlaybackInfo] if available, a new one if there is no cached one.
     */
    fun getPlaybackInfo(order: Int): PlaybackInfo {
        return playbackInfoCache.getPlaybackInfo(order)
    }

    /**
     * Get current list of [ToroPlayer]s' orders whose [PlaybackInfo] are cached.
     * Returning an empty list will disable the save/restore of player's position.
     *
     * @return list of [ToroPlayer]s' orders.
     */
    @RemoveIn(version = "3.6.0")
    @Deprecated("Use {@link #getLatestPlaybackInfos()} for the same purpose.")
    //
    fun getSavedPlayerOrders(): List<Int> {
        return ArrayList(playbackInfoCache.coldKeyToOrderMap.keys)
    }

    /**
     * Get a [SparseArray] contains cached [PlaybackInfo] of [ToroPlayer]s managed
     * by this [Container]. If there is non-null [CacheManager], this method should
     * return the list of all [PlaybackInfo] cached by [PlaybackInfoCache], otherwise,
     * this method returns current [PlaybackInfo] of attached [ToroPlayer]s only.
     */
    fun getLatestPlaybackInfos(): SparseArray<PlaybackInfo> {
        val cache = SparseArray<PlaybackInfo>()
        val activePlayers = this.filterBy(ToroEpoxyCarousel.Filter.PLAYING)
        // This will update hotCache and coldCache if they are available.
        for (player in activePlayers) {
            this.savePlaybackInfo(player.playerOrder, player.currentPlaybackInfo)
        }

        if (cacheManager == null) {
            if (playbackInfoCache.hotCache != null) {
                for (entry in playbackInfoCache.hotCache.entries) {
                    cache.put(entry.key, entry.value)
                }
            }
        } else {
            for (entry in playbackInfoCache.coldKeyToOrderMap.entries) {
                cache.put(entry.key, playbackInfoCache.coldCache.get(entry.value))
            }
        }

        return cache
    }

    /**
     * Set a [CacheManager] to this [Container]. A [CacheManager] will
     * allow this [Container] to save/restore [PlaybackInfo] on various states or life
     * cycle events. Setting a `null` [CacheManager] will remove that ability.
     * [Container] doesn't have a non-null [CacheManager] by default.
     *
     * Setting this while there is a `non-null` [CacheManager] available will clear
     * current [PlaybackInfo] cache.
     *
     * @param cacheManager The [CacheManager] to set to the [Container].
     */
    fun setCacheManager(cacheManager: CacheManager?) {
        if (this.cacheManager === cacheManager) return
        this.playbackInfoCache.clearCache()
        this.cacheManager = cacheManager
    }

    /**
     * Get current [CacheManager] of the [Container].
     *
     * @return current [CacheManager] of the [Container]. Can be `null`.
     */
    fun getCacheManager(): CacheManager? {
        return cacheManager
    }

    /**
     * Temporary save current playback infos when the App is stopped but not re-created. (For example:
     * User press App Stack). If not `empty` then user is back from a living-but-stopped state.
     */
    internal val tmpStates = SparseArray<PlaybackInfo>()

    /**
     * In case user press "App Stack" button, this View's window will have visibility change from
     * [.VISIBLE] to [.INVISIBLE] to [.GONE]. When user is back from that state,
     * the visibility changes from [.GONE] to [.INVISIBLE] to [.VISIBLE]. A proper
     * playback needs to handle this case too.
     */
    @CallSuper
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.GONE) {
            val players = playerManager.players
            // if onSaveInstanceState is called before, source will contain no item, just fine.
            for (player in players) {
                if (player.isPlaying) {
                    this.savePlaybackInfo(player.playerOrder, player.currentPlaybackInfo)
                    playerManager.pause(player)
                }
            }
        } else if (visibility == View.VISIBLE) {
            if (tmpStates.size() > 0) {
                for (i in 0 until tmpStates.size()) {
                    val order = tmpStates.keyAt(i)
                    val playbackInfo = tmpStates.get(order)
                    this.savePlaybackInfo(order, playbackInfo)
                }
            }
            tmpStates.clear()
            dispatchUpdateOnAnimationFinished(true)
        }

        dispatchWindowVisibilityMayChange()
    }

    private var screenState: Int = 0

    override fun onScreenStateChanged(screenState: Int) {
        super.onScreenStateChanged(screenState)
        this.screenState = screenState
        dispatchWindowVisibilityMayChange()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        dispatchWindowVisibilityMayChange()
    }

    /**
     * This method supports the case that by some reasons, Container should changes it behaviour not
     * caused by any Activity recreation (so [.onSaveInstanceState] and
     * [.onRestoreInstanceState] could not help).
     *
     * This method is called when:
     * - Screen state changed.
     * or - Window focus changed.
     * or - Window visibility changed.
     *
     * For each of that event, Screen may be turned off or Window's focus state may change, we need
     * to decide if Container should keep current playback state or change it.
     *
     * **Discussion**: In fact, we expect that: Container will be playing if the
     * following conditions are all satisfied:
     * - Current window is visible. (but not necessarily focused).
     * - Container is visible in Window (partly is fine, we care about the Media player).
     * - Container is focused in Window. (so we don't screw up other components' focuses).
     *
     * In lower API (eg: 16), [.getWindowVisibility] always returns [.VISIBLE], which
     * cannot tell much. We need to investigate this flag in various APIs in various Scenarios.
     */
    private fun dispatchWindowVisibilityMayChange() {
        if (screenState == View.SCREEN_STATE_OFF) {
            val players = playerManager.players
            for (player in players) {
                if (player.isPlaying) {
                    this.savePlaybackInfo(player.playerOrder, player.currentPlaybackInfo)
                    playerManager.pause(player)
                }
            }
        } else if (screenState == View.SCREEN_STATE_ON
            // Container is focused in current Window
            && hasFocus()
            // In fact, Android 24+ supports multi-window mode in which visible Window may not have focus.
            // In that case, other triggers are supposed to be called and we are safe here.
            // Need further investigation if need.
            && hasWindowFocus()
        ) {
            // tmpStates may be consumed already, if there is a good reason for that, so not a big deal.
            if (tmpStates.size() > 0) {
                var i = 0
                val size = tmpStates.size()
                while (i < size) {
                    val order = tmpStates.keyAt(i)
                    this.savePlaybackInfo(order, tmpStates.get(order))
                    i++
                }
            }
            tmpStates.clear()
            dispatchUpdateOnAnimationFinished(true)
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val source = playerManager.players
        for (player in source) {
            if (player.isPlaying) {
                this.savePlaybackInfo(player.playerOrder, player.currentPlaybackInfo)
                playerManager.pause(player)
            }
        }

        val states = playbackInfoCache.saveStates()

        val recreating = context is Activity && (context as Activity).isChangingConfigurations

        // Release current players on recreation event only.
        // Note that there are cases where this method is called without the activity destroying/recreating.
        // For example: in API 26 (my test mostly run on 8.0), when user click to "Current App" button,
        // current Activity will enter the "Stop" state but not be destroyed/recreated and View hierarchy
        // state will be saved (this method is called).
        //
        // We only need to release current resources when the recreation happens.
        if (recreating) {
            for (player in source) {
                if (!playerManager.release(player)) player.release()
                playerManager.detachPlayer(player)
            }
        }

        // Client must consider this behavior using CacheManager implement.
        val playerViewState = PlayerViewState(superState)
        playerViewState.statesCache = states

        // To mark that this method was called. An activity recreation will clear this.
        if (states != null && states!!.size() > 0) {
            for (i in 0 until states!!.size()) {
                val value = states!!.valueAt(i)
                if (value != null) tmpStates.put(states!!.keyAt(i), value)
            }
        }

        return playerViewState
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is PlayerViewState) {
            super.onRestoreInstanceState(state)
            return
        }

        super.onRestoreInstanceState(state.superState)
        val saveStates = state.statesCache
        if (saveStates != null) playbackInfoCache.restoreStates(saveStates)
    }

    private class RecyclerListenerImpl internal constructor(internal val container: ToroEpoxyCarousel) :
        RecyclerListener {
        var delegate: RecyclerListener? = null

        override fun onViewRecycled(holder: EpoxyPlayerHolder) {
            if (this.delegate != null) this.delegate!!.onViewRecycled(holder)
            if (holder is ToroPlayer) {
                val player = holder as ToroPlayer
                this.container.playbackInfoCache.onPlayerRecycled(player)
                this.container.playerManager.recycle(player)
            }
        }
    }

    /**
     * Store the array of [PlaybackInfo] of recently cached playback. This state will be used
     * only when [.cacheManager] is not `null`. Extension of [Container] must
     * also have its own version of [SavedState] extends this [PlayerViewState].
     */
    //
    class PlayerViewState : AbsSavedState {

        internal var statesCache: SparseArray<*>? = null

        /**
         * Called by onSaveInstanceState
         */
        internal constructor(superState: Parcelable) : super(superState) {}

        /**
         * Called by CREATOR
         */
        internal constructor(`in`: Parcel, loader: ClassLoader) : super(`in`, loader) {
            statesCache = `in`.readSparseArray(loader)
        }

        internal constructor(`in`: Parcel) : super(`in`) {}

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)

            dest.writeSparseArray(statesCache as SparseArray<Any>?)
        }

        override fun toString(): String {
            return "Cache{states=$statesCache}"
        }

        companion object {

            @JvmField
            val CREATOR: Parcelable.Creator<PlayerViewState> =
                object : Parcelable.ClassLoaderCreator<PlayerViewState> { // Added from API 13
                    override fun createFromParcel(
                        `in`: Parcel,
                        loader: ClassLoader
                    ): PlayerViewState {
                        return PlayerViewState(`in`, loader)
                    }

                    override fun createFromParcel(source: Parcel): PlayerViewState {
                        return PlayerViewState(source)
                    }

                    override fun newArray(size: Int): Array<PlayerViewState?> {
                        return arrayOfNulls(size)
                    }
                }
        }
    }

    private inner class ToroDataObserver internal constructor() :
        RecyclerView.AdapterDataObserver() {

        private var adapter: RecyclerView.Adapter<*>? = null

        internal fun registerAdapter(adapter: RecyclerView.Adapter<*>?) {
            if (this.adapter === adapter) return
            if (this.adapter != null) {
                this.adapter!!.unregisterAdapterDataObserver(this)
                this.adapter!!.unregisterAdapterDataObserver(playbackInfoCache)
            }

            this.adapter = adapter
            if (this.adapter != null) {
                this.adapter!!.registerAdapterDataObserver(this)
                this.adapter!!.registerAdapterDataObserver(playbackInfoCache)
            }
        }

        override fun onChanged() {
            dispatchUpdateOnAnimationFinished(true)
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            dispatchUpdateOnAnimationFinished(false)
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            dispatchUpdateOnAnimationFinished(false)
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            dispatchUpdateOnAnimationFinished(false)
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            dispatchUpdateOnAnimationFinished(false)
        }
    }

    /**
     * A [Handler.Callback] that will fake a scroll with [.SCROLL_STATE_IDLE] to refresh
     * all the playback. This is relatively expensive.
     */
    private class AnimatorHelper internal constructor(private val container: ToroEpoxyCarousel) :
        Handler.Callback {

        override fun handleMessage(msg: Message): Boolean {
            this.container.onScrollStateChanged(RecyclerView.SCROLL_STATE_IDLE)
            return true
        }
    }

    // This instance is to mark a RecyclerListenerImpl to be set by Toro, not by user.
    private val NULL = RecyclerListener {
        // No-ops
    }

    interface RecyclerListener {

        /**
         * This method is called whenever the view in the ViewHolder is recycled.
         *
         * RecyclerView calls this method right before clearing ViewHolder's internal data and
         * sending it to RecycledViewPool. This way, if ViewHolder was holding valid information
         * before being recycled, you can call [ViewHolder.getAdapterPosition] to get
         * its adapter position.
         *
         * @param holder The ViewHolder containing the view that was recycled
         */
        fun onViewRecycled(holder: EpoxyPlayerHolder)
    }

    /**
     * This behaviour is to catch the touch/fling/scroll caused by other children of
     * [CoordinatorLayout]. We try to acknowledge user actions by intercepting the call but not
     * consume the events.
     *
     * This class helps solve the issue when Client has a [Container] inside a
     * [CoordinatorLayout] together with an [AppBarLayout] whose direct child is a
     * [CollapsingToolbarLayout] (which is 'scrollable'). This 'scroll behavior' is not the
     * same as Container's natural scroll. When user 'scrolls' to collapse or expand the [ ], [CoordinatorLayout] will offset the [Container] to make room for
     * [AppBarLayout], in which [Container] will not receive any scrolling event update,
     * but just be shifted along the scrolling axis. This behavior results in a bad case that after
     * the AppBarLayout collapse its direct [CollapsingToolbarLayout], the Video may be fully
     * visible, but because the Container has no way to know about that event, there is no playback
     * update.
     *
     * @since 3.4.2
     */
    //
    class Behavior
    /* No default constructors. Using this class from xml will result in error. */

    // public Behavior() {
    // }
    //
    // public Behavior(Context context, AttributeSet attrs) {
    //   super(context, attrs);
    // }

        (delegate: CoordinatorLayout.Behavior<ToroEpoxyCarousel>) :
        CoordinatorLayout.Behavior<ToroEpoxyCarousel>(),
        Handler.Callback {

        internal val delegate: CoordinatorLayout.Behavior<in ToroEpoxyCarousel>
        internal var callback: BehaviorCallback? = null

        internal val scrollConsumed = AtomicBoolean(false)

        internal var handler: Handler? = null

        internal fun onViewAttached(container: ToroEpoxyCarousel) {
            if (handler == null) handler = Handler(this)
            this.callback = container.behaviorCallback
        }

        internal fun onViewDetached(container: ToroEpoxyCarousel) {
            if (handler != null) {
                handler!!.removeCallbacksAndMessages(null)
                handler = null
            }
            this.callback = null
        }

        override fun handleMessage(msg: Message): Boolean {
            if (callback == null) return true
            when (msg.what) {
                EVENT_SCROLL, EVENT_TOUCH -> {
                    scrollConsumed.set(false)
                    handler!!.removeMessages(EVENT_IDLE)
                    handler!!.sendEmptyMessageDelayed(EVENT_IDLE, EVENT_DELAY.toLong())
                }
                EVENT_IDLE ->
                    // idle --> consume it.
                    if (!scrollConsumed.getAndSet(true)) callback!!.onFinishInteraction()
            }
            return true
        }

        init {
            this.delegate = ToroUtil.checkNotNull(delegate, "Behavior is null.")
        }

        /// We only need to intercept the following 3 methods:

        override fun onInterceptTouchEvent( //
            parent: CoordinatorLayout, child: ToroEpoxyCarousel, ev: MotionEvent
        ): Boolean {
            if (this.handler != null) {
                this.handler!!.removeCallbacksAndMessages(null)
                this.handler!!.sendEmptyMessage(EVENT_TOUCH)
            }
            return delegate.onInterceptTouchEvent(parent, child, ev)
        }

        override fun onTouchEvent(
            parent: CoordinatorLayout,
            child: ToroEpoxyCarousel,
            ev: MotionEvent
        ): Boolean {
            if (this.handler != null) {
                this.handler!!.removeCallbacksAndMessages(null)
                this.handler!!.sendEmptyMessage(EVENT_TOUCH)
            }
            return delegate.onTouchEvent(parent, child, ev)
        }

        override fun onStartNestedScroll(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            directTargetChild: View, target: View, axes: Int, type: Int
        ): Boolean {
            if (this.handler != null) {
                this.handler!!.removeCallbacksAndMessages(null)
                this.handler!!.sendEmptyMessage(EVENT_SCROLL)
            }
            return delegate.onStartNestedScroll(
                layout,
                child,
                directTargetChild,
                target,
                axes,
                type
            )
        }

        /// Other methods

        override fun onAttachedToLayoutParams(params: CoordinatorLayout.LayoutParams) {
            if (handler == null) handler = Handler(this)
            delegate.onAttachedToLayoutParams(params)
        }

        override fun onDetachedFromLayoutParams() {
            if (handler != null) {
                handler!!.removeCallbacksAndMessages(null)
                handler = null
            }
            delegate.onDetachedFromLayoutParams()
        }

        @ColorInt
        override fun getScrimColor(parent: CoordinatorLayout, child: ToroEpoxyCarousel): Int {
            return delegate.getScrimColor(parent, child)
        }

        @FloatRange(from = 0.0, to = 1.0)
        override fun getScrimOpacity(parent: CoordinatorLayout, child: ToroEpoxyCarousel): Float {
            return delegate.getScrimOpacity(parent, child)
        }

        override fun blocksInteractionBelow(
            parent: CoordinatorLayout,
            child: ToroEpoxyCarousel
        ): Boolean {
            return delegate.blocksInteractionBelow(parent, child)
        }

        override fun layoutDependsOn(
            parent: CoordinatorLayout,
            child: ToroEpoxyCarousel,
            dependency: View
        ): Boolean {
            return delegate.layoutDependsOn(parent, child, dependency)
        }

        override fun onDependentViewChanged(
            parent: CoordinatorLayout, child: ToroEpoxyCarousel,
            dependency: View
        ): Boolean {
            return delegate.onDependentViewChanged(parent, child, dependency)
        }

        override fun onDependentViewRemoved(
            parent: CoordinatorLayout,
            child: ToroEpoxyCarousel,
            dependency: View
        ) {
            delegate.onDependentViewRemoved(parent, child, dependency)
        }

        override fun onMeasureChild(
            parent: CoordinatorLayout,
            child: ToroEpoxyCarousel,
            parentWidthMeasureSpec: Int,
            widthUsed: Int,
            parentHeightMeasureSpec: Int,
            heightUsed: Int
        ): Boolean {
            return delegate.onMeasureChild(
                parent, child, parentWidthMeasureSpec, widthUsed,
                parentHeightMeasureSpec, heightUsed
            )
        }

        override fun onLayoutChild(
            parent: CoordinatorLayout,
            child: ToroEpoxyCarousel,
            layoutDirection: Int
        ): Boolean {
            return delegate.onLayoutChild(parent, child, layoutDirection)
        }

        override fun onNestedScrollAccepted(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            directTargetChild: View, target: View, axes: Int, type: Int
        ) {
            delegate.onNestedScrollAccepted(layout, child, directTargetChild, target, axes, type)
        }

        override fun onStopNestedScroll(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            target: View, type: Int
        ) {
            delegate.onStopNestedScroll(layout, child, target, type)
        }

        override fun onNestedScroll(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int
        ) {
            delegate.onNestedScroll(
                layout, child, target, dxConsumed, dyConsumed, dxUnconsumed,
                dyUnconsumed, type
            )
        }

        override fun onNestedPreScroll(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            target: View, dx: Int, dy: Int, consumed: IntArray, type: Int
        ) {
            delegate.onNestedPreScroll(layout, child, target, dx, dy, consumed, type)
        }

        override fun onNestedFling(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            target: View, velocityX: Float, velocityY: Float, consumed: Boolean
        ): Boolean {
            return delegate.onNestedFling(layout, child, target, velocityX, velocityY, consumed)
        }

        override fun onNestedPreFling(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            target: View, velocityX: Float, velocityY: Float
        ): Boolean {
            return delegate.onNestedPreFling(layout, child, target, velocityX, velocityY)
        }

        override fun onApplyWindowInsets(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            insets: WindowInsetsCompat
        ): WindowInsetsCompat {
            return delegate.onApplyWindowInsets(layout, child, insets)
        }

        override fun onRequestChildRectangleOnScreen(
            layout: CoordinatorLayout, child: ToroEpoxyCarousel,
            rectangle: Rect, immediate: Boolean
        ): Boolean {
            return delegate.onRequestChildRectangleOnScreen(layout, child, rectangle, immediate)
        }

        override fun onRestoreInstanceState(
            parent: CoordinatorLayout, child: ToroEpoxyCarousel,
            state: Parcelable
        ) {
            delegate.onRestoreInstanceState(parent, child, state)
        }

        override fun onSaveInstanceState(
            parent: CoordinatorLayout,
            child: ToroEpoxyCarousel
        ): Parcelable? {
            return delegate.onSaveInstanceState(parent, child)
        }

        override fun getInsetDodgeRect(
            parent: CoordinatorLayout, child: ToroEpoxyCarousel,
            rect: Rect
        ): Boolean {
            return delegate.getInsetDodgeRect(parent, child, rect)
        }

        companion object {

            internal val EVENT_IDLE = 1
            internal val EVENT_SCROLL = 2
            internal val EVENT_TOUCH = 3
            internal val EVENT_DELAY = 150
        }
    }

    /**
     * Callback for [Behavior] to tell the Client that User has finished the interaction for
     * enough amount of time, so it (the Client) should do something. Normally, we ask Container to
     * dispatch an 'idle scroll' to refresh the player list.
     */
    interface BehaviorCallback {

        fun onFinishInteraction()
    }

    interface Initializer {

        fun initPlaybackInfo(order: Int): PlaybackInfo

        companion object {

            val DEFAULT: Initializer = object : Initializer {
                override fun initPlaybackInfo(order: Int): PlaybackInfo {
                    return PlaybackInfo()
                }
            }
        }
    }

    class ChildLayoutChangeListener(container: ToroEpoxyCarousel) : View.OnLayoutChangeListener {

        val containerRef: WeakReference<ToroEpoxyCarousel>

        init {
            this.containerRef = WeakReference<ToroEpoxyCarousel>(container)
        }

        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int,
            oldTop: Int, oldRight: Int, oldBottom: Int
        ) {
            val container = containerRef.get() ?: return
            if (layoutDidChange(left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)) {
//                container.dispatchUpdateOnAnimationFinished(false)
            }
        }

        fun layoutDidChange(
            left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int,
            oldRight: Int, oldBottom: Int
        ): Boolean {
            return left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom
        }
    }

    override fun createLayoutManager(): LayoutManager {
        return LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    }

    override fun swapAdapter(
        adapter: RecyclerView.Adapter<*>?,
        removeAndRecycleExistingViews: Boolean
    ) {
        //TODO: Check if this causes any errors, because ToroEpoxyCarousel is a merge of both Container and EpoxyRecyclerView
        super.swapAdapter(adapter, removeAndRecycleExistingViews)

        dataObserver.registerAdapter(adapter)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        //TODO: Check if this causes any errors, because ToroEpoxyCarousel is a merge of both Container and EpoxyRecyclerView

        if (removedAdapter != null) {
            dataObserver.registerAdapter(removedAdapter)
        }
        if (animatorFinishHandler == null) {
            animatorFinishHandler = Handler(AnimatorHelper(this))
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager != null && powerManager.isScreenOn) {
            this.screenState = View.SCREEN_STATE_ON
        } else {
            this.screenState = View.SCREEN_STATE_OFF
        }

        /* setRecyclerListener can be called before this, it is considered as user-setup */
        if (recyclerListener == null) {
            recyclerListener = RecyclerListenerImpl(this)
            recyclerListener!!.delegate = null // mark as it is set by Toro, not user.
            setRecyclerListener2(recyclerListener)  // must be a super call
        }

        playbackInfoCache.onAttach()
        playerManager.onAttach()

        val params = layoutParams
        if (params is CoordinatorLayout.LayoutParams) {
            val behavior = params.behavior
            if (behavior is Behavior) {
                behavior.onViewAttached(this)
            }
        }


        if (removedAdapter != null) {
            // Restore the adapter that was removed when the view was detached from window
            swapAdapter(removedAdapter, false)
        }
        clearRemovedAdapterAndCancelRunnable()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        //TODO: Check if this causes any errors, because ToroEpoxyCarousel is a merge of both Container and EpoxyRecyclerView

        val params = layoutParams
        if (params is CoordinatorLayout.LayoutParams) {
            val behavior = params.behavior
            if (behavior is Behavior) {
                behavior.onViewDetached(this)
            }
        }

        if (recyclerListener != null && recyclerListener!!.delegate === NULL) {  // set by Toro, not user.
            recyclerListener = null
            setRecyclerListener2(recyclerListener)  // must be a super call
        }

        if (animatorFinishHandler != null) {
            animatorFinishHandler!!.removeCallbacksAndMessages(null)
            animatorFinishHandler = null
        }

        val players = playerManager.players
        if (!players.isEmpty()) {
            val size = players.size
            var i = size - 1
            while (i >= 0) {
                val player = players[i]
                if (player.isPlaying) {
                    this.savePlaybackInfo(player.playerOrder, player.currentPlaybackInfo)
                    playerManager.pause(player)
                }
                playerManager.release(player)
                i--
            }
            playerManager.clear()
        }
        playerManager.onDetach()
        playbackInfoCache.onDetach()
        dataObserver.registerAdapter(null)
        childLayoutChangeListener.containerRef.clear()


        preloadScrollListeners.forEach { it.cancelPreloadRequests() }

//        if (removeAdapterWhenDetachedFromWindow) { //this is always false in case of Carousel
//            if (delayMsWhenRemovingAdapterOnDetach > 0) {
//
//                isRemoveAdapterRunnablePosted = true
//                postDelayed(removeAdapterRunnable, delayMsWhenRemovingAdapterOnDetach.toLong())
//            } else {
                removeAdapter()
//            }
//        }
        clearPoolIfActivityIsDestroyed()
    }

    companion object {
        private const val DEFAULT_ADAPTER_REMOVAL_DELAY_MS = 2000

        /**
         * Store one unique pool per activity. They are cleared out when activities are destroyed, so this
         * only needs to hold pools for active activities.
         */
        private val ACTIVITY_RECYCLER_POOL = ActivityRecyclerPool()

        const val VIEW_HOLDER_TAG = "1234"
    }
}