package com.example.pixelpackagetracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Copies exactly the selected text. Android 13+ supplies its own brief confirmation. */
internal fun copyToClipboard(context: Context, label: String, value: String) {
 val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
 clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
 if(Build.VERSION.SDK_INT < 33) Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

/** One header action, independent of expansion; take a stable snapshot when opened. */
@Composable internal fun CopyDetailsButton(title: String, fields: List<CopyField>) {
 val context = LocalContext.current
 var snapshot by remember { mutableStateOf<List<CopyField>?>(null) }
 IconButton(onClick = { snapshot = fields.toList() }) {
  Icon(painterResource(R.drawable.ic_content_copy), contentDescription = title, tint = MaterialTheme.colorScheme.primary)
 }
 snapshot?.let { details ->
  CopySelectionDialog(title, details, onDismiss = { snapshot = null }, onCopy = { value ->
   copyToClipboard(context, title, value)
   snapshot = null
  })
 }
}

@Composable internal fun CopySelectionDialog(title: String, fields: List<CopyField>, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
 var selected by remember(fields) { mutableStateOf(emptySet<String>()) }
 AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
  Column {
   Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(enabled = fields.isNotEmpty(), onClick = { selected = fields.map { it.id }.toSet() }) { Text("Select all") }
    TextButton(enabled = selected.isNotEmpty(), onClick = { selected = emptySet() }) { Text("Clear") }
   }
   LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    items(fields, key = { it.id }) { field ->
     Row(Modifier.fillMaxWidth().toggleable(value = field.id in selected, role = Role.Checkbox, onValueChange = { checked ->
      selected = if(checked) selected + field.id else selected - field.id
     }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = field.id in selected, onCheckedChange = null)
      Column(Modifier.weight(1f).padding(start = 8.dp)) {
       Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
       Text(field.value, style = MaterialTheme.typography.bodyMedium)
      }
     }
    }
   }
  }
 }, confirmButton = {
  TextButton(enabled = selected.isNotEmpty(), onClick = { onCopy(formatCopySelection(fields, selected)) }) { Text("Copy (${selected.size})") }
 }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
