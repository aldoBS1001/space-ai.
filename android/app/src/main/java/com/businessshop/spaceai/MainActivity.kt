package com.businessshop.spaceai

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.businessshop.spaceai.core.AccessReport
import com.businessshop.spaceai.core.AccessState
import com.businessshop.spaceai.core.CleanupCategory
import com.businessshop.spaceai.core.RiskLevel
import com.businessshop.spaceai.data.SystemConsentBridge
import com.businessshop.spaceai.ui.SpaceViewModel
import com.businessshop.spaceai.ui.UiError
import com.businessshop.spaceai.ui.screens.CompressionScreen
import com.businessshop.spaceai.ui.screens.ConfirmScreen
import com.businessshop.spaceai.ui.screens.FoldersScreen
import com.businessshop.spaceai.ui.screens.HistoryScreen
import com.businessshop.spaceai.ui.screens.HomeScreen
import com.businessshop.spaceai.ui.screens.ReportScreen
import com.businessshop.spaceai.ui.screens.ResultsScreen
import com.businessshop.spaceai.ui.screens.ReviewScreen
import com.businessshop.spaceai.ui.screens.SettingsScreen
import com.businessshop.spaceai.ui.theme.SpaceTheme

class SpaceAiApp : Application()

class MainActivity : ComponentActivity() {
    private val vm: SpaceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SpaceTheme { SpaceApp(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.setAccess(Permissions.accessReport(this))
        vm.refreshStorage()
    }
}

object Permissions {
    fun requested(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Estado real de acceso; el acceso parcial de Android 14 se reporta como LIMITED, nunca como completo. */
    fun accessReport(ctx: Context): AccessReport {
        fun state(full: String): AccessState = when {
            granted(ctx, full) -> AccessState.FULL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> AccessState.LIMITED
            else -> if (asked(ctx)) AccessState.DENIED else AccessState.NOT_REQUESTED
        }
        val photos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) state(Manifest.permission.READ_MEDIA_IMAGES) else state(Manifest.permission.READ_EXTERNAL_STORAGE)
        val videos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) state(Manifest.permission.READ_MEDIA_VIDEO) else state(Manifest.permission.READ_EXTERNAL_STORAGE)
        val hasTrees = ctx.contentResolver.persistedUriPermissions.isNotEmpty()
        return AccessReport(photos, videos, if (hasTrees) AccessState.LIMITED else AccessState.NOT_REQUESTED, AccessState.RESTRICTED_BY_OS)
    }

