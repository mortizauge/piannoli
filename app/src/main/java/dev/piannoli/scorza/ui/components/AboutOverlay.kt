package dev.piannoli.scorza.ui.components

import dev.piannoli.ui.components.screenBackground
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.piannoli.scorza.BuildConfig
import dev.piannoli.scorza.R
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.components.BottomBar
import dev.piannoli.ui.components.screenPadding
import dev.piannoli.ui.theme.LocalPiannoliTypography
import dev.piannoli.ui.theme.Spacing
import dev.piannoli.ui.theme.Success

@Composable
fun AboutOverlay(statusMessage: String? = null, updateAvailable: Boolean = false, buttonStyle: ButtonStyle = ButtonStyle()) {
    val typo = LocalPiannoliTypography.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenBackground(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(128.dp),
            )

            Text(
                text = stringResource(R.string.about_title),
                style = typo.titleLarge.copy(color = Color.White)
            )

            Spacer(modifier = Modifier.height(Spacing.Lg))

            Text(
                text = "v${BuildConfig.VERSION_NAME}  •  ${BuildConfig.BUILD_DATE}  •  ${BuildConfig.GIT_HASH}",
                style = typo.bodyMedium.copy(color = Color.White)
            )

            Spacer(modifier = Modifier.height(Spacing.Lg))

            Text(
                text = stringResource(R.string.about_website),
                style = typo.bodyMedium.copy(color = Color.White)
            )

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(Spacing.Md))
                Text(
                    text = statusMessage,
                    style = typo.labelSmall.copy(color = Success)
                )
            }
        }
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
            rightItems = buildList {
                if (updateAvailable) add(buttonStyle.west to stringResource(R.string.label_update))
                add(buttonStyle.north to stringResource(R.string.label_credits))
            }
        )
    }
}
