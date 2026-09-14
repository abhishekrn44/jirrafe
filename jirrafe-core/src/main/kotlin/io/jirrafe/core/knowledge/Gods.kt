package io.jirrafe.core.knowledge

import java.util.Random

/** In-degree and betweenness on the class projection; god nodes are the top of both combined. */
internal object Gods {
    class Scores(val inDegree: IntArray, val betweenness: DoubleArray) {
        /** Rank blend in [0, 2]: each measure normalised by its maximum. */
        fun score(i: Int): Double {
            val maxIn = inDegree.max().coerceAtLeast(1)
            val maxB = betweenness.max().coerceAtLeast(1e-9)
            return inDegree[i] / maxIn.toDouble() + betweenness[i] / maxB
        }
    }

    fun compute(g: ClassGraph, seed: Long = 42): Scores {
        val n = g.classes.size
        val inDegree = IntArray(n) { g.dependents[it].size }
        return Scores(inDegree, betweenness(g.undirected(), seed))
    }

    /**
     * Brandes betweenness, unweighted. Exact up to 2000 classes; beyond that 200 sampled sources
     * scaled to the full count.
     */
    private fun betweenness(u: Array<MutableMap<Int, Double>>, seed: Long): DoubleArray {
        val n = u.size
        val cb = DoubleArray(n)
        val sources = if (n <= 2000) (0 until n).toList() else Random(seed).let { r -> List(200) { r.nextInt(n) } }
        val scale = n.toDouble() / sources.size
        for (s in sources) {
            val stack = ArrayDeque<Int>()
            val pred = Array(n) { ArrayList<Int>(2) }
            val sigma = DoubleArray(n); sigma[s] = 1.0
            val dist = IntArray(n) { -1 }; dist[s] = 0
            val queue = ArrayDeque<Int>().also { it += s }
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                stack.addLast(v)
                for (w in u[v].keys) {
                    if (dist[w] < 0) { dist[w] = dist[v] + 1; queue += w }
                    if (dist[w] == dist[v] + 1) { sigma[w] += sigma[v]; pred[w] += v }
                }
            }
            val delta = DoubleArray(n)
            while (stack.isNotEmpty()) {
                val w = stack.removeLast()
                for (v in pred[w]) delta[v] += sigma[v] / sigma[w] * (1 + delta[w])
                if (w != s) cb[w] += delta[w] * scale
            }
        }
        for (i in cb.indices) cb[i] /= 2 // undirected: each pair counted twice
        return cb
    }
}
