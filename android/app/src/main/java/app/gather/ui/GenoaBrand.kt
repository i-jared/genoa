package app.gather.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gather.R

@Composable
fun GenoaBrandMark(modifier: Modifier = Modifier) {
    Icon(
        painterResource(R.drawable.genoa_brand_mark),
        null,
        modifier,
        tint = if (isSystemInDarkTheme()) Color(0xFFF5F3EE) else Color(0xFF206F60),
    )
}

@Composable
fun GenoaBrand(size: Dp = 36.dp, style: TextStyle = MaterialTheme.typography.headlineMedium) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GenoaBrandMark(Modifier.size(size))
        Text("Genoa", style = style)
    }
}
