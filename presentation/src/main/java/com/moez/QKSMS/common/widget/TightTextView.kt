/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.common.widget

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.text.SpannableString
import java.util.regex.Pattern
import kotlin.math.*

val UTM_REGEX = Pattern.compile("""(\d{1,2}[C-HJ-NP-X])\s+(\d{6})\s+(\d{7})""")
val MGRS_REGEX = Pattern.compile("""(\d{1,2}[C-HJ-NP-X])([A-Z]{3})(\d{2,5})(\d{2,5})""")
val DECIMAL_COORD_REGEX = Pattern.compile("""([-+]?\d{1,2}\.\d+)\s*,\s*([-+]?\d{1,3}\.\d+)""")
val GPS_DMS_ALL: Pattern = Pattern.compile(
    """([NS])\s*([0-9]+)[^\d]+([0-9]+)[^\d]+([0-9]+)[^a-zA-Z0-9]+([EW])\s*([0-9]+)[^\d]+([0-9]+)[^\d]+([0-9]+)"""
)

private fun dmsToDecimal(degrees: String, minutes: String, seconds: String): Float {
    val d = degrees.toFloat()
    val m = minutes.toFloat()
    val s = seconds.toFloat()
    return d + m / 60f + s / 3600f
}

private fun convertUTMToLatLon(zone: String, easting: Int, northing: Int): Pair<Double, Double> {
    val zoneNumber = zone.dropLast(1).toInt()
    val northernHemisphere = zone.last() >= 'N'
    val k0 = 0.9996
    val a = 6378137.0
    val e = 0.0818192
    val e1sq = 0.006739497
    val x = easting - 500000.0
    var y = northing.toDouble()
    if (!northernHemisphere) y -= 10000000.0

    val longOrigin = (zoneNumber - 1) * 6 - 180 + 3
    val m = y / k0
    val mu = m / (a * (1 - pow(e, 2.0) / 4.0 - 3 * pow(e, 4.0) / 64.0 - 5 * pow(e, 6.0) / 256.0))

    val phi1Rad = mu +
        (3 * e1sq / 2 - 27 * pow(e1sq, 3.0) / 32.0) * sin(2 * mu) +
        (21 * pow(e1sq, 2.0) / 16 - 55 * pow(e1sq, 4.0) / 32.0) * sin(4 * mu) +
        (151 * pow(e1sq, 3.0) / 96.0) * sin(6 * mu)

    val n1 = a / sqrt(1 - pow(e * sin(phi1Rad), 2.0))
    val t1 = tan(phi1Rad).pow(2.0)
    val c1 = e1sq * cos(phi1Rad).pow(2.0)
    val r1 = a * (1 - pow(e, 2.0)) / pow(1 - pow(e * sin(phi1Rad), 2.0), 1.5)
    val d = x / (n1 * k0)

    val lat = phi1Rad - (n1 * tan(phi1Rad) / r1) *
        (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * e1sq) * pow(d, 4.0) / 24 +
        (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * e1sq - 3 * c1 * c1) * pow(d, 6.0) / 720)

    val lon = longOrigin + (d - (1 + 2 * t1 + c1) * pow(d, 3.0) / 6 +
        (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * e1sq + 24 * t1 * t1) * pow(d, 5.0) / 120) / cos(phi1Rad)

    return Pair(Math.toDegrees(lat), lon)
}

private fun convertMGRSToLatLon(mgrs: String): Pair<Double, Double> {
    return Pair(0.0, 0.0)
}

class TightTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : QkTextView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val layout = layout ?: return
        if (layout.lineCount <= 1) return

        val maxLineWidth = (0 until layout.lineCount)
            .map(layout::getLineWidth)
            .max() ?: 0f

        val width = Math.ceil(maxLineWidth.toDouble()).toInt() + compoundPaddingLeft + compoundPaddingRight
        if (width < measuredWidth) {
            val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthMeasureSpec))
            super.onMeasure(widthSpec, heightMeasureSpec)
        }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (text == null) {
            super.setText(text, type)
            return
        }

        val allowed = SpannableStringBuilder()
        val matchers = listOf(
            GPS_DMS_ALL.matcher(text),
            DECIMAL_COORD_REGEX.matcher(text),
            UTM_REGEX.matcher(text),
            MGRS_REGEX.matcher(text)
        )

        matchers.forEach { matcher ->
            while (matcher.find()) {
                allowed.append(matcher.group())
                allowed.append("\n")
            }
        }

        val spannable = SpannableString.valueOf(allowed.toString())
        var added = false

        added = Linkify.addLinks(spannable, GPS_DMS_ALL, "geo:0,0?q=", null, null) { matcher, _ ->
            val latDir = matcher.group(1)
            val lat = dmsToDecimal(matcher.group(2), matcher.group(3), matcher.group(4))
            val lonDir = matcher.group(5)
            val lon = dmsToDecimal(matcher.group(6), matcher.group(7), matcher.group(8))
            val finalLat = if (latDir == "S") -lat else lat
            val finalLon = if (lonDir == "W") -lon else lon
            Uri.encode("$finalLat,$finalLon")
        }

        added = added or Linkify.addLinks(spannable, DECIMAL_COORD_REGEX, "geo:0,0?q=", null, null) { matcher, _ ->
            Uri.encode("${matcher.group(1)},${matcher.group(2)}")
        }

        added = added or Linkify.addLinks(spannable, UTM_REGEX, "geo:0,0?q=", null, null) { matcher, _ ->
            val (lat, lon) = convertUTMToLatLon(matcher.group(1), matcher.group(2).toInt(), matcher.group(3).toInt())
            Uri.encode("$lat,$lon")
        }

        added = added or Linkify.addLinks(spannable, MGRS_REGEX, "geo:0,0?q=", null, null) { matcher, _ ->
            val (lat, lon) = convertMGRSToLatLon(matcher.group(0))
            Uri.encode("$lat,$lon")
        }

        if (added) {
            this.movementMethod = LinkMovementMethod.getInstance()
            super.setText(spannable, type)
        } else {
            super.setText("", type) // Strip anything that doesn't match expected input
        }
    }
}
