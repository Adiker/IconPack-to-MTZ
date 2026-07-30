package io.github.adiker.iconpacktomtz.conversion

import io.github.adiker.iconpacktomtz.core.model.ConversionProgress
import io.github.adiker.iconpacktomtz.core.model.ConversionStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ConversionSessionState {
    data object Idle : ConversionSessionState
    data class Running(
        val operationId: String,
        val progress: ConversionProgress,
    ) : ConversionSessionState
    data class Completed(
        val operationId: String,
        val outputUri: String,
        val jsonReportUri: String,
        val textReportUri: String,
        val outputBytes: Long,
        val durationMillis: Long,
        val generatedIcons: Int,
        val skippedEntries: Int,
        val errors: Int,
    ) : ConversionSessionState
    data class Failed(
        val operationId: String,
        val message: String,
        val cancelled: Boolean,
        val jsonReportUri: String? = null,
        val textReportUri: String? = null,
    ) : ConversionSessionState
}

class ConversionSessionStore {
    private val mutableState = MutableStateFlow<ConversionSessionState>(ConversionSessionState.Idle)
    val state: StateFlow<ConversionSessionState> = mutableState.asStateFlow()

    fun start(operationId: String) {
        mutableState.value = ConversionSessionState.Running(
            operationId,
            ConversionProgress(ConversionStage.PREPARING),
        )
    }

    fun progress(operationId: String, progress: ConversionProgress) {
        mutableState.value = ConversionSessionState.Running(operationId, progress)
    }

    fun completed(state: ConversionSessionState.Completed) {
        mutableState.value = state
    }

    fun failed(
        operationId: String,
        message: String,
        cancelled: Boolean,
        jsonReportUri: String? = null,
        textReportUri: String? = null,
    ) {
        mutableState.value = ConversionSessionState.Failed(
            operationId,
            message,
            cancelled,
            jsonReportUri,
            textReportUri,
        )
    }

    fun clear() {
        mutableState.value = ConversionSessionState.Idle
    }
}
