package io.github.mangi.eta.ui.screens.assistants

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.screens.skills.SkillSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AssistantEditScreen(
    assistantId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(assistantId) { AssistantRepository.profile(assistantId) }
    if (original == null) {
        LaunchedEffect(assistantId) { onBack() }
        return
    }
    var name by remember(assistantId) { mutableStateOf(original.name) }
    var prompt by remember(assistantId) { mutableStateOf(original.prompt) }
    var avatarFileName by remember(assistantId) { mutableStateOf(original.avatarFileName) }
    var memoryEnabled by remember(assistantId) { mutableStateOf(original.memoryEnabled) }
    var enabledSkillIds by remember(assistantId) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var memoryDraft by remember(assistantId) { mutableStateOf("") }
    var memorySaved by remember(assistantId) { mutableStateOf("") }
    var installedSkills by remember(assistantId) { mutableStateOf<List<SkillIndexEntry>>(emptyList()) }
    var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val avatarBitmap = remember(assistantId, avatarFileName) {
        AssistantRepository.avatarBitmap(avatarFileName)
    }
    val memoryBytes = remember(memoryDraft) { memoryDraft.toByteArray(Charsets.UTF_8).size }
    val memoryOverLimit = memoryBytes > AgentMemoryStore.MAX_FILE_BYTES
    val dirty = name.trim() != original.name ||
        prompt != original.prompt ||
        avatarFileName != original.avatarFileName ||
        memoryEnabled != original.memoryEnabled ||
        enabledSkillIds != original.enabledSkillIds.toSet() ||
        memoryDraft != memorySaved

    LaunchedEffect(assistantId) {
        val loaded = withContext(Dispatchers.IO) {
            val snapshot = runCatching { AgentMemoryRepository.snapshot().content }.getOrDefault("")
            val skills = runCatching {
                SkillRuntime.createIndexService(context.applicationContext)
                    .listSkillsForManagement()
                    .filter { it.installed }
            }.getOrDefault(emptyList())
            snapshot to skills
        }
        memoryDraft = loaded.first
        memorySaved = loaded.first
        installedSkills = loaded.second
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        cropBitmap = decodeAvatarBitmap(context, uri)
    }

    fun save() {
        if (!dirty || saving || memoryOverLimit) return
        saving = true
        scope.launch {
            withContext(Dispatchers.IO) {
                AssistantRepository.update(
                    original.copy(
                        name = name,
                        prompt = prompt,
                        avatarFileName = avatarFileName,
                        memoryEnabled = memoryEnabled,
                        enabledSkillIds = enabledSkillIds.toList(),
                    ),
                )
                if (memoryDraft != memorySaved) {
                    AgentMemoryRepository.replaceAll(memoryDraft)
                }
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            }
            saving = false
            onBack()
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.assistant_edit_title),
        onBack = onBack,
        actions = {
            Text(
                text = stringResource(R.string.action_save),
                color = if (dirty && !saving && !memoryOverLimit) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                modifier = Modifier
                    .padding(end = 12.dp)
                    .clickable(enabled = dirty && !saving && !memoryOverLimit, onClick = ::save),
            )
        },
    ) {
        item(key = "preview_title") {
            SmallTitle(stringResource(R.string.assistant_preview))
        }
        item(key = "preview") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .clickable {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AssistantAvatar(bitmap = avatarBitmap, size = 56.dp)
                        Icon(
                            imageVector = Icons.Rounded.PhotoCamera,
                            contentDescription = stringResource(R.string.assistant_change_avatar),
                            tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(16.dp),
                        )
                    }
                    Text(
                        text = name.trim().ifBlank { stringResource(R.string.assistant_unnamed) },
                        style = MiuixTheme.textStyles.title3,
                    )
                }
            }
        }
        item(key = "identity_title") {
            SmallTitle(stringResource(R.string.assistant_identity))
        }
        item(key = "identity") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.assistant_name),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }
        item(key = "prompt_title") {
            SmallTitle(stringResource(R.string.assistant_prompt_title))
        }
        item(key = "prompt") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                TextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = "",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(240.dp),
                    singleLine = false,
                )
            }
        }
        item(key = "memory_title") {
            SmallTitle(stringResource(R.string.ui_memory_b55ff5))
        }
        item(key = "memory") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.ui_enable_memory_4b69b7),
                    summary = stringResource(R.string.assistant_memory_summary),
                    checked = memoryEnabled,
                    onCheckedChange = { memoryEnabled = it },
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextField(
                        value = memoryDraft,
                        onValueChange = { memoryDraft = it },
                        label = "MEMORY.md",
                        useLabelAsPlaceholder = true,
                        singleLine = false,
                        minLines = 6,
                        maxLines = 12,
                        textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (memoryOverLimit) {
                        Text(
                            text = stringResource(R.string.memory_over_limit),
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        item(key = "skills_title") {
            SmallTitle(stringResource(R.string.route_skills))
        }
        item(key = "skills") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_skills_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (installedSkills.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    installedSkills.forEach { skill ->
                        SkillSwitchRow(
                            skill = skill.toSwitcherUi(enabledSkillIds.contains(skill.id)),
                            enabled = !saving,
                            showMenu = false,
                            onToggle = { enabled ->
                                enabledSkillIds = if (enabled) {
                                    enabledSkillIds + skill.id
                                } else {
                                    enabledSkillIds - skill.id
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    cropBitmap?.let { bitmap ->
        AvatarCropDialog(
            bitmap = bitmap,
            onCancel = { cropBitmap = null },
            onConfirm = { cropped ->
                cropBitmap = null
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AssistantRepository.saveAvatar(assistantId, cropped)
                    }
                    avatarFileName = updated.avatarFileName
                    if (cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                }
            },
        )
    }
}

private fun SkillIndexEntry.toSwitcherUi(enabled: Boolean): SkillItemUi = SkillItemUi(
    id = id,
    name = name,
    description = description,
    source = source,
    enabled = enabled,
    installed = installed,
    capabilities = buildList {
        if (hasScripts) add("scripts")
        if (hasReferences) add("references")
        if (hasAssets) add("assets")
        if (hasEvals) add("evals")
    },
)
