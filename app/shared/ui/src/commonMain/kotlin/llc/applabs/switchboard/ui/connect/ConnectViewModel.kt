package llc.applabs.switchboard.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import llc.applabs.switchboard.domain.AuthRepository
import llc.applabs.switchboard.domain.PasskeyCancelled
import llc.applabs.switchboard.domain.model.HubInfo
import llc.applabs.switchboard.domain.model.InvitePreview
import llc.applabs.switchboard.ui.common.friendly

enum class ConnectStep { Address, Choose, PairOwner, Join, Token }

data class ConnectUiState(
    val step: ConnectStep = ConnectStep.Address,
    val hubUrl: String = "",
    val hub: HubInfo? = null,
    val code: String = "",
    val name: String = "",
    val token: String = "",
    val invite: InvitePreview? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface ConnectUiEvent {
    data class HubUrl(val value: String) : ConnectUiEvent
    data class Code(val value: String) : ConnectUiEvent
    data class Name(val value: String) : ConnectUiEvent
    data class Token(val value: String) : ConnectUiEvent
    /** A pasted or opened invite link: `https://switchboard.applabs.llc/join#hub=…&code=…`. */
    data class InviteLink(val link: String) : ConnectUiEvent
    data object Continue : ConnectUiEvent
    data object ChoosePasskey : ConnectUiEvent
    data object ChooseJoin : ConnectUiEvent
    data object ChooseToken : ConnectUiEvent
    /** The owner adding this phone (or replacing a lost one) with a setup code from the hub machine. */
    data object ChooseSetupCode : ConnectUiEvent
    data object PreviewInvite : ConnectUiEvent
    data object Submit : ConnectUiEvent
    data object Back : ConnectUiEvent
}

/**
 * Onboarding (spec: Onboarding). The hub address first; then the owner pairs with the setup code
 * printed on the hub machine, a member joins with an invite, or anyone signs in with their passkey.
 * Signing in changes [AuthRepository.state], which takes the app to its main screens.
 */
class ConnectViewModel(private val auth: AuthRepository, private val deviceName: String, lastHubUrl: String?) : ViewModel() {
    private val _state = MutableStateFlow(ConnectUiState(hubUrl = lastHubUrl.orEmpty()))
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    fun onEvent(e: ConnectUiEvent) {
        when (e) {
            is ConnectUiEvent.HubUrl ->
                if (e.value.contains("/join") && parseInviteLink(e.value) != null) onEvent(ConnectUiEvent.InviteLink(e.value))
                else _state.update { it.copy(hubUrl = e.value, error = null) }
            is ConnectUiEvent.Code -> _state.update { it.copy(code = e.value, error = null) }
            is ConnectUiEvent.Name -> _state.update { it.copy(name = e.value, error = null) }
            is ConnectUiEvent.Token -> _state.update { it.copy(token = e.value, error = null) }
            is ConnectUiEvent.InviteLink -> parseInviteLink(e.link)?.let { (hub, code) ->
                _state.update { it.copy(hubUrl = hub, code = code, step = ConnectStep.Join, invite = null) }
                probe(then = { onEvent(ConnectUiEvent.PreviewInvite) })
            }
            ConnectUiEvent.Continue -> probe()
            ConnectUiEvent.ChoosePasskey -> run { auth.signInWithPasskey(_state.value.hubUrl, deviceName) }
            ConnectUiEvent.ChooseJoin -> _state.update { it.copy(step = ConnectStep.Join, code = "", invite = null, error = null) }
            ConnectUiEvent.ChooseToken -> _state.update { it.copy(step = ConnectStep.Token, error = null) }
            ConnectUiEvent.ChooseSetupCode -> _state.update { it.copy(step = ConnectStep.PairOwner, code = "", error = null) }
            ConnectUiEvent.PreviewInvite -> run {
                _state.update { it.copy(invite = auth.previewInvite(it.hubUrl, it.code)) }
            }
            ConnectUiEvent.Submit -> submit()
            ConnectUiEvent.Back -> _state.update {
                it.copy(step = when {
                    it.step == ConnectStep.Choose -> ConnectStep.Address
                    it.step == ConnectStep.PairOwner && it.hub?.setupRequired == true -> ConnectStep.Address
                    else -> ConnectStep.Choose
                },
                    error = null, invite = null)
            }
        }
    }

    private fun probe(then: () -> Unit = {}) = run {
        val hub = auth.probe(_state.value.hubUrl)
        _state.update { s ->
            val next = when {
                s.step == ConnectStep.Join -> ConnectStep.Join
                hub.setupRequired -> ConnectStep.PairOwner
                else -> ConnectStep.Choose
            }
            s.copy(hub = hub, step = next)
        }
        then()
    }

    private fun submit() {
        val s = _state.value
        when (s.step) {
            ConnectStep.PairOwner -> run {
                require(s.code.isNotBlank()) { "Enter the setup code shown on the hub machine." }
                require(s.name.isNotBlank()) { "Enter your name." }
                auth.pairOwner(s.hubUrl, s.code, s.name, deviceName)
            }
            ConnectStep.Join -> run {
                if (s.invite == null) { _state.update { it.copy(invite = auth.previewInvite(s.hubUrl, s.code)) }; return@run }
                require(s.name.isNotBlank()) { "Enter your name." }
                auth.join(s.hubUrl, s.code, s.name, deviceName)
            }
            ConnectStep.Token -> run { auth.signInWithToken(s.hubUrl, s.token) }
            else -> probe()
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: PasskeyCancelled) {
                // the person closed the passkey sheet
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendly()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        /** `https://switchboard.applabs.llc/join#hub=<encoded>&code=<code>` → (hub, code). */
        fun parseInviteLink(link: String): Pair<String, String>? {
            val fragment = link.substringAfter('#', "").ifEmpty { link.substringAfter('?', "") }
            val params = fragment.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to decode(it[1]) } }.toMap()
            val hub = params["hub"] ?: return null
            val code = params["code"] ?: return null
            return hub to code
        }

        private fun decode(s: String): String {
            val out = StringBuilder(); var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) { out.append(s.substring(i + 1, i + 3).toInt(16).toChar()); i += 3 }
                else { out.append(if (c == '+') ' ' else c); i++ }
            }
            return out.toString()
        }
    }
}
