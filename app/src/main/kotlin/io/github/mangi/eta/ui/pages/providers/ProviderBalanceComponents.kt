package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import io.github.mangi.eta.ui.icons.MoneyBag02
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderBalanceFetcher
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
    val scope = rememberCoroutineScope()

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
                            testResult = ProviderBalanceFetcher.fetch(provider)
                                .fold(onSuccess = { it.formatBalanceResult() }, onFailure = { it.message ?: it.toString() })
                            isTesting = false
                        }
                    }
                )
                testResult?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            imageVector = MoneyBag02,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

private fun String.formatBalanceResult(): String {
    val trimmed = trim()
    val number = trimmed.toDoubleOrNull()
    return if (number != null) {
        DecimalFormat(
            "#,##0.00",
            DecimalFormatSymbols.getInstance(java.util.Locale.getDefault()),
        ).format(number)
    } else {
        trimmed
    }
}
