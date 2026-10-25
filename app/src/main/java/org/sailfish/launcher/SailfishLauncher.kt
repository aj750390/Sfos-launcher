/**
 * Sailfish OS Inspired Launcher for Unrooted Android Devices
 * Built with Kotlin & Jetpack Compose
 *
 * Key Unrooted Architecture Mechanics:
 * 1. Active Covers Grid (Home): Combines UsageStatsManager (recent apps), pinned apps,
 *    NotificationListenerService (live message previews), and MediaSessionManager (playback controls)
 *    to emulate Sailfish OS Lipstick/Silica Active Covers with left/right Cover Actions.
 * 2. Silica Pulley Menu (PullDownMenu): Physics-based vertical drag gesture from the top of any
 *    surface with edge glow, threshold-based item highlighting, haptic ticks, and release-to-execute.
 * 3. Remorse Timer (RemorseItem): 5-second inline cancellation bar when closing a cover or clearing
 *    notifications, matching Sailfish Silica's destructive action pattern.
 * 4. Three-Surface Spatial Flow: HorizontalPager between Events View (left) and Home Covers (right),
 *    plus swipe-up modal sheet for the 4-column Silica App Grid.
 * 5. Ambience Engine: Dynamic color tinting via Android Palette/WallpaperManager coupled with
 *    AudioManager ringer profile switching (Normal, Vibrate, Silent).
 */

package org.sailfish.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// 1. DATA MODELS (Silica Ambience, Active Covers, Notifications & Apps)
// ============================================================================

enum class RingerProfile(val label: String, val audioManagerMode: Int) {
    NORMAL("Normal", AudioManager.RINGER_MODE_NORMAL),
    VIBRATE("Vibrate", AudioManager.RINGER_MODE_VIBRATE),
    SILENT("Silent", AudioManager.RINGER_MODE_SILENT)
}

data class Ambience(
    val id: String,
    val name: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val highlightColor: Color,
    val surfaceOverlay: Color,
    val defaultProfile: RingerProfile = RingerProfile.NORMAL
)

val BuiltInAmbiences = listOf(
    Ambience(
        id = "kaamos_teal",
        name = "Kaamos Borealis",
        primaryColor = Color(0xFF0A2229),
        secondaryColor = Color(0xFF041014),
        highlightColor = Color(0xFF00E5FF),
        surfaceOverlay = Color(0x55103440),
        defaultProfile = RingerProfile.NORMAL
    ),
    Ambience(
        id = "jolla_orange",
        name = "The Orange",
        primaryColor = Color(0xFF2E150B),
        secondaryColor = Color(0xFF140804),
        highlightColor = Color(0xFFFF6D3A),
        surfaceOverlay = Color(0x55421D10),
        defaultProfile = RingerProfile.NORMAL
    ),
    Ambience(
        id = "baltic_cobalt",
        name = "Baltic Dusk",
        primaryColor = Color(0xFF0D1B2A),
        secondaryColor = Color(0xFF070F17),
        highlightColor = Color(0xFF64B5F6),
        surfaceOverlay = Color(0x551B263B),
        defaultProfile = RingerProfile.VIBRATE
    ),
    Ambience(
        id = "silent_snow",
        name = "Silent Night",
        primaryColor = Color(0xFF1A1A24),
        secondaryColor = Color(0xFF0B0B10),
        highlightColor = Color(0xFFB39DDB),
        surfaceOverlay = Color(0x55282838),
        defaultProfile = RingerProfile.SILENT
    )
)

data class CoverAction(
    val label: String,
    val iconName: String,
    val onTrigger: () -> Unit
)

data class ActiveCoverItem(
    val packageName: String,
    val appName: String,
    val statusHeadline: String,
    val statusSubtext: String,
    val lastActiveTimestamp: Long,
    val isPinned: Boolean = false,
    val primaryAction: CoverAction? = null,
    val secondaryAction: CoverAction? = null
)

data class SailfishEventNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null
)

data class PulleyMenuItem(
    val title: String,
    val onSelect: () -> Unit
)

// ============================================================================
// 2. UNROOTED NOTIFICATION & MEDIA BRIDGE (NotificationListenerService)
// ============================================================================

