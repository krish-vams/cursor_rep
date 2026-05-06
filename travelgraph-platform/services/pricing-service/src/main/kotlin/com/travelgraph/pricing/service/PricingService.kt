package com.travelgraph.pricing.service

import com.travelgraph.pricing.domain.PriceEntity
import com.travelgraph.pricing.domain.PriceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Price quote returned to GraphQL. All amounts are in the same currency.
 *
 * @property amount Per-night base amount AFTER weekend/holiday uplifts but BEFORE loyalty discount.
 * @property nights Number of nights covered by the quote.
 * @property subtotal `amount * nights` (pre-discount, pre-tax).
 * @property discount Loyalty discount applied to the subtotal (>= 0).
 * @property taxes Taxes applied to (subtotal - discount).
 * @property totalAmount Final amount paid by the guest = subtotal - discount + taxes.
 */
data class PriceQuote(
    val propertyId: UUID,
    val currency: String,
    val amount: BigDecimal,
    val nights: Int,
    val subtotal: BigDecimal,
    val discount: BigDecimal,
    val taxes: BigDecimal,
    val totalAmount: BigDecimal,
    val appliedRules: List<String>,
)

@Service
class PricingService(
    private val repository: PriceRepository,
) {

    @Transactional(readOnly = true)
    suspend fun findBaseRow(propertyId: UUID): PriceEntity? = withContext(Dispatchers.IO) {
        repository.findById(propertyId).orElse(null)
    }

    @Transactional(readOnly = true)
    suspend fun findBaseRows(propertyIds: Collection<UUID>): Map<UUID, PriceEntity> =
        withContext(Dispatchers.IO) {
            repository.findAllByPropertyIdIn(propertyIds).associateBy { it.propertyId }
        }

    /**
     * Compute a [PriceQuote] for the given property and stay. If [checkIn] or [checkOut]
     * are null we quote a single night at today's rate (useful for catalog browsing).
     *
     * Application order (deliberate, documented):
     *   1. Per-night base.
     *   2. + weekend uplift on Friday/Saturday nights.
     *   3. + holiday uplift on the configured holiday calendar.
     *   4. Sum across nights -> subtotal.
     *   5. - loyalty discount applied to subtotal.
     *   6. + tax applied to (subtotal - discount).
     */
    suspend fun quote(
        propertyId: UUID,
        checkIn: LocalDate?,
        checkOut: LocalDate?,
        loyaltyTier: LoyaltyTier = LoyaltyTier.NONE,
    ): PriceQuote? {
        val row = findBaseRow(propertyId) ?: return null
        return quoteFromRow(row, checkIn, checkOut, loyaltyTier)
    }

    fun quoteFromRow(
        row: PriceEntity,
        checkIn: LocalDate?,
        checkOut: LocalDate?,
        loyaltyTier: LoyaltyTier = LoyaltyTier.NONE,
    ): PriceQuote {
        val (start, end) = normaliseDates(checkIn, checkOut)
        val nights = (end.toEpochDay() - start.toEpochDay()).toInt().coerceAtLeast(1)

        val applied = mutableListOf<String>()
        var subtotal = BigDecimal.ZERO

        var perNightSample: BigDecimal = row.basePrice
        for (i in 0 until nights) {
            val night = start.plusDays(i.toLong())
            var nightly = row.basePrice
            if (PricingRules.isWeekend(night)) {
                nightly = nightly.applyPercent(PricingRules.WEEKEND_UPLIFT_PERCENT)
                if (i == 0) applied += "WEEKEND_UPLIFT(+${PricingRules.WEEKEND_UPLIFT_PERCENT}%)"
            }
            if (PricingRules.isHoliday(night)) {
                nightly = nightly.applyPercent(PricingRules.HOLIDAY_UPLIFT_PERCENT)
                if (i == 0) applied += "HOLIDAY_UPLIFT(+${PricingRules.HOLIDAY_UPLIFT_PERCENT}%)"
            }
            if (i == 0) perNightSample = nightly
            subtotal = subtotal.add(nightly)
        }

        val discountPercent = PricingRules.LOYALTY_DISCOUNTS[loyaltyTier] ?: 0.0
        val discount = if (discountPercent > 0.0) {
            applied += "LOYALTY_${loyaltyTier.name}(-$discountPercent%)"
            subtotal.percentOf(discountPercent)
        } else {
            BigDecimal.ZERO
        }

        val taxable = subtotal.subtract(discount)
        val taxes = taxable.multiply(row.taxRate).setScale(2, RoundingMode.HALF_UP)
        val total = taxable.add(taxes).setScale(2, RoundingMode.HALF_UP)

        return PriceQuote(
            propertyId = row.propertyId,
            currency = row.currency,
            amount = perNightSample.setScale(2, RoundingMode.HALF_UP),
            nights = nights,
            subtotal = subtotal.setScale(2, RoundingMode.HALF_UP),
            discount = discount.setScale(2, RoundingMode.HALF_UP),
            taxes = taxes,
            totalAmount = total,
            appliedRules = applied,
        )
    }

    private fun normaliseDates(checkIn: LocalDate?, checkOut: LocalDate?): Pair<LocalDate, LocalDate> {
        // If only one date is provided, quote a single night starting that date.
        // If neither is provided, quote a single night starting today.
        val start = checkIn ?: LocalDate.now()
        val end = when {
            checkOut != null -> checkOut
            checkIn != null -> checkIn.plusDays(1)
            else -> start.plusDays(1)
        }
        require(!end.isBefore(start)) { "checkOut must be on or after checkIn" }
        return start to end
    }

    private fun BigDecimal.applyPercent(pct: Double): BigDecimal =
        this.multiply(BigDecimal.valueOf(1.0 + pct / 100.0))

    private fun BigDecimal.percentOf(pct: Double): BigDecimal =
        this.multiply(BigDecimal.valueOf(pct / 100.0))
}
