package io.jirrafe.core.knowledge

import nl.cwts.networkanalysis.Clustering
import nl.cwts.networkanalysis.LeidenAlgorithm
import nl.cwts.networkanalysis.Network
import nl.cwts.util.LargeDoubleArray
import nl.cwts.util.LargeIntArray
import java.util.Random

/**
 * Hierarchical Leiden on the undirected class projection. Level 0 is the finest clustering at
 * modularity resolution 1; each further level re-clusters the reduced network at a lower
 * resolution, so parents are unions of children. The package tree enters as a prior: classes of
 * the same package share a weak edge (2/size per pair), enough to keep small packages together
 * unless the call graph disagrees.
 */
internal object Communities {
    /** Per level, `assignment[i]` is the cluster of level-i node i (level 0 nodes are classes). */
    class Hierarchy(val levels: List<IntArray>) {
        val depth get() = levels.size
        fun leafOf(cls: Int): Int = levels[0][cls]
        fun parentOf(level: Int, cluster: Int): Int = levels[level + 1][cluster]
        fun clustersAt(level: Int): Int = if (levels[level].isEmpty()) 0 else levels[level].max() + 1
    }

    private val RESOLUTIONS = doubleArrayOf(1.0, 0.4, 0.15)

    fun detect(g: ClassGraph, seed: Long = 42): Hierarchy {
        val n = g.classes.size
        if (n == 0) return Hierarchy(emptyList())
        val u = g.undirected()
        val byPackage = g.classes.indices.groupBy { g.packageOf(g.classes[it]) }
        for ((_, members) in byPackage) {
            if (members.size < 2 || members.size > 200) continue
            val w = 2.0 / members.size
            for (a in members.indices) for (b in a + 1 until members.size) {
                u[members[a]].merge(members[b], w, Double::plus)
                u[members[b]].merge(members[a], w, Double::plus)
            }
        }
        val from = LargeIntArray(0L); val to = LargeIntArray(0L); val weights = LargeDoubleArray(0L)
        for (i in 0 until n) for ((j, w) in u[i]) if (i < j) { from.append(i); to.append(j); weights.append(w) }
        var network = Network(n, true, arrayOf(from, to), weights, false, false)

        val levels = ArrayList<IntArray>()
        for (resolution in RESOLUTIONS) {
            val algorithm = LeidenAlgorithm(
                resolution / (2 * network.totalEdgeWeight + network.totalEdgeWeightSelfLinks),
                10, LeidenAlgorithm.DEFAULT_RANDOMNESS, Random(seed),
            )
            val clustering = Clustering(network.nNodes).also { it.initSingletonClusters() }
            algorithm.improveClustering(network, clustering)
            clustering.removeEmptyClusters()
            clustering.orderClustersByNNodes()
            if (levels.isNotEmpty() && clustering.nClusters == network.nNodes) break // nothing merged
            levels += clustering.clusters.copyOf()
            if (clustering.nClusters <= 4) break
            network = network.createReducedNetwork(clustering)
        }
        return Hierarchy(levels)
    }
}
