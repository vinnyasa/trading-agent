package com.rotationtracker.config

data class FidelityFund(
    val ticker: String,
    val name: String,
    val parentEtf: String,
    val shortTermFee: Boolean,
    val minHoldDays: Int,
)

val FIDELITY_FUNDS: List<FidelityFund> = listOf(
    FidelityFund("FSPTX",  "Fidelity Select Technology",           "XLK",  true,  30),
    FidelityFund("FSELX",  "Fidelity Select Semiconductors",       "SMH",  true,  30),
    FidelityFund("FDCPX",  "Fidelity Select Tech Hardware",        "XLK",  true,  30),
    FidelityFund("FIDSX",  "Fidelity Select Financial Services",   "XLF",  true,  30),
    FidelityFund("FSRBX",  "Fidelity Select Banking",              "XLF",  true,  30),
    FidelityFund("FSPHX",  "Fidelity Select Health Care",          "XLV",  true,  30),
    FidelityFund("FIDRX",  "Fidelity Select Industrials",          "XLI",  true,  30),
    FidelityFund("FSDAX",  "Fidelity Select Defense & Aerospace",  "XLI",  true,  30),
    FidelityFund("FSUTX",  "Fidelity Select Utilities",            "XLU",  true,  30),
    FidelityFund("FSENX",  "Fidelity Select Energy",               "XLE",  true,  30),
    FidelityFund("FSLEX",  "Fidelity Environment & Alt Energy",    "XLU",  true,  30),
    FidelityFund("FRESX",  "Fidelity Real Estate Fund",            "XLRE", true,  30),
    FidelityFund("FSDPX",  "Fidelity Select Materials",            "XLB",  true,  30),
    FidelityFund("FSCSX",  "Fidelity Select IT Services",          "XLK",  true,  30),
    FidelityFund("FBGRX",  "Fidelity Blue Chip Growth",            "QQQ",  false, 0),
)

fun getFundByTicker(ticker: String): FidelityFund? = FIDELITY_FUNDS.find { it.ticker == ticker }

fun getFundsByEtf(etf: String): List<FidelityFund> = FIDELITY_FUNDS.filter { it.parentEtf == etf }
