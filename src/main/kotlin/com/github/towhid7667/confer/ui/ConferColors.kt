package com.github.towhid7667.confer.ui

import com.intellij.ui.JBColor
import java.awt.Color

/** Mirrors the CSS custom-property token table in chat.html §1. Keep both in sync. */
object ConferColors {

    private fun rgb(hex: Int): Color = Color(hex)

    private fun rgba(hex: Int, alpha: Int): Color =
        Color((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF, alpha)

    private fun light(hex: Int) = JBColor(rgb(hex), rgb(hex))
    private fun lightA(hex: Int, alpha: Int) = JBColor(rgba(hex, alpha), rgba(hex, alpha))

    val bg           = JBColor(rgb(0xFAF9F7), rgb(0x1F1E1D))
    val bgElevated   = JBColor(rgb(0xF2F0EC), rgb(0x262524))
    val bgInput      = JBColor(rgb(0xFFFFFF), rgb(0x2B2A28))
    val bgHover      = JBColor(rgb(0xEBE8E3), rgb(0x35332F))
    val bgActive     = JBColor(rgb(0xE2DED8), rgb(0x3E3B37))
    val border       = JBColor(rgb(0xDDD9D2), rgb(0x3A3835))
    val borderSubtle = JBColor(rgb(0xE9E6E1), rgb(0x2E2C2A))

    val fg      = JBColor(rgb(0x2B2A28), rgb(0xE5E2DD))
    val fgMuted = JBColor(rgb(0x6E6A65), rgb(0xA8A29A))
    val fgFaint = JBColor(rgb(0xA8A29A), rgb(0x6E6A65))

    val accent       = JBColor(rgb(0xC05F3C), rgb(0xD97757))
    val accentHover  = JBColor(rgb(0xA94E2E), rgb(0xE08D71))
    val accentMuted  = lightA(0xD97757, 36)
    val accentBorder = lightA(0xD97757, 89)

    val success = JBColor(rgb(0x17845F), rgb(0x4EC9A0))
    val warning = JBColor(rgb(0xB87515), rgb(0xE5A44C))
    val error   = JBColor(rgb(0xC42B2B), rgb(0xF14C4C))
    val info    = light(0x4FA3E3)

    val diffAddBg     = lightA(0x4EC9A0, 36)
    val diffAddFg     = light(0x6FD9B8)
    val diffAddGutter = light(0x2E4E44)
    val diffDelBg     = lightA(0xF14C4C, 33)
    val diffDelFg     = light(0xF58A8A)
    val diffDelGutter = light(0x4E3030)

    val thinkingFg = JBColor(rgb(0x6B5FA8), rgb(0x9B93C9))
    val thinkingBg = lightA(0x9B93C9, 20)

    val userBg     = JBColor(rgb(0xF2F0EC), rgb(0x262524))
    val userAccent = accent
}
