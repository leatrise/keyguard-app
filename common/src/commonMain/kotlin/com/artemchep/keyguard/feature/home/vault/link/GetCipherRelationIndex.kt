package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

interface GetCipherRelationIndex : () -> Flow<CipherRelationIndex>

class GetCipherRelationIndexImpl internal constructor(
    private val getCiphers: GetCiphers,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetCipherRelationIndex {
    constructor(directDI: DirectDI) : this(
        getCiphers = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val sharedFlow = getCiphers()
        .mapLatest(::buildCipherRelationIndex)
        .flowOn(dispatcher)
        .shareIn(
            scope = windowCoroutineScope,
            started = SharingStarted.Lazily,
            replay = 1,
        )

    override fun invoke(): Flow<CipherRelationIndex> = sharedFlow
}
