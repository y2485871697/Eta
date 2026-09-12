package io.github.mangi.eta.ui.pages.providers

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
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderBalanceFetcher
import io.github.mangi.eta.data.repository.formatBalanceDisplay
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

@Composable
internal fun ProviderBalanceOptionFields(
    balanceOption: BalanceOption,
    onBalanceOptionChange: (BalanceOption) -> Unit,
    provider: ProviderSetting? = null,
) {
    var expanded by remember { mutableStateOf(balanceOption.enabled) }
    val context = LocalContext.current
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var accessTokenVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isNewApi = balanceOption.preset == BalanceOption.PRESET_NEW_API

    Column(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.ui_balance_info_title),
            summary = stringResource(R.string.ui_balance_info_summary),
            onClick = { expanded = !expanded },
            endActions = {
                androidx.compose.material3.Switch(
                    checked = balanceOption.enabled,
                    onCheckedChange = {
                        onBalanceOptionChange(balanceOption.copy(enabled = it))
                        expanded = it
                    }
                )
            }
        )

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                                balanceOption,
                            ),
                        )
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (isNewApi) {
                    TextField(
                        value = balanceOption.userId,
                        onValueChange = { onBalanceOptionChange(balanceOption.copy(userId = it)) },
                        label = stringResource(R.string.ui_balance_user_id),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = balanceOption.accessToken,
                        onValueChange = {
                            onBalanceOptionChange(balanceOption.copy(accessToken = it))
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
                }
                TextField(
                    value = balanceOption.apiPath,
                    onValueChange = { onBalanceOptionChange(balanceOption.copy(apiPath = it)) },
                    label = stringResource(R.string.ui_balance_api_path),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = balanceOption.resultPath,
                    onValueChange = { onBalanceOptionChange(balanceOption.copy(resultPath = it)) },
                    label = stringResource(R.string.ui_balance_result_path),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    text = if (isTesting) {
                        context.getString(R.string.page_testing_f43705)
                    } else {
                        stringResource(R.string.ui_test_balance)
                    },
                    enabled = balanceOption.enabled && balanceOption.apiPath.isNotBlank() &&
                        balanceOption.resultPath.isNotBlank() && !isTesting && provider != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        provider ?: return@TextButton
                        scope.launch {
                            isTesting = true
                            testResult = null
                            testResult = ProviderBalanceFetcher.fetch(provider, balanceOption)
                                .fold(
                                    onSuccess = { formatBalanceDisplay(it) },
                                    onFailure = { it.message ?: it.toString() },
                                )
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
