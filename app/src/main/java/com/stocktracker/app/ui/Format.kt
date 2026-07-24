package com.stocktracker.app.ui

import java.util.Locale

/** Symbole d'affichage d'une devise ("GBp" = pence de Londres chez Yahoo). */
internal fun currencySymbolOf(currency: String): String =
    if (currency == "GBp") "p"
    else when (currency.uppercase()) {
        "EUR" -> "€"
        "USD" -> "$"
        "GBP" -> "£"
        "GBX" -> "p"
        "JPY" -> "¥"
        "CHF" -> "CHF"
        else -> currency
    }

internal fun formatMoney(value: Double, currencySymbol: String): String =
    String.format(Locale.FRANCE, "%,.2f %s", value, currencySymbol)

internal fun formatOrDash(value: Double, currencySymbol: String): String =
    if (value.isNaN()) "—" else formatMoney(value, currencySymbol)

internal fun formatSignedMoney(value: Double, currencySymbol: String): String =
    String.format(Locale.FRANCE, "%+,.2f %s", value, currencySymbol)

internal fun formatSignedPercent(value: Double): String =
    String.format(Locale.FRANCE, "%+.2f %%", value)

internal fun formatQuantity(quantity: Double): String =
    if (quantity == quantity.toLong().toDouble()) {
        quantity.toLong().toString()
    } else {
        String.format(Locale.FRANCE, "%.4f", quantity).trimEnd('0').trimEnd(',')
    }
