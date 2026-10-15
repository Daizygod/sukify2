package ru.nepalimsya.sukify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/** Простая страница: «назад» + заголовок и список под ним. */
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val navigator = LocalNavigator.current
    Column(modifier.fillMaxSize().background(SukifyColors.Elevated).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = { navigator.back() })
            Text(
                title,
                style = SukifyType.Title,
                color = SukifyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomChromePadding(24.dp)),
            content = content,
        )
    }
}
