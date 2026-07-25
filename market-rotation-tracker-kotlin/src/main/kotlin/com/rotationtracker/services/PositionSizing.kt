package com.rotationtracker.services

data class PositionSizeGuidance(val minPct: Int, val maxPct: Int, val label: String)

fun getPositionSizeGuidance(conviction: String): PositionSizeGuidance = when (conviction) {
    "high"   -> PositionSizeGuidance(15, 20, "15–20% of account")
    "medium" -> PositionSizeGuidance(8,  12, "8–12% of account")
    "low"    -> PositionSizeGuidance(3,  5,  "3–5% of account")
    else     -> PositionSizeGuidance(0,  0,  "No position — signal is noise-level")
}

fun convictionFromScore(score: Double): String = when {
    score >= 70 -> "high"
    score >= 50 -> "medium"
    score >= 30 -> "low"
    else        -> "noise"
}
