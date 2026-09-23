package io.github.mangi.eta.ui.pages.providers

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.icons.MoneyBag02
import kotlinx.coroutines.delay
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
 * 只有拿到过成功金额才占用空间。
 *
 * 余额刷新在后台静默进行：既没有“刷新中”，也不显示“已刷新”之类的成功提示，
 * 无缓存金额的失败同样不再插入提示文本，避免刷新过程在顶部栏与模型选择器里闪烁。
 */
internal fun hasBalanceIndicatorContent(state: ProviderBalanceState?): Boolean =
    state?.amount != null

/**
 * 顶部栏与模型选择器共用的余额只读指示器。
 *
 * - 只展示最后一次成功金额；刷新继续在后台更新数字，但不再显示刷新中/已刷新等提示。
 * - 长时间未更新或最近一次失败时用警示色标注，不把旧金额伪装成实时值。
 * - 只读：不响应点击，也不弹出余额详情。
 */
@Composable
internal fun ProviderBalanceIndicator(
    state: ProviderBalanceState?,
    modifier: Modifier = Modifier,
) {
    if (state == null) return
    val amount = state.amount ?: return
    val updatedAtMillis = state.updatedAtMillis
    val now by produceState(System.currentTimeMillis(), updatedAtMillis) {
        while (true) {
            value = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val stale = updatedAtMillis?.let { now - it > 90_000 } == true
    val amountColor = if (state.error != null || stale) {
        StatusWarning
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
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
            tint = amountColor,
        )
        Text(
            text = amount,
            style = MiuixTheme.textStyles.footnote1,
            color = amountColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
