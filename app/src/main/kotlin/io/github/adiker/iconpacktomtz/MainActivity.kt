package io.github.adiker.iconpacktomtz

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.adiker.iconpacktomtz.conversion.ConversionSessionState
import io.github.adiker.iconpacktomtz.core.data.ConversionHistoryEntity
import io.github.adiker.iconpacktomtz.core.model.ConversionMode
import io.github.adiker.iconpacktomtz.core.model.IconPackAnalysis
import io.github.adiker.iconpacktomtz.core.model.IssueSeverity
import io.github.adiker.iconpacktomtz.core.model.NamingStrategy
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: ConverterViewModel by viewModels()
    private val shizukuBinderReceivedListener =
        Shizuku.OnBinderReceivedListener { viewModel.refreshShizukuState() }
    private val shizukuBinderDeadListener =
        Shizuku.OnBinderDeadListener { viewModel.refreshShizukuState() }
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            viewModel.onShizukuPermissionResult(requestCode, grantResult)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainApp(
                    viewModel = viewModel,
                    share = ::shareDocument,
                    open = ::openDocument,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshShizukuState()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        super.onDestroy()
    }

    private fun shareDocument(uriString: String, mime: String) {
        val uri = Uri.parse(uriString)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, "shared document", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.share_mtz),
            ),
        )
    }

    private fun openDocument(uriString: String, packageName: String? = null) {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("MTZ", uri)
            packageName?.let(::setPackage)
        }
        runCatching {
            startActivity(
                if (packageName == null) Intent.createChooser(intent, getString(R.string.open_with))
                else intent,
            )
        }
    }
}

private enum class AppPage { CONVERTER, HISTORY, SETTINGS }

@Composable
private fun MainApp(
    viewModel: ConverterViewModel,
    share: (String, String) -> Unit,
    open: (String, String?) -> Unit,
) {
    val form by viewModel.form.collectAsState()
    val shizukuState by viewModel.shizukuState.collectAsState()
    val session by viewModel.session.collectAsState()
    val history by viewModel.history.collectAsState()
    var page by remember { mutableStateOf(AppPage.CONVERTER) }
    val context = LocalContext.current

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::selectApk) }
    val mtzPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.selectBaseMtz(uri) }
    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::selectOutputTree) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startConversion() }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == AppPage.CONVERTER,
                    onClick = { page = AppPage.CONVERTER },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text(stringResource(R.string.converter)) },
                )
                NavigationBarItem(
                    selected = page == AppPage.HISTORY,
                    onClick = { page = AppPage.HISTORY },
                    icon = { Icon(Icons.Default.History, null) },
                    label = { Text(stringResource(R.string.history)) },
                )
                NavigationBarItem(
                    selected = page == AppPage.SETTINGS,
                    onClick = { page = AppPage.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(stringResource(R.string.settings)) },
                )
            }
        },
    ) { padding ->
        when (page) {
            AppPage.CONVERTER -> ConverterScreen(
                modifier = Modifier.padding(padding),
                form = form,
                session = session,
                shizukuAvailable = shizukuState.available,
                onPickApk = {
                    apkPicker.launch(
                        arrayOf(
                            "application/vnd.android.package-archive",
                            "application/zip",
                            "application/octet-stream",
                        ),
                    )
                },
                onPickMtz = {
                    mtzPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                onRemoveMtz = { viewModel.selectBaseMtz(null) },
                onPickOutput = { outputPicker.launch(null) },
                onMode = viewModel::updateMode,
                onNaming = viewModel::updateNaming,
                onSize = viewModel::updateSize,
                onMargin = viewModel::updateMargin,
                onWorkers = viewModel::updateWorkers,
                onTitle = viewModel::updateTitle,
                onAuthor = viewModel::updateAuthor,
                onDescription = viewModel::updateDescription,
                onUseShizuku = viewModel::updateUseShizuku,
                onAnalyze = viewModel::analyze,
                onGenerate = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.startConversion()
                    }
                },
                onCancel = viewModel::cancelConversion,
                onDismiss = viewModel::clearResult,
                share = share,
                open = open,
            )
            AppPage.HISTORY -> HistoryScreen(
                Modifier.padding(padding),
                history,
                viewModel::clearHistory,
                open,
            )
            AppPage.SETTINGS -> SettingsScreen(
                Modifier.padding(padding),
                form,
                viewModel::clearCache,
                viewModel::updateAdvancedLimits,
                viewModel::updateCacheLimitMiB,
            )
        }
    }
}