class SailfishNotificationBridge : NotificationListenerService() {

    companion object {
        private val _notifications = MutableStateFlow<List<SailfishEventNotification>>(emptyList())
        val notifications: StateFlow<List<SailfishEventNotification>> = _notifications.asStateFlow()

        private val _liveCoverSnippets = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
        val liveCoverSnippets: StateFlow<Map<String, Pair<String, String>>> = _liveCoverSnippets.asStateFlow()

        fun dismissNotificationByKey(key: String) {
            _notifications.value = _notifications.value.filterNot { it.key == key }
        }

        fun clearAllNotifications() {
            _notifications.value = emptyList()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        refreshActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        refreshActiveNotifications()
    }

    private fun refreshActiveNotifications() {
        val activeList = try {
            activeNotifications ?: emptyArray()
        } catch (e: SecurityException) {
            emptyArray()
        }

        val pm = applicationContext.packageManager
        val mapped = activeList.mapNotNull { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: return@mapNotNull null
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val appName = try {
                val info = pm.getApplicationInfo(sbn.packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                sbn.packageName.substringAfterLast('.')
            }
            SailfishEventNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = text,
                timestamp = sbn.postTime
            )
        }.sortedByDescending { it.timestamp }

        _notifications.value = mapped

        // Also update live Active Cover snippets per package
        val snippetMap = mutableMapOf<String, Pair<String, String>>()
        mapped.forEach { notif ->
            if (!snippetMap.containsKey(notif.packageName)) {
                snippetMap[notif.packageName] = notif.title to notif.text
            }
        }
        _liveCoverSnippets.value = snippetMap
    }
}

// ============================================================================
// 3. UNROOTED SYSTEM HELPER (UsageStats, PackageManager, AudioManager)
// ============================================================================

object UnrootedSystemRepository {

    fun loadInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null
            InstalledApp(
                packageName = pkg,
                label = ri.loadLabel(pm).toString(),
                icon = ri.loadIcon(pm)
            )
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasNotificationListenerPermission(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(context.packageName)
    }

    fun queryRecentAppsForCovers(
        context: Context,
        installedApps: List<InstalledApp>,
        liveSnippets: Map<String, Pair<String, String>>,
        maxCount: Int = 6
    ): List<ActiveCoverItem> {
        val now = System.currentTimeMillis()
        val recentPackages = if (hasUsageStatsPermission(context)) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 1000L * 60 * 60 * 12,
                now
            )
            stats?.filter { it.totalTimeInForeground > 0 && it.packageName != context.packageName }
                ?.sortedByDescending { it.lastTimeUsed }
                ?.map { it.packageName to it.lastTimeUsed }
                ?.distinctBy { it.first }
                ?.take(maxCount)
                ?: emptyList()
        } else {
            emptyList()
        }

        val appMap = installedApps.associateBy { it.packageName }
        if (recentPackages.isNotEmpty()) {
            return recentPackages.mapNotNull { (pkg, lastUsed) ->
                val app = appMap[pkg] ?: return@mapNotNull null
                val snippet = liveSnippets[pkg]
                ActiveCoverItem(
                    packageName = pkg,
                    appName = app.label,
                    statusHeadline = snippet?.first ?: "Running in background",
                    statusSubtext = snippet?.second ?: "Tap to resume • Hold to close",
                    lastActiveTimestamp = lastUsed,
                    primaryAction = CoverAction("Open", "open") {
                        launchApp(context, pkg)
                    }
                )
            }
        }

        // Default starter covers when UsageStats isn't granted yet or on fresh start
        return installedApps.take(maxCount).mapIndexed { index, app ->
            val snippet = liveSnippets[app.packageName]
            ActiveCoverItem(
                packageName = app.packageName,
                appName = app.label,
                statusHeadline = snippet?.first ?: "Active Cover",
                statusSubtext = snippet?.second ?: "Swipe action or tap to launch",
                lastActiveTimestamp = now - index * 60_000L,
                isPinned = index < 2,
                primaryAction = CoverAction("Launch", "launch") {
                    launchApp(context, app.packageName)
                }
            )
        }
    }

    fun launchApp(context: Context, packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }

    fun applyAmbienceRingerProfile(context: Context, profile: RingerProfile) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            audioManager.ringerMode = profile.audioManagerMode
        } catch (_: SecurityException) {
            // Requires Do Not Disturb access for SILENT mode on Android 7.0+
        }
    }
}

