package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.defaultVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan

data class VaultSearchResult(
    val items: List<VaultItem2.Item>,
    val plan: CompiledQueryPlan?,
)

interface VaultSearchIndex {
    fun compile(
        query: String,
        searchBy: VaultRoute.Args.SearchBy = VaultRoute.Args.SearchBy.ALL,
        qualifierCatalog: VaultSearchQualifierCatalog = defaultVaultSearchQualifierCatalog,
    ): CompiledQueryPlan?

    suspend fun evaluate(
        plan: CompiledQueryPlan?,
        candidates: List<VaultItem2.Item>,
        highlightBackgroundColor: Color,
        highlightContentColor: Color,
    ): List<VaultItem2.Item>

    /** Evaluates domain candidates without creating highlighted presentation models. */
    suspend fun evaluateSources(
        plan: CompiledQueryPlan?,
        candidates: List<DSecret>,
    ): List<DSecret>
}

internal interface SurfaceAwareVaultSearchIndex : VaultSearchIndex {
    fun withSurface(
        surface: String?,
    ): VaultSearchIndex
}
