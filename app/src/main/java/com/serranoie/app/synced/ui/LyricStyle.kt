package com.serranoie.app.synced.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.serranoie.app.synced.R
import kotlin.math.ceil

private fun sans(weight: FontWeight, style: FontStyle) = Font(
    R.font.google_sans_flex, weight, style,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.slant(if (style == FontStyle.Italic) -10f else 0f),
    ),
)

val GoogleSans = FontFamily(
    sans(FontWeight.Normal, FontStyle.Normal),
    sans(FontWeight.Normal, FontStyle.Italic),
    sans(FontWeight.SemiBold, FontStyle.Normal),
    sans(FontWeight.SemiBold, FontStyle.Italic),
)

private val LyricShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 8f)

val CurrentLine = TextStyle(
    color = Color.White,
    fontSize = 17.sp,
    fontFamily = GoogleSans,
    fontWeight = FontWeight.SemiBold,
    textAlign = TextAlign.Center,
    shadow = LyricShadow,
)

val OtherLine = TextStyle(
    color = Color.White.copy(alpha = 0.45f),
    fontSize = 14.sp,
    fontFamily = GoogleSans,
    textAlign = TextAlign.Center,
    shadow = LyricShadow,
)

fun glowStyle(color: Color) = SpanStyle(shadow = Shadow(color, blurRadius = 24f))

private val ASIDE = Regex("""\([^()]*\)""")

fun italicizeAsides(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in ASIDE.findAll(text)) {
        append(text, last, m.range.first)
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.value) }
        last = m.range.last + 1
    }
    append(text, last, text.length)
}

fun sweep(text: AnnotatedString, glow: SpanStyle, progress: Float): AnnotatedString = buildAnnotatedString {
    append(text)
    val lit = ceil(progress * text.length).toInt()
    if (lit > 0) addStyle(glow, 0, lit)
}