    private fun asked(ctx: Context) = ctx.getSharedPreferences("perm", Context.MODE_PRIVATE).getBoolean("asked", false)
    fun markAsked(ctx: Context) = ctx.getSharedPreferences("perm", Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
}

private object Routes {
    const val HOME = "home"; const val RESULTS = "results"; const val REVIEW = "review/{cat}"; const val CONFIRM = "confirm"
    const val REPORT = "report"; const val COMPRESS = "compress"; const val SETTINGS = "settings"; const val HISTORY = "history"
    const val FOLDERS = "folders"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceApp(vm: SpaceViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Diálogo de confirmación del sistema para borrar medios (requisito de Android).
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        pendingConsent?.result?.complete(res.resultCode == android.app.Activity.RESULT_OK)
        pendingConsent = null
    }
    LaunchedEffect(Unit) {
        for (req in SystemConsentBridge.requests) {
            pendingConsent = req
            runCatching { consentLauncher.launch(IntentSenderRequest.Builder(req.sender).build()) }
                .onFailure { req.result.complete(false); pendingConsent = null }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Permissions.markAsked(ctx)
        val access = Permissions.accessReport(ctx)
        vm.setAccess(access)
        if (access.photos == AccessState.FULL || access.photos == AccessState.LIMITED ||
            access.videos == AccessState.FULL || access.videos == AccessState.LIMITED) vm.startScan()
    }

    LaunchedEffect(state.result, state.progress) {
        if (state.progress == null && state.result != null && route == Routes.HOME && vm.justFinished()) nav.navigate(Routes.RESULTS)
    }

    Scaffold(
        topBar = {
            if (route != null && route != Routes.HOME) TopAppBar(
                title = { Text("SPACE AI") },
                navigationIcon = {
                    IconButton(onClick = { if (route == Routes.COMPRESS) vm.closeCompression(); nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        state,
                        onAnalyze = {
                            val a = state.access
                            if (a.photos == AccessState.FULL || a.photos == AccessState.LIMITED) vm.startScan()
                            else permLauncher.launch(Permissions.requested())
                        },
                        onCancel = vm::cancelScan,
                        onSeeResults = { nav.navigate(Routes.RESULTS) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.RESULTS) {
                    ResultsScreen(
                        state,
                        onOpenCategory = { nav.navigate("review/${it.name}") },
                        onReviewAndFree = { if (vm.preparePlan(null)) nav.navigate(Routes.CONFIRM) else nav.navigate("review/${firstCategory(state)}") },
                        onClearTemp = { vm.clearAppTemp() },
                        onOpenFiles = { nav.navigate(Routes.FOLDERS) },
                    )
                }
                composable(Routes.REVIEW) { entry ->
                    val cat = runCatching { CleanupCategory.valueOf(entry.arguments?.getString("cat")!!) }.getOrNull() ?: return@composable
                    ReviewScreen(
                        state, cat,
                        onToggle = vm::toggle,
                        onSelectAll = { ids, sel -> vm.selectAll(ids, sel) },
                        onSetKeeper = { g, k -> vm.setKeeper(g, k) },
                        onCompress = { vm.openCompression(it); nav.navigate(Routes.COMPRESS) },
                        onDelete = { if (vm.preparePlan(setOf(cat))) nav.navigate(Routes.CONFIRM) },
                    )
                }
                composable(Routes.CONFIRM) {
                    ConfirmScreen(
                        state, vm.settings.effectiveDeleteMode,
                        onConfirm = { ack -> vm.executePlan(ack) { nav.navigate(Routes.REPORT) { popUpTo(Routes.RESULTS) } } },
                        onCancel = { vm.discardPlan(); nav.popBackStack() },
                        onCancelRunning = vm::cancelCleanup,
                    )
                }
                composable(Routes.REPORT) {
                    ReportScreen(state, onDeleteTrashed = { vm.deleteTrashedPermanently { } }, onDone = { nav.popBackStack(Routes.RESULTS, false) })
                }
                composable(Routes.COMPRESS) {
                    CompressionScreen(
                        state,
                        onPreset = vm::setPreset, onStart = vm::startCompression, onCancel = { vm.cancelCompression() },
                        onWatched = vm::markPreviewWatched, onSave = vm::saveCompressedCopy, onDiscard = vm::discardCompressed,
                        onDeleteOriginal = {
                            val item = state.compression?.item ?: return@CompressionScreen
                            if (vm.planForItems(listOf(item), RiskLevel.MEDIUM, CleanupCategory.COMPRESSIBLE_VIDEOS)) nav.navigate(Routes.CONFIRM)
                        },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(vm.settings, onOpenHistory = { nav.navigate(Routes.HISTORY) }, onOpenFolders = { nav.navigate(Routes.FOLDERS) },
                        onClearCache = { vm.clearAnalysisCache() }, onClearHistory = { vm.clearHistory() })
                }
                composable(Routes.HISTORY) { HistoryScreen(state, onLoad = { vm.loadHistory() }) }
                composable(Routes.FOLDERS) { FoldersScreen(vm.documents(), onChanged = { vm.setAccess(Permissions.accessReport(ctx)) }) }
            }
        }
    }

    state.error?.let { err ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text(stringResource(R.string.error_title)) },
            text = {
                Text(stringResource(when (err) {
                    UiError.SCAN_FAILED -> R.string.err_scan
                    UiError.UNSAFE_PLAN -> R.string.err_unsafe
                    UiError.CLEANUP_FAILED -> R.string.err_cleanup
                    UiError.NO_SPACE_FOR_COMPRESSION -> R.string.err_no_space
                    UiError.COMPRESSION_FAILED -> R.string.err_compression
                    UiError.SAVE_FAILED -> R.string.err_save
                }) + (state.errorDetail?.let { "\n\n$it" } ?: ""))
            },
            confirmButton = { TextButton(onClick = vm::clearError) { Text(stringResource(R.string.close)) } },
        )
    }
}

private var pendingConsent: SystemConsentBridge.Request? = null

private fun firstCategory(state: com.businessshop.spaceai.ui.UiState): String =
    state.result?.categories?.firstOrNull()?.category?.name ?: CleanupCategory.SIMILAR_PHOTOS.name
