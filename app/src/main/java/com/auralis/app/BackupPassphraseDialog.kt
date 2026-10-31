package com.auralis.app

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** 设置 / 修改 / 清除"备份口令"的对话框。 */
@Composable
fun BackupPassphraseDialog(
    isSet: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_passphrase_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.backup_passphrase_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it; errorRes = null },
                    label = { Text(stringResource(R.string.backup_passphrase_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; errorRes = null },
                    label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = errorRes != null,
                    supportingText = { errorRes?.let { Text(stringResource(it)) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            ApplyDialogBlur()
            Button(
                enabled = pass.isNotEmpty(),
                onClick = {
                    when {
                        pass.length < 8 -> errorRes = R.string.backup_passphrase_err_short
                        pass != confirm -> errorRes = R.string.backup_passphrase_err_mismatch
                        !BackupPassphraseStore.set(context, pass) -> errorRes = R.string.backup_passphrase_err_save
                        else -> {
                            Toast.makeText(context, context.getString(R.string.backup_passphrase_saved), Toast.LENGTH_LONG).show()
                            onChanged()
                            onDismiss()
                        }
                    }
                }
            ) { Text(stringResource(R.string.backup_passphrase_save)) }
        },
        dismissButton = {
            Row {
                if (isSet) {
                    TextButton(onClick = {
                        BackupPassphraseStore.clear(context)
                        Toast.makeText(context, context.getString(R.string.backup_passphrase_cleared), Toast.LENGTH_LONG).show()
                        onChanged()
                        onDismiss()
                    }) { Text(stringResource(R.string.backup_passphrase_clear), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    )
}
