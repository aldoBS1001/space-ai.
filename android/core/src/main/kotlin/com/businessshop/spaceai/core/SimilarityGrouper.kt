package com.businessshop.spaceai.core

import kotlin.math.abs

/** Sensibilidad configurable por el usuario. Distancia de Hamming máxima sobre dHash de 64 bits. */
enum class Sensitivity(val maxHamming: Int) {
    STRICT(4),
    NORMAL(8),
    RELAXED(12);
}

data class PhotoSignature(
    val item: MediaItem,
    val dHash: Long,
    val quality: QualityMetrics?,
)

data class SimilarGroup(
    val items: List<PhotoSignature>,
    val keeper: PhotoSignature,
    val isBurst: Boolean,
) {
    val candidates: List<PhotoSignature> get() = items.filter { it.item.id != keeper.item.id }
    val reclaimableBytes: Long get() = candidates.sumOf { it.item.sizeBytes }
}

/**
 * Agrupa fotos visualmente similares con un BK-tree (consulta sub-cuadrática),
 * más dos guardas contra falsos positivos:
 * - relación de aspecto compatible (±8 %),
 * - ventana temporal (por defecto 10 min) salvo coincidencia casi exacta (≤ 2 bits),
 *   porque dos fotos de cielo azul de años distintos no son "la misma foto".
 * Las capturas de pantalla se excluyen: interfaces de una misma app se parecen sin ser duplicados.
 */
class SimilarityGrouper(
    private val sensitivity: Sensitivity = Sensitivity.NORMAL,
    private val timeWindowMillis: Long = 10 * 60 * 1000L,
    private val nearExactBits: Int = 2,
    private val aspectTolerance: Double = 0.08,
) {
    fun group(signatures: List<PhotoSignature>, cancel: CancellationSignal = CancellationSignal.NONE): List<SimilarGroup> {
        val eligible = signatures.filter { !it.item.isScreenshot }
        if (eligible.size < 2) return emptyList()
        val uf = UnionFind(eligible.size)
        // 1) Casi-exactas en toda la biblioteca: BK-tree con radio pequeño (muy rápido).
        val tree = BkTree()
        eligible.forEachIndexed { i, sig ->
            if (i % 256 == 0 && cancel.isCancelled()) throw ScanCancelledException()
            for (j in tree.query(sig.dHash, nearExactBits)) if (compatible(sig, eligible[j])) uf.union(i, j)
            tree.add(sig.dHash, i)
        }
        // 2) Similares: solo dentro de la ventana temporal (ventana deslizante sobre fechas ordenadas).
        val dated = eligible.indices.filter { eligible[it].item.dateTakenMillis > 0 }
            .sortedBy { eligible[it].item.dateTakenMillis }
        var start = 0
        for (k in dated.indices) {
            if (k % 256 == 0 && cancel.isCancelled()) throw ScanCancelledException()
            val i = dated[k]
            val ti = eligible[i].item.dateTakenMillis
            while (ti - eligible[dated[start]].item.dateTakenMillis > timeWindowMillis) start++
            for (m in start until k) {
                val j = dated[m]
                if (PerceptualHash.hamming(eligible[i].dHash, eligible[j].dHash) <= sensitivity.maxHamming &&
                    compatible(eligible[i], eligible[j])) uf.union(i, j)
            }
        }
        // Las ráfagas del sistema se agrupan aunque el hash difiera (movimiento entre cuadros).
        eligible.indices.groupBy { eligible[it].item.burstId }
            .filterKeys { it != null }
            .values.forEach { idx -> for (k in 1 until idx.size) uf.union(idx[0], idx[k]) }

        return eligible.indices.groupBy { uf.find(it) }.values
            .filter { it.size > 1 }
            .map { idx ->
                val members = idx.map { eligible[it] }
                val burst = members.all { it.item.burstId != null } && members.map { it.item.burstId }.toSet().size == 1
                SimilarGroup(members, BestShotSelector.choose(members), burst)
            }
            .sortedByDescending { it.reclaimableBytes }
    }

    /** Guarda contra falsos positivos: la relación de aspecto debe coincidir (±8 %). Rotar sí cambia la foto. */
    private fun compatible(a: PhotoSignature, b: PhotoSignature): Boolean {
        val ra = a.item.aspectRatio; val rb = b.item.aspectRatio
        if (ra <= 0 || rb <= 0) return true
        return abs(ra - rb) / maxOf(ra, rb) <= aspectTolerance
    }
}

object BestShotSelector {
    /** Conserva la mejor: favorita > calidad (nitidez/exposición) > resolución > más reciente edición. */
    fun choose(members: List<PhotoSignature>): PhotoSignature =
        members.sortedWith(
            compareByDescending<PhotoSignature> { it.item.isFavorite }
                .thenByDescending { it.quality?.score() ?: 0.0 }
                .thenByDescending { it.item.pixelCount }
                .thenByDescending { it.item.sizeBytes }
                .thenBy { it.item.id }
        ).first()
}

internal class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    private val rank = IntArray(n)
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
        return r
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra == rb) return
        when {
            rank[ra] < rank[rb] -> parent[ra] = rb
            rank[ra] > rank[rb] -> parent[rb] = ra
            else -> { parent[rb] = ra; rank[ra]++ }
        }
    }
}

/** BK-tree sobre distancia de Hamming: búsqueda por radio sin comparar todos contra todos. */
internal class BkTree {
    private class Node(val hash: Long, val index: Int) {
        val children = HashMap<Int, Node>(4)
        val duplicates = ArrayList<Int>(0)
    }
    private var root: Node? = null

    fun add(hash: Long, index: Int) {
        val r = root ?: run { root = Node(hash, index); return }
        var node: Node = r
        while (true) {
            val d = PerceptualHash.hamming(hash, node.hash)
            if (d == 0) { node.duplicates += index; return }
            val child = node.children[d]
            if (child == null) { node.children[d] = Node(hash, index); return }
            node = child
        }
    }

    fun query(hash: Long, radius: Int): List<Int> {
        val out = ArrayList<Int>()
        val r = root ?: return out
        val stack = ArrayDeque<Node>().apply { add(r) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val d = PerceptualHash.hamming(hash, n.hash)
            if (d <= radius) { out += n.index; out += n.duplicates }
            for ((k, child) in n.children) if (k >= d - radius && k <= d + radius) stack.add(child)
        }
        return out
    }
}