@Composable
private fun ConverterScreen(
    modifier: Modifier,
    form: ConverterFormState,
    session: ConversionSessionState,
    shizukuAvailable: Boolean,
    onPickApk: () -> Unit,
    onPickMtz: () -> Unit,
    onRemoveMtz: () -> Unit,
    onPickOutput: () -> Unit,
    onMode: (ConversionMode) -> Unit,
    onNaming: (NamingStrategy) -> Unit,
    onSize: (Int) -> Unit,
    onMargin: (Float) -> Unit,
    onWorkers: (Int) -> Unit,
    onTitle: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onDescription: (String) -> Unit,
    onUseShizuku: (Boolean) -> Unit,
    onAnalyze: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    share: (String, String) -> Unit,
    open: (String, String?) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            SectionCard(stringResource(R.string.source_and_destination)) {
                FileChoice(stringResource(R.string.select_apk), form.apkName, onPickApk)
                Spacer(Modifier.height(8.dp))
                FileChoice(stringResource(R.string.select_base_mtz), form.baseMtzName, onPickMtz)
                if (form.baseMtzUri != null) {
                    OutlinedButton(onClick = onRemoveMtz) {
                        Text(stringResource(R.string.remove_base_mtz))
                    }
                }
                Spacer(Modifier.height(8.dp))
                FileChoice(
                    stringResource(R.string.select_output_folder),
                    form.outputTreeName,
                    onPickOutput,
                )
            }
        }
        item {
            SectionCard(stringResource(R.string.conversion_mode)) {
                ModeRow(
                    selected = form.mode == ConversionMode.FULL,
                    title = stringResource(R.string.full_mode),
                    description = stringResource(R.string.full_mode_hint),
                ) { onMode(ConversionMode.FULL) }
                ModeRow(
                    selected = form.mode == ConversionMode.INSTALLED_ONLY,
                    title = stringResource(R.string.installed_mode),
                    description = stringResource(R.string.installed_mode_hint),
                ) { onMode(ConversionMode.INSTALLED_ONLY) }
                if (form.mode == ConversionMode.INSTALLED_ONLY) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = form.useShizuku,
                            onCheckedChange = onUseShizuku,
                            enabled = shizukuAvailable,
                        )
                        Text(stringResource(R.string.use_shizuku))
                    }
                    if (!shizukuAvailable) {
                        Text(
                            stringResource(R.string.shizuku_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.naming_strategy)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NamingStrategy.entries.forEach { strategy ->
                        FilterChip(
                            selected = form.namingStrategy == strategy,
                            onClick = { onNaming(strategy) },
                            label = {
                                Text(
                                    when (strategy) {
                                        NamingStrategy.OPTIMIZED ->
                                            stringResource(R.string.naming_optimized)
                                        NamingStrategy.FULL_COMPATIBILITY ->
                                            stringResource(R.string.naming_full)
                                        NamingStrategy.PACKAGES_ONLY ->
                                            stringResource(R.string.naming_packages)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.theme_metadata)) {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = onTitle,
                    label = { Text(stringResource(R.string.theme_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.author,
                    onValueChange = onAuthor,
                    label = { Text(stringResource(R.string.author)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.description,
                    onValueChange = onDescription,
                    label = { Text(stringResource(R.string.description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        }
        item {
            SectionCard(stringResource(R.string.rendering)) {
                Text(stringResource(R.string.png_size, form.sizePx))
                Slider(
                    value = form.sizePx.toFloat(),
                    onValueChange = { onSize(it.roundToInt()) },
                    valueRange = 48f..512f,
                    steps = 28,
                )
                Text(stringResource(R.string.icon_margin, (form.marginFraction * 100).roundToInt()))
                Slider(
                    value = form.marginFraction,
                    onValueChange = onMargin,
                    valueRange = 0f..0.4f,
                    steps = 39,
                )
                Text(stringResource(R.string.workers, form.workerCount))
                Slider(
                    value = form.workerCount.toFloat(),
                    onValueChange = { onWorkers(it.roundToInt()) },
                    valueRange = 1f..4f,
                    steps = 2,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onAnalyze,
                    enabled = form.apkUri != null && !form.isAnalyzing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.analyze_apk))
                }
                Button(
                    onClick = onGenerate,
                    enabled = form.apkUri != null &&
                        form.outputTreeUri != null &&
                        session !is ConversionSessionState.Running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.generate_mtz))
                }
            }
        }
        if (form.isAnalyzing) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.analyzing))
                    }
                }
            }
        }
        form.analysisError?.let { error ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(error, Modifier.padding(16.dp))
                }
            }
        }
        form.analysis?.let { analysis ->
            item { AnalysisCard(analysis) }
        }
        when (session) {
            is ConversionSessionState.Running -> item {
                ProgressCard(session, onCancel)
            }
            is ConversionSessionState.Completed -> item {
                ResultCard(session, onDismiss, share, open)
            }
            is ConversionSessionState.Failed -> item {
                FailureCard(session, onDismiss, share)
            }
            ConversionSessionState.Idle -> Unit
        }
        item {
            Text(
                stringResource(R.string.mtz_size_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun FileChoice(title: String, selected: String?, action: () -> Unit) {
    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Folder, null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                selected ?: stringResource(R.string.selection_not_selected),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick)
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnalysisCard(analysis: IconPackAnalysis) {
    SectionCard(stringResource(R.string.analysis)) {
        Text(stringResource(R.string.pack_name, analysis.metadata.label))
        Text(stringResource(R.string.pack_version, analysis.metadata.versionName ?: "—"))
        Text(stringResource(R.string.mapping_location, analysis.mappingLocation))
        HorizontalDivider()
        Text(stringResource(R.string.mapping_entries, analysis.entries.size))
        Text(stringResource(R.string.unique_packages, analysis.packageCount))
        Text(stringResource(R.string.unique_icons, analysis.uniqueDrawableCount))
        Text(stringResource(R.string.predicted_files, analysis.predictedOutputFiles))
        Text(
            stringResource(
                R.string.estimated_size,
                formatBytes(analysis.estimatedMtzBytes ?: 0),
            ),
        )
        Text(stringResource(R.string.detected_issues, analysis.issues.size))
        analysis.issues.take(8).forEach { issue ->
            Text(
                "• ${issue.code}: ${issue.subject.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = if (issue.severity == IssueSeverity.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun ProgressCard(
    session: ConversionSessionState.Running,
    onCancel: () -> Unit,
) {
    val progress = session.progress
    SectionCard(stringResource(R.string.conversion_progress)) {
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${progress.completed} / ${progress.total}")
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.stage, progress.stage.name.replace('_', ' ')))
        Text(stringResource(R.string.rendered_count, progress.renderedIcons))
        Text(stringResource(R.string.alias_count, progress.aliasesWritten))
        Text(stringResource(R.string.error_count, progress.errors))
        Button(onClick = onCancel) {
            Icon(Icons.Default.Cancel, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun ResultCard(
    result: ConversionSessionState.Completed,
    onDismiss: () -> Unit,
    share: (String, String) -> Unit,
    open: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    val knownTargets = remember(result.outputUri) {
        listOf(
            "com.android.thememanager" to "Xiaomi Themes",
            "com.htetznaing.zfont2" to "zFont",
        ).filter { (packageName, _) ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(result.outputUri), "application/zip")
                setPackage(packageName)
            }
            context.packageManager.resolveActivity(intent, 0) != null
        }
    }
    SectionCard(stringResource(R.string.conversion_complete)) {
        Text(stringResource(R.string.output_size, formatBytes(result.outputBytes)))
        Text(stringResource(R.string.duration, formatDuration(result.durationMillis)))
        Text(stringResource(R.string.generated_icons, result.generatedIcons))
        Text(stringResource(R.string.skipped_entries, result.skippedEntries))
        Text(stringResource(R.string.error_count, result.errors))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { share(result.outputUri, "application/zip") }) {
                Icon(Icons.Default.Share, null)
                Text(stringResource(R.string.share_mtz))
            }
            OutlinedButton(onClick = { open(result.outputUri, null) }) {
                Text(stringResource(R.string.open_with))
            }
        }
        knownTargets.forEach { (packageName, label) ->
            OutlinedButton(onClick = { open(result.outputUri, packageName) }) {
                Text(label)
            }
        }
        OutlinedButton(onClick = { share(result.jsonReportUri, "application/json") }) {
            Text(stringResource(R.string.open_report_json))
        }
        OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
    }
}

@Composable
private fun FailureCard(
    failure: ConversionSessionState.Failed,
    onDismiss: () -> Unit,
    share: (String, String) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (failure.cancelled) {
                    stringResource(R.string.conversion_cancelled)
                } else {
                    stringResource(R.string.conversion_failed)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(failure.message)
            failure.jsonReportUri?.let { uri ->
                OutlinedButton(onClick = { share(uri, "application/json") }) {
                    Text(stringResource(R.string.open_report_json))
                }
            }
            failure.textReportUri?.let { uri ->
                OutlinedButton(onClick = { share(uri, "text/plain") }) {
                    Text(stringResource(R.string.open_report_text))
                }
            }
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
private fun HistoryScreen(
    modifier: Modifier,
    history: List<ConversionHistoryEntity>,
    clear: () -> Unit,
    open: (String, String?) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.history), style = MaterialTheme.typography.headlineMedium)
                if (history.isNotEmpty()) {
                    OutlinedButton(onClick = clear) {
                        Text(stringResource(R.string.clear_history))
                    }
                }
            }
        }
        if (history.isEmpty()) {
            item { Text(stringResource(R.string.no_history)) }
        }
        items(history, key = { it.operationId }) { item ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = item.outputUri != null) {
                        item.outputUri?.let { open(it, null) }
                    },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.sourceDisplayName, fontWeight = FontWeight.Bold)
                    Text("${item.status} • ${item.mode} • ${item.namingStrategy}")
                    Text(
                        "${formatDuration(item.durationMillis)} • " +
                            "${formatBytes(item.outputBytes)} • ${item.iconCount}",
                    )
                    item.errorSummary?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    form: ConverterFormState,
    clearCache: () -> Unit,
    updateAdvancedLimits: (Boolean) -> Unit,
    updateCacheLimitMiB: (Int) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        }
        item {
            SectionCard(stringResource(R.string.privacy_title)) {
                Text(stringResource(R.string.privacy_text))
            }
        }
        item {
            SectionCard(stringResource(R.string.compatibility_title)) {
                Text(stringResource(R.string.compatibility_text))
            }
        }
        item {
            SectionCard(stringResource(R.string.rendering)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = form.advancedLimits,
                        onCheckedChange = updateAdvancedLimits,
                    )
                    Column {
                        Text(stringResource(R.string.advanced_limits))
                        Text(
                            stringResource(R.string.advanced_limits_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(stringResource(R.string.cache_limit, form.cacheLimitMiB))
                Slider(
                    value = form.cacheLimitMiB.toFloat(),
                    onValueChange = { updateCacheLimitMiB(it.roundToInt()) },
                    valueRange = 32f..2_048f,
                    steps = 62,
                )
                OutlinedButton(onClick = clearCache) {
                    Text(stringResource(R.string.clear_cache))
                }
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private fun formatDuration(millis: Long): String =
    if (millis < 1_000) "$millis ms" else String.format(Locale.getDefault(), "%.1f s", millis / 1_000.0)
