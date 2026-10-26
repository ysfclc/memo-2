package app.memo.core.catalog

/**
 * Orders GGUF quantisations for picking one file per repository. Lower rank is
 * better: `Q4_K_M > Q4_K_S > Q5_K_M > Q4_0 > Q5_K_S > Q6_K > Q8_0 > IQ* > other`.
 * Unknown labels (for example Unsloth's `Q4_K_XL`) sort after every listed label.
 */
object QuantRanking {

    private val ORDER = listOf("Q4_K_M", "Q4_K_S", "Q5_K_M", "Q4_0", "Q5_K_S", "Q6_K", "Q8_0")
    private const val IQ_RANK = 10
    private const val OTHER_RANK = 20

    /** The last quantisation label in a file name (`Qwen3-1.7B-Q4_K_M.gguf` gives `Q4_K_M`), or null. */
    private val LABEL = Regex("(IQ\\d_[A-Z]+|Q\\d_K_[A-Z]+|Q\\d_K|Q\\d_\\d|BF16|F16|F32)", RegexOption.IGNORE_CASE)

    /** Quantisation label of [fileName], upper-cased, or null when none is present. */
    fun quantOf(fileName: String): String? =
        LABEL.findAll(fileName).lastOrNull()?.value?.uppercase()

    /** Rank of a quantisation label; lower is better. */
    fun rank(quant: String?): Int {
        if (quant == null) return OTHER_RANK
        val index = ORDER.indexOf(quant.uppercase())
        return when {
            index >= 0 -> index
            quant.uppercase().startsWith("IQ") -> IQ_RANK
            else -> OTHER_RANK
        }
    }

    /**
     * The best single-file GGUF among [names], or null when none qualifies.
     * Sharded files (`-00001-of-00002`) are skipped: a shard is not a loadable model.
     * Ties break on the name, so the choice is deterministic.
     */
    fun bestGguf(names: Collection<String>): String? =
        names
            .filter { it.endsWith(".gguf", ignoreCase = true) && !SHARD.containsMatchIn(it) }
            .minWithOrNull(compareBy<String>({ rank(quantOf(it)) }, { it }))

    private val SHARD = Regex("-\\d{5}-of-\\d{5}")
}
