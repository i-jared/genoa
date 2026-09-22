package app.gather.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import app.gather.R

@Composable
fun GenoaIcon(
    name: String,
    label: String?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
) {
    val resource =
        when (name) {
            "home" -> R.drawable.genoa_home
            "video" -> R.drawable.genoa_video
            "profile" -> R.drawable.genoa_profile
            "search" -> R.drawable.genoa_search
            "back" -> R.drawable.genoa_back
            "chevron" -> R.drawable.genoa_chevron
            "close" -> R.drawable.genoa_close
            "add" -> R.drawable.genoa_add
            "play" -> R.drawable.genoa_play
            "check" -> R.drawable.genoa_check
            "clock" -> R.drawable.genoa_clock
            "flip-camera" -> R.drawable.genoa_flip_camera
            "flash" -> R.drawable.genoa_flash
            "bell" -> R.drawable.genoa_bell
            "lock" -> R.drawable.genoa_lock
            "media" -> R.drawable.genoa_media
            "appearance" -> R.drawable.genoa_appearance
            "info" -> R.drawable.genoa_info
            "more" -> R.drawable.genoa_more
            "upload" -> R.drawable.genoa_upload
            "external" -> R.drawable.genoa_external
            "alert" -> R.drawable.genoa_alert
            "signal" -> R.drawable.genoa_signal
            "battery" -> R.drawable.genoa_battery
            else -> R.drawable.genoa_info
        }
    Icon(painterResource(resource), label, modifier, tint)
}
