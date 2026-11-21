// Core 1.10 uses the AndroidX cancellation token for its Android 10 implementation.
@file:Suppress("DEPRECATION")

package rynfell.islom.shah

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rynfell.islom.shah.ui.theme.ShahTheme

private val mecca = Location("qibla").apply {
    latitude = 21.4225
    longitude = 39.8262
}

internal enum class LocationStatus { PermissionRequired, Locating, Disabled, Failed, Ready }

class MainActivity : ComponentActivity() {
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val headingSensor by lazy {
        CompassHeading(
            sensorManager = getSystemService(SensorManager::class.java),
            screenRotation = { window.decorView.display?.rotation ?: 0 },
            onHeading = ::updateHeading,
        )
    }

    private var locationStatus by mutableStateOf(LocationStatus.Locating)
    private var headingStatus by mutableStateOf(HeadingStatus.Waiting)
    private var bearing by mutableStateOf<Float?>(null)
    private var trueHeading by mutableStateOf<Float?>(null)
    private var placeName by mutableStateOf<String?>(null)
    private var aligned by mutableStateOf(false)
    private var magneticDeclination = 0f
    private var alignment = CompassAlignment()
    private var locationRequests = emptyList<CancellationSignal>()
    private var placeLookup: Job? = null
    private var prayerRefresh: Job? = null
    private var prayerState by mutableStateOf<PrayerScheduleState>(PrayerScheduleState.Loading)
    private var prayerRefreshing by mutableStateOf(false)
    private val prayerScheduleCache by lazy { PrayerScheduleCache(applicationContext) }
    private val prayerVisitState by viewModels<PrayerVisitViewModel>()
    private var selectedTab by mutableStateOf(ShahTab.Prayers)
    private val showCompass get() = selectedTab == ShahTab.Compass
    private val prayerNotifications by lazy { PrayerNotifications(applicationContext) }
    private var notificationSettings by mutableStateOf(PrayerNotificationSettings())
    private var notificationsAllowed by mutableStateOf(false)
    private var exactAlarmsAllowed by mutableStateOf(false)
    private var soundPreview: MediaPlayer? = null
    private var resumed = false
    private lateinit var soundPool: SoundPool
    private var alignmentSoundId = 0
    private var alignmentSoundReady = false

