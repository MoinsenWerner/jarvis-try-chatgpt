package com.jarvis.assistant

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent as activitySetContent
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable

/**
 * Keeps the main activity source independent from Compose import details.
 *
 * Jarvis.kt calls setContent without importing the Activity Compose extension.
 * This package-local bridge makes that call available and delegates to the
 * official androidx.activity implementation.
 */
fun ComponentActivity.setContent(content: @Composable () -> Unit) {
    this.activitySetContent(content = content)
}

/**
 * Package-local wrapper around Material 3's experimental TopAppBar API.
 *
 * Declarations in the current package take precedence over wildcard imports,
 * so the existing Jarvis.kt call resolves to this stable wrapper while the
 * experimental opt-in remains isolated here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    androidx.compose.material3.TopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions
    )
}
