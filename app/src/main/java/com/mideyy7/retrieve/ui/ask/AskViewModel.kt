package com.mideyy7.retrieve.ui.ask

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mideyy7.retrieve.qa.AskEvent
import com.mideyy7.retrieve.qa.AskPipeline
import com.mideyy7.retrieve.qa.CheckedAnswer
import com.mideyy7.retrieve.qa.GenerationStats
import com.mideyy7.retrieve.qa.LiteRtGenerator
import com.mideyy7.retrieve.qa.LlmModel
import com.mideyy7.retrieve.qa.Source
import com.mideyy7.retrieve.retrieveApp
import com.mideyy7.retrieve.search.SearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AskPhase { IDLE, FINDING, LOADING_MODEL, WRITING, DONE, REFUSED, FAILED }

data class AskUiState(
    val question: String = "",
    val phase: AskPhase = AskPhase.IDLE,
    val sources: List<Source> = emptyList(),
    val answer: CheckedAnswer? = null,
    val stats: GenerationStats? = null,
    /** Why there's no answer (refused or failed). */
    val message: String? = null,
    /** The model said the passages don't answer it. */
    val modelDeclined: Boolean = false,
    val modelInstalled: Boolean = true,
) {
    val busy get() = phase == AskPhase.FINDING || phase == AskPhase.LOADING_MODEL || phase == AskPhase.WRITING
}

class AskViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val generator = LiteRtGenerator(app)
    private val pipeline = AskPipeline(
        search = SearchRepository(app.retrieveApp.database, app.retrieveApp.keywordIndex, app.retrieveApp.semanticIndex, { app.retrieveApp.embedder.embed(it) })::search,
        generator = generator,
    )

    private val _state = MutableStateFlow(
        AskUiState(question = saved[KEY_QUESTION] ?: "", modelInstalled = LlmModel.isInstalled(app)),
    )
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var releaseTimer: Job? = null

    fun onQuestionChange(q: String) {
        saved[KEY_QUESTION] = q
        _state.update { it.copy(question = q) }
    }

    fun ask() {
        val q = _state.value.question.trim()
        if (q.isEmpty()) return
        job?.cancel()
        _state.update { AskUiState(question = it.question, phase = AskPhase.FINDING, modelInstalled = LlmModel.isInstalled(getApplication())) }
        if (!_state.value.modelInstalled) {
            _state.update { it.copy(phase = AskPhase.FAILED, message = "The answering model isn't on this device yet.") }
            return
        }
        job = viewModelScope.launch {
            try {
                pipeline.ask(q).collect { e ->
                    when (e) {
                        is AskEvent.Refused -> _state.update { it.copy(phase = AskPhase.REFUSED, message = e.reason) }
                        is AskEvent.Sources -> _state.update {
                            it.copy(sources = e.sources, phase = if (generator.isLoaded) AskPhase.WRITING else AskPhase.LOADING_MODEL)
                        }
                        is AskEvent.Answer -> _state.update {
                            it.copy(
                                answer = e.checked,
                                phase = when {
                                    !e.done -> AskPhase.WRITING
                                    e.modelDeclined -> AskPhase.REFUSED
                                    else -> AskPhase.DONE
                                },
                                stats = e.stats ?: it.stats,
                                modelDeclined = e.modelDeclined,
                                message = if (e.done && e.modelDeclined) "The passages that came closest don't answer this." else it.message,
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = AskPhase.FAILED, message = e.message ?: "Something went wrong while answering.") }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { if (it.busy) it.copy(phase = if (it.answer != null) AskPhase.DONE else AskPhase.IDLE) else it }
    }

    /** The Ask screen is visible again: keep the model. */
    fun onScreenShown() {
        releaseTimer?.cancel()
    }

    /**
     * The Ask screen went away. Free the ~600 MB model if it stays away for a minute: long enough to
     * open a cited slide and come back without a slow reload, short enough not to hold the memory.
     */
    fun onScreenHidden() {
        releaseTimer?.cancel()
        releaseTimer = viewModelScope.launch {
            delay(RELEASE_AFTER_MS)
            job?.cancel()
            generator.close()
        }
    }

    override fun onCleared() {
        generator.close()
    }

    private companion object {
        const val KEY_QUESTION = "question"
        const val RELEASE_AFTER_MS = 60_000L
    }
}
