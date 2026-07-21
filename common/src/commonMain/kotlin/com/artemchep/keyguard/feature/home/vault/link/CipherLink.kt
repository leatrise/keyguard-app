package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.common.model.DSecret
import kotlin.uuid.Uuid

class CipherLink private constructor(
    val remoteCipherId: String,
) {
    override fun toString(): String = "$SCHEME_PREFIX$remoteCipherId"

    companion object {
        const val SCHEME_PREFIX = "keyguard://cipher/"

        fun of(remoteCipherId: String): CipherLink? = kotlin.runCatching {
            CipherLink(Uuid.parse(remoteCipherId).toString())
        }.getOrNull()

        fun parse(value: String?): CipherLink? {
            val normalizedValue = value?.trim().orEmpty()
            if (!normalizedValue.startsWith(SCHEME_PREFIX)) {
                return null
            }
            val remoteCipherId = normalizedValue.removePrefix(SCHEME_PREFIX)
            if (
                remoteCipherId.isEmpty() ||
                remoteCipherId.any { it == '/' || it == '?' || it == '#' }
            ) {
                return null
            }
            return of(remoteCipherId)
        }
    }
}

data class CipherRelation(
    val fieldIndex: Int,
    val label: String,
    val link: CipherLink,
    val cipher: DSecret?,
)

data class CipherRelations(
    val outgoing: List<CipherRelation>,
    val incoming: List<CipherRelation>,
)

class CipherRelationIndex internal constructor(
    val ciphers: List<DSecret>,
    private val targetsByKey: Map<Key, DSecret>,
    private val incomingByKey: Map<Key, List<CipherRelation>>,
) {
    internal data class Key(
        val accountId: String,
        val remoteCipherId: String,
    )

    fun findTarget(
        accountId: String,
        link: CipherLink,
        excludedCipherId: String? = null,
    ): DSecret? = targetsByKey[Key(accountId, link.remoteCipherId)]
        ?.takeIf { target -> target.id != excludedCipherId }

    fun resolve(cipher: DSecret): CipherRelations {
        val outgoing = cipher.fields.mapIndexedNotNull { fieldIndex, field ->
            if (field.type != DSecret.Field.Type.Text) {
                return@mapIndexedNotNull null
            }
            val link = CipherLink.parse(field.value)
                ?: return@mapIndexedNotNull null
            val key = Key(cipher.accountId, link.remoteCipherId)
            CipherRelation(
                fieldIndex = fieldIndex,
                label = field.name.orEmpty(),
                link = link,
                cipher = targetsByKey[key]
                    ?.takeIf { target -> target.id != cipher.id },
            )
        }

        val currentRemoteId = cipher.service.remote?.id
            ?.let(CipherLink::of)
            ?.remoteCipherId
        val incoming = currentRemoteId
            ?.let { remoteCipherId ->
                incomingByKey[Key(cipher.accountId, remoteCipherId)]
            }
            .orEmpty()
            .filter { relation -> relation.cipher?.id != cipher.id }

        return CipherRelations(
            outgoing = outgoing,
            incoming = incoming,
        )
    }
}

fun buildCipherRelationIndex(
    ciphers: List<DSecret>,
): CipherRelationIndex {
    val activeCiphers = ciphers.filter { cipher -> cipher.deletedDate == null }
    val targetsByKey = buildMap {
        activeCiphers.forEach { cipher ->
            val remoteCipherId = cipher.service.remote?.id
                ?.let(CipherLink::of)
                ?.remoteCipherId
                ?: return@forEach
            put(
                CipherRelationIndex.Key(cipher.accountId, remoteCipherId),
                cipher,
            )
        }
    }
    val incomingByKey = mutableMapOf<CipherRelationIndex.Key, MutableList<CipherRelation>>()
    activeCiphers.forEach { source ->
        source.fields.forEachIndexed { fieldIndex, field ->
            if (field.type != DSecret.Field.Type.Text) {
                return@forEachIndexed
            }
            val link = CipherLink.parse(field.value)
                ?: return@forEachIndexed
            val key = CipherRelationIndex.Key(source.accountId, link.remoteCipherId)
            incomingByKey.getOrPut(key) { mutableListOf() } += CipherRelation(
                fieldIndex = fieldIndex,
                label = field.name.orEmpty(),
                link = link,
                cipher = source,
            )
        }
    }
    return CipherRelationIndex(
        ciphers = activeCiphers,
        targetsByKey = targetsByKey,
        incomingByKey = incomingByKey,
    )
}

fun resolveCipherRelations(
    cipher: DSecret,
    ciphers: List<DSecret>,
): CipherRelations = buildCipherRelationIndex(ciphers).resolve(cipher)

fun resolveCipherRelations(
    cipher: DSecret,
    index: CipherRelationIndex,
): CipherRelations = index.resolve(cipher)
