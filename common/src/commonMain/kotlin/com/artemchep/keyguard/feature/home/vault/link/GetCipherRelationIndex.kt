package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

interface GetCipherRelationIndex : () -> Flow<CipherRelationIndex>

class GetCipherRelationIndexImpl internal constructor(
    private val getCiphers: GetCiphers,
    private val windowCoroutineScope: WindowCoroutineScope,
    getVaultSession: GetVaultSession,
    sessionDi: DI,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetCipherRelationIndex {
    constructor(directDI: DirectDI) : this(
        getCiphers = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
        getVaultSession = directDI.instance(),
        sessionDi = directDI.di,
    )

    private val sessionJob = SupervisorJob(windowCoroutineScope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(
        windowCoroutineScope.coroutineContext + sessionJob,
    )

    init {
        getVaultSession()
            .onEach { session ->
                val isOwningSession =
                    (session as? MasterSession.Key)?.di === sessionDi
                if (!isOwningSession) {
                    sessionJob.cancel()
                }
            }
            .launchIn(sessionScope)
    }

    private val sharedFlow = getCiphers()
        .mapLatest(::buildCipherRelationIndex)
        .flowOn(dispatcher)
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.Lazily,
            replay = 1,
        )

    override fun invoke(): Flow<CipherRelationIndex> = sharedFlow
}
