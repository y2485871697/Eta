package io.github.mangi.eta.ui.pages.providers

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderBalanceFetcher
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.data.repository.formatBalanceDisplay
import io.github.mangi.eta.ui.app.ConversationTimeLabels
import io.github.mangi.eta.ui.components.EtaDropdownMenu
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.icons.MoneyBag02
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 从 UI 触发的余额刷新需要一个长生命周期协程作用域：composition 随页面销毁会取消在途请求。
 * 这里沿 Context 包裹链向上寻找宿主 Activity 的 LifecycleOwner，拿它的 lifecycleScope。
 */
internal fun Context.activityLifecycleOwnerOrNull(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    return current as? LifecycleOwner
}

@Composable
internal fun ProviderBalanceOptionFields(
    balanceOption: BalanceOption,
    onBalanceOptionChange: (BalanceOption) -> Unit,
    provider: ProviderSetting? = null,
) {
    var expanded by remember { mutableStateOf(balanceOption.enabled) }
    var locallyEnabled by remember { mutableStateOf(balanceOption.enabled) }
    val context = LocalContext.current
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var accessTokenVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resolved = balanceOption.copy(enabled = locallyEnabled || balanceOption.enabled).resolved()
    val isNewApi = resolved.preset == BalanceOption.PRESET_NEW_API
    val switchOn = locallyEnabled || balanceOption.enabled

    Column(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.ui_balance_info_title),
            summary = stringResource(R.string.ui_balance_info_summary),
            onClick = { expanded = !expanded },
            endActions = {
                androidx.compose.material3.Switch(
                    checked = switchOn,
                    onCheckedChange = {
                        locallyEnabled = it
                        onBalanceOptionChange(balanceOption.copy(enabled = it))
                        if (it) expanded = true
                    }
                )
            }
        )

        AnimatedVisibility(visible = expanded) {
            Column {
                WindowSpinnerPreference(
                    items = listOf(
                        DropdownItem(text = stringResource(R.string.ui_balance_preset_custom)),
                        DropdownItem(text = stringResource(R.string.ui_balance_preset_new_api)),
                    ),
                    selectedIndex = if (isNewApi) 1 else 0,
                    title = stringResource(R.string.ui_balance_preset),
                    summary = if (isNewApi) {
                        stringResource(R.string.ui_balance_preset_new_api_summary)
                    } else {
                        stringResource(R.string.ui_balance_preset_custom_summary)
                    },
                    onSelectedIndexChange = { selectedIndex ->
                        onBalanceOptionChange(
                            BalanceOption.applyPreset(
                                if (selectedIndex == 1) {
                                    BalanceOption.PRESET_NEW_API
                                } else {
                                    BalanceOption.PRESET_CUSTOM
                                },
                                balanceOption.copy(enabled = switchOn),
                            ),
                        )
                    },
                )
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    if (isNewApi) {
                        TextField(
                            value = balanceOption.accessToken,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(accessToken = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_access_token),
                            singleLine = true,
                            visualTransformation = if (accessTokenVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { accessTokenVisible = !accessTokenVisible }) {
                                    Icon(
                                        imageVector = if (accessTokenVisible) {
                                            Icons.Rounded.Visibility
                                        } else {
                                            Icons.Rounded.VisibilityOff
                                        },
                                        contentDescription = if (accessTokenVisible) {
                                            context.getString(R.string.page_hide_bb0e7e)
                                        } else {
                                            context.getString(R.string.page_show_71b677)
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.ui_balance_access_token_summary),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                        )
                    } else {
                        TextField(
                            value = balanceOption.apiPath,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(apiPath = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_api_path),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            value = balanceOption.resultPath,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(resultPath = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_result_path),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    TextButton(
                        text = if (isTesting) {
                            context.getString(R.string.page_testing_f43705)
                        } else {
                            stringResource(R.string.ui_test_balance)
                        },
                        enabled = switchOn && !isTesting && provider != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val currentProvider = provider ?: return@TextButton
                            scope.launch {
                                isTesting = true
                                testResult = null
                                val option = balanceOption.copy(enabled = true).resolved()
                                testResult = if (option.apiPath.isBlank() || option.resultPath.isBlank()) {
                                    context.getString(R.string.ui_balance_test_missing_fields)
                                } else {
                                    ProviderBalanceFetcher.fetch(currentProvider, option)
                                        .fold(
                                            onSuccess = { formatBalanceDisplay(it) },
                                            onFailure = { it.message ?: it.toString() },
                                        )
                                }
                                isTesting = false
                            }
                        }
                    )
                    testResult?.let {
                        ProviderBalanceAmount(
                            amount = it,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProviderBalanceAmount(
    amount: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = MoneyBag02,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(12.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = amount,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 有内容可展示的富状态判断：有缓存金额、刷新中或失败信息任一存在都应显示。
 *
 * 无缓存且从未失败（例如从未配置过余额查询）时返回 false，保持原布局不占用空间。
 */
internal fun hasBalanceIndicatorContent(state: ProviderBalanceState?): Boolean =
    state != null && (state.amount != null || state.refreshing || state.error != null)

/**
 * 两处余额消费共用的富状态指示器。
 *
 * - 展示最后一次成功金额；失败时用警示色标注为“未更新”，绝不把旧金额伪装成实时值。
 * - 刷新中显示内联提示；无缓存失败时直接显示“获取失败”。
 * - 点击打开详情：包含金额、状态与最后成功更新时间。
 */
@Composable
internal fun ProviderBalanceIndicator(
    state: ProviderBalanceState?,
    modifier: Modifier = Modifier,
) {
    if (!hasBalanceIndicatorContent(state)) return
    val view = LocalView.current
    var showDetail by remember { mutableStateOf(false) }
    val amount = state?.amount
    val refreshing = state?.refreshing == true
    val now by produceState(System.currentTimeMillis(), state?.updatedAtMillis) {
        while (true) { value = System.currentTimeMillis(); delay(5_000) }
    }
    val stale = state?.updatedAtMillis?.let { now - it > 90_000 } == true
    val failed = state?.error != null || stale
    val amountColor = if (failed) StatusWarning else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                TouchHaptics.click(view)
                showDetail = true
            },
        ) {
            when {
                amount != null -> {
                    Icon(
                        imageVector = MoneyBag02,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(12.dp),
                        tint = amountColor,
                    )
                    Text(
                        text = amount,
                        style = MiuixTheme.textStyles.footnote1,
                        color = amountColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ProviderBalanceInlineHint(refreshing = refreshing, failed = failed)
                }
                refreshing -> {
                    Text(
                        text = "刷新中…",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                failed -> {
                    Text(
                        text = "获取失败",
                        style = MiuixTheme.textStyles.footnote1,
                        color = StatusError,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        EtaDropdownMenu(
            expanded = showDetail,
            alignEnd = true,
            onDismissRequest = { showDetail = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "余额详情", style = MiuixTheme.textStyles.body1)
                BalanceDetailRow(label = "金额", value = amount ?: "—")
                BalanceDetailRow(label = "状态", value = if (stale && state?.error == null) "余额已过期，等待刷新" else balanceStatusLabel(state))
                BalanceDetailRow(label = "最后更新", value = balanceUpdatedLabel(state?.updatedAtMillis))
                val error = state?.error
                if (error != null) {
                    Text(
                        text = "最近一次刷新失败：$error",
                        style = MiuixTheme.textStyles.footnote1,
                        color = StatusError,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderBalanceInlineHint(refreshing: Boolean, failed: Boolean) {
    val text = when {
        refreshing -> "刷新中"
        failed -> "未更新"
        else -> return
    }
    Spacer(modifier = Modifier.width(4.dp))
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = if (refreshing) MiuixTheme.colorScheme.onSurfaceVariantSummary else StatusWarning,
        maxLines = 1,
    )
}

private fun balanceStatusLabel(state: ProviderBalanceState?): String = when {
    state == null -> "暂无数据"
    state.refreshing -> "刷新中…"
    state.error != null && state.amount != null -> "更新失败，显示上次成功金额"
    state.error != null -> "获取失败（无缓存金额）"
    state.amount != null -> "上次成功获取"
    else -> "暂无数据"
}

private fun balanceUpdatedLabel(millis: Long?): String {
    if (millis == null || millis <= 0L) return "从未成功更新"
    return ConversationTimeLabels.label(
        timestampMillis = millis,
        yesterdayLabel = "昨天",
        recentLabel = "刚刚",
    )
}

@Composable
private fun BalanceDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(end = 12.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
