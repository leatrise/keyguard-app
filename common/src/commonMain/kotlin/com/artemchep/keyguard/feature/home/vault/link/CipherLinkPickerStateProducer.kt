package com.artemchep.keyguard.feature.home.vault.link

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetVaultSearchIndex
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.home.vault.screen.toVaultItemIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.debounceSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private const val CIPHER_LINK_PICKER_PAGE_SIZE = 100
private const val CIPHER_LINK_PICKER_SEARCH_SURFACE = "cipher-link-picker"

@Composable
fun produceCipherLinkPickerState(
    args: CipherLinkPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherLinkPickerResult>,
): CipherLinkPickerState = with(localDI().direct) {
    produceCipherLinkPickerState(
        args = args,
        transmitter = transmitter,
        getCiphers = instance(),
        getVaultSearchIndex = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
    )
}

@Composable
fun produceCipherLinkPickerState(
    args: CipherLinkPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherLinkPickerResult>,
    getCiphers: GetCiphers,
    getVaultSearchIndex: GetVaultSearchIndex,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
): CipherLinkPickerState = produceScreenState(
    key = "cipher_link_picker",
    initial = CipherLinkPickerState(),
    args = arrayOf(args, getCiphers, getVaultSearchIndex, getAppIcons, getWebsiteIcons),
) {
    val queryHandle = textFieldHandle(
        key = "query",
        initial = "",
    )
    val typeTitles = DSecret.Type.entries
        .associateWith { type -> translate(type.titleH()) }
    val visibleCountFlow = MutableStateFlow(CIPHER_LINK_PICKER_PAGE_SIZE)
    val candidatesFlow = getCiphers()
        .mapLatest { ciphers ->
            filterCipherLinkPickerCiphers(
                ciphers = ciphers,
                accountId = args.accountId,
                excludedCipherId = args.excludedCipherId,
            )
        }
        .flowOn(Dispatchers.Default)
        .shareIn(
            scope = this,
            started = SharingStarted.WhileSubscribed(5000L),
            replay = 1,
        )
    val queryFlow = queryHandle.sink
        .map { queryCell -> queryCell.text.trim() }
        .distinctUntilChanged()
        .onEach {
            visibleCountFlow.value = CIPHER_LINK_PICKER_PAGE_SIZE
        }
        .shareIn(
            scope = this,
            started = SharingStarted.WhileSubscribed(5000L),
            replay = 1,
        )
    val resultsFlow = queryFlow
        .debounceSearch { query -> query }
        .flatMapLatest { query ->
            if (query.isEmpty()) {
                candidatesFlow
            } else {
                combine(
                    getVaultSearchIndex(CIPHER_LINK_PICKER_SEARCH_SURFACE),
                    candidatesFlow,
                ) { searchIndex, candidates ->
                    searchIndex to candidates
                }
                    .mapLatest { (searchIndex, candidates) ->
                        val plan = searchIndex.compile(query)
                        searchIndex.evaluateSources(
                            plan = plan,
                            candidates = candidates,
                        )
                    }
            }
        }
        .flowOn(Dispatchers.Default)
        .shareIn(
            scope = this,
            started = SharingStarted.WhileSubscribed(5000L),
            replay = 1,
        )

    combine(
        queryHandle.sink,
        resultsFlow,
        visibleCountFlow,
        getAppIcons(),
        getWebsiteIcons(),
    ) { queryCell, results, visibleCount, appIcons, websiteIcons ->
        val visibleCiphers = results.take(visibleCount)
        val items = visibleCiphers
            .asSequence()
            .map { cipher ->
                val link = requireNotNull(
                    cipher.service.remote?.id?.let(CipherLink::of),
                )
                CipherLinkPickerState.Item(
                    id = cipher.id,
                    title = cipher.name,
                    text = cipher.login?.username
                        ?.takeIf { it.isNotBlank() }
                        ?: typeTitles.getValue(cipher.type),
                    icon = cipher.toVaultItemIcon(
                        appIcons = appIcons,
                        websiteIcons = websiteIcons,
                    ),
                    onClick = {
                        transmitter(CipherLinkPickerResult.Confirm(link))
                        navigatePopSelf()
                    },
                )
            }
            .toList()

        CipherLinkPickerState(
            query = TextFieldModel(
                text = queryCell.text,
                textRevision = queryCell.revision,
                onChange = queryHandle::onChange,
                onSetText = queryHandle::setText,
            ),
            items = items,
            onLoadMore = if (visibleCiphers.size < results.size) {
                {
                    visibleCountFlow.value =
                        (visibleCountFlow.value + CIPHER_LINK_PICKER_PAGE_SIZE)
                            .coerceAtMost(results.size)
                }
            } else {
                null
            },
            onDeny = {
                transmitter(CipherLinkPickerResult.Deny)
                navigatePopSelf()
            },
        )
    }
}

internal fun filterCipherLinkPickerCiphers(
    ciphers: List<DSecret>,
    accountId: String,
    excludedCipherId: String?,
): List<DSecret> = ciphers
    .asSequence()
    .filter { cipher ->
        cipher.accountId == accountId &&
                cipher.id != excludedCipherId &&
                cipher.deletedDate == null &&
                cipher.service.remote?.id?.let(CipherLink::of) != null
    }
    .sortedWith(
        StringComparatorIgnoreCase<DSecret> { cipher -> cipher.name }
            .thenBy(DSecret::id),
    )
    .toList()