    private val permissions = arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
    private val permissionPreferences by lazy { getPreferences(MODE_PRIVATE) }
    private val permissionRequestedKey = "location_permission_requested"
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (resumed && showCompass) refreshLocation()
        }
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshNotificationAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = ShahTab.entries.getOrNull(savedInstanceState?.getInt("selected_tab") ?: 0)
            ?: ShahTab.Prayers
        notificationSettings = prayerNotifications.settings()
        enableEdgeToEdge()
        loadAlignmentSound()
        setContent {
            ShahTheme {
                ShahNavigation(selectedTab, ::selectTab) {
                    when (selectedTab) {
                        ShahTab.Compass -> CompassScreen(
                            locationStatus = locationStatus,
                            headingStatus = headingStatus,
                            bearing = bearing,
                            trueHeading = trueHeading,
                            aligned = aligned,
                            placeName = placeName,
                            onLocationAction = ::handleLocationAction,
                        )
                        ShahTab.Prayers -> PrayerScheduleScreen(prayerState, prayerRefreshing, ::refreshPrayerSchedule)
                        ShahTab.Settings -> NotificationSettingsScreen(
                            settings = notificationSettings,
                            notificationsAllowed = notificationsAllowed,
                            exactAlarmsAllowed = exactAlarmsAllowed,
                            onChange = ::changeNotificationSettings,
                            onAllowNotifications = ::allowNotifications,
                            onAllowExactAlarms = ::allowExactAlarms,
                            onPlaySound = ::playNotificationSound,
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_tab", selectedTab.ordinal)
        super.onSaveInstanceState(outState)
    }

    private fun selectTab(tab: ShahTab) {
        if (selectedTab == tab) return
        val wasCompass = showCompass
        selectedTab = tab
        if (!resumed) return
        if (wasCompass) stopCompass()
        if (showCompass) startCompass()
        if (tab != ShahTab.Settings) stopSoundPreview()
    }

    private fun refreshNotificationAccess() {
        notificationsAllowed = prayerNotifications.notificationsAllowed
        exactAlarmsAllowed = prayerNotifications.exactAlarmsAllowed
        prayerNotifications.sync()
    }

    private fun changeNotificationSettings(settings: PrayerNotificationSettings) {
        notificationSettings = settings
        prayerNotifications.updateSettings(settings)
        if (settings.enabled && !notificationsAllowed &&
            !permissionPreferences.getBoolean("asked_notifications", false)
        ) {
            allowNotifications()
        }
    }

    private fun allowNotifications() {
        prayerNotifications.createChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED &&
            (!permissionPreferences.getBoolean("asked_notifications", false) ||
                shouldShowRequestPermissionRationale(POST_NOTIFICATIONS))
        ) {
            permissionPreferences.edit { putBoolean("asked_notifications", true) }
            requestNotifications.launch(POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun allowExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    private fun playNotificationSound() {
        stopSoundPreview()
        soundPreview = MediaPlayer.create(this, R.raw.prayer_chime)?.apply {
            setOnCompletionListener { player ->
                if (soundPreview === player) stopSoundPreview()
            }
            start()
        }
    }

    private fun stopSoundPreview() {
        soundPreview?.release()
        soundPreview = null
    }

    private fun refreshPrayerSchedule() {
        if (prayerRefreshing) return
        val visit = prayerVisitState.visit ?: return
        prayerRefresh?.cancel()
        prayerRefresh = lifecycleScope.launch {
            while (true) {
                val started = Instant.now()
                val cached = prayerScheduleCache.read()
                val previous = prayerState as? PrayerScheduleState.Ready
                val keepVisible = previous?.day == cached?.day && cached?.usableUntil(visit, started) != null
                prayerRefreshing = true
                if (!keepVisible) prayerState = PrayerScheduleState.Loading

                var cacheExpiresAt: Instant? = null
                prayerState = try {
                    val day = loadPrayerSchedule()
                    val fetched = Instant.now()
                    if (day.mosqueSchedule.date != fetched.atZone(dushanbeZone).toLocalDate()) {
                        throw IOException("The site returned an outdated schedule")
                    }
                    prayerScheduleCache.save(day, fetched)
                    prayerNotifications.updateSchedule(day.mosqueSchedule)
                    PrayerScheduleState.Ready(day)
                } catch (_: IOException) {
                    cacheExpiresAt = cached?.usableUntil(visit, Instant.now())
                    if (cached != null && cacheExpiresAt != null) {
                        PrayerScheduleState.Ready(cached.day, fromCache = true)
                    } else {
                        PrayerScheduleState.Unavailable
                    }
                }
                prayerRefreshing = false
                val now = ZonedDateTime.now(dushanbeZone)
                if (prayerState == PrayerScheduleState.Unavailable &&
                    now.toLocalDate() != started.atZone(dushanbeZone).toLocalDate()
                ) continue
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay(dushanbeZone).toInstant()
                val nextRefresh = minOf(midnight, cacheExpiresAt ?: midnight)
                delay(Duration.between(now.toInstant(), nextRefresh).toMillis().coerceAtLeast(1))
            }
        }
    }

    override fun onDestroy() {
        soundPool.release()
        super.onDestroy()
    }

    private fun loadAlignmentSound() {
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == alignmentSoundId) alignmentSoundReady = status == 0
        }
        alignmentSoundId = soundPool.load(this, R.raw.qibla_aligned, 1)
    }

    override fun onStart() {
        super.onStart()
        if (prayerVisitState.visit == null) prayerVisitState.visit = prayerScheduleCache.beginVisit(Instant.now())
        refreshPrayerSchedule()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshNotificationAccess()
        if (showCompass) startCompass()
    }

    private fun startCompass() {
        refreshLocation()
        headingSensor.start()
        if (locationStatus == LocationStatus.PermissionRequired &&
            !permissionPreferences.getBoolean(permissionRequestedKey, false)
        ) {
            askForLocationPermission()
        }
    }

    override fun onPause() {
        resumed = false
        stopSoundPreview()
        if (showCompass) stopCompass()
        super.onPause()
    }

    override fun onStop() {
        prayerRefresh?.cancel()
        prayerRefreshing = false
        if (!isChangingConfigurations) prayerVisitState.visit = null
        super.onStop()
    }

    private fun stopCompass() {
        cancelLocationRequests()
        placeLookup?.cancel()
        headingSensor.stop()
    }

    private fun handleLocationAction() {
        when (locationStatus) {
            LocationStatus.PermissionRequired -> {
                if (permissionPreferences.getBoolean(permissionRequestedKey, false) &&
                    permissions.none { shouldShowRequestPermissionRationale(it) }
                ) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                } else {
                    askForLocationPermission()
                }
            }
            LocationStatus.Disabled -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            LocationStatus.Failed -> refreshLocation()
            else -> Unit
        }
    }

    private fun askForLocationPermission() {
        permissionPreferences.edit { putBoolean(permissionRequestedKey, true) }
        requestPermissions.launch(permissions)
    }

    @SuppressLint("MissingPermission")
    private fun refreshLocation() {
        cancelLocationRequests()
        placeLookup?.cancel()
        placeName = null
        bearing = null
        trueHeading = null
        aligned = false
        alignment = CompassAlignment()

        val precise = checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
        if (!precise && checkSelfPermission(ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED) {
            locationStatus = LocationStatus.PermissionRequired
            return
        }
        if (!locationManager.isLocationEnabled) {
            locationStatus = LocationStatus.Disabled
            return
        }

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
        }.filter { it in locationManager.allProviders && locationManager.isProviderEnabled(it) }

        if (providers.isEmpty()) {
            locationStatus = LocationStatus.Failed
            return
        }

        locationStatus = LocationStatus.Locating
        val requests = providers.map { CancellationSignal() }
        locationRequests = requests
        var pending = providers.size
        var bestAccuracy = Float.POSITIVE_INFINITY
        val receiveLocation: (Location?) -> Unit = { location ->
            if (locationRequests === requests) {
                pending--
                if (location != null) {
                    val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
                    if (accuracy < bestAccuracy) {
                        bestAccuracy = accuracy
                        magneticDeclination = GeomagneticField(
                            location.latitude.toFloat(),
                            location.longitude.toFloat(),
                            if (location.hasAltitude()) location.altitude.toFloat() else 0f,
                            System.currentTimeMillis(),
                        ).declination
                        bearing = normalizeDegrees(location.bearingTo(mecca))
                        locationStatus = LocationStatus.Ready
                        resolvePlace(location)
                    }
                }
                if (pending == 0) {
                    locationRequests = emptyList()
                    if (bearing == null) {
                        locationStatus = if (locationManager.isLocationEnabled) {
                            LocationStatus.Failed
                        } else {
                            LocationStatus.Disabled
                        }
                    }
                }
            }
        }

        providers.zip(requests).forEach { (provider, request) ->
            try {
                LocationManagerCompat.getCurrentLocation(
                    locationManager, provider, request, mainExecutor
                ) { receiveLocation(it) }
            } catch (_: IllegalArgumentException) {
                receiveLocation(null)
            } catch (_: SecurityException) {
                receiveLocation(null)
            }
        }
    }

    private fun cancelLocationRequests() {
        val requests = locationRequests
        locationRequests = emptyList()
        requests.forEach { it.cancel() }
    }

    private fun resolvePlace(location: Location) {
        placeLookup?.cancel()
        placeLookup = lifecycleScope.launch {
            val address = withTimeoutOrNull(12_000L) { findAddress(location) }
            val name = listOfNotNull(
                address?.countryName?.takeIf { it.isNotBlank() },
                address?.locality?.takeIf { it.isNotBlank() },
            ).distinct().joinToString(" · ")
            if (name.isNotEmpty()) {
                placeName = name
            } else if (placeName == null) {
                placeName = getString(R.string.place_unavailable)
            }
        }
    }

    private suspend fun findAddress(location: Location): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(applicationContext)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(
                        location.latitude, location.longitude, 1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                continuation.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                continuation.resume(null)
                            }
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun updateHeading(status: HeadingStatus, magneticHeading: Float?, timestamp: Long) {
        headingStatus = status
        val target = bearing
        val heading = if (magneticHeading != null && target != null) {
            normalizeDegrees(magneticHeading + magneticDeclination)
        } else {
            null
        }
        trueHeading = heading
        val error = if (heading != null && target != null) signedDegrees(target - heading) else null
        val shouldConfirm = alignment.update(error, timestamp)
        aligned = alignment.aligned

        if (shouldConfirm) {
            val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            window.decorView.performHapticFeedback(feedback)
            if (alignmentSoundReady) {
                soundPool.play(alignmentSoundId, 0.8f, 0.8f, 1, 0, 1f)
            }
        }
    }
}
