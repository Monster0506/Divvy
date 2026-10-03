package com.monster0506.divvy.split

/** An owner of an item and their relative share weight. */
data class Owner(val personId: String, val weight: Int = 1)

/** A receipt line. [priceCents] is the full line total. */
data class SplitItem(val id: String, val priceCents: Long, val owners: List<Owner>)

data class SplitInput(val items: List<SplitItem>)

/** Each person's share in cents, keyed by person id. */
data class SplitResult(val shares: Map<String, Long>) {
    val totalCents: Long get() = shares.values.sum()
}

object SplitCalculator {

    fun calculate(input: SplitInput): SplitResult {
        val shares = mutableMapOf<String, Long>()
        for (item in input.items) {
            for ((personId, cents) in splitItem(item)) {
                shares[personId] = (shares[personId] ?: 0L) + cents
            }
        }
        return SplitResult(shares.toList().sortedBy { it.first }.toMap())
    }

    /** Splits one item by owner weight; leftover cents go to the largest fractional remainders, ties to the lowest person id. */
    internal fun splitItem(item: SplitItem): Map<String, Long> {
        require(item.priceCents >= 0) { "Item ${item.id} has a negative price" }
        require(item.owners.isNotEmpty()) { "Item ${item.id} has no owners" }
        require(item.owners.all { it.weight > 0 }) { "Item ${item.id} has a non-positive weight" }
        require(item.owners.map { it.personId }.toSet().size == item.owners.size) {
            "Item ${item.id} lists an owner twice"
        }

        val totalWeight = item.owners.sumOf { it.weight.toLong() }
        val owners = item.owners.sortedBy { it.personId }
        val base = owners.map { item.priceCents * it.weight / totalWeight }
        val remainders = owners.map { item.priceCents * it.weight % totalWeight }
        var leftover = item.priceCents - base.sum()

        val extra = LongArray(owners.size)
        val order = owners.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
        for (i in order) {
            if (leftover == 0L) break
            extra[i] = 1
            leftover--
        }
        return owners.indices.associate { owners[it].personId to base[it] + extra[it] }
    }
}
