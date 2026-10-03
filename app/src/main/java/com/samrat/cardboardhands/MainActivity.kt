package com.samrat.cardboardhands

import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.systemBarsPadding
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import zone.ien.hig.CupertinoText
import zone.ien.hig.icons.CupertinoIcons
import zone.ien.hig.icons.filled.Cart
import zone.ien.hig.icons.filled.Gearshape
import zone.ien.hig.icons.filled.House
import zone.ien.hig.icons.filled.Person
import zone.ien.hig.theme.CupertinoTheme
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf(0)
    private var status by mutableStateOf<String?>(null)
    private var games by mutableStateOf<List<GameLibrary.Game>?>(null)
    private var busy by mutableStateOf<String?>(null)
    /** A game waiting for the user's decision: patch it, or install the patched build. */
    private var pendingPatch by mutableStateOf<GameLibrary.Game?>(null)
    private var ready by mutableStateOf<Ready?>(null)
    private var error by mutableStateOf<String?>(null)
    private var startAfterPermission = false
    /** Bumped on every resume, so dialogs re-check what is installed after the system uninstaller. */
    private var resumes by mutableStateOf(0)

    private var shizuku by mutableStateOf(VirtualScreen.Access.NOT_RUNNING)
    private var cinemaScene by mutableStateOf(CinemaActivity.SCENE_ROOM)
    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { shizuku = VirtualScreen.access() }
    }

    private var storeItems by mutableStateOf<List<GameStore.Item>?>(null)
    private var androidApps by mutableStateOf(false)
    private var languagePicker by mutableStateOf(false)

    // ---------------------------------------------------------------- Friends
    private var friendsProfile by mutableStateOf<Friends.Person?>(null)
    private var friendsMine by mutableStateOf<List<Friends.Person>>(emptyList())
    private var friendsAddedMe by mutableStateOf<List<Friends.Person>>(emptyList())
    private var friendsFound by mutableStateOf<List<Friends.Person>>(emptyList())
    private var friendsQuery by mutableStateOf("")
    private var friendsUsername by mutableStateOf("")
    private var friendsStatus by mutableStateOf<String?>(null)
    private var friendsLoading by mutableStateOf(false)

    private fun refreshFriends() {
        if (Account.current(this) == null) return
        friendsLoading = true
        Thread {
            val result = runCatching {
                val profile = Friends.myProfile(this)
                val mine = if (profile != null) Friends.mine(this) else emptyList()
                Triple(profile, mine, if (profile != null) Friends.addedMe(this, mine) else emptyList())
            }
            runOnUiThread {
                friendsLoading = false
                result.onSuccess { (profile, mine, addedMe) ->
                    friendsProfile = profile; friendsMine = mine; friendsAddedMe = addedMe; friendsStatus = null
                }.onFailure { friendsStatus = it.message }
            }
        }.start()
    }

    private fun friendsAction(action: () -> String?) {
        Thread {
            val error = runCatching { action() }.getOrElse { it.message }
            runOnUiThread { friendsStatus = error; refreshFriends() }
        }.start()
    }

    @Composable
    private fun FriendsTab() {
        HigPage(title = tr("Friends"), subtitle = tr("Add friends by username and call them as your Persona in VR"), bottomInset = TAB_BAR_ROOM) {
            if (Account.current(this@MainActivity) == null) {
                HigSection(footer = tr("Friends and calls need a PhoneXR account.")) {
                    HigLink(tr("Sign in")) { start(AccountActivity::class.java) }
                }
                return@HigPage
            }
            friendsStatus?.let { HigSection { HigRow(it) } }
            val profile = friendsProfile
            if (profile == null) {
                HigSection(title = tr("Your username"), footer = tr("3–20 characters: a–z, 0–9, _ and . Friends will find you by it.")) {
                    InputRow("username", friendsUsername) { friendsUsername = it.lowercase().replace(" ", "") }
                    HigLink(if (friendsLoading) tr("Loading…") else tr("Done"), enabled = !friendsLoading) {
                        val name = friendsUsername
                        friendsAction { Friends.setUsername(this@MainActivity, name) }
                    }
                }
                return@HigPage
            }
            HigSection { HigRow("@${profile.username}", profile.name) }
            HigSection(title = tr("Find by username")) {
                InputRow("@username", friendsQuery) { value ->
                    friendsQuery = value
                    Thread {
                        val found = runCatching { Friends.search(this@MainActivity, value) }.getOrDefault(emptyList())
                        runOnUiThread { if (friendsQuery == value) friendsFound = found }
                    }.start()
                }
                friendsFound.forEach { person ->
                    val added = friendsMine.any { it.id == person.id }
                    HigLink("@${person.username}", value = if (added) "✓" else tr("Add"), enabled = !added) {
                        friendsAction { Friends.add(this@MainActivity, person) }
                    }
                }
            }
            if (friendsAddedMe.isNotEmpty()) HigSection(title = tr("Added you")) {
                friendsAddedMe.forEach { person ->
                    HigLink("@${person.username}", value = tr("Add")) { friendsAction { Friends.add(this@MainActivity, person) } }
                }
            }
            HigSection(title = tr("My friends"), footer = tr("You can call from the Calls app in the headset while a friend is online.")) {
                if (friendsMine.isEmpty()) HigRow(tr("Nothing here yet"))
                friendsMine.forEach { person ->
                    val online = Calls.online.any { it.id == person.id }
                    HigLink("@${person.username}", value = if (online) tr("Online") else tr("Remove")) {
                        if (!online) friendsAction { Friends.remove(this@MainActivity, person) }
                    }
                }
            }
        }
    }

    /** A one-line text field in a section row. */
    @Composable
    private fun InputRow(hint: String, value: String, onChange: (String) -> Unit) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (value.isEmpty()) CupertinoText(hint, color = CupertinoTheme.colorScheme.secondaryLabel)
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = CupertinoTheme.colorScheme.label, fontSize = 17.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0xFF0A84FF)),
            )
        }
    }
    /** A VR mode from the store whose activation steps are shown. */
    private var guide by mutableStateOf<VrMode?>(null)
    private var webApps by mutableStateOf<List<WebApps.App>>(emptyList())
    private var installedWeb by mutableStateOf<Set<String>>(emptySet())
    private var storeError by mutableStateOf<String?>(null)
    private var storeLoading by mutableStateOf(false)
    /** Download progress per store path: 0..1, or -1 while the size is unknown. */
    private val downloads = mutableStateMapOf<String, Float>()
    private val storeIcons = mutableStateMapOf<String, Bitmap>()
    private val storeDescriptions = mutableStateMapOf<String, String>()

    /**
     * A patched APK ready to install. [replacesPackage] is set when the same package is installed
     * with a different signature, so the old copy must be removed first.
     */
    private data class Ready(val result: ApkPatcher.Result, val label: String?, val replacesPackage: String?)

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startAfterPermission) startTracking()
        else if (!granted) status = "Hand tracking needs access to the camera"
        startAfterPermission = false
    }
    private val scanCardboard = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val mm = result.data?.getIntExtra(CardboardQrActivity.EXTRA_IPD_MM, -1) ?: -1
        if (mm !in Settings.MIN_IPD_MM..Settings.MAX_IPD_MM) {
            error = "The Cardboard profile has no valid interpupillary distance"
        } else {
            ipd = mm
            Settings.setIpdMm(this, mm)
            status = "Cardboard profile applied: $mm mm"
        }
    }
    /** The VR home needs the camera (passthrough, hands) and the gallery (Spatial Photos). */
    private val enterVr = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // First start: the permissions are asked while the phone is still in the hand, then the
        // user has three seconds to put it in the headset.
        if (readyStep == 1) readyStep = 2 else startActivity(Intent(this, VrHomeActivity::class.java))
    }

    private fun vrPermissions() =
        if (BuildConfig.BE) arrayOf(Manifest.permission.RECORD_AUDIO)
        else if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.RECORD_AUDIO)
        else arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)

    /** First start: 0 choosing the headset, 1 asking for permissions, 2 "put the phone in the headset". */
    private var readyStep by mutableIntStateOf(0)
    /** The setup in VR has not been done yet: the phone only asks whether the user is ready. */
    private var needsSetup by mutableStateOf(false)
    /** An avatar model (.glb / .vrm) from the phone's files. */
    private val chooseAvatar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Thread {
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val error = bytes?.let { AvatarModel.save(this, it, AvatarModel.Source.FILE) } ?: tr("Couldn't read the file")
            runOnUiThread { status = error ?: tr("Avatar saved"); resumes++ }
        }.start()
    }

    /** A Minecraft mod from the phone's files. */
    private val chooseMod = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Thread {
            val problem = runCatching { MinecraftMods.installFromUri(this, uri) }.getOrElse { it.message }
            runOnUiThread { if (problem != null) error = problem }
        }.start()
    }
    private var mods by mutableStateOf<List<GameStore.Item>?>(null)
    private val modProgress = mutableStateMapOf<String, Int>()

    private fun installMod(item: GameStore.Item) {
        modProgress[item.path] = 0
        Thread {
            val file = runCatching {
                GameStore.download(item, MinecraftMods.folder(this)) { value ->
                    runOnUiThread { modProgress[item.path] = (value * 100).toInt().coerceAtLeast(0) }
                }
            }
            runOnUiThread {
                modProgress.remove(item.path)
                file.onSuccess { MinecraftMods.install(this, it)?.let { problem -> error = problem } }
                    .onFailure { error = "“${item.title}” didn't download" }
            }
        }.start()
    }

    private val chooseApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) patch(uri, replaces = null)
    }
    private var pendingPxr: File? = null
    private val savePxr = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = pendingPxr.also { pendingPxr = null }
        if (uri != null && source != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
        }.onFailure { error = "Couldn't save the .pxr" }
    }
    private val chooseForPxr = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = "Building the .pxr…"
        Thread {
            runCatching {
                val payload = PxrPackage.androidPayload(this, uri)
                val prepared = ApkPatcher.patch(this, payload).apk
                PxrPackage.packAndroid(this, prepared, "PhoneXR-app")
            }.onSuccess { file ->
                runOnUiThread { busy = null; pendingPxr = file; savePxr.launch(file.name) }
            }.onFailure { failure ->
                runOnUiThread { busy = null; error = failure.localizedMessage ?: "Couldn't build the .pxr" }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt(KEY_TAB) ?: 0
        L10n.init(this)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)
        setContent { PhoneXRTheme { Root() } }
    }

    /**
     * The first thing a new user sees: "Choose your headset" and the headsets; then "put the phone in the headset"
     * with three seconds counted down, and the VR setup (in the Quest style, with the account)
     * starts by itself.
     */
    @Composable
    private fun Ready() {
        val colors = androidx.compose.material3.MaterialTheme.colorScheme
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.surfaceContainerLowest, colors.surface))
            ).systemBarsPadding().padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            if (readyStep < 2) {
                // Which headset: its lenses decide where each eye's picture goes.
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Text(
                        tr("Choose your headset"), color = colors.onSurface, fontSize = 34.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)
                    )
                    androidx.compose.foundation.lazy.LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(Headsets.ALL.size) { i ->
                            val headset = Headsets.ALL[i]
                            androidx.compose.material3.Surface(
                                onClick = {
                                    Headsets.choose(this@MainActivity, headset)
                                    readyStep = 1
                                    enterVr.launch(vrPermissions())
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = colors.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                                    androidx.compose.material3.Text(tr(headset.name), color = colors.onSurface, fontSize = 18.sp)
                                    androidx.compose.material3.Text(
                                        tr("Lenses") + ": ${headset.lensesMm} " + tr("mm") + " · ${headset.fovDeg.roundToInt()}°",
                                        color = colors.onSurfaceVariant, fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                var left by remember { mutableIntStateOf(3) }
                LaunchedEffect(Unit) {
                    // While the language list is open the countdown waits.
                    while (left > 0) { kotlinx.coroutines.delay(1000); if (!languagePicker) left-- }
                    readyStep = 0
                    startActivity(Intent(this@MainActivity, VrHomeActivity::class.java))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    androidx.compose.material3.Text(
                        tr("Put the phone in the VR headset"), color = colors.onSurface, fontSize = 30.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    androidx.compose.material3.Text(if (left > 0) "$left" else "", color = colors.onSurfaceVariant, fontSize = 64.sp)
                }
            }
            // The language is chosen right here, on the first screen: before this the interface
            // was only in Russian and the picker in Settings is not reachable until setup is over.
            androidx.compose.material3.Surface(
                onClick = { languagePicker = true },
                shape = RoundedCornerShape(50),
                color = colors.surfaceContainerHigh,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.Language, null,
                        tint = colors.onSurface, modifier = Modifier.size(20.dp)
                    )
                    androidx.compose.material3.Text(L10n.current.title, color = colors.onSurface, fontSize = 16.sp)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        // First start: everything (setup and the account) happens in VR, after "Are you ready?".
        needsSetup = !Settings.setupDone(this)
        // Not back to "Are you ready?" here: the permission dialog also ends in onResume, right after
        // the answer moved on to the countdown. The countdown itself goes back to 0 when VR starts.
        if (needsSetup) return
        // PhoneXR opens only with an account (BE has no friends or calls, so no account either).
        if (!BuildConfig.BE && Account.current(this) == null) {
            startActivity(Intent(this, AccountActivity::class.java).putExtra(AccountActivity.EXTRA_REQUIRED, true))
            return
        }
        resumes++
        androidApps = BuildConfig.LITE || AndroidAppsContent.enabled(this)
        if (BuildConfig.LITE) AndroidAppsContent.setEnabled(this, true)
        screenShape = Settings.screenShape(this)
        curvedScreen = Settings.curvedScreen(this)
        refresh = Settings.refresh(this)
        keyboardWindow = Settings.keyboardWindow(this)
        depthInstalled = DepthModel.installed(this)
        ipd = Settings.ipdMm(this)
        lensOffset = Settings.lensOffsetMm(this)
        sixDof = Settings.load(this).sixDof
        trackingSmoothness = Settings.load(this).trackingSmoothness
        clipboard = Settings.sharedClipboard(this)
        travel = Settings.travelMode(this)
        guest = Settings.guestMode(this)
        if (clipboard) SharedClipboard.start(this)
        refreshGames()
        checkUpdateOnce()
        keepRuntimeInstalled()
        shizuku = VirtualScreen.access()
    }

    private var runtimeChecked = false

    /** Once per start: PhoneXR's own OpenXR runtime is installed or updated quietly, if it can be. */
    private fun keepRuntimeInstalled() {
        if (runtimeChecked || BuildConfig.LITE) return
        runtimeChecked = true
        Thread {
            if (!VrApiDriver.ready(this)) {
                val problem = runCatching { VrApiDriver.installQuietly(this) }.getOrElse { it.localizedMessage }
                runOnUiThread { resumes++; if (problem == null) status = "VrApi driver installed: Quest and Gear VR games run without patching" }
            }
            if (PhoneXrRuntime.state(this) == PhoneXrRuntime.State.READY) return@Thread
            val problem = runCatching { PhoneXrRuntime.installQuietly(this) }.getOrElse { it.localizedMessage }
            runOnUiThread { resumes++; if (problem == null) status = "PhoneXR Runtime installed: OpenXR games run without patching" }
        }.start()
    }

    private fun refreshGames() {
        Thread {
            val found = runCatching { GameLibrary.scan(this) }.getOrDefault(emptyList())
            runOnUiThread { games = found }
        }.start()
    }

    @Composable
    private fun Root() {
        if (needsSetup) {
            Ready()
            LanguagePicker()
            return
        }
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                if (BuildConfig.BE) { if (tab == 0) MenuTab() else SettingsTab() } else when (tab) {
                    0 -> MenuTab()
                    1 -> StoreTab()
                    2 -> FriendsTab()
                    else -> SettingsTab()
                }
            }
            HigTabBar(
                tabs = if (BuildConfig.BE) listOf(
                    HigTab(androidx.compose.material.icons.Icons.Rounded.Home, tr("Menu")),
                    HigTab(androidx.compose.material.icons.Icons.Rounded.Settings, tr("Settings"))
                ) else listOf(
                    HigTab(androidx.compose.material.icons.Icons.Rounded.Home, tr("Menu")),
                    HigTab(androidx.compose.material.icons.Icons.Rounded.ShoppingBag, tr("Store")),
                    HigTab(androidx.compose.material.icons.Icons.Rounded.People, tr("Friends")),
                    HigTab(androidx.compose.material.icons.Icons.Rounded.Settings, tr("Settings"))
                ),
                selected = tab,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSelect = { selectTab(it) }
            )
        }

        pendingPatch?.let { PatchDialog(it) }
        guide?.let { GuideDialog(it) }
        LanguagePicker()
        update?.let { found ->
            HigAlert(
                title = "PhoneXR ${found.version} is available",
                message = Updates.formatSize(found.size),
                actions = listOf(
                    HigAction(tr("Later"), HigActionStyle.CANCEL) { update = null },
                    HigAction(tr("Details")) { update = null; start(UpdateActivity::class.java) }
                ),
                onDismiss = { update = null }
            )
        }
        ready?.let { ReadyDialog(it) }
        error?.let { message ->
            HigAlert(
                title = tr("Something went wrong"),
                message = message,
                actions = listOf(HigAction("OK") { error = null }),
                onDismiss = { error = null }
            )
        }
    }

    /**
     * The language list: the same one on the first screen and in Settings → Language. Chosen here, it
     * applies to the whole interface at once: the activity is built again in the new language.
     */
    @Composable
    private fun LanguagePicker() {
        if (!languagePicker) return
        HigAlert(
            title = tr("Language"),
            message = "PhoneXR",
            actions = L10n.Lang.values().map { lang ->
                HigAction((if (lang == L10n.current) "✓ " else "") + lang.title) {
                    languagePicker = false
                    L10n.set(this@MainActivity, lang)
                    recreate()
                }
            } + HigAction(tr("Cancel"), HigActionStyle.CANCEL) { languagePicker = false },
            onDismiss = { languagePicker = false }
        )
    }

    private var updateChecked = false
    private var update by mutableStateOf<Updates.Release?>(null)

    /** Automatic updates: once per start, a new version opens the update screen. */
    private fun checkUpdateOnce() {
        if (updateChecked || !Updates.autoUpdate(this)) return
        updateChecked = true
        Thread {
            val found = runCatching { Updates.check(this) }.getOrNull() ?: return@Thread
            runOnUiThread { update = found }
        }.start()
    }

    private fun selectTab(index: Int) {
        tab = index
        if (index == 2) refreshFriends()
        if (index == 1 && storeItems == null && !storeLoading) refreshStore()
    }

    private fun scanCardboardProfile() {
        scanCardboard.launch(Intent(this, CardboardQrActivity::class.java))
    }

    // ---------------------------------------------------------------- Menu

    @Composable
    private fun MenuTab() {
        HigPage(
            title = "PhoneXR",
            subtitle = "VR on your phone: hands in the camera, Joy‑Con instead of controllers",
            bottomInset = TAB_BAR_ROOM
        ) {
            if (BuildConfig.BE) {
                HigSection(footer = tr("No camera needed: aim the dot in the center at a button and tap the phone screen.")) {
                    HigLink(tr("Enter VR")) { enterVr.launch(vrPermissions()) }
                }
                return@HigPage
            }
            if (!sixDof) HigSection(
                title = "3DoF is active right now",
                footer = "Head rotation and controllers work, but walking around the room, the boundary, walls, tables and room physics need 6DoF."
            ) {
                HigLink("Turn on 6DoF") {
                    if (BuildConfig.LITE) error = "6DoF is available in PhoneXR Full"
                    else {
                        sixDof = true
                        Settings.save(this@MainActivity, Settings.load(this@MainActivity).copy(sixDof = true))
                    }
                }
            }
            HigSection(footer = "The VR home in mixed reality: a pointer ray comes out of your hand, pinch to press; a fist at the left or right edge of a window moves it; Joy‑Con: ZR or A.") {
                HigLink(tr("Enter VR")) {
                    enterVr.launch(vrPermissions())
                }
            }

            HigSection(
                title = tr("Games"),
                footer = "Tap a game to turn on tracking and launch it. " +
                    "Gear VR games and headset builds have to be patched first: PhoneXR opens the PhoneXR " +
                    "runtime for them, writes in the new tracking and optimizes them for the phone."
            ) {
                val list = games
                when {
                    list == null -> HigRow("Looking for games…", trailing = { HigSpinner() })
                    list.isEmpty() -> HigRow("No VR games found", "Install a game from the store or from a file")
                    else -> list.forEach { game -> GameRow(game) }
                }
            }

            HigSection(
                title = tr("Install"),
                footer = "An OpenXR game APK, Gear VR games (64- and 32-bit) or a .pxr package. " +
                    "PhoneXR writes in the new tracking, optimizes the build for the phone, signs it and opens the installer."
            ) {
                HigLink(busy ?: tr("Install a game from a file"), enabled = busy == null) {
                    chooseApk.launch(
                        arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream")
                    )
                }
                HigLink(tr("Game store")) { selectTab(1) }
                HigLink("Build a .pxr from an APK", enabled = busy == null) {
                    chooseForPxr.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                }
            }

            RuntimeSection()

            HigSection(
                title = "Daydream",
                footer = "Daydream games look for Google VR Services. PhoneXR installs Opendream Services 1.13 — " +
                    "after that Daydream and Cardboard games show up in the game list and in the VR home."
            ) {
                if (Daydream.servicesInstalled(this@MainActivity)) {
                    HigRow("VR Services", "Opendream installed")
                } else {
                    HigLink("Install Opendream Services") { Daydream.installServices(this@MainActivity) }
                }
            }

            HigSection(title = tr("Tracking"), footer = status) {
                HigLink(tr("Stop tracking")) {
                    stopService(Intent(this@MainActivity, HandTrackingService::class.java))
                    status = "Tracking stopped"
                }
            }
        }
    }

    /** PhoneXR Runtime for OpenXR games: install or update it, then pick it in the OpenXR broker. */
    @Composable
    private fun RuntimeSection() {
        val state = if (resumes >= 0) PhoneXrRuntime.state(this) else PhoneXrRuntime.State.MISSING
        HigSection(
            title = "OpenXR",
            footer = "PhoneXR Runtime is OpenXR on the phone: games get hands, Joy‑Con and head from PhoneXR. " +
                "It is its own OpenXR broker, so Android XR (Galaxy XR), Pico and newer Quest games with the Khronos loader " +
                "run without patching. A separate OpenXR Runtime Broker is not needed — if it is installed, remove it, otherwise it hijacks games. " +
                "Older Quest and Gear VR games (VrApi) also run unpatched — through the PhoneXR VrApi driver."
        ) {
            when (state) {
                PhoneXrRuntime.State.READY -> HigRow("PhoneXR Runtime", "Installed")
                PhoneXrRuntime.State.OUTDATED -> HigLink("Update PhoneXR Runtime") { PhoneXrRuntime.install(this@MainActivity) }
                PhoneXrRuntime.State.MISSING -> if (PhoneXrRuntime.bundled(this@MainActivity)) {
                    HigLink("Install PhoneXR Runtime") { PhoneXrRuntime.install(this@MainActivity) }
                } else {
                    HigRow("PhoneXR Runtime", "Not part of this build")
                }
            }
            if (VrApiDriver.ready(this@MainActivity)) HigRow("VrApi driver", "Installed")
            else if (VrApiDriver.bundled(this@MainActivity)) HigLink("Install the VrApi driver", value = "Quest and Gear VR games without patching") {
                VrApiDriver.install(this@MainActivity)
            }
            if (PhoneXrRuntime.brokerInstalled(this@MainActivity)) {
                HigLink("Remove OpenXR Runtime Broker", value = "not needed") { uninstall("org.khronos.openxr.runtime_broker") }
            }
        }
    }

    private fun openCinema(target: String, scene: String = cinemaScene) {
        startActivity(
            Intent(this, CinemaActivity::class.java)
                .putExtra(CinemaActivity.EXTRA_PACKAGE, target)
                .putExtra(CinemaActivity.EXTRA_SCENE, scene)
        )
    }

    @Composable
    private fun HigScope.GameRow(game: GameLibrary.Game) {
        HigItem(
            title = game.label,
            details = listOf(GameLibrary.describe(game) + if (game.checksPurchase) " · Oculus purchase check" else ""),
            detailColor = if (game.kind == GameLibrary.Kind.VRAPI_ORIGINAL && !VrApiDriver.ready(this@MainActivity))
                HigColors.accent else Color.Unspecified,
            icon = { AppIcon(game.packageName) },
            onClick = { open(game) }
        )
    }

    @Composable
    private fun AppIcon(packageName: String) {
        val drawable: Drawable? = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        Canvas(modifier = Modifier.size(30.dp)) {
            drawIntoCanvas { canvas ->
                drawable?.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawable?.draw(canvas.nativeCanvas)
            }
        }
    }

    // ---------------------------------------------------------------- Store

    @Composable
    private fun StoreTab() {
        HigPage(
            title = tr("Store"),
            subtitle = "OpenXR, Quest and Pico: PhoneXR downloads and prepares the APK itself.",
            bottomInset = TAB_BAR_ROOM
        ) {
            HigSection(
                title = tr("VR modes"),
                footer = "Regular Minecraft and Roblox on a big screen in VR: your own room, head rotation, " +
                    "playing with both hands or a Joy‑Con. Tap a mode to see the instructions."
            ) {
                VR_MODES.forEach { mode -> VrModeRow(mode) }
            }
            HigSection(
                title = tr("PhoneXR apps"),
                footer = "Android apps: any phone app as a window in VR (needs Shizuku). Shows up on the VR home screen."
            ) {
                val added = resumes >= 0 && androidApps
                HigLink(tr("Android apps"), value = if (BuildConfig.LITE) "On" else if (added) tr("Remove") else tr("Get")) {
                    if (!BuildConfig.LITE) {
                        androidApps = !added
                        AndroidAppsContent.setEnabled(this@MainActivity, !added)
                    }
                }
            }
            HigSection(
                title = tr("Minecraft mods"),
                footer = tr("Add-ons, resource packs and worlds for Minecraft Bedrock (.mcaddon, .mcpack, .mcworld). " +
                    "Minecraft imports the mod itself, then turn it on in the world settings.")
            ) {
                HigLink("PhoneXR VR — VR for Minecraft", value = if (resumes >= 0 && MinecraftBridge.modInstalled(this@MainActivity)) "✓" else tr("Install")) {
                    MinecraftBridge.installMod(this@MainActivity)?.let { error = it }
                }
                HigLink(tr("Install a mod from a file")) { chooseMod.launch(arrayOf("*/*")) }
                mods?.forEach { item ->
                    val progress = modProgress[item.path]
                    HigLink(item.title, value = progress?.let { "$it%" } ?: tr("Install"), enabled = progress == null) { installMod(item) }
                }
            }
            val items = storeItems
            HigSection(
                title = tr("Games"),
                footer = when {
                    storeError != null -> storeError
                    items != null && items.isEmpty() -> GameStore.EMPTY_HINT
                    else -> null
                }
            ) {
                when {
                    storeLoading && items == null -> HigRow(tr("Loading…"), trailing = { HigSpinner() })
                    items.isNullOrEmpty() -> HigRow(if (storeError != null) "The store is unavailable" else tr("Nothing here yet"))
                    else -> items.forEach { item -> StoreRow(item) }
                }
            }
            if (webApps.isNotEmpty()) {
                HigSection(
                    title = tr("Web apps"),
                    footer = "They open in the PhoneXR browser right in VR. Added ones show up on the VR home screen."
                ) {
                    webApps.forEach { app ->
                        val added = app.url in installedWeb
                        HigLink(app.name, value = if (added) tr("Open") else tr("Add")) {
                            if (added) {
                                if (!WebApps.open(this@MainActivity, app.url)) error = "Install the PhoneXR browser"
                            } else {
                                WebApps.add(this@MainActivity, app)
                                installedWeb = installedWeb + app.url
                            }
                        }
                    }
                }
            }
            HigSection {
                HigLink(if (storeLoading) tr("Refreshing…") else tr("Refresh"), enabled = !storeLoading) { refreshStore() }
            }
        }
    }

    @Composable
    private fun HigScope.VrModeRow(mode: VrMode) {
        val installed = resumes >= 0 && isInstalled(mode.packageName)
        HigItem(
            title = mode.title,
            details = listOf(mode.subtitle),
            icon = { AppIcon(if (installed) mode.packageName else packageName) },
            trailing = { HigText(if (installed) tr("Play") else tr("Download"), color = HigColors.accent) },
            onClick = { guide = mode }
        )
    }

    /** How to switch a VR mode on: the game from Google Play, Shizuku, then play from PhoneXR. */
    @Composable
    private fun GuideDialog(mode: VrMode) {
        val installed = resumes >= 0 && isInstalled(mode.packageName)
        val ready = shizuku == VirtualScreen.Access.READY
        val steps = listOf(
                        (if (installed) "✓ " else "1. ") + "Install ${mode.game} from Google Play.",
                        (if (ready) "✓ " else "2. ") + "Install and start Shizuku (over Wi‑Fi debugging), then allow PhoneXR access.",
                        "3. Tap “Play”: the game opens on a big screen — ${mode.scene}.",
                        if (mode.packageName == MINECRAFT)
                            "4. The PhoneXR VR mod installs itself into Minecraft on the first “Play”. In Minecraft: Settings → General → " +
                                "turn off “Require encrypted websockets”; in the world turn on cheats and the “PhoneXR VR” behavior pack. " +
                                "In the world hold your palm towards your face and bring your fingers together — PhoneXR connects the mod. Then: head — a 360° look, " +
                                "hands are visible in the world, fist — break and hit, pinch — place a block, a finger “gun” — walk."
                        else "4. Controls: a pinch of either hand taps the screen, two hands — two fingers. " +
                            "Joy‑Con and gamepads work just like in the game itself. A tap on the phone recenters the view.",
        )
        val next = when {
            !installed -> HigAction(tr("Download")) {
                guide = null
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${mode.packageName}")))
            }
            !ready -> HigAction("Shizuku") {
                guide = null
                if (shizuku == VirtualScreen.Access.NEEDS_PERMISSION) VirtualScreen.requestPermission()
                else packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                    ?: startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
            }
            mode.packageName == MINECRAFT && !MinecraftBridge.modInstalled(this@MainActivity) -> HigAction(tr("Install mod")) {
                guide = null
                // First time: Minecraft imports the VR mod, then "Play" starts VR.
                MinecraftBridge.installMod(this@MainActivity)?.let { error = it }
            }
            else -> HigAction(tr("Play")) {
                guide = null
                openCinema(mode.packageName, mode.sceneId)
            }
        }
        HigAlert(
            title = "How to turn on ${mode.title}",
            message = steps.joinToString("\n"),
            actions = listOf(HigAction(tr("Close"), HigActionStyle.CANCEL) { guide = null }, next),
            onDismiss = { guide = null }
        )
    }

    @Composable
    private fun HigScope.StoreRow(item: GameStore.Item) {
        val progress = downloads[item.path]
        HigItem(
            title = item.title,
            details = listOfNotNull(
                storeDescriptions[item.path],
                item.extension.uppercase() + if (item.size > 0) " · " + formatSize(item.size) else ""
            ),
            enabled = progress == null,
            icon = { StoreIcon(item) },
            trailing = {
                when {
                    progress == null -> HigText(tr("Get"), color = HigColors.accent)
                    progress < 0f -> HigSpinner()
                    progress >= 1f -> HigText("Preparing…")
                    else -> HigText("${(progress * 100).roundToInt()}%")
                }
            },
            onClick = { if (progress == null && busy == null) downloadAndInstall(item) }
        )
    }

    @Composable
    private fun StoreIcon(item: GameStore.Item) {
        val icon = storeIcons[item.path]
        if (icon != null) {
            Image(
                icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                AppIcon(packageName)
            }
        }
    }

    private fun refreshStore() {
        storeLoading = true
        storeError = null
        Thread {
            try {
                val items = GameStore.list()
                val foundMods = runCatching { GameStore.mods() }.getOrDefault(emptyList())
                runOnUiThread { mods = foundMods }
                val web = WebApps.fromStore()
                runOnUiThread {
                    webApps = web
                    installedWeb = WebApps.installed(this).map { it.url }.toSet()
                    storeItems = items
                    storeLoading = false
                }
                for (item in items) {
                    GameStore.description(item)?.let { text -> runOnUiThread { storeDescriptions[item.path] = text } }
                    GameStore.icon(item)?.let { bitmap -> runOnUiThread { storeIcons[item.path] = bitmap } }
                }
            } catch (failure: Throwable) {
                android.util.Log.e("PhoneXR-Store", "Store listing failed", failure)
                runOnUiThread {
                    storeLoading = false
                    storeError = failure.localizedMessage ?: "The store is unavailable"
                }
            }
        }.start()
    }

    private fun downloadAndInstall(item: GameStore.Item) {
        // Internal installs use the same shell service that runs phone apps in PhoneXR windows.
        if (VirtualScreen.access() != VirtualScreen.Access.READY) {
            error = "Installing inside PhoneXR needs Shizuku running with access allowed for PhoneXR."
            return
        }
        downloads[item.path] = 0f
        Thread {
            try {
                // Downloads live in files, not in the cache: Android empties the cache when the
                // phone runs low on space, and a game would disappear mid-download.
                val file = GameStore.download(item, File(filesDir, "store")) { value ->
                    runOnUiThread { downloads[item.path] = value }
                }
                // Quest, Pico and generic OpenXR APKs are prepared automatically inside PhoneXR.
                // A .pxr already carries a prepared payload and only needs unpacking.
                val apk = if (item.extension == "pxr") PxrPackage.androidApk(this, Uri.fromFile(file))
                else ApkPatcher.patch(this, Uri.fromFile(file)).apk
                runOnUiThread {
                    downloads.remove(item.path)
                    installApk(apk)
                }
            } catch (failure: Throwable) {
                android.util.Log.e("PhoneXR-Store", "Download failed: ${item.path}", failure)
                runOnUiThread {
                    downloads.remove(item.path)
                    error = "“${item.title}” didn't download: " +
                        (failure.localizedMessage ?: failure.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    // ---------------------------------------------------------------- Settings

    private var screenShape by mutableStateOf(Settings.ScreenShape.NORMAL)
    private var curvedScreen by mutableStateOf(true)
    private var refresh by mutableStateOf(Settings.Refresh.AUTO)
    private var keyboardWindow by mutableStateOf(true)
    private var ipd by mutableStateOf(Settings.DEFAULT_IPD_MM)
    private var lensOffset by mutableStateOf(0)
    private var sixDof by mutableStateOf(true)
    private var trackingSmoothness by mutableStateOf(50)
    private var clipboard by mutableStateOf(false)
    private var travel by mutableStateOf(false)
    private var guest by mutableStateOf(false)
    private val spatialAudio: String
        get() = if (android.os.Build.VERSION.SDK_INT >= 32 &&
            getSystemService(android.media.AudioManager::class.java)?.spatializer?.isAvailable == true
        ) "Your phone supports it — on for video and cinema" else "Your phone doesn't support it"
    private var depthInstalled by mutableStateOf(false)
    private var depthProgress by mutableStateOf<Float?>(null)

    /** The depth network is tens of megabytes: it is fetched once, with the progress in plain sight. */
    private fun downloadDepth() {
        if (depthProgress != null) return
        depthProgress = 0f
        Thread {
            val result = runCatching {
                DepthModel.download(this) { value -> runOnUiThread { depthProgress = value } }
            }
            runOnUiThread {
                depthProgress = null
                result.onSuccess { depthInstalled = true }
                    .onFailure { error = "The neural network didn't download: ${it.localizedMessage ?: it.javaClass.simpleName}" }
            }
        }.start()
    }

    @Composable
    private fun SettingsTab() {
        val rates = remember { DisplayRate.available(this) }
        HigPage(title = tr("Settings"), bottomInset = TAB_BAR_ROOM) {
            HigSection(title = tr("Controls")) {
                HigLink(tr("Controls")) { start(SettingsActivity::class.java) }
            }
            HigSection(
                title = "Head tracking",
                footer = "3DoF tracks rotation. 6DoF sees the room through the camera (ARCore): you can walk and the boundary and the table work. " +
                    "Without ARCore, 6DoF gives head tilts and body movement from the sensors."
            ) {
                HigChoice("3DoF", "Head rotation", !sixDof) {
                    sixDof = false
                    Settings.save(this@MainActivity, Settings.load(this@MainActivity).copy(sixDof = false))
                }
                HigChoice("6DoF", "Rotation and walking around the room", sixDof) {
                    sixDof = true
                    Settings.save(this@MainActivity, Settings.load(this@MainActivity).copy(sixDof = true))
                }
            }
            if (BuildConfig.LITE) HigSection(
                title = "Edition",
                footer = "Lite leaves out the Elix voice assistant, the depth neural network and 6DoF through " +
                    "ARCore, and reads the hand camera at a smaller frame — that is how it runs on budget phones. " +
                    "Games, the cinema, Joy‑Con and the VR home work the same."
            ) {
                HigRow("PhoneXR Lite", "Lightweight build", detailColor = HigColors.accent)
            }
            HigSection(
                title = tr("Avatar"),
                footer = "Make an avatar from a selfie in Avaturn (tap “Download” there — PhoneXR picks the model up itself) " +
                    "or a character in the VRoid app from Google Play: export it as .vrm and load the file here."
            ) {
                val source = if (resumes >= 0) AvatarModel.source(this@MainActivity) else AvatarModel.Source.STANDARD
                HigRow(tr("Current"), if (source == AvatarModel.Source.STANDARD) tr("Standard") else source.title)
                HigLink(tr("Create in Avaturn")) { startActivity(Intent(this@MainActivity, AvatarWebActivity::class.java)) }
                HigLink(tr("Make in VRoid"), value = "Google Play") {
                    // VRoid makes the character in its own app; PhoneXR then takes the exported .vrm.
                    AvatarModel.openVroid(this@MainActivity)
                    status = tr("Make a character in VRoid, export it as .vrm and tap “Load a .glb / .vrm file”")
                }
                HigLink(tr("Load a .glb / .vrm file")) { chooseAvatar.launch(arrayOf("*/*")) }
                if (source != AvatarModel.Source.STANDARD) HigLink(tr("Back to standard")) { AvatarModel.reset(this@MainActivity); resumes++ }
            }
            HigSection(
                title = "Multi-device",
                footer = "Shared clipboard: copy text on one phone — paste it on the other. " +
                    "Both must be on the same Wi‑Fi network."
            ) {
                HigSwitchRow("Shared clipboard", clipboard) {
                    clipboard = it
                    Settings.setSharedClipboard(this@MainActivity, it)
                    if (it) SharedClipboard.start(this@MainActivity) else SharedClipboard.stop()
                }
                HigLink("Send the clipboard to the other phone") {
                    val sent = SharedClipboard.send(this@MainActivity)
                    status = if (sent == null) "The clipboard is empty" else "Sent: ${sent.take(40)}"
                }
            }
            HigSection(
                title = "System",
                footer = "Car mode: turns on 3DoF so the view does not drift with the turns of a car or a train, while menus and windows " +
                    "follow your gaze smoothly. Guest mode keeps someone else's calibration and settings apart from yours."
            ) {
                HigSwitchRow(tr("Car mode"), travel) {
                    travel = it
                    Settings.setTravelMode(this@MainActivity, it)
                }
                HigSwitchRow("Guest mode", guest) {
                    guest = it
                    Settings.setGuestMode(this@MainActivity, it)
                }
                HigRow("Spatial audio", spatialAudio, detailColor = HigColors.secondary)
            }
            HigSection(
                title = "Hand tracking smoothness",
                footer = "0 — the lowest latency but more jitter. 100 — the smoothest hands but a softer response."
            ) {
                HigStepper("Smoothing", "$trackingSmoothness%") { step ->
                    trackingSmoothness = (trackingSmoothness + step * 5).coerceIn(0, 100)
                    val current = Settings.load(this@MainActivity)
                    Settings.save(this@MainActivity, current.copy(trackingSmoothness = trackingSmoothness))
                }
            }
            HigSection(
                title = "Second phone",
                footer = "The second phone becomes a pointer for the cinema: it clicks wherever you aim it. " +
                    "Open this screen on it, with both phones on the same Wi‑Fi network."
            ) {
                HigLink("Make this phone a controller") { start(ControllerActivity::class.java) }
            }
            HigSection(title = tr("Store"), footer = "Games come from the “${GameStore.FOLDER}” folder in Supabase and from the .json files in the root of the PhoneXR repository on GitHub.") {
                HigRow(tr("Server"), GameStore.URL_BASE.removePrefix("https://"))
            }
            HigSection(
                title = "Lenses and eyes",
                footer = "If the picture doubles in the headset, first set the interpupillary distance " +
                    "(the gap between your pupils), then the offset: it shifts the screen halves under the lenses. " +
                    "The settings apply the next time you enter VR."
            ) {
                HigStepper("Interpupillary distance", "$ipd mm") { step ->
                    ipd = (ipd + step).coerceIn(Settings.MIN_IPD_MM, Settings.MAX_IPD_MM)
                    Settings.setIpdMm(this@MainActivity, ipd)
                }
                HigLink("Scan the headset QR", value = "Google Cardboard") { scanCardboardProfile() }
                HigStepper("Image offset for the lenses", "$lensOffset mm") { step ->
                    lensOffset = (lensOffset + step).coerceIn(-Settings.MAX_LENS_MM, Settings.MAX_LENS_MM)
                    Settings.setLensOffsetMm(this@MainActivity, lensOffset)
                }
            }
            HigSection(
                title = "Cinema screen",
                footer = "A wide screen is a wide virtual display: the game itself draws more " +
                    "than 16:9. A curved one keeps the edges of the screen as far away as its middle. " +
                    "The keyboard window shows the table from the camera while you type on a real keyboard."
            ) {
                Settings.ScreenShape.entries.forEach { shape ->
                    HigChoice(shape.title, shape.detail, screenShape == shape) {
                        screenShape = shape
                        Settings.setScreenShape(this@MainActivity, shape)
                    }
                }
                HigSwitchRow("Curved screen", curvedScreen) {
                    curvedScreen = it
                    Settings.setCurvedScreen(this@MainActivity, it)
                }
                HigSwitchRow("Keyboard window", keyboardWindow) {
                    keyboardWindow = it
                    Settings.setKeyboardWindow(this@MainActivity, it)
                }
            }
            if (!BuildConfig.LITE) HigSection(
                title = "3D memories",
                footer = "The depth neural network looks at an ordinary photo and tells what is closer and what is farther — " +
                    "out of that PhoneXR makes a 3D shot for both eyes. It is large, so it is downloaded separately " +
                    "and kept in the app's storage. Video and 360° panoramas work without it."
            ) {
                when {
                    depthProgress != null -> HigRow(
                        "Depth neural network",
                        "Downloading: ${((depthProgress ?: 0f) * 100).roundToInt()}%",
                        trailing = { HigSpinner() }
                    )
                    depthInstalled -> HigRow("Depth neural network", "Ready", detailColor = HigColors.good)
                    else -> HigLink("Download the depth neural network", value = "${DepthModel.MEGABYTES} MB") { downloadDepth() }
                }
                if (depthInstalled) HigLink("Remove the neural network") {
                    DepthModel.close()
                    DepthModel.file(this@MainActivity).delete()
                    depthInstalled = false
                }
            }
            HigSection(
                title = "Refresh rate",
                footer = rates.let { list ->
                    if (list.isEmpty()) "The phone does not report which rates it supports."
                    else "The phone supports: " + list.joinToString(", ") { "$it Hz" } +
                        ". Higher is smoother head motion, but the battery drains faster."
                }
            ) {
                Settings.Refresh.entries
                    .filter { it == Settings.Refresh.AUTO || rates.isEmpty() || rates.any { hz -> hz >= it.hz } }
                    .forEach { option ->
                        HigChoice(option.title, null, refresh == option) {
                            refresh = option
                            Settings.setRefresh(this@MainActivity, option)
                        }
                    }
            }
            HigSection {
                HigLink(tr("Language"), value = L10n.current.title) { languagePicker = true }
                HigLink(tr("Account"), value = if (resumes >= 0) Account.current(this@MainActivity)?.name ?: tr("Sign in") else null) { start(AccountActivity::class.java) }
                HigLink(tr("Software Update")) { start(UpdateActivity::class.java) }
                HigLink(tr("About")) { start(AboutActivity::class.java) }
            }
        }
    }

    private fun start(screen: Class<*>) = startActivity(Intent(this, screen))

    // ---------------------------------------------------------------- Patching and installing

    @Composable
    private fun PatchDialog(game: GameLibrary.Game) {
        val about = if (game.kind == GameLibrary.Kind.VRAPI_ORIGINAL)
            "This is a ${game.headset.title} game on VrApi: without the headset itself and the Oculus driver it closes right away. " +
                "PhoneXR will replace libvrapi.so in it with a shim over OpenXR"
        else "This is a ${game.headset.title} build: on a phone it finds no OpenXR runtime and shows a black screen. " +
            "PhoneXR will give it access to the PhoneXR runtime"
        HigAlert(
            title = "Patch “${game.label}”?",
            message = about + ", write in the new tracking and optimize the build for the phone, " +
                "and then sign it with its own signature. " +
                "The original has to be uninstalled (the signature differs), and the game's saves are lost with it.",
            actions = listOf(
                HigAction(tr("Cancel"), HigActionStyle.CANCEL) { pendingPatch = null },
                HigAction("Patch") {
                    pendingPatch = null
                    patch(Uri.fromFile(game.apk), replaces = game)
                }
            ),
            onDismiss = { pendingPatch = null }
        )
    }

    @Composable
    private fun ReadyDialog(value: Ready) {
        // The same game, still installed with the store's signature: it goes first.
        val stale = value.replacesPackage?.takeIf { resumes >= 0 && isInstalled(it) }
        // Without the runtime the game starts into a black screen, whatever the patch did.
        val runtime = if (PhoneXrRuntime.state(this) == PhoneXrRuntime.State.READY) "" else
            "\n\nSo the game is not a black screen, install PhoneXR Runtime from the menu and select it " +
                "in the OpenXR Runtime Broker."
        HigAlert(
            title = value.label?.let { "“$it” is ready" }
                ?: if (value.result.vrApi) "The ${value.result.headset.title} game is ready" else "The build is ready",
            message = value.result.changes.joinToString("\n") { "• $it" } + runtime +
                if (stale != null) "\n\nA version with a different signature is installed: remove it first, " +
                    "then tap “Install”. The game's saves will be lost." else "",
            actions = listOf(
                HigAction(tr("Later"), HigActionStyle.CANCEL) { ready = null },
                if (stale != null) HigAction("Remove the old one", HigActionStyle.DESTRUCTIVE) { uninstall(stale) }
                else HigAction("Install") {
                    ready = null
                    install(value.result)
                }
            ),
            onDismiss = { ready = null }
        )
    }

    private fun open(game: GameLibrary.Game) {
        when (game.kind) {
            GameLibrary.Kind.VRAPI_ORIGINAL -> if (VrApiDriver.ready(this)) requestStart(game) else pendingPatch = game
            GameLibrary.Kind.DAYDREAM -> {
                if (!Daydream.servicesInstalled(this) && Daydream.bundled(this)) {
                    status = "Install Opendream Services first, then open the game again"
                    Daydream.installServices(this)
                } else {
                    requestStart(game)
                }
            }
            GameLibrary.Kind.VRAPI_UNSUPPORTED -> error =
                "“${game.label}” is a ${game.headset.title} game PhoneXR cannot launch: " +
                    "it has no ARM build (arm64-v8a or armeabi-v7a)."
            else -> requestStart(game)
        }
    }

    private var gameToStart: GameLibrary.Game? = null

    private fun requestStart(game: GameLibrary.Game) {
        gameToStart = game
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        } else {
            startAfterPermission = true
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startTracking() {
        if (gameToStart?.let { GameLibrary.ownsHandTracking(this, it.packageName) } == true) {
            // The game tracks hands itself: PhoneXR declines and leaves the camera to it.
            stopService(Intent(this, HandTrackingService::class.java))
            status = "The game tracks hands itself"
        } else {
            ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
            status = "Hand camera on"
        }
        val game = gameToStart ?: return
        gameToStart = null
        val intent = GameLibrary.launchIntent(this, game)
        if (intent == null) {
            error = "“${game.label}” has no launch screen"
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun patch(uri: Uri, replaces: GameLibrary.Game?, label: String? = replaces?.label) {
        if (replaces?.hasSplits == true) {
            error = "“${replaces.label}” is installed from several APKs (split). Install such a game from its original file."
            return
        }
        busy = "Preparing…"
        Thread {
            // With PhoneXR Runtime and the VrApi driver in place, a headset game needs no patch: it is
            // installed as it is, with its own signature. The patch stays for when Android refuses it.
            val original = if (VrApiDriver.ready(this) && PhoneXrRuntime.state(this) == PhoneXrRuntime.State.READY)
                runCatching { unchangedGame(PxrPackage.androidPayload(this, uri)) }.getOrNull() else null
            if (original != null) {
                runOnUiThread { installUnchanged(original, uri, replaces, label) }
                return@Thread
            }
            patchNow(uri, label)
        }.start()
    }

    /** A copy of the APK behind [uri] when it is a VR game (VrApi or OpenXR), else null. */
    private fun unchangedGame(uri: Uri): File? {
        val file = File(File(cacheDir, "patched").apply { mkdirs() }, "original.apk")
        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        val vr = java.util.zip.ZipFile(file).use { zip ->
            zip.entries().toList().any { it.name.startsWith("lib/") && (it.name.endsWith("/libvrapi.so") || it.name.endsWith("/libopenxr_loader.so")) }
        }
        return file.takeIf { vr }
    }

    private fun installUnchanged(file: File, uri: Uri, replaces: GameLibrary.Game?, label: String?) {
        if (signatureConflict(file) != null) {
            // Installed before in a patched build (PhoneXR's signature): keep going the patched way.
            Thread { patchNow(uri, label) }.start()
            return
        }
        busy = "Installing unchanged…"
        InternalInstaller.install(this, file) { problem ->
            when {
                problem == null -> {
                    busy = null
                    status = "${label ?: replaces?.label ?: "The game"} installed without patching"
                    refreshGames()
                }
                // A phone "has no VR headset" for some Android versions: then the patch makes it optional.
                problem.contains("FEATURE", ignoreCase = true) -> Thread { patchNow(uri, label) }.start()
                else -> { busy = null; error = problem }
            }
        }
    }

    private fun patchNow(uri: Uri, label: String?) {
        try {
            val payload = PxrPackage.androidPayload(this, uri)
            val result = ApkPatcher.patch(this, payload)
            val conflict = signatureConflict(result.apk)
            runOnUiThread {
                busy = null
                ready = Ready(result, label, conflict)
            }
        } catch (failure: Throwable) {
            android.util.Log.e("PhoneXR-Patch", "APK preparation failed for $uri", failure)
            runOnUiThread {
                busy = null
                error = failure.localizedMessage ?: failure.javaClass.simpleName
            }
        }
    }

    /** Package name of [apk] when that package is installed with other signing keys, else null. */
    private fun signatureConflict(apk: File): String? {
        val archive = packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return null
        val installed = runCatching {
            packageManager.getPackageInfo(archive.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return null
        fun signers(info: android.content.pm.PackageInfo) =
            info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        return archive.packageName.takeIf { signers(archive) != signers(installed) }
    }

    /** Installs the prepared game inside the PhoneXR flow, without opening the APK installer UI. */
    private fun installApk(file: File) {
        signatureConflict(file)?.let { conflict ->
            error = "This game is already installed with a different signature ($conflict). Remove it and download it again."
            return
        }
        busy = "Installing inside PhoneXR…"
        InternalInstaller.install(this, file) { problem ->
            busy = null
            if (problem != null) error = problem
            else {
                status = "Game added to PhoneXR"
                refreshGames()
            }
        }
    }

    private fun install(result: ApkPatcher.Result) {
        installApk(result.apk)
    }

    @Suppress("DEPRECATION")
    private fun uninstall(target: String) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$target")))
    }

    private fun isInstalled(target: String) =
        runCatching { packageManager.getApplicationInfo(target, 0) }.isSuccess

    /** A normal game played on the big VR screen (cinema) with its own scene. */
    private data class VrMode(
        val title: String, val game: String, val packageName: String,
        val subtitle: String, val scene: String, val sceneId: String,
    )

    companion object {
        private const val KEY_TAB = "tab"
        private const val MINECRAFT = "com.mojang.minecraftpe"
        private val VR_MODES = listOf(
            VrMode("Minecraft VR", "Minecraft", MINECRAFT, "Bedrock in a living room with a fireplace", "a living room from Minecraft VR", CinemaActivity.SCENE_ROOM),
            VrMode("Roblox VR", "Roblox", "com.roblox.client", "Roblox in a house from Brookhaven", "a Roblox house with a fireplace", CinemaActivity.SCENE_ROBLOX),
            VrMode("Brawl Stars VR", "Brawl Stars", "com.supercell.brawlstars", "Brawl Stars in the middle of the arena", "a 360° panorama of the arena", CinemaActivity.SCENE_BRAWL),
        )
        /** Height of the floating tab bar plus its margin. */
        private val TAB_BAR_ROOM = 120.dp
    }
}