// ============================================================================
// 4. SILICA PULLEY MENU COMPONENT (Signature One-Handed Pull-Down Menu)
// ============================================================================

@Composable
fun SilicaPulleyMenuContainer(
    items: List<PulleyMenuItem>,
    highlightColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val stepHeightPx = with(density) { 52.dp.toPx() }
    val activationStartPx = with(density) { 36.dp.toPx() }
    val maxPullPx = activationStartPx + items.size * stepHeightPx + with(density) { 24.dp.toPx() }

    val activeIndex = remember(dragOffsetPx, items.size) {
        if (dragOffsetPx < activationStartPx || items.isEmpty()) {
            -1
        } else {
            ((dragOffsetPx - activationStartPx) / stepHeightPx)
                .toInt()
                .coerceIn(0, items.lastIndex)
        }
    }

    // Trigger subtle haptic tick each time the highlighted pulley item changes
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val animatedOffset by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pulleyOffset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    val next = (dragOffsetPx + delta * 0.85f).coerceIn(0f, maxPullPx)
                    dragOffsetPx = next
                },
                onDragStopped = {
                    val selected = activeIndex
                    dragOffsetPx = 0f
                    if (selected in items.indices) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        items[selected].onSelect()
                    }
                }
            )
    ) {
        // Pulley Menu drawer revealed above content
        if (animatedOffset > 4f && items.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.End
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == activeIndex
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) highlightColor.copy(alpha = 0.28f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = item.title,
                            color = if (isSelected) highlightColor else Color.White.copy(alpha = 0.78f),
                            fontSize = if (isSelected) 18.sp else 16.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }

        // Main screen content translated downwards during pull
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = with(density) { animatedOffset.toDp() })
        ) {
            content()

            // Signature Sailfish Top Glow Indicator showing Pulley Menu availability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                highlightColor.copy(alpha = 0.85f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

// ============================================================================
// 5. SILICA ACTIVE COVER CARD WITH REMORSE TIMER & COVER ACTIONS
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SilicaActiveCoverCard(
    cover: ActiveCoverItem,
    ambience: Ambience,
    onLaunch: () -> Unit,
    onCloseCover: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRemorse by remember { mutableStateOf(false) }
    val remorseProgress = remember { Animatable(1f) }

    LaunchedEffect(showRemorse) {
        if (showRemorse) {
            remorseProgress.snapTo(1f)
            remorseProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 5000, easing = LinearEasing)
            )
            if (showRemorse) {
                onCloseCover()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ambience.surfaceOverlay)
            .border(
                width = 1.dp,
                color = ambience.highlightColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick = {
                    if (showRemorse) {
                        showRemorse = false
                    } else {
                        onLaunch()
                    }
                },
                onLongClick = {
                    showRemorse = true
                }
            )
            .padding(14.dp)
    ) {
        if (showRemorse) {
            // Sailfish Silica RemorseItem Overlay (5-second cancelable countdown)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Closing ${cover.appName}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tap anywhere to cancel",
                    color = ambience.highlightColor,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { remorseProgress.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = ambience.highlightColor,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header: App Title + Pin indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cover.appName,
                        color = ambience.highlightColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (cover.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned Cover",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Body: Live Notification / Status Preview
                Column {
                    Text(
                        text = cover.statusHeadline,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = cover.statusSubtext,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Bottom Bar: Silica Cover Action Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { cover.primaryAction?.onTrigger?.invoke() ?: onLaunch() },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(ambience.highlightColor.copy(alpha = 0.18f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Cover Action",
                            tint = ambience.highlightColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { showRemorse = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Cover",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 6. MAIN LAUNCHER SCREEN (Events <-> Home HorizontalPager + App Grid Drawer)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SailfishLauncherRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentAmbienceIndex by remember { mutableIntStateOf(0) }
    val ambience = BuiltInAmbiences[currentAmbienceIndex]

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    val notifications by SailfishNotificationBridge.notifications.collectAsState()
    val liveSnippets by SailfishNotificationBridge.liveCoverSnippets.collectAsState()
    var closedCoverPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }

    // Pager states:
    // Horizontal: Page 0 = Events View, Page 1 = Home View (Active Covers)
    val horizontalPagerState = rememberPagerState(initialPage = 1, pageCount = { 2 })
    // Vertical: Page 0 = Workspace (Events/Home), Page 1 = Full App Grid
    val verticalPagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    LaunchedEffect(Unit) {
        installedApps = UnrootedSystemRepository.loadInstalledApps(context)
    }

    val activeCovers = remember(installedApps, liveSnippets, closedCoverPackages) {
        UnrootedSystemRepository.queryRecentAppsForCovers(
            context = context,
            installedApps = installedApps,
            liveSnippets = liveSnippets,
            maxCount = 8
        ).filterNot { closedCoverPackages.contains(it.packageName) }
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(ambience.primaryColor, ambience.secondaryColor)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .systemBarsPadding()
    ) {
        VerticalPager(
            state = verticalPagerState,
            modifier = Modifier.fillMaxSize()
        ) { verticalPage ->
            if (verticalPage == 0) {
                // Top Workspace: Events View (0) <-> Home Active Covers (1)
                HorizontalPager(
                    state = horizontalPagerState,
                    modifier = Modifier.fillMaxSize()
                ) { horizontalPage ->
                    if (horizontalPage == 0) {
                        EventsSurface(
                            ambience = ambience,
                            notifications = notifications,
                            onCycleAmbience = {
                                currentAmbienceIndex = (currentAmbienceIndex + 1) % BuiltInAmbiences.size
                                val nextAmb = BuiltInAmbiences[currentAmbienceIndex]
                                UnrootedSystemRepository.applyAmbienceRingerProfile(
                                    context,
                                    nextAmb.defaultProfile
                                )
                            },
                            onClearNotifications = {
                                SailfishNotificationBridge.clearAllNotifications()
                            },
                            onOpenNotificationSettings = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        )
                    } else {
                        HomeCoversSurface(
                            ambience = ambience,
                            covers = activeCovers,
                            onCycleAmbience = {
                                currentAmbienceIndex = (currentAmbienceIndex + 1) % BuiltInAmbiences.size
                                val nextAmb = BuiltInAmbiences[currentAmbienceIndex]
                                UnrootedSystemRepository.applyAmbienceRingerProfile(
                                    context,
                                    nextAmb.defaultProfile
                                )
                            },
                            onOpenAppDrawer = {
                                scope.launch { verticalPagerState.animateToPage(1) }
                            },
                            onRestoreCovers = {
                                closedCoverPackages = emptySet()
                            },
                            onCloseCover = { pkg ->
                                closedCoverPackages = closedCoverPackages + pkg
                            },
                            onLaunchApp = { pkg ->
                                UnrootedSystemRepository.launchApp(context, pkg)
                            }
                        )
                    }
                }
            } else {
                // Bottom Surface: Sailfish 4-Column App Grid
                AppGridSurface(
                    ambience = ambience,
                    apps = installedApps,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onLaunchApp = { pkg ->
                        UnrootedSystemRepository.launchApp(context, pkg)
                    },
                    onReturnHome = {
                        scope.launch { verticalPagerState.animateToPage(0) }
                    }
                )
            }
        }
    }
}

// ============================================================================
// 7. HOME SURFACE (Clock Header + 2xN Active Covers Grid + Pulley Menu)
// ============================================================================

@Composable
fun HomeCoversSurface(
    ambience: Ambience,
    covers: List<ActiveCoverItem>,
    onCycleAmbience: () -> Unit,
    onOpenAppDrawer: () -> Unit,
    onRestoreCovers: () -> Unit,
    onCloseCover: (String) -> Unit,
    onLaunchApp: (String) -> Unit
) {
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormatter = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    var currentTime by remember { mutableStateOf(timeFormatter.format(Date())) }
    var currentDate by remember { mutableStateOf(dateFormatter.format(Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            currentTime = timeFormatter.format(now)
            currentDate = dateFormatter.format(now)
            delay(15_000L)
        }
    }

    val pulleyItems = listOf(
        PulleyMenuItem("Switch Ambience (${ambience.name})") { onCycleAmbience() },
        PulleyMenuItem("Restore Closed Covers") { onRestoreCovers() },
        PulleyMenuItem("Grant Usage Access (Live Covers)") {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        },
        PulleyMenuItem("Open App Grid") { onOpenAppDrawer() }
    )

    SilicaPulleyMenuContainer(
        items = pulleyItems,
        highlightColor = ambience.highlightColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Silica Top Status & Clock Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = currentTime,
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        text = currentDate,
                        color = ambience.highlightColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Ambience Badge
                Surface(
                    color = ambience.surfaceOverlay,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable { onCycleAmbience() }
                ) {
                    Text(
                        text = "${ambience.name} • ${ambience.defaultProfile.label}",
                        color = ambience.highlightColor,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Active Covers 2-Column Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(covers, key = { it.packageName }) { cover ->
                    SilicaActiveCoverCard(
                        cover = cover,
                        ambience = ambience,
                        onLaunch = { onLaunchApp(cover.packageName) },
                        onCloseCover = { onCloseCover(cover.packageName) }
                    )
                }
            }

            // Bottom Edge Chevron Indicator for App Grid Swipe-Up
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAppDrawer() }
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Swipe up for App Grid",
                    tint = Color.White.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Swipe up for Apps • Swipe right for Events",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============================================================================
// 8. EVENTS VIEW SURFACE (Notifications, Ambience Toggles, Quick Controls)
// ============================================================================

@Composable
fun EventsSurface(
    ambience: Ambience,
    notifications: List<SailfishEventNotification>,
    onCycleAmbience: () -> Unit,
    onClearNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val context = LocalContext.current
    val hasNotifPerm = remember {
        UnrootedSystemRepository.hasNotificationListenerPermission(context)
    }

    val pulleyItems = listOf(
        PulleyMenuItem("Clear All Notifications") { onClearNotifications() },
        PulleyMenuItem("Switch Ambience") { onCycleAmbience() },
        PulleyMenuItem("Notification Listener Access") { onOpenNotificationSettings() }
    )

    SilicaPulleyMenuContainer(
        items = pulleyItems,
        highlightColor = ambience.highlightColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Events",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light
            )
            Text(
                text = "Pull down for menu • Swipe left for Home Covers",
                color = ambience.highlightColor.copy(alpha = 0.85f),
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!hasNotifPerm) {
                Surface(
                    color = ambience.surfaceOverlay,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNotificationSettings() }
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Enable Live Notifications & Media Covers",
                            color = ambience.highlightColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap to grant Notification Listener access so Events and Active Covers update in real time on unrooted Android.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active notifications",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(notifications, key = { it.key }) { item ->
                        Surface(
                            color = ambience.surfaceOverlay,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    UnrootedSystemRepository.launchApp(context, item.packageName)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.appName,
                                        color = ambience.highlightColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = item.title,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (item.text.isNotBlank()) {
                                        Text(
                                            text = item.text,
                                            color = Color.White.copy(alpha = 0.72f),
                                            fontSize = 13.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        SailfishNotificationBridge.dismissNotificationByKey(item.key)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 9. APP GRID SURFACE (4-Column Silica Launcher Drawer + Search Filter)
// ============================================================================

@Composable
fun AppGridSurface(
    ambience: Ambience,
    apps: List<InstalledApp>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onLaunchApp: (String) -> Unit,
    onReturnHome: () -> Unit
) {
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = {
                Text("Search applications...", color = Color.White.copy(alpha = 0.5f))
            },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = ambience.highlightColor)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onLaunchApp(app.packageName) }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                ) {
                    // Silica Squircle Icon Badge
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ambience.surfaceOverlay)
                            .border(
                                width = 1.dp,
                                color = ambience.highlightColor.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = app.label.take(2).uppercase(Locale.getDefault()),
                            color = ambience.highlightColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = app.label,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ============================================================================
// 10. MAIN ACTIVITY ENTRY POINT
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Intercept system back button so pressing Back on the home launcher stays on Home
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Keep user on launcher workspace
            }
        })

        setContent {
            MaterialTheme {
                SailfishLauncherRoot()
            }
        }
    }
}
