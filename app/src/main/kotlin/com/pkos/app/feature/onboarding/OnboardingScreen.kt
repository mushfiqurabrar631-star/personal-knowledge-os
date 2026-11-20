package com.pkos.app.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pkos.app.R
import com.pkos.app.ui.components.PrimaryButton
import com.pkos.app.ui.theme.Motion
import com.pkos.app.ui.theme.Spacing
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val accent: ImageVector, val title: Int, val body: Int, val points: List<Int> = emptyList())

private val pages = listOf(
    Page(Icons.Outlined.AutoAwesome, Icons.Outlined.IosShare, R.string.onboarding_1_title, R.string.onboarding_1_body),
    Page(
        Icons.Outlined.PhoneAndroid, Icons.Outlined.CheckCircle, R.string.onboarding_2_title, R.string.onboarding_2_body,
        listOf(R.string.onboarding_2_point_1, R.string.onboarding_2_point_2, R.string.onboarding_2_point_3),
    ),
    Page(Icons.Outlined.Shield, Icons.Outlined.CheckCircle, R.string.onboarding_3_title, R.string.onboarding_3_body),
)

const val ONBOARDING_PAGE_COUNT = 3

/** What PKOS does, local-first explanation, and "permissions only when needed" (spec 2.7). */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = onFinished, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_skip)) }
            else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index -> OnboardingPage(pages[index]) }
        PageDots(current = pager.currentPage, count = pages.size, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(Spacing.xl))
        PrimaryButton(
            text = if (last) R.string.onboarding_start else R.string.onboarding_next,
            onClick = { if (last) onFinished() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun OnboardingPage(page: Page) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Illustration(page.icon, page.accent)
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(page.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (page.points.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xl))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
                page.points.forEach { p ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Spacing.m))
                            Text(stringResource(p), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Simple, calm illustration made from the app's own shapes (no stock imagery). */
@Composable
private fun Illustration(icon: ImageVector, accent: ImageVector) {
    Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), shape = CircleShape, modifier = Modifier.size(176.dp)) {}
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.size(112.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(52.dp))
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 22.dp).size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(accent, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.onboarding_page_indicator, current + 1, count)
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        repeat(count) { i ->
            val selected = i == current
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, tween(Motion.MEDIUM), label = "dot")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                tween(Motion.MEDIUM), label = "dotColor",
            )
            Box(Modifier.height(8.dp).width(width).background(color, CircleShape))
        }
    }
}
