package dev.probe.textselection.engine

import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus

data class ContextPackage(
    val selectedText: String?,
    val precedingContext: String?,
    val followingContext: String?,
    val sourcePackage: String?,
    val captureMethod: CaptureMethod,
    val captureStatus: CaptureStatus,
    val contextQuality: ContextQuality,
)
