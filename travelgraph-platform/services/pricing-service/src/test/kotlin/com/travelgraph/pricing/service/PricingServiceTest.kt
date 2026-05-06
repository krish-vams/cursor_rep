package com.travelgraph.pricing.service

import com.travelgraph.pricing.domain.PriceEntity
import com.travelgraph.pricing.domain.PriceRepository
import com.travelgraph.pricing.domain.Season
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Verifies that the weekend, holiday, and loyalty rules in [PricingService] are
 * applied in the documented order with the documented uplift/discount factors.
 *
 * `quoteFromRow` is pure (no repository access), so the repository is mocked but
 * never invoked. This keeps the rules-engine tests independent of JPA.
 */
class PricingServiceTest {

    private val propertyId = UUID.fromString("11111111-1111-1111-1111-000000000001")

    private val baseRow = PriceEntity(
        propertyId = propertyId,
        basePrice = BigDecimal("200.00"),
        taxRate = BigDecimal("0.1000"),
        currency = "USD",
        season = Season.STANDARD,
    )

    private val service = PricingService(mock<PriceRepository>())

    /** Case 1 — single weekday night with no loyalty applies only tax. */
    @Test
    fun `weekday night with no loyalty applies only tax`() {
        val monday = LocalDate.of(2026, 6, 8)
        val q = service.quoteFromRow(baseRow, monday, monday.plusDays(1), LoyaltyTier.NONE)

        assertEquals(BigDecimal("200.00"), q.amount.setScale(2, RoundingMode.HALF_UP))
        assertEquals(1, q.nights)
        assertEquals(BigDecimal("0.00"), q.discount.setScale(2, RoundingMode.HALF_UP))
        // tax = 200 * 0.10 = 20.00; total = 220.00
        assertEquals(BigDecimal("20.00"), q.taxes.setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal("220.00"), q.totalAmount.setScale(2, RoundingMode.HALF_UP))
        assertTrue(q.appliedRules.isEmpty(), "weekday night should apply no rules but got ${q.appliedRules}")
    }

    /** Case 2 — Friday (weekend) night applies 20% uplift. */
    @Test
    fun `weekend night applies 20 percent uplift`() {
        val friday = LocalDate.of(2026, 6, 12)
        val q = service.quoteFromRow(baseRow, friday, friday.plusDays(1), LoyaltyTier.NONE)

        // base 200 * 1.20 = 240; tax 240 * 0.10 = 24; total = 264
        assertEquals(BigDecimal("240.00"), q.subtotal.setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal("24.00"), q.taxes.setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal("264.00"), q.totalAmount.setScale(2, RoundingMode.HALF_UP))
        assertTrue(q.appliedRules.any { it.startsWith("WEEKEND_UPLIFT") })
    }

    /** Case 3 — holiday on a weekend, with PLATINUM loyalty composes all three rules. */
    @Test
    fun `holiday weekend night with platinum loyalty composes weekend uplift holiday uplift and 15 percent discount`() {
        // 2026-12-25 is a Friday and is in the configured holiday set.
        val christmas = LocalDate.of(2026, 12, 25)
        val q = service.quoteFromRow(baseRow, christmas, christmas.plusDays(1), LoyaltyTier.PLATINUM)

        // 200 * 1.20 * 1.35 = 324.00 (nightly subtotal for one night)
        // discount = 324.00 * 0.15 = 48.60
        // taxable = 324.00 - 48.60 = 275.40
        // taxes = 275.40 * 0.10 = 27.54
        // total = 275.40 + 27.54 = 302.94
        assertEquals(BigDecimal("324.00"), q.subtotal.setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal("48.60"), q.discount.setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal("27.54"), q.taxes.setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal("302.94"), q.totalAmount.setScale(2, RoundingMode.HALF_UP))

        assertTrue(q.appliedRules.any { it.startsWith("WEEKEND_UPLIFT") })
        assertTrue(q.appliedRules.any { it.startsWith("HOLIDAY_UPLIFT") })
        assertTrue(q.appliedRules.any { it.startsWith("LOYALTY_PLATINUM") })
    }

    /** Case 4 — multi-night stay applies uplifts per night. */
    @Test
    fun `multi-night stay applies uplifts per night`() {
        // Thu 2026-06-11 -> Sun 2026-06-14: nights are Thu, Fri, Sat. Fri and Sat get the uplift.
        val checkIn = LocalDate.of(2026, 6, 11)
        val checkOut = LocalDate.of(2026, 6, 14)
        val q = service.quoteFromRow(baseRow, checkIn, checkOut, LoyaltyTier.NONE)

        // Thu = 200, Fri = 240, Sat = 240 -> subtotal = 680
        assertEquals(3, q.nights)
        assertEquals(BigDecimal("680.00"), q.subtotal.setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal("68.00"), q.taxes.setScale(2, RoundingMode.HALF_UP))
        assertEquals(BigDecimal("748.00"), q.totalAmount.setScale(2, RoundingMode.HALF_UP))
    }
}
