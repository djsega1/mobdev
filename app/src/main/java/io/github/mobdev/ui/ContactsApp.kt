package io.github.mobdev.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.mobdev.data.Contact
import io.github.mobdev.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ContactsApp() {
    val context = LocalContext.current
    val repository = remember { ContactsRepository(context) }
    val permission = Manifest.permission.READ_CONTACTS

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionRequested by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var selectedContact by remember { mutableStateOf<Contact?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(hasPermission) {
        contacts = if (hasPermission) {
            withContext(Dispatchers.IO) {
                repository.fetchAllContacts()
            }
        } else {
            emptyList()
        }
    }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                !hasPermission -> {
                    NoPermissionScreen(
                        permissionRequested = permissionRequested,
                        onRequestPermission = {
                            permissionRequested = true
                            permissionLauncher.launch(permission)
                        }
                    )
                }

                contacts.isEmpty() -> {
                    EmptyContactsScreen()
                }

                else -> {
                    ContactsListScreen(
                        contacts = contacts,
                        onContactClick = { selectedContact = it }
                    )
                }
            }
        }
    }

    selectedContact?.let { contact ->
        ContactDetailScreen(
            contact = contact,
            onDismiss = { selectedContact = null }
        )
    }
}