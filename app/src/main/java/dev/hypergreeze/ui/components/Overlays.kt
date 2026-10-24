package dev.hypergreeze.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hypergreeze.ui.theme.Dimens
import dev.hypergreeze.ui.theme.MiuixTheme
import dev.hypergreeze.ui.theme.MiuixType

/**
 * Page shell: large Miuix title that scrolls away, compact 52dp bar that fades in once the content
 * moves. Keeps one implementation for all pages so the header rhythm is identical everywhere.
 */
@Composable
fun PageScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    headerExtra: (@Composable () -> Unit)? = null,
    bottomPadding: Dp = 96.dp,
    content: LazyListScope.() -> Unit,
) {
    val colors = MiuixTheme.colors
    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 64
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(horizontal = Dimens.pagePadding)) {
                    Spacer(Modifier.height(if (onBack != null) 8.dp else 16.dp))
                    if (onBack != null) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(colors.card, CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onBack,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            MiuixIcon(HgIcons.ArrowBack, tint = colors.textPrimary, size = 18.dp)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    MiuixText(title, style = MiuixType.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        MiuixText(
                            subtitle,
                            style = MiuixType.caption,
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    headerExtra?.invoke()
                }
            }
            content()
        }

        AnimatedVisibility(
            visible = collapsed,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .height(Dimens.topBarCollapsed)
                    .padding(horizontal = Dimens.pagePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    MiuixIcon(
                        vector = HgIcons.ArrowBack,
                        tint = colors.textPrimary,
                        size = 18.dp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                MiuixText(
                    text = title,
                    style = MiuixType.titleCollapsed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Bottom sheet. Scrim and card animate separately so the sheet slides while the scrim fades. */
@Composable
fun MiuixBottomSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colors
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(240)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .background(colors.card, Shapes.sheet)
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(Dimens.sheetHandleWidth, Dimens.sheetHandleHeight)
                        .background(colors.divider, CircleShape),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(title, style = MiuixType.titleCollapsed, modifier = Modifier.weight(1f))
                    MiuixIcon(
                        vector = HgIcons.ChevronDown,
                        tint = colors.textSecondary,
                        size = 20.dp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 28.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
fun MiuixTabBar(
    tabs: List<Pair<String, ImageVector>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEachIndexed { index, (label, icon) ->
            val selected = index == selectedIndex
            val tint = if (selected) colors.primary else colors.textHint
            val pillAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(160),
                label = "tab-pill",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(width = 46.dp, height = 26.dp)
                            .background(
                                colors.primary.copy(alpha = 0.10f * pillAlpha),
                                CircleShape,
                            ),
                    )
                    MiuixIcon(vector = icon, tint = tint, size = 22.dp)
                }
                Spacer(Modifier.height(3.dp))
                MiuixText(
                    text = label,
                    style = MiuixType.tiny,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun MiuixSnackbar(
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pagePadding)
            .heightIn(min = 48.dp)
            .background(colors.onSecondaryContainer, Shapes.tiny)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        MiuixText(
            text = message,
            style = MiuixType.caption,
            color = colors.card,
        )
    }
}
